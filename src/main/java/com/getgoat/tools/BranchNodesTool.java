package com.getgoat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getgoat.map.branch.BranchManager;

public class BranchNodesTool implements ToolUnit {
    private final BranchManager bm;
    public BranchNodesTool(BranchManager bm) { this.bm = bm; }

    @Override public String getName() { return "list_branch_nodes"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "List all rounds/nodes in a branch tree, showing round, strategy, outcome, and children.");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("tree_id", ToolRegistry.stringParam("Branch tree ID"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object"); params.set("properties", props);
        ArrayNode req = ToolRegistry.arrayNode(); req.add("tree_id");
        params.set("required", req); def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        String treeId = ToolRegistry.s(args, "tree_id", null);
        if (treeId == null || treeId.isEmpty())
            return ToolRegistry.objectNode().put("error", "tree_id required");
        // Re-use flat list export
        String json = bm.exportFlatListJson(treeId);
        if (json.startsWith("{\"error\""))
            return ToolRegistry.objectNode().put("error", "tree not found");
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            return ToolRegistry.objectNode().put("error", e.getMessage());
        }
    }
}
