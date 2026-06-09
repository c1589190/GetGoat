package com.getgoat.map.model;

import java.util.*;

/**
 * A named entity on the map with a position, source, and metadata.
 */
public class Unit {
    private final String code;       // unique identifier
    private String name;
    private String description;      // organization / composition
    private String source;           // origin: "base", "dlc1", "custom", etc.
    private String status;           // current state: "active", "idle", "moving", "engaged", etc.
    private String type;             // "infantry", "naval", "air", "civilian", etc.
    private String color;            // hex color for map marker
    private double lat, lng;
    private long createdAt;
    private String icon;             // custom emoji/icon override (null = use type default)
    private Set<String> visibleTo = new LinkedHashSet<>(); // sources that can see this unit

    public Unit(String code, String name, String source, String type,
                double lat, double lng) {
        this.code = Objects.requireNonNull(code);
        this.name = name != null ? name : code;
        this.source = source != null ? source : "custom";
        this.status = "active";
        this.type = type != null ? type : "generic";
        this.lat = lat;
        this.lng = lng;
        this.description = "";
        this.color = defaultColor(type);
        this.createdAt = System.currentTimeMillis();
        this.visibleTo.add(this.source); // default: own source can see
    }

    private static String defaultColor(String type) {
        return switch (type.toLowerCase()) {
            case "infantry", "army" -> "#e74c3c";
            case "naval", "navy" -> "#3498db";
            case "air", "airforce" -> "#f39c12";
            case "civilian" -> "#2ecc71";
            case "supply", "logistics" -> "#9b59b6";
            default -> "#95a5a6";
        };
    }

    // ---- Getters ----
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSource() { return source; }
    public String getStatus() { return status; }
    public String getType() { return type; }
    public String getColor() { return color; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public long getCreatedAt() { return createdAt; }
    public GeoPoint getPosition() { return new GeoPoint(lat, lng); }
    public Set<String> getVisibleTo() { return visibleTo; }
    public boolean isVisibleTo(String source) { return visibleTo.contains(source); }
    public void addVisibleTo(String src) { if (src != null && !src.isEmpty()) visibleTo.add(src); }
    public void removeVisibleTo(String src) { visibleTo.remove(src); }
    public void setVisibleTo(Set<String> srcs) { this.visibleTo = new LinkedHashSet<>(srcs); }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    // ---- Setters ----
    public void setName(String name) { if (name != null) this.name = name; }
    public void setDescription(String desc) { if (desc != null) this.description = desc; }
    public void setSource(String source) { if (source != null) this.source = source; }
    public void setStatus(String status) { if (status != null) this.status = status; }
    public void setType(String type) { if (type != null) this.type = type; }
    public void setColor(String color) { if (color != null) this.color = color; }
    public void setPosition(double lat, double lng) { this.lat = lat; this.lng = lng; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Unit u && code.equals(u.code);
    }
    @Override
    public int hashCode() { return code.hashCode(); }
    @Override
    public String toString() {
        return "Unit[" + code + " \"" + name + "\" " + source + "/" + type
            + " @ (" + lat + "," + lng + ")]";
    }
}
