package com.getgoat.agent.modes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getgoat.agent.AgentMode;
import com.getgoat.agent.BaseAgent;
import com.getgoat.agent.sim.*;
import com.getgoat.map.branch.BranchNode;
import com.getgoat.map.branch.CommanderAction;
import com.getgoat.map.model.*;

import java.util.*;

/**
 * Simulation mode — adjudicates a wargame round.
 *
 * Two paths:
 *   Deterministic: call simulateRound() directly → runs all resolvers without LLM
 *   LLM-orchestrated: LLM calls get_all_deployments → resolve_movement → detect_engagements
 *                     → resolve_combat → save_outcome via tool calls
 */
public class SimulateMode implements AgentMode {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Cached resolver instances (initialized on first use)
    private MovementResolver movementResolver;
    private EngagementDetector engagementDetector;
    private CombatResolver combatResolver;

    @Override
    public String getName() { return "simulate"; }

    @Override
    public String getDefaultSystemPrompt() {
        return """
            你是一名战场推演裁决官。你需要按顺序调用以下工具裁决本回合：
            1. get_all_deployments — 获取各方部署命令
            2. resolve_movement — 裁决单位移动
            3. detect_engagements — 检测接敌
            4. resolve_combat — 裁决战斗
            5. save_outcome — 保存推演结果

            裁决使用兰彻斯特方程简化版，考虑地形修正和兵力对比。""";
    }

    @Override
    public JsonNode getToolDefinitions() {
        ArrayNode tools = MAPPER.createArrayNode();
        tools.add(defineTool("get_all_deployments", "获取当前节点所有阵营的部署命令",
            new String[][]{{"treeId", "string", "分支树ID"}, {"nodeId", "string", "节点ID"}}));
        tools.add(defineTool("resolve_movement", "裁决所有单位移动（匀速模型+地形减速），返回各单位实际到达位置",
            new String[][]{{"unitMoves", "string", "JSON数组[{code,fromLat,fromLng,toLat,toLng,type}]"}}));
        tools.add(defineTool("detect_engagements", "检测所有敌对单位对是否进入交战半径",
            new String[][]{{"friendlySide", "string", "本方阵营名"},
                           {"enemySides", "string", "敌方阵营JSON数组[\"side1\",\"side2\"]"}}));
        tools.add(defineTool("resolve_combat", "裁决交战结果（简化的兰彻斯特方程）",
            new String[][]{{"pairs", "string", "JSON数组[{attacker,defender,attackerPower,defenderPower}]"}}));
        tools.add(defineTool("save_outcome", "保存推演结果到新分支节点",
            new String[][]{{"treeId", "string", "分支树ID"}, {"parentNodeId", "string", "父节点ID"},
                           {"name", "string", "新节点名(如'Round 1 Result')"}, {"round", "number", "回合号"},
                           {"outcome", "string", "推演结果摘要"}}));
        // Intel tools — LLM uses these to declare per-side fog-of-war
        tools.add(defineTool("set_side_intel", "声明某个阵营在本回合结束后能看到什么。entriesJson是[{unitCode,phantomId,name,apparentSource,reportedType,certainty,lat,lng,uncertaintyRadiusKm,lastObservedRound}]。己方单位自动确认，无需包含。",
            new String[][]{{"side", "string", "目标阵营"},
                           {"entriesJson", "string", "该阵营可见的单位情报JSON数组"}}));
        tools.add(defineTool("set_explored_region", "标记某阵营已探索的经纬度矩形区域",
            new String[][]{{"side", "string", "目标阵营"},
                           {"minLat", "number", "南边界"}, {"maxLat", "number", "北边界"},
                           {"minLng", "number", "西边界"}, {"maxLng", "number", "东边界"}}));
        tools.add(defineTool("set_phantom", "在目标阵营情报中植入一个假象单位（战术欺骗）",
            new String[][]{{"targetSide", "string", "看到假象的阵营"},
                           {"name", "string", "假象单位名称"},
                           {"type", "string", "假象单位类型"},
                           {"lat", "number", "显示纬度"}, {"lng", "number", "显示经度"}}));
        return tools;
    }

    @Override
    public JsonNode buildCurrentRoundContext(BaseAgent agent, BranchNode current,
                                              List<BranchNode> chain, String guidance) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("## 推演裁决 — Round ").append(current.getRound())
            .append(" (").append(current.getName()).append(")\n\n");
        if (guidance != null && !guidance.isEmpty())
            ctx.append("### 裁决参数\n").append(guidance).append("\n\n");
        ctx.append("### 当前态势\n").append(agent.formatIntel(current)).append("\n\n");

        var actions = current.getCommanderActions();
        ctx.append("### 各方部署计划\n");
        for (var entry : actions.entrySet()) {
            ctx.append("**").append(entry.getKey()).append("**:\n");
            for (CommanderAction a : entry.getValue()) {
                if (a.finalPlan != null && a.finalPlan.isArray() && a.finalPlan.size() > 0) {
                    ctx.append("- ").append(a.finalPlan.size()).append(" 项行动");
                    if (a.rationale != null && !a.rationale.isEmpty())
                        ctx.append(" | ").append(a.rationale.length() > 120
                            ? a.rationale.substring(0, 120) + "..." : a.rationale);
                    ctx.append("\n");
                } else if (a.rationale != null && !a.rationale.isEmpty()) {
                    ctx.append("- 无具体行动，理由: ").append(a.rationale.length() > 120
                        ? a.rationale.substring(0, 120) + "..." : a.rationale).append("\n");
                }
            }
        }
        ctx.append("\n裁决顺序: 移动→接敌→战斗→情报(每方视角)→保存。\n\n");
        ctx.append("情报裁决指南:\n");
        ctx.append("- 己方单位对该方始终CONFIRMED\n");
        ctx.append("- 在友方单位12km内的敌军→ESTIMATED(不确定半径5km)\n");
        ctx.append("- 在山脉后的敌军→OUTDATED或不可见\n");
        ctx.append("- 可用set_phantom模拟佯动/伪装等战术欺骗效果\n");
        ctx.append("- 用set_explored_region标记各方已侦察的区域");
        return agent.msg("user", ctx.toString());
    }

    @Override
    public String dispatchTool(BaseAgent agent, String toolName, JsonNode args) {
        initResolvers(agent);
        switch (toolName) {
            case "get_all_deployments":
                return getAllDeployments(agent, agent.getArgS(args, "treeId"),
                    agent.getArgS(args, "nodeId"));
            case "resolve_movement":
                return resolveAllMovements(agent.getArgS(args, "unitMoves"));
            case "detect_engagements":
                return detectEngagementsCall(agent.getArgS(args, "friendlySide"),
                    agent.getArgS(args, "enemySides"));
            case "resolve_combat":
                return resolveBattles(agent, agent.getArgS(args, "pairs"));
            case "save_outcome":
                return saveOutcome(agent, agent.getArgS(args, "treeId"),
                    agent.getArgS(args, "parentNodeId"), agent.getArgS(args, "name"),
                    (int)agent.getArgD(args, "round"), agent.getArgS(args, "outcome"));
            case "set_side_intel":
                return setSideIntel(agent, agent.getArgS(args, "side"),
                    agent.getArgS(args, "entriesJson"));
            case "set_explored_region":
                return setExploredRegion(agent, agent.getArgS(args, "side"),
                    agent.getArgD(args, "minLat"), agent.getArgD(args, "maxLat"),
                    agent.getArgD(args, "minLng"), agent.getArgD(args, "maxLng"));
            case "set_phantom":
                return setPhantom(agent, agent.getArgS(args, "targetSide"),
                    agent.getArgS(args, "name"), agent.getArgS(args, "type"),
                    agent.getArgD(args, "lat"), agent.getArgD(args, "lng"));
            default:
                return "{\"error\":\"unknown simulate tool: " + toolName + "\"}";
        }
    }

    private void initResolvers(BaseAgent agent) {
        if (movementResolver == null) {
            movementResolver = new MovementResolver(agent.getMapManager());
            engagementDetector = new EngagementDetector(agent.getUnitsManager(), agent.getMapManager());
            combatResolver = new CombatResolver(agent.getMapManager());
        }
    }

    // ---- Tool implementations ----

    private String getAllDeployments(BaseAgent agent, String treeId, String nodeId) {
        var tree = agent.getBranchManager().getTree(treeId);
        if (tree == null) return "{\"error\":\"tree not found\"}";
        var node = tree.findNode(nodeId);
        if (node == null) return "{\"error\":\"node not found\"}";

        var allActions = node.getCommanderActions();
        ArrayNode result = MAPPER.createArrayNode();
        for (var entry : allActions.entrySet()) {
            for (CommanderAction a : entry.getValue()) {
                ObjectNode item = result.addObject();
                item.put("side", entry.getKey());
                item.put("rationale", a.rationale != null ? a.rationale : "");
                if (a.finalPlan != null) item.set("plan", a.finalPlan);
                else item.set("plan", MAPPER.createArrayNode());
            }
        }
        return result.toString();
    }

    private String resolveAllMovements(String unitMovesJson) {
        try {
            JsonNode arr = MAPPER.readTree(unitMovesJson);
            ArrayNode results = MAPPER.createArrayNode();
            for (JsonNode m : arr) {
                String code = m.has("code") ? m.get("code").asText() : "?";
                double fl = m.has("fromLat") ? m.get("fromLat").asDouble() : 0;
                double fln = m.has("fromLng") ? m.get("fromLng").asDouble() : 0;
                double tl = m.has("toLat") ? m.get("toLat").asDouble() : 0;
                double tln = m.has("toLng") ? m.get("toLng").asDouble() : 0;
                String type = m.has("type") ? m.get("type").asText() : "infantry";
                double speed = MovementResolver.baseSpeedKmh(type);

                var outcome = movementResolver.resolve(code, fl, fln, tl, tln, speed);
                ObjectNode r = results.addObject();
                r.put("code", code);
                r.put("reached", outcome.reached);
                r.put("actualLat", Math.round(outcome.toLat * 10000.0) / 10000.0);
                r.put("actualLng", Math.round(outcome.toLng * 10000.0) / 10000.0);
                r.put("progressPct", Math.round(outcome.progressPct * 10.0) / 10.0);
                r.put("terrain", outcome.terrainAtDest != null ? outcome.terrainAtDest : "unknown");
            }
            return results.toString();
        } catch (Exception e) {
            return "{\"error\":\"movement resolve failed: " + e.getMessage() + "\"}";
        }
    }

    private String detectEngagementsCall(String friendlySide, String enemySidesJson) {
        try {
            List<String> enemySides = new ArrayList<>();
            JsonNode arr = MAPPER.readTree(enemySidesJson);
            if (arr.isArray()) for (JsonNode s : arr) enemySides.add(s.asText());

            var engagements = engagementDetector.detect(friendlySide, enemySides);
            ArrayNode result = MAPPER.createArrayNode();
            for (var e : engagements) {
                ObjectNode item = result.addObject();
                item.put("attacker", e.attackerCode);
                item.put("defender", e.defenderCode);
                item.put("distanceKm", Math.round(e.distanceKm * 10.0) / 10.0);
                item.put("terrain", e.terrain);
            }
            return result.toString();
        } catch (Exception e) {
            return "{\"error\":\"engagement detect failed: " + e.getMessage() + "\"}";
        }
    }

    private String resolveBattles(BaseAgent agent, String pairsJson) {
        try {
            JsonNode pairs = MAPPER.readTree(pairsJson);
            ArrayNode results = MAPPER.createArrayNode();
            for (JsonNode p : pairs) {
                String atk = p.has("attacker") ? p.get("attacker").asText() : "?";
                String def = p.has("defender") ? p.get("defender").asText() : "?";
                int atkStr = p.has("attackerStrength") ? p.get("attackerStrength").asInt()
                    : (int)(p.has("attackerPower") ? p.get("attackerPower").asDouble() : 10000);
                int defStr = p.has("defenderStrength") ? p.get("defenderStrength").asInt()
                    : (int)(p.has("defenderPower") ? p.get("defenderPower").asDouble() : 10000);
                String atkType = p.has("attackerType") ? p.get("attackerType").asText() : "infantry";
                String defType = p.has("defenderType") ? p.get("defenderType").asText() : "infantry";

                // Get terrain at midpoint
                var terrainCell = agent.getMapManager().getTerrainAt(
                    p.has("midLat") ? p.get("midLat").asDouble() : 35,
                    p.has("midLng") ? p.get("midLng").asDouble() : 117);

                var outcome = combatResolver.resolve(atk, atkStr, atkType, def, defStr, defType, terrainCell);
                results.add(outcomeToJson(outcome.attacker));
                results.add(outcomeToJson(outcome.defender));
            }
            return results.toString();
        } catch (Exception e) {
            return "{\"error\":\"combat resolve failed: " + e.getMessage() + "\"}";
        }
    }

    private ObjectNode outcomeToJson(SimulationResult.CombatOutcome o) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("unitCode", o.unitCode);
        n.put("initialStrength", Math.round(o.initialStrength));
        n.put("finalStrength", Math.round(o.finalStrength));
        n.put("lossRate", Math.round(o.lossRate * 1000.0) / 10.0);
        n.put("newStatus", o.newStatus);
        n.put("reason", o.reason);
        return n;
    }

    private String saveOutcome(BaseAgent agent, String treeId, String parentNodeId,
                                String name, int round, String outcome) {
        try {
            var node = agent.getBranchManager().addRound(treeId, parentNodeId, name,
                round, "historical", outcome);
            if (node == null) return "{\"error\":\"parent node not found\"}";
            return "{\"ok\":true,\"nodeId\":\"" + node.getId() + "\",\"round\":" + round
                + ",\"name\":\"" + name + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"save failed: " + e.getMessage() + "\"}";
        }
    }

    // ========================================================================
    //  Intel tools — per-side fog-of-war
    // ========================================================================

    /** Accumulated intel maps during LLM tool calling. Flushed to BranchNode on round end. */
    private final Map<String, SideIntelMap> pendingIntelMaps = new LinkedHashMap<>();

    private String setSideIntel(BaseAgent agent, String side, String entriesJson) {
        try {
            JsonNode arr = MAPPER.readTree(entriesJson);
            SideIntelMap intelMap = pendingIntelMaps.computeIfAbsent(
                side, s -> SideIntelMap.create(side, 0));
            int count = 0;
            for (JsonNode e : arr) {
                SideIntelEntry entry = MAPPER.treeToValue(e, SideIntelEntry.class);
                intelMap.addEntry(entry);
                count++;
            }
            return "{\"ok\":true,\"side\":\"" + side + "\",\"entries\":" + count + "}";
        } catch (Exception e) {
            return "{\"error\":\"set_side_intel failed: " + e.getMessage() + "\"}";
        }
    }

    private String setExploredRegion(BaseAgent agent, String side,
                                       double minLat, double maxLat, double minLng, double maxLng) {
        SideIntelMap intelMap = pendingIntelMaps.computeIfAbsent(
            side, s -> SideIntelMap.create(side, 0));
        intelMap.addExploredBounds(minLat, maxLat, minLng, maxLng);
        return "{\"ok\":true,\"side\":\"" + side + "\",\"bounds\":["
            + String.format("%.2f", minLat) + "," + String.format("%.2f", maxLat) + ","
            + String.format("%.2f", minLng) + "," + String.format("%.2f", maxLng) + "]}";
    }

    private String setPhantom(BaseAgent agent, String targetSide,
                                String name, String type, double lat, double lng) {
        String phantomId = "phantom-" + UUID.randomUUID().toString().substring(0, 6);
        SideIntelEntry entry = SideIntelEntry.phantom(phantomId, name, "unknown", type, lat, lng, 0);
        SideIntelMap intelMap = pendingIntelMaps.computeIfAbsent(
            targetSide, s -> SideIntelMap.create(targetSide, 0));
        intelMap.addEntry(entry);
        return "{\"ok\":true,\"phantomId\":\"" + phantomId + "\",\"targetSide\":\""
            + targetSide + "\",\"lat\":" + lat + ",\"lng\":" + lng + "}";
    }

    /** Build automatic (non-LLM) intel for all sides after combat resolution. */
    private void autoGenerateIntel(BaseAgent agent, String treeId, String nodeId, int newRound) {
        pendingIntelMaps.clear();
        Set<String> sides = new LinkedHashSet<>();
        for (Unit u : agent.getUnitsManager().listAll()) sides.add(u.getSource());

        for (String side : sides) {
            SideIntelMap intelMap = SideIntelMap.create(side, newRound);
            // Mark all units from this side as CONFIRMED to themselves
            for (Unit u : agent.getUnitsManager().listAll()) {
                if (u.getStatus().equals("destroyed")) continue;
                if (side.equals(u.getSource())) {
                    intelMap.addEntry(SideIntelEntry.confirmed(
                        u.getCode(), u.getName(), u.getSource(), u.getType(),
                        u.getLat(), u.getLng(), newRound));
                }
            }
            pendingIntelMaps.put(side, intelMap);
        }
    }

    /** Flush accumulated intel maps to the BranchNode. */
    private void flushIntelToNode(BaseAgent agent, String treeId, String nodeId) {
        var tree = agent.getBranchManager().getTree(treeId);
        if (tree == null) return;
        var node = tree.findNode(nodeId);
        if (node == null) return;
        for (var entry : pendingIntelMaps.entrySet()) {
            entry.getValue().setRoundNumber(node.getRound());
            node.putSideIntelMap(entry.getKey(), entry.getValue());
        }
        agent.getBranchManager().saveToDisk();
        pendingIntelMaps.clear();
    }

    // ========================================================================
    //  Deterministic simulation (no LLM needed)
    // ========================================================================

    /**
     * Run a complete simulation deterministically, without calling an LLM.
     * Extracts all deployments from the node, resolves movement + combat,
     * applies results to live units, and saves a new outcome node.
     */
    public SimulationResult simulateDeterministic(BaseAgent agent, String treeId, String nodeId) {
        initResolvers(agent);
        SimulationResult result = new SimulationResult();

        var tree = agent.getBranchManager().getTree(treeId);
        if (tree == null) { result.summary = "Tree not found"; return result; }
        var node = tree.findNode(nodeId);
        if (node == null) { result.summary = "Node not found"; return result; }
        result.roundNumber = node.getRound() + 1;

        // 1. Collect all unit movements from deployments
        List<Unit> allUnits = agent.getUnitsManager().listAll();
        Map<String, String> unitTypes = new LinkedHashMap<>();
        for (Unit u : allUnits) unitTypes.put(u.getCode(), u.getType());

        var allActions = node.getCommanderActions();
        for (var entry : allActions.entrySet()) {
            for (CommanderAction a : entry.getValue()) {
                if (a.finalPlan == null || !a.finalPlan.isArray()) continue;
                for (JsonNode tc : a.finalPlan) {
                    JsonNode fn = tc.has("function") ? tc.get("function") : tc;
                    if (!"move_unit".equals(fn.has("name") ? fn.get("name").asText() : "")) continue;
                    JsonNode args = fn.get("arguments");
                    if (args == null) continue;
                    if (args.isTextual()) try { args = MAPPER.readTree(args.asText()); } catch (Exception e) {}

                    String code = args.has("code") ? args.get("code").asText() : null;
                    double toLat = args.has("lat") ? args.get("lat").asDouble() : 0;
                    double toLng = args.has("lng") ? args.get("lng").asDouble() : 0;
                    if (code == null) continue;

                    Unit u = agent.getUnitsManager().get(code);
                    if (u == null) continue;

                    double speed = MovementResolver.baseSpeedKmh(u.getType());
                    var mo = movementResolver.resolve(code, u.getLat(), u.getLng(), toLat, toLng, speed);
                    result.movements.add(mo);

                    // Apply movement to live unit
                    agent.getUnitsManager().move(code, mo.toLat, mo.toLng);
                }
            }
        }

        // 2. Detect engagements between all opposing sides
        Map<String, String> unitSides = new LinkedHashMap<>();
        for (Unit u : agent.getUnitsManager().listAll()) unitSides.put(u.getCode(), u.getSource());
        Set<String> sides = new LinkedHashSet<>(unitSides.values());

        for (String side : sides) {
            List<String> enemies = new ArrayList<>();
            for (String s : sides) if (!s.equals(side)) enemies.add(s);
            var engs = engagementDetector.detect(side, enemies);
            result.engagements.addAll(engs);
        }

        // 3. Resolve combat for each engagement
        for (var eng : result.engagements) {
            Unit atkUnit = agent.getUnitsManager().get(eng.attackerCode);
            Unit defUnit = agent.getUnitsManager().get(eng.defenderCode);
            if (atkUnit == null || defUnit == null) continue;

            int atkStr = atkUnit.getStrength();
            int defStr = defUnit.getStrength();
            String atkType = unitTypes.getOrDefault(eng.attackerCode, "infantry");
            String defType = unitTypes.getOrDefault(eng.defenderCode, "infantry");

            var tc = agent.getMapManager().getTerrainAt(
                (atkUnit.getLat() + defUnit.getLat()) / 2,
                (atkUnit.getLng() + defUnit.getLng()) / 2);

            var pair = combatResolver.resolve(eng.attackerCode, atkStr, atkType,
                eng.defenderCode, defStr, defType, tc);
            result.combatResults.add(pair.attacker);
            result.combatResults.add(pair.defender);

            // Apply casualties and status to live units
            atkUnit.setStrength((int)pair.attacker.finalStrength);
            atkUnit.setStatus(pair.attacker.newStatus);
            if ("destroyed".equals(pair.attacker.newStatus))
                agent.getUnitsManager().delete(eng.attackerCode);

            defUnit.setStrength((int)pair.defender.finalStrength);
            defUnit.setStatus(pair.defender.newStatus);
            if ("destroyed".equals(pair.defender.newStatus))
                agent.getUnitsManager().delete(eng.defenderCode);
        }

        // 4. Auto-generate per-side intel maps (no LLM = auto intel only)
        autoGenerateIntel(agent, treeId, nodeId, result.roundNumber);

        // 5. Create outcome node
        result.summary = result.toSummary();
        var newNode = agent.getBranchManager().addRound(treeId, nodeId,
            "Round " + result.roundNumber + " Result", result.roundNumber, "historical",
            result.summary);

        // 6. Flush intel maps to the new node
        if (newNode != null) {
            flushIntelToNode(agent, treeId, newNode.getId());
        }

        result.summary += " | node:" + (newNode != null ? newNode.getId() : "null")
            + " | intel:" + pendingIntelMaps.size() + " sides";
        return result;
    }

    // ---- Helpers ----

    private ObjectNode defineTool(String name, String desc, String[][] params) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", name);
        fn.put("description", desc);
        ObjectNode ps = fn.putObject("parameters");
        ps.put("type", "object");
        ObjectNode props = ps.putObject("properties");
        ArrayNode required = ps.putArray("required");
        for (String[] p : params) {
            props.putObject(p[0]).put("type", p[1]).put("description", p[2]);
            if (p.length < 4 || !"optional".equals(p[3])) required.add(p[0]);
        }
        return tool;
    }
}
