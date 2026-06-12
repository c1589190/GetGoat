package com.cna.getgoat.map.annotation;

import java.util.List;
import com.cna.getgoat.map.geometry.GeoPoint;
import com.cna.getgoat.map.terrain.TerrainCell;

/**
 * Result of a radius query — everything found within a given distance of a point.
 */
public record RadiusQueryResult(
    GeoPoint center,
    double radiusKm,
    TerrainCell centerCell,
    List<TerrainCell> cellsInRadius,
    List<Region> regionsInRadius,
    List<Annotation> annotationsInRadius,
    List<RegionLabel> labelsInRadius,
    long queryTimeMs
) {
    /** Summary of terrain types in the radius. */
    public String terrainSummary() {
        if (centerCell == null) return "unknown";
        return centerCell.getTerrain().getDisplayName()
            + " @ " + Math.round(centerCell.getElevationMeters()) + "m";
    }

    @Override
    public String toString() {
        return String.format(
            "RadiusQuery[%.4f,%.4f r=%.1fkm] terrain=%s cells=%d regions=%d labels=%d (%dms)",
            center.getLatitude(), center.getLongitude(), radiusKm,
            terrainSummary(),
            cellsInRadius.size(), regionsInRadius.size(), labelsInRadius.size(),
            queryTimeMs
        );
    }
}
