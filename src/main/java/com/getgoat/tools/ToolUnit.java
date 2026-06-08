package com.getgoat.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single tool in OpenAI function-calling format.
 *
 * Each implementation provides:
 *   1. definition  — the OpenAI function schema (name, description, parameters)
 *   2. execute     — run the tool with JSON arguments, return result object
 *   3. feedback    — format the result as a role=tool assistant message
 */
public interface ToolUnit {

    /** Unique tool name, e.g. "get_terrain" */
    String getName();

    /** OpenAI function definition as JSON. Must include name, description, parameters. */
    JsonNode getDefinition();

    /**
     * Execute the tool with parsed JSON arguments.
     * @return result as a Jackson JsonNode (object, array, or value)
     */
    JsonNode execute(JsonNode args) throws Exception;

    /**
     * Format the execution result as an OpenAI assistant message.
     * @param toolCallId  the tool_call_id from the request
     * @param result      the output from execute()
     */
    default String feedback(String toolCallId, JsonNode result) {
        return String.format(
            "{\"role\":\"tool\",\"tool_call_id\":\"%s\",\"content\":%s}",
            esc(toolCallId), result.toString());
    }

    /** Brief description for tool listing. */
    default String getDescription() {
        JsonNode def = getDefinition();
        if (def != null && def.has("description"))
            return def.get("description").asText();
        return getName();
    }

    private static String esc(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\","\\\\").replace("\"","\\\"") + "\"";
    }
}
