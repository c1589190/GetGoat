package com.cna.getgoat.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.map.MapManager;
import com.cna.getgoat.map.terrain.TerrainCell;
import com.cna.getgoat.map.MapManager.ReliefClass;

public class GetTerrainTool implements ToolUnit {
    private final MapManager mm;
    public GetTerrainTool(MapManager mm) { this.mm = mm; }

    @Override public String getName() { return "get_terrain"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Get terrain type, elevation, and relief class at a latitude/longitude point.");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("lat", ToolRegistry.numberParam("Latitude (-90 to 90)"));
        props.set("lng", ToolRegistry.numberParam("Longitude (-180 to 180)"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object");
        params.set("properties", props);
        params.set("required", ToolRegistry.arrayNode().add("lat").add("lng"));
        def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        double lat = ToolRegistry.d(args, "lat", Double.NaN);
        double lng = ToolRegistry.d(args, "lng", Double.NaN);
        TerrainCell cell = mm.getTerrainAt(lat, lng);
        if (cell == null) { return ToolRegistry.objectNode().put("error", "no data"); }
        int row = mm.getTerrainGrid().latToRow(lat);
        int col = mm.getTerrainGrid().lngToCol(lng);
        ReliefClass relief = mm.computeReliefClass(row, col);
        ObjectNode r = ToolRegistry.objectNode();
        r.put("lat", lat); r.put("lng", lng);
        r.put("terrain", cell.getTerrain().getDisplayName());
        r.put("elevation_m", (int) cell.getElevationMeters());
        if (relief != null) r.put("relief", relief.name().substring(0,1)+relief.name().substring(1).toLowerCase());
        r.put("color", cell.getColorHex());
        return r;
    }
}
