package com.cna.getgoat.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.map.campaigns.NodesManager;
import com.cna.getgoat.map.campaigns.node.BranchTree;
import java.util.List;

public class BranchListTool implements ToolUnit {
    private final NodesManager bm;
    public BranchListTool(NodesManager bm) { this.bm = bm; }

    @Override public String getName() { return "list_branch_trees"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "List all available branch trees (wargame scenarios).");
        def.set("parameters", ToolRegistry.objectNode()); // no params
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        List<BranchTree> trees = bm.listTrees();
        ArrayNode arr = ToolRegistry.arrayNode();
        for (BranchTree t : trees) {
            ObjectNode o = ToolRegistry.objectNode();
            o.put("id", t.getId());
            o.put("name", t.getName());
            o.put("nodeCount", t.countNodes());
            o.put("createdAt", t.getCreatedAt());
            arr.add(o);
        }
        ObjectNode out = ToolRegistry.objectNode();
        out.put("count", trees.size());
        out.set("trees", arr);
        return out;
    }
}
