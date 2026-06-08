package com.getgoat.map.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getgoat.map.model.GeoPoint;
import org.locationtech.jts.geom.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads GeoJSON files and converts them to JTS Geometry objects.
 *
 * Uses Jackson for JSON parsing and manual geometry construction
 * (avoids heavy GeoTools dependency). All coordinates are WGS84 (EPSG:4326).
 */
public class GeoJsonLoader {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeometryFactory geomFactory = new GeometryFactory(new PrecisionModel(), 4326);

    /**
     * Load all geometries from a classpath resource.
     */
    public List<Geometry> loadFromResource(String resourcePath) throws IOException {
        InputStream is = getClass().getClassLoader().getResourceAsStream("geodata/" + resourcePath);
        if (is == null) {
            throw new IOException("Resource not found: geodata/" + resourcePath);
        }
        return parseStream(is);
    }

    /**
     * Load all geometries from a file path.
     */
    public List<Geometry> loadFromFile(String filePath) throws IOException {
        return parseStream(new FileInputStream(filePath));
    }

    /**
     * Load all geometries from a File.
     */
    public List<Geometry> loadFromFile(File file) throws IOException {
        return parseStream(new FileInputStream(file));
    }

    private List<Geometry> parseStream(InputStream is) throws IOException {
        JsonNode root = mapper.readTree(is);
        List<Geometry> geometries = new ArrayList<>();

        String type = root.has("type") ? root.get("type").asText() : "";

        switch (type) {
            case "FeatureCollection" -> {
                JsonNode features = root.get("features");
                if (features != null) {
                    for (JsonNode feature : features) {
                        Geometry g = parseFeature(feature);
                        if (g != null) geometries.add(g);
                    }
                }
            }
            case "Feature" -> {
                Geometry g = parseFeature(root);
                if (g != null) geometries.add(g);
            }
            default -> {
                // Raw geometry
                Geometry g = parseGeometry(root);
                if (g != null) geometries.add(g);
            }
        }
        return geometries;
    }

    private Geometry parseFeature(JsonNode featureNode) {
        JsonNode geomNode = featureNode.get("geometry");
        if (geomNode == null) return null;
        return parseGeometry(geomNode);
    }

    /** Public entry point for parsing a raw geometry node from external code. */
    public Geometry parseRawGeometry(JsonNode geomNode) {
        return parseGeometry(geomNode);
    }

    private Geometry parseGeometry(JsonNode geomNode) {
        if (geomNode == null) return null;
        String type = geomNode.has("type") ? geomNode.get("type").asText() : "";

        return switch (type) {
            case "Point" -> parsePoint(geomNode.get("coordinates"));
            case "MultiPoint" -> parseMultiPoint(geomNode.get("coordinates"));
            case "LineString" -> parseLineString(geomNode.get("coordinates"));
            case "MultiLineString" -> parseMultiLineString(geomNode.get("coordinates"));
            case "Polygon" -> parsePolygon(geomNode.get("coordinates"));
            case "MultiPolygon" -> parseMultiPolygon(geomNode.get("coordinates"));
            case "GeometryCollection" -> parseGeometryCollection(geomNode.get("geometries"));
            default -> null;
        };
    }

    private Point parsePoint(JsonNode coords) {
        double x = coords.get(0).asDouble();
        double y = coords.get(1).asDouble();
        return geomFactory.createPoint(new Coordinate(x, y));
    }

    private MultiPoint parseMultiPoint(JsonNode coords) {
        Point[] points = new Point[coords.size()];
        for (int i = 0; i < coords.size(); i++) {
            points[i] = parsePoint(coords.get(i));
        }
        return geomFactory.createMultiPoint(points);
    }

    private LineString parseLineString(JsonNode coords) {
        Coordinate[] cs = parseCoordinates(coords);
        return geomFactory.createLineString(cs);
    }

    private MultiLineString parseMultiLineString(JsonNode coords) {
        LineString[] lines = new LineString[coords.size()];
        for (int i = 0; i < coords.size(); i++) {
            lines[i] = parseLineString(coords.get(i));
        }
        return geomFactory.createMultiLineString(lines);
    }

    private Polygon parsePolygon(JsonNode rings) {
        if (rings.size() == 0) return null;
        LinearRing shell = geomFactory.createLinearRing(parseCoordinates(rings.get(0)));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = geomFactory.createLinearRing(parseCoordinates(rings.get(i)));
        }
        return geomFactory.createPolygon(shell, holes);
    }

    private MultiPolygon parseMultiPolygon(JsonNode polys) {
        Polygon[] polygons = new Polygon[polys.size()];
        for (int i = 0; i < polys.size(); i++) {
            polygons[i] = parsePolygon(polys.get(i));
        }
        return geomFactory.createMultiPolygon(polygons);
    }

    private GeometryCollection parseGeometryCollection(JsonNode geoms) {
        Geometry[] geometries = new Geometry[geoms.size()];
        for (int i = 0; i < geoms.size(); i++) {
            geometries[i] = parseGeometry(geoms.get(i));
        }
        return geomFactory.createGeometryCollection(geometries);
    }

    /** Parse a JSON array of [lng, lat] coordinates into JTS Coordinates. */
    private Coordinate[] parseCoordinates(JsonNode coords) {
        Coordinate[] cs = new Coordinate[coords.size()];
        for (int i = 0; i < coords.size(); i++) {
            JsonNode c = coords.get(i);
            cs[i] = new Coordinate(c.get(0).asDouble(), c.get(1).asDouble());
        }
        return cs;
    }

    /**
     * Load coastlines and return them as a list of LineStrings.
     */
    public List<LineString> loadCoastlines(String path) throws IOException {
        List<Geometry> geoms = loadFromResource(path);
        List<LineString> coastlines = new ArrayList<>();
        for (Geometry g : geoms) {
            extractLineStrings(g, coastlines);
        }
        return coastlines;
    }

    private void extractLineStrings(Geometry g, List<LineString> out) {
        if (g instanceof LineString) {
            out.add((LineString) g);
        } else if (g instanceof MultiLineString) {
            for (int i = 0; i < g.getNumGeometries(); i++) {
                out.add((LineString) g.getGeometryN(i));
            }
        } else if (g instanceof GeometryCollection) {
            for (int i = 0; i < g.getNumGeometries(); i++) {
                extractLineStrings(g.getGeometryN(i), out);
            }
        }
    }

    /**
     * Create a JTS Point from a GeoPoint.
     */
    public Point createPoint(GeoPoint p) {
        return geomFactory.createPoint(new Coordinate(p.getLongitude(), p.getLatitude()));
    }
}
