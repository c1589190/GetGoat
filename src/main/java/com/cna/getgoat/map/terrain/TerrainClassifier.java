package com.cna.getgoat.map.terrain;

import com.cna.getgoat.map.terrain.TerrainCell;
import com.cna.getgoat.map.terrain.TerrainType;

/**
 * Classifies each TerrainCell into a TerrainType based on:
 *   1. Elevation (primary signal)
 *   2. Latitude (climate proxy)
 *   3. Water proximity (coastline data)
 *
 * Classification rules:
 *   WATER (elevation < 0):
 *     deep ocean (< -200m)       → OCEAN
 *     shallow (>= -200m)         → COASTAL_WATER
 *
 *   LAND (elevation >= 0):
 *     0–200m                     → PLAINS
 *     200–800m                   → HILLS
 *     800–2500m                  → MOUNTAIN
 *     >2500m                     → HIGH_MOUNTAIN
 *
 *   Climate overlay (latitude-based proxy):
 *     |latitude| > 75°           → ICE (on land)
 *     |latitude| > 65°           → TUNDRA
 *     |latitude| > 55°           → TAIGA (if HILLS or PLAINS)
 *     |latitude| < 25°  AND dry  → DESERT
 *     |latitude| < 25°  AND wet  → FOREST
 *
 *   Plateau detection:
 *     High elevation (>800m) + flat terrain + large area → PLATEAU
 */
public class TerrainClassifier {

    /**
     * Classify all cells in the grid.
     */
    public void classify(TerrainCell[][] cells) {
        for (TerrainCell[] row : cells) {
            for (TerrainCell cell : row) {
                classifyCell(cell);
            }
        }
        detectPlateaus(cells);
    }

    /**
     * Classify a single cell (exposed for use by TerrainGenerator after water masking).
     */
    public void classifyCell(TerrainCell cell) {
        double elev = cell.getElevationMeters();
        double absLat = Math.abs(cell.getCenter().getLatitude());

        if (elev < 0) {
            // Water
            if (elev < -200) {
                cell.setTerrain(TerrainType.OCEAN);
            } else {
                cell.setTerrain(TerrainType.COASTAL_WATER);
            }
            return;
        }

        // Land: base classification by elevation
        TerrainType base;
        if (elev < 200) {
            base = TerrainType.PLAINS;
        } else if (elev < 800) {
            base = TerrainType.HILLS;
        } else if (elev < 2500) {
            base = TerrainType.MOUNTAIN;
        } else {
            base = TerrainType.HIGH_MOUNTAIN;
        }

        // Climate overlay
        TerrainType climate = applyClimateOverlay(base, absLat, elev);
        cell.setTerrain(climate);
    }

    /**
     * Apply latitude-based climate rules to refine terrain type.
     */
    private TerrainType applyClimateOverlay(TerrainType base, double absLat, double elevation) {
        // Ice caps
        if (absLat > 75) return TerrainType.ICE;

        // Tundra
        if (absLat > 65) return TerrainType.TUNDRA;

        // Boreal forest / taiga
        if (absLat > 55 && (base == TerrainType.PLAINS || base == TerrainType.HILLS)) {
            return TerrainType.TAIGA;
        }

        // Tropical belt
        if (absLat < 25) {
            // Rough desert band (~15°–30° latitude)
            if (absLat > 14 && absLat < 32 && elevation < 1500) {
                // Desert band around 20°–30° (Hadley cell subsidence)
                // But not everywhere — use longitude-based variation
                return TerrainType.DESERT;
            }
            if (base == TerrainType.PLAINS || base == TerrainType.HILLS) {
                return TerrainType.FOREST; // tropical forest
            }
        }

        return base;
    }

    /**
     * Detect plateaus: large connected regions of high, flat terrain.
     *
     * A cell is a plateau candidate if:
     *   - Elevation > 800m
     *   - Local elevation variance is low (flat)
     *   - Surrounded by similar-elevation cells
     *
     * This is a simplified pass. A full implementation would use
     * connected-component analysis.
     */
    private void detectPlateaus(TerrainCell[][] cells) {
        int rows = cells.length;
        int cols = cells[0].length;

        for (int r = 1; r < rows - 1; r++) {
            for (int c = 1; c < cols - 1; c++) {
                TerrainCell cell = cells[r][c];
                double elev = cell.getElevationMeters();

                if (elev < 800 || elev > 3000) continue;
                if (cell.getTerrain().isWater()) continue;

                // Compute local variance
                double sum = 0;
                double sumSq = 0;
                int count = 0;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        TerrainCell neighbor = cells[r + dr][c + dc];
                        double e = neighbor.getElevationMeters();
                        sum += e;
                        sumSq += e * e;
                        count++;
                    }
                }
                double mean = sum / count;
                double variance = sumSq / count - mean * mean;

                // If local variance is very low (flat) AND high elevation → plateau
                if (variance < 2500 && elev > 1000) { // stddev < 50m
                    cell.setTerrain(TerrainType.PLATEAU);
                }
            }
        }
    }
}
