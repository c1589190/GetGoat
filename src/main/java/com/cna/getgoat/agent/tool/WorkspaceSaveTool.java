package com.cna.getgoat.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.map.campaigns.NodesManager;
import com.cna.getgoat.map.campaigns.UnitsManager;

public class WorkspaceSaveTool implements ToolUnit {
    private final NodesManager bm;
    private final UnitsManager um;
    public WorkspaceSaveTool(NodesManager bm, UnitsManager um) { this.bm = bm; this.um = um; }

    @Override public String getName() { return "save_workspace"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Save current unit positions and branch trees to the workspace directory (workspaces/songhu-1937/).");
        def.set("parameters", ToolRegistry.objectNode());
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        bm.saveToDisk();
        ObjectNode out = ToolRegistry.objectNode();
        out.put("ok", true);
        out.put("units", um.count());
        out.put("branches_saved", bm.listTrees().size());
        return out;
    }
}
