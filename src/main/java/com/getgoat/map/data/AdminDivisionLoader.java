package com.getgoat.map.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getgoat.map.model.AdminDivision;
import com.getgoat.map.model.GeoBounds;
import com.getgoat.map.model.GeoPoint;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Loads and indexes administrative divisions and cities from Natural Earth GeoJSON.
 *
 * Supports:
 *   - Point → province lookup (which province contains this coordinate?)
 *   - Province → terrain cell list (which cells are in this province?)
 *   - Nearby cities query
 *   - GeoJSON export for rendering
 */
public class AdminDivisionLoader {

    private static final Logger LOG = Logger.getLogger(AdminDivisionLoader.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    private final List<AdminDivision> provinces = new ArrayList<>();
    private final List<AdminDivision> cities = new ArrayList<>();
    private final STRtree provinceIndex = new STRtree();
    private boolean loaded;

    /**
     * Load provinces from a GeoJSON FeatureCollection file.
     */
    public void loadProvinces(String path) throws IOException {
        LOG.info("Loading provinces from " + path);
        loadFeatures(new FileInputStream(path), AdminDivision.Level.PROVINCE, provinces);
        for (var p : provinces) {
            provinceIndex.insert(p.getBoundary().getEnvelopeInternal(), p);
        }
        provinceIndex.build();
        LOG.info("Loaded " + provinces.size() + " provinces");
    }

    /**
     * Load cities from a GeoJSON FeatureCollection file.
     */
    public void loadCities(String path) throws IOException {
        LOG.info("Loading cities from " + path);
        loadFeatures(new FileInputStream(path), AdminDivision.Level.CITY, cities);
        LOG.info("Loaded " + cities.size() + " cities");
    }

    private void loadFeatures(InputStream is, AdminDivision.Level level, List<AdminDivision> out) throws IOException {
        JsonNode root = MAPPER.readTree(is);
        JsonNode features = root.get("features");
        if (features == null) return;

        for (JsonNode feat : features) {
            JsonNode geomNode = feat.get("geometry");
            JsonNode propNode = feat.get("properties");
            if (geomNode == null) continue;

            Geometry geom = parseGeometry(geomNode);
            if (geom == null) continue;

            Map<String, Object> props = new LinkedHashMap<>();
            if (propNode != null) {
                var it = propNode.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    JsonNode v = entry.getValue();
                    if (v.isTextual()) props.put(entry.getKey(), v.asText());
                    else if (v.isInt()) props.put(entry.getKey(), v.asInt());
                    else if (v.isLong()) props.put(entry.getKey(), v.asLong());
                    else if (v.isDouble()) props.put(entry.getKey(), v.asDouble());
                    else if (v.isBoolean()) props.put(entry.getKey(), v.asBoolean());
                }
            }

            String name = (String) props.getOrDefault("name",
                (String) props.getOrDefault("NAME", "unnamed"));
            String id = level.name().charAt(0) + "_" + name.toLowerCase().replaceAll("[^a-z0-9]", "_");

            out.add(new AdminDivision(id, name, level, geom, props));
        }
    }

    private Geometry parseGeometry(JsonNode node) {
        return new GeoJsonLoader().parseRawGeometry(node);
    }

    public boolean isLoaded() { return !provinces.isEmpty(); }
    public List<AdminDivision> getProvinces() { return provinces; }
    public List<AdminDivision> getCities() { return cities; }

    /** Find the province containing a point. */
    @SuppressWarnings("unchecked")
    public AdminDivision findProvinceAt(double lat, double lng) {
        Point pt = GF.createPoint(new Coordinate(lng, lat));
        Envelope env = pt.getEnvelopeInternal();

        for (Object cand : provinceIndex.query(env)) {
            AdminDivision p = (AdminDivision) cand;
            if (p.getBoundary().contains(pt)) return p;
        }
        return null;
    }

    /** Find cities within a radius. */
    public List<AdminDivision> findCitiesInRadius(double lat, double lng, double radiusKm) {
        double deg = radiusKm / 111.32;
        List<AdminDivision> result = new ArrayList<>();
        Point pt = GF.createPoint(new Coordinate(lng, lat));
        for (var city : cities) {
            double d = pt.distance(city.getBoundary()) * 111.32;
            if (d <= radiusKm) result.add(city);
        }
        return result;
    }

    /** Export province boundary as GeoJSON Feature. */
    public String exportProvinceGeoJson(AdminDivision province) {
        return String.format(
            "{\"type\":\"Feature\",\"geometry\":%s,\"properties\":{"
            + "\"id\":\"%s\",\"name\":\"%s\",\"level\":\"%s\",\"country\":\"%s\",\"population\":%d}}",
            writeGeometry(province.getBoundary()),
            esc(province.getId()), esc(province.getName()),
            province.getLevel().name(), esc(province.getCountry()),
            province.getPopulation());
    }

    private String writeGeometry(Geometry g) {
        // Simple JTS→GeoJSON for admin data (Polygon/MultiPolygon only)
        return switch (g.getGeometryType()) {
            case "Polygon" -> writePolygon((Polygon) g);
            case "MultiPolygon" -> writeMultiPolygon((MultiPolygon) g);
            case "Point" -> writePoint((Point) g);
            default -> "null";
        };
    }

    private String writePoint(Point p) {
        return String.format(Locale.US, "{\"type\":\"Point\",\"coordinates\":[%.6f,%.6f]}",
            p.getX(), p.getY());
    }

    private String writePolygon(Polygon p) {
        StringBuilder sb = new StringBuilder("{\"type\":\"Polygon\",\"coordinates\":[");
        sb.append(writeRing(p.getExteriorRing().getCoordinates()));
        for (int i = 0; i < p.getNumInteriorRing(); i++) {
            sb.append(",").append(writeRing(p.getInteriorRingN(i).getCoordinates()));
        }
        sb.append("]}");
        return sb.toString();
    }

    private String writeMultiPolygon(MultiPolygon mp) {
        StringBuilder sb = new StringBuilder("{\"type\":\"MultiPolygon\",\"coordinates\":[");
        for (int i = 0; i < mp.getNumGeometries(); i++) {
            if (i > 0) sb.append(",");
            Polygon p = (Polygon) mp.getGeometryN(i);
            sb.append("[").append(writeRing(p.getExteriorRing().getCoordinates()));
            for (int j = 0; j < p.getNumInteriorRing(); j++) {
                sb.append(",").append(writeRing(p.getInteriorRingN(j).getCoordinates()));
            }
            sb.append("]");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String writeRing(Coordinate[] coords) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US, "[%.6f,%.6f]", coords[i].x, coords[i].y));
        }
        sb.append("]");
        return sb.toString();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }
}
