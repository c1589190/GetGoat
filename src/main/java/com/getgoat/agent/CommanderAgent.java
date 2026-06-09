package com.getgoat.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.getgoat.map.branch.*;
import com.getgoat.map.manager.UnitsManager;
import com.getgoat.map.manager.MapManager;
import com.getgoat.map.model.Unit;
import com.getgoat.map.geometry.SphericalEngine;
import com.getgoat.map.model.TerrainCell;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Abstract commander agent with multi-round execution.
 *
 * Each branch round can have multiple LLM sub-rounds:
 *   sub-1: recon (query_radius, get_distance)
 *   sub-2: move orders (move_unit, create_unit)
 *   sub-3: confirm / more recon
 *
 * All sub-rounds are stored in CommanderAction.subRounds.
 */
public abstract class CommanderAgent {

    protected static final Logger LOG = Logger.getLogger(CommanderAgent.class.getName());
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SUBROUNDS = 5;

    protected CommanderConfig config;
    protected BranchManager branchManager;
    protected UnitsManager unitsManager;
    protected MapManager mapManager;
    protected Path workspaceDir;

    public void initialize(CommanderConfig cfg, BranchManager bm, UnitsManager um,
                           MapManager mm, Path workspace) {
        this.config = cfg;
        this.branchManager = bm;
        this.unitsManager = um;
        this.mapManager = mm;
        this.workspaceDir = workspace;
        LOG.info("Initialized commander: " + cfg.getName() + " (" + cfg.getSide() + ")");
    }

    // ---- Abstract ----

    public abstract JsonNode callLLM(JsonNode messages);

    // ---- Multi-Round Execution ----

    /**
     * Execute a full commander round: recon → plan → confirm.
     * Loops until LLM returns no more tool_calls or max iterations reached.
     *
     * @param maxIterations  max sub-rounds (default 5)
     * @return CommanderAction with all subRounds populated
     */
    public CommanderAction executeFullRound(String treeId, String nodeId, String guidance,
                                            int maxIterations) throws IOException {
        BranchTree tree = branchManager.getTree(treeId);
        if (tree == null) throw new IOException("Tree not found: " + treeId);
        BranchNode node = tree.findNode(nodeId);
        if (node == null) throw new IOException("Node not found: " + nodeId);

        CommanderAction action = new CommanderAction(config.getSide(), guidance);
        action.source = "llm";

        // Build initial conversation
        ArrayNode messages = (ArrayNode) buildConversation(treeId, nodeId, guidance);

        for (int iter = 0; iter < maxIterations; iter++) {
            LOG.info("Sub-round " + (iter + 1) + "/" + maxIterations + " for " + config.getName());

            // Call LLM
            JsonNode response = callLLM(messages);
            CommanderAction.SubRound sr = new CommanderAction.SubRound();
            sr.iteration = iter + 1;
            sr.response = response;
            sr.timestamp = System.currentTimeMillis();

            // Parse tool_calls from response
            JsonNode toolCalls = extractToolCalls(response);
            if (toolCalls == null || toolCalls.size() == 0) {
                // No more tool calls — deployment complete
                sr.results = MAPPER.createObjectNode().put("done", true);
                action.subRounds.add(sr);
                action.finalPlan = collectAllToolCalls(action.subRounds);
                action.rationale = extractRationale(response);
                action.guidanceAssessment = extractField(response, "guidanceAssessment");
                action.risks = extractField(response, "risks");
                action.recommendations = extractField(response, "recommendations");
                LOG.info("Deployment complete: " + config.getName() + " after " + (iter + 1) + " sub-rounds");
                break;
            }

            // Execute tool calls locally
            JsonNode results = executeToolCalls(toolCalls);
            sr.results = results;
            action.subRounds.add(sr);

            // Add assistant response
            messages.add(toAssistantMessage(response));
            // Add one tool message per tool call (OpenAI 1:1 requirement)
            for (int i = 0; i < results.size(); i++) {
                JsonNode r = results.get(i);
                ObjectNode toolMsg = MAPPER.createObjectNode();
                toolMsg.put("role", "tool");
                String tcId = (i < toolCalls.size() && toolCalls.get(i).has("id"))
                    ? toolCalls.get(i).get("id").asText() : ("call_" + i);
                toolMsg.put("tool_call_id", tcId);
                // OpenAI requires tool content to be a STRING
                String tcContent = r.has("content")
                    ? (r.get("content").isTextual() ? r.get("content").asText() : r.get("content").toString())
                    : r.toString();
                toolMsg.put("content", tcContent);
                messages.add(toolMsg);
            }

            // Prompt to continue
            messages.add(msg("user", (iter < maxIterations - 1)
                ? "工具执行结果已返回。继续制定计划（如已完成请回复不含 tool_calls 的总结）。"
                : "这是最后一轮。请基于所有已获取的信息，生成最终作战部署。"));

            // Check if LLM indicated completion
            String finish = extractFinishReason(response);
            if ("stop".equals(finish)) {
                action.finalPlan = collectAllToolCalls(action.subRounds);
                action.rationale = extractRationale(response);
                LOG.info("LLM signaled completion for " + config.getName());
                break;
            }
        }

        // Save to branch node
        node.putCommanderAction(config.getSide(), action);
        branchManager.saveToDisk();
        return action;
    }

    // ---- Tool Execution ----

    /**
     * Execute MCP tool calls locally.
     */
    @SuppressWarnings("unchecked")
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
                String toolResult = dispatchTool(name, args);
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

    /** Route a tool call to the right handler. */
    private String dispatchTool(String name, JsonNode args) {
        double lat, lng, r, lat1, lng1, lat2, lng2;
        switch (name) {
            case "get_distance":
                lat1 = getArg(args, "lat1"); lng1 = getArg(args, "lng1");
                lat2 = getArg(args, "lat2"); lng2 = getArg(args, "lng2");
                double dist = SphericalEngine.haversineDistance(lat1, lng1, lat2, lng2);
                double bearing = SphericalEngine.bearing(lat1, lng1, lat2, lng2);
                return String.format("{\"distance_km\":%.1f,\"bearing_deg\":%.1f}", dist, bearing);

            case "query_terrain":
                lat = getArg(args, "lat"); lng = getArg(args, "lng");
                TerrainCell cell = mapManager.getTerrainAt(lat, lng);
                if (cell == null) return "{\"error\":\"no data\"}";
                return String.format("{\"terrain\":\"%s\",\"elevation\":%.0f,\"color\":\"%s\"}",
                    cell.getTerrain().getDisplayName(), cell.getElevationMeters(), cell.getColorHex());

            case "query_radius":
                lat = getArg(args, "lat"); lng = getArg(args, "lng");
                r = getArg(args, "r", 50);
                var qr = mapManager.queryRadiusEnhanced(lat, lng, r);
                StringBuilder sb = new StringBuilder("{");
                if (qr.centerCell() != null) {
                    sb.append("\"terrain\":\"").append(qr.centerCell().getTerrain().getDisplayName()).append("\",");
                    sb.append("\"elevation\":").append((int)qr.centerCell().getElevationMeters()).append(",");
                }
                sb.append("\"cities\":[");
                boolean first = true;
                for (var c : qr.cities()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"name\":\"").append(esc(c.getName())).append("\",\"lat\":")
                      .append(c.getCenter().getLatitude()).append(",\"lng\":")
                      .append(c.getCenter().getLongitude()).append("}");
                }
                sb.append("],\"roadNodes\":").append(qr.roadNodes().size());
                sb.append(",\"roadSegments\":").append(qr.roadSegments().size());
                if (qr.terrainProfile() != null) {
                    sb.append(",\"terrainProfile\":{");
                    boolean ft = true;
                    for (var e : qr.terrainProfile().entrySet()) {
                        if (!ft) sb.append(",");
                        ft = false;
                        sb.append("\"").append(e.getKey().getDisplayName()).append("\":").append(e.getValue());
                    }
                    sb.append("}");
                }
                sb.append("}");
                return sb.toString();

            case "move_unit":
                String code = getArgS(args, "code");
                lat = getArg(args, "lat"); lng = getArg(args, "lng");
                unitsManager.move(code, lat, lng);
                return "{\"moved\":\"" + esc(code) + "\",\"lat\":" + lat + ",\"lng\":" + lng + "}";

            case "create_unit":
                code = getArgS(args, "code");
                String uname = getArgS(args, "name", code);
                String src = getArgS(args, "source", config.getSide());
                String type = getArgS(args, "type", "infantry");
                lat = getArg(args, "lat"); lng = getArg(args, "lng");
                try {
                    unitsManager.create(code, uname, src, type, lat, lng);
                    return "{\"created\":\"" + esc(code) + "\",\"lat\":" + lat + ",\"lng\":" + lng + "}";
                } catch (IllegalArgumentException e) {
                    return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
                }

            case "delete_unit":
                code = getArgS(args, "code");
                boolean del = unitsManager.delete(code);
                return "{\"deleted\":" + del + ",\"code\":\"" + esc(code) + "\"}";

            default:
                return "{\"error\":\"unknown tool: " + esc(name) + "\"}";
        }
    }

    // ---- Unit Table Formatting ----

    /**
     * Format ALL units as a markdown table for the prompt.
     * This prevents the LLM from querying unit positions via tools.
     */
    protected String formatFullUnitTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("### 全部单位清单 (含位置、类型、状态)\n\n");

        // Group by source
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
            sb.append("| code | name | lat | lng | type | status |\n");
            sb.append("|------|------|-----|-----|------|--------|\n");
            for (Unit u : units) {
                sb.append("| ").append(u.getCode())
                  .append(" | ").append(u.getName())
                  .append(" | ").append(String.format("%.2f", u.getLat()))
                  .append(" | ").append(String.format("%.2f", u.getLng()))
                  .append(" | ").append(u.getType())
                  .append(" | ").append(u.getStatus())
                  .append(" |\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ---- Conversation Builder (updated) ----

    public JsonNode buildConversation(String treeId, String nodeId, String guidance) {
        ArrayNode messages = MAPPER.createArrayNode();
        messages.add(msg("system", config.getSystemPrompt()));

        BranchTree tree = branchManager.getTree(treeId);
        if (tree == null) { messages.add(msg("user", "Tree not found")); return messages; }
        BranchNode target = tree.findNode(nodeId);
        if (target == null) { messages.add(msg("user", "Node not found")); return messages; }

        List<BranchNode> chain = buildChain(tree, target);

        // Past rounds
        for (int i = 0; i < chain.size() - 1; i++) {
            BranchNode curNode = chain.get(i);
            CommanderAction action = curNode.getCommanderAction(config.getSide());

            if (action != null && action.subRounds != null && !action.subRounds.isEmpty()) {
                // Replay sub-rounds from cache
                String sourceNote = "historical".equals(action.source)
                    ? "\n\n*(以下为历史记录，非你生成的命令)*" : "";
                String roundHeader = "## Round " + curNode.getRound() + " — " + curNode.getName()
                    + (action.guidance != null && !action.guidance.isEmpty()
                        ? "\n\n### 指令要点\n" + action.guidance : "")
                    + sourceNote
                    + "\n\n" + formatIntel(curNode);

                messages.add(msg("user", roundHeader));

                for (CommanderAction.SubRound sr : action.subRounds) {
                    if (sr.response != null) {
                        messages.add(toAssistantMessage(sr.response));
                    }
                    if (sr.results != null && !sr.results.has("done") && sr.results.isArray()) {
                        // One tool message per result entry (OpenAI requires 1:1 pairing)
                        for (JsonNode r : sr.results) {
                            ObjectNode toolMsg = MAPPER.createObjectNode();
                            toolMsg.put("role", "tool");
                            toolMsg.put("tool_call_id", r.has("tool_call_id") ? r.get("tool_call_id").asText() : "");
                            String sc = r.has("content")
                                ? (r.get("content").isTextual() ? r.get("content").asText() : r.get("content").toString())
                                : r.toString();
                            toolMsg.put("content", sc);
                            messages.add(toolMsg);
                        }
                    }
                    if (sr.results != null && sr.results.has("done")) {
                        break; // end of sub-rounds for this round
                    }
                }
                // Feedback
                if (action.feedback != null && !action.feedback.isEmpty()) {
                    messages.add(msg("user", "### 执行反馈\n" + action.feedback));
                }
            } else if (action != null) {
                // Legacy cache without subRounds — include as plain text (no unmatching tool_calls)
                String roundHeader = "## Round " + curNode.getRound() + " — " + curNode.getName();
                if (action.guidance != null && !action.guidance.isEmpty())
                    roundHeader += "\n\n### 指令要点\n" + action.guidance;
                StringBuilder legacyContent = new StringBuilder(roundHeader);
                legacyContent.append("\n\n").append(formatIntel(curNode));
                if (action.rationale != null && !action.rationale.isEmpty())
                    legacyContent.append("\n\n### 指挥决策\n").append(action.rationale);
                if (action.deployment != null && !action.deployment.isEmpty())
                    legacyContent.append("\n\n### 执行命令\n```json\n").append(action.deployment).append("\n```");
                if (action.feedback != null && !action.feedback.isEmpty())
                    legacyContent.append("\n\n### 执行反馈\n").append(action.feedback);
                messages.add(msg("user", legacyContent.toString()));
            } else {
                messages.add(msg("user",
                    "## Round " + curNode.getRound() + " — " + curNode.getName()
                    + "\n\n" + formatIntel(curNode)));
            }
        }

        // Current round
        BranchNode current = chain.get(chain.size() - 1);
        CommanderAction curAction = current.getCommanderAction(config.getSide());

        if (curAction != null && curAction.subRounds != null && !curAction.subRounds.isEmpty()) {
            // Resuming: replay current round's sub-rounds + prompt to continue
            String header = "## Round " + current.getRound() + " — " + current.getName() + " (当前)\n\n"
                + formatFullUnitTable();
            if (curAction.guidance != null && !curAction.guidance.isEmpty())
                header += "\n### 指令要点\n" + curAction.guidance;
            messages.add(msg("user", header));

            for (CommanderAction.SubRound sr : curAction.subRounds) {
                if (sr.response != null) messages.add(toAssistantMessage(sr.response));
                if (sr.results != null && !sr.results.has("done"))
                    messages.add(toToolResultsRaw(sr.results));
            }
            messages.add(msg("user",
                "工具结果已返回。请继续制定计划，如需移动部队请使用 move_unit/create_unit/delete_unit。如计划完成请回复不含 tool_calls 的总结。"));
        } else {
            // Fresh round
            StringBuilder intel = new StringBuilder();
            intel.append("## Round ").append(current.getRound()).append(" — ")
                .append(current.getName()).append(" (当前)\n\n");
            intel.append(formatFullUnitTable()).append("\n");
            if (guidance != null && !guidance.isEmpty())
                intel.append("### 指令要点 (必须遵守)\n").append(guidance).append("\n\n");
            intel.append("### 态势\n").append(formatIntel(current)).append("\n\n");
            intel.append("### 可用工具\n");
            intel.append("- get_distance(lat1,lng1,lat2,lng2) — 计算距离\n");
            intel.append("- query_terrain(lat,lng) — 查询单点地形\n");
            intel.append("- query_radius(lat,lng,r) — 查询半径内地形/城市\n");
            intel.append("- move_unit(code,lat,lng) — 移动部队\n");
            intel.append("- create_unit(code,name,source,type,lat,lng) — 新增部队\n");
            intel.append("- delete_unit(code) — 消灭/撤退部队\n\n");
            intel.append("单位位置已在上方表格中完整列出，无需重复查询。请优先使用侦察工具了解地形，再下达移动命令。");

            messages.add(msg("user", intel.toString()));
        }
        return messages;
    }

    public JsonNode buildConversation(String treeId, String nodeId) {
        return buildConversation(treeId, nodeId, null);
    }

    // ---- Deployment generation (single-shot, legacy) ----

    public JsonNode generateDeployment(String treeId, String nodeId, String guidance) {
        return callLLM(buildConversation(treeId, nodeId, guidance));
    }
    public JsonNode generateDeployment(String treeId, String nodeId) {
        return generateDeployment(treeId, nodeId, null);
    }

    // ---- Save deployment / feedback ----

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

    // ---- Intel ----

    protected String formatIntel(BranchNode node) {
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

    // ---- Prompt (debug) ----

    public String buildPrompt(String treeId, String nodeId, String guidance) {
        JsonNode conv = buildConversation(treeId, nodeId, guidance);
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

    // ---- Helpers ----

    private double getArg(JsonNode args, String key) {
        return args.has(key) ? args.get(key).asDouble() : 0;
    }
    private double getArg(JsonNode args, String key, double def) {
        return args.has(key) ? args.get(key).asDouble() : def;
    }
    private String getArgS(JsonNode args, String key) {
        return args.has(key) ? args.get(key).asText() : "";
    }
    private String getArgS(JsonNode args, String key, String def) {
        return args.has(key) ? args.get(key).asText() : def;
    }

    private JsonNode extractToolCalls(JsonNode response) {
        JsonNode choices = response.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode msg = choices.get(0).get("message");
            if (msg != null && msg.has("tool_calls")) return msg.get("tool_calls");
        }
        // Also check for Anthropic format
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

    private String extractRationale(JsonNode response) {
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

    private String extractField(JsonNode response, String field) {
        if (response.has(field)) return response.get(field).asText();
        JsonNode choices = response.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode msg = choices.get(0).get("message");
            if (msg != null && msg.has("content") && !msg.get("content").isNull()) {
                String content = msg.get("content").asText();
                // Try to parse as JSON containing the field
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

    private String extractFinishReason(JsonNode response) {
        JsonNode choices = response.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0)
            return choices.get(0).has("finish_reason")
                ? choices.get(0).get("finish_reason").asText() : "";
        return "";
    }

    private JsonNode collectAllToolCalls(List<CommanderAction.SubRound> subRounds) {
        ArrayNode all = MAPPER.createArrayNode();
        for (CommanderAction.SubRound sr : subRounds) {
            if (sr.response == null) continue;
            JsonNode tc = extractToolCalls(sr.response);
            if (tc != null && tc.isArray()) all.addAll((ArrayNode) tc);
        }
        return all;
    }

    // ---- Message builders ----

    private ObjectNode msg(String role, String content) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("role", role);
        m.put("content", content != null ? content : "");
        return m;
    }

    private ObjectNode toAssistantMessage(JsonNode response) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "assistant");

        String rationale = extractRationale(response);
        JsonNode tc = extractToolCalls(response);

        if (!rationale.isEmpty()) msg.put("content", rationale);
        else msg.putNull("content");

        if (tc != null && tc.isArray() && tc.size() > 0) {
            msg.set("tool_calls", tc);
        }
        return msg;
    }

    private ObjectNode toAssistantText(String rationale, String deploymentJson) {
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

    private ObjectNode toToolResultsMessage(JsonNode toolCalls, JsonNode results) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "user");
        StringBuilder sb = new StringBuilder("工具执行结果:\n");
        for (int i = 0; i < results.size(); i++) {
            JsonNode r = results.get(i);
            sb.append("- ").append(r.has("name") ? r.get("name").asText() : "?")
              .append(": ").append(r.get("content").toString()).append("\n");
        }
        msg.put("content", sb.toString());
        return msg;
    }

    private ObjectNode toToolResultsRaw(JsonNode results) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "tool");
        if (results.isArray()) {
            for (JsonNode r : results) {
                if (r.has("tool_call_id")) msg.put("tool_call_id", r.get("tool_call_id").asText());
                if (r.has("content")) msg.set("content", r.get("content"));
            }
        }
        return msg;
    }

    // ---- Tree helpers ----

    private List<BranchNode> buildChain(BranchTree tree, BranchNode target) {
        List<BranchNode> chain = new ArrayList<>();
        BranchNode cur = target;
        while (cur != null) {
            chain.add(0, cur);
            String pid = cur.getParentId();
            cur = pid != null ? tree.findNode(pid) : null;
        }
        return chain;
    }

    private static String esc(String s) {
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

    // ---- Deployment / deployment fields (legacy compat) ----
    // "deployment" is stored in CommanderAction as a string for backward compat
    // SubRound stores the structured version

    public CommanderConfig getConfig() { return config; }
    public Path getWorkspaceDir() { return workspaceDir; }
}
