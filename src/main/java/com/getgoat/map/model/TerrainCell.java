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

    // =========================================================================
    // Compact encoding — for LLM-facing tool output (not for frontend)
    // =========================================================================

    /** 3-letter terrain code used in compact string encoding. */
    public static String terrainCode(TerrainType t) {
        return switch (t) {
            case OCEAN          -> "OCN";
            case COASTAL_WATER  -> "CSW";
            case PLAINS         -> "PLN";
            case HILLS          -> "HIL";
            case MOUNTAIN       -> "MTN";
            case HIGH_MOUNTAIN  -> "HMT";
            case PLATEAU        -> "PLT";
            case DESERT         -> "DES";
            case FOREST         -> "FOR";
            case TAIGA          -> "TAI";
            case TUNDRA         -> "TUN";
            case ICE            -> "ICE";
            case WETLAND        -> "WET";
            case URBAN          -> "URB";
        };
    }

    /** Single-char terrain symbol used in ASCII grid views. */
    public static char terrainChar(TerrainType t) {
        return switch (t) {
            case OCEAN          -> '~';
            case COASTAL_WATER  -> '≈';
            case PLAINS         -> '.';
            case HILLS          -> 'n';
            case MOUNTAIN       -> '▲';
            case HIGH_MOUNTAIN  -> '^';
            case PLATEAU        -> '▢';
            case DESERT         -> ':';
            case FOREST         -> '♣';
            case TAIGA          -> '♠';
            case TUNDRA         -> 'τ';
            case ICE            -> '*';
            case WETLAND        -> '~';
            case URBAN          -> '#';
        };
    }

    /** Elevation band code: a=<0m, b=0-200m, c=200-800m, d=800-2500m, e=>2500m */
    public static char elevationBandCode(double meters) {
        if (meters < 0) return 'a';
        if (meters < 200) return 'b';
        if (meters < 800) return 'c';
        if (meters < 2500) return 'd';
        return 'e';
    }

    /** Passability char: █=blocked, ▓=very slow, ▒=slow, ░=moderate, ■=normal */
    public static char passabilityChar(double penalty) {
        if (penalty <= 0) return '█';
        if (penalty <= 0.2) return '▓';
        if (penalty <= 0.5) return '▒';
        if (penalty <= 0.8) return '░';
        return ' ';
    }

    /**
     * Compact string encoding for a single cell.
     * Format: E:<elev>|T:<code>|R:<relief>:<stddev>|C:<zone>|A:<speed>|O:<override>
     * Example: E:250|T:FOR|R:HIL:45|C:SUB|A:0.8|O:-
     */
    public String toCompactString(String reliefClass, int reliefStddev,
                                   String climateZone, double speedMod, String overrideTag) {
        return String.format("E:%d|T:%s|R:%s:%d|C:%s|A:%.1f|O:%s",
            (int) elevationMeters,
            terrainCode(terrain),
            reliefClass,
            reliefStddev,
            climateZone,
            speedMod,
            overrideTag != null ? overrideTag : "-");
    }

    /**
     * Full legend for interpreting compact strings and ASCII grids.
     * Returns multi-line string suitable for LLM context.
     */
    public static String legend() {
        return """
            Terrain codes (3-letter): OCN=Ocean CSW=CoastalWater PLN=Plains HIL=Hills MTN=Mountain
                       HMT=HighMountain PLT=Plateau DES=Desert FOR=Forest TAI=Taiga TUN=Tundra
                       ICE=Ice WET=Wetland URB=Urban
            Elevation bands: a=<0m b=0-200m c=200-800m d=800-2500m e=>2500m
            ASCII symbols: ~=Ocean ≈=Coastal .=Plains n=Hills ▲=Mountain ^=HighMtn ▢=Plateau
                           :=Desert ♣=Forest ♠=Taiga τ=Tundra *=Ice ~=Wetland #=Urban
            Passability: █=blocked ▓=verySlow ▒=slow ░=moderate ' '=normal
            Compact format: E:<elev>|T:<code3>|R:<relief>:<stddev>|C:<zone>|A:<speedMod>|O:<override>""";
    }
}
