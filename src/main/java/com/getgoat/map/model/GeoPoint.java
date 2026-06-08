package com.getgoat.map.model;

import java.util.Objects;

/**
 * Represents a geographic coordinate on the WGS84 ellipsoid.
 * Latitude: -90° (South Pole) to +90° (North Pole)
 * Longitude: -180° (West) to +180° (East)
 */
public class GeoPoint {
    private final double latitude;
    private final double longitude;

    public GeoPoint(double latitude, double longitude) {
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("Latitude must be in [-90, 90], got: " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("Longitude must be in [-180, 180], got: " + longitude);
        }
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }

    /** Latitude in radians */
    public double latRad() { return Math.toRadians(latitude); }

    /** Longitude in radians */
    public double lngRad() { return Math.toRadians(longitude); }

    /**
     * Creates a GeoPoint, normalizing longitude to [-180, 180].
     */
    public static GeoPoint of(double lat, double lng) {
        // Normalize longitude
        lng = ((lng + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return new GeoPoint(lat, lng);
    }

    @Override
    public String toString() {
        return String.format("GeoPoint(%.4f, %.4f)", latitude, longitude);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GeoPoint geoPoint = (GeoPoint) o;
        return Double.compare(geoPoint.latitude, latitude) == 0
            && Double.compare(geoPoint.longitude, longitude) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(latitude, longitude);
    }
}
