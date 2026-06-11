package com.getgoat.map.model;

import com.getgoat.map.terrain.TerrainCache;

import java.util.*;

/**
 * A regular lat-lon grid backed by a TerrainCache MappedByteBuffer.
 * TerrainCell objects are created on demand — no large in-memory array.
 *
 * Rows: south to north (row 0 = -90° lat = South Pole).
 * Cols: west to east (col 0 = -180° lng = antimeridian).
 */
public class TerrainGrid {
    private final double cellSizeDegrees;
    private final int rows, cols;
    private final TerrainCache cache;

    public TerrainGrid(double cellSizeDegrees, TerrainCache cache) {
        if (cellSizeDegrees <= 0 || cellSizeDegrees > 180) {
            throw new IllegalArgumentException("cellSizeDegrees must be in (0, 180], got: " + cellSizeDegrees);
        }
        if (180.0 % cellSizeDegrees != 0 || 360.0 % cellSizeDegrees != 0) {
            throw new IllegalArgumentException(
                "cellSizeDegrees must evenly divide 180 and 360, got: " + cellSizeDegrees);
        }
        this.cellSizeDegrees = cellSizeDegrees;
        this.rows = (int) (180.0 / cellSizeDegrees);
        this.cols = (int) (360.0 / cellSizeDegrees);
        this.cache = cache;
    }

    public double getCellSizeDegrees() { return cellSizeDegrees; }
    public int getRows() { return rows; }
    public int getCols() { return cols; }
    public double approxCellWidthKm() { return cellSizeDegrees * 111.32; }
    public int totalCells() { return rows * cols; }
    public TerrainCache getCache() { return cache; }

    // ---- Cell access (on-demand from cache) ----

    public TerrainCell getCell(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return null;
        TerrainType t = cache.getTerrain(row, col);
        double elev = cache.getElevation(row, col);
        double lat = rowToLat(row);
        double lng = colToLng(col);
        TerrainCell cell = new TerrainCell(row, col, new GeoPoint(lat, lng));
        if (t != null) {
            cell.setTerrain(t);
            cell.setElevationMeters(elev);
        }
        // if t is null → cell stays at defaults (OCEAN, elevation 0)
        return cell;
    }

    public TerrainCell getCellAt(double lat, double lng) {
        int row = latToRow(lat);
        int col = lngToCol(lng);
        return getCell(row, col);
    }

    public TerrainCell getCellAt(GeoPoint point) {
        return getCellAt(point.getLatitude(), point.getLongitude());
    }

    // ---- Row/col conversion ----

    public int latToRow(double lat) { return (int) ((lat + 90.0) / cellSizeDegrees); }
    public int lngToCol(double lng) { return (int) ((lng + 180.0) / cellSizeDegrees); }
    public double rowToLat(int row) { return -90.0 + (row + 0.5) * cellSizeDegrees; }
    public double colToLng(int col) { return -180.0 + (col + 0.5) * cellSizeDegrees; }

    public GeoBounds cellBounds(int row, int col) {
        double south = -90.0 + row * cellSizeDegrees;
        double north = south + cellSizeDegrees;
        double west = -180.0 + col * cellSizeDegrees;
        double east = west + cellSizeDegrees;
        return new GeoBounds(south, north, west, east);
    }

    /**
     * Snap arbitrary lat/lng bounds to the nearest cell-aligned edges.
     * Every pixel/character in the rendered output will correspond to exactly
     * one geographic cell — no partial cells.
     *
     * @return normalized bounds snapped to grid cell boundaries
     */
    public GeoBounds snapBounds(GeoBounds in) {
        int rowSouth = latToRow(in.getSouthLat());
        int rowNorth = latToRow(in.getNorthLat());
        int colWest = lngToCol(in.getWestLng());
        int colEast = lngToCol(in.getEastLng());

        double snappedSouth = -90.0 + Math.min(rowSouth, rowNorth) * cellSizeDegrees;
        double snappedNorth = -90.0 + (Math.max(rowSouth, rowNorth) + 1) * cellSizeDegrees;
        double snappedWest = -180.0 + Math.min(colWest, colEast) * cellSizeDegrees;
        double snappedEast = -180.0 + (Math.max(colWest, colEast) + 1) * cellSizeDegrees;

        // Clamp to globe
        snappedSouth = Math.max(-90.0, snappedSouth);
        snappedNorth = Math.min(90.0, snappedNorth);
        snappedWest = Math.max(-180.0, snappedWest);
        snappedEast = Math.min(180.0, snappedEast);

        return new GeoBounds(snappedSouth, snappedNorth, snappedWest, snappedEast);
    }

    // ---- Queries ----

    /**
     * All cells that fall within the given bounds (row/col grid only).
     */
    public List<TerrainCell> getCellsInBounds(GeoBounds bounds) {
        List<TerrainCell> result = new ArrayList<>();
        int startRow = Math.max(0, latToRow(bounds.getSouthLat()));
        int endRow = Math.min(rows - 1, latToRow(bounds.getNorthLat()));
        int startCol = Math.max(0, lngToCol(bounds.getWestLng()));
        int endCol = Math.min(cols - 1, lngToCol(bounds.getEastLng()));
        for (int r = startRow; r <= endRow; r++) {
            for (int c = startCol; c <= endCol; c++) {
                int col = ((c % cols) + cols) % cols;
                TerrainCell cell = getCell(r, col);
                if (cell != null) result.add(cell);
            }
        }
        return result;
    }

    public List<TerrainCell> getCellsByType(TerrainType type) {
        List<TerrainCell> result = new ArrayList<>();
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                if (cache.getTerrain(r, c) == type)
                    result.add(getCell(r, c));
        return result;
    }

    public Map<TerrainType, Integer> countByType() {
        Map<TerrainType, Integer> counts = new EnumMap<>(TerrainType.class);
        cache.countByType(counts);
        return counts;
    }

    // ---- Modifiers (delegate to cache) ----

    public void setTerrain(int row, int col, TerrainType type) {
        double elev = cache.getElevation(row, col);
        cache.putCell(row, col, type, elev);
    }

    public void setElevation(int row, int col, double meters) {
        short ord = cache.getTerrainOrd(row, col);
        TerrainType t = (ord != Short.MIN_VALUE && ord >= 0 && ord < TerrainType.values().length)
            ? TerrainType.values()[ord] : TerrainType.OCEAN;
        cache.putCell(row, col, t, meters);
    }

    /**
     * Write terrain type directly to the cache.
     * @deprecated Prefer {@link com.getgoat.map.terrain.TerrainOverrideStore#put}
     *             for manual edits — it survives cache regeneration.
     *             This method is reserved for TerrainGenerator during initial classification.
     */
    @Deprecated
    public void setTerrainAt(double lat, double lng, TerrainType type) {
        int r = latToRow(lat), c = lngToCol(lng);
        if (r >= 0 && r < rows && c >= 0 && c < cols) setTerrain(r, c, type);
    }

    /**
     * Write elevation directly to the cache.
     * @deprecated Prefer {@link com.getgoat.map.terrain.TerrainOverrideStore#put}
     *             for manual edits — it survives cache regeneration.
     *             This method is reserved for TerrainGenerator during initial classification.
     */
    @Deprecated
    public void setElevationAt(double lat, double lng, double meters) {
        int r = latToRow(lat), c = lngToCol(lng);
        if (r >= 0 && r < rows && c >= 0 && c < cols) setElevation(r, c, meters);
    }

    @Override
    public String toString() {
        return String.format("TerrainGrid(res=%.4f°, %d×%d = %d cells)",
            cellSizeDegrees, rows, cols, totalCells());
    }
}
