package com.cna.getgoat.map.data;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import java.util.*;
import com.cna.getgoat.map.geometry.GeoPoint;

/**
 * An administrative division — country, state/province, or city.
 * Wraps a GeoJSON feature's geometry and properties.
 */
public class AdminDivision {
    public enum Level { COUNTRY, PROVINCE, CITY }

    private final String id;
    private final String name;
    private final Level level;
    private final Geometry boundary;       // Polygon/MultiPolygon for areas, Point for cities
    private final Map<String, Object> props;

    public AdminDivision(String id, String name, Level level, Geometry boundary,
                         Map<String, Object> props) {
        this.id = id;
        this.name = name;
        this.level = level;
        this.boundary = boundary;
        this.props = new LinkedHashMap<>(props);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Level getLevel() { return level; }
    public Geometry getBoundary() { return boundary; }
    public Map<String, Object> getProps() { return Collections.unmodifiableMap(props); }

    /** Country name if this is a province/city. */
    public String getCountry() {
        return (String) props.getOrDefault("admin", props.getOrDefault("country", ""));
    }

    /** Population if available. */
    public long getPopulation() {
        Object p = props.get("pop_max");
        if (p instanceof Number) return ((Number) p).longValue();
        p = props.get("population");
        if (p instanceof Number) return ((Number) p).longValue();
        return 0;
    }

    /** GeoPoint center — centroid for provinces, coordinates for cities. */
    public GeoPoint getCenter() {
        Point c = boundary.getCentroid();
        return new GeoPoint(c.getY(), c.getX());
    }

    @Override
    public String toString() {
        return String.format("%s[%s '%s' %s pop=%,d]",
            level, id, name, getCountry(), getPopulation());
    }
}
