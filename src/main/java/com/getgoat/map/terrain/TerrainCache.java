package com.getgoat.map.terrain;

import com.getgoat.map.model.TerrainType;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

/**
 * Binary terrain cache with MappedByteBuffer random access.
 *
 * File format:
 *   int rows, int cols, double cellSize    (16-byte header)
 *   for each cell row-major:
 *     short terrainOrdinal, short elevation (4 bytes per cell)
 *   Sentinels: terrainOrdinal = Short.MIN_VALUE → not yet classified
 *
 * File is pre-allocated to full size on creation.
 */
public class TerrainCache {

    private static final Logger LOG = Logger.getLogger(TerrainCache.class.getName());
    private static final String CACHE_FILE = "terrain_cache.bin";
    private static final short SENTINEL = Short.MIN_VALUE;

    private final Path cachePath;
    private MappedByteBuffer buffer;
    private int rows, cols;
    private double cellSize;
    private long fileSize;

    public TerrainCache() {
        this.cachePath = Paths.get(System.getProperty("user.dir")).resolve(CACHE_FILE);
    }

    public Path getCachePath() { return cachePath; }
    public boolean isOpen() { return buffer != null; }
    public int getRows() { return rows; }
    public int getCols() { return cols; }
    public double getCellSize() { return cellSize; }
    public int totalCells() { return rows * cols; }

    // ---- Open / create ----

    /**
     * Open existing cache or create a new empty one.
     * @param expectedRows rows for new cache
     * @param expectedCols cols for new cache
     * @param expectedCellSize cell size in degrees
     * @return true if opened existing, false if created new
     */
    public boolean open(int expectedRows, int expectedCols, double expectedCellSize) throws IOException {
        if (Files.exists(cachePath)) {
            return openExisting(expectedRows, expectedCols, expectedCellSize);
        } else {
            createNew(expectedRows, expectedCols, expectedCellSize);
            return false;
        }
    }

    private boolean openExisting(int expectedRows, int expectedCols, double expectedCellSize) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(cachePath.toFile())))) {
            rows = dis.readInt();
            cols = dis.readInt();
            cellSize = dis.readDouble();
        }
        fileSize = Files.size(cachePath);
        long expectedSize = 16L + rows * cols * 4L;
        if (fileSize != expectedSize) {
            LOG.warning("Cache file size mismatch, recreating");
            Files.delete(cachePath);
            createNew(expectedRows, expectedCols, expectedCellSize);
            return false;
        }
        if (rows != expectedRows || cols != expectedCols
            || Math.abs(cellSize - expectedCellSize) > 1e-9) {
            LOG.info("Cache dimensions mismatch — recreating");
            Files.delete(cachePath);
            createNew(expectedRows, expectedCols, expectedCellSize);
            return false;
        }
        // Memory-map the entire file read-write
        try (FileChannel fc = FileChannel.open(cachePath,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            buffer = fc.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);
        }
        int classified = 0;
        for (int r = 0; r < rows && classified < 1000; r++) {
            for (int c = 0; c < cols && classified < 1000; c++) {
                if (getTerrainOrd(r, c) != SENTINEL) classified++;
            }
        }
        LOG.info("Terrain cache opened: " + rows + "×" + cols
            + " (" + totalCells() + " cells, " + (fileSize / 1024 / 1024) + " MB)");
        return true;
    }

    private void createNew(int r, int c, double cs) throws IOException {
        this.rows = r;
        this.cols = c;
        this.cellSize = cs;
        this.fileSize = 16L + (long) rows * cols * 4L;
        LOG.info("Creating terrain cache: " + rows + "×" + cols
            + " (" + totalCells() + " cells, " + (fileSize / 1024 / 1024) + " MB)");

        // Write header + sentinel blocks via FileChannel (fast bulk), then map
        byte[] sentinelBlock = new byte[64 * 1024]; // 64KB blocks
        for (int i = 0; i < sentinelBlock.length; i += 2) {
            sentinelBlock[i] = (byte) (SENTINEL >> 8);      // high byte of short
            sentinelBlock[i + 1] = (byte) (SENTINEL & 0xff); // low byte
        }
        try (FileChannel fc = FileChannel.open(cachePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING)) {
            // Header
            java.nio.ByteBuffer hdr = java.nio.ByteBuffer.allocate(16);
            hdr.putInt(rows).putInt(cols).putDouble(cellSize).flip();
            fc.write(hdr);
            // Sentinel body
            long remaining = fileSize - 16;
            java.nio.ByteBuffer block = java.nio.ByteBuffer.wrap(sentinelBlock);
            while (remaining > 0) {
                int chunk = (int) Math.min(remaining, sentinelBlock.length);
                block.limit(chunk).position(0);
                fc.write(block);
                remaining -= chunk;
            }
            fc.force(false);
        }
        // Now map
        try (FileChannel fc = FileChannel.open(cachePath,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            buffer = fc.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);
        }
        LOG.info("Cache initialized with sentinels (bulk write)");
    }

    /** Close the buffer (optional — GC will clean it up). */
    public void close() {
        if (buffer != null) {
            buffer.force();
            buffer = null;
        }
    }

    // ---- Cell offset ----

    private long offset(int row, int col) {
        return 16L + ((long) row * cols + col) * 4L;
    }

    // ---- Read/write single cells ----

    /** Get terrain ordinal, or SENTINEL if not classified. */
    public short getTerrainOrd(int row, int col) {
        return buffer.getShort((int) offset(row, col));
    }

    public short getElevationShort(int row, int col) {
        return buffer.getShort((int) offset(row, col) + 2);
    }

    public TerrainType getTerrain(int row, int col) {
        short ord = getTerrainOrd(row, col);
        if (ord == SENTINEL || ord < 0 || ord >= TerrainType.values().length)
            return null; // not classified
        return TerrainType.values()[ord];
    }

    public double getElevation(int row, int col) {
        return getElevationShort(row, col);
    }

    public boolean isClassified(int row, int col) {
        return getTerrainOrd(row, col) != SENTINEL;
    }

    /** Write a cell's classification. */
    public void putCell(int row, int col, TerrainType terrain, double elevation) {
        long off = offset(row, col);
        buffer.putShort((int) off, (short) terrain.ordinal());
        buffer.putShort((int) off + 2, (short) elevation);
    }

    /** Count how many cells have been classified. */
    public int classifiedCount() {
        int count = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (isClassified(r, c)) count++;
            }
        }
        return count;
    }

    /** Whether all cells have been classified. */
    public boolean isComplete() {
        return classifiedCount() == totalCells();
    }

    /** Flush to disk. */
    public void flush() {
        if (buffer != null) buffer.force();
    }

    // ---- Bulk status (for stats) ----

    public void countByType(java.util.Map<TerrainType, Integer> counts) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                TerrainType t = getTerrain(r, c);
                if (t != null) counts.merge(t, 1, Integer::sum);
            }
        }
    }

    public void delete() {
        try { Files.deleteIfExists(cachePath); LOG.info("Terrain cache deleted"); }
        catch (IOException e) { LOG.warning("Failed to delete terrain cache: " + e.getMessage()); }
    }

    public boolean exists() { return Files.exists(cachePath); }

    // ---- Row/col conversion ----

    public int latToRow(double lat) { return (int) ((lat + 90.0) / cellSize); }
    public int lngToCol(double lng) { return (int) ((lng + 180.0) / cellSize); }
    public double rowToLat(int row) { return -90.0 + (row + 0.5) * cellSize; }
    public double colToLng(int col) { return -180.0 + (col + 0.5) * cellSize; }
}
