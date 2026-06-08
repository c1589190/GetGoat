package com.getgoat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getgoat.map.manager.UnitsManager;
import com.getgoat.map.model.Unit;
import java.util.List;

public class UnitListTool implements ToolUnit {
    private final UnitsManager um;
    public UnitListTool(UnitsManager um) { this.um = um; }

    @Override public String getName() { return "list_units"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "List units. Filter by source, status, name (exact) or search (fuzzy keyword across source/status/name).");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("source", ToolRegistry.stringParam("Exact source filter"));
        props.set("status", ToolRegistry.stringParam("Exact status filter (e.g. active, idle, engaged)"));
        props.set("name", ToolRegistry.stringParam("Exact name filter"));
        props.set("search", ToolRegistry.stringParam("Fuzzy keyword search across source, status, and name"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object"); params.set("properties", props);
        params.set("required", ToolRegistry.arrayNode());
        def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        String search = ToolRegistry.s(args, "search", null);
        String source = ToolRegistry.s(args, "source", null);
        String status = ToolRegistry.s(args, "status", null);
        String name = ToolRegistry.s(args, "name", null);

        List<Unit> list;
        String filterDesc;
        if (search != null) {
            list = um.search(search);
            filterDesc = "search=" + search;
        } else if (status != null) {
            list = um.listByStatus(status);
            filterDesc = "status=" + status;
        } else if (name != null) {
            list = um.listByName(name);
            filterDesc = "name=" + name;
        } else if (source != null) {
            list = um.listBySource(source);
            filterDesc = "source=" + source;
        } else {
            list = um.listAll();
            filterDesc = "all";
        }

        ArrayNode arr = ToolRegistry.arrayNode();
        for (Unit u : list) {
            ObjectNode o = ToolRegistry.objectNode();
            o.put("code", u.getCode()).put("name", u.getName())
             .put("source", u.getSource()).put("status", u.getStatus()).put("type", u.getType())
             .put("lat", u.getLat()).put("lng", u.getLng());
            arr.add(o);
        }
        ObjectNode out = ToolRegistry.objectNode();
        out.put("count", list.size()); out.put("filter", filterDesc); out.set("units", arr);
        return out;
    }
}
