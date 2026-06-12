package com.cna.getgoat.agent;

import com.cna.getgoat.config.CommanderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cna.getgoat.map.campaigns.NodesManager;
import com.cna.getgoat.map.campaigns.node.CommanderAction;
import com.cna.getgoat.map.campaigns.UnitsManager;
import com.cna.getgoat.map.MapManager;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class AgentManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NodesManager branchManager;
    private final UnitsManager unitsManager;
    private final MapManager mapManager;
    private Path workspaceDir;
    private final Map<String, Commander> agentsBySide = new LinkedHashMap<>();

    public AgentManager(NodesManager bm, UnitsManager um, MapManager mm) {
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
                    Commander agent = createAgent(cfg);
                    agent.initialize(cfg, branchManager, unitsManager, mapManager, workspace);
                    agentsBySide.put(cfg.getSide(), agent);
                } catch (IOException e) {
                    System.err.println("Failed to load commander: " + cfgFile + " — " + e.getMessage());
                }
            });
        } catch (IOException ignored) {}
        System.out.println("Loaded " + agentsBySide.size() + " commander(s)");
    }

    private Commander createAgent(CommanderConfig cfg) {
        if (cfg.getLlm() != null && cfg.getLlm().apiKey != null && !cfg.getLlm().apiKey.isEmpty())
            return new LLMCommanderAgent();
        return new Commander() {
            @Override public JsonNode callLLM(JsonNode messages) {
                var n = MAPPER.createObjectNode();
                n.put("rationale", "LLM not configured");
                n.set("plan", MAPPER.createArrayNode());
                return n;
            }
        };
    }

    public Commander getAgent(String side) { return agentsBySide.get(side); }
    public Set<String> getSides() { return agentsBySide.keySet(); }

    // ---- Delegates ----

    public String getSystemPrompt(String side) {
        Commander a = getAgent(side);
        return a != null ? a.getConfig().getSystemPrompt() : "Not configured";
    }

    public void setSystemPrompt(String side, String content) throws IOException {
        Commander a = getAgent(side);
        if (a == null) throw new IOException("No agent: " + side);
        a.getConfig().setSystemPrompt(content);
        Path f = Path.of(workspaceDir.toString(), "commanders", side,
            a.getConfig().getSystemPromptFile());
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    public String buildContext(String side, String treeId, String nodeId, String guidance) {
        Commander a = getAgent(side);
        return a != null ? a.buildPrompt(treeId, nodeId, guidance) : "Not configured";
    }
    public String buildContext(String side, String treeId, String nodeId) {
        return buildContext(side, treeId, nodeId, null);
    }

    /** Multi-round deployment. */
    public CommanderAction executeFullRound(String side, String treeId, String nodeId,
                                            String guidance, int maxIterations) throws IOException {
        Commander a = getAgent(side);
        if (a == null) throw new IOException("No agent: " + side);
        return a.executeFullRound(treeId, nodeId, guidance, maxIterations);
    }

    /** Multi-round deployment with streaming callback — invoked after each sub-round. */
    public CommanderAction executeFullRoundStreaming(String side, String treeId, String nodeId,
            String guidance, int maxIterations,
            java.util.function.Consumer<CommanderAction.SubRound> onSubRound) throws IOException {
        Commander a = getAgent(side);
        if (a == null) throw new IOException("No agent: " + side);
        return a.executeFullRound(treeId, nodeId, guidance, maxIterations, onSubRound);
    }

    public JsonNode generateDeployment(String side, String treeId, String nodeId, String guidance) {
        Commander a = getAgent(side);
        if (a == null) { var n = MAPPER.createObjectNode(); n.put("error", "No agent: " + side); return n; }
        return a.generateDeployment(treeId, nodeId, guidance);
    }

    public void submitDeployment(String side, String treeId, String nodeId, JsonNode result) throws IOException {
        Commander a = getAgent(side);
        if (a != null) a.submitDeployment(treeId, nodeId, result);
    }

    public void submitFeedback(String side, String treeId, String nodeId, String feedback) throws IOException {
        Commander a = getAgent(side);
        if (a != null) a.submitFeedback(treeId, nodeId, feedback);
    }

    public void setGuidance(String side, String treeId, String nodeId, String guidance) throws IOException {
        Commander a = getAgent(side);
        if (a != null) a.setGuidance(treeId, nodeId, guidance);
    }
}
