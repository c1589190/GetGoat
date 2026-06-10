package com.getgoat.agent.sim;

import com.getgoat.map.manager.MapManager;
import com.getgoat.map.model.TerrainCell;

import java.util.*;

/**
 * Resolves combat between opposing units using a simplified Lanchester model.
 *
 * Algorithm:
 *   power = unitStrength × typeMultiplier × terrainBonus
 *   lossRate = min(MAX_LOSS, enemyPower / friendlyPower × BASE_LOSS)
 *   survivors = strength × (1 - lossRate)
 *
 * Configurable via config.properties or defaults.
 */
public class CombatResolver {

    public static final double BASE_LOSS_RATE = 0.30;   // 30% base loss per round
    public static final double MAX_LOSS_RATE = 0.80;    // cap loss at 80%

    private final MapManager mapManager;

    public CombatResolver(MapManager mapManager) {
        this.mapManager = mapManager;
    }

    /**
     * Resolve a single engagement between attacker and defender.
     *
     * @param attackerCode    attacking unit code
     * @param attackerPower   attacker's combat power (strength × type multiplier)
     * @param defenderCode    defending unit code
     * @param defenderPower   defender's combat power
     * @param terrainAt       terrain where combat occurs
     * @return pair of CombatOutcomes (attacker, defender)
     */
    public CombatOutcomePair resolve(String attackerCode, double attackerPower,
                                      String defenderCode, double defenderPower,
                                      TerrainCell terrainAt) {

        double attackerTerrainBonus = terrainBonus(attackerCode, terrainAt, true);
        double defenderTerrainBonus = terrainBonus(defenderCode, terrainAt, false);

        double adjAttackerPower = attackerPower * attackerTerrainBonus;
        double adjDefenderPower = defenderPower * defenderTerrainBonus;

        double attackerLoss = Math.min(MAX_LOSS_RATE,
            adjDefenderPower / Math.max(adjAttackerPower, 1.0) * BASE_LOSS_RATE);
        double defenderLoss = Math.min(MAX_LOSS_RATE,
            adjAttackerPower / Math.max(adjDefenderPower, 1.0) * BASE_LOSS_RATE);

        double attackerSurvivors = attackerPower * (1 - attackerLoss);
        double defenderSurvivors = defenderPower * (1 - defenderLoss);

        String terrainName = terrainAt != null ? terrainAt.getTerrain().getDisplayName() : "plains";

        CombatOutcomePair pair = new CombatOutcomePair();
        pair.attacker = new SimulationResult.CombatOutcome(
            attackerCode, attackerPower, attackerSurvivors, attackerLoss,
            statusFromLoss(attackerLoss, defenderSurvivors <= 0),
            String.format("vs %s at %s (atkPower=%.0f, defPower=%.0f, atkLoss=%.0f%%, defLoss=%.0f%%)",
                defenderCode, terrainName,
                adjAttackerPower, adjDefenderPower,
                attackerLoss * 100, defenderLoss * 100));

        pair.defender = new SimulationResult.CombatOutcome(
            defenderCode, defenderPower, defenderSurvivors, defenderLoss,
            statusFromLoss(defenderLoss, attackerSurvivors <= 0),
            String.format("vs %s at %s (atkPower=%.0f, defPower=%.0f, atkLoss=%.0f%%, defLoss=%.0f%%)",
                attackerCode, terrainName,
                adjAttackerPower, adjDefenderPower,
                attackerLoss * 100, defenderLoss * 100));

        return pair;
    }

    /** Determine unit status from losses. */
    private String statusFromLoss(double lossRate, boolean enemyDestroyed) {
        if (lossRate >= 0.8) return "destroyed";
        if (lossRate >= 0.5) return "retreating";
        if (enemyDestroyed) return "advancing";
        if (lossRate >= 0.1) return "engaged";
        return "active";
    }

    /** Terrain multiplier for combat. Defender gets high-ground bonus. */
    private double terrainBonus(String unitCode, TerrainCell cell, boolean isAttacker) {
        if (cell == null) return 1.0;
        return switch (cell.getTerrain()) {
            case MOUNTAIN, HIGH_MOUNTAIN -> isAttacker ? 0.6 : 1.8;
            case HILLS                   -> isAttacker ? 0.8 : 1.4;
            case FOREST, TAIGA           -> isAttacker ? 0.7 : 1.3;
            case PLAINS                  -> isAttacker ? 1.1 : 0.9;
            case PLATEAU                 -> isAttacker ? 0.9 : 1.2;
            case DESERT                  -> 0.9;
            case WETLAND                 -> isAttacker ? 0.5 : 0.8;
            case URBAN                   -> isAttacker ? 0.6 : 1.6;
            case TUNDRA, ICE             -> isAttacker ? 0.7 : 0.7;
            default -> 1.0;
        };
    }

    /** Combat power multiplier by unit type. */
    public static double typePowerMultiplier(String unitType) {
        return switch (unitType != null ? unitType.toLowerCase() : "infantry") {
            case "infantry", "generic" -> 1.0;
            case "armor", "mechanized" -> 3.0;
            case "artillery"           -> 2.5;
            case "air"                 -> 5.0;
            case "naval"               -> 4.0;
            case "supply"              -> 0.2;
            case "civilian"            -> 0.1;
            default -> 1.0;
        };
    }

    /** Result of a single engagement. */
    public static class CombatOutcomePair {
        public SimulationResult.CombatOutcome attacker;
        public SimulationResult.CombatOutcome defender;
    }
}
