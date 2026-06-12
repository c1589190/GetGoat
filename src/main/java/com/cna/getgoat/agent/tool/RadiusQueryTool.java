package com.cna.getgoat.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.map.MapManager;
import com.cna.getgoat.map.terrain.TerrainType;
import java.util.Map;

public class RadiusQueryTool implements ToolUnit {
    private final MapManager mm;
    public RadiusQueryTool(MapManager mm) { this.mm = mm; }

    @Override public String getName() { return "query_radius"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Query terrain, relief, elevation, roads, and cities within a radius (km) of a point.");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("lat", ToolRegistry.numberParam("Center latitude"));
        props.set("lng", ToolRegistry.numberParam("Center longitude"));
        props.set("radius_km", ToolRegistry.numberParam("Search radius in km (default 100)"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object"); params.set("properties", props);
        params.set("required", ToolRegistry.arrayNode().add("lat").add("lng"));
        def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        double lat = ToolRegistry.d(args, "lat", 0), lng = ToolRegistry.d(args, "lng", 0);
        double r = ToolRegistry.d(args, "radius_km", 100);
        var res = mm.queryRadiusEnhanced(lat, lng, r);
        ObjectNode out = ToolRegistry.objectNode();
        out.put("lat", lat); out.put("lng", lng); out.put("radius_km", r);
        if (res.centerCell() != null) {
            out.put("center_terrain", res.centerCell().getTerrain().getDisplayName());
            out.put("center_elevation_m", (int) res.centerCell().getElevationMeters());
        }
        ObjectNode elev = ToolRegistry.objectNode();
        elev.put("min_m", res.elevationProfile().min());
        elev.put("max_m", res.elevationProfile().max());
        elev.put("mean_m", res.elevationProfile().mean());
        elev.put("range_m", res.elevationProfile().range());
        out.set("elevation", elev);
        ObjectNode terr = ToolRegistry.objectNode();
        Map<TerrainType, Double> tp = res.terrainProfile();
        if (tp != null) tp.forEach((k, v) -> terr.put(k.getDisplayName(), v));
        out.set("terrain_percent", terr);
        out.put("road_nodes", res.roadNodes().size());
        out.put("road_segments", res.roadSegments().size());
        ArrayNode cities = ToolRegistry.arrayNode();
        res.cities().forEach(c -> cities.add(c.getName()));
        out.set("cities", cities);
        out.put("query_time_ms", res.queryTimeMs());
        return out;
    }
}
