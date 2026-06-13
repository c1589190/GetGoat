package com.cna.getgoat.map.terrain;

import com.cna.getgoat.config.ConfigsManager;
import com.cna.getgoat.map.data.GeoJsonLoader;
import com.cna.getgoat.map.geometry.*;
import com.cna.getgoat.map.terrain.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Generates terrain cache data from raw data sources (ETOPO1, GeoTIFF, simulation).
 *
 * <p>No longer caches a relief image in the Java heap.  The {@link #populate}
 * method writes directly to a {@link TerrainCache} which is backed by a
 * memory-mapped file.
 */
public class TerrainGenerator {

    private static final Logger LOG = Logger.getLogger(TerrainGenerator.class.getName());
    private final ElevationLoader elevationLoader;
    private final TerrainClassifier classifier;
    private final GeoJsonLoader geoJsonLoader;
    private final GeometryFactory geomFactory;

    public TerrainGenerator() {
        this.elevationLoader = new ElevationLoader();
        this.classifier = new TerrainClassifier();
        this.geoJsonLoader = new GeoJsonLoader();
        this.geomFactory = new GeometryFactory(new PrecisionModel(), 4326);
    }

    // ---- Main entry: populate a TerrainCache from data sources ----

    /**
     * Populate an already-open {@link TerrainCache} with terrain classification
     * from available data sources (ETOPO1 → GeoTIFF HSV → simulated).
     *
     * <p>After this method returns the cache is fully classified and flushed to disk.
     */
    @SuppressWarnings("deprecation")
    public void populate(TerrainCache cache, double cellSizeDegrees) {
        int rows = cache.getRows();
        int cols = cache.getCols();
        LOG.info("Populating terrain cache: " + rows + "×" + cols
            + " (" + (rows * cols) + " cells)");

        PreparedGeometry landMask = loadLandMask();
        boolean classified = false;

        // 1. Try ETOPO1 real elevation
        if (elevationLoader.isEtopoAvailable(null)) {
            LOG.info("Classifying from ETOPO1 elevation data...");
            etopoClassify(cache, rows, cols, cellSizeDegrees, landMask);
            classified = true;
        }

        // 2. Try GeoTIFF HSV classification
        if (!classified) {
            ReliefMapLoader relief = new ReliefMapLoader();
            String tiffPath = ConfigsManager.getReliefTiff();
            if (tiffPath == null || !new File(tiffPath).exists())
                tiffPath = ConfigsManager.getFallbackTiff();
            if (tiffPath != null && new File(tiffPath).exists()) {
                try {
                    LOG.info("Classifying from GeoTIFF HSV...");
                    relief.load(tiffPath);
                    geotiffClassify(cache, rows, cols, cellSizeDegrees, relief.getImage(), landMask);
                    classified = true;
                } catch (Exception e) {
                    LOG.warning("GeoTIFF classification failed: " + e.getMessage());
                }
            }
        }

        // 3. Simulated elevation fallback
        if (!classified) {
            LOG.info("No data sources — using simulated elevation");
            simClassify(cache, rows, cols, cellSizeDegrees, landMask);
        }

        cache.flush();
        LOG.info("Classification complete");
    }

    // ---- Classification strategies (stream to cache, no in-memory grid) ----

    private void geotiffClassify(TerrainCache cache, int rows, int cols, double cellSize,
                                  BufferedImage img, PreparedGeometry landMask) {
        int w = img.getWidth(), h = img.getHeight();
        long total = (long) rows * cols;
        long lastLog = 0;
        for (int r = 0; r < rows; r++) {
            double lat = -90.0 + (r + 0.5) * cellSize;
            for (int c = 0; c < cols; c++) {
                double lng = -180.0 + (c + 0.5) * cellSize;
                Point pt = geomFactory.createPoint(new Coordinate(lng, lat));
                TerrainType terrain;
                double elev;
                if (!landMask.contains(pt)) {
                    terrain = TerrainType.OCEAN;
                    elev = -3000;
                } else {
                    int px = (int) ((lng + 180.0) / 360.0 * w);
                    int py = (int) ((90.0 - lat) / 180.0 * h);
                    px = Math.max(0, Math.min(w - 1, px));
                    py = Math.max(0, Math.min(h - 1, py));
                    int rgb = img.getRGB(px, py);
                    terrain = ColorClassifier.classifyColor(rgb);
                    elev = ColorClassifier.elevationFromRgb(rgb);
                }
                cache.putCell(r, c, terrain, elev);
            }
            long done = (long) (r + 1) * cols;
            if (done - lastLog >= 5_000_000) {
                LOG.info(String.format("Classified %d/%d cells (%.0f%%)",
                    done, total, 100.0 * done / total));
                lastLog = done;
            }
        }
    }

    private void etopoClassify(TerrainCache cache, int rows, int cols, double cellSize,
                                PreparedGeometry landMask) {
        for (int r = 0; r < rows; r++) {
            double lat = -90.0 + (r + 0.5) * cellSize;
            for (int c = 0; c < cols; c++) {
                double lng = -180.0 + (c + 0.5) * cellSize;
                Point pt = geomFactory.createPoint(new Coordinate(lng, lat));
                if (!landMask.contains(pt)) {
                    cache.putCell(r, c, TerrainType.OCEAN, -3000);
                } else {
                    double elev = ElevationLoader.simulatedElevation(lat, lng);
                    TerrainCell dummy = new TerrainCell(r, c, new GeoPoint(lat, lng));
                    dummy.setElevationMeters(elev);
                    classifier.classifyCell(dummy);
                    cache.putCell(r, c, dummy.getTerrain(), elev);
                }
            }
        }
    }

    private void simClassify(TerrainCache cache, int rows, int cols, double cellSize,
                              PreparedGeometry landMask) {
        for (int r = 0; r < rows; r++) {
            double lat = -90.0 + (r + 0.5) * cellSize;
            for (int c = 0; c < cols; c++) {
                double lng = -180.0 + (c + 0.5) * cellSize;
                Point pt = geomFactory.createPoint(new Coordinate(lng, lat));
                if (!landMask.contains(pt)) {
                    cache.putCell(r, c, TerrainType.OCEAN, -3000);
                } else {
                    double elev = ElevationLoader.simulatedElevation(lat, lng);
                    TerrainCell dummy = new TerrainCell(r, c, new GeoPoint(lat, lng));
                    dummy.setElevationMeters(elev);
                    classifier.classifyCell(dummy);
                    cache.putCell(r, c, dummy.getTerrain(), elev);
                }
            }
        }
    }

    // ---- Land mask ----

    private PreparedGeometry loadLandMask() {
        try {
            List<Geometry> geoms = geoJsonLoader.loadFromResource("land.geojson");
            if (geoms.isEmpty()) return PreparedGeometryFactory.prepare(
                geomFactory.createPoint(new Coordinate(0, 0)));
            java.util.ArrayList<Polygon> polys = new java.util.ArrayList<>();
            for (Geometry g : geoms) {
                if (g instanceof Polygon) polys.add((Polygon) g);
                else if (g instanceof MultiPolygon)
                    for (int i = 0; i < g.getNumGeometries(); i++)
                        polys.add((Polygon) g.getGeometryN(i));
            }
            LOG.info("Land mask: " + polys.size() + " polygons");
            return PreparedGeometryFactory.prepare(
                geomFactory.createMultiPolygon(polys.toArray(new Polygon[0])));
        } catch (IOException e) {
            LOG.severe("Failed to load land polygons: " + e.getMessage());
            return PreparedGeometryFactory.prepare(geomFactory.createPoint(new Coordinate(0, 0)));
        }
    }

    public PreparedGeometry getLandMask() {
        try { return loadLandMask(); } catch (Exception e) { return null; }
    }

    // ---- Cache management ----

    /**
     * Full cache regeneration — opens or creates a new cache file and populates it.
     * Used by the HTTP {@code /api/map/cache-all} admin endpoint.
     */
    public String cacheAll() {
        try {
            double res = ConfigsManager.getGridCellSize();
            int rows = (int) (180.0 / res);
            int cols = (int) (360.0 / res);
            TerrainCache cache = new TerrainCache();
            cache.open(rows, cols, res);
            populate(cache, res);
            return "{\"cached\":true,\"cells\":" + cache.totalCells()
                + ",\"rows\":" + cache.getRows()
                + ",\"cols\":" + cache.getCols() + "}";
        } catch (Exception e) {
            return "{\"cached\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
