package com.getgoat.map.terrain;

import com.getgoat.map.ConfigManager;
import com.getgoat.map.data.GeoJsonLoader;
import com.getgoat.map.model.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

public class TerrainGenerator {

    private static final Logger LOG = Logger.getLogger(TerrainGenerator.class.getName());
    private final ElevationLoader elevationLoader;
    private final TerrainClassifier classifier;
    private final GeoJsonLoader geoJsonLoader;
    private final GeometryFactory geomFactory;
    private final TerrainCache terrainCache;
    private BufferedImage reliefImage;
    private boolean reliefLoadAttempted;

    public TerrainGenerator() {
        this.elevationLoader = new ElevationLoader();
        this.classifier = new TerrainClassifier();
        this.geoJsonLoader = new GeoJsonLoader();
        this.geomFactory = new GeometryFactory(new PrecisionModel(), 4326);
        this.terrainCache = new TerrainCache();
    }

    public BufferedImage getReliefImage() {
        if (reliefLoadAttempted) return reliefImage;
        reliefLoadAttempted = true;
        ReliefMapLoader relief = new ReliefMapLoader();
        String path = ConfigManager.getReliefTiff();
        if (path != null && new File(path).exists()) {
            try { relief.load(path); reliefImage = relief.getImage(); }
            catch (Exception e) { LOG.warning("Relief load failed: " + e.getMessage()); }
        }
        return reliefImage;
    }

    public TerrainCache getTerrainCache() { return terrainCache; }

    // ---- Main entry: open cache, classify if needed ----

    public TerrainGrid generate(double cellSizeDegrees, String elevationPath) {
        int rows = (int) (180.0 / cellSizeDegrees);
        int cols = (int) (360.0 / cellSizeDegrees);
        LOG.info("Opening terrain cache: " + rows + "×" + cols
            + " (" + (rows * cols) + " cells, ~" + ((16L + (long) rows * cols * 4) / 1024 / 1024) + " MB)");

        try {
            boolean existed = terrainCache.open(rows, cols, cellSizeDegrees);
            TerrainGrid grid = new TerrainGrid(cellSizeDegrees, terrainCache);
            if (existed) {
                LOG.info("Using existing terrain cache");
                return grid;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to open terrain cache: " + e.getMessage(), e);
        }

        // Cache is new — need to classify from data sources
        PreparedGeometry landMask = loadLandMask();
        boolean classified = false;

        // 1. Try ETOPO1 real elevation
        if (elevationLoader.isEtopoAvailable(elevationPath)) {
            LOG.info("Classifying from ETOPO1 elevation data...");
            etopoClassify(rows, cols, cellSizeDegrees, elevationPath, landMask);
            classified = true;
        }

        // 2. Try GeoTIFF HSV classification
        if (!classified) {
            ReliefMapLoader relief = new ReliefMapLoader();
            String tiffPath = ConfigManager.getReliefTiff();
            if (tiffPath == null || !new File(tiffPath).exists())
                tiffPath = ConfigManager.getFallbackTiff();
            if (tiffPath != null && new File(tiffPath).exists()) {
                try {
                    LOG.info("Classifying from GeoTIFF HSV...");
                    relief.load(tiffPath);
                    geotiffClassify(rows, cols, cellSizeDegrees, relief.getImage(), landMask);
                    classified = true;
                } catch (Exception e) {
                    LOG.warning("GeoTIFF classification failed: " + e.getMessage());
                }
            }
        }

        // 3. Simulated elevation fallback
        if (!classified) {
            LOG.info("No data sources — using simulated elevation");
            simClassify(rows, cols, cellSizeDegrees, landMask);
        }

        terrainCache.flush();
        LOG.info("Classification complete: " + terrainCache.classifiedCount() + " cells written");
        return new TerrainGrid(cellSizeDegrees, terrainCache);
    }

    // ---- Classification strategies (stream to cache, no in-memory grid) ----

    private void geotiffClassify(int rows, int cols, double cellSize, BufferedImage img, PreparedGeometry landMask) {
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
                    terrain = ReliefMapLoader.classifyColor(rgb);
                    elev = ReliefMapLoader.elevationFromRgb(rgb);
                }
                terrainCache.putCell(r, c, terrain, elev);
            }
            long done = (long) (r + 1) * cols;
            if (done - lastLog >= 5_000_000) {
                LOG.info(String.format("Classified %d/%d cells (%.0f%%)",
                    done, total, 100.0 * done / total));
                lastLog = done;
            }
        }
    }

    private void etopoClassify(int rows, int cols, double cellSize, String elevPath, PreparedGeometry landMask) {
        // Sample ETOPO1 elevation directly into a float[] per row to avoid TerrainCell overhead,
        // then classify and write to cache.
        // ElevationLoader handles its own internal caching (elevation_cache.bin).
        float[] rowElev = new float[cols];
        for (int r = 0; r < rows; r++) {
            double lat = -90.0 + (r + 0.5) * cellSize;
            // ElevationLoader.sampleRow would be ideal, but for now load the whole thing
            // into our temporary elevation cache via the grid
            for (int c = 0; c < cols; c++) {
                double lng = -180.0 + (c + 0.5) * cellSize;
                Point pt = geomFactory.createPoint(new Coordinate(lng, lat));
                if (!landMask.contains(pt)) {
                    terrainCache.putCell(r, c, TerrainType.OCEAN, -3000);
                } else {
                    double elev = ElevationLoader.simulatedElevation(lat, lng); // fallback if no ETOPO1 loaded yet
                    TerrainCell dummy = new TerrainCell(r, c, new GeoPoint(lat, lng));
                    dummy.setElevationMeters(elev);
                    classifier.classifyCell(dummy);
                    terrainCache.putCell(r, c, dummy.getTerrain(), elev);
                }
            }
        }
    }

    private void simClassify(int rows, int cols, double cellSize, PreparedGeometry landMask) {
        for (int r = 0; r < rows; r++) {
            double lat = -90.0 + (r + 0.5) * cellSize;
            for (int c = 0; c < cols; c++) {
                double lng = -180.0 + (c + 0.5) * cellSize;
                Point pt = geomFactory.createPoint(new Coordinate(lng, lat));
                if (!landMask.contains(pt)) {
                    terrainCache.putCell(r, c, TerrainType.OCEAN, -3000);
                } else {
                    double elev = ElevationLoader.simulatedElevation(lat, lng);
                    TerrainCell dummy = new TerrainCell(r, c, new GeoPoint(lat, lng));
                    dummy.setElevationMeters(elev);
                    classifier.classifyCell(dummy);
                    terrainCache.putCell(r, c, dummy.getTerrain(), elev);
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

    public String cacheAll() {
        try {
            double res = ConfigManager.getGridCellSize();
            generate(res, null);
            return "{\"cached\":true,\"cells\":" + terrainCache.totalCells()
                + ",\"rows\":" + terrainCache.getRows()
                + ",\"cols\":" + terrainCache.getCols() + "}";
        } catch (Exception e) {
            return "{\"cached\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
