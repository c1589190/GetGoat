package com.cna.getgoat.agent;

import com.cna.getgoat.config.CommanderConfig;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.cna.getgoat.map.campaigns.node.*;
import com.cna.getgoat.map.campaigns.NodesManager;
import com.cna.getgoat.map.campaigns.UnitsManager;
import com.cna.getgoat.map.MapManager;
import com.cna.getgoat.map.campaigns.unit.Unit;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Unified abstract agent — the shared foundation for all agent capabilities.
 *
 * Owns the multi-round LLM conversation loop, tool execution dispatch, branch-tree
 * traversal, and context replay. The {@link AgentMode} controls what the agent
 * actually DOES (command, intel gathering, simulation, training).
 *
 * Subclasses only need to implement {@link #callLLM(JsonNode)} for their LLM
 * provider (see {@link LLMCommanderAgent}).
 */
public abstract class AbstractAgent {

    protected static final Logger LOG = Logger.getLogger(AbstractAgent.class.getName());
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final int MAX_SUBROUNDS = 32;

    protected CommanderConfig config;
    protected NodesManager branchManager;
    protected UnitsManager unitsManager;
    protected MapManager mapManager;
    protected Path workspaceDir;
    protected AgentMode mode;

    // ---- Lifecycle ----

    public void initialize(CommanderConfig cfg, NodesManager bm, UnitsManager um,
                           MapManager mm, Path workspace) {
        this.config = cfg;
        this.branchManager = bm;
        this.unitsManager = um;
        this.mapManager = mm;
        this.workspaceDir = workspace;
        LOG.info("Initialized agent: " + cfg.getName() + " (" + cfg.getSide() + ")");
    }

    /** Set the working mode. Must be called before executeRound(). */
    public void setMode(AgentMode mode) {
        this.mode = mode;
    }

    public AgentMode getMode() { return mode; }
    public CommanderConfig getConfig() { return config; }
    public Path getWorkspaceDir() { return workspaceDir; }
    public NodesManager getNodesManager() { return branchManager; }
    public UnitsManager getUnitsManager() { return unitsManager; }
    public MapManager getMapManager() { return mapManager; }

    // ---- Abstract ----

    /** Call the LLM with an OpenAI-format messages array. Subclasses implement provider logic. */
    public abstract JsonNode callLLM(JsonNode messages);

    // ========================================================================
    //  Multi-Round Execution (generic — delegates to AgentMode)
    // ========================================================================

    /**
     * Execute a full round: build context → loop { LLM → tools } → save.
     *
     * @param maxIterations max sub-rounds (default 5)
     * @return CommanderAction with all subRounds populated
     */
    public CommanderAction executeFullRound(String treeId, String nodeId, String guidance,
                                            int maxIterations) throws IOException {
        if (mode == null) throw new IllegalStateException("AgentMode not set");

        BranchTree tree = branchManager.getTree(treeId);
        if (tree == null) throw new IOException("Tree not found: " + treeId);
        BranchNode node = tree.findNode(nodeId);
        if (node == null) throw new IOException("Node not found: " + nodeId);

        CommanderAction action = new CommanderAction(config.getSide(), guidance);
        action.source = "llm";

        // Build conversation: system + past rounds + current context
        ArrayNode messages = buildFullConversation(tree, node, guidance);

        for (int iter = 0; iter < maxIterations; iter++) {
            LOG.info(mode.getName() + " sub-round " + (iter + 1) + "/" + maxIterations
                + " for " + config.getName());

            JsonNode response = callLLM(messages);
            CommanderAction.SubRound sr = new CommanderAction.SubRound();
            sr.iteration = iter + 1;
            sr.response = response;
            sr.timestamp = System.currentTimeMillis();

            JsonNode toolCalls = extractToolCalls(response);
            if (toolCalls == null || toolCalls.size() == 0) {
                // No tool_calls yet — either thinking (send continue) or done
                String finish = extractFinishReason(response);
                boolean hasReasoning = false;
                try {
                    JsonNode choices = response.get("choices");
                    if (choices != null && choices.isArray() && choices.size() > 0) {
                        JsonNode msg = choices.get(0).get("message");
                        if (msg != null && msg.has("reasoning_content")
                                && !msg.get("reasoning_content").isNull()
                                && msg.get("reasoning_content").asText().length() > 20)
                            hasReasoning = true;
                    }
                } catch (Exception ignored) {}
                // If model was reasoning (not explicitly stopped), prompt it to continue
                if (hasReasoning && !"stop".equals(finish) && iter < maxIterations - 1) {
                    messages.add(toAssistantMessage(response));
                    messages.add(msg("user", "请继续。调用工具执行侦察或移动命令。"));
                    action.subRounds.add(sr);
                    LOG.info("Reasoning detected, prompting to continue (iter " + (iter+1) + ")");
                    continue;
                }
                sr.results = MAPPER.createObjectNode().put("done", true);
                action.subRounds.add(sr);
                action.finalPlan = collectAllToolCalls(action.subRounds);
                action.rationale = extractRationale(response);
                action.guidanceAssessment = extractField(response, "guidanceAssessment");
                action.risks = extractField(response, "risks");
                action.recommendations = extractField(response, "recommendations");
                LOG.info(mode.getName() + " complete: " + config.getName()
                    + " after " + (iter + 1) + " sub-rounds");
                break;
            }

            JsonNode results = executeToolCalls(toolCalls);
            sr.results = results;
            action.subRounds.add(sr);

            messages.add(toAssistantMessage(response));
            for (int i = 0; i < results.size(); i++) {
                JsonNode r = results.get(i);
                ObjectNode toolMsg = MAPPER.createObjectNode();
                toolMsg.put("role", "tool");
                String tcId = (i < toolCalls.size() && toolCalls.get(i).has("id"))
                    ? toolCalls.get(i).get("id").asText() : ("call_" + i);
                toolMsg.put("tool_call_id", tcId);
                String tcContent = r.has("content")
                    ? (r.get("content").isTextual() ? r.get("content").asText() : r.get("content").toString())
                    : r.toString();
                toolMsg.put("content", tcContent);
                messages.add(toolMsg);
            }

            messages.add(msg("user", (iter < maxIterations - 1)
                ? "工具执行结果已返回。继续制定计划（如已完成请回复不含 tool_calls 的总结）。"
                : "这是最后一轮。请基于所有已获取的信息，生成最终结果。"));

            String finish = extractFinishReason(response);
            if ("stop".equals(finish)) {
                action.finalPlan = collectAllToolCalls(action.subRounds);
                action.rationale = extractRationale(response);
                LOG.info("LLM signaled completion for " + config.getName());
                break;
            }
        }

        mode.onRoundComplete(this, action);
        node.putCommanderAction(config.getSide(), action);
        branchManager.saveToDisk();
        return action;
    }

    // ========================================================================
    //  Conversation building (orchestrates system + history + mode context)
    // ========================================================================

    protected ArrayNode buildFullConversation(BranchTree tree, BranchNode target, String guidance) {
        ArrayNode messages = MAPPER.createArrayNode();

        // System prompt (mode provides default, config overrides)
        messages.add(mode.buildSystemMessage(this));

        List<BranchNode> chain = buildChain(tree, target);

        // Past rounds replay (AbstractAgent handles the generic pattern)
        for (int i = 0; i < chain.size() - 1; i++) {
            BranchNode pastNode = chain.get(i);
            ArrayNode pastMsgs = mode.buildPastRoundReplay(this, pastNode, chain, i);
            if (pastMsgs != null && pastMsgs.size() > 0)
                messages.addAll(pastMsgs);
        }

        // Current round context (mode-specific)
        BranchNode current = chain.get(chain.size() - 1);
        JsonNode ctx = mode.buildCurrentRoundContext(this, current, chain, guidance);
        if (ctx != null) {
            if (ctx.isArray()) messages.addAll((ArrayNode) ctx);
            else messages.add(ctx);
        }

        return messages;
    }

    // ========================================================================
    //  Past-round replay (generic — all modes share CommanderAction structure)
    // ========================================================================

    /**
     * Build past-round replay messages for a historical round node.
     * Public so AgentMode implementations can delegate back to this.
     */
    public ArrayNode replayPastRound(BranchNode pastNode, List<BranchNode> chain, int index) {
        ArrayNode msgs = MAPPER.createArrayNode();
        CommanderAction action = pastNode.getCommanderAction(config.getSide());

        if (action != null && action.subRounds != null && !action.subRounds.isEmpty()) {
            String sourceNote = "historical".equals(action.source)
                ? "\n\n*(以下为历史记录，非你生成的命令)*" : "";
            String roundHeader = "## Round " + pastNode.getRound() + " — " + pastNode.getName()
                + (action.guidance != null && !action.guidance.isEmpty()
                    ? "\n\n### 指令要点\n" + action.guidance : "")
                + sourceNote
                + "\n\n" + formatIntel(pastNode);

            msgs.add(msg("user", roundHeader));

            for (CommanderAction.SubRound sr : action.subRounds) {
                if (sr.response != null) msgs.add(toAssistantMessage(sr.response));
                if (sr.results != null && !sr.results.has("done") && sr.results.isArray()) {
                    for (JsonNode r : sr.results) {
                        ObjectNode toolMsg = MAPPER.createObjectNode();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", r.has("tool_call_id")
                            ? r.get("tool_call_id").asText() : "");
                        String sc = r.has("content")
                            ? (r.get("content").isTextual() ? r.get("content").asText() : r.get("content").toString())
                            : r.toString();
                        toolMsg.put("content", sc);
                        msgs.add(toolMsg);
                    }
                }
                if (sr.results != null && sr.results.has("done")) break;
            }
            if (action.feedback != null && !action.feedback.isEmpty()) {
                msgs.add(msg("user", "### 执行反馈\n" + action.feedback));
            }
        } else if (action != null) {
            // Legacy cache without subRounds
            String roundHeader = "## Round " + pastNode.getRound() + " — " + pastNode.getName();
            if (action.guidance != null && !action.guidance.isEmpty())
                roundHeader += "\n\n### 指令要点\n" + action.guidance;
            StringBuilder legacyContent = new StringBuilder(roundHeader);
            legacyContent.append("\n\n").append(formatIntel(pastNode));
            if (action.rationale != null && !action.rationale.isEmpty())
                legacyContent.append("\n\n### 指挥决策\n").append(action.rationale);
            if (action.deployment != null && !action.deployment.isEmpty())
                legacyContent.append("\n\n### 执行命令\n```json\n").append(action.deployment).append("\n```");
            if (action.feedback != null && !action.feedback.isEmpty())
                legacyContent.append("\n\n### 执行反馈\n").append(action.feedback);
            msgs.add(msg("user", legacyContent.toString()));
        } else {
            msgs.add(msg("user",
                "## Round " + pastNode.getRound() + " — " + pastNode.getName()
                + "\n\n" + formatIntel(pastNode)));
        }
        return msgs;
    }

    // ========================================================================
    //  Tool execution (delegates dispatch to AgentMode)
    // ========================================================================

    protected JsonNode executeToolCalls(JsonNode toolCalls) {
        ArrayNode results = MAPPER.createArrayNode();
        for (JsonNode tc : toolCalls) {
            String id = tc.has("id") ? tc.get("id").asText() : "call_unknown";
            JsonNode fn = tc.has("function") ? tc.get("function") : tc;
            String name = fn.has("name") ? fn.get("name").asText() : "unknown";
            JsonNode args = fn.has("arguments") ? fn.get("arguments") : MAPPER.createObjectNode();
            if (args.isTextual()) {
                try { args = MAPPER.readTree(args.asText()); } catch (Exception e) {}
            }

            ObjectNode result = MAPPER.createObjectNode();
            result.put("tool_call_id", id);
            result.put("name", name);

            try {
                String toolResult = mode.dispatchTool(this, name, args);
                try {
                    result.set("content", MAPPER.readTree(toolResult));
                } catch (Exception e) {
                    result.put("content", toolResult);
                }
            } catch (Exception e) {
                result.put("content", "Error: " + e.getMessage());
            }
            results.add(result);
        }
        return results;
    }

    // ========================================================================
    //  Intel / unit table formatting (used by multiple modes)
    // ========================================================================

    /** Format all live units as a markdown table for the LLM prompt. */
    public String formatFullUnitTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("### 全部单位清单 (含位置、地形、兵力、状态)\n\n");

        Map<String, List<Unit>> bySource = new LinkedHashMap<>();
        for (Unit u : unitsManager.listAll()) {
            bySource.computeIfAbsent(u.getSource(), k -> new ArrayList<>()).add(u);
        }

        for (var entry : bySource.entrySet()) {
            String src = entry.getKey();
            List<Unit> units = entry.getValue();
            boolean isFriendly = src.equals(config.getSide());
            sb.append("**").append(src).append("** (").append(units.size()).append(" units)");
            if (isFriendly) sb.append(" ← 我方");
            sb.append("\n\n");
            sb.append("| code | name | lat | lng | terrain | elev | men | type | status | description |\n");
            sb.append("|------|------|-----|-----|---------|------|-----|------|--------|-------------|\n");
            for (Unit u : units) {
                String terrain = "?"; int elev = 0;
                try {
                    String quick = mapManager.getTerrainQuick(u.getLat(), u.getLng());
                    String[] parts = quick.split("\\|");
                    terrain = parts[0]; elev = Integer.parseInt(parts[1]);
                } catch (Exception ignored) {}
                int men = u.getStrength();
                String desc = u.getDescription() != null && !u.getDescription().isEmpty()
                    ? u.getDescription().length() > 50 ? u.getDescription().substring(0, 47) + "..." : u.getDescription()
                    : "-";
                sb.append("| ").append(u.getCode())
                  .append(" | ").append(u.getName())
                  .append(" | ").append(String.format("%.2f", u.getLat()))
                  .append(" | ").append(String.format("%.2f", u.getLng()))
                  .append(" | ").append(terrain)
                  .append(" | ").append(elev).append("m")
                  .append(" | ").append(men)
                  .append(" | ").append(u.getType())
                  .append(" | ").append(u.getStatus())
                  .append(" | ").append(desc)
                  .append(" |\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Format intel: friendly + enemy unit lists from snapshots. */
    public String formatIntel(BranchNode node) {
        StringBuilder sb = new StringBuilder();
        List<UnitSnapshot> snaps = node.getUnitSnapshots();
        if (snaps.isEmpty()) snaps = branchManager.captureSnapshot();
        List<UnitSnapshot> friendly = new ArrayList<>(), enemy = new ArrayList<>();
        for (UnitSnapshot s : snaps) {
            if (config.getSide().equals(s.getSource())) friendly.add(s);
            else enemy.add(s);
        }
        sb.append("**我方 (").append(friendly.size()).append("):**\n");
        for (UnitSnapshot u : friendly)
            sb.append("- ").append(u.getCode()).append(" ").append(u.getName())
              .append(" (").append(String.format("%.2f",u.getLat())).append(",")
              .append(String.format("%.2f",u.getLng())).append(") ").append(u.getType()).append("\n");
        sb.append("\n**敌军 (").append(enemy.size()).append("):**\n");
        for (UnitSnapshot u : enemy)
            sb.append("- ").append(u.getCode()).append(" ").append(u.getName())
              .append(" (").append(String.format("%.2f",u.getLat())).append(",")
              .append(String.format("%.2f",u.getLng())).append(")\n");
        return sb.toString();
    }

    // ========================================================================
    //  Deployment / feedback / guidance (tree manipulation)
    // ========================================================================

    public JsonNode generateDeployment(String treeId, String nodeId, String guidance) {
        BranchTree tree = branchManager.getTree(treeId);
        if (tree == null) return MAPPER.createObjectNode().put("error", "Tree not found");
        BranchNode node = tree.findNode(nodeId);
        if (node == null) return MAPPER.createObjectNode().put("error", "Node not found");
        return callLLM(buildFullConversation(tree, node, guidance));
    }
    public JsonNode generateDeployment(String treeId, String nodeId) {
        return generateDeployment(treeId, nodeId, null);
    }

    public void submitDeployment(String treeId, String nodeId, JsonNode deploymentResult) throws IOException {
        BranchTree tree = branchManager.getTree(treeId);
        if (tree == null) throw new IOException("Tree not found");
        BranchNode node = tree.findNode(nodeId);
        if (node == null) throw new IOException("Node not found");
        CommanderAction action = new CommanderAction(config.getSide(), "");
        action.source = "llm";
        action.deployment = deploymentResult.has("plan") ? deploymentResult.get("plan").toString() : "[]";
        action.rationale = deploymentResult.has("rationale") ? deploymentResult.get("rationale").asText() : "";
        action.guidanceAssessment = extractField(deploymentResult, "guidanceAssessment");
        action.risks = extractField(deploymentResult, "risks");
        action.recommendations = extractField(deploymentResult, "recommendations");
        node.putCommanderAction(config.getSide(), action);
        branchManager.saveToDisk();
    }

    public void submitFeedback(String treeId, String nodeId, String feedback) throws IOException {
        BranchTree tree = branchManager.getTree(treeId);
        if (tree == null) throw new IOException("Tree not found");
        BranchNode node = tree.findNode(nodeId);
        if (node == null) throw new IOException("Node not found");
        CommanderAction action = node.getCommanderAction(config.getSide());
        if (action == null) {
            action = new CommanderAction(config.getSide(), "");
            node.putCommanderAction(config.getSide(), action);
        }
        action.feedback = feedback;
        branchManager.saveToDisk();
    }

    public void setGuidance(String treeId, String nodeId, String guidance) throws IOException {
        BranchTree tree = branchManager.getTree(treeId);
        if (tree == null) throw new IOException("Tree not found");
        BranchNode node = tree.findNode(nodeId);
        if (node == null) throw new IOException("Node not found");
        CommanderAction action = node.getCommanderAction(config.getSide());
        if (action == null) {
            action = new CommanderAction(config.getSide(), guidance);
            node.putCommanderAction(config.getSide(), action);
        } else {
            action.guidance = guidance;
        }
        branchManager.saveToDisk();
    }

    // ========================================================================
    //  Prompt debug helper
    // ========================================================================

    public String buildPrompt(String treeId, String nodeId, String guidance) {
        BranchTree tree = branchManager.getTree(treeId);
        if (tree == null) return "Tree not found";
        BranchNode node = tree.findNode(nodeId);
        if (node == null) return "Node not found";
        JsonNode conv = buildFullConversation(tree, node, guidance);
        StringBuilder sb = new StringBuilder();
        for (JsonNode msg : conv) {
            String role = msg.has("role") ? msg.get("role").asText() : "?";
            sb.append("[").append(role).append("] ");
            if (msg.has("content") && !msg.get("content").isNull()) {
                String c = msg.get("content").asText();
                sb.append(c.length() > 300 ? c.substring(0, 300) + "..." : c);
            }
            if (msg.has("tool_calls")) sb.append(" (tc:").append(msg.get("tool_calls").size()).append(")");
            sb.append("\n\n");
        }
        return sb.toString();
    }
    public String buildPrompt(String treeId, String nodeId) { return buildPrompt(treeId, nodeId, null); }

    // ========================================================================
    //  Response parsing helpers
    // ========================================================================

    protected JsonNode extractToolCalls(JsonNode response) {
        JsonNode choices = response.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode msg = choices.get(0).get("message");
            if (msg != null && msg.has("tool_calls")) return msg.get("tool_calls");
        }
        if (response.has("content")) {
            JsonNode content = response.get("content");
            ArrayNode tc = MAPPER.createArrayNode();
            for (JsonNode block : content) {
                if ("tool_use".equals(block.has("type") ? block.get("type").asText() : "")) {
                    ObjectNode entry = tc.addObject();
                    entry.put("id", block.get("id").asText());
                    entry.putObject("function").put("name", block.get("name").asText())
                        .set("arguments", block.get("input"));
                }
            }
            if (tc.size() > 0) return tc;
        }
        return null;
    }

    protected String extractRationale(JsonNode response) {
        JsonNode choices = response.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode msg = choices.get(0).get("message");
            if (msg != null) {
                if (msg.has("reasoning_content") && !msg.get("reasoning_content").isNull())
                    return msg.get("reasoning_content").asText();
                if (msg.has("content") && !msg.get("content").isNull())
                    return msg.get("content").asText();
            }
        }
        return "";
    }

    protected String extractField(JsonNode response, String field) {
        if (response.has(field)) return response.get(field).asText();
        JsonNode choices = response.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode msg = choices.get(0).get("message");
            if (msg != null && msg.has("content") && !msg.get("content").isNull()) {
                String content = msg.get("content").asText();
                int start = content.indexOf('{');
                int end = content.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    try {
                        JsonNode parsed = MAPPER.readTree(content.substring(start, end + 1));
                        if (parsed.has(field)) return parsed.get(field).asText();
                    } catch (Exception ignored) {}
                }
            }
        }
        return "";
    }

    protected String extractFinishReason(JsonNode response) {
        JsonNode choices = response.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0)
            return choices.get(0).has("finish_reason")
                ? choices.get(0).get("finish_reason").asText() : "";
        return "";
    }

    protected JsonNode collectAllToolCalls(List<CommanderAction.SubRound> subRounds) {
        ArrayNode all = MAPPER.createArrayNode();
        for (CommanderAction.SubRound sr : subRounds) {
            if (sr.response == null) continue;
            JsonNode tc = extractToolCalls(sr.response);
            if (tc != null && tc.isArray()) all.addAll((ArrayNode) tc);
        }
        return all;
    }

    // ========================================================================
    //  Message builders
    // ========================================================================

    public ObjectNode msg(String role, String content) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("role", role);
        m.put("content", content != null ? content : "");
        return m;
    }

    protected ObjectNode toAssistantMessage(JsonNode response) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "assistant");
        String rationale = extractRationale(response);
        JsonNode tc = extractToolCalls(response);
        boolean hasTc = tc != null && tc.isArray() && tc.size() > 0;
        if (!rationale.isEmpty()) msg.put("content", rationale);
        else if (!hasTc) msg.put("content", " "); // OpenAI requires content or tool_calls
        else msg.putNull("content");
        if (hasTc) msg.set("tool_calls", tc);
        return msg;
    }

    protected ObjectNode toAssistantText(String rationale, String deploymentJson) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "assistant");
        msg.put("content", rationale != null ? rationale : "");
        if (deploymentJson != null && !deploymentJson.isEmpty() && !deploymentJson.equals("[]")) {
            try {
                JsonNode plan = MAPPER.readTree(deploymentJson);
                if (plan.isArray() && plan.size() > 0) {
                    ArrayNode tc = msg.putArray("tool_calls");
                    for (int j = 0; j < plan.size(); j++) {
                        JsonNode entry = plan.get(j);
                        ObjectNode tce = tc.addObject();
                        tce.put("id", "legacy_" + j);
                        tce.put("type", "function");
                        ObjectNode fn = tce.putObject("function");
                        fn.put("name", entry.has("tool") ? entry.get("tool").asText() : "unknown");
                        fn.set("arguments", entry.has("args") ? entry.get("args") : MAPPER.createObjectNode());
                    }
                }
            } catch (Exception ignored) {}
        }
        return msg;
    }

    // ========================================================================
    //  Tree helpers
    // ========================================================================

    protected List<BranchNode> buildChain(BranchTree tree, BranchNode target) {
        List<BranchNode> chain = new ArrayList<>();
        BranchNode cur = target;
        while (cur != null) {
            chain.add(0, cur);
            String pid = cur.getParentId();
            cur = pid != null ? tree.findNode(pid) : null;
        }
        return chain;
    }

    // ========================================================================
    //  Public accessors (for AgentMode implementations)
    // ========================================================================

    public double getArgD(JsonNode args, String key) {
        return args.has(key) ? args.get(key).asDouble() : 0;
    }
    public double getArgD(JsonNode args, String key, double def) {
        return args.has(key) ? args.get(key).asDouble() : def;
    }
    public String getArgS(JsonNode args, String key) {
        return args.has(key) ? args.get(key).asText() : "";
    }
    public String getArgS(JsonNode args, String key, String def) {
        return args.has(key) ? args.get(key).asText() : def;
    }

    /** Expose toAssistantMessage for mode context building. */
    public ObjectNode toAssistantMessageInternal(JsonNode response) {
        return toAssistantMessage(response);
    }

    // ========================================================================
    //  Utilities
    // ========================================================================

    protected static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.toString();
    }

    protected double getArg(JsonNode args, String key) {
        return args.has(key) ? args.get(key).asDouble() : 0;
    }
    protected double getArg(JsonNode args, String key, double def) {
        return args.has(key) ? args.get(key).asDouble() : def;
    }
}
