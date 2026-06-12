package com.cna.getgoat.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cna.getgoat.map.geometry.SphericalEngine;

public class DistanceTool implements ToolUnit {
    @Override public String getName() { return "get_distance"; }

    @Override public JsonNode getDefinition() {
        ObjectNode def = ToolRegistry.objectNode();
        def.put("name", getName());
        def.put("description", "Calculate great-circle distance (km) and bearing between two points.");
        ObjectNode props = ToolRegistry.objectNode();
        props.set("lat1", ToolRegistry.numberParam("Start latitude"));
        props.set("lng1", ToolRegistry.numberParam("Start longitude"));
        props.set("lat2", ToolRegistry.numberParam("End latitude"));
        props.set("lng2", ToolRegistry.numberParam("End longitude"));
        ObjectNode params = ToolRegistry.objectNode();
        params.put("type", "object"); params.set("properties", props);
        params.set("required", ToolRegistry.arrayNode().add("lat1").add("lng1").add("lat2").add("lng2"));
        def.set("parameters", params);
        return def;
    }

    @Override public JsonNode execute(JsonNode args) {
        double lat1 = args.get("lat1").asDouble(), lng1 = args.get("lng1").asDouble();
        double lat2 = args.get("lat2").asDouble(), lng2 = args.get("lng2").asDouble();
        double dist = SphericalEngine.haversineDistance(lat1, lng1, lat2, lng2);
        double bearing = SphericalEngine.bearing(lat1, lng1, lat2, lng2);
        ObjectNode r = ToolRegistry.objectNode();
        r.put("distance_km", Math.round(dist * 100.0) / 100.0);
        r.put("bearing_deg", Math.round(bearing * 10.0) / 10.0);
        r.set("from", ToolRegistry.objectNode().put("lat", lat1).put("lng", lng1));
        r.set("to", ToolRegistry.objectNode().put("lat", lat2).put("lng", lng2));
        return r;
    }
}
