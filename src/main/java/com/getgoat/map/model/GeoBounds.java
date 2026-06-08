package com.getgoat.map.model;

/**
 * A latitude-longitude bounding box.
 * Used for spatial queries — "give me everything within this rectangle."
 *
 * Handles bounding boxes that cross the ±180° antimeridian by tracking
 * whether the box is "wrapped" (westLng > eastLng).
 */
public class GeoBounds {
    private final double southLat;   // minimum latitude
    private final double northLat;   // maximum latitude
    private final double westLng;    // westernmost longitude
    private final double eastLng;    // easternmost longitude
    private final boolean wrapped;   // true if crosses antimeridian

    public GeoBounds(double southLat, double northLat, double westLng, double eastLng) {
        this.southLat = southLat;
        this.northLat = northLat;
        this.wrapped = westLng > eastLng;
        this.westLng = westLng;
        this.eastLng = eastLng;
    }

    /** World-spanning bounds. */
    public static GeoBounds world() {
        return new GeoBounds(-90, 90, -180, 180);
    }

    public double getSouthLat() { return southLat; }
    public double getNorthLat() { return northLat; }
    public double getWestLng() { return westLng; }
    public double getEastLng() { return eastLng; }
    public boolean isWrapped() { return wrapped; }

    /** Latitude span in degrees. */
    public double latSpan() { return northLat - southLat; }

    /** Longitude span in degrees (handles wrapping). */
    public double lngSpan() {
        if (wrapped) {
            return (180.0 - westLng) + (eastLng - (-180.0));
        }
        return eastLng - westLng;
    }

    /** Center point of the bounding box. */
    public GeoPoint center() {
        double centerLat = (southLat + northLat) / 2.0;
        double centerLng;
        if (wrapped) {
            centerLng = westLng + lngSpan() / 2.0;
            if (centerLng > 180.0) centerLng -= 360.0;
        } else {
            centerLng = (westLng + eastLng) / 2.0;
        }
        return new GeoPoint(centerLat, centerLng);
    }

    /**
     * Check if a point is inside this bounding box.
     */
    public boolean contains(GeoPoint point) {
        if (point.getLatitude() < southLat || point.getLatitude() > northLat) {
            return false;
        }
        if (wrapped) {
            // Crosses antimeridian: point is inside if lng >= westLng OR lng <= eastLng
            return point.getLongitude() >= westLng || point.getLongitude() <= eastLng;
        } else {
            return point.getLongitude() >= westLng && point.getLongitude() <= eastLng;
        }
    }

    /**
     * Check if another bounding box intersects this one.
     */
    public boolean intersects(GeoBounds other) {
        // Latitude overlap check
        if (this.southLat > other.northLat || this.northLat < other.southLat) {
            return false;
        }
        // Longitude overlap check (handles wrapping)
        if (this.wrapped || other.wrapped) {
            return true; // conservative for wrapped bounds — refine if needed
        }
        return this.westLng <= other.eastLng && this.eastLng >= other.westLng;
    }

    @Override
    public String toString() {
        return String.format("GeoBounds(lat %.2f–%.2f, lng %.2f–%.2f%s)",
            southLat, northLat, westLng, eastLng, wrapped ? " [wrapped]" : "");
    }
}
