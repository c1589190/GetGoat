package com.cna.getgoat.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.map.campaigns.UnitsManager;
import com.cna.getgoat.map.campaigns.unit.Unit;

public class UnitGetTool implements ToolUnit {
    private final UnitsManager um;
    public UnitGetTool(UnitsManager um) { this.um = um; }

    @Override public String getName() { return "get_unit"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Get detailed info about a unit by its unique code.");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("code", ToolRegistry.stringParam("Unit unique code"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object"); params.set("properties", props);
        params.set("required", ToolRegistry.arrayNode().add("code"));
        def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        Unit u = um.get(args.get("code").asText());
        if (u == null) return ToolRegistry.objectNode().put("error", "not found");
        ObjectNode r = ToolRegistry.objectNode();
        r.put("code", u.getCode()).put("name", u.getName()).put("description", u.getDescription());
        r.put("source", u.getSource()).put("status", u.getStatus())
         .put("type", u.getType()).put("color", u.getColor());
        r.put("lat", u.getLat()).put("lng", u.getLng()).put("created", u.getCreatedAt());
        return r;
    }
}
