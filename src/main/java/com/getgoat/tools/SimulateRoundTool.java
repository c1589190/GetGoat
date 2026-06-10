package com.getgoat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getgoat.agent.modes.SimulateMode;
import com.getgoat.agent.sim.SimulationResult;
import com.getgoat.map.branch.BranchManager;
import com.getgoat.map.manager.MapManager;
import com.getgoat.map.manager.UnitsManager;

/**
 * MCP tool: Simulate a wargame round deterministically.
 *
 * Reads all CommanderActions for the current node, resolves movement and combat,
 * applies results to live units, and saves a new BranchNode.
 */
public class SimulateRoundTool implements ToolUnit {

    private final MapManager mapManager;
    private final UnitsManager unitsManager;
    private final BranchManager branchManager;

    public SimulateRoundTool(MapManager mm, UnitsManager um, BranchManager bm) {
        this.mapManager = mm;
        this.unitsManager = um;
        this.branchManager = bm;
    }

    @Override
    public String getName() { return "simulate_round"; }

    @Override
    public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "裁决一个推演回合：读取各方部署→计算移动→检测接敌→裁决战斗→生成新节点。需要先由各方指挥官完成部署。");

        ObjectNode props = ToolRegistry.objectNode();
        props.set("treeId", ToolRegistry.stringParam("分支树ID"));
        props.set("nodeId", ToolRegistry.stringParam("当前节点ID（部署所在的节点）"));

        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object");
        params.set("properties", props);
        params.set("required", ToolRegistry.arrayNode().add("treeId").add("nodeId"));
        def.set("parameters", params);
        return def;
    }

    @Override
    public JsonNode execute(JsonNode args) throws Exception {
        String treeId = args.has("treeId") ? args.get("treeId").asText() : null;
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;

        if (treeId == null || nodeId == null)
            return ToolRegistry.objectNode().put("error", "treeId and nodeId required");

        var tree = branchManager.getTree(treeId);
        if (tree == null) return ToolRegistry.objectNode().put("error", "tree not found: " + treeId);
        var node = tree.findNode(nodeId);
        if (node == null) return ToolRegistry.objectNode().put("error", "node not found: " + nodeId);

        // Create a temporary agent (no LLM needed) for SimulateMode
        var agent = new com.getgoat.agent.CommanderAgent() {
            @Override public JsonNode callLLM(JsonNode m) { return null; }
        };
        agent.initialize(new com.getgoat.agent.CommanderConfig(), branchManager, unitsManager,
            mapManager, java.nio.file.Path.of(System.getProperty("user.dir")));
        agent.setMode(new SimulateMode());

        // Run deterministic simulation
        SimulateMode simMode = (SimulateMode) agent.getMode();
        SimulationResult result = simMode.simulateDeterministic(agent, treeId, nodeId);

        ObjectNode out = ToolRegistry.objectNode();
        out.put("ok", true);
        out.put("round", result.roundNumber);
        out.put("summary", result.summary);
        out.put("movementsResolved", result.movements.size());
        out.put("engagementsDetected", result.engagements.size());
        out.put("combatResults", result.combatResults.size());

        // Detailed combat report
        int destroyed = 0, retreated = 0, engaged = 0;
        for (var c : result.combatResults) {
            if ("destroyed".equals(c.newStatus)) destroyed++;
            else if ("retreating".equals(c.newStatus)) retreated++;
            else if ("engaged".equals(c.newStatus) || "advancing".equals(c.newStatus)) engaged++;
        }
        out.put("destroyed", destroyed);
        out.put("retreated", retreated);
        out.put("engaged", engaged);
        out.put("unitCount", unitsManager.count());

        // Movement details
        int reached = 0;
        for (var m : result.movements) if (m.reached) reached++;
        out.put("unitsReachedDestination", reached);

        return out;
    }
}
