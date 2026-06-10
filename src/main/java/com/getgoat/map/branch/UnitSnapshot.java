package com.getgoat.map.branch;

import com.getgoat.map.model.Unit;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lightweight snapshot of a Unit's state at a given point in the branch tree.
 */
public class UnitSnapshot {
    private final String code, name, source, type, status, color, description;
    private final double lat, lng;
    private final int strength, maxStrength;

    public UnitSnapshot(Unit u) {
        this.code = u.getCode();
        this.name = u.getName();
        this.source = u.getSource();
        this.type = u.getType();
        this.status = u.getStatus();
        this.color = u.getColor();
        this.lat = u.getLat();
        this.lng = u.getLng();
        this.description = u.getDescription();
        this.strength = u.getStrength();
        this.maxStrength = u.getMaxStrength();
    }

    @JsonCreator
    public UnitSnapshot(
            @JsonProperty("code") String code,
            @JsonProperty("name") String name,
            @JsonProperty("source") String source,
            @JsonProperty("type") String type,
            @JsonProperty("status") String status,
            @JsonProperty("color") String color,
            @JsonProperty("lat") double lat,
            @JsonProperty("lng") double lng,
            @JsonProperty("description") String description,
            @JsonProperty("strength") int strength,
            @JsonProperty("maxStrength") int maxStrength) {
        this.code = code; this.name = name; this.source = source;
        this.type = type; this.status = status != null ? status : "active";
        this.color = color; this.lat = lat; this.lng = lng;
        this.description = description != null ? description : "";
        this.strength = strength > 0 ? strength : 10000;
        this.maxStrength = maxStrength > 0 ? maxStrength : 10000;
    }

    // Getters
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getSource() { return source; }
    public String getType() { return type; }
    public String getStatus() { return status; }
    public String getColor() { return color; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public String getDescription() { return description; }
    public int getStrength() { return strength; }
    public int getMaxStrength() { return maxStrength; }
}
