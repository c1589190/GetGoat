package com.cna.getgoat.map.terrain;

/**
 * Abstraction for terrain data access — a source of terrain type and
 * elevation at grid-cell resolution.
 *
 * <p>All coordinates use the row/col grid system where:
 * <ul>
 *   <li>row 0 = South Pole (lat=-90°)</li>
 *   <li>col 0 = antimeridian (lng=-180°)</li>
 * </ul>
 *
 * <p>Coordinate conversion methods are provided as {@code default} methods
 * so every implementation shares identical mathematics. This is critical
 * because {@link TerrainOverrideStore} is keyed by row:col.
 *
 * <p>Implementations decide how data is sourced:
 * <ul>
 *   <li>{@link TerrainCacheLoader} — memory-mapped binary cache (runtime)</li>
 *   <li>{@link GeoTIFFMapLoader} — regional GeoTIFF reads (fine-resolution)</li>
 * </ul>
 */
public interface TerrainLoader {

    /** Grid cell size in degrees. */
    double getCellSizeDegrees();

    /** Number of rows (latitudinal cells). */
    int getRows();

    /** Number of columns (longitudinal cells). */
    int getCols();

    /** Total number of cells. */
    default int totalCells() { return getRows() * getCols(); }

    /** Whether the loader is initialized and ready for queries. */
    boolean isReady();

    // ---- Single-cell access (returns primitives, no allocation) ----

    /**
     * Get terrain type at a grid cell, or {@code null} if not classified.
     */
    TerrainType getTerrain(int row, int col);

    /**
     * Get elevation in meters at a grid cell.
     */
    double getElevation(int row, int col);

    /**
     * Whether this cell has been classified (has valid terrain data).
     */
    boolean isClassified(int row, int col);

    // ---- Coordinate conversion (default — shared by all implementations) ----

    default int latToRow(double lat) {
        return (int) ((lat + 90.0) / getCellSizeDegrees());
    }

    default int lngToCol(double lng) {
        return (int) ((lng + 180.0) / getCellSizeDegrees());
    }

    default double rowToLat(int row) {
        return -90.0 + (row + 0.5) * getCellSizeDegrees();
    }

    default double colToLng(int col) {
        return -180.0 + (col + 0.5) * getCellSizeDegrees();
    }
}
