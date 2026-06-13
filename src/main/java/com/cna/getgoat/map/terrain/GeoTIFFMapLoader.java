package com.cna.getgoat.map.terrain;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Terrain loader that reads GeoTIFF pixels on demand using
 * {@link ImageReadParam#setSourceRegion} for regional access.
 *
 * <p>Unlike {@link ReliefMapLoader} which loads the entire 933 MB image
 * into the Java heap, this loader:
 * <ol>
 *   <li>Opens the TIFF <b>metadata only</b> at construction time</li>
 *   <li>Maintains a small LRU-style region cache (~256×256 px)</li>
 *   <li>On cache miss, reads only the needed pixel region via
 *       {@code ImageReader.readTile()} or {@code setSourceRegion()}</li>
 *   <li>Classifies each sampled pixel using {@link ColorClassifier}</li>
 * </ol>
 *
 * <p>This is the <b>fine-resolution loader</b> — used when sub-cell
 * precision is needed (province export, debug pipeline, elevation color
 * sampling).  Normal runtime queries use the primary
 * {@link TerrainCacheLoader} which is orders of magnitude faster.
 *
 * <p>Thread safety: this loader is NOT thread-safe.  Concurrent queries
 * from different threads will thrash the region cache.  Use one instance
 * per thread or guard externally.
 */
public class GeoTIFFMapLoader implements TerrainLoader, Closeable {

    private static final Logger LOG = Logger.getLogger(GeoTIFFMapLoader.class.getName());

    /** Default region cache size in pixels. */
    private static final int REGION_SIZE = 256;

    private final double cellSizeDegrees;
    private final int rows, cols;
    private final ImageReader reader;
    private final ImageInputStream imageStream;
    private final int imgWidth, imgHeight;

    // Region cache
    private Rectangle cachedRegion;   // pixel-space rectangle of last read
    private BufferedImage regionImage; // the cached pixels

    /**
     * Create a GeoTIFF loader for the given file at the given grid resolution.
     *
     * @throws IOException if the file cannot be opened or decoded
     */
    public GeoTIFFMapLoader(String tiffPath, double cellSizeDegrees) throws IOException {
        this.cellSizeDegrees = cellSizeDegrees;
        this.rows = (int) (180.0 / cellSizeDegrees);
        this.cols = (int) (360.0 / cellSizeDegrees);

        File f = new File(tiffPath);
        if (!f.exists()) throw new IOException("TIFF not found: " + tiffPath);

        imageStream = ImageIO.createImageInputStream(f);
        if (imageStream == null) throw new IOException("Cannot create ImageInputStream for " + tiffPath);

        var readers = ImageIO.getImageReaders(imageStream);
        if (!readers.hasNext()) throw new IOException("No ImageReader for " + tiffPath);
        reader = readers.next();
        reader.setInput(imageStream, false, false); // metadata only

        imgWidth = reader.getWidth(0);
        imgHeight = reader.getHeight(0);

        LOG.info("GeoTIFF opened: " + imgWidth + "×" + imgHeight
            + " px, grid " + rows + "×" + cols
            + " @ " + cellSizeDegrees + "° (regional reads, region=" + REGION_SIZE + "px)");
    }

    /**
     * Factory: returns a new loader if the TIFF exists, otherwise {@code null}.
     */
    public static GeoTIFFMapLoader createIfAvailable(String tiffPath, double cellSizeDegrees) {
        if (tiffPath == null || !new File(tiffPath).exists()) return null;
        try {
            return new GeoTIFFMapLoader(tiffPath, cellSizeDegrees);
        } catch (IOException e) {
            LOG.warning("GeoTIFFMapLoader unavailable: " + e.getMessage());
            return null;
        }
    }

    // ---- TerrainLoader ----

    @Override public double getCellSizeDegrees() { return cellSizeDegrees; }
    @Override public int getRows()               { return rows; }
    @Override public int getCols()               { return cols; }
    @Override public boolean isReady()           { return reader != null; }

    @Override
    public TerrainType getTerrain(int row, int col) {
        int px = gridColToPx(col);
        int py = gridRowToPy(row);
        int rgb = samplePixel(px, py);
        return ColorClassifier.classifyColor(rgb);
    }

    @Override
    public double getElevation(int row, int col) {
        int px = gridColToPx(col);
        int py = gridRowToPy(row);
        int rgb = samplePixel(px, py);
        return ColorClassifier.elevationFromRgb(rgb);
    }

    @Override
    public boolean isClassified(int row, int col) {
        return row >= 0 && row < rows && col >= 0 && col < cols;
    }

    // ---- Coordinate mapping (GeoTIFF pixels ↔ grid rows/cols) ----

    /** Grid column → GeoTIFF pixel x (lng → px). */
    private int gridColToPx(int col) {
        double lng = colToLng(col);
        return clampX((int) ((lng + 180.0) / 360.0 * imgWidth));
    }

    /** Grid row → GeoTIFF pixel y (lat → py, north-up image). */
    private int gridRowToPy(int row) {
        double lat = rowToLat(row);
        return clampY((int) ((90.0 - lat) / 180.0 * imgHeight));
    }

    // ---- Regional pixel sampling ----

    /**
     * Read a single pixel, loading the surrounding region into the cache
     * if necessary.
     */
    private int samplePixel(int px, int py) {
        if (regionImage == null || !cachedRegion.contains(px, py)) {
            loadRegion(px, py);
        }
        // Convert to local coordinates within the cached region
        int lx = px - cachedRegion.x;
        int ly = py - cachedRegion.y;
        try {
            return regionImage.getRGB(lx, ly);
        } catch (ArrayIndexOutOfBoundsException e) {
            // Fallback: clamp and retry
            lx = Math.max(0, Math.min(regionImage.getWidth() - 1, lx));
            ly = Math.max(0, Math.min(regionImage.getHeight() - 1, ly));
            return regionImage.getRGB(lx, ly);
        }
    }

    /**
     * Load a pixel region centered on (cxPx, cyPx) into the cache.
     */
    private void loadRegion(int cxPx, int cyPx) {
        int half = REGION_SIZE / 2;
        int x = Math.max(0, cxPx - half);
        int y = Math.max(0, cyPx - half);
        int w = Math.min(REGION_SIZE, imgWidth - x);
        int h = Math.min(REGION_SIZE, imgHeight - y);

        cachedRegion = new Rectangle(x, y, w, h);

        try {
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(cachedRegion);
            // Read at 1:1 resolution within the region
            regionImage = reader.read(0, param);
            if (regionImage == null) {
                // Fallback: read the full image (shouldn't happen for TIFF)
                LOG.warning("Regional read returned null, falling back to full read for region " + cachedRegion);
                reader.setInput(imageStream, false, false);
                BufferedImage full = reader.read(0);
                regionImage = full.getSubimage(x, y, w, h);
            }
        } catch (Exception e) {
            // If regional read fails (e.g. strip-organized TIFF), fall back
            // to reading the full image once
            LOG.warning("Regional read failed (" + e.getMessage()
                + "), reading full image as fallback");
            try {
                reader.reset();
                reader.setInput(imageStream, false, false);
                BufferedImage full = reader.read(0);
                regionImage = full.getSubimage(x, y, w, h);
            } catch (IOException e2) {
                throw new RuntimeException("Cannot read GeoTIFF: " + e2.getMessage(), e2);
            }
        }
    }

    // ---- Clamp helpers ----

    private int clampX(int px) { return Math.max(0, Math.min(imgWidth - 1, px)); }
    private int clampY(int py) { return Math.max(0, Math.min(imgHeight - 1, py)); }

    // ---- Closeable ----

    @Override
    public void close() throws IOException {
        regionImage = null;
        cachedRegion = null;
        if (reader != null) reader.dispose();
        if (imageStream != null) imageStream.close();
    }
}
