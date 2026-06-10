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
        }

        createDemoRegions();

        LOG.info("=== Verification ===");
        runVerification();

        // Start HTTP server
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Main::handleRoot);
        server.createContext("/api/", Main::handleApi);
        server.createContext("/api/tools", Main::handleTools);
        server.setExecutor(null);
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
            // Unit sub-paths
            if (path.startsWith("/api/map/units/") && path.length() > 15
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
                CommanderAction action = ctx.agentManager.executeFullRound(side, treeId, nodeId, guidance, 5);
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
                yield ctx.mapManager.exportTerrainTileJson(parseBounds(q));
            }
            case "/api/map/regions" -> ctx.mapManager.exportRegionsGeoJson();
            case "/api/map/labels" -> ctx.mapManager.exportAllLabelsJson();
            case "/api/map/annotations" -> ctx.mapManager.exportAnnotationsGeoJson(GeoBounds.world());
            case "/api/map/bundle" -> {
                String q = exchange.getRequestURI().getQuery();
                yield ctx.mapManager.exportFullMapBundle(parseBounds(q));
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
                String wd = ConfigManager.getProperty("workspace.dir", null);
                if (wd == null) yield "{\"error\":\"workspace.dir not configured\"}";
                Path wp = Paths.get(wd); if (!wp.isAbsolute()) wp = Paths.get(System.getProperty("user.dir")).resolve(wd);
                Files.createDirectories(wp);
                Files.writeString(wp.resolve("units.json"), ctx.unitsManager.exportAllJson());
                ctx.branchManager.saveToDisk();
                yield "{\"ok\":true,\"units\":" + ctx.unitsManager.count() + "}";
            }
            case "/api/workspace/load" -> {
                String wd = ConfigManager.getProperty("workspace.dir", null);
                if (wd == null) yield "{\"error\":\"workspace.dir not configured\"}";
                loadUnitsFromWorkspace(wd);
                yield "{\"ok\":true,\"units\":" + ctx.unitsManager.count() + "}";
            }
            case "/api/simulate" -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    String q = exchange.getRequestURI().getQuery();
                    yield handleSimulate(qps(q, "tree", null), qps(q, "node", null));
                }
                yield "{\"error\":\"Use POST\"}";
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
        var result = ctx.mapManager.queryRadius(lat, lng, r);
        return String.format("{\"center\":{\"lat\":%.4f,\"lng\":%.4f},\"radiusKm\":%.1f,\"cellsInRadius\":%d}",
            lat, lng, r, result.cellsInRadius().size());
    }

    private static String handleRadiusEnhanced(String q) {
        double lat = qp(q,"lat",Double.NaN), lng = qp(q,"lng",Double.NaN), r = qp(q,"r",100);
        if (Double.isNaN(lat)) return "{\"error\":\"lat,lng required\"}";
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
