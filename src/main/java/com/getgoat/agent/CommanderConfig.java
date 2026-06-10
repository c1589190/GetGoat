package com.getgoat.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getgoat.map.ConfigManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-commander configuration loaded from config.json.
 *
 * <pre>
 * {
 *   "name": "矶谷廉介",
 *   "side": "japanese",
 *   "llm": { "provider": "anthropic", "model": "claude-sonnet-4-6", "apiKey": "env:ANTHROPIC_API_KEY" },
 *   "systemPromptFile": "system.md"
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommanderConfig {

    private String name;
    private String side;

    @JsonProperty("llm")
    private LlmConfig llm;

    @JsonProperty("systemPromptFile")
    private String systemPromptFile = "system.md";

    // transient — loaded on initialize
    private String systemPrompt;
    private Path baseDir;

    public CommanderConfig() {}

    // ---- Getters ----

    public String getName()          { return name; }
    public String getSide()          { return side; }
    public LlmConfig getLlm()        { return llm; }
    public String getSystemPromptFile() { return systemPromptFile; }
    public String getSystemPrompt()  { return systemPrompt; }
    public Path getBaseDir()         { return baseDir; }

    // ---- Setters ----

    public void setName(String name)                   { this.name = name; }
    public void setSide(String side)                   { this.side = side; }
    public void setLlm(LlmConfig llm)                  { this.llm = llm; }
    public void setSystemPromptFile(String f)          { this.systemPromptFile = f; }
    public void setSystemPrompt(String prompt)         { this.systemPrompt = prompt; }
    public void setBaseDir(Path dir)                   { this.baseDir = dir; }

    // ---- Load ----

    public static CommanderConfig load(Path configFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        CommanderConfig cfg = mapper.readValue(configFile.toFile(), CommanderConfig.class);
        cfg.baseDir = configFile.getParent();

        // Merge global LLM defaults from config.properties (per-commander config overrides)
        if (cfg.llm == null) cfg.llm = new LlmConfig();
        LlmConfig c = cfg.llm;
        if (c.provider == null || c.provider.isEmpty()) c.provider = ConfigManager.getLlmProvider();
        if (c.model == null || c.model.isEmpty()) c.model = ConfigManager.getLlmModel();
        if (c.endpoint == null || c.endpoint.isEmpty()) c.endpoint = ConfigManager.getLlmEndpoint();
        if (c.apiKey == null || c.apiKey.isEmpty()) c.apiKey = ConfigManager.getLlmApiKey();
        else if (c.apiKey.startsWith("env:")) {
            String envVar = c.apiKey.substring(4);
            String resolved = System.getenv(envVar);
            c.apiKey = resolved != null ? resolved : "";
        }
        if (c.maxTokens <= 0) c.maxTokens = ConfigManager.getLlmMaxTokens();

        // Load system prompt from file
        Path spFile = cfg.baseDir.resolve(cfg.systemPromptFile);
        if (Files.exists(spFile)) {
            cfg.systemPrompt = Files.readString(spFile);
        } else {
            cfg.systemPrompt = "# " + cfg.name + " (" + cfg.side + ") commander prompt\n\nNot configured.";
        }

        return cfg;
    }

    /**
     * LLM connection configuration.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmConfig {
        public String provider;     // "anthropic", "openai", "local"
        public String model;        // "claude-sonnet-4-6", etc.
        public String apiKey;
        @JsonProperty("maxTokens")
        public int maxTokens = 4096;
        public String endpoint;     // optional custom endpoint

        public LlmConfig() {}
    }
}
