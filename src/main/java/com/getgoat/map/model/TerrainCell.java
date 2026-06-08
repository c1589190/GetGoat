package com.getgoat.map.model;

/**
 * A single cell in the terrain grid covering a rectangular patch of the Earth's surface.
 *
 * Each cell is approximately cellSize° × cellSize° in size.
 * At 0.5° resolution, that's roughly 55km × 55km at the equator.
 */
public class TerrainCell {
    private final int row;
    private final int col;
    private final GeoPoint center;
    private double elevationMeters;
    private TerrainType terrain;
    private double temperature;       // annual mean °C (optional, NaN if unknown)
    private double precipitation;     // annual mm (optional, NaN if unknown)
    private boolean isWater;

    public TerrainCell(int row, int col, GeoPoint center) {
        this.row = row;
        this.col = col;
        this.center = center;
        this.elevationMeters = 0;
        this.terrain = TerrainType.OCEAN;
        this.temperature = Double.NaN;
        this.precipitation = Double.NaN;
        this.isWater = true;
    }

    // ---- Getters ----

    public int getRow() { return row; }
    public int getCol() { return col; }
    public GeoPoint getCenter() { return center; }
    public double getElevationMeters() { return elevationMeters; }
    public TerrainType getTerrain() { return terrain; }
    public double getTemperature() { return temperature; }
    public double getPrecipitation() { return precipitation; }
    public boolean isWater() { return isWater; }
    public boolean isLand() { return !isWater; }

    // ---- Setters (package-private — should only be set during generation) ----

    public void setElevationMeters(double elevationMeters) {
        this.elevationMeters = elevationMeters;
    }

    public void setTerrain(TerrainType terrain) {
        this.terrain = terrain;
        this.isWater = terrain.isWater();
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public void setPrecipitation(double precipitation) {
        this.precipitation = precipitation;
    }

    /** Convenience: get the cell's terrain color for rendering. */
    public String getColorHex() {
        return terrain.getColorHex();
    }

    @Override
    public String toString() {
        return String.format("TerrainCell[row=%d col=%d %s elev=%.0fm %s]",
            row, col, center, elevationMeters, terrain.getDisplayName());
    }
}
