package com.cna.getgoat.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.map.campaigns.NodesManager;

public class BranchApplyTool implements ToolUnit {
    private final NodesManager bm;
    public BranchApplyTool(NodesManager bm) { this.bm = bm; }

    @Override public String getName() { return "apply_branch_node"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Apply a branch round/node to the map — moves all units to their positions in that round.");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("tree_id", ToolRegistry.stringParam("Branch tree ID"));
        props.set("node_id", ToolRegistry.stringParam("Node/round ID to apply"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object"); params.set("properties", props);
        ArrayNode req = ToolRegistry.arrayNode(); req.add("tree_id"); req.add("node_id");
        params.set("required", req); def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        String treeId = ToolRegistry.s(args, "tree_id", null);
        String nodeId = ToolRegistry.s(args, "node_id", null);
        if (treeId == null || nodeId == null)
            return ToolRegistry.objectNode().put("error", "tree_id and node_id required");
        boolean ok = bm.applyNode(treeId, nodeId);
        ObjectNode out = ToolRegistry.objectNode();
        out.put("applied", ok);
        if (ok) out.put("node_id", nodeId);
        else out.put("error", "node or tree not found");
        return out;
    }
}
