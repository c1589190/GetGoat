package com.getgoat.map.commander;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getgoat.map.branch.*;
import com.getgoat.map.manager.UnitsManager;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages commander system prompts and cached operational plans per side.
 * Each campaign workspace has commanders/{nationalist,japanese}/ with:
 *   system.md        — the commander's doctrine / tactical preferences
 *   cache/round-N.json — generated LLM tool-call transcripts per round
 */
public class CommanderManager {

    private static final Logger LOG = Logger.getLogger(CommanderManager.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UnitsManager unitsManager;
    private final BranchManager branchManager;
    private Path workspaceDir;

    public CommanderManager(UnitsManager um, BranchManager bm) {
        this.unitsManager = um;
        this.branchManager = bm;
    }

    public void setWorkspace(Path dir) { this.workspaceDir = dir; }

    // ---- System prompt ----

    public String getSystemPrompt(String side) {
        Path f = commanderPath(side).resolve("system.md");
        try { return Files.exists(f) ? Files.readString(f) : "# " + side + " commander prompt (not configured)"; }
        catch (IOException e) { return "Error: " + e.getMessage(); }
    }

    public void setSystemPrompt(String side, String content) throws IOException {
        Path dir = commanderPath(side);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("system.md"), content);
        LOG.info("Updated system prompt for " + side);
    }

    // ---- Context builder ----

    /**
     * Build full context for LLM to generate operational plan for a specific round.
     * Walks the branch tree from root→target node, assembling:
     *   [SystemPrompt] + [R1-intel+plan] + [R2-intel+plan] + ... + [RN-current-intel]
     */
    public String buildContext(String side, String treeId, String nodeId) {
        StringBuilder sb = new StringBuilder();

        // 1. System prompt
        sb.append(getSystemPrompt(side)).append("\n\n---\n\n");

        BranchTree tree = branchManager.getTree(treeId);
        if (tree == null) return sb.append("Tree not found").toString();

        BranchNode target = tree.findNode(nodeId);
        if (target == null) return sb.append("Node not found").toString();

        // 2. Walk ancestors root→target
        List<BranchNode> chain = buildChain(tree, target);
        BranchNode parent = null;

        for (int i = 0; i < chain.size() - 1; i++) {
            BranchNode curNode = chain.get(i);
            boolean isLast = (i == chain.size() - 2); // the node BEFORE the target

            sb.append("## Round ").append(curNode.getRound()).append(" — ").append(curNode.getName()).append("\n\n");

            // Intel: what the commander would know
            sb.append("### 已知情报\n\n");
            sb.append(formatIntel(side, curNode)).append("\n");

            // If there is a cached plan, include it
            String cached = loadCache(side, curNode.getRound());
            if (cached != null && !cached.isEmpty()) {
                sb.append("### 作战部署\n\n");
                sb.append(formatCachedTranscript(cached)).append("\n\n");
            } else {
                // Generate virtual tool calls from unitChanges
                sb.append("### 作战部署（根据兵力变动反推）\n\n");
                sb.append(formatVirtualToolCalls(side, curNode)).append("\n\n");
            }

            if (isLast) break;
        }

        // 3. Current situation (target node)
        BranchNode current = chain.get(chain.size() - 1);
        sb.append("## Round ").append(current.getRound()).append(" — ").append(current.getName()).append(" (当前)\n\n");
        sb.append("### 当前态势\n\n");
        sb.append(formatIntel(side, current)).append("\n");
        if (current.getOutcome() != null && !current.getOutcome().isEmpty()) {
            sb.append("### 战役结果\n\n").append(current.getOutcome()).append("\n\n");
        }

        // 4. Available tools
        sb.append("---\n\n");
        sb.append("你可以使用以下 MCP 工具执行作战部署:\n\n");
        sb.append("- `move_unit(code, lat, lng)` — 移动部队到新位置\n");
        sb.append("- `create_unit(code, name, source, type, lat, lng, color)` — 新增/增援部队\n");
        sb.append("- `delete_unit(code)` — 部队被消灭/撤退\n");
        sb.append("- `query_terrain(lat, lng)` — 查询地形类型和海拔\n");
        sb.append("- `query_radius(lat, lng, r)` — 查询半径内地形和城市\n");
        sb.append("- `get_distance(lat1, lng1, lat2, lng2)` — 计算两地距离\n\n");
        sb.append("请生成第 ").append(current.getRound()).append(" 轮的作战部署。先分析态势，然后逐个调用工具执行兵力调动。");

        return sb.toString();
    }

    /** Walk from target up to root, return path root→...→target */
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

    /** Format intel: show friendly and enemy unit positions at this node. */
    private String formatIntel(String side, BranchNode node) {
        StringBuilder sb = new StringBuilder();
        List<UnitSnapshot> snaps = node.getUnitSnapshots();
        if (snaps.isEmpty()) snaps = branchManager.captureSnapshot();

        List<UnitSnapshot> friendly = new ArrayList<>();
        List<UnitSnapshot> enemy = new ArrayList<>();

        boolean isJp = "japanese".equals(side);
        for (UnitSnapshot s : snaps) {
            boolean isEnemy = isJp ? !"japanese".equals(s.getSource()) : "japanese".equals(s.getSource());
            if (isEnemy) enemy.add(s); else friendly.add(s);
        }

        sb.append("**我方兵力 (").append(friendly.size()).append("):**\n");
        for (UnitSnapshot u : friendly) {
            sb.append("- ").append(u.getCode()).append(" ").append(u.getName())
              .append(" — (").append(String.format("%.2f", u.getLat())).append(", ")
              .append(String.format("%.2f", u.getLng())).append(") ").append(u.getType());
            if (u.getDescription() != null && !u.getDescription().isEmpty())
                sb.append(" — ").append(u.getDescription());
            sb.append("\n");
        }

        sb.append("\n**敌军兵力 (").append(enemy.size()).append("):**\n");
        for (UnitSnapshot u : enemy) {
            sb.append("- ").append(u.getCode()).append(" ").append(u.getName())
              .append(" — (").append(String.format("%.2f", u.getLat())).append(", ")
              .append(String.format("%.2f", u.getLng())).append(")");
            sb.append("\n");
        }

        return sb.toString();
    }

    /** Format a cached transcript as readable text. */
    private String formatCachedTranscript(String cacheJson) {
        try {
            JsonNode root = MAPPER.readTree(cacheJson);
            JsonNode transcript = root.get("transcript");
            if (transcript == null || !transcript.isArray()) return cacheJson;
            StringBuilder sb = new StringBuilder();
            for (JsonNode msg : transcript) {
                String role = msg.has("role") ? msg.get("role").asText() : "?";
                if ("system".equals(role)) continue;
                if ("assistant".equals(role)) {
                    if (msg.has("content") && !msg.get("content").isNull())
                        sb.append(msg.get("content").asText()).append("\n");
                    if (msg.has("tool_calls")) {
                        for (JsonNode tc : msg.get("tool_calls")) {
                            sb.append("  → ").append(tc.get("name").asText()).append("(");
                            JsonNode args = tc.get("arguments");
                            if (args != null) sb.append(args.toString());
                            sb.append(")\n");
                        }
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) { return cacheJson; }
    }

    /**
     * Generate virtual tool calls from unitChanges.
     * This simulates what tool calls an LLM WOULD have made to achieve the historical movements.
     */
    private String formatVirtualToolCalls(String side, BranchNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("**作战行动:**\n\n");

        // If we have parent, compute changes
        List<UnitChange> changes = node.getUnitChanges();
        if (changes.isEmpty() && node.getParentId() != null) {
            BranchTree tree = findTreeForNode(node.getId());
            if (tree != null) {
                BranchNode parent = tree.findNode(node.getParentId());
                if (parent != null) {
                    changes = computeChanges(parent.getUnitSnapshots(), node.getUnitSnapshots());
                }
            }
        }

        if (changes.isEmpty()) {
            sb.append("(初始部署)\n");
            return sb.toString();
        }

        for (UnitChange c : changes) {
            sb.append("- `");
            switch (c.getChangeType()) {
                case "move":
                    sb.append("move_unit(\"").append(c.getCode()).append("\", ")
                      .append(String.format("%.2f", c.getToLat())).append(", ")
                      .append(String.format("%.2f", c.getToLng())).append(")");
                    break;
                case "create":
                    sb.append("create_unit(\"").append(c.getCode()).append("\", ..., ")
                      .append(String.format("%.2f", c.getToLat())).append(", ")
                      .append(String.format("%.2f", c.getToLng())).append(")");
                    break;
                case "delete":
                    sb.append("delete_unit(\"").append(c.getCode()).append("\")");
                    break;
                case "hold":
                    sb.append("// ").append(c.getCode()).append(" held position");
                    break;
                case "status_change":
                    sb.append("// ").append(c.getCode()).append(" status: ").append(c.getOldStatus()).append("→").append(c.getNewStatus());
                    break;
            }
            sb.append("`\n");
        }
        return sb.toString();
    }

    /** Compute changes by comparing parent → child snapshots. */
    private List<UnitChange> computeChanges(List<UnitSnapshot> parent, List<UnitSnapshot> child) {
        List<UnitChange> changes = new ArrayList<>();
        Map<String, UnitSnapshot> pm = new LinkedHashMap<>();
        Map<String, UnitSnapshot> cm = new LinkedHashMap<>();
        for (UnitSnapshot s : parent) pm.put(s.getCode(), s);
        for (UnitSnapshot s : child) cm.put(s.getCode(), s);

        for (UnitSnapshot c : child) {
            UnitSnapshot p = pm.get(c.getCode());
            if (p == null) {
                changes.add(UnitChange.created(c.getCode(), c.getLat(), c.getLng(), "deploy"));
            } else {
                double d = Math.abs(c.getLat() - p.getLat()) + Math.abs(c.getLng() - p.getLng());
                if (d > 0.0001) changes.add(UnitChange.moved(c.getCode(),
                    p.getLat(), p.getLng(), c.getLat(), c.getLng(), "advance"));
                else changes.add(UnitChange.held(c.getCode(), c.getLat(), c.getLng()));
            }
        }
        for (UnitSnapshot p : parent) if (!cm.containsKey(p.getCode()))
            changes.add(UnitChange.deleted(p.getCode(), p.getLat(), p.getLng()));

        return changes;
    }

    private BranchTree findTreeForNode(String nodeId) {
        for (BranchTree t : branchManager.listTrees())
            if (t.findNode(nodeId) != null) return t;
        return null;
    }

    // ---- Cache ----

    public String loadCache(String side, int round) {
        Path f = cachePath(side, round);
        try { return Files.exists(f) ? Files.readString(f) : null; }
        catch (IOException e) { return null; }
    }

    public void saveCache(String side, int round, String treeId, String nodeId, String transcript) throws IOException {
        Path dir = cacheDir(side);
        Files.createDirectories(dir);
        ObjectNode cache = MAPPER.createObjectNode();
        cache.put("treeId", treeId);
        cache.put("nodeId", nodeId);
        cache.put("round", round);
        cache.put("side", side);
        cache.put("generatedAt", System.currentTimeMillis());
        try { cache.set("transcript", MAPPER.readTree(transcript)); }
        catch (Exception e) { cache.put("transcript", transcript); }
        Files.writeString(cachePath(side, round), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(cache));
        LOG.info("Saved " + side + " round-" + round + " cache");
    }

    // ---- Path helpers ----

    private Path commanderPath(String side) {
        return workspaceDir.resolve("commanders").resolve(side);
    }
    private Path cacheDir(String side) {
        return commanderPath(side).resolve("cache");
    }
    private Path cachePath(String side, int round) {
        return cacheDir(side).resolve("round-" + round + ".json");
    }
}
