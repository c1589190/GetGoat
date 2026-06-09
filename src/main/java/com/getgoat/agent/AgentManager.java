package com.getgoat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getgoat.map.branch.BranchManager;
import com.getgoat.map.branch.CommanderAction;
import com.getgoat.map.manager.UnitsManager;
import com.getgoat.map.manager.MapManager;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class AgentManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BranchManager branchManager;
    private final UnitsManager unitsManager;
    private final MapManager mapManager;
    private Path workspaceDir;
    private final Map<String, CommanderAgent> agentsBySide = new LinkedHashMap<>();

    public AgentManager(BranchManager bm, UnitsManager um, MapManager mm) {
        this.branchManager = bm;
        this.unitsManager = um;
        this.mapManager = mm;
    }

    public void setWorkspace(Path workspace) {
        this.workspaceDir = workspace;
        agentsBySide.clear();
        Path cmdDir = workspace.resolve("commanders");
        if (!Files.exists(cmdDir)) return;
        try {
            Files.list(cmdDir).forEach(sideDir -> {
                Path cfgFile = sideDir.resolve("config.json");
                if (!Files.exists(cfgFile)) return;
                try {
                    CommanderConfig cfg = CommanderConfig.load(cfgFile);
                    CommanderAgent agent = createAgent(cfg);
                    agent.initialize(cfg, branchManager, unitsManager, mapManager, workspace);
                    agentsBySide.put(cfg.getSide(), agent);
                } catch (IOException e) {
                    System.err.println("Failed to load commander: " + cfgFile + " — " + e.getMessage());
                }
            });
        } catch (IOException ignored) {}
        System.out.println("Loaded " + agentsBySide.size() + " commander(s)");
    }

    private CommanderAgent createAgent(CommanderConfig cfg) {
        if (cfg.getLlm() != null && cfg.getLlm().apiKey != null && !cfg.getLlm().apiKey.isEmpty())
            return new LLMCommanderAgent();
        return new CommanderAgent() {
            @Override public JsonNode callLLM(JsonNode messages) {
                var n = MAPPER.createObjectNode();
                n.put("rationale", "LLM not configured");
                n.set("plan", MAPPER.createArrayNode());
                return n;
            }
        };
    }

    public CommanderAgent getAgent(String side) { return agentsBySide.get(side); }
    public Set<String> getSides() { return agentsBySide.keySet(); }

    // ---- Delegates ----

    public String getSystemPrompt(String side) {
        CommanderAgent a = getAgent(side);
        return a != null ? a.getConfig().getSystemPrompt() : "Not configured";
    }

    public void setSystemPrompt(String side, String content) throws IOException {
        CommanderAgent a = getAgent(side);
        if (a == null) throw new IOException("No agent: " + side);
        a.getConfig().setSystemPrompt(content);
        Path f = Path.of(workspaceDir.toString(), "commanders", side,
            a.getConfig().getSystemPromptFile());
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    public String buildContext(String side, String treeId, String nodeId, String guidance) {
        CommanderAgent a = getAgent(side);
        return a != null ? a.buildPrompt(treeId, nodeId, guidance) : "Not configured";
    }
    public String buildContext(String side, String treeId, String nodeId) {
        return buildContext(side, treeId, nodeId, null);
    }

    /** Multi-round deployment. */
    public CommanderAction executeFullRound(String side, String treeId, String nodeId,
                                            String guidance, int maxIterations) throws IOException {
        CommanderAgent a = getAgent(side);
        if (a == null) throw new IOException("No agent: " + side);
        return a.executeFullRound(treeId, nodeId, guidance, maxIterations);
    }

    public JsonNode generateDeployment(String side, String treeId, String nodeId, String guidance) {
        CommanderAgent a = getAgent(side);
        if (a == null) { var n = MAPPER.createObjectNode(); n.put("error", "No agent: " + side); return n; }
        return a.generateDeployment(treeId, nodeId, guidance);
    }

    public void submitDeployment(String side, String treeId, String nodeId, JsonNode result) throws IOException {
        CommanderAgent a = getAgent(side);
        if (a != null) a.submitDeployment(treeId, nodeId, result);
    }

    public void submitFeedback(String side, String treeId, String nodeId, String feedback) throws IOException {
        CommanderAgent a = getAgent(side);
        if (a != null) a.submitFeedback(treeId, nodeId, feedback);
    }

    public void setGuidance(String side, String treeId, String nodeId, String guidance) throws IOException {
        CommanderAgent a = getAgent(side);
        if (a != null) a.setGuidance(treeId, nodeId, guidance);
    }
}
