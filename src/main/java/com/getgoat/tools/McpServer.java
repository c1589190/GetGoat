package com.getgoat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getgoat.map.ConfigManager;
import com.getgoat.map.manager.MapManager;
import com.getgoat.map.manager.UnitsManager;

import java.io.*;
import java.util.logging.*;

/**
 * MCP (Model Context Protocol) stdio server.
 *
 * Reads JSON-RPC 2.0 requests from stdin, writes responses to stdout.
 * Stderr is reserved for logging (java.util.logging → stderr).
 *
 * Usage:
 *   java -cp target/classes:$(mvn dependency:build-classpath -q -DincludeScope=runtime) com.getgoat.tools.McpServer
 *
 * Register in Claude Code settings.json:
 *   { "mcpServers": { "getgoat": { "command": "java", "args": ["-cp", "...", "com.getgoat.tools.McpServer"] } } }
 */
public class McpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "GetGoat Map";

    private final MapManager mapManager;
    private final UnitsManager unitsManager;
    private final ToolRegistry registry;

    public McpServer() {
        this.mapManager = new MapManager();
        this.unitsManager = new UnitsManager();
        this.registry = new ToolRegistry();
        registerTools();
    }

    private void registerTools() {
        registry.register(new GetTerrainTool(mapManager));
        registry.register(new RadiusQueryTool(mapManager));
        registry.register(new DistanceTool());
        registry.register(new FindPathTool(mapManager));
        registry.register(new ReliefProfileTool(mapManager));
        registry.register(new UnitListTool(unitsManager));
        registry.register(new UnitGetTool(unitsManager));
        registry.register(new UnitCreateTool(unitsManager));
        registry.register(new UnitMoveTool(unitsManager));
        registry.register(new UnitDeleteTool(unitsManager));
    }

    public void init() {
        double res = ConfigManager.getGridCellSize();
        log("Initializing map at " + res + "° resolution...");
        mapManager.initialize(res);
        log("Map ready: " + mapManager.getStats().totalCells() + " cells");
    }

    // ---- MCP JSON-RPC loop ----

    public void run() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out), true);

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            try {
                JsonNode req = MAPPER.readTree(line);
                String method = req.has("method") ? req.get("method").asText() : "";
                String id = req.has("id") && !req.get("id").isNull()
                    ? req.get("id").toString() : null;

                switch (method) {
                    case "initialize" -> out.println(response(id, handleInitialize(req)));
                    case "notifications/initialized" -> { /* no response */ }
                    case "tools/list" -> out.println(response(id, handleToolsList()));
                    case "tools/call" -> out.println(response(id, handleToolCall(req)));
                    default -> out.println(error(id, -32601, "Method not found: " + method));
                }
            } catch (Exception e) {
                log("Error processing request: " + e.getMessage());
                out.println(error(null, -32700, "Parse error: " + e.getMessage()));
            }
        }
    }

    // ---- Handler methods ----

    private JsonNode handleInitialize(JsonNode req) {
        ObjectNode caps = MAPPER.createObjectNode();
        caps.set("tools", MAPPER.createObjectNode());
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.set("capabilities", caps);
        ObjectNode info = MAPPER.createObjectNode();
        info.put("name", SERVER_NAME);
        info.put("version", "0.1.0");
        result.set("serverInfo", info);
        return result;
    }

    private JsonNode handleToolsList() {
        ArrayNode tools = MAPPER.createArrayNode();
        for (ToolUnit t : registry.all()) {
            ObjectNode tool = MAPPER.createObjectNode();
            tool.put("name", t.getName());
            JsonNode def = t.getDefinition();
            if (def.has("description")) tool.put("description", def.get("description").asText());
            // Convert OpenAI "parameters" to MCP "inputSchema"
            if (def.has("parameters")) tool.set("inputSchema", def.get("parameters"));
            tools.add(tool);
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private JsonNode handleToolCall(JsonNode req) {
        JsonNode params = req.get("params");
        if (params == null || !params.has("name"))
            return errorNode(-32602, "Missing tool name");

        String toolName = params.get("name").asText();
        JsonNode args = params.has("arguments") ? params.get("arguments")
            : MAPPER.createObjectNode();

        ToolUnit tool = registry.get(toolName);
        if (tool == null) return errorNode(-32602, "Unknown tool: " + toolName);

        try {
            JsonNode result = tool.execute(args);
            String text = result.toString();
            ObjectNode content = MAPPER.createObjectNode();
            content.put("type", "text");
            content.put("text", text);
            ArrayNode arr = MAPPER.createArrayNode();
            arr.add(content);
            ObjectNode out = MAPPER.createObjectNode();
            out.set("content", arr);
            return out;
        } catch (Exception e) {
            return errorNode(-32000, "Tool error: " + e.getMessage());
        }
    }

    // ---- JSON-RPC helpers ----

    private String response(String id, JsonNode result) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("jsonrpc", "2.0");
        if (id != null) r.set("id", idToNode(id));
        r.set("result", result);
        return r.toString();
    }

    private String error(String id, int code, String message) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("jsonrpc", "2.0");
        if (id != null) r.set("id", idToNode(id));
        ObjectNode e = MAPPER.createObjectNode();
        e.put("code", code);
        e.put("message", message);
        r.set("error", e);
        return r.toString();
    }

    private JsonNode errorNode(int code, String message) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("code", code);
        e.put("message", message);
        ObjectNode r = MAPPER.createObjectNode();
        r.set("error", e);
        return r;
    }

    private JsonNode idToNode(String id) {
        try { return MAPPER.readTree(id); }
        catch (Exception ex) { return MAPPER.createObjectNode().put("raw", id); }
    }

    // ---- Entry point ----

    public static void main(String[] args) {
        // Route java.util.logging to stderr (MCP protocol uses stdout for JSON-RPC)
        Logger rootLog = Logger.getLogger("");
        for (Handler h : rootLog.getHandlers()) rootLog.removeHandler(h);
        StreamHandler sh = new StreamHandler(System.err, new SimpleFormatter());
        sh.setLevel(Level.INFO);
        rootLog.addHandler(sh);
        rootLog.setLevel(Level.INFO);

        try {
            McpServer server = new McpServer();
            server.init();
            server.run();
        } catch (Exception e) {
            System.err.println("FATAL: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void log(String msg) {
        Logger.getLogger(McpServer.class.getName()).info(msg);
    }
}
