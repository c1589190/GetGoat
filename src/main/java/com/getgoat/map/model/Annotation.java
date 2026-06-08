package com.getgoat.map.model;

import org.locationtech.jts.geom.Geometry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A generic annotation on the map — can be a point, line, polygon, or text.
 *
 * Annotations are lightweight markers for anything the user or system wants
 * to highlight: battle sites, trade routes, resource deposits, quest markers, etc.
 */
public class Annotation {

    public enum Type {
        POINT,
        LINE,
        POLYGON,
        TEXT
    }

    private final String id;
    private Type type;
    private Geometry geometry;             // JTS geometry (Point, LineString, Polygon)
    private String label;
    private String description;
    private String style;                  // JSON style string: {"color":"#ff0000","weight":3}
    private Map<String, Object> properties;

    public Annotation(String id, Type type, Geometry geometry) {
        this.id = id;
        this.type = type;
        this.geometry = geometry;
        this.label = "";
        this.description = "";
        this.style = "{}";
        this.properties = new LinkedHashMap<>();
    }

    // Convenience: create a point annotation
    public static Annotation point(String id, GeoPoint point, String label) {
        org.locationtech.jts.geom.GeometryFactory gf =
            new org.locationtech.jts.geom.GeometryFactory();
        org.locationtech.jts.geom.Point jtsPoint = gf.createPoint(
            new org.locationtech.jts.geom.Coordinate(point.getLongitude(), point.getLatitude()));
        Annotation ann = new Annotation(id, Type.POINT, jtsPoint);
        ann.setLabel(label);
        return ann;
    }

    // ---- Getters ----
    public String getId() { return id; }
    public Type getType() { return type; }
    public Geometry getGeometry() { return geometry; }
    public String getLabel() { return label; }
    public String getDescription() { return description; }
    public String getStyle() { return style; }
    public Map<String, Object> getProperties() { return properties; }

    // ---- Setters ----
    public void setType(Type type) { this.type = type; }
    public void setGeometry(Geometry geometry) { this.geometry = geometry; }
    public void setLabel(String label) { this.label = label; }
    public void setDescription(String description) { this.description = description; }
    public void setStyle(String style) { this.style = style; }
    public void setProperty(String key, Object value) { properties.put(key, value); }

    @Override
    public String toString() {
        return String.format("Annotation[%s %s '%s']", id, type, label);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Annotation that = (Annotation) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
