package com.getgoat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getgoat.map.manager.UnitsManager;

public class UnitDeleteTool implements ToolUnit {
    private final UnitsManager um;
    public UnitDeleteTool(UnitsManager um) { this.um = um; }

    @Override public String getName() { return "delete_unit"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Delete a unit by its unique code.");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("code", ToolRegistry.stringParam("Unit code to delete"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object"); params.set("properties", props);
        params.set("required", ToolRegistry.arrayNode().add("code"));
        def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        String code = args.get("code").asText();
        return ToolRegistry.objectNode().put("deleted", um.delete(code)).put("code", code);
    }
}
