package com.getgoat.agent.sim;

import com.getgoat.map.geometry.SphericalEngine;
import com.getgoat.map.manager.MapManager;
import com.getgoat.map.manager.UnitsManager;
import com.getgoat.map.model.Unit;

import java.util.*;

/**
 * Detects when opposing units enter combat range after movement.
 *
 * Combat ranges (km):
 *   infantry vs infantry: 5
 *   infantry vs armor:    8
 *   armor vs armor:      15
 *   artillery vs any:    20
 *   air vs any:          50
 */
public class EngagementDetector {

    private final UnitsManager unitsManager;
    private final MapManager mapManager;

    public EngagementDetector(UnitsManager unitsManager, MapManager mapManager) {
        this.unitsManager = unitsManager;
        this.mapManager = mapManager;
    }

    public EngagementDetector(UnitsManager unitsManager) {
        this(unitsManager, null);
    }

    /**
     * Find all engagements between opposing sides.
     *
     * @param friendlySide the side whose units we're checking
     * @param enemySides   list of opposing side names
     * @return list of Engagement pairs (each pair is friendly vs enemy)
     */
    public List<SimulationResult.Engagement> detect(String friendlySide, List<String> enemySides) {
        List<SimulationResult.Engagement> engagements = new ArrayList<>();

        List<Unit> friendly = new ArrayList<>();
        List<Unit> enemies = new ArrayList<>();

        for (Unit u : unitsManager.listAll()) {
            if (u.getStatus().equals("destroyed")) continue;
            if (friendlySide.equals(u.getSource())) friendly.add(u);
            else if (enemySides.contains(u.getSource())) enemies.add(u);
        }

        for (Unit fu : friendly) {
            double combatRadius = combatRadiusKm(fu.getType());
            double fuLat = fu.getLat(), fuLng = fu.getLng();

            for (Unit eu : enemies) {
                double dist = SphericalEngine.haversineDistance(fuLat, fuLng, eu.getLat(), eu.getLng());
                double euRadius = combatRadiusKm(eu.getType());
                double detectionRange = Math.max(combatRadius, euRadius);

                if (dist <= detectionRange) {
                    // Determine terrain at midpoint
                    String terrain = "unknown";
                    if (mapManager != null) {
                        try {
                            double midLat = (fuLat + eu.getLat()) / 2;
                            double midLng = (fuLng + eu.getLng()) / 2;
                            var cell = mapManager.getTerrainAt(midLat, midLng);
                            terrain = cell != null ? cell.getTerrain().getDisplayName() : "unknown";
                        } catch (Exception ignored) {}
                    }

                    engagements.add(new SimulationResult.Engagement(
                        fu.getCode(), eu.getCode(), dist, terrain));
                }
            }
        }
        return engagements;
    }

    /** Combat radius in km by unit type. */
    public static double combatRadiusKm(String unitType) {
        return switch (unitType != null ? unitType.toLowerCase() : "infantry") {
            case "infantry", "generic" -> 5.0;
            case "armor", "mechanized" -> 15.0;
            case "artillery"            -> 20.0;
            case "air"                  -> 50.0;
            case "naval"                -> 30.0;
            case "supply", "civilian"   -> 2.0;
            default -> 5.0;
        };
    }
}
