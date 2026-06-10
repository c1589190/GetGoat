package com.getgoat.agent.sim;

import com.getgoat.map.manager.MapManager;
import com.getgoat.map.geometry.SphericalEngine;
import com.getgoat.map.model.TerrainCell;

import java.util.*;

/**
 * Computes where each unit ends up after one round of movement.
 *
 * Simple constant-speed model with terrain slowdown:
 *   - Roads: 1.5× speed multiplier
 *   - Plains/farmland: 1.0×
 *   - Hills/forest: 0.7×
 *   - Mountain: 0.4×
 *   - River crossing: 0.5×
 */
public class MovementResolver {

    // Base speed (km per round — one round = ~6 hours of combat time)
    public static final double ROUND_HOURS = 6.0;

    private final MapManager mapManager;

    public MovementResolver(MapManager mapManager) {
        this.mapManager = mapManager;
    }

    /**
     * Resolve movement for a unit from (fromLat,fromLng) to (toLat,toLng).
     *
     * @param code     unit identifier
     * @param fromLat  current latitude
     * @param fromLng  current longitude
     * @param toLat    target latitude
     * @param toLng    target longitude
     * @param speedKmh base speed in km/h for this unit type
     * @return MovementOutcome with actual position after the round
     */
    public SimulationResult.MovementOutcome resolve(String code,
            double fromLat, double fromLng, double toLat, double toLng, double speedKmh) {

        double totalDistKm = SphericalEngine.haversineDistance(fromLat, fromLng, toLat, toLng);
        SimulationResult.MovementOutcome outcome = new SimulationResult.MovementOutcome(
            code, fromLat, fromLng, toLat, toLng);

        if (totalDistKm < 0.01) {
            outcome.reached = true;
            outcome.progressPct = 100;
            return outcome;
        }

        // Check terrain along the path (sample midpoint + destination)
        double effectiveSpeed = speedKmh;
        TerrainCell midCell = mapManager.getTerrainAt(
            (fromLat + toLat) / 2, (fromLng + toLng) / 2);
        TerrainCell destCell = mapManager.getTerrainAt(toLat, toLng);

        double terrainMod = terrainSpeedModifier(midCell);
        double destMod = terrainSpeedModifier(destCell);
        // Use the worst terrain modifier along the path
        effectiveSpeed *= Math.min(terrainMod, destMod);

        double maxDistKm = effectiveSpeed * ROUND_HOURS;

        if (maxDistKm >= totalDistKm) {
            // Reached destination
            outcome.reached = true;
            outcome.progressPct = 100;
            outcome.terrainAtDest = destCell != null ? destCell.getTerrain().getDisplayName() : "unknown";
        } else {
            // Partial progress — interpolate position
            double fraction = maxDistKm / totalDistKm;
            double midLat = fromLat + (toLat - fromLat) * fraction;
            double midLng = fromLng + (toLng - fromLng) * fraction;

            outcome.reached = false;
            outcome.progressPct = fraction * 100;
            outcome.toLat = midLat;  // actual position reached
            outcome.toLng = midLng;
            outcome.terrainAtDest = midCell != null ? midCell.getTerrain().getDisplayName() : "unknown";
        }

        return outcome;
    }

    /** Get terrain speed modifier. Higher = faster. */
    public static double terrainSpeedModifier(TerrainCell cell) {
        if (cell == null) return 1.0;
        return switch (cell.getTerrain()) {
            case PLAINS, URBAN        -> 1.0;
            case COASTAL_WATER        -> 0.9;
            case HILLS                -> 0.7;
            case FOREST, TAIGA        -> 0.65;
            case MOUNTAIN             -> 0.4;
            case HIGH_MOUNTAIN        -> 0.25;
            case PLATEAU              -> 0.75;
            case DESERT               -> 0.8;
            case TUNDRA               -> 0.55;
            case WETLAND              -> 0.3;
            case ICE                  -> 0.2;
            case OCEAN                -> 0.05;
        };
    }

    /** Get base speed for a unit type in km/h. */
    public static double baseSpeedKmh(String unitType) {
        return switch (unitType != null ? unitType.toLowerCase() : "infantry") {
            case "infantry", "generic" -> 5.0;
            case "armor", "mechanized" -> 25.0;
            case "air" -> 300.0;
            case "naval" -> 20.0;
            case "supply" -> 4.0;
            case "civilian" -> 3.0;
            default -> 5.0;
        };
    }
}
