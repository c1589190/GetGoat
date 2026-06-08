package com.getgoat.map.data;

import com.getgoat.map.model.*;

import org.locationtech.jts.geom.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exports internal map data as GeoJSON strings.
 *
 * Uses manual JSON string building to avoid GeoTools dependency.
 * GeoJSON is the interchange format for the Leaflet frontend.
 */
public class GeoJsonWriter {

    /** JTS geometry → GeoJSON geometry string. */
    public String writeGeometry(Geometry g) {
        if (g == null) return "null";

        return switch (g.getGeometryType()) {
            case "Point" -> writePoint((Point) g);
            case "LineString" -> writeLineString((LineString) g);
            case "Polygon" -> writePolygon((Polygon) g);
            case "MultiPoint" -> writeMultiPoint((MultiPoint) g);
            case "MultiLineString" -> writeMultiLineString((MultiLineString) g);
            case "MultiPolygon" -> writeMultiPolygon((MultiPolygon) g);
            case "GeometryCollection" -> writeGeometryCollection((GeometryCollection) g);
            default -> "null";
        };
    }

    // ---- Primitives ----

    private String writeCoordinate(Coordinate c) {
        return String.format(Locale.US, "[%.6f,%.6f]", c.x, c.y);
    }

    private String writeCoordinates(Coordinate[] coords) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(writeCoordinate(coords[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    private String writePoint(Point p) {
        return String.format("{\"type\":\"Point\",\"coordinates\":%s}",
            writeCoordinate(p.getCoordinate()));
    }

    private String writeLineString(LineString ls) {
        return String.format("{\"type\":\"LineString\",\"coordinates\":%s}",
            writeCoordinates(ls.getCoordinates()));
    }

    private String writePolygon(Polygon p) {
        StringBuilder sb = new StringBuilder("{\"type\":\"Polygon\",\"coordinates\":[");
        sb.append(writeCoordinates(p.getExteriorRing().getCoordinates()));
        for (int i = 0; i < p.getNumInteriorRing(); i++) {
            sb.append(",");
            sb.append(writeCoordinates(p.getInteriorRingN(i).getCoordinates()));
        }
        sb.append("]}");
        return sb.toString();
    }

    private String writeMultiPoint(MultiPoint mp) {
        StringBuilder sb = new StringBuilder("{\"type\":\"MultiPoint\",\"coordinates\":[");
        for (int i = 0; i < mp.getNumGeometries(); i++) {
            if (i > 0) sb.append(",");
            sb.append(writeCoordinate(((Point) mp.getGeometryN(i)).getCoordinate()));
        }
        sb.append("]}");
        return sb.toString();
    }

    private String writeMultiLineString(MultiLineString mls) {
        StringBuilder sb = new StringBuilder("{\"type\":\"MultiLineString\",\"coordinates\":[");
        for (int i = 0; i < mls.getNumGeometries(); i++) {
            if (i > 0) sb.append(",");
            sb.append(writeCoordinates(((LineString) mls.getGeometryN(i)).getCoordinates()));
        }
        sb.append("]}");
        return sb.toString();
    }

    private String writeMultiPolygon(MultiPolygon mp) {
        StringBuilder sb = new StringBuilder("{\"type\":\"MultiPolygon\",\"coordinates\":[");
        for (int i = 0; i < mp.getNumGeometries(); i++) {
            if (i > 0) sb.append(",");
            sb.append(writePolygonBody((Polygon) mp.getGeometryN(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private String writePolygonBody(Polygon p) {
        StringBuilder sb = new StringBuilder("[");
        sb.append(writeCoordinates(p.getExteriorRing().getCoordinates()));
        for (int i = 0; i < p.getNumInteriorRing(); i++) {
            sb.append(",");
            sb.append(writeCoordinates(p.getInteriorRingN(i).getCoordinates()));
        }
        sb.append("]");
        return sb.toString();
    }

    private String writeGeometryCollection(GeometryCollection gc) {
        StringBuilder sb = new StringBuilder("{\"type\":\"GeometryCollection\",\"geometries\":[");
        for (int i = 0; i < gc.getNumGeometries(); i++) {
            if (i > 0) sb.append(",");
            sb.append(writeGeometry(gc.getGeometryN(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    // ---- Map entities → GeoJSON features ----

    /**
     * Write terrain grid as a GeoJSON FeatureCollection.
     */
    public String writeTerrainGrid(TerrainGrid grid, GeoBounds bounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");

        List<TerrainCell> cells = bounds != null
            ? grid.getCellsInBounds(bounds)
            : grid.getCellsInBounds(GeoBounds.world());

        boolean first = true;
        for (TerrainCell cell : cells) {
            if (!first) sb.append(",");
            first = false;
            sb.append(writeCellFeature(grid, cell));
        }

        sb.append("]}");
        return sb.toString();
    }

    private String writeCellFeature(TerrainGrid grid, TerrainCell cell) {
        double size = grid.getCellSizeDegrees();
        double lat = cell.getCenter().getLatitude();
        double lng = cell.getCenter().getLongitude();
        double half = size / 2.0;
        double south = lat - half, north = lat + half;
        double west = lng - half, east = lng + half;

        return String.format(Locale.US,
            "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[["
                + "[%.4f,%.4f],[%.4f,%.4f],[%.4f,%.4f],[%.4f,%.4f],[%.4f,%.4f]"
                + "]]},\"properties\":{"
                + "\"row\":%d,\"col\":%d,"
                + "\"elevation\":%.0f,"
                + "\"terrain\":\"%s\","
                + "\"color\":\"%s\","
                + "\"isWater\":%b"
                + "}}",
            west, south, east, south, east, north, west, north, west, south,
            cell.getRow(), cell.getCol(),
            cell.getElevationMeters(),
            cell.getTerrain().name(),
            cell.getColorHex(),
            cell.isWater()
        );
    }

    /**
     * Write regions as GeoJSON FeatureCollection.
     */
    public String writeRegions(List<Region> regions) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        for (int i = 0; i < regions.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(writeRegionFeature(regions.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private String writeRegionFeature(Region region) {
        String geomJson = writeGeometry(region.getBoundary());
        return String.format(
            "{\"type\":\"Feature\",\"geometry\":%s,\"properties\":{"
                + "\"id\":\"%s\",\"name\":\"%s\",\"category\":\"%s\","
                + "\"color\":\"%s\",\"opacity\":%.2f"
                + "}}",
            geomJson,
            esc(region.getId()), esc(region.getName()), esc(region.getCategory()),
            region.getColor(), region.getOpacity()
        );
    }

    /**
     * Write annotations as GeoJSON FeatureCollection.
     */
    public String writeAnnotations(List<Annotation> annotations) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        for (int i = 0; i < annotations.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(writeAnnotationFeature(annotations.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private String writeAnnotationFeature(Annotation ann) {
        String geomJson = writeGeometry(ann.getGeometry());
        return String.format(
            "{\"type\":\"Feature\",\"geometry\":%s,\"properties\":{"
                + "\"id\":\"%s\",\"type\":\"%s\",\"label\":\"%s\",\"description\":\"%s\""
                + "}}",
            geomJson,
            esc(ann.getId()), ann.getType().name(), esc(ann.getLabel()), esc(ann.getDescription())
        );
    }

    /**
     * Write labels as a JSON array.
     */
    public String writeLabels(List<RegionLabel> labels) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) sb.append(",");
            RegionLabel l = labels.get(i);
            sb.append(String.format(Locale.US,
                "{\"id\":\"%s\",\"text\":\"%s\",\"lat\":%.4f,\"lng\":%.4f,"
                    + "\"fontSize\":%d,\"color\":\"%s\",\"alignment\":\"%s\","
                    + "\"rotation\":%.1f,\"regionId\":\"%s\"}",
                esc(l.getId()), esc(l.getText()),
                l.getPosition().getLatitude(), l.getPosition().getLongitude(),
                l.getFontSize(), l.getColor(), l.getAlignment(),
                l.getRotation(),
                l.getAssociatedRegionId() != null ? esc(l.getAssociatedRegionId()) : ""
            ));
        }
        sb.append("]");
        return sb.toString();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
