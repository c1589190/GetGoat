package com.cna.getgoat.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.map.campaigns.NodesManager;
import com.cna.getgoat.map.campaigns.node.BranchNode;

public class BranchSaveRoundTool implements ToolUnit {
    private final NodesManager bm;
    public BranchSaveRoundTool(NodesManager bm) { this.bm = bm; }

    @Override public String getName() { return "save_branch_round"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Save current unit positions as a new round in a branch tree. Captures all unit snapshots automatically.");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("tree_id", ToolRegistry.stringParam("Branch tree ID"));
        props.set("parent_node_id", ToolRegistry.stringParam("Parent node ID (use 'root' for the root node)"));
        props.set("name", ToolRegistry.stringParam("Round name, e.g. 'R3: 大场激战'"));
        props.set("round", ToolRegistry.numberParam("Round number (1, 2, 3...)"));
        props.set("strategy", ToolRegistry.stringParam("Strategy tag: historical, alt1, alt2, etc."));
        props.set("outcome", ToolRegistry.stringParam("Narrative description of the round's outcome"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object"); params.set("properties", props);
        ArrayNode req = ToolRegistry.arrayNode();
        req.add("tree_id"); req.add("name"); req.add("round");
        params.set("required", req); def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        String treeId = ToolRegistry.s(args, "tree_id", null);
        String parentId = ToolRegistry.s(args, "parent_node_id", "root");
        String name = ToolRegistry.s(args, "name", "Round");
        int round = args.has("round") ? args.get("round").asInt() : 1;
        String strategy = ToolRegistry.s(args, "strategy", "historical");
        String outcome = ToolRegistry.s(args, "outcome", "");

        if (treeId == null)
            return ToolRegistry.objectNode().put("error", "tree_id required");

        BranchNode node = bm.addRound(treeId, parentId, name, round, strategy, outcome);
        if (node == null)
            return ToolRegistry.objectNode().put("error", "parent node or tree not found");

        ObjectNode out = ToolRegistry.objectNode();
        out.put("ok", true);
        out.put("node_id", node.getId());
        out.put("round", round);
        return out;
    }
}
