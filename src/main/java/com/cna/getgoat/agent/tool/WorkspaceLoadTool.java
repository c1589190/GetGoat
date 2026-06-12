package com.cna.getgoat.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.map.campaigns.NodesManager;
import com.cna.getgoat.map.campaigns.UnitsManager;

public class WorkspaceLoadTool implements ToolUnit {
    private final NodesManager bm;
    private final UnitsManager um;
    public WorkspaceLoadTool(NodesManager bm, UnitsManager um) { this.bm = bm; this.um = um; }

    @Override public String getName() { return "load_workspace"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Reload all branch trees and units from the workspace directory on disk.");
        def.set("parameters", ToolRegistry.objectNode());
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        String result = bm.reloadWorkspace();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result);
        } catch (Exception e) {
            ObjectNode out = ToolRegistry.objectNode();
            out.put("ok", true).put("branches_reloaded", bm.listTrees().size())
               .put("units", um.count());
            return out;
        }
    }
}
