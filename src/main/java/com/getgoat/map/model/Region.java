package com.getgoat.map.model;

import org.locationtech.jts.geom.Geometry;

import java.util.*;

/**
 * A named polygonal region on the map.
 *
 * Regions are the primary annotation unit — they can represent:
 *   - Geographic areas ("Sahara Desert", "Alps", "Amazon Basin")
 *   - Cultural/historical regions ("Mesopotamia", "Silk Road Corridor")
 *   - Economic zones ("Ruhr Valley", "Silicon Valley")
 *   - Custom player-defined areas
 *
 * Regions have no political meaning by themselves — they are purely annotations.
 * Later, political entities (countries, provinces) will be built on top of regions.
 */
public class Region {
    private final String id;
    private String name;
    private Geometry boundary;           // JTS Polygon or MultiPolygon
    private String category;             // geographic | cultural | economic | custom
    private String color;                // hex color for rendering
    private double opacity;              // 0.0–1.0
    private Map<String, Object> properties;
    private List<String> tags;
    private long createdAt;
    private long updatedAt;

    public Region(String id, String name, Geometry boundary, String category) {
        this.id = id;
        this.name = name;
        this.boundary = boundary;
        this.category = category;
        this.color = "#3388ff";          // default blue
        this.opacity = 0.3;
        this.properties = new LinkedHashMap<>();
        this.tags = new ArrayList<>();
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // ---- Getters ----

    public String getId() { return id; }
    public String getName() { return name; }
    public Geometry getBoundary() { return boundary; }
    public String getCategory() { return category; }
    public String getColor() { return color; }
    public double getOpacity() { return opacity; }
    public Map<String, Object> getProperties() { return Collections.unmodifiableMap(properties); }
    public List<String> getTags() { return Collections.unmodifiableList(tags); }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

    // ---- Setters ----

    public void setName(String name) { this.name = name; touch(); }
    public void setBoundary(Geometry boundary) { this.boundary = boundary; touch(); }
    public void setCategory(String category) { this.category = category; touch(); }
    public void setColor(String color) { this.color = color; touch(); }
    public void setOpacity(double opacity) { this.opacity = opacity; touch(); }

    public void setProperty(String key, Object value) {
        properties.put(key, value);
        touch();
    }

    public void addTag(String tag) {
        tags.add(tag);
        touch();
    }

    public void removeTag(String tag) {
        tags.remove(tag);
        touch();
    }

    private void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("Region[%s '%s' category=%s tags=%s]",
            id, name, category, tags);
    }
}
