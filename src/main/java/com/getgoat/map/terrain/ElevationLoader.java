package com.getgoat.map.terrain;

import com.getgoat.map.model.TerrainCell;
import com.getgoat.map.model.TerrainGrid;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * Loads real elevation data from ETOPO1 global relief model.
 *
 * ETOPO1 format (grid-registered, 1 arc-minute):
 *   - 21601 rows × 10801 columns of int16 (little-endian)
 *   - Row 0 = 90°N (North Pole), Row 21600 = 90°S (South Pole)
 *   - Col 0 = 180°W, Col 10800 = 180°E
 *   - Values: meters above sea level (negative = ocean depth)
 *
 * Falls back to simulated elevation if ETOPO1 data is not available.
 */
public class ElevationLoader {

    private static final Logger LOG = Logger.getLogger(ElevationLoader.class.getName());

    // ETOPO1 grid-registered dimensions
    private static final int ETOPO1_ROWS = 21601;
    private static final int ETOPO1_COLS = 10801;
    private static final double ETOPO1_RES = 1.0 / 60.0; // 1 arc-minute in degrees

    // Cache file (pre-sampled at our grid resolution to avoid re-reading 466MB every startup)
    private static final String CACHE_FILE = "elevation_cache.bin";

    // ETOPO1 data (lazily loaded)
    private short[] etopoData = null;

    /**
     * Load elevation into the given TerrainGrid.
     *
     * Tries (in order):
     *   1. Pre-sampled cache file
     *   2. ETOPO1 raw binary → sample and write cache
     *   3. Simulated elevation (fallback)
     */
    public void loadElevation(TerrainGrid grid, String elevationPath) {
        // Try cache first
        if (loadFromCache(grid)) {
            LOG.info("Loaded elevation from cache");
            return;
        }

        // Try ETOPO1 binary
        Path etopoFile = findEtopoFile(elevationPath);
        if (etopoFile != null && loadFromEtopo(grid, etopoFile)) {
            LOG.info("Loaded elevation from ETOPO1");
            saveToCache(grid);
            return;
        }

        // Fallback: simulated
        LOG.info("ETOPO1 not found — using simulated elevation");
        generateSimulatedElevation(grid);
    }

    // ---- Cache ----

    private Path cachePath() {
        return Paths.get(System.getProperty("user.dir")).resolve(CACHE_FILE);
    }

    private boolean loadFromCache(TerrainGrid grid) {
        Path p = cachePath();
        if (!Files.exists(p)) return false;

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(p.toFile())))) {
            int rows = dis.readInt();
            int cols = dis.readInt();
            double cellSize = dis.readDouble();
            if (rows != grid.getRows() || cols != grid.getCols()) return false;

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    short elev = dis.readShort();
                    grid.getCell(r, c).setElevationMeters(elev);
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void saveToCache(TerrainGrid grid) {
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(cachePath().toFile())))) {
            dos.writeInt(grid.getRows());
            dos.writeInt(grid.getCols());
            dos.writeDouble(grid.getCellSizeDegrees());
            for (int r = 0; r < grid.getRows(); r++) {
                for (int c = 0; c < grid.getCols(); c++) {
                    dos.writeShort((short) grid.getCell(r, c).getElevationMeters());
                }
            }
            LOG.info("Elevation cache saved");
        } catch (IOException e) {
            LOG.warning("Failed to save elevation cache: " + e.getMessage());
        }
    }

    /** Check whether ETOPO1 binary data is actually available on disk. */
    public boolean isEtopoAvailable(String elevationPath) {
        return findEtopoFile(elevationPath) != null;
    }

    // ---- ETOPO1 binary reader ----

    private Path findEtopoFile(String explicitPath) {
        // 1. Explicit path given
        if (explicitPath != null) {
            Path p = Paths.get(explicitPath);
            if (Files.exists(p)) return p;
        }
        // 2. Look in resources/geodata/elevation/
        Path inProject = Paths.get("src/main/resources/geodata/elevation/etopo1_ice_g_i2.bin");
        if (Files.exists(inProject)) return inProject;
        // 3. Look in working directory
        Path inCwd = Paths.get("etopo1_ice_g_i2.bin");
        if (Files.exists(inCwd)) return inCwd;
        // 4. Look for .zip and extract
        Path zipPath = Paths.get("etopo1_ice_g_i2.zip");
        if (Files.exists(zipPath)) {
            LOG.info("ETOPO1 zip found. Extract the .bin file from it first:");
            LOG.info("  unzip etopo1_ice_g_i2.zip -d src/main/resources/geodata/elevation/");
        }
        return null;
    }

    private boolean loadFromEtopo(TerrainGrid grid, Path binFile) {
        try {
            LOG.info("Loading ETOPO1 binary: " + binFile);
            long fileSize = Files.size(binFile);
            int expectedSize = ETOPO1_ROWS * ETOPO1_COLS * 2L > Integer.MAX_VALUE
                ? -1 : ETOPO1_ROWS * ETOPO1_COLS * 2;
            LOG.info("ETOPO1 file size: " + (fileSize / 1024 / 1024) + " MB");

            // ETOPO1 is little-endian int16 — read as raw bytes and assemble
            etopoData = new short[ETOPO1_ROWS * ETOPO1_COLS];
            try (InputStream is = new BufferedInputStream(new FileInputStream(binFile.toFile()), 1 << 20)) {
                byte[] buf = new byte[2];
                for (int i = 0; i < etopoData.length; i++) {
                    is.read(buf);
                    // Little-endian: first byte is low byte
                    etopoData[i] = (short) ((buf[1] << 8) | (buf[0] & 0xff));
                }
            }

            // Sample at our grid resolution
            sampleToGrid(grid);
            etopoData = null; // free memory
            return true;

        } catch (IOException e) {
            LOG.warning("Failed to read ETOPO1: " + e.getMessage());
            return false;
        }
    }

    /**
     * Sample ETOPO1 data points at the grid resolution.
     *
     * ETOPO1: 1 arc-minute cells, 21601×10801 grid-registered points.
     * Our grid: cellSizeDegrees cells.
     *
     * For each of our cells, average the ETOPO1 points that fall within it.
     */
    private void sampleToGrid(TerrainGrid grid) {
        double cellSize = grid.getCellSizeDegrees();
        int step = (int) Math.round(cellSize / ETOPO1_RES); // e.g. 0.5° / (1/60)° = 30

        for (int r = 0; r < grid.getRows(); r++) {
            double centerLat = grid.rowToLat(r);
            // ETOPO1 row: 0 = 90°N, 21600 = 90°S
            int etopoRowCenter = (int) Math.round((90.0 - centerLat) / ETOPO1_RES);
            int etopoRowStart = Math.max(0, etopoRowCenter - step / 2);
            int etopoRowEnd = Math.min(ETOPO1_ROWS - 1, etopoRowCenter + step / 2);

            for (int c = 0; c < grid.getCols(); c++) {
                double centerLng = grid.colToLng(c);
                // ETOPO1 col: 0 = 180°W, 10800 = 180°E
                double adjustedLng = centerLng + 180.0; // shift to 0-360
                int etopoColCenter = (int) Math.round(adjustedLng / ETOPO1_RES);
                int etopoColStart = Math.max(0, etopoColCenter - step / 2);
                int etopoColEnd = Math.min(ETOPO1_COLS - 1, etopoColCenter + step / 2);

                // Average elevation over the ETOPO1 points in this cell
                long sum = 0;
                int count = 0;
                for (int er = etopoRowStart; er <= etopoRowEnd; er++) {
                    for (int ec = etopoColStart; ec <= etopoColEnd; ec++) {
                        sum += etopoData[er * ETOPO1_COLS + ec];
                        count++;
                    }
                }
                double avgElev = (double) sum / count;
                grid.getCell(r, c).setElevationMeters(avgElev);
            }
        }
    }

    // ---- Simulated elevation (fallback) ----

    private void generateSimulatedElevation(TerrainGrid grid) {
        for (int r = 0; r < grid.getRows(); r++) {
            double lat = grid.rowToLat(r);
            for (int c = 0; c < grid.getCols(); c++) {
                double lng = grid.colToLng(c);
                grid.getCell(r, c).setElevationMeters(simulatedElevation(lat, lng));
            }
        }
    }

    // ... (same simulatedElevation, gaussian, ridgeGaussian, smoothstep as before)
    public static double simulatedElevation(double lat, double lng) {
        // Africa
        double continent = 0.45 * gaussian(lat, lng, 2, 25, 28, 22);
        continent += 0.55 * gaussian(lat, lng, 50, 70, 30, 45);    // Eurasia
        continent += 0.40 * gaussian(lat, lng, 48, -100, 18, 30);   // North America
        continent += 0.35 * gaussian(lat, lng, -8, -60, 15, 10);    // South America
        continent += 0.25 * gaussian(lat, lng, -25, 135, 10, 10);   // Australia
        continent += 0.20 * gaussian(lat, lng, 0, 115, 12, 15);     // SE Asia
        continent += 0.20 * gaussian(lat, lng, 25, 45, 8, 8);       // Middle East
        continent += 0.30 * gaussian(lat, lng, 22, 78, 10, 8);      // India
        continent += 0.15 * gaussian(lat, lng, 12, -85, 6, 5);       // Central America
        continent += 0.12 * Math.cos(Math.toRadians(lat)*1.5) * Math.sin(Math.toRadians(lng)*1.2+0.5);
        continent += 0.08 * Math.sin(Math.toRadians(lat)*2.5+1.0) * Math.cos(Math.toRadians(lng)*2.0);
        continent = Math.tanh(continent * 3.0);

        double mountains = 0.0;
        mountains += 5000 * gaussian(lat, lng, 30, 85, 5, 6);
        mountains += 3500 * gaussian(lat, lng, 32, 90, 6, 8);
        mountains += 4000 * ridgeGaussian(lat, lng, -5, -70, 30, 2.5, -12);
        mountains += 3000 * ridgeGaussian(lat, lng, 44, -108, 22, 3, -5);
        mountains += 2500 * gaussian(lat, lng, 46, 8, 2, 2.5);
        mountains += 1500 * gaussian(lat, lng, 42.5, 44, 1.5, 2);
        mountains += 2000 * gaussian(lat, lng, -3, 37, 3, 3);
        mountains += 1200 * gaussian(lat, lng, 31, -6, 2, 3);
        mountains += 1200 * ridgeGaussian(lat, lng, 58, 60, 18, 1.5, 0);
        mountains += 2000 * gaussian(lat, lng, 72, -40, 4, 5);
        mountains += 2800 * smoothstep(lat, -90, -68);

        if (continent < -0.05) {
            return -(Math.abs(continent) * 4000 + Math.random() * 200);
        } else {
            double baseHeight = continent * 650;
            double total = baseHeight + mountains * Math.max(0, continent);
            return Math.max(0, total + Math.random() * 40);
        }
    }

    private static double gaussian(double lat, double lng, double clat, double clng, double slat, double slng) {
        double dLat = (lat - clat) / slat;
        double dLng = (lng - clng) / slng;
        return Math.exp(-0.5 * (dLat * dLat + dLng * dLng));
    }

    private static double ridgeGaussian(double lat, double lng, double rlat, double rlng,
                                         double len, double wid, double tilt) {
        double tr = Math.toRadians(tilt);
        double dLat = lat - rlat, dLng = lng - rlng;
        double along = dLat * Math.cos(tr) + dLng * Math.sin(tr);
        double across = -dLat * Math.sin(tr) + dLng * Math.cos(tr);
        return Math.exp(-0.5 * square(along / (len/2.0))) * Math.exp(-0.5 * square(across / wid));
    }

    private static double smoothstep(double v, double s, double e) {
        double t = Math.max(0, Math.min(1, (v - s) / (e - s)));
        return t * t * (3 - 2 * t);
    }

    private static double square(double x) { return x * x; }
}
