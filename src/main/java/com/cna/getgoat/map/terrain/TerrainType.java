package com.cna.getgoat.map.terrain;

/**
 * Terrain classification for each cell on the map.
 *
 * Classification logic:
 *   Base (by elevation):
 *     WATER < 0m  → OCEAN or COASTAL_WATER
 *     LAND 0-200m  → PLAINS
 *     LAND 200-800m → HILLS
 *     LAND 800-2500m → MOUNTAIN
 *     LAND >2500m → HIGH_MOUNTAIN
 *
 *   Climate overlay (overrides base if climate data available):
 *     PLAINS in desert zone → DESERT
 *     PLAINS in tropical zone → FOREST
 *     HILLS in boreal zone → TAIGA
 *     Any in arctic zone → TUNDRA or ICE
 */
public enum TerrainType {
    OCEAN("Ocean", "#1a5276"),
    COASTAL_WATER("Coastal Water", "#2980b9"),
    PLAINS("Plains", "#7dce82"),
    HILLS("Hills", "#a2b573"),
    MOUNTAIN("Mountain", "#8b7355"),
    HIGH_MOUNTAIN("High Mountain", "#d5d5d5"),
    PLATEAU("Plateau", "#c9a96e"),
    DESERT("Desert", "#e8c382"),
    FOREST("Forest", "#2d7d3a"),
    TAIGA("Taiga", "#4a6b4a"),
    TUNDRA("Tundra", "#b8c9a8"),
    ICE("Ice", "#e8e8f0"),
    WETLAND("Wetland", "#5d8a6e"),
    URBAN("Urban", "#888888");

    private final String displayName;
    private final String colorHex;

    TerrainType(String displayName, String colorHex) {
        this.displayName = displayName;
        this.colorHex = colorHex;
    }

    public String getDisplayName() { return displayName; }

    /** CSS-compatible hex color for map rendering. */
    public String getColorHex() { return colorHex; }

    public boolean isWater() {
        return this == OCEAN || this == COASTAL_WATER;
    }

    public boolean isLand() {
        return !isWater();
    }

    /**
     * Look up a TerrainType by constant name or display name.
     * e.g. both "URBAN" and "Urban" return URBAN.
     * Returns null if no match found.
     */
    public static TerrainType fromString(String s) {
        if (s == null) return null;
        // Try enum constant name first
        try { return valueOf(s); }
        catch (IllegalArgumentException ignored) {}
        // Fall back to display name match
        for (TerrainType t : values()) {
            if (t.displayName.equalsIgnoreCase(s)) return t;
        }
        return null;
    }

    /** Whether this terrain is passable by land units. */
    public boolean isPassable() {
        return this != OCEAN;
    }

    /** Movement speed multiplier (1.0 = normal, lower = slower). */
    public double movementPenalty() {
        return switch (this) {
            case PLAINS, URBAN -> 1.0;
            case HILLS -> 0.8;
            case FOREST -> 0.7;
            case MOUNTAIN -> 0.4;
            case HIGH_MOUNTAIN -> 0.15;
            case DESERT -> 0.6;
            case WETLAND -> 0.5;
            case TAIGA, TUNDRA -> 0.55;
            case OCEAN, COASTAL_WATER -> 0.0; // land units can't enter
            case PLATEAU -> 0.75;
            case ICE -> 0.2;
        };
    }
}
