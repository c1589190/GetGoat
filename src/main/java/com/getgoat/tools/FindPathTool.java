package com.getgoat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getgoat.map.manager.MapManager;

public class FindPathTool implements ToolUnit {
    private final MapManager mm;
    public FindPathTool(MapManager mm) { this.mm = mm; }

    @Override public String getName() { return "find_path"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Find shortest terrain-weighted path between two points. Toggle land/water.");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("lat1", ToolRegistry.numberParam("Start latitude"));
        props.set("lng1", ToolRegistry.numberParam("Start longitude"));
        props.set("lat2", ToolRegistry.numberParam("End latitude"));
        props.set("lng2", ToolRegistry.numberParam("End longitude"));
        props.set("allow_land", ToolRegistry.boolParam("Allow land traversal (default true)"));
        props.set("allow_water", ToolRegistry.boolParam("Allow water traversal (default false)"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object"); params.set("properties", props);
        params.set("required", ToolRegistry.arrayNode().add("lat1").add("lng1").add("lat2").add("lng2"));
        def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        double lat1 = args.get("lat1").asDouble(), lng1 = args.get("lng1").asDouble();
        double lat2 = args.get("lat2").asDouble(), lng2 = args.get("lng2").asDouble();
        boolean land = ToolRegistry.b(args, "allow_land", true);
        boolean water = ToolRegistry.b(args, "allow_water", false);
        var r = mm.findGridPath(lat1, lng1, lat2, lng2, 0.125, land, water);
        ObjectNode out = ToolRegistry.objectNode();
        out.put("reachable", r.path().size() > 1);
        out.put("straight_km", Math.round(r.straightLineKm()*10.0)/10.0);
        out.put("cost_km", Math.round(r.totalCostKm()*10.0)/10.0);
        out.put("waypoints", r.waypoints());
        out.put("allow_land", land); out.put("allow_water", water);
        ArrayNode path = ToolRegistry.arrayNode();
        for (var p : r.path()) path.add(ToolRegistry.arrayNode()
            .add(Math.round(p.getLatitude()*10000.0)/10000.0)
            .add(Math.round(p.getLongitude()*10000.0)/10000.0));
        out.set("path", path);
        out.put("query_time_ms", r.queryTimeMs());
        return out;
    }
}
