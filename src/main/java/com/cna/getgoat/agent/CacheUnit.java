package com.cna.getgoat.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal unit of a conversation message (OpenAI format).
 * Supports roles: system, user, assistant, tool.
 * A sequence of CacheUnit fed to Cache renders a valid LLM conversation.
 */
public class CacheUnit {
    private final String role;
    private final String content;
    private final List<Map<String, Object>> toolCalls;
    private final String toolCallId;   // for role=tool

    private CacheUnit(String role, String content, List<Map<String, Object>> toolCalls, String toolCallId) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
    }

    public static CacheUnit system(String content) {
        return new CacheUnit("system", content, null, null);
    }

    public static CacheUnit user(String content) {
        return new CacheUnit("user", content, null, null);
    }

    public static CacheUnit assistant(String content) {
        return new CacheUnit("assistant", content, null, null);
    }

    public static CacheUnit assistant(String content, List<Map<String, Object>> toolCalls) {
        return new CacheUnit("assistant", content, toolCalls, null);
    }

    public static CacheUnit tool(String callId, String result) {
        return new CacheUnit("tool", result, null, callId);
    }

    public String getRole() { return role; }
    public String getContent() { return content; }
    public List<Map<String, Object>> getToolCalls() { return toolCalls; }
    public String getToolCallId() { return toolCallId; }

    /** Render this unit as an OpenAI-format JSON object node. */
    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        if (content != null) node.put("content", content);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            ArrayNode tcArray = mapper.createArrayNode();
            for (var tc : toolCalls) {
                ObjectNode tcNode = mapper.createObjectNode();
                tcNode.put("id", (String) tc.getOrDefault("id", ""));
                tcNode.put("type", "function");
                ObjectNode fn = mapper.createObjectNode();
                fn.put("name", (String) tc.getOrDefault("name", ""));
                fn.put("arguments", (String) tc.getOrDefault("arguments", "{}"));
                tcNode.set("function", fn);
                tcArray.add(tcNode);
            }
            node.set("tool_calls", tcArray);
        }
        if (toolCallId != null) node.put("tool_call_id", toolCallId);
        return node;
    }
}
