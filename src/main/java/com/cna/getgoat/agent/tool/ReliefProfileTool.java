package com.cna.getgoat.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.map.MapManager;
import java.util.Map;

public class ReliefProfileTool implements ToolUnit {
    private final MapManager mm;
    public ReliefProfileTool(MapManager mm) { this.mm = mm; }

    @Override public String getName() { return "get_relief_profile"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Get relief class and elevation band distribution within a radius.");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("lat", ToolRegistry.numberParam("Center latitude"));
        props.set("lng", ToolRegistry.numberParam("Center longitude"));
        props.set("radius_km", ToolRegistry.numberParam("Radius in km (default 100)"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object"); params.set("properties", props);
        params.set("required", ToolRegistry.arrayNode().add("lat").add("lng"));
        def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        double lat = ToolRegistry.d(args, "lat", 0), lng = ToolRegistry.d(args, "lng", 0);
        double r = ToolRegistry.d(args, "radius_km", 100);
        Map<String, Double> relief = mm.computeReliefProfile(lat, lng, r);
        Map<String, Double> bands = mm.computeElevationBandProfile(lat, lng, r);
        ObjectNode out = ToolRegistry.objectNode();
        out.put("lat", lat); out.put("lng", lng); out.put("radius_km", r);
        ObjectNode rj = ToolRegistry.objectNode(); relief.forEach(rj::put); out.set("relief_percent", rj);
        ObjectNode bj = ToolRegistry.objectNode(); bands.forEach(bj::put); out.set("elevation_bands_percent", bj);
        return out;
    }
}
