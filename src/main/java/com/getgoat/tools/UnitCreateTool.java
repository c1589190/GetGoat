package com.getgoat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getgoat.map.manager.UnitsManager;
import com.getgoat.map.model.Unit;

public class UnitCreateTool implements ToolUnit {
    private final UnitsManager um;
    public UnitCreateTool(UnitsManager um) { this.um = um; }

    @Override public String getName() { return "create_unit"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Create a new unit at a specific position.");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("code", ToolRegistry.stringParam("Unique code"));
        props.set("name", ToolRegistry.stringParam("Display name"));
        props.set("source", ToolRegistry.stringParam("Source tag (default 'custom')"));
        props.set("status", ToolRegistry.stringParam("Current status: active, idle, moving, engaged (default active)"));
        props.set("type", ToolRegistry.stringParam("Type: infantry, naval, air, civilian, supply, generic"));
        props.set("lat", ToolRegistry.numberParam("Latitude"));
        props.set("lng", ToolRegistry.numberParam("Longitude"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object"); params.set("properties", props);
        params.set("required", ToolRegistry.arrayNode().add("code").add("lat").add("lng"));
        def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        String code = args.get("code").asText();
        String name = ToolRegistry.s(args, "name", code);
        String source = ToolRegistry.s(args, "source", "custom");
        String type = ToolRegistry.s(args, "type", "generic");
        String status = ToolRegistry.s(args, "status", "active");
        double lat = args.get("lat").asDouble(), lng = args.get("lng").asDouble();
        try {
            Unit u = um.create(code, name, source, type, lat, lng);
            u.setStatus(status);
            ObjectNode r = ToolRegistry.objectNode();
            r.put("ok", true).put("code", u.getCode()).put("name", u.getName());
            r.put("source", u.getSource()).put("status", u.getStatus()).put("type", u.getType())
             .put("lat", u.getLat()).put("lng", u.getLng());
            return r;
        } catch (IllegalArgumentException e) {
            return ToolRegistry.objectNode().put("ok", false).put("error", e.getMessage());
        }
    }
}
