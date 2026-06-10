package com.getgoat.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.logging.Level;

/**
 * LLM-backed commander agent — calls Anthropic/OpenAI API.
 *
 * Conversation flow:
 *   buildConversation() → [system, user:R1, assistant:R1, tool:R1, ... user:RN]
 *   callLLM(messages)   → sends to API, returns assistant message with tool_calls
 *   submitDeployment()  → saves assistant response
 *   submitFeedback()    → saves tool execution results
 */
public class LLMCommanderAgent extends CommanderAgent {

    private static final String ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    @Override
    public JsonNode generateDeployment(String treeId, String nodeId) {
        return generateDeployment(treeId, nodeId, null);
    }

    @Override
    public JsonNode generateDeployment(String treeId, String nodeId, String guidance) {
        try {
            JsonNode result = super.generateDeployment(treeId, nodeId, guidance);
            return parseResponse(result);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "LLM call failed for " + config.getName(), e);
            ObjectNode err = MAPPER.createObjectNode();
            err.put("error", e.getMessage());
            return err;
        }
    }

    private HttpClient newHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);

    /**
     * Call the LLM with an OpenAI-format conversation array.
     *
     * For Anthropic: converts to Anthropic Messages format
     * For OpenAI: passes through directly
     */
    @Override
    public JsonNode callLLM(JsonNode messages) {
        CommanderConfig.LlmConfig llm = config.getLlm();
        if (llm == null || llm.apiKey == null || llm.apiKey.isEmpty()) {
            throw new RuntimeException("LLM not configured");
        }

        try {
            String responseBody;
            if ("openai".equalsIgnoreCase(llm.provider)) {
                responseBody = callOpenAI(llm, messages);
            } else {
                responseBody = callAnthropic(llm, messages);
            }
            return MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new RuntimeException("LLM API error: " + e.getMessage(), e);
        }
    }

    // ---- Anthropic API ----

    private String callAnthropic(CommanderConfig.LlmConfig llm, JsonNode messages) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", llm.model);
        body.put("max_tokens", llm.maxTokens);

        // Convert OpenAI messages to Anthropic format
        ArrayNode anthropicMsgs = MAPPER.createArrayNode();
        StringBuilder systemPrompt = new StringBuilder();

        for (JsonNode msg : messages) {
            String role = msg.has("role") ? msg.get("role").asText() : "";
            if ("system".equals(role)) {
                if (systemPrompt.length() > 0) systemPrompt.append("\n\n");
                systemPrompt.append(msg.get("content").asText());
                continue;
            }
            anthropicMsgs.add(convertToAnthropic(msg));
        }

        if (systemPrompt.length() > 0) {
            body.put("system", systemPrompt.toString());
        }
        body.set("messages", anthropicMsgs);

        // Tool definitions from the active mode
        if (mode != null) body.set("tools", convertToAnthropicTools(mode.getToolDefinitions()));

        String json = MAPPER.writeValueAsString(body);
        String endpoint = llm.endpoint != null ? llm.endpoint : ANTHROPIC_ENDPOINT;

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("x-api-key", llm.apiKey)
            .header("anthropic-version", "2023-06-01")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        try {
            HttpResponse<String> resp = newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("API " + resp.statusCode() + ": " + resp.body());
            }
            return convertAnthropicResponse(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM call interrupted", e);
        }
    }

    /** Convert one OpenAI-format message to Anthropic content blocks. */
    private JsonNode convertToAnthropic(JsonNode msg) {
        String role = msg.has("role") ? msg.get("role").asText() : "user";
        ObjectNode am = MAPPER.createObjectNode();
        am.put("role", role.equals("assistant") ? "assistant" : "user");

        if (role.equals("tool")) {
            // Tool result → user message with tool_result content
            ArrayNode content = am.putArray("content");
            ObjectNode tr = content.addObject();
            tr.put("type", "tool_result");
            tr.put("tool_use_id", msg.has("tool_call_id") ? msg.get("tool_call_id").asText() : "");
            tr.put("content", msg.has("content") ? msg.get("content").asText() : "");
            return am;
        }

        if (role.equals("assistant") && msg.has("tool_calls")) {
            ArrayNode content = am.putArray("content");
            // Add text if present
            if (msg.has("content") && !msg.get("content").isNull()) {
                ObjectNode textBlock = content.addObject();
                textBlock.put("type", "text");
                textBlock.put("text", msg.get("content").asText());
            }
            // Add tool_use blocks
            JsonNode tcArray = msg.get("tool_calls");
            for (JsonNode tc : tcArray) {
                ObjectNode tu = content.addObject();
                tu.put("type", "tool_use");
                tu.put("id", tc.has("id") ? tc.get("id").asText() : ("call_" + System.nanoTime()));
                JsonNode fn = tc.get("function");
                tu.put("name", fn != null && fn.has("name") ? fn.get("name").asText() : "unknown");
                tu.set("input", fn != null && fn.has("arguments") ? parseArgs(fn.get("arguments")) : MAPPER.createObjectNode());
            }
            return am;
        }

        // Plain user/assistant text message
        am.put("content", msg.has("content") && !msg.get("content").isNull()
            ? msg.get("content").asText() : "");
        return am;
    }

    /** Parse arguments that might be a JSON string. */
    private JsonNode parseArgs(JsonNode args) {
        if (args.isTextual()) {
            try { return MAPPER.readTree(args.asText()); }
            catch (Exception e) { return MAPPER.createObjectNode(); }
        }
        return args;
    }

    /** Build Anthropic-format tool definitions from the mode's OpenAI-format definitions. */
    private ArrayNode convertToAnthropicTools(JsonNode openaiTools) {
        ArrayNode tools = MAPPER.createArrayNode();
        for (JsonNode t : openaiTools) {
            JsonNode fn = t.has("function") ? t.get("function") : t;
            if (fn == null) continue;
            ObjectNode tool = tools.addObject();
            tool.put("name", fn.has("name") ? fn.get("name").asText() : "");
            tool.put("description", fn.has("description") ? fn.get("description").asText() : "");
            if (fn.has("parameters")) {
                tool.set("input_schema", fn.get("parameters"));
            } else {
                tool.putObject("input_schema").put("type", "object");
            }
        }
        return tools;
    }

    /** Convert Anthropic response to OpenAI-compatible format. */
    private String convertAnthropicResponse(String responseBody) throws IOException {
        JsonNode resp = MAPPER.readTree(responseBody);
        ObjectNode result = MAPPER.createObjectNode();

        JsonNode content = resp.get("content");
        if (content != null && content.isArray()) {
            ObjectNode choice = result.putArray("choices").addObject();
            ObjectNode msg = choice.putObject("message");
            msg.put("role", "assistant");

            StringBuilder text = new StringBuilder();
            ArrayNode toolCalls = msg.putArray("tool_calls");

            for (JsonNode block : content) {
                String type = block.has("type") ? block.get("type").asText() : "";
                if ("text".equals(type)) {
                    text.append(block.get("text").asText());
                } else if ("tool_use".equals(type)) {
                    ObjectNode tc = MAPPER.createObjectNode();
                    tc.put("id", block.get("id").asText());
                    tc.put("type", "function");
                    ObjectNode fn = tc.putObject("function");
                    fn.put("name", block.get("name").asText());
                    fn.set("arguments", block.get("input"));
                    toolCalls.add(tc);
                }
            }
            if (text.length() > 0) msg.put("content", text.toString());
            else msg.putNull("content");
        }
        return MAPPER.writeValueAsString(result);
    }

    // ---- OpenAI API ----

    private String callOpenAI(CommanderConfig.LlmConfig llm, JsonNode messages) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", llm.model);
        body.put("max_tokens", llm.maxTokens);
        body.set("messages", messages);

        // Tool definitions from the active mode
        if (mode != null) body.set("tools", mode.getToolDefinitions());
        body.put("tool_choice", "auto");

        String json = MAPPER.writeValueAsString(body);
        String endpoint = llm.endpoint != null ? llm.endpoint : OPENAI_ENDPOINT;

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + llm.apiKey)
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        try {
            HttpResponse<String> resp = newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("OpenAI API " + resp.statusCode() + ": " + resp.body());
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM call interrupted", e);
        }
    }


    // ---- Response parsing ----

    private JsonNode parseResponse(JsonNode apiResponse) {
        LOG.info("LLM raw response keys: " + apiResponse.fieldNames().toString());
        JsonNode choices = apiResponse.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode choice = choices.get(0);
            JsonNode msg = choice.get("message");
            if (msg != null) {
                ObjectNode result = MAPPER.createObjectNode();
                // Prefer reasoning_content (DeepSeek) over content for rationale
                String rationale = null;
                if (msg.has("reasoning_content") && !msg.get("reasoning_content").isNull())
                    rationale = msg.get("reasoning_content").asText();
                else if (msg.has("content") && !msg.get("content").isNull())
                    rationale = msg.get("content").asText();
                if (rationale != null) result.put("rationale", rationale);
                // Capture content separately if both exist
                if (msg.has("reasoning_content") && msg.has("content") && !msg.get("content").isNull())
                    result.put("summary", msg.get("content").asText());
                if (msg.has("tool_calls")) {
                    result.set("plan", msg.get("tool_calls"));
                } else {
                    result.set("plan", MAPPER.createArrayNode());
                }
                return result;
            }
        }
        // Fallback: return raw response
        return apiResponse;
    }
}
