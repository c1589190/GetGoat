package com.cna.getgoat.llm;

import com.cna.getgoat.agent.Cache;
import com.cna.getgoat.config.CommanderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Unified LLM call manager. Takes a configured Cache and calls the appropriate LLM provider,
 * returning the result. Handles Anthropic ↔ OpenAI format translation internally.
 */
public class LLManager {
    private static final Logger LOG = Logger.getLogger(LLManager.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final CommanderConfig.LlmConfig config;
    private final HttpClient httpClient;

    public LLManager(CommanderConfig.LlmConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    }

    /** Call LLM with the given cache. Returns JSON response (OpenAI format). */
    public JsonNode call(Cache cache) throws IOException {
        return callWithRetry(cache, 1);
    }

    private JsonNode callWithRetry(Cache cache, int retries) throws IOException {
        ArrayNode messages = cache.toMessages();
        String responseBody;
        try {
            if ("openai".equalsIgnoreCase(config.provider)) {
                responseBody = callOpenAI(messages);
            } else {
                responseBody = callAnthropic(messages);
            }
            return MAPPER.readTree(responseBody);
        } catch (IOException e) {
            if (retries > 0) {
                LOG.warning("LLM call failed, retrying: " + e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return callWithRetry(cache, retries - 1);
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM call interrupted", e);
        }
    }

    private String callOpenAI(ArrayNode messages) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.model != null ? config.model : "deepseek-v4-pro");
        body.set("messages", messages);
        body.put("max_tokens", config.maxTokens);
        body.put("temperature", 0.7);

        String endpoint = config.endpoint != null ? config.endpoint : "https://api.openai.com/v1/chat/completions";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Authorization", "Bearer " + config.apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .timeout(REQUEST_TIMEOUT)
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("OpenAI API " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private String callAnthropic(ArrayNode messages) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.model != null ? config.model : "claude-sonnet-4-6");
        body.put("max_tokens", config.maxTokens);

        // Convert OpenAI messages → Anthropic content blocks
        ArrayNode anthropicMessages = MAPPER.createArrayNode();
        StringBuilder systemPrompt = new StringBuilder();
        for (JsonNode msg : messages) {
            String role = msg.has("role") ? msg.get("role").asText() : "user";
            if ("system".equals(role)) {
                if (systemPrompt.length() > 0) systemPrompt.append("\n");
                systemPrompt.append(msg.get("content").asText());
                continue;
            }
            ObjectNode am = MAPPER.createObjectNode();
            am.put("role", "user".equals(role) ? "user" : "assistant");
            if (msg.has("tool_calls")) {
                ArrayNode content = MAPPER.createArrayNode();
                if (msg.has("content") && !msg.get("content").isNull()) {
                    ObjectNode textBlock = MAPPER.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", msg.get("content").asText());
                    content.add(textBlock);
                }
                for (JsonNode tc : msg.get("tool_calls")) {
                    ObjectNode toolBlock = MAPPER.createObjectNode();
                    toolBlock.put("type", "tool_use");
                    toolBlock.put("id", tc.get("id").asText());
                    toolBlock.put("name", tc.get("function").get("name").asText());
                    try {
                        toolBlock.set("input", MAPPER.readTree(tc.get("function").get("arguments").asText()));
                    } catch (Exception e) {
                        toolBlock.put("input", MAPPER.createObjectNode());
                    }
                    content.add(toolBlock);
                }
                am.set("content", content);
            } else if ("tool".equals(role)) {
                ArrayNode content = MAPPER.createArrayNode();
                ObjectNode tr = MAPPER.createObjectNode();
                tr.put("type", "tool_result");
                tr.put("tool_use_id", msg.has("tool_call_id") ? msg.get("tool_call_id").asText() : "");
                tr.put("content", msg.get("content").asText());
                content.add(tr);
                am.set("content", content);
            } else {
                am.put("content", msg.has("content") ? msg.get("content").asText() : "");
            }
            anthropicMessages.add(am);
        }
        body.set("messages", anthropicMessages);
        if (systemPrompt.length() > 0) body.put("system", systemPrompt.toString());

        String endpoint = config.endpoint != null ? config.endpoint : "https://api.anthropic.com/v1/messages";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .timeout(REQUEST_TIMEOUT)
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Anthropic API " + resp.statusCode() + ": " + resp.body());
        }

        // Convert Anthropic response → OpenAI format
        JsonNode ar = MAPPER.readTree(resp.body());
        ObjectNode openAiResp = MAPPER.createObjectNode();
        ArrayNode choices = MAPPER.createArrayNode();
        ObjectNode choice = MAPPER.createObjectNode();
        choice.put("index", 0);
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");

        // Extract text content and tool_calls
        StringBuilder textContent = new StringBuilder();
        ArrayNode toolCallsArr = MAPPER.createArrayNode();
        for (JsonNode block : ar.get("content")) {
            if ("text".equals(block.get("type").asText())) {
                textContent.append(block.get("text").asText());
            } else if ("tool_use".equals(block.get("type").asText())) {
                ObjectNode tc = MAPPER.createObjectNode();
                tc.put("id", block.get("id").asText());
                tc.put("type", "function");
                ObjectNode fn = MAPPER.createObjectNode();
                fn.put("name", block.get("name").asText());
                fn.put("arguments", block.get("input").toString());
                tc.set("function", fn);
                toolCallsArr.add(tc);
            }
        }
        message.put("content", textContent.toString());
        if (toolCallsArr.size() > 0) message.set("tool_calls", toolCallsArr);
        choice.set("message", message);
        choice.put("finish_reason", ar.has("stop_reason") ? ar.get("stop_reason").asText() : "stop");
        choices.add(choice);
        openAiResp.set("choices", choices);

        return openAiResp.toString();
    }
}
