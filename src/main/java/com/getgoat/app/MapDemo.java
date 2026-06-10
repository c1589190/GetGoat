package com.getgoat.app;

import com.getgoat.map.geometry.SphericalEngine;
import com.getgoat.map.manager.MapManager;
import com.getgoat.map.manager.UnitsManager;
import com.getgoat.map.branch.BranchManager;
import com.getgoat.agent.AgentManager;
import com.getgoat.map.branch.CommanderAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.getgoat.map.model.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Demonstration entry point.
 *
 * Starts a lightweight HTTP server (port 8080) that:
 *   - Serves the frontend HTML/CSS/JS from the frontend/ directory
 *   - Exposes /api/map/* endpoints backed by MapManager
 *
 * Compile and run:
 *   mvn compile exec:java -Dexec.mainClass="com.getgoat.app.MapDemo"
 */
public class MapDemo {

    private static final Logger LOG = Logger.getLogger(MapDemo.class.getName());
    private static final int PORT = 8080;
    // Resolve frontend dir relative to project root (where pom.xml lives),
    // falling back to CWD-relative if running outside Maven.
    private static final Path FRONTEND_DIR = resolveFrontendDir();

    private static Path resolveFrontendDir() {
        // Try to find project root by looking for pom.xml up the tree
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml")) && Files.exists(dir.resolve("frontend/index.html"))) {
                return dir.resolve("frontend");
            }
            if (Files.exists(dir.resolve("frontend/index.html"))) {
                return dir.resolve("frontend");
            }
            Path parent = dir.getParent();
            if (parent == null || parent.equals(dir)) break;
            dir = parent;
        }
        return Paths.get("frontend").toAbsolutePath(); // fallback
    }
    private static final MapManager mapManager = new MapManager();
    private static final com.getgoat.map.manager.UnitsManager unitsManager =
        new com.getgoat.map.manager.UnitsManager();
    private static final BranchManager branchManager =
        new BranchManager(unitsManager);
    private static final AgentManager agentManager =
        new AgentManager(branchManager, unitsManager, mapManager);
    private static final com.getgoat.tools.ToolRegistry toolRegistry =
        new com.getgoat.tools.ToolRegistry();

    static {
        toolRegistry.register(new com.getgoat.tools.GetTerrainTool(mapManager));
        toolRegistry.register(new com.getgoat.tools.RadiusQueryTool(mapManager));
        toolRegistry.register(new com.getgoat.tools.DistanceTool());
        toolRegistry.register(new com.getgoat.tools.FindPathTool(mapManager));
        toolRegistry.register(new com.getgoat.tools.ReliefProfileTool(mapManager));
        toolRegistry.register(new com.getgoat.tools.UnitListTool(unitsManager));
        toolRegistry.register(new com.getgoat.tools.UnitGetTool(unitsManager));
        toolRegistry.register(new com.getgoat.tools.UnitCreateTool(unitsManager));
        toolRegistry.register(new com.getgoat.tools.UnitMoveTool(unitsManager));
        toolRegistry.register(new com.getgoat.tools.UnitDeleteTool(unitsManager));
    }

    public static void main(String[] args) throws Exception {
        LOG.info("=== GetGoat Map System Demo ===");

        // 1. Initialize the map
        double res = com.getgoat.map.ConfigManager.getGridCellSize();
        LOG.info("Initializing map (" + res + "° resolution)...");
        mapManager.initialize(res);
        MapManager.MapStats stats = mapManager.getStats();
        LOG.info("Map stats: " + stats.totalCells() + " cells, "
            + stats.landCells() + " land, " + stats.waterCells() + " water");
        LOG.info("Terrain distribution: " + stats.terrainDistribution());

        // 2. Load workspace
        String wsDir = com.getgoat.map.ConfigManager.getProperty("workspace.dir", null);
        if (wsDir != null && !wsDir.isEmpty()) {
            Path wsPath = Paths.get(wsDir);
            if (!wsPath.isAbsolute()) wsPath = Paths.get(System.getProperty("user.dir")).resolve(wsDir);
            branchManager.setWorkspace(wsDir);
            agentManager.setWorkspace(wsPath);
            LOG.info("Workspace loaded from " + wsDir);
            loadUnitsFromWorkspace(wsDir);
        }

        // 3. Create some demo regions
        createDemoRegions();

        // 3. Run distance verifications
        runVerification();

        // 4. Start HTTP server
        startServer();
    }

    // ---- Workspace ----

    private static void loadUnitsFromWorkspace(String wsDir) {
        Path wsPath = Paths.get(wsDir);
        if (!wsPath.isAbsolute()) wsPath = Paths.get(System.getProperty("user.dir")).resolve(wsDir);
        Path unitsFile = wsPath.resolve("units.json");
        if (!Files.exists(unitsFile)) {
            LOG.info("No units.json in workspace, skipping load");
            return;
        }
        try {
            String content = Files.readString(unitsFile);
            var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
            unitsManager.deleteAll();
            for (var node : arr) {
                String code = node.has("code") ? node.get("code").asText() : null;
                if (code == null || code.isEmpty()) continue;
                String name = node.has("name") ? node.get("name").asText() : code;
                String source = node.has("source") ? node.get("source").asText() : "custom";
                String type = node.has("type") ? node.get("type").asText() : "generic";
                double lat = node.has("lat") ? node.get("lat").asDouble() : 0;
                double lng = node.has("lng") ? node.get("lng").asDouble() : 0;
                var u = unitsManager.create(code, name, source, type, lat, lng);
                if (node.has("color")) u.setColor(node.get("color").asText());
                if (node.has("status")) u.setStatus(node.get("status").asText());
                if (node.has("description")) u.setDescription(node.get("description").asText());
            }
            LOG.info("Loaded " + unitsManager.count() + " units from workspace");
        } catch (Exception e) {
            LOG.warning("Failed to load units from workspace: " + e.getMessage());
        }
    }

    /** Export units filtered by a side's SideIntelMap. */
    private static String exportIntelFilteredUnits(String treeId, String nodeId, String side) {
        var tree = branchManager.getTree(treeId);
        if (tree == null) return "[]";
        var node = tree.findNode(nodeId);
        if (node == null) return "[]";
        var intelMap = node.getSideIntelMap(side);
        // Always include the side's own units
        var result = new com.fasterxml.jackson.databind.node.ArrayNode(
            new com.fasterxml.jackson.databind.ObjectMapper().getNodeFactory());
        for (var u : unitsManager.listAll()) {
            if (side.equals(u.getSource())) {
                var obj = result.addObject();
                obj.put("code", u.getCode());
                obj.put("name", u.getName());
                obj.put("source", u.getSource());
                obj.put("type", u.getType());
                obj.put("status", u.getStatus());
                obj.put("color", u.getColor());
                obj.put("lat", u.getLat());
                obj.put("lng", u.getLng());
                obj.put("certainty", "confirmed");
                obj.put("uncertaintyRadiusKm", 0);
                if (u.getDescription() != null) obj.put("description", u.getDescription());
            } else if (intelMap != null) {
                var entry = intelMap.findByUnitCode(u.getCode());
                if (entry != null) {
                    var obj = result.addObject();
                    obj.put("code", u.getCode());
                    obj.put("name", entry.getName());
                    obj.put("source", entry.getApparentSource());
                    obj.put("type", entry.getReportedType());
                    obj.put("status", "unknown"); // can't see enemy status accurately
                    obj.put("color", entry.getCertainty().getColorHex());
                    obj.put("lat", entry.getLat());
                    obj.put("lng", entry.getLng());
                    obj.put("certainty", entry.getCertainty().getDisplayName());
                    obj.put("uncertaintyRadiusKm", entry.getUncertaintyRadiusKm());
                }
            }
        }
        // Add phantom entries
        if (intelMap != null) {
            for (var entry : intelMap.getEntries()) {
                if (entry.isPhantom()) {
                    var obj = result.addObject();
                    obj.put("code", entry.getPhantomId());
                    obj.put("name", entry.getName());
                    obj.put("source", entry.getApparentSource());
                    obj.put("type", entry.getReportedType());
                    obj.put("status", "decoy");
                    obj.put("color", entry.getCertainty().getColorHex());
                    obj.put("lat", entry.getLat());
                    obj.put("lng", entry.getLng());
                    obj.put("certainty", entry.getCertainty().getDisplayName());
                    obj.put("uncertaintyRadiusKm", entry.getUncertaintyRadiusKm());
                    obj.put("isPhantom", true);
                }
            }
        }
        return result.toString();
    }

    private static String handleSimulate(String treeId, String nodeId) {
        // Create a temporary no-LLM agent for deterministic simulation
        var agent = new com.getgoat.agent.CommanderAgent() {
            @Override public com.fasterxml.jackson.databind.JsonNode callLLM(
                    com.fasterxml.jackson.databind.JsonNode m) { return null; }
        };
        agent.initialize(new com.getgoat.agent.CommanderConfig(), branchManager,
            unitsManager, mapManager, java.nio.file.Path.of(System.getProperty("user.dir")));
        var simMode = new com.getgoat.agent.modes.SimulateMode();
        agent.setMode(simMode);

        var result = simMode.simulateDeterministic(agent, treeId, nodeId);

        return "{\"ok\":true,\"round\":" + result.roundNumber
            + ",\"summary\":\"" + esc(result.summary) + "\""
            + ",\"movementsResolved\":" + result.movements.size()
            + ",\"engagementsDetected\":" + result.engagements.size()
            + ",\"combatResults\":" + result.combatResults.size()
            + ",\"destroyed\":" + result.combatResults.stream().filter(
                c -> "destroyed".equals(c.newStatus)).count()
            + ",\"retreated\":" + result.combatResults.stream().filter(
                c -> "retreating".equals(c.newStatus)).count()
            + ",\"engaged\":" + result.combatResults.stream().filter(
                c -> "engaged".equals(c.newStatus) || "advancing".equals(c.newStatus)).count()
            + ",\"unitsReachedDestination\":" + result.movements.stream().filter(
                m -> m.reached).count()
            + "}";
    }

    private static String handleWorkspaceSave() {
        String wsDir = com.getgoat.map.ConfigManager.getProperty("workspace.dir", null);
        if (wsDir == null || wsDir.isEmpty()) return "{\"error\":\"workspace.dir not configured\"}";
        Path wsPath = Paths.get(wsDir);
        if (!wsPath.isAbsolute()) wsPath = Paths.get(System.getProperty("user.dir")).resolve(wsDir);
        try {
            Files.createDirectories(wsPath);
            Files.writeString(wsPath.resolve("units.json"), unitsManager.exportAllJson());
            branchManager.saveToDisk(); // branches.json already saved to workspace
            return "{\"ok\":true,\"units\":" + unitsManager.count()
                + ",\"path\":\"" + esc(wsPath.toString()) + "\"}";
        } catch (IOException e) {
            return "{\"error\":\"save failed: " + e.getMessage() + "\"}";
        }
    }

    private static String handleWorkspaceLoad() {
        String wsDir = com.getgoat.map.ConfigManager.getProperty("workspace.dir", null);
        if (wsDir == null || wsDir.isEmpty()) return "{\"error\":\"workspace.dir not configured\"}";
        try {
            loadUnitsFromWorkspace(wsDir);
            return "{\"ok\":true,\"units\":" + unitsManager.count() + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static void createDemoRegions() {
        LOG.info("Creating demo regions...");

        // Sahara Desert region (rough bounding polygon)
        org.locationtech.jts.geom.GeometryFactory gf =
            new org.locationtech.jts.geom.GeometryFactory();
        org.locationtech.jts.geom.Coordinate[] saharaCoords = {
            new org.locationtech.jts.geom.Coordinate(-17, 15),
            new org.locationtech.jts.geom.Coordinate(35, 16),
            new org.locationtech.jts.geom.Coordinate(50, 22),
            new org.locationtech.jts.geom.Coordinate(35, 30),
            new org.locationtech.jts.geom.Coordinate(-5, 30),
            new org.locationtech.jts.geom.Coordinate(-17, 15),
        };
        org.locationtech.jts.geom.Polygon sahara =
            gf.createPolygon(saharaCoords);
        Region r1 = mapManager.createRegion("Sahara Desert", sahara, "geographic");
        r1.setColor("#e8c382");
        r1.setOpacity(0.35);
        r1.addTag("desert");
        r1.addTag("africa");

        // Amazon Basin
        org.locationtech.jts.geom.Coordinate[] amazonCoords = {
            new org.locationtech.jts.geom.Coordinate(-75, -5),
            new org.locationtech.jts.geom.Coordinate(-50, -2),
            new org.locationtech.jts.geom.Coordinate(-45, -15),
            new org.locationtech.jts.geom.Coordinate(-65, -18),
            new org.locationtech.jts.geom.Coordinate(-75, -5),
        };
        org.locationtech.jts.geom.Polygon amazon =
            gf.createPolygon(amazonCoords);
        Region r2 = mapManager.createRegion("Amazon Basin", amazon, "geographic");
        r2.setColor("#2d7d3a");
        r2.setOpacity(0.3);
        r2.addTag("rainforest");
        r2.addTag("south-america");

        // Alps
        org.locationtech.jts.geom.Coordinate[] alpsCoords = {
            new org.locationtech.jts.geom.Coordinate(5, 44),
            new org.locationtech.jts.geom.Coordinate(16, 44),
            new org.locationtech.jts.geom.Coordinate(16, 48),
            new org.locationtech.jts.geom.Coordinate(5, 48),
            new org.locationtech.jts.geom.Coordinate(5, 44),
        };
        org.locationtech.jts.geom.Polygon alps =
            gf.createPolygon(alpsCoords);
        Region r3 = mapManager.createRegion("Alps", alps, "geographic");
        r3.setColor("#d5d5d5");
        r3.setOpacity(0.4);
        r3.addTag("mountains");
        r3.addTag("europe");

        // Labels
        mapManager.addLabel("Sahara", new GeoPoint(23, 13));
        mapManager.addLabel("Amazon", new GeoPoint(-5, -62));
        mapManager.addLabel("Alps", new GeoPoint(46, 8));

        // Annotation: Mount Everest marker
        Annotation everest = Annotation.point("ev1",
            new GeoPoint(27.9881, 86.9250), "Mount Everest");
        everest.setDescription("Highest point on Earth: 8,848m");
        mapManager.addAnnotation(everest);

        LOG.info("Added " + mapManager.getAllRegions().size() + " regions, "
            + mapManager.getAllLabels().size() + " labels");
    }

    private static void runVerification() {
        LOG.info("=== Running verification ===");

        // Test distances
        GeoPoint berlin = new GeoPoint(52.52, 13.405);
        GeoPoint paris = new GeoPoint(48.8566, 2.3522);
        GeoPoint moscow = new GeoPoint(55.7558, 37.6173);
        GeoPoint tokyo = new GeoPoint(35.6762, 139.6503);
        GeoPoint sf = new GeoPoint(37.7749, -122.4194);

        double berlinParis = mapManager.distance(berlin, paris);
        double berlinMoscow = mapManager.distance(berlin, moscow);
        double tokyoSF = mapManager.distance(tokyo, sf);
        double everestElev = mapManager.getElevationAt(27.988, 86.925);

        LOG.info(String.format("Berlin → Paris: %.1f km (expected ~878)", berlinParis));
        LOG.info(String.format("Berlin → Moscow: %.1f km (expected ~1610)", berlinMoscow));
        LOG.info(String.format("Tokyo → San Francisco: %.1f km (expected ~8270)", tokyoSF));
        LOG.info(String.format("Everest elevation: %.0f m (expected >8000)", everestElev));
        LOG.info(String.format("Sahara terrain: %s", mapManager.getTerrainTypeNameAt(23, 13)));
        LOG.info(String.format("Pacific terrain: %s", mapManager.getTerrainTypeNameAt(0, -150)));
    }

    // ---- HTTP Server ----

    private static void startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", MapDemo::handleRoot);
        server.createContext("/api/map/terrain-image", MapDemo::handleTerrainImage);
        server.createContext("/api/", MapDemo::handleApi);
        server.createContext("/api/tools", MapDemo::handleTools);
        server.setExecutor(null); // default executor
        server.start();
        LOG.info("Server started at http://localhost:" + PORT);
        LOG.info("Open http://localhost:" + PORT + " in your browser to view the map.");
    }

    private static void handleRoot(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        Path file = FRONTEND_DIR.resolve(path.substring(1));
        if (Files.exists(file) && !Files.isDirectory(file)) {
            byte[] bytes = Files.readAllBytes(file);
            String contentType = getContentType(path);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } else {
            String notFound = "404 Not Found: " + path;
            exchange.sendResponseHeaders(404, notFound.length());
            exchange.getResponseBody().write(notFound.getBytes());
        }
        exchange.getResponseBody().close();
    }

    private static void handleApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String response;
        String contentType = "application/json";

        try {
            // Handle /api/map/units/{code} sub-paths (skip /units/sources and /units/batch)
            if (path.startsWith("/api/map/units/") && path.length() > 15
                    && !path.equals("/api/map/units/sources")
                    && !path.equals("/api/map/units/batch")) {
                String code = java.net.URLDecoder.decode(path.substring(15), "UTF-8");
                String method = exchange.getRequestMethod();
                if ("DELETE".equals(method)) {
                    response = "{\"deleted\":" + unitsManager.delete(code) + "}";
                } else if ("PUT".equals(method)) {
                    String q = exchange.getRequestURI().getQuery();
                    double lat = getQueryParam(q, "lat", Double.NaN);
                    double lng = getQueryParam(q, "lng", Double.NaN);
                    if (Double.isNaN(lat) || Double.isNaN(lng))
                        response = "{\"error\":\"lat and lng required\"}";
                    else {
                        unitsManager.move(code, lat, lng);
                        response = unitsManager.exportOneJson(code);
                    }
                } else if ("PATCH".equals(method)) {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    response = handleUnitPatch(code, body);
                } else {
                    response = unitsManager.exportOneJson(code);
                }
            // Handle /api/commander/{side}/... sub-paths
            } else if (path.startsWith("/api/commander/") && path.length() > 15) {
                String sub = path.substring(15); // e.g. "nationalist/prompt"
                String[] parts = sub.split("/");
                if (parts.length >= 1) {
                    String side = parts[0];
                    if (!"nationalist".equals(side) && !"japanese".equals(side)
                            && !"cpc".equals(side)) {
                        response = "{\"error\":\"Side must be nationalist, japanese, or cpc\"}";
                    } else if (parts.length == 2 && "prompt".equals(parts[1])) {
                        if ("PUT".equals(exchange.getRequestMethod())) {
                            String body = new String(exchange.getRequestBody().readAllBytes());
                            try {
                                agentManager.setSystemPrompt(side, body);
                                response = "{\"ok\":true}";
                            } catch (Exception e) {
                                response = "{\"error\":\"" + e.getMessage() + "\"}";
                            }
                        } else {
                            response = agentManager.getSystemPrompt(side);
                        }
                    } else if (parts.length == 2 && "context".equals(parts[1])) {
                        String q = exchange.getRequestURI().getQuery();
                        String treeId = getQueryStringParam(q, "tree", null);
                        String nodeId = getQueryStringParam(q, "node", null);
                        String guidance = getQueryStringParam(q, "guidance", null);
                        if (treeId == null || nodeId == null)
                            response = "{\"error\":\"tree and node params required\"}";
                        else
                            response = agentManager.buildContext(side, treeId, nodeId, guidance);
                    } else if (parts.length == 2 && "deploy".equals(parts[1]) && "POST".equals(exchange.getRequestMethod())) {
                        String q = exchange.getRequestURI().getQuery();
                        String treeId = getQueryStringParam(q, "tree", null);
                        String nodeId = getQueryStringParam(q, "node", null);
                        // guidance from POST body (avoids URL encoding issues with Chinese)
                        String rawBody = new String(exchange.getRequestBody().readAllBytes());
                        String guidance = null;
                        try {
                            var bodyNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawBody);
                            guidance = bodyNode.has("guidance") ? bodyNode.get("guidance").asText() : null;
                        } catch (Exception e) {
                            guidance = rawBody;
                        }
                        if (treeId == null || nodeId == null)
                            response = "{\"error\":\"tree and node params required\"}";
                        else {
                            try {
                                CommanderAction action = agentManager.executeFullRound(
                                    side, treeId, nodeId, guidance, 5);
                                var resp = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                                resp.put("ok", true);
                                resp.put("source", action.source);
                                resp.put("rationale", action.rationale != null ? action.rationale : "");
                                resp.put("guidanceAssessment", action.guidanceAssessment != null ? action.guidanceAssessment : "");
                                resp.put("risks", action.risks != null ? action.risks : "");
                                resp.set("finalPlan", action.finalPlan != null ? action.finalPlan
                                    : new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode());
                                resp.put("subRounds", action.subRounds.size());
                                response = resp.toString();
                            } catch (Exception e) {
                                response = "{\"error\":\"" + e.getMessage().replace("\"","'") + "\"}";
                            }
                        }
                    } else if (parts.length == 2 && "feedback".equals(parts[1]) && "POST".equals(exchange.getRequestMethod())) {
                        String q = exchange.getRequestURI().getQuery();
                        String treeId = getQueryStringParam(q, "tree", null);
                        String nodeId = getQueryStringParam(q, "node", null);
                        String body = new String(exchange.getRequestBody().readAllBytes());
                        if (treeId == null || nodeId == null)
                            response = "{\"error\":\"tree and node params required\"}";
                        else {
                            agentManager.submitFeedback(side, treeId, nodeId, body);
                            response = "{\"ok\":true}";
                        }
                    } else if (parts.length == 2 && "guidance".equals(parts[1])) {
                        String q = exchange.getRequestURI().getQuery();
                        String treeId = getQueryStringParam(q, "tree", null);
                        String nodeId = getQueryStringParam(q, "node", null);
                        if (treeId == null || nodeId == null)
                            response = "{\"error\":\"tree and node params required\"}";
                        else if ("POST".equals(exchange.getRequestMethod())) {
                            String body = new String(exchange.getRequestBody().readAllBytes());
                            agentManager.setGuidance(side, treeId, nodeId, body);
                            response = "{\"ok\":true}";
                        } else {
                            var tree = branchManager.getTree(treeId);
                            if (tree == null) response = "null";
                            else {
                                var node = tree.findNode(nodeId);
                                if (node == null) response = "null";
                                else {
                                    var action = node.getCommanderAction(side);
                                    response = action != null ? "\"" + esc(action.guidance) + "\"" : "null";
                                }
                            }
                        }
                    } else {
                        response = "{\"error\":\"Unknown commander endpoint\"}";
                    }
                } else {
                    response = "{\"error\":\"Invalid commander path\"}";
                }
            // Handle /api/intel/{treeId}/{nodeId}/{side} sub-paths
            } else if (path.startsWith("/api/intel/") && path.length() > 11
                    && !path.equals("/api/intel/sides")) {
                String sub = path.substring(11); // e.g. "tree123/node456/nationalist"
                String[] parts = sub.split("/");
                if (parts.length >= 3) {
                    String treeId = parts[0];
                    String nodeId = parts[1];
                    String side = parts[2];
                    var tree = branchManager.getTree(treeId);
                    if (tree == null) response = "{\"error\":\"tree not found\"}";
                    else {
                        var node = tree.findNode(nodeId);
                        if (node == null) response = "{\"error\":\"node not found\"}";
                        else {
                            var intelMap = node.getSideIntelMap(side);
                            if (intelMap == null) response = "{\"entries\":[],\"exploredBounds\":[],\"side\":\"" + esc(side) + "\",\"roundNumber\":0}";
                            else {
                                try {
                                    response = new com.fasterxml.jackson.databind.ObjectMapper()
                                        .writeValueAsString(intelMap);
                                } catch (Exception e) {
                                    response = "{\"error\":\"serialize failed\"}";
                                }
                            }
                        }
                    }
                } else {
                    response = "{\"error\":\"path must be /api/intel/{treeId}/{nodeId}/{side}\"}";
                }
            // Handle /api/branches/{treeId}/... sub-paths
            } else if (path.startsWith("/api/branches/") && path.length() > 14) {
                String sub = path.substring(14);
                String[] parts = sub.split("/");
                if (parts.length >= 1) {
                    String treeId = parts[0];
                    if (parts.length == 1) {
                        if ("DELETE".equals(exchange.getRequestMethod()))
                            response = "{\"deleted\":" + branchManager.deleteTree(treeId) + "}";
                        else
                            response = branchManager.exportTreeJson(treeId);
                    } else if (parts.length == 2 && "flat".equals(parts[1])) {
                        response = branchManager.exportFlatListJson(treeId);
                    } else if ("changes".equals(parts[1]) && parts.length >= 3) {
                        // GET /api/branches/{treeId}/changes/{nodeId}
                        String nodeId = parts[2];
                        response = branchManager.exportChangesJson(treeId, nodeId);
                    } else if ("moves".equals(parts[1]) && parts.length >= 3) {
                        // GET /api/branches/{treeId}/moves/{nodeId}
                        String nodeId = parts[2];
                        response = branchManager.exportMovesFromSnapshots(treeId, nodeId);
                    } else if ("apply".equals(parts[1]) && parts.length >= 3) {
                        String nodeId = parts[2];
                        boolean ok = branchManager.applyNode(treeId, nodeId);
                        response = ok ? "{\"ok\":true,\"applied\":\"" + esc(nodeId) + "\"}"
                                      : "{\"error\":\"node not found\"}";
                    } else if ("nodes".equals(parts[1]) && parts.length >= 4 && "action".equals(parts[3])) {
                        // /api/branches/{treeId}/nodes/{nodeId}/action/{side}
                        String actionNodeId = parts[2];
                        String actionSide = parts[4];
                        if ("POST".equals(exchange.getRequestMethod())) {
                            String body = new String(exchange.getRequestBody().readAllBytes());
                            try {
                                var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                                var action = new com.getgoat.map.branch.CommanderAction();
                                action.side = actionSide;
                                action.source = n.has("source") ? n.get("source").asText() : "manual";
                                action.guidance = n.has("guidance") ? n.get("guidance").asText() : "";
                                action.rationale = n.has("rationale") ? n.get("rationale").asText() : "";
                                action.feedback = n.has("feedback") ? n.get("feedback").asText() : "";
                                action.deployment = n.has("deployment") ? n.get("deployment").toString() : "";
                                action.finalPlan = n.has("finalPlan") ? n.get("finalPlan") : null;
                                action.timestamp = System.currentTimeMillis();
                                branchManager.saveCommanderAction(treeId, actionNodeId, action);
                                response = "{\"ok\":true}";
                            } catch (Exception e) {
                                response = "{\"error\":\"" + e.getMessage() + "\"}";
                            }
                        } else {
                            var action = branchManager.getCommanderAction(treeId, actionNodeId, actionSide);
                            response = action != null ? "{\"found\":true}" : "null";
                        }
                    } else if ("nodes".equals(parts[1]) && parts.length == 2 && "POST".equals(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes());
                        response = handleBranchAddRound(treeId, body);
                    } else if ("nodes".equals(parts[1]) && parts.length >= 3) {
                        String nodeId = parts[2];
                        if ("DELETE".equals(exchange.getRequestMethod()))
                            response = "{\"deleted\":" + branchManager.deleteNode(treeId, nodeId) + "}";
                        else if ("PATCH".equals(exchange.getRequestMethod())) {
                            String q = exchange.getRequestURI().getQuery();
                            String newName = getQueryStringParam(q, "name", null);
                            String newStrategy = getQueryStringParam(q, "strategy", null);
                            String newOutcome = getQueryStringParam(q, "outcome", null);
                            response = "{\"updated\":" + branchManager.updateNode(treeId, nodeId, newName, newStrategy, newOutcome) + "}";
                        } else
                            response = branchManager.exportNodeJson(treeId, nodeId);
                    } else if (parts.length == 2 && "reload".equals(parts[1])
                            && "POST".equals(exchange.getRequestMethod())) {
                        response = branchManager.reloadWorkspace();
                    } else {
                        response = "{\"error\":\"Unknown branch endpoint: " + path + "\"}";
                    }
                } else {
                    response = "{\"error\":\"Invalid branch path\"}";
                }
            } else {
                response = switch (path) {
                case "/api/intel/sides" -> {
                    var sides = new java.util.LinkedHashSet<String>();
                    for (var u : unitsManager.listAll()) sides.add(u.getSource());
                    yield "{\"sides\":" + new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(sides) + "}";
                }
                case "/api/map/stats" -> mapManager.exportStatsJson();
                case "/api/map/terrain" -> {
                    String query = exchange.getRequestURI().getQuery();
                    GeoBounds bounds = parseBounds(query);
                    yield mapManager.exportTerrainTileJson(bounds);
                }
                case "/api/map/regions" -> mapManager.exportRegionsGeoJson();
                case "/api/map/labels" -> mapManager.exportAllLabelsJson();
                case "/api/map/annotations" -> mapManager.exportAnnotationsGeoJson(GeoBounds.world());
                case "/api/map/bundle" -> {
                    String query = exchange.getRequestURI().getQuery();
                    GeoBounds bounds = parseBounds(query);
                    yield mapManager.exportFullMapBundle(bounds);
                }
                case "/api/map/distance" -> {
                    String q = exchange.getRequestURI().getQuery();
                    yield handleDistanceQuery(q);
                }
                case "/api/map/radius" -> {
                    String q = exchange.getRequestURI().getQuery();
                    yield handleRadiusQuery(q);
                }
                case "/api/map/radius-enhanced" -> {
                    String q = exchange.getRequestURI().getQuery();
                    yield handleEnhancedRadiusQuery(q);
                }
                case "/api/map/neighbors" -> {
                    String q = exchange.getRequestURI().getQuery();
                    yield handleNeighborsQuery(q);
                }
                case "/api/map/path" -> {
                    String q = exchange.getRequestURI().getQuery();
                    yield handlePathQuery(q);
                }
                case "/api/map/grid-path" -> {
                    String q = exchange.getRequestURI().getQuery();
                    yield handleGridPathQuery(q);
                }
                case "/api/map/mark-segment" -> {
                    String q = exchange.getRequestURI().getQuery();
                    yield handleMarkSegmentQuery(q);
                }
                case "/api/map/roads-geojson" -> mapManager.exportRoadsGeoJson();
                case "/api/map/roads-in-radius" -> {
                    String q = exchange.getRequestURI().getQuery();
                    double lat = getQueryParam(q, "lat", Double.NaN);
                    double lng = getQueryParam(q, "lng", Double.NaN);
                    double r = getQueryParam(q, "r", 100);
                    yield mapManager.exportRoadsInRadius(lat, lng, r);
                }
                case "/api/map/apply-upgrades" -> mapManager.applyUpgradeRules();
                case "/api/map/upgrade-rules" -> {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes());
                        handleUpgradeRulesSave(body);
                        yield "{\"ok\":true}";
                    }
                    yield handleUpgradeRulesGet();
                }
                case "/api/map/hsv-rules" -> {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes());
                        handleHsvRulesSave(body);
                        yield "{\"ok\":true}";
                    }
                    yield handleHsvRulesGet();
                }
                case "/api/map/rivers-in-radius" -> {
                    String q = exchange.getRequestURI().getQuery();
                    double lat = getQueryParam(q, "lat", Double.NaN);
                    double lng = getQueryParam(q, "lng", Double.NaN);
                    double r = getQueryParam(q, "r", 100);
                    yield mapManager.exportRiversInRadius(lat, lng, r);
                }
                case "/api/map/province-at" -> {
                    String q = exchange.getRequestURI().getQuery();
                    double lat = getQueryParam(q, "lat", Double.NaN);
                    double lng = getQueryParam(q, "lng", Double.NaN);
                    double res = getQueryParam(q, "res", 0.25);
                    yield mapManager.exportProvinceWithCells(lat, lng, res);
                }
                case "/api/map/terrain-cells" -> {
                    String q = exchange.getRequestURI().getQuery();
                    double lat = getQueryParam(q, "lat", Double.NaN);
                    double lng = getQueryParam(q, "lng", Double.NaN);
                    double r = getQueryParam(q, "r", 100);
                    double res = getQueryParam(q, "res", 0);
                    yield mapManager.exportTerrainCellsInRadius(lat, lng, r, res);
                }
                case "/api/map/debug-pipeline" -> {
                    String q = exchange.getRequestURI().getQuery();
                    double lat = getQueryParam(q, "lat", 23.13);
                    double lng = getQueryParam(q, "lng", 113.26);
                    yield mapManager.debugClassificationPipeline(lat, lng);
                }
                case "/api/map/cache-all" -> mapManager.cacheAll();
                case "/api/map/cache-status" -> "{\"exists\":" + mapManager.isCacheExists()
                    + ",\"path\":\"" + mapManager.getCachePath().replace("\\", "\\\\") + "\"}";
                case "/api/map/units" -> {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes());
                        yield handleUnitCreate(body);
                    }
                    String q = exchange.getRequestURI().getQuery();
                    // Per-side intel view: filter units by SideIntelMap
                    String viewedBy = getQueryStringParam(q, "viewedBy", null);
                    String treeId = getQueryStringParam(q, "tree", null);
                    String nodeId = getQueryStringParam(q, "node", null);
                    if (viewedBy != null && treeId != null && nodeId != null) {
                        yield exportIntelFilteredUnits(treeId, nodeId, viewedBy);
                    }
                    // Branch node context: return snapshots from tree/node
                    if (treeId != null && nodeId != null) {
                        yield branchManager.exportUnitSnapshotsJson(treeId, nodeId);
                    }
                    String source = getQueryStringParam(q, "source", null);
                    String status = getQueryStringParam(q, "status", null);
                    String name = getQueryStringParam(q, "name", null);
                    String search = getQueryStringParam(q, "search", null);
                    if (search != null) yield unitsManager.exportJson(unitsManager.search(search));
                    if (status != null) yield unitsManager.exportJson(unitsManager.listByStatus(status));
                    if (name != null) yield unitsManager.exportJson(unitsManager.listByName(name));
                    yield source != null
                        ? unitsManager.exportJson(unitsManager.listBySource(source))
                        : unitsManager.exportAllJson();
                }
                case "/api/map/units/sources" ->
                    "{\"sources\":" + new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(unitsManager.listSources()) + "}";
                case "/api/map/units/batch" -> {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes());
                        yield handleUnitBatchCreate(body);
                    }
                    if ("DELETE".equals(exchange.getRequestMethod()))
                        yield handleUnitBatchDelete();
                    yield "{\"error\":\"Use POST to batch create, DELETE to clear\"}";
                }
                case "/api/simulate" -> {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        String q = exchange.getRequestURI().getQuery();
                        String treeId = getQueryStringParam(q, "tree", null);
                        String nodeId = getQueryStringParam(q, "node", null);
                        if (treeId == null || nodeId == null)
                            yield "{\"error\":\"tree and node params required\"}";
                        yield handleSimulate(treeId, nodeId);
                    }
                    yield "{\"error\":\"Use POST to simulate\"}";
                }
                case "/api/branches" -> {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes());
                        yield handleBranchCreate(body);
                    }
                    yield branchManager.exportTreeListJson();
                }
                case "/api/workspace/save" -> {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        yield handleWorkspaceSave();
                    }
                    yield "{\"error\":\"Use POST to save workspace\"}";
                }
                case "/api/workspace/load" -> {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        yield handleWorkspaceLoad();
                    }
                    yield "{\"error\":\"Use POST to load workspace\"}";
                }
                case "/api/map/relief-config" -> {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes());
                        yield handleReliefConfigSave(body);
                    }
                    yield handleReliefConfigGet();
                }
                case "/api/map/terrain-at" -> {
                    String q = exchange.getRequestURI().getQuery();
                    yield handleTerrainAtQuery(q);
                }
                default -> "{\"error\":\"Unknown endpoint: " + path + "\"}";
                };
            } // end else (units sub-path)
        } catch (Exception e) {
            response = "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }

        byte[] bytes = response.getBytes();
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static GeoBounds parseBounds(String query) {
        if (query == null) return GeoBounds.world();
        double south = getQueryParam(query, "south", -90);
        double north = getQueryParam(query, "north", 90);
        double west = getQueryParam(query, "west", -180);
        double east = getQueryParam(query, "east", 180);
        return new GeoBounds(south, north, west, east);
    }

    private static double getQueryParam(String query, String key, double defaultVal) {
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length == 2 && kv[0].equals(key)) {
                return Double.parseDouble(kv[1]);
            }
        }
        return defaultVal;
    }

    private static String handleRadiusQuery(String query) {
        double lat = getQueryParam(query, "lat", Double.NaN);
        double lng = getQueryParam(query, "lng", Double.NaN);
        double radiusKm = getQueryParam(query, "r", 100.0); // default 100km
        if (Double.isNaN(lat) || Double.isNaN(lng)) {
            return "{\"error\":\"lat and lng required\"}";
        }
        var result = mapManager.queryRadius(lat, lng, radiusKm);
        return String.format(
            "{\"center\":{\"lat\":%.4f,\"lng\":%.4f}," +
            "\"radiusKm\":%.1f," +
            "\"terrain\":\"%s\"," +
            "\"elevation\":%.0f," +
            "\"cellsInRadius\":%d," +
            "\"regions\":[%s]," +
            "\"annotations\":[%s]," +
            "\"labels\":[%s]," +
            "\"queryTimeMs\":%d}",
            lat, lng, radiusKm,
            result.centerCell() != null ? result.centerCell().getTerrain().getDisplayName() : "unknown",
            result.centerCell() != null ? result.centerCell().getElevationMeters() : 0,
            result.cellsInRadius().size(),
            result.regionsInRadius().stream()
                .map(r -> String.format("{\"id\":\"%s\",\"name\":\"%s\",\"category\":\"%s\"}",
                    esc(r.getId()), esc(r.getName()), esc(r.getCategory())))
                .reduce((a,b) -> a+","+b).orElse(""),
            result.annotationsInRadius().stream()
                .map(a -> String.format("{\"id\":\"%s\",\"label\":\"%s\",\"type\":\"%s\"}",
                    esc(a.getId()), esc(a.getLabel()), a.getType().name()))
                .reduce((a,b) -> a+","+b).orElse(""),
            result.labelsInRadius().stream()
                .map(l -> String.format("{\"id\":\"%s\",\"text\":\"%s\"}",
                    esc(l.getId()), esc(l.getText())))
                .reduce((a,b) -> a+","+b).orElse(""),
            result.queryTimeMs()
        );
    }

    private static String handleEnhancedRadiusQuery(String query) {
        double lat = getQueryParam(query, "lat", Double.NaN);
        double lng = getQueryParam(query, "lng", Double.NaN);
        double r = getQueryParam(query, "r", 100.0);
        if (Double.isNaN(lat) || Double.isNaN(lng)) {
            return "{\"error\":\"lat and lng required\"}";
        }
        var result = mapManager.queryRadiusEnhanced(lat, lng, r);

        // Build terrain profile JSON
        StringBuilder tp = new StringBuilder("{");
        boolean firstTp = true;
        var profile = result.terrainProfile();
        if (profile != null) {
            for (var e : profile.entrySet()) {
                if (!firstTp) tp.append(",");
                firstTp = false;
                tp.append("\"").append(e.getKey().getDisplayName()).append("\":").append(e.getValue());
            }
        }
        tp.append("}");

        // Build relief + elevation band profile JSON
        var rp = mapManager.computeReliefProfile(lat, lng, r);
        var bp = mapManager.computeElevationBandProfile(lat, lng, r);
        StringBuilder rpJson = new StringBuilder("{");
        boolean firstRp = true;
        for (var e : rp.entrySet()) {
            if (!firstRp) rpJson.append(",");
            firstRp = false;
            rpJson.append("\"").append(e.getKey()).append("\":").append(e.getValue());
        }
        rpJson.append("}");

        // Build elevation band JSON
        StringBuilder bpJson = new StringBuilder("{");
        boolean firstBp = true;
        for (var e : bp.entrySet()) {
            if (!firstBp) bpJson.append(",");
            firstBp = false;
            bpJson.append("\"").append(e.getKey()).append("\":").append(e.getValue());
        }
        bpJson.append("}");

        var ep = result.elevationProfile();
        return String.format(
            "{\"center\":{\"lat\":%.4f,\"lng\":%.4f},\"radiusKm\":%.1f," +
            "\"terrain\":\"%s\",\"elevation\":%.0f," +
            "\"roadNodes\":%d,\"roadSegments\":%d," +
            "\"cities\":[%s]," +
            "\"regions\":[%s],\"annotations\":[%s]," +
            "\"terrainBreakdown\":{\"north\":\"%s\",\"south\":\"%s\",\"east\":\"%s\",\"west\":\"%s\"}," +
            "\"terrainProfile\":%s," +
            "\"roughness\":%s," +
            "\"bands\":%s," +
            "\"elevation\":{\"min\":%d,\"max\":%d,\"mean\":%d,\"range\":%d}," +
            "\"queryTimeMs\":%d}",
            lat, lng, r,
            result.centerCell() != null ? result.centerCell().getTerrain().getDisplayName() : "unknown",
            result.centerCell() != null ? result.centerCell().getElevationMeters() : 0,
            result.roadNodes().size(), result.roadSegments().size(),
            result.cities().stream().map(c -> String.format(
                "{\"name\":\"%s\",\"lat\":%.4f,\"lng\":%.4f,\"country\":\"%s\",\"population\":%d}",
                esc(c.getName()), c.getCenter().getLatitude(), c.getCenter().getLongitude(),
                esc(c.getCountry()), c.getPopulation()))
                .reduce((a,b)->a+","+b).orElse(""),
            result.regions().stream().map(r2 -> String.format(
                "{\"id\":\"%s\",\"name\":\"%s\"}", esc(r2.getId()), esc(r2.getName())))
                .reduce((a,b)->a+","+b).orElse(""),
            result.annotations().stream().map(a2 -> String.format(
                "{\"id\":\"%s\",\"label\":\"%s\"}", esc(a2.getId()), esc(a2.getLabel())))
                .reduce((a,b)->a+","+b).orElse(""),
            esc(result.terrainBreakdown().northSummary()),
            esc(result.terrainBreakdown().southSummary()),
            esc(result.terrainBreakdown().eastSummary()),
            esc(result.terrainBreakdown().westSummary()),
            tp.toString(),
            rpJson.toString(),
            bpJson.toString(),
            ep.min(), ep.max(), ep.mean(), ep.range(),
            result.queryTimeMs()
        );
    }

    private static String handleNeighborsQuery(String query) {
        String place = getQueryStringParam(query, "place", "Berlin");
        double r = getQueryParam(query, "r", 200.0);
        var result = mapManager.queryNeighbors(place, r);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("{\"place\":\"%s\",\"radiusKm\":%.0f," +
            "\"location\":{\"lat\":%.4f,\"lng\":%.4f}," +
            "\"nearestNodeId\":\"%s\",\"neighbors\":[",
            esc(place), r,
            result.placeLocation() != null ? result.placeLocation().getLatitude() : 0,
            result.placeLocation() != null ? result.placeLocation().getLongitude() : 0,
            result.nearestNodeId() != null ? result.nearestNodeId() : ""));
        boolean first = true;
        for (var n : result.neighbors()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(String.format("{\"place\":\"%s\",\"lat\":%.4f,\"lng\":%.4f," +
                "\"distanceKm\":%.1f,\"viaSegments\":[%s]}",
                esc(n.placeName()), n.placeLocation().getLatitude(), n.placeLocation().getLongitude(),
                n.distanceKm(),
                n.viaSegments().stream().map(s -> "\"" + s + "\"").reduce((a,b)->a+","+b).orElse("")));
        }
        sb.append("],\"regions\":[");
        first = true;
        for (var reg : result.regionsInArea()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(String.format("{\"id\":\"%s\",\"name\":\"%s\"}", esc(reg.getId()), esc(reg.getName())));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String handleGridPathQuery(String query) {
        double lat1 = getQueryParam(query, "lat1", Double.NaN);
        double lng1 = getQueryParam(query, "lng1", Double.NaN);
        double lat2 = getQueryParam(query, "lat2", Double.NaN);
        double lng2 = getQueryParam(query, "lng2", Double.NaN);
        double res = getQueryParam(query, "res", 0.125);
        boolean allowLand = !"false".equals(getQueryStringParam(query, "land", "true"));
        boolean allowWater = "true".equals(getQueryStringParam(query, "water", "false"));
        if (Double.isNaN(lat1) || Double.isNaN(lng1) || Double.isNaN(lat2) || Double.isNaN(lng2)) {
            return "{\"error\":\"lat1,lng1,lat2,lng2 required\"}";
        }
        var result = mapManager.findGridPath(lat1, lng1, lat2, lng2, res, allowLand, allowWater);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US,
            "{\"from\":[%.4f,%.4f],\"to\":[%.4f,%.4f]," +
            "\"waypoints\":%d,\"totalCostKm\":%.1f,\"straightKm\":%.1f," +
            "\"nodesExplored\":%d,\"queryTimeMs\":%d," +
            "\"allowLand\":%b,\"allowWater\":%b," +
            "\"path\":[",
            lat1, lng1, lat2, lng2,
            result.waypoints(), result.totalCostKm(), result.straightLineKm(),
            result.nodesExplored(), result.queryTimeMs(),
            allowLand, allowWater));
        boolean first = true;
        for (GeoPoint p : result.path()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(String.format(Locale.US, "[%.4f,%.4f]", p.getLatitude(), p.getLongitude()));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String handlePathQuery(String query) {
        String from = getQueryStringParam(query, "from", "Berlin");
        String to = getQueryStringParam(query, "to", "Paris");
        var result = mapManager.findShortestPath(from, to);
        return String.format(
            "{\"from\":\"%s\",\"to\":\"%s\",\"reachable\":%b," +
            "\"totalDistanceKm\":%.1f,\"nodeCount\":%d,\"segmentCount\":%d," +
            "\"nodeIds\":[%s],\"segmentIds\":[%s]}",
            esc(from), esc(to), result.reachable(),
            result.totalDistanceKm(), result.nodeIds().size(), result.segmentIds().size(),
            result.nodeIds().stream().map(n -> "\"" + n + "\"").reduce((a,b)->a+","+b).orElse(""),
            result.segmentIds().stream().map(s -> "\"" + s + "\"").reduce((a,b)->a+","+b).orElse(""));
    }

    private static String handleMarkSegmentQuery(String query) {
        String segId = getQueryStringParam(query, "id", "");
        String label = getQueryStringParam(query, "label", "marked");
        if (segId.isEmpty()) return "{\"error\":\"segment id required\"}";
        var result = mapManager.markRoadSegment(segId, label);
        return String.format(
            "{\"segmentId\":\"%s\",\"markLabel\":\"%s\",\"success\":%b," +
            "\"fromNode\":%s,\"toNode\":%s,\"lengthKm\":%.1f}",
            esc(result.segmentId()), esc(result.markLabel()), result.success(),
            result.fromNode() != null ? String.format(
                "{\"id\":\"%s\",\"lat\":%.4f,\"lng\":%.4f}",
                result.fromNode().getId(), result.fromNode().getLocation().getLatitude(),
                result.fromNode().getLocation().getLongitude()) : "null",
            result.toNode() != null ? String.format(
                "{\"id\":\"%s\",\"lat\":%.4f,\"lng\":%.4f}",
                result.toNode().getId(), result.toNode().getLocation().getLatitude(),
                result.toNode().getLocation().getLongitude()) : "null",
            result.lengthKm()
        );
    }

    private static String getQueryStringParam(String query, String key, String defaultVal) {
        if (query == null) return defaultVal;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try {
                    return java.net.URLDecoder.decode(kv[1], "UTF-8");
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return defaultVal;
    }

    private static java.io.File findConfigFile() {
        for (String p : new String[]{"terrain-colors.json", "src/main/resources/geodata/terrain-colors.json",
                "../terrain-colors.json", "../../src/main/resources/geodata/terrain-colors.json"}) {
            java.io.File f = new java.io.File(p);
            if (f.exists()) return f;
        }
        return new java.io.File("terrain-colors.json");
    }

    private static void handleUpgradeRulesSave(String body) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.io.File cfgFile = findConfigFile();
            var root = cfgFile.exists() ? mapper.readTree(cfgFile) : mapper.readTree("{}");
            var newRules = mapper.readTree(body);
            var sb = new StringBuilder();
            sb.append("{\n");
            var fields = root.fields();
            boolean first = true, wroteUpgrade = false;
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!first) sb.append(",\n"); first = false;
                sb.append("  \"").append(entry.getKey()).append("\": ");
                if (entry.getKey().equals("upgradeRules")) {
                    sb.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(newRules.get("upgradeRules")));
                    wroteUpgrade = true;
                } else {
                    sb.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entry.getValue()));
                }
            }
            if (!wroteUpgrade) {
                if (!first) sb.append(",\n");
                sb.append("  \"upgradeRules\": ").append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(newRules.get("upgradeRules")));
            }
            sb.append("\n}\n");
            // Write to both project and CWD locations
            java.nio.file.Files.writeString(java.nio.file.Paths.get("src/main/resources/geodata/terrain-colors.json"), sb.toString());
            try { java.nio.file.Files.writeString(java.nio.file.Paths.get("terrain-colors.json"), sb.toString()); } catch(Exception ignored) {}
            try { java.nio.file.Files.writeString(java.nio.file.Paths.get("../terrain-colors.json"), sb.toString()); } catch(Exception ignored) {}
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static String handleUpgradeRulesGet() {
        try {
            java.io.File f = findConfigFile();
            if (!f.exists()) return "{\"upgradeRules\":{}}";
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(f);
            var ur = root.get("upgradeRules");
            return ur != null ? "{\"upgradeRules\":" + ur.toString() + "}" : "{\"upgradeRules\":{}}";
        } catch (Exception e) { return "{\"upgradeRules\":{}}"; }
    }

    private static String handleHsvRulesGet() {
        try {
            java.io.File f = new java.io.File("terrain-colors.json");
            if(!f.exists()) f=new java.io.File("src/main/resources/geodata/terrain-colors.json");
            if(!f.exists()) return "{}";
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(f);
            var rules = root.get("rules");
            // Return just HILLS and MOUNTAIN rules for the UI
            StringBuilder sb = new StringBuilder("[");
            if(rules != null) for(var r : rules) {
                String t = r.get("terrain").asText();
                if(t.equals("HILLS") || t.equals("MOUNTAIN")) {
                    if(sb.length()>1)sb.append(",");
                    sb.append(r.toString());
                }
            }
            sb.append("]");
            return sb.toString();
        } catch(Exception e) { return "[]"; }
    }

    private static void handleHsvRulesSave(String body) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.io.File cfgFile = new java.io.File("terrain-colors.json");
            if(!cfgFile.exists()) cfgFile=new java.io.File("src/main/resources/geodata/terrain-colors.json");
            if(!cfgFile.exists()) cfgFile=new java.io.File("../terrain-colors.json");
            var root = cfgFile.exists() ? mapper.readTree(cfgFile) : mapper.readTree("{}");
            var newRules = mapper.readTree(body);
            // Replace HILLS and MOUNTAIN rules
            var rulesArr = root.get("rules");
            if(rulesArr != null && newRules.isArray()) {
                for(var nr : newRules) {
                    String t = nr.get("terrain").asText();
                    for(int i=0;i<rulesArr.size();i++) {
                        if(rulesArr.get(i).get("terrain").asText().equals(t)) {
                            var __ = ((com.fasterxml.jackson.databind.node.ArrayNode)rulesArr).set(i, nr);
                            break;
                        }
                    }
                }
            }
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            java.nio.file.Files.writeString(java.nio.file.Paths.get("src/main/resources/geodata/terrain-colors.json"), json);
            try{java.nio.file.Files.writeString(java.nio.file.Paths.get("terrain-colors.json"),json);}catch(Exception ignored){}
            try{java.nio.file.Files.writeString(java.nio.file.Paths.get("../terrain-colors.json"),json);}catch(Exception ignored){}
            try{java.nio.file.Files.writeString(java.nio.file.Paths.get("src/main/resources/geodata/elevation/terrain-colors.json"),json);}catch(Exception ignored){}
        } catch(Exception e) { e.printStackTrace(); }
    }

    private static String handleReliefConfigGet() {
        return "{\"elevThreshold\":" + mapManager.getReliefElevThreshold()
            + ",\"roughThreshold\":" + mapManager.getReliefRoughThreshold() + "}";
    }

    private static String handleReliefConfigSave(String body) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            int elev = node.has("elevThreshold") ? node.get("elevThreshold").asInt() : 500;
            int rough = node.has("roughThreshold") ? node.get("roughThreshold").asInt() : 60;
            mapManager.setReliefThresholds(elev, rough);
            return "{\"ok\":true,\"elevThreshold\":" + elev + ",\"roughThreshold\":" + rough + "}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String handleUnitPatch(String code, String body) {
        try {
            var unit = unitsManager.get(code);
            if (unit == null) return "{\"error\":\"unit not found: " + esc(code) + "\"}";
            var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
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
            return unitsManager.exportOneJson(code);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String handleUnitCreate(String body) {
        try {
            var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            String code = n.has("code") ? n.get("code").asText() : null;
            if (code == null || code.isEmpty()) return "{\"error\":\"code required\"}";
            String name = n.has("name") ? n.get("name").asText() : code;
            String source = n.has("source") ? n.get("source").asText() : "custom";
            String type = n.has("type") ? n.get("type").asText() : "generic";
            double lat = n.has("lat") ? n.get("lat").asDouble() : 0;
            double lng = n.has("lng") ? n.get("lng").asDouble() : 0;
            try {
                var u = unitsManager.create(code, name, source, type, lat, lng);
                if (n.has("color")) u.setColor(n.get("color").asText());
                if (n.has("description")) u.setDescription(n.get("description").asText());
                if (n.has("status")) {
                    String s = n.get("status").asText();
                    if (s != null && !s.isEmpty()) u.setStatus(s);
                }
                return unitsManager.exportOneJson(u.getCode());
            } catch (IllegalArgumentException e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String handleUnitBatchCreate(String body) {
        try {
            var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            int created = 0;
            for (var n : arr) {
                String code = n.has("code") ? n.get("code").asText() : null;
                if (code == null || code.isEmpty()) continue;
                String name = n.has("name") ? n.get("name").asText() : code;
                String source = n.has("source") ? n.get("source").asText() : "custom";
                String type = n.has("type") ? n.get("type").asText() : "generic";
                double lat = n.has("lat") ? n.get("lat").asDouble() : 0;
                double lng = n.has("lng") ? n.get("lng").asDouble() : 0;
                try {
                    var u = unitsManager.create(code, name, source, type, lat, lng);
                    if (n.has("color")) u.setColor(n.get("color").asText());
                    if (n.has("status")) u.setStatus(n.get("status").asText());
                    if (n.has("description")) u.setDescription(n.get("description").asText());
                    created++;
                } catch (IllegalArgumentException ignored) {}
            }
            return "{\"ok\":true,\"created\":" + created + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String handleUnitBatchDelete() {
        int n = unitsManager.count();
        unitsManager.deleteAll();
        return "{\"ok\":true,\"deleted\":" + n + "}";
    }

    private static String handleBranchCreate(String body) {
        try {
            var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            String name = n.has("name") ? n.get("name").asText() : "Untitled";
            var tree = branchManager.createTree(name);
            return "{\"ok\":true,\"treeId\":\"" + tree.getId() + "\",\"name\":\"" + esc(name) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String handleBranchAddRound(String treeId, String body) {
        try {
            var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            String parentId = n.has("parentId") ? n.get("parentId").asText() : "root";
            String name = n.has("name") ? n.get("name").asText() : "Round";
            int round = n.has("round") ? n.get("round").asInt() : 1;
            String strategy = n.has("strategy") ? n.get("strategy").asText() : "historical";
            String outcome = n.has("outcome") ? n.get("outcome").asText() : "";

            var node = branchManager.addRound(treeId, parentId, name, round, strategy, outcome);
            if (node == null) return "{\"error\":\"parent node not found\"}";
            return "{\"ok\":true,\"nodeId\":\"" + node.getId() + "\",\"round\":" + round + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static String handleDistanceQuery(String query) {
        double lat1 = getQueryParam(query, "lat1", 0);
        double lng1 = getQueryParam(query, "lng1", 0);
        double lat2 = getQueryParam(query, "lat2", 0);
        double lng2 = getQueryParam(query, "lng2", 0);
        double dist = SphericalEngine.haversineDistance(lat1, lng1, lat2, lng2);
        double bearing = SphericalEngine.bearing(lat1, lng1, lat2, lng2);
        return String.format(
            "{\"from\":{\"lat\":%.4f,\"lng\":%.4f},\"to\":{\"lat\":%.4f,\"lng\":%.4f},"
                + "\"distanceKm\":%.2f,\"bearingDeg\":%.1f}",
            lat1, lng1, lat2, lng2, dist, bearing);
    }

    private static String handleTerrainAtQuery(String query) {
        double lat = getQueryParam(query, "lat", 0);
        double lng = getQueryParam(query, "lng", 0);
        TerrainCell cell = mapManager.getTerrainAt(lat, lng);
        if (cell == null) return "{\"error\":\"no data\"}";
        List<Region> containingRegions = mapManager.getRegionsContaining(
            new GeoPoint(lat, lng));
        return String.format(
            "{\"lat\":%.4f,\"lng\":%.4f,\"terrain\":\"%s\",\"elevation\":%.0f,"
                + "\"color\":\"%s\",\"regions\":[%s]}",
            lat, lng,
            cell.getTerrain().getDisplayName(),
            cell.getElevationMeters(),
            cell.getColorHex(),
            containingRegions.stream()
                .map(r -> "\"" + r.getName() + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("")
        );
    }

    private static void handleTerrainImage(HttpExchange exchange) throws IOException {
        String q = exchange.getRequestURI().getQuery();
        double lat = getQueryParam(q, "lat", Double.NaN);
        double lng = getQueryParam(q, "lng", Double.NaN);
        double r = getQueryParam(q, "r", 100);
        int imgSize = (int) getQueryParam(q, "size", 512);
        if (Double.isNaN(lat) || Double.isNaN(lng)) {
            String err = "{\"error\":\"lat and lng required\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, err.length());
            try (OutputStream os = exchange.getResponseBody()) { os.write(err.getBytes()); }
            return;
        }

        double degRadius = r / 111.32;
        double south = lat - degRadius;
        double north = lat + degRadius;
        double west = lng - degRadius;
        double east = lng + degRadius;

        BufferedImage img = new BufferedImage(imgSize, imgSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // Background: transparent
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, imgSize, imgSize);

        com.getgoat.map.model.TerrainGrid grid = mapManager.getTerrainGrid();
        if (grid != null) {
            double cellSize = grid.getCellSizeDegrees();
            int startRow = grid.latToRow(north);   // north = smaller row
            int endRow = grid.latToRow(south);     // south = larger row
            int startCol = grid.lngToCol(west);
            int endCol = grid.lngToCol(east);

            // Handle dateline wrap
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

                    // Check if cell center is within radius
                    double dist = com.getgoat.map.geometry.SphericalEngine
                        .haversineDistance(lat, lng, cellCenterLat, cellCenterLng);
                    if (dist > r) continue;

                    // Map cell coords to image pixel coords
                    int px = (int) ((cellWest - west) / lngSpan * imgSize);
                    int py = (int) ((north - cellNorth) / latSpan * imgSize);
                    int pw = (int) Math.max(1, (cellEast - cellWest) / lngSpan * imgSize);
                    int ph = (int) Math.max(1, (cellNorth - cellSouth) / latSpan * imgSize);

                    // Get color
                    com.getgoat.map.model.TerrainCell cell = grid.getCell(row, col);
                    String hex = cell != null ? cell.getColorHex() : "#1a5276";
                    g.setColor(Color.decode(hex));
                    g.fillRect(Math.max(0, px), Math.max(0, py),
                        Math.min(imgSize - px, pw), Math.min(imgSize - py, ph));
                }
            }
        }

        // Circular mask: make pixels outside the radius circle transparent
        int cx = imgSize / 2, cy = imgSize / 2;
        double pixelsPerDeg = imgSize / (2.0 * degRadius);
        int radiusPx = (int) (r / 111.32 * pixelsPerDeg);
        for (int x = 0; x < imgSize; x++) {
            for (int y = 0; y < imgSize; y++) {
                int dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > radiusPx * radiusPx) {
                    int alpha = img.getRGB(x, y) & 0xFF000000;
                    img.setRGB(x, y, alpha); // keep alpha, clear RGB
                }
            }
        }

        g.dispose();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", bos);
        byte[] bytes = bos.toByteArray();

        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=30");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void handleTools(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String response;
        String contentType = "application/json";

        try {
            if ("/api/tools".equals(path) && "GET".equals(method)) {
                // List all tools in OpenAI format
                response = toolRegistry.listTools().toString();
            } else if ("/api/tools/dispatch".equals(path) && "POST".equals(method)) {
                // Dispatch a single tool call: {"tool_call_id":"...", "name":"...", "arguments":{...}}
                String body = new String(exchange.getRequestBody().readAllBytes());
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                String id = node.has("tool_call_id") ? node.get("tool_call_id").asText() : "call_0";
                String name = node.get("name").asText();
                var args = node.get("arguments");
                response = toolRegistry.dispatch(id, name, args);
            } else {
                response = "{\"error\":\"Unknown tools endpoint: " + path + "\"}";
            }
        } catch (Exception e) {
            response = "{\"error\":\"" + e.getMessage().replace("\"","'") + "\"}";
        }

        byte[] bytes = response.getBytes();
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private static String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        return "text/plain";
    }
}
