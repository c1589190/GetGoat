package com.getgoat.agent.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the complete result of a simulation round.
 */
public class SimulationResult {

    public static class MovementOutcome {
        public String unitCode;
        public double fromLat, fromLng, toLat, toLng;
        public boolean reached;       // reached destination
        public double progressPct;    // how far along the path (0-100)
        public String terrainAtDest;

        public MovementOutcome(String code, double fl, double fln, double tl, double tln) {
            this.unitCode = code; this.fromLat = fl; this.fromLng = fln;
            this.toLat = tl; this.toLng = tln; this.reached = true; this.progressPct = 100;
        }
    }

    public static class Engagement {
        public String attackerCode, defenderCode;
        public double distanceKm;
        public String terrain;

        public Engagement(String a, String d, double dist, String t) {
            this.attackerCode = a; this.defenderCode = d;
            this.distanceKm = dist; this.terrain = t;
        }
    }

    public static class CombatOutcome {
        public String unitCode;
        public double initialStrength, finalStrength;
        public double lossRate;     // 0-1
        public String newStatus;    // active/engaged/retreating/destroyed/advancing
        public String reason;

        public CombatOutcome(String code, double init, double fin, double loss, String status, String reason) {
            this.unitCode = code; this.initialStrength = init; this.finalStrength = fin;
            this.lossRate = loss; this.newStatus = status; this.reason = reason;
        }
    }

    public final List<MovementOutcome> movements = new ArrayList<>();
    public final List<Engagement> engagements = new ArrayList<>();
    public final List<CombatOutcome> combatResults = new ArrayList<>();
    public String summary = "";
    public int roundNumber;
    public long timestamp = System.currentTimeMillis();

    public String toSummary() {
        int reached = 0, engaged = 0, destroyed = 0, retreated = 0;
        for (MovementOutcome m : movements) if (m.reached) reached++;
        for (CombatOutcome c : combatResults) {
            engaged++;
            if ("destroyed".equals(c.newStatus)) destroyed++;
            else if ("retreating".equals(c.newStatus)) retreated++;
        }
        return String.format("Round %d: %d/%d units reached destination, %d engagements (%d destroyed, %d retreating)",
            roundNumber, reached, movements.size(), engaged, destroyed, retreated);
    }
}
