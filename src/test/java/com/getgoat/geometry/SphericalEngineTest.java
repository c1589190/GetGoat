package com.getgoat.geometry;

import com.getgoat.map.geometry.SphericalEngine;
import com.getgoat.map.model.GeoPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SphericalEngine using known city-pair distances.
 *
 * Reference distances verified against NOAA's latitude/longitude distance calculator
 * (based on WGS84 ellipsoid). Haversine tolerance: ±0.5% (~5km per 1000km).
 */
class SphericalEngineTest {

    // Known city coordinates
    private static final GeoPoint BERLIN = new GeoPoint(52.5200, 13.4050);
    private static final GeoPoint PARIS = new GeoPoint(48.8566, 2.3522);
    private static final GeoPoint MOSCOW = new GeoPoint(55.7558, 37.6173);
    private static final GeoPoint TOKYO = new GeoPoint(35.6762, 139.6503);
    private static final GeoPoint SAN_FRANCISCO = new GeoPoint(37.7749, -122.4194);
    private static final GeoPoint SYDNEY = new GeoPoint(-33.8688, 151.2093);
    private static final GeoPoint LONDON = new GeoPoint(51.5074, -0.1278);
    private static final GeoPoint BEIJING = new GeoPoint(39.9042, 116.4074);
    private static final GeoPoint CAPE_TOWN = new GeoPoint(-33.9249, 18.4241);
    private static final GeoPoint BUENOS_AIRES = new GeoPoint(-34.6037, -58.3816);

    // ---- Haversine distance tests ----

    @Test
    @DisplayName("Berlin → Paris ≈ 878 km")
    void berlinToParis() {
        double d = SphericalEngine.haversineDistance(BERLIN, PARIS);
        assertEquals(878.0, d, 10.0); // ±10km tolerance (~1.1%)
    }

    @Test
    @DisplayName("Berlin → Moscow ≈ 1610 km")
    void berlinToMoscow() {
        double d = SphericalEngine.haversineDistance(BERLIN, MOSCOW);
        assertEquals(1610.0, d, 15.0);
    }

    @Test
    @DisplayName("Tokyo → San Francisco ≈ 8270 km")
    void tokyoToSanFrancisco() {
        double d = SphericalEngine.haversineDistance(TOKYO, SAN_FRANCISCO);
        assertEquals(8270.0, d, 50.0);
    }

    @Test
    @DisplayName("London → Sydney ≈ 16990 km (antipodal-ish)")
    void londonToSydney() {
        double d = SphericalEngine.haversineDistance(LONDON, SYDNEY);
        assertEquals(16990.0, d, 100.0);
    }

    @Test
    @DisplayName("Beijing → Buenos Aires ≈ 19270 km")
    void beijingToBuenosAires() {
        double d = SphericalEngine.haversineDistance(BEIJING, BUENOS_AIRES);
        assertEquals(19270.0, d, 100.0);
    }

    @Test
    @DisplayName("Paris → Paris = 0 km")
    void samePointZeroDistance() {
        double d = SphericalEngine.haversineDistance(PARIS, PARIS);
        assertEquals(0.0, d, 0.001);
    }

    @Test
    @DisplayName("Haversine symmetric: A→B == B→A")
    void haversineSymmetric() {
        double d1 = SphericalEngine.haversineDistance(BERLIN, TOKYO);
        double d2 = SphericalEngine.haversineDistance(TOKYO, BERLIN);
        assertEquals(d1, d2, 0.001);
    }

    // ---- Vincenty distance tests ----

    @Test
    @DisplayName("Vincenty: Berlin → Paris ≈ 878 km (high precision)")
    void vincentyBerlinToParis() {
        double d = SphericalEngine.vincentyDistance(BERLIN, PARIS);
        assertTrue(d > 870 && d < 885, "Expected ~878km, got " + d);
    }

    @Test
    @DisplayName("Vincenty close to Haversine (within 0.5%)")
    void vincentyCloseToHaversine() {
        double hav = SphericalEngine.haversineDistance(BERLIN, MOSCOW);
        double vin = SphericalEngine.vincentyDistance(BERLIN, MOSCOW);
        double pctDiff = Math.abs(hav - vin) / vin * 100.0;
        assertTrue(pctDiff < 0.5, "Difference " + pctDiff + "% exceeds 0.5%");
    }

    // ---- Bearing tests ----

    @Test
    @DisplayName("Berlin → Paris bearing ≈ 247° (WSW)")
    void bearingBerlinToParis() {
        double b = SphericalEngine.bearing(BERLIN, PARIS);
        assertEquals(247.0, b, 5.0);
    }

    @Test
    @DisplayName("Berlin → Moscow bearing ≈ 67° (ENE)")
    void bearingBerlinToMoscow() {
        double b = SphericalEngine.bearing(BERLIN, MOSCOW);
        assertEquals(67.5, b, 3.0);
    }

    @Test
    @DisplayName("North bearing: (0,0) → (10,0) = 0°")
    void bearingNorth() {
        double b = SphericalEngine.bearing(0, 0, 10, 0);
        assertEquals(0.0, b, 1.0);
    }

    @Test
    @DisplayName("East bearing: (0,0) → (0,10) = 90°")
    void bearingEast() {
        double b = SphericalEngine.bearing(0, 0, 0, 10);
        assertEquals(90.0, b, 1.0);
    }

    // ---- Destination tests ----

    @Test
    @DisplayName("Go 100km north from (50,10)")
    void destinationNorth() {
        GeoPoint dest = SphericalEngine.destination(50, 10, 0, 100);
        assertTrue(dest.getLatitude() > 50.8 && dest.getLatitude() < 51.0,
            "Should go ~0.9° north, got lat=" + dest.getLatitude());
        assertEquals(10.0, dest.getLongitude(), 0.5);
    }

    @Test
    @DisplayName("Go 100km east from (50,10)")
    void destinationEast() {
        GeoPoint dest = SphericalEngine.destination(50, 10, 90, 100);
        assertTrue(dest.getLongitude() > 11.0, "Should go ~1.4° east, got lng=" + dest.getLongitude());
        assertEquals(50.0, dest.getLatitude(), 0.5);
    }

    // ---- Great-circle path tests ----

    @Test
    @DisplayName("Path start == a, end == b")
    void greatCirclePathEndpoints() {
        List<GeoPoint> path = SphericalEngine.greatCirclePath(BERLIN, PARIS, 10);
        assertEquals(10, path.size());
        assertEquals(BERLIN.getLatitude(), path.get(0).getLatitude(), 0.01);
        assertEquals(BERLIN.getLongitude(), path.get(0).getLongitude(), 0.01);
        assertEquals(PARIS.getLatitude(), path.get(path.size() - 1).getLatitude(), 0.01);
        assertEquals(PARIS.getLongitude(), path.get(path.size() - 1).getLongitude(), 0.01);
    }

    // ---- Point-in-polygon tests ----

    @Test
    @DisplayName("Point inside simple square polygon")
    void pointInsideSquare() {
        // Square around Berlin
        List<GeoPoint> square = Arrays.asList(
            new GeoPoint(52.0, 13.0),
            new GeoPoint(53.0, 13.0),
            new GeoPoint(53.0, 14.0),
            new GeoPoint(52.0, 14.0)
        );
        assertTrue(SphericalEngine.pointInPolygon(BERLIN, square));
    }

    @Test
    @DisplayName("Point outside simple square polygon")
    void pointOutsideSquare() {
        List<GeoPoint> square = Arrays.asList(
            new GeoPoint(52.0, 13.0),
            new GeoPoint(53.0, 13.0),
            new GeoPoint(53.0, 14.0),
            new GeoPoint(52.0, 14.0)
        );
        assertFalse(SphericalEngine.pointInPolygon(PARIS, square));
    }

    @Test
    @DisplayName("Point-in-polygon negative longitudes")
    void pointInPolygonNegativeLng() {
        // Square in the western hemisphere (around SF)
        List<GeoPoint> square = Arrays.asList(
            new GeoPoint(37.5, -123.0),
            new GeoPoint(38.0, -123.0),
            new GeoPoint(38.0, -122.0),
            new GeoPoint(37.5, -122.0)
        );
        assertTrue(SphericalEngine.pointInPolygon(SAN_FRANCISCO, square));
        assertFalse(SphericalEngine.pointInPolygon(TOKYO, square));
    }

    // ---- Degenerate/edge cases ----

    @Test
    @DisplayName("Very close points (~1 meter)")
    void veryClosePoints() {
        GeoPoint a = new GeoPoint(52.5200, 13.4050);
        GeoPoint b = new GeoPoint(52.5200, 13.40501); // ~0.7m east
        double d = SphericalEngine.haversineDistance(a, b);
        assertTrue(d < 0.1, "Distance should be < 100m, got " + d + "km");
    }

    @Test
    @DisplayName("Antipodal points (~20000 km)")
    void antipodal() {
        GeoPoint a = new GeoPoint(0, 0);
        GeoPoint b = new GeoPoint(0, 180);
        double d = SphericalEngine.haversineDistance(a, b);
        // Half circumference via both routes = 20037.5 km
        assertEquals(20015.0, d, 50.0);
    }

    @Test
    @DisplayName("Cross-antimeridian: Fiji → Tahiti")
    void crossAntimeridian() {
        // Fiji: (-18.1, 178.4), Tahiti: (-17.65, -149.4)  — cross the 180° line
        GeoPoint fiji = new GeoPoint(-18.1, 178.4);
        GeoPoint tahiti = new GeoPoint(-17.65, -149.4);
        double d = SphericalEngine.haversineDistance(fiji, tahiti);
        assertTrue(d > 3000 && d < 4000, "Fiji-Tahiti should be ~3450km, got " + d);
    }
}
