package com.cna.getgoat.map.annotation;

import java.util.Objects;
import com.cna.getgoat.map.geometry.GeoPoint;

/**
 * A text label positioned on the map.
 *
 * Labels are always visible (within their zoom range) and can be associated
 * with a Region or stand independently (e.g., city names, mountain names).
 */
public class RegionLabel {
    private final String id;
    private String text;
    private GeoPoint position;
    private String fontFamily;
    private int fontSize;
    private String color;
    private String alignment;        // left | center | right
    private double rotation;         // degrees
    private String associatedRegionId; // optional link to a Region
    private int minZoom;             // hide when zoom < this
    private int maxZoom;             // hide when zoom > this

    public RegionLabel(String id, String text, GeoPoint position) {
        this.id = id;
        this.text = text;
        this.position = position;
        this.fontFamily = "sans-serif";
        this.fontSize = 12;
        this.color = "#333333";
        this.alignment = "center";
        this.rotation = 0;
        this.associatedRegionId = null;
        this.minZoom = 0;
        this.maxZoom = 20;
    }

    // ---- Getters ----
    public String getId() { return id; }
    public String getText() { return text; }
    public GeoPoint getPosition() { return position; }
    public String getFontFamily() { return fontFamily; }
    public int getFontSize() { return fontSize; }
    public String getColor() { return color; }
    public String getAlignment() { return alignment; }
    public double getRotation() { return rotation; }
    public String getAssociatedRegionId() { return associatedRegionId; }
    public int getMinZoom() { return minZoom; }
    public int getMaxZoom() { return maxZoom; }

    // ---- Setters ----
    public void setText(String text) { this.text = text; }
    public void setPosition(GeoPoint position) { this.position = position; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }
    public void setFontSize(int fontSize) { this.fontSize = fontSize; }
    public void setColor(String color) { this.color = color; }
    public void setAlignment(String alignment) { this.alignment = alignment; }
    public void setRotation(double rotation) { this.rotation = rotation; }
    public void setAssociatedRegionId(String id) { this.associatedRegionId = id; }
    public void setMinZoom(int minZoom) { this.minZoom = minZoom; }
    public void setMaxZoom(int maxZoom) { this.maxZoom = maxZoom; }

    @Override
    public String toString() {
        return String.format("RegionLabel['%s' at %s]", text, position);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RegionLabel that = (RegionLabel) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
