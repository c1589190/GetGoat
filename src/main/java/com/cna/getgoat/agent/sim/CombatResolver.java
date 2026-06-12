package com.cna.getgoat.agent.sim;

import com.cna.getgoat.map.MapManager;
import com.cna.getgoat.map.terrain.TerrainCell;

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

    public static final double BASE_LOSS_RATE = 0.15;   // 15% base loss per round
    public static final double MAX_LOSS_RATE = 0.80;    // cap loss at 80%
    public static final int MIN_CASUALTIES = 0;         // always record, even if 0

    private final MapManager mapManager;

    public CombatResolver(MapManager mapManager) {
        this.mapManager = mapManager;
    }

    /**
     * Resolve a single engagement between attacker and defender.
     * Uses actual troop strengths, terrain bonuses, and always produces a record.
     *
     * @param attackerStrength  attacker's troop count (men)
     * @param defenderStrength  defender's troop count (men)
     * @param attackerType      attacker unit type (for power multiplier)
     * @param defenderType      defender unit type (for power multiplier)
     */
    public CombatOutcomePair resolve(String attackerCode, int attackerStrength, String attackerType,
                                      String defenderCode, int defenderStrength, String defenderType,
                                      TerrainCell terrainAt) {

        double attackerTerrainBonus = terrainBonus(attackerCode, terrainAt, true);
        double defenderTerrainBonus = terrainBonus(defenderCode, terrainAt, false);

        double attackerPower = attackerStrength * typePowerMultiplier(attackerType);
        double defenderPower = defenderStrength * typePowerMultiplier(defenderType);

        double adjAttackerPower = attackerPower * attackerTerrainBonus;
        double adjDefenderPower = defenderPower * defenderTerrainBonus;

        double attackerLoss = Math.min(MAX_LOSS_RATE,
            adjDefenderPower / Math.max(adjAttackerPower, 1.0) * BASE_LOSS_RATE);
        double defenderLoss = Math.min(MAX_LOSS_RATE,
            adjAttackerPower / Math.max(adjDefenderPower, 1.0) * BASE_LOSS_RATE);

        // Always at least minimal attrition (even stalemates cause some casualties)
        attackerLoss = Math.max(attackerLoss, 0.005);
        defenderLoss = Math.max(defenderLoss, 0.005);

        int attackerRemaining = (int) Math.round(attackerStrength * (1 - attackerLoss));
        int defenderRemaining = (int) Math.round(defenderStrength * (1 - defenderLoss));

        String terrainName = terrainAt != null ? terrainAt.getTerrain().getDisplayName() : "plains";

        // Status: LLM judges retreat/rout; deterministic baseline uses loss rate
        String atkStatus = statusFromLoss(attackerLoss, defenderRemaining <= 0);
        String defStatus = statusFromLoss(defenderLoss, attackerRemaining <= 0);

        CombatOutcomePair pair = new CombatOutcomePair();
        pair.attacker = new SimulationResult.CombatOutcome(
            attackerCode, attackerStrength, attackerRemaining, attackerLoss,
            atkStatus,
            String.format("vs %s at %s (atk=%dmen, def=%dmen, atkLoss=%.0f%%, defLoss=%.0f%%)",
                defenderCode, terrainName,
                attackerStrength, defenderStrength,
                attackerLoss * 100, defenderLoss * 100));

        pair.defender = new SimulationResult.CombatOutcome(
            defenderCode, defenderStrength, defenderRemaining, defenderLoss,
            defStatus,
            String.format("vs %s at %s (atk=%dmen, def=%dmen, atkLoss=%.0f%%, defLoss=%.0f%%)",
                attackerCode, terrainName,
                attackerStrength, defenderStrength,
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
