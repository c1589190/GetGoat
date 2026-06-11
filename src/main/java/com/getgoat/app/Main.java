package com.getgoat.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getgoat.agent.modes.SimulateMode;
import com.getgoat.map.ConfigManager;
import com.getgoat.map.branch.BranchManager;
import com.getgoat.map.branch.BranchNode;
import com.getgoat.map.branch.BranchTree;
import com.getgoat.map.branch.CommanderAction;
import com.getgoat.map.geometry.SphericalEngine;
import com.getgoat.map.model.*;
import com.getgoat.tools.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.*;

/**
 * Unified entry point for GetGoat.
 *
 *   java -cp ... com.getgoat.app.Main          → HTTP server on :8080
 *   java -cp ... com.getgoat.app.Main --mcp    → MCP stdio server
 *
 * Both share a single AppContext (one terrain grid, one unit store, one branch tree).
 */
public class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PORT = 8080;

    // ---- API safety limits ----
    /** Maximum cells allowed in a single terrain/bundle export (prevents OOM). */
    private static final long MAX_TERRAIN_EXPORT_CELLS = 10_000;
    /** Maximum allowed query radius (km) for radius-based endpoints. */
    private static final double MAX_RADIUS_KM = 500;

    // ---- Shared state ----
    private static AppContext ctx;
    private static Path FRONTEND_DIR;

    public static void main(String[] args) throws Exception {
        // Resolve frontend dir
        FRONTEND_DIR = resolveFrontendDir();

        // MCP mode
        if (args.length > 0 && "--mcp".equals(args[0])) {
            runMcp();
            return;
        }

        // HTTP server mode
        runHttp();
    }

    // ========================================================================
    //  HTTP Server Mode
    // ========================================================================

    private static void runHttp() throws Exception {
        ctx = new AppContext();
        ctx.initialize();

        // Load units from workspace
        String wsDir = ConfigManager.getProperty("workspace.dir", null);
        if (wsDir != null && !wsDir.isEmpty()) {
            loadUnitsFromWorkspace(wsDir);
            // Propagate workspace path to terrain override store
            if (ctx.branchManager.isWorkspaceReady()) {
                ctx.mapManager.setWorkspaceForOverrides(ctx.branchManager.getWorkspaceDir());
            }
        }

        createDemoRegions();

        LOG.info("=== Verification ===");
        runVerification();

        // Start HTTP server
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Main::handleRoot);
        server.createContext("/api/", Main::handleApi);
        server.createContext("/api/tools", Main::handleTools);
        server.createContext("/api/map/terrain-image", Main::handleTerrainImage);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        LOG.info("Server started at http://localhost:" + PORT);
        LOG.info("Open http://localhost:" + PORT + " in your browser.");
    }

    // ========================================================================
    //  MCP stdio Mode
    // ========================================================================

    private static void runMcp() throws Exception {
        // Route java.util.logging to stderr
        Logger rootLog = Logger.getLogger("");
        for (Handler h : rootLog.getHandlers()) rootLog.removeHandler(h);
        StreamHandler sh = new StreamHandler(System.err, new SimpleFormatter());
        sh.setLevel(Level.INFO);
        rootLog.addHandler(sh);
        rootLog.setLevel(Level.INFO);

        ctx = new AppContext();
        ctx.initializeLight();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out), true);

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            try {
                JsonNode req = MAPPER.readTree(line);
                String method = req.has("method") ? req.get("method").asText() : "";
                String id = req.has("id") && !req.get("id").isNull() ? req.get("id").toString() : null;

                switch (method) {
                    case "initialize" -> out.println(jsonRpc(id, handleMcpInit(req)));
                    case "notifications/initialized" -> {}
                    case "tools/list" -> out.println(jsonRpc(id, handleMcpToolsList()));
                    case "tools/call" -> out.println(jsonRpc(id, handleMcpToolCall(req)));
                    default -> out.println(jsonRpcError(id, -32601, "Method not found: " + method));
                }
            } catch (Exception e) {
                LOG.warning("Error: " + e.getMessage());
                out.println(jsonRpcError(null, -32700, "Parse error: " + e.getMessage()));
            }
        }
    }

    private static JsonNode handleMcpInit(JsonNode req) {
        ObjectNode caps = MAPPER.createObjectNode();
        caps.set("tools", MAPPER.createObjectNode());
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.set("capabilities", caps);
        ObjectNode info = MAPPER.createObjectNode();
        info.put("name", "GetGoat Map");
        info.put("version", "0.1.0");
        result.set("serverInfo", info);
        return result;
    }

    private static JsonNode handleMcpToolsList() {
        ArrayNode tools = MAPPER.createArrayNode();
        for (ToolUnit t : ctx.toolRegistry.all()) {
            ObjectNode tool = MAPPER.createObjectNode();
            tool.put("name", t.getName());
            JsonNode def = t.getDefinition();
            if (def.has("description")) tool.put("description", def.get("description").asText());
            if (def.has("parameters")) tool.set("inputSchema", def.get("parameters"));
            tools.add(tool);
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private static JsonNode handleMcpToolCall(JsonNode req) {
        JsonNode params = req.get("params");
        String toolName = params.has("name") ? params.get("name").asText() : "";
        JsonNode args = params.has("arguments") ? params.get("arguments") : MAPPER.createObjectNode();

        ToolUnit tool = ctx.toolRegistry.get(toolName);
        if (tool == null) {
            ObjectNode err = MAPPER.createObjectNode();
            err.put("code", -32602).put("message", "Unknown tool: " + toolName);
            ObjectNode out = MAPPER.createObjectNode();
            out.set("error", err);
            return out;
        }
        try {
            String text = tool.execute(args).toString();
            ObjectNode content = MAPPER.createObjectNode();
            content.put("type", "text").put("text", text);
            ArrayNode arr = MAPPER.createArrayNode();
            arr.add(content);
            ObjectNode out = MAPPER.createObjectNode();
            out.set("content", arr);
            return out;
        } catch (Exception e) {
            ObjectNode err = MAPPER.createObjectNode();
            err.put("code", -32000).put("message", "Tool error: " + e.getMessage());
            ObjectNode out = MAPPER.createObjectNode();
            out.set("error", err);
            return out;
        }
    }

    private static String jsonRpc(String id, JsonNode result) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("jsonrpc", "2.0");
        if (id != null) {
            try { r.set("id", MAPPER.readTree(id)); }
            catch (Exception e) { r.put("id", id); }
        }
        r.set("result", result);
        return r.toString();
    }
    private static String jsonRpcError(String id, int code, String message) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("jsonrpc", "2.0");
        if (id != null) {
            try { r.set("id", MAPPER.readTree(id)); }
            catch (Exception e) { r.put("id", id); }
        }
        ObjectNode e = MAPPER.createObjectNode();
        e.put("code", code).put("message", message);
        r.set("error", e);
        return r.toString();
    }

    // ========================================================================
    //  HTTP Handlers (consolidated from MapDemo)
    // ========================================================================

    private static void handleRoot(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = FRONTEND_DIR.resolve(path.substring(1));
        if (Files.exists(file) && !Files.isDirectory(file)) {
            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", getContentType(path));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } else {
            String nf = "404: " + path;
            exchange.sendResponseHeaders(404, nf.length());
            exchange.getResponseBody().write(nf.getBytes());
        }
        exchange.getResponseBody().close();
    }

    private static void handleApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String response;
        String contentType = "application/json";

        try {
            // Workspace routes
            if (path.equals("/api/workspaces")) {
                if ("POST".equals(exchange.getRequestMethod())) {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    var n = MAPPER.readTree(body);
                    String name = n.has("name") ? n.get("name").asText() : null;
                    if (name == null || name.isEmpty()) {
                        response = "{\"error\":\"name required\"}";
                    } else {
                        response = ctx.branchManager.createWorkspace(name);
                    }
                } else {
                    response = ctx.branchManager.listWorkspaces();
                }
            } else if (path.startsWith("/api/workspaces/") && path.length() > 16) {
                String sub = path.substring(16);
                if (sub.endsWith("/load")) {
                    String wsName = sub.substring(0, sub.length() - 5);
                    if ("POST".equals(exchange.getRequestMethod())) {
                        response = ctx.branchManager.switchWorkspace(wsName);
                    } else {
                        response = "{\"error\":\"Use POST to load workspace\"}";
                    }
                } else if ("DELETE".equals(exchange.getRequestMethod())) {
                    response = ctx.branchManager.deleteWorkspace(sub);
                } else {
                    response = "{\"error\":\"unknown workspace sub-path\"}";
                }
            } else if (path.startsWith("/api/map/units/") && path.length() > 15
                    && !path.equals("/api/map/units/sources")
                    && !path.equals("/api/map/units/batch")) {
                response = handleUnitSubPath(path, exchange);
            // Commander sub-paths
            } else if (path.startsWith("/api/commander/") && path.length() > 15) {
                response = handleCommanderPath(path, exchange);
            // Intel sub-paths
            } else if (path.startsWith("/api/intel/") && path.length() > 11
                    && !path.equals("/api/intel/sides")) {
                response = handleIntelPath(path);
            // Branch sub-paths
            } else if (path.startsWith("/api/branches/") && path.length() > 14) {
                response = handleBranchPath(path, exchange);
            } else {
                response = handleFlatApi(path, exchange);
            }
        } catch (Exception e) {
            response = "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }

        byte[] bytes = response.getBytes();
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    // ---- Unit sub-path ----

    private static String handleUnitSubPath(String path, HttpExchange exchange) throws IOException {
        String code = java.net.URLDecoder.decode(path.substring(15), "UTF-8");
        String method = exchange.getRequestMethod();
        if ("DELETE".equals(method)) return "{\"deleted\":" + ctx.unitsManager.delete(code) + "}";
        if ("PUT".equals(method)) {
            String q = exchange.getRequestURI().getQuery();
            double lat = qp(q, "lat", Double.NaN), lng = qp(q, "lng", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lng)) return "{\"error\":\"lat,lng required\"}";
            ctx.unitsManager.move(code, lat, lng);
            return ctx.unitsManager.exportOneJson(code);
        }
        if ("PATCH".equals(method)) {
            String body = new String(exchange.getRequestBody().readAllBytes());
            var unit = ctx.unitsManager.get(code);
            if (unit == null) return "{\"error\":\"unit not found\"}";
            var n = MAPPER.readTree(body);
            if (n.has("name")) unit.setName(n.get("name").asText());
            if (n.has("type")) unit.setType(n.get("type").asText());
            if (n.has("status")) unit.setStatus(n.get("status").asText());
            if (n.has("source")) unit.setSource(n.get("source").asText());
            if (n.has("color")) unit.setColor(n.get("color").asText());
            if (n.has("icon")) unit.setIcon(n.get("icon").asText());
            if (n.has("description")) unit.setDescription(n.get("description").asText());
            if (n.has("strength")) unit.setStrength(n.get("strength").asInt());
            if (n.has("maxStrength")) unit.setMaxStrength(n.get("maxStrength").asInt());
            if (n.has("visibleTo") && n.get("visibleTo").isArray()) {
                var v = new java.util.LinkedHashSet<String>();
                for (var item : n.get("visibleTo")) v.add(item.asText());
                unit.setVisibleTo(v);
            }
            return ctx.unitsManager.exportOneJson(code);
        }
        return ctx.unitsManager.exportOneJson(code);
    }

    // ---- Commander path ----

    private static String handleCommanderPath(String path, HttpExchange exchange) throws IOException {
        String sub = path.substring(15);
        String[] parts = sub.split("/");
        if (parts.length < 1) return "{\"error\":\"invalid path\"}";
        String side = parts[0];
        if (!"nationalist".equals(side) && !"japanese".equals(side) && !"cpc".equals(side))
            return "{\"error\":\"unknown side: " + side + "\"}";

        if (parts.length == 2 && "prompt".equals(parts[1])) {
            if ("PUT".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes());
                ctx.agentManager.setSystemPrompt(side, body);
                return "{\"ok\":true}";
            }
            return ctx.agentManager.getSystemPrompt(side);
        }
        if (parts.length == 2 && "context".equals(parts[1])) {
            String q = exchange.getRequestURI().getQuery();
            String treeId = qps(q, "tree", null), nodeId = qps(q, "node", null);
            String guidance = qps(q, "guidance", null);
            if (treeId == null || nodeId == null) return "{\"error\":\"tree,node required\"}";
            return ctx.agentManager.buildContext(side, treeId, nodeId, guidance);
        }
        if (parts.length == 2 && "deploy".equals(parts[1]) && "POST".equals(exchange.getRequestMethod())) {
            String q = exchange.getRequestURI().getQuery();
            String treeId = qps(q, "tree", null), nodeId = qps(q, "node", null);
            String rawBody = new String(exchange.getRequestBody().readAllBytes());
            String guidance = null;
            try {
                var bodyNode = MAPPER.readTree(rawBody);
                guidance = bodyNode.has("guidance") ? bodyNode.get("guidance").asText() : null;
            } catch (Exception e) { guidance = rawBody; }
            if (treeId == null || nodeId == null) return "{\"error\":\"tree,node required\"}";
            try {
                CommanderAction action = ctx.agentManager.executeFullRound(side, treeId, nodeId, guidance, 32);
                var resp = MAPPER.createObjectNode();
                resp.put("ok", true);
                resp.put("source", action.source);
                resp.put("rationale", action.rationale != null ? action.rationale : "");
                resp.put("guidanceAssessment", action.guidanceAssessment != null ? action.guidanceAssessment : "");
                resp.put("risks", action.risks != null ? action.risks : "");
                resp.set("finalPlan", action.finalPlan != null ? action.finalPlan : MAPPER.createArrayNode());
                resp.put("subRounds", action.subRounds.size());
                return resp.toString();
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage().replace("\"","'") + "\"}";
            }
        }
        // Feedback, guidance endpoints
        String q = exchange.getRequestURI().getQuery();
        String treeId = qps(q, "tree", null), nodeId = qps(q, "node", null);
        if (parts.length == 2 && "feedback".equals(parts[1]) && "POST".equals(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes());
            if (treeId == null || nodeId == null) return "{\"error\":\"tree,node required\"}";
            ctx.agentManager.submitFeedback(side, treeId, nodeId, body);
            return "{\"ok\":true}";
        }
        if (parts.length == 2 && "guidance".equals(parts[1])) {
            if (treeId == null || nodeId == null) return "{\"error\":\"tree,node required\"}";
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes());
                ctx.agentManager.setGuidance(side, treeId, nodeId, body);
                return "{\"ok\":true}";
            }
            var tree = ctx.branchManager.getTree(treeId);
            if (tree == null) return "null";
            var node = tree.findNode(nodeId);
            if (node == null) return "null";
            var action = node.getCommanderAction(side);
            return action != null ? "\"" + esc(action.guidance) + "\"" : "null";
        }
        return "{\"error\":\"unknown commander endpoint\"}";
    }

    // ---- Intel path ----

    private static String handleIntelPath(String path) {
        String sub = path.substring(11);
        String[] parts = sub.split("/");
        if (parts.length < 3) return "{\"error\":\"need /api/intel/{treeId}/{nodeId}/{side}\"}";
        var tree = ctx.branchManager.getTree(parts[0]);
        if (tree == null) return "{\"error\":\"tree not found\"}";
        var node = tree.findNode(parts[1]);
        if (node == null) return "{\"error\":\"node not found\"}";
        var intelMap = node.getSideIntelMap(parts[2]);
        if (intelMap == null) return "{\"entries\":[],\"exploredBounds\":[],\"side\":\"" + esc(parts[2]) + "\",\"roundNumber\":0}";
        try { return MAPPER.writeValueAsString(intelMap); }
        catch (Exception e) { return "{\"error\":\"serialize failed\"}"; }
    }

    // ---- Branch path ----

    private static String handleBranchPath(String path, HttpExchange exchange) throws IOException {
        String sub = path.substring(14);
        String[] parts = sub.split("/");
        if (parts.length < 1) return "{\"error\":\"invalid branch path\"}";
        String treeId = parts[0];

        if (parts.length == 1) {
            if ("DELETE".equals(exchange.getRequestMethod())) return "{\"deleted\":" + ctx.branchManager.deleteTree(treeId) + "}";
            return ctx.branchManager.exportTreeJson(treeId);
        }
        if (parts.length == 2 && "flat".equals(parts[1])) return ctx.branchManager.exportFlatListJson(treeId);
        if (parts.length == 2 && "reload".equals(parts[1])) return ctx.branchManager.reloadWorkspace();
        if ("nodes".equals(parts[1]) && parts.length == 2 && "POST".equals(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes());
            var n = MAPPER.readTree(body);
            String pid = n.has("parentId") ? n.get("parentId").asText() : "root";
            String name = n.has("name") ? n.get("name").asText() : "Round";
            int round = n.has("round") ? n.get("round").asInt() : 1;
            String strategy = n.has("strategy") ? n.get("strategy").asText() : "historical";
            String outcome = n.has("outcome") ? n.get("outcome").asText() : "";
            var node = ctx.branchManager.addRound(treeId, pid, name, round, strategy, outcome);
            if (node == null) return "{\"error\":\"parent not found\"}";
            return "{\"ok\":true,\"nodeId\":\"" + node.getId() + "\",\"round\":" + round + "}";
        }
        if ("changes".equals(parts[1]) && parts.length >= 3) return ctx.branchManager.exportChangesJson(treeId, parts[2]);
        if ("moves".equals(parts[1]) && parts.length >= 3) return ctx.branchManager.exportMovesFromSnapshots(treeId, parts[2]);
        if ("apply".equals(parts[1]) && parts.length >= 3) {
            boolean ok = ctx.branchManager.applyNode(treeId, parts[2]);
            return ok ? "{\"ok\":true}" : "{\"error\":\"node not found\"}";
        }
        if ("nodes".equals(parts[1]) && parts.length >= 3) {
            String nodeId = parts[2];
            if ("DELETE".equals(exchange.getRequestMethod())) return "{\"deleted\":" + ctx.branchManager.deleteNode(treeId, nodeId) + "}";
            if ("PATCH".equals(exchange.getRequestMethod())) {
                String q = exchange.getRequestURI().getQuery();
                return "{\"updated\":" + ctx.branchManager.updateNode(treeId, nodeId,
                    qps(q, "name", null), qps(q, "strategy", null), qps(q, "outcome", null)) + "}";
            }
            return ctx.branchManager.exportNodeJson(treeId, nodeId);
        }
        return "{\"error\":\"unknown branch path: " + path + "\"}";
    }

    // ---- Flat API paths ----

    private static String handleFlatApi(String path, HttpExchange exchange) throws IOException {
        return switch (path) {
            case "/api/map/stats" -> ctx.mapManager.exportStatsJson();
            case "/api/map/terrain" -> {
                String q = exchange.getRequestURI().getQuery();
                GeoBounds bounds = parseBounds(q);
                long est = estimateCellCount(bounds, ctx.mapManager.getTerrainGrid().getCellSizeDegrees());
                if (est > MAX_TERRAIN_EXPORT_CELLS)
                    yield "{\"error\":\"Bounds too large: ~" + est + " cells exceeds max " + MAX_TERRAIN_EXPORT_CELLS + ". Narrow your bounds.\"}";
                yield ctx.mapManager.exportTerrainTileJson(bounds);
            }
            case "/api/map/regions" -> ctx.mapManager.exportRegionsGeoJson();
            case "/api/map/labels" -> ctx.mapManager.exportAllLabelsJson();
            case "/api/map/annotations" -> ctx.mapManager.exportAnnotationsGeoJson(GeoBounds.world());
            case "/api/map/bundle" -> {
                String q = exchange.getRequestURI().getQuery();
                GeoBounds bounds = parseBounds(q);
                long est = estimateCellCount(bounds, ctx.mapManager.getTerrainGrid().getCellSizeDegrees());
                if (est > MAX_TERRAIN_EXPORT_CELLS)
                    yield "{\"error\":\"Bounds too large: ~" + est + " cells exceeds max " + MAX_TERRAIN_EXPORT_CELLS + ". Narrow your bounds.\"}";
                yield ctx.mapManager.exportFullMapBundle(bounds);
            }
            case "/api/map/distance" -> handleDistance(exchange.getRequestURI().getQuery());
            case "/api/map/radius" -> handleRadius(exchange.getRequestURI().getQuery());
            case "/api/map/radius-enhanced" -> handleRadiusEnhanced(exchange.getRequestURI().getQuery());
            case "/api/map/neighbors" -> handleNeighbors(exchange.getRequestURI().getQuery());
            case "/api/map/path" -> handlePath(exchange.getRequestURI().getQuery());
            case "/api/map/grid-path" -> handleGridPath(exchange.getRequestURI().getQuery());
            case "/api/map/mark-segment" -> handleMarkSegment(exchange.getRequestURI().getQuery());
            case "/api/map/roads-geojson" -> ctx.mapManager.exportRoadsGeoJson();
            case "/api/map/roads-in-radius" -> {
                String q = exchange.getRequestURI().getQuery();
                yield ctx.mapManager.exportRoadsInRadius(qp(q, "lat", 0), qp(q, "lng", 0), qp(q, "r", 100));
            }
            case "/api/map/rivers-in-radius" -> {
                String q = exchange.getRequestURI().getQuery();
                yield ctx.mapManager.exportRiversInRadius(qp(q, "lat", 0), qp(q, "lng", 0), qp(q, "r", 100));
            }
            case "/api/map/province-at" -> {
                String q = exchange.getRequestURI().getQuery();
                yield ctx.mapManager.exportProvinceWithCells(qp(q, "lat", 0), qp(q, "lng", 0), qp(q, "res", 0.25));
            }
            case "/api/map/terrain-cells" -> {
                String q = exchange.getRequestURI().getQuery();
                yield ctx.mapManager.exportTerrainCellsInRadius(qp(q, "lat", 0), qp(q, "lng", 0), qp(q, "r", 100), qp(q, "res", 0));
            }
            case "/api/map/debug-pipeline" -> {
                String q = exchange.getRequestURI().getQuery();
                yield ctx.mapManager.debugClassificationPipeline(qp(q, "lat", 23.13), qp(q, "lng", 113.26));
            }
            case "/api/map/cache-all" -> ctx.mapManager.cacheAll();
            case "/api/map/cache-status" -> "{\"exists\":" + ctx.mapManager.isCacheExists() + "}";
            case "/api/map/units" -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    yield handleUnitCreate(body);
                }
                String q = exchange.getRequestURI().getQuery();
                String viewedBy = qps(q, "viewedBy", null), treeId = qps(q, "tree", null), nodeId = qps(q, "node", null);
                if (viewedBy != null && treeId != null && nodeId != null) yield exportIntelFilteredUnits(treeId, nodeId, viewedBy);
                if (treeId != null && nodeId != null) yield ctx.branchManager.exportUnitSnapshotsJson(treeId, nodeId);
                String src = qps(q, "source", null), status = qps(q, "status", null), name = qps(q, "name", null), search = qps(q, "search", null);
                if (search != null) yield ctx.unitsManager.exportJson(ctx.unitsManager.search(search));
                if (status != null) yield ctx.unitsManager.exportJson(ctx.unitsManager.listByStatus(status));
                if (name != null) yield ctx.unitsManager.exportJson(ctx.unitsManager.listByName(name));
                yield src != null ? ctx.unitsManager.exportJson(ctx.unitsManager.listBySource(src)) : ctx.unitsManager.exportAllJson();
            }
            case "/api/map/units/sources" -> {
                try { yield "{\"sources\":" + MAPPER.writeValueAsString(ctx.unitsManager.listSources()) + "}"; }
                catch (Exception e) { yield "{\"error\":\"json error\"}"; }
            }
            case "/api/map/units/batch" -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    yield handleUnitBatch(body);
                }
                if ("DELETE".equals(exchange.getRequestMethod())) {
                    int n = ctx.unitsManager.count(); ctx.unitsManager.deleteAll();
                    yield "{\"ok\":true,\"deleted\":" + n + "}";
                }
                yield "{\"error\":\"Use POST/DELETE\"}";
            }
            case "/api/branches" -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    var n = MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes()));
                    String name = n.has("name") ? n.get("name").asText() : "Untitled";
                    var tree = ctx.branchManager.createTree(name);
                    yield "{\"ok\":true,\"treeId\":\"" + tree.getId() + "\"}";
                }
                yield ctx.branchManager.exportTreeListJson();
            }
            case "/api/workspace/save" -> {
                Path wp = ctx.branchManager.getWorkspaceDir();
                if (wp == null) yield "{\"error\":\"no active workspace\"}";
                Files.createDirectories(wp);
                Files.writeString(wp.resolve("units.json"), ctx.unitsManager.exportAllJson());
                ctx.branchManager.saveToDisk();
                yield "{\"ok\":true,\"units\":" + ctx.unitsManager.count() + "}";
            }
            case "/api/workspace/load" -> {
                Path wp = ctx.branchManager.getWorkspaceDir();
                if (wp == null) yield "{\"error\":\"no active workspace\"}";
                loadUnitsFromWorkspace(wp.toString());
                yield "{\"ok\":true,\"units\":" + ctx.unitsManager.count() + "}";
            }
            case "/api/simulate" -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    String q = exchange.getRequestURI().getQuery();
                    yield handleSimulate(qps(q, "tree", null), qps(q, "node", null));
                }
                yield "{\"error\":\"Use POST\"}";
            }
            case "/api/map/terrain-at" -> {
                String q = exchange.getRequestURI().getQuery();
                double lat = qp(q, "lat", 0), lng = qp(q, "lng", 0);
                var cell = ctx.mapManager.getTerrainAt(lat, lng);
                if (cell == null) yield "{\"error\":\"no data\"}";
                yield String.format("{\"lat\":%.4f,\"lng\":%.4f,\"terrain\":\"%s\",\"elevation\":%.0f,\"row\":%d,\"col\":%d,\"color\":\"%s\"}",
                    lat, lng, cell.getTerrain().getDisplayName(), cell.getElevationMeters(),
                    cell.getRow(), cell.getCol(), cell.getColorHex());
            }
            case "/api/map/cell" -> {
                String q = exchange.getRequestURI().getQuery();
                double lat = qp(q, "lat", 0), lng = qp(q, "lng", 0);
                String fmt = qps(q, "format", null);
                if ("json".equals(fmt)) {
                    // Full JSON
                    TerrainCell cell = ctx.mapManager.getTerrainAt(lat, lng);
                    if (cell == null) yield "{\"error\":\"no data\"}";
                    var obj = MAPPER.createObjectNode();
                    obj.put("lat", Math.round(lat * 10000.0) / 10000.0);
                    obj.put("lng", Math.round(lng * 10000.0) / 10000.0);
                    obj.put("row", cell.getRow());
                    obj.put("col", cell.getCol());
                    obj.put("elevation", (int) cell.getElevationMeters());
                    obj.put("terrain", cell.getTerrain().getDisplayName());
                    obj.put("terrainCode", TerrainCell.terrainCode(cell.getTerrain()));
                    obj.put("color", cell.getColorHex());
                    obj.put("isWater", cell.isWater());
                    obj.put("movementPenalty", cell.getTerrain().movementPenalty());
                    // Check override
                    var store = ctx.mapManager.getOverrideStore();
                    if (store.isReady()) {
                        var ov = store.get(cell.getRow(), cell.getCol());
                        if (ov != null) {
                            var o = obj.putObject("override");
                            if (ov.terrain() != null) o.put("terrain", ov.terrain().getDisplayName());
                            if (ov.elevation() != null) o.put("elevation", ov.elevation());
                        }
                    }
                    yield obj.toString();
                }
                // Default: compact string
                yield ctx.mapManager.buildCellCompact(lat, lng);
            }
            case "/api/map/grid-view" -> {
                String q = exchange.getRequestURI().getQuery();
                GeoBounds bounds = parseBounds(q);
                String fmt = qps(q, "format", null);
                if ("stats".equals(fmt)) {
                    yield ctx.mapManager.buildGridStatsJson(bounds);
                }
                String layers = qps(q, "layers", "terrain,elevation,passability");
                int maxCells = Math.min((int) qp(q, "maxCells", 2500), 10_000);
                yield ctx.mapManager.buildGridView(bounds, layers, maxCells);
            }
            case "/api/map/terrain-overrides" -> {
                if (!ctx.branchManager.isWorkspaceReady())
                    yield "{\"error\":\"No workspace active. Create or load a workspace first.\"}";
                String method = exchange.getRequestMethod();
                if ("GET".equals(method)) {
                    yield handleOverrideQuery(exchange.getRequestURI().getQuery());
                } else if ("POST".equals(method)) {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    yield handleOverrideCreate(body);
                } else if ("DELETE".equals(method)) {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    yield handleOverrideDelete(body);
                }
                yield "{\"error\":\"Use GET, POST, or DELETE\"}";
            }
            case "/api/intel/sides" -> {
                var sides = new java.util.LinkedHashSet<String>();
                for (var u : ctx.unitsManager.listAll()) sides.add(u.getSource());
                yield "{\"sides\":" + MAPPER.writeValueAsString(sides) + "}";
            }
            default -> "{\"error\":\"Unknown: " + path + "\"}";
        };
    }

    // ---- Helper endpoints ----

    private static String handleDistance(String q) {
        double d = SphericalEngine.haversineDistance(qp(q,"lat1",0),qp(q,"lng1",0),qp(q,"lat2",0),qp(q,"lng2",0));
        return String.format("{\"distanceKm\":%.2f}", d);
    }

    private static String handleRadius(String q) {
        double lat = qp(q,"lat",0), lng = qp(q,"lng",0), r = qp(q,"r",100);
        if (r > MAX_RADIUS_KM) return "{\"error\":\"Radius " + r + "km exceeds max " + MAX_RADIUS_KM + "km\"}";
        var result = ctx.mapManager.queryRadius(lat, lng, r);
        // Return richer result than before
        try {
            var obj = MAPPER.createObjectNode();
            obj.put("center", MAPPER.createObjectNode().put("lat", lat).put("lng", lng));
            obj.put("radiusKm", r);
            obj.put("cellsInRadius", result.cellsInRadius().size());
            obj.put("regionsInRadius", result.regionsInRadius().size());
            obj.put("annotationsInRadius", result.annotationsInRadius().size());
            obj.put("labelsInRadius", result.labelsInRadius().size());
            obj.put("queryTimeMs", result.queryTimeMs());
            return obj.toString();
        } catch (Exception e) {
            return String.format("{\"center\":{\"lat\":%.4f,\"lng\":%.4f},\"radiusKm\":%.1f,\"cellsInRadius\":%d}",
                lat, lng, r, result.cellsInRadius().size());
        }
    }

    private static String handleRadiusEnhanced(String q) {
        double lat = qp(q,"lat",Double.NaN), lng = qp(q,"lng",Double.NaN), r = qp(q,"r",100);
        if (Double.isNaN(lat)) return "{\"error\":\"lat,lng required\"}";
        if (r > MAX_RADIUS_KM) return "{\"error\":\"Radius " + r + "km exceeds max " + MAX_RADIUS_KM + "km\"}";
        var result = ctx.mapManager.queryRadiusEnhanced(lat, lng, r);
        return String.format("{\"center\":{\"lat\":%.4f,\"lng\":%.4f},\"radiusKm\":%.1f,\"roadNodes\":%d,\"roadSegments\":%d}",
            lat, lng, r, result.roadNodes().size(), result.roadSegments().size());
    }

    private static String handleNeighbors(String q) {
        String place = qps(q, "place", "Berlin");
        double r = qp(q, "r", 200);
        var result = ctx.mapManager.queryNeighbors(place, r);
        return String.format("{\"place\":\"%s\",\"radiusKm\":%.0f,\"neighbors\":%d}", place, r, result.neighbors().size());
    }

    private static String handlePath(String q) {
        String from = qps(q, "from", "Berlin"), to = qps(q, "to", "Paris");
        var r = ctx.mapManager.findShortestPath(from, to);
        return String.format("{\"from\":\"%s\",\"to\":\"%s\",\"reachable\":%b,\"totalDistanceKm\":%.1f}",
            from, to, r.reachable(), r.totalDistanceKm());
    }

    private static String handleGridPath(String q) {
        double l1=qp(q,"lat1",0),ln1=qp(q,"lng1",0),l2=qp(q,"lat2",0),ln2=qp(q,"lng2",0);
        double res=qp(q,"res",0.125);
        boolean land=!"false".equals(qps(q,"land","true")), water="true".equals(qps(q,"water","false"));
        var r = ctx.mapManager.findGridPath(l1,ln1,l2,ln2,res,land,water);
        return String.format(Locale.US,"{\"waypoints\":%d,\"totalCostKm\":%.1f,\"straightKm\":%.1f,\"nodesExplored\":%d}",
            r.waypoints(), r.totalCostKm(), r.straightLineKm(), r.nodesExplored());
    }

    private static String handleMarkSegment(String q) {
        String segId = qps(q, "id", ""), label = qps(q, "label", "marked");
        var r = ctx.mapManager.markRoadSegment(segId, label);
        return String.format("{\"segmentId\":\"%s\",\"markLabel\":\"%s\",\"success\":%b}", segId, label, r.success());
    }

    private static String handleUnitCreate(String body) {
        try {
            var n = MAPPER.readTree(body);
            String code = n.has("code") ? n.get("code").asText() : null;
            if (code == null) return "{\"error\":\"code required\"}";
            try {
                var u = ctx.unitsManager.create(code,
                    n.has("name") ? n.get("name").asText() : code,
                    n.has("source") ? n.get("source").asText() : "custom",
                    n.has("type") ? n.get("type").asText() : "generic",
                    n.has("lat") ? n.get("lat").asDouble() : 0,
                    n.has("lng") ? n.get("lng").asDouble() : 0);
                if (n.has("color")) u.setColor(n.get("color").asText());
                if (n.has("description")) u.setDescription(n.get("description").asText());
                if (n.has("status")) u.setStatus(n.get("status").asText());
                if (n.has("strength")) u.setStrength(n.get("strength").asInt());
                if (n.has("maxStrength")) u.setMaxStrength(n.get("maxStrength").asInt());
                return ctx.unitsManager.exportOneJson(u.getCode());
            } catch (IllegalArgumentException e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }

    private static String handleUnitBatch(String body) {
        try {
        var arr = MAPPER.readTree(body);
        int created = 0;
        for (var n : arr) {
            String code = n.has("code") ? n.get("code").asText() : null;
            if (code == null) continue;
            try {
                var u = ctx.unitsManager.create(code,
                    n.has("name") ? n.get("name").asText() : code,
                    n.has("source") ? n.get("source").asText() : "custom",
                    n.has("type") ? n.get("type").asText() : "generic",
                    n.has("lat") ? n.get("lat").asDouble() : 0,
                    n.has("lng") ? n.get("lng").asDouble() : 0);
                if (n.has("color")) u.setColor(n.get("color").asText());
                if (n.has("status")) u.setStatus(n.get("status").asText());
                if (n.has("description")) u.setDescription(n.get("description").asText());
                if (n.has("strength")) u.setStrength(n.get("strength").asInt());
                if (n.has("maxStrength")) u.setMaxStrength(n.get("maxStrength").asInt());
                created++;
            } catch (IllegalArgumentException ignored) {}
        }
        return "{\"ok\":true,\"created\":" + created + "}";
        } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }

    private static String handleSimulate(String treeId, String nodeId) {
        if (treeId == null || nodeId == null) return "{\"error\":\"tree,node required\"}";
        var agent = new com.getgoat.agent.CommanderAgent() {
            @Override public JsonNode callLLM(JsonNode m) { return null; }
        };
        agent.initialize(new com.getgoat.agent.CommanderConfig(), ctx.branchManager,
            ctx.unitsManager, ctx.mapManager, Path.of(System.getProperty("user.dir")));
        var simMode = new SimulateMode();
        agent.setMode(simMode);
        var result = simMode.simulateDeterministic(agent, treeId, nodeId);
        return "{\"ok\":true,\"round\":" + result.roundNumber
            + ",\"summary\":\"" + esc(result.summary) + "\""
            + ",\"movementsResolved\":" + result.movements.size()
            + ",\"engagementsDetected\":" + result.engagements.size()
            + ",\"destroyed\":" + result.combatResults.stream().filter(c -> "destroyed".equals(c.newStatus)).count()
            + ",\"retreated\":" + result.combatResults.stream().filter(c -> "retreating".equals(c.newStatus)).count()
            + ",\"engaged\":" + result.combatResults.stream().filter(c -> "engaged".equals(c.newStatus) || "advancing".equals(c.newStatus)).count()
            + ",\"unitsReachedDestination\":" + result.movements.stream().filter(m -> m.reached).count() + "}";
    }

    // ========================================================================
    //  Terrain override handlers
    // ========================================================================

    private static String handleOverrideQuery(String q) {
        var store = ctx.mapManager.getOverrideStore();
        if (!store.isReady()) return "{\"overrides\":[],\"count\":0}";

        double lat = qp(q, "lat", Double.NaN);
        double lng = qp(q, "lng", Double.NaN);
        // Single-cell lookup
        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
            var grid = ctx.mapManager.getTerrainGrid();
            if (grid == null) return "{\"error\":\"terrain grid not loaded\"}";
            int row = grid.latToRow(lat);
            int col = grid.lngToCol(lng);
            var ov = store.get(row, col);
            if (ov == null) return "null";
            try { return MAPPER.writeValueAsString(ov); }
            catch (Exception e) { return "null"; }
        }

        // Bounds-based query
        GeoBounds bounds = parseBounds(q);
        var list = store.inBounds(bounds, ctx.mapManager.getTerrainGrid().getCellSizeDegrees());
        var result = MAPPER.createObjectNode();
        result.put("count", list.size());
        var arr = result.putArray("overrides");
        try {
            for (var ov : list) arr.add(MAPPER.valueToTree(ov));
        } catch (Exception e) {}
        return result.toString();
    }

    private static String handleOverrideCreate(String body) {
        try {
            var store = ctx.mapManager.getOverrideStore();
            var grid = ctx.mapManager.getTerrainGrid();
            if (grid == null) return "{\"error\":\"terrain grid not loaded\"}";

            var node = MAPPER.readTree(body);
            int created = 0, updated = 0;

            // Support both single object and array of overrides
            var items = node.has("overrides") ? node.get("overrides") : (node.has("lat") ? MAPPER.createArrayNode().add(node) : node);
            if (!items.isArray()) return "{\"error\":\"Expected JSON object with lat,lng fields, or {overrides:[...]}\"}";

            for (var item : items) {
                double lat = item.has("lat") ? item.get("lat").asDouble() : Double.NaN;
                double lng = item.has("lng") ? item.get("lng").asDouble() : Double.NaN;
                if (Double.isNaN(lat) || Double.isNaN(lng)) continue;

                int row = grid.latToRow(lat);
                int col = grid.lngToCol(lng);
                TerrainType terrain = null;
                if (item.has("terrain") && !item.get("terrain").isNull()) {
                    terrain = TerrainType.fromString(item.get("terrain").asText());
                }
                Double elevation = item.has("elevation") && !item.get("elevation").isNull()
                    ? item.get("elevation").asDouble() : null;

                if (terrain == null && elevation == null) continue; // nothing to override

                boolean existed = store.get(row, col) != null;
                store.put(row, col, terrain, elevation, lat, lng);
                if (existed) updated++; else created++;
            }
            // Clear terrain quick cache after overrides changed
            ctx.mapManager.invalidateCache();
            return "{\"ok\":true,\"created\":" + created + ",\"updated\":" + updated + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    private static String handleOverrideDelete(String body) {
        try {
            var store = ctx.mapManager.getOverrideStore();
            var grid = ctx.mapManager.getTerrainGrid();
            if (grid == null) return "{\"error\":\"terrain grid not loaded\"}";

            var node = MAPPER.readTree(body);

            // clearAll
            if (node.has("clearAll") && node.get("clearAll").asBoolean()) {
                int n = store.count();
                store.clear();
                ctx.mapManager.invalidateCache();
                return "{\"ok\":true,\"deleted\":" + n + "}";
            }

            // Bulk or single delete
            var items = node.has("overrides") ? node.get("overrides") : (node.has("lat") ? MAPPER.createArrayNode().add(node) : node);
            if (!items.isArray()) return "{\"error\":\"Expected {overrides:[{lat,lng},...]} or {clearAll:true}\"}";

            int deleted = 0;
            for (var item : items) {
                double lat = item.has("lat") ? item.get("lat").asDouble() : Double.NaN;
                double lng = item.has("lng") ? item.get("lng").asDouble() : Double.NaN;
                if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                int row = grid.latToRow(lat);
                int col = grid.lngToCol(lng);
                if (store.remove(row, col)) deleted++;
            }
            if (deleted > 0) ctx.mapManager.invalidateCache();
            return "{\"ok\":true,\"deleted\":" + deleted + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    private static String exportIntelFilteredUnits(String treeId, String nodeId, String side) {
        var tree = ctx.branchManager.getTree(treeId);
        if (tree == null) return "[]";
        var node = tree.findNode(nodeId);
        if (node == null) return "[]";
        var intelMap = node.getSideIntelMap(side);
        var result = MAPPER.createArrayNode();
        for (var u : ctx.unitsManager.listAll()) {
            if (side.equals(u.getSource())) {
                var obj = result.addObject();
                obj.put("code", u.getCode()); obj.put("name", u.getName());
                obj.put("source", u.getSource()); obj.put("type", u.getType());
                obj.put("status", u.getStatus()); obj.put("color", u.getColor());
                obj.put("lat", u.getLat()); obj.put("lng", u.getLng());
                obj.put("certainty", "confirmed"); obj.put("uncertaintyRadiusKm", 0);
            } else if (intelMap != null) {
                var entry = intelMap.findByUnitCode(u.getCode());
                if (entry != null) {
                    var obj = result.addObject();
                    obj.put("code", u.getCode()); obj.put("name", entry.getName());
                    obj.put("source", entry.getApparentSource()); obj.put("type", entry.getReportedType());
                    obj.put("status", "unknown"); obj.put("color", entry.getCertainty().getColorHex());
                    obj.put("lat", entry.getLat()); obj.put("lng", entry.getLng());
                    obj.put("certainty", entry.getCertainty().getDisplayName());
                    obj.put("uncertaintyRadiusKm", entry.getUncertaintyRadiusKm());
                }
            }
        }
        if (intelMap != null) {
            for (var e : intelMap.getEntries()) {
                if (e.isPhantom()) {
                    var obj = result.addObject();
                    obj.put("code", e.getPhantomId()); obj.put("name", e.getName());
                    obj.put("source", e.getApparentSource()); obj.put("type", e.getReportedType());
                    obj.put("status", "decoy"); obj.put("color", e.getCertainty().getColorHex());
                    obj.put("lat", e.getLat()); obj.put("lng", e.getLng());
                    obj.put("certainty", "decoy"); obj.put("uncertaintyRadiusKm", e.getUncertaintyRadiusKm());
                    obj.put("isPhantom", true);
                }
            }
        }
        return result.toString();
    }

    // ---- Terrain image endpoint ----

    private static void handleTerrainImage(HttpExchange exchange) throws IOException {
        String q = exchange.getRequestURI().getQuery();
        double lat = qp(q, "lat", Double.NaN);
        double lng = qp(q, "lng", Double.NaN);
        double r = qp(q, "r", 100);
        int imgSize = (int) qp(q, "size", 512);
        String fmt = qps(q, "format", "png");

        if (Double.isNaN(lat) || Double.isNaN(lng)) {
            String err = "{\"error\":\"lat and lng required\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, err.length());
            try (var os = exchange.getResponseBody()) { os.write(err.getBytes()); }
            return;
        }

        double degRadius = r / 111.32;
        double south = lat - degRadius;
        double north = lat + degRadius;
        double west = lng - degRadius;
        double east = lng + degRadius;

        // Snap to cell boundaries — every pixel = one real geographic cell
        var grid = ctx.mapManager.getTerrainGrid();
        if (grid != null) {
            GeoBounds snapped = grid.snapBounds(new GeoBounds(south, north, west, east));
            south = snapped.getSouthLat();
            north = snapped.getNorthLat();
            west = snapped.getWestLng();
            east = snapped.getEastLng();
        }

        BufferedImage img = new BufferedImage(imgSize, imgSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, imgSize, imgSize);

        if (grid != null) {
            double cellSize = grid.getCellSizeDegrees();
            int startRow = grid.latToRow(north);
            int endRow = grid.latToRow(south);
            int startCol = grid.lngToCol(west);
            int endCol = grid.lngToCol(east);
            if (endCol < startCol) { int tmp = startCol; startCol = endCol; endCol = tmp; }

            double latSpan = north - south;
            double lngSpan = east - west;

            for (int row = startRow; row <= endRow; row++) {
                double cellCenterLat = grid.rowToLat(row);
                double cellSouth = cellCenterLat - cellSize / 2.0;
                double cellNorth = cellCenterLat + cellSize / 2.0;
                for (int col = startCol; col <= endCol; col++) {
                    double cellCenterLng = grid.colToLng(col);
                    double cellWest = cellCenterLng - cellSize / 2.0;
                    double cellEast = cellCenterLng + cellSize / 2.0;
                    double dist = com.getgoat.map.geometry.SphericalEngine
                        .haversineDistance(lat, lng, cellCenterLat, cellCenterLng);
                    if (dist > r) continue;

                    int px = (int) ((cellWest - west) / lngSpan * imgSize);
                    int py = (int) ((north - cellNorth) / latSpan * imgSize);
                    int pw = Math.max(1, (int) ((cellEast - cellWest) / lngSpan * imgSize));
                    int ph = Math.max(1, (int) ((cellNorth - cellSouth) / latSpan * imgSize));

                    var cell = ctx.mapManager.getTerrainAt(cellCenterLat, cellCenterLng);
                    String hex = cell != null ? cell.getColorHex() : "#1a5276";
                    g.setColor(Color.decode(hex));
                    g.fillRect(Math.max(0, px), Math.max(0, py),
                        Math.min(imgSize - px, pw), Math.min(imgSize - py, ph));
                }
            }
        }

        // Circular mask
        int cx = imgSize / 2, cy = imgSize / 2;
        int radiusPx = (int) (r / 111.32 * (imgSize / (2.0 * degRadius)));
        for (int x = 0; x < imgSize; x++) {
            for (int y = 0; y < imgSize; y++) {
                int dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > radiusPx * radiusPx) {
                    int alpha = img.getRGB(x, y) & 0xFF000000;
                    img.setRGB(x, y, alpha);
                }
            }
        }
        g.dispose();

        var bos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", bos);
        byte[] bytes = bos.toByteArray();

        if ("base64".equals(fmt)) {
            // Return JSON-wrapped base64
            String b64 = Base64.getEncoder().encodeToString(bytes);
            var legend = MAPPER.createObjectNode();
            for (var t : TerrainType.values())
                legend.put(t.getDisplayName(), "#" + t.getColorHex().substring(1));
            var resp = MAPPER.createObjectNode();
            var center = resp.putObject("center");
            center.put("lat", Math.round(lat * 10000.0) / 10000.0);
            center.put("lng", Math.round(lng * 10000.0) / 10000.0);
            resp.put("radiusKm", r);
            resp.put("imageBase64", b64);
            resp.put("mimeType", "image/png");
            resp.put("width", imgSize);
            resp.put("height", imgSize);
            resp.set("legend", legend);
            byte[] jsonBytes = resp.toString().getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, jsonBytes.length);
            try (var os = exchange.getResponseBody()) { os.write(jsonBytes); }
        } else {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=30");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    // ---- Tools endpoint ----

    private static void handleTools(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String response;
        if ("/api/tools".equals(path) && "GET".equals(method)) {
            response = ctx.toolRegistry.listTools().toString();
        } else if ("/api/tools/dispatch".equals(path) && "POST".equals(method)) {
            String body = new String(exchange.getRequestBody().readAllBytes());
            var node = MAPPER.readTree(body);
            String id = node.has("tool_call_id") ? node.get("tool_call_id").asText() : "call_0";
            response = ctx.toolRegistry.dispatch(id, node.get("name").asText(), node.get("arguments"));
        } else {
            response = "{\"error\":\"Unknown\"}";
        }
        byte[] bytes = response.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    // ========================================================================
    //  Utility
    // ========================================================================

    private static double qp(String q, String key, double def) {
        if (q == null) return def;
        for (String p : q.split("&")) {
            String[] kv = p.split("=");
            if (kv.length == 2 && kv[0].equals(key)) return Double.parseDouble(kv[1]);
        }
        return def;
    }

    private static String qps(String q, String key, String def) {
        if (q == null) return def;
        for (String p : q.split("&")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try { return java.net.URLDecoder.decode(kv[1], "UTF-8"); }
                catch (Exception e) { return kv[1]; }
            }
        }
        return def;
    }

    private static GeoBounds parseBounds(String q) {
        if (q == null) return GeoBounds.world();
        return new GeoBounds(qp(q,"south",-90), qp(q,"north",90), qp(q,"west",-180), qp(q,"east",180));
    }

    /** Estimate how many cells a bounding box would contain at the given resolution. */
    private static long estimateCellCount(GeoBounds bounds, double cellSizeDeg) {
        long latCells = (long)(bounds.latSpan() / cellSizeDeg);
        long lngCells = (long)(bounds.lngSpan() / cellSizeDeg);
        return latCells * lngCells;
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) { case '"'->sb.append("\\\""); case '\\'->sb.append("\\\\"); case '\n'->sb.append("\\n"); default->sb.append(c); }
        }
        return sb.toString();
    }

    static String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        return "text/plain";
    }

    private static Path resolveFrontendDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("frontend/index.html"))) return dir.resolve("frontend");
            Path p = dir.getParent();
            if (p == null || p.equals(dir)) break;
            dir = p;
        }
        return Paths.get("frontend").toAbsolutePath();
    }

    // ---- Demo regions + verification ----

    private static void loadUnitsFromWorkspace(String wsDir) {
        Path wsPath = Paths.get(wsDir);
        if (!wsPath.isAbsolute()) wsPath = Paths.get(System.getProperty("user.dir")).resolve(wsDir);
        Path unitsFile = wsPath.resolve("units.json");
        if (!Files.exists(unitsFile)) return;
        try {
            var arr = MAPPER.readTree(Files.readString(unitsFile));
            ctx.unitsManager.deleteAll();
            for (var node : arr) {
                String code = node.has("code") ? node.get("code").asText() : null;
                if (code == null) continue;
                var u = ctx.unitsManager.create(code,
                    node.has("name") ? node.get("name").asText() : code,
                    node.has("source") ? node.get("source").asText() : "custom",
                    node.has("type") ? node.get("type").asText() : "generic",
                    node.has("lat") ? node.get("lat").asDouble() : 0,
                    node.has("lng") ? node.get("lng").asDouble() : 0);
                if (node.has("color")) u.setColor(node.get("color").asText());
                if (node.has("status")) u.setStatus(node.get("status").asText());
                if (node.has("description")) u.setDescription(node.get("description").asText());
            }
            LOG.info("Loaded " + ctx.unitsManager.count() + " units from workspace");
        } catch (Exception e) { LOG.warning("Failed to load units: " + e.getMessage()); }
    }

    private static void createDemoRegions() {
        var gf = new org.locationtech.jts.geom.GeometryFactory();
        // Sahara
        ctx.mapManager.createRegion("Sahara Desert", gf.createPolygon(new org.locationtech.jts.geom.Coordinate[]{
            new org.locationtech.jts.geom.Coordinate(-17,15), new org.locationtech.jts.geom.Coordinate(35,16),
            new org.locationtech.jts.geom.Coordinate(50,22), new org.locationtech.jts.geom.Coordinate(35,30),
            new org.locationtech.jts.geom.Coordinate(-5,30), new org.locationtech.jts.geom.Coordinate(-17,15)}), "geographic");
        // Amazon
        ctx.mapManager.createRegion("Amazon Basin", gf.createPolygon(new org.locationtech.jts.geom.Coordinate[]{
            new org.locationtech.jts.geom.Coordinate(-75,-5), new org.locationtech.jts.geom.Coordinate(-50,-2),
            new org.locationtech.jts.geom.Coordinate(-45,-15), new org.locationtech.jts.geom.Coordinate(-65,-18),
            new org.locationtech.jts.geom.Coordinate(-75,-5)}), "geographic");
        ctx.mapManager.addLabel("Sahara", new GeoPoint(23, 13));
        ctx.mapManager.addLabel("Amazon", new GeoPoint(-5, -62));
        LOG.info("Regions and labels created");
    }

    private static void runVerification() {
        LOG.info(String.format("Berlin→Paris: %.1f km (~878)", SphericalEngine.haversineDistance(52.52,13.405,48.8566,2.3522)));
        LOG.info(String.format("Berlin→Moscow: %.1f km (~1610)", SphericalEngine.haversineDistance(52.52,13.405,55.7558,37.6173)));
        LOG.info(String.format("Everest elevation: %.0f m", ctx.mapManager.getElevationAt(27.988,86.925)));
    }
}
