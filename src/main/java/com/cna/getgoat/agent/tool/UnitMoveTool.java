package com.cna.getgoat.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.map.campaigns.UnitsManager;
import com.cna.getgoat.map.campaigns.unit.Unit;

public class UnitMoveTool implements ToolUnit {
    private final UnitsManager um;
    public UnitMoveTool(UnitsManager um) { this.um = um; }

    @Override public String getName() { return "move_unit"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Move a unit to new coordinates.");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("code", ToolRegistry.stringParam("Unit code"));
        props.set("lat", ToolRegistry.numberParam("New latitude"));
        props.set("lng", ToolRegistry.numberParam("New longitude"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object"); params.set("properties", props);
        params.set("required", ToolRegistry.arrayNode().add("code").add("lat").add("lng"));
        def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        String code = args.get("code").asText();
        double lat = args.get("lat").asDouble(), lng = args.get("lng").asDouble();
        try {
            Unit u = um.move(code, lat, lng);
            return ToolRegistry.objectNode().put("ok", true).put("code", u.getCode())
                .put("new_lat", u.getLat()).put("new_lng", u.getLng());
        } catch (IllegalArgumentException e) {
            return ToolRegistry.objectNode().put("ok", false).put("error", e.getMessage());
        }
    }
}
