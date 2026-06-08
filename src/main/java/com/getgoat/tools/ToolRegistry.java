package com.getgoat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * Registers tools and provides OpenAI-compatible listing + dispatch.
 */
public class ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, ToolUnit> tools = new LinkedHashMap<>();

    public void register(ToolUnit tool) {
        tools.put(tool.getName(), tool);
    }

    public ToolUnit get(String name) { return tools.get(name); }
    public Collection<ToolUnit> all() { return tools.values(); }

    /** Full OpenAI tools array (for API request). */
    public JsonNode listTools() {
        ArrayNode arr = MAPPER.createArrayNode();
        for (ToolUnit t : tools.values()) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("type", "function");
            item.set("function", t.getDefinition());
            arr.add(item);
        }
        return arr;
    }

    /** Dispatch a tool call and return the assistant feedback message. */
    public String dispatch(String toolCallId, String name, JsonNode args) {
        ToolUnit tool = tools.get(name);
        if (tool == null) {
            return error(toolCallId, "Unknown tool: " + name);
        }
        try {
            JsonNode result = tool.execute(args);
            return tool.feedback(toolCallId, result);
        } catch (Exception e) {
            return error(toolCallId, e.getMessage());
        }
    }

    private String error(String id, String msg) {
        return "{\"role\":\"tool\",\"tool_call_id\":\"" + id
            + "\",\"content\":\"[error] " + msg.replace("\"","'") + "\"}";
    }

    // -- Jackson helpers for subclasses --

    public static ObjectMapper mapper() { return MAPPER; }
    public static ObjectNode objectNode() { return MAPPER.createObjectNode(); }
    public static ArrayNode arrayNode() { return MAPPER.createArrayNode(); }
    public static ObjectNode paramObj() { return MAPPER.createObjectNode(); }

    public static ObjectNode stringParam(String desc) {
        return objectNode().put("type", "string").put("description", desc);
    }
    public static ObjectNode numberParam(String desc) {
        return objectNode().put("type", "number").put("description", desc);
    }
    public static ObjectNode boolParam(String desc) {
        return objectNode().put("type", "boolean").put("description", desc);
    }

    public static double d(JsonNode n, String key, double def) {
        return n.has(key) ? n.get(key).asDouble() : def;
    }
    public static String s(JsonNode n, String key, String def) {
        return n.has(key) ? n.get(key).asText() : def;
    }
    public static boolean b(JsonNode n, String key, boolean def) {
        return n.has(key) ? n.get(key).asBoolean() : def;
    }
}
