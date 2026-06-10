package com.getgoat.map.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;

/**
 * Complete intelligence picture for one side at a specific round.
 * Stored inside BranchNode to capture per-side fog-of-war state.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SideIntelMap {

    private String side;
    private int roundNumber;
    private List<SideIntelEntry> entries = new ArrayList<>();

    /**
     * Explored regions — rectangles in lat/lng space that this side has scouted.
     * Each entry is [minLat, maxLat, minLng, maxLng].
     */
    private List<double[]> exploredBounds = new ArrayList<>();

    /** Free-text intel summary (LLM-generated). */
    private String summary = "";

    public SideIntelMap() {}

    @JsonCreator
    public SideIntelMap(
            @JsonProperty("side") String side,
            @JsonProperty("roundNumber") int roundNumber,
            @JsonProperty("entries") List<SideIntelEntry> entries,
            @JsonProperty("exploredBounds") List<double[]> exploredBounds,
            @JsonProperty("summary") String summary) {
        this.side = side;
        this.roundNumber = roundNumber;
        this.entries = entries != null ? entries : new ArrayList<>();
        this.exploredBounds = exploredBounds != null ? exploredBounds : new ArrayList<>();
        this.summary = summary != null ? summary : "";
    }

    public static SideIntelMap create(String side, int round) {
        SideIntelMap m = new SideIntelMap();
        m.side = side;
        m.roundNumber = round;
        return m;
    }

    /** Add an intel entry (real or phantom). */
    public void addEntry(SideIntelEntry e) { entries.add(e); }

    /** Get all entries for this side. */
    public List<SideIntelEntry> getEntries() { return entries; }

    /** Get only confirmed entries. Not serialized (computed). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<SideIntelEntry> getConfirmed() {
        return entries.stream()
            .filter(e -> e.getCertainty() == IntelCertainty.CONFIRMED)
            .toList();
    }

    /** Get entries matching a unit code. */
    public SideIntelEntry findByUnitCode(String code) {
        return entries.stream()
            .filter(e -> code.equals(e.getUnitCode()))
            .findFirst().orElse(null);
    }

    /** Check if a unit code is visible to this side. */
    public boolean isVisible(String unitCode) {
        return entries.stream().anyMatch(e -> unitCode.equals(e.getUnitCode()));
    }

    /** Check if a lat/lng is within any explored region. */
    public boolean isExplored(double lat, double lng) {
        for (double[] b : exploredBounds) {
            if (lat >= b[0] && lat <= b[1] && lng >= b[2] && lng <= b[3])
                return true;
        }
        return false;
    }

    /** Mark a lat/lng rectangle as explored. */
    public void addExploredBounds(double minLat, double maxLat, double minLng, double maxLng) {
        exploredBounds.add(new double[]{minLat, maxLat, minLng, maxLng});
    }

    /** Build a markdown intel brief suitable for CommanderAgent prompt. */
    public String toIntelBrief(Map<String, List<Unit>> allUnitsBySource) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前态势 — ").append(side).append("视角 (Round ").append(roundNumber).append(")\n\n");

        // Friendly units (always confirmed)
        List<Unit> friendly = allUnitsBySource.getOrDefault(side, List.of());
        sb.append("### 己方部队 (").append(friendly.size()).append(" units)\n");
        sb.append("| code | name | lat | lng | type | status |\n");
        sb.append("|------|------|-----|-----|------|--------|\n");
        for (Unit u : friendly) {
            sb.append("| ").append(u.getCode()).append(" | ").append(u.getName())
              .append(" | ").append(String.format("%.2f", u.getLat()))
              .append(" | ").append(String.format("%.2f", u.getLng()))
              .append(" | ").append(u.getType()).append(" | ").append(u.getStatus()).append(" |\n");
        }

        // Observed enemy units (from intel)
        List<SideIntelEntry> enemyIntel = new ArrayList<>();
        List<SideIntelEntry> phantoms = new ArrayList<>();
        for (SideIntelEntry e : entries) {
            if (side.equals(e.getApparentSource())) continue; // skip friendly
            if (e.isPhantom()) phantoms.add(e);
            else enemyIntel.add(e);
        }

        sb.append("\n### 观察到的敌军 (").append(enemyIntel.size()).append(")\n");
        if (enemyIntel.isEmpty()) {
            sb.append("(无)\n");
        } else {
            for (SideIntelEntry e : enemyIntel) {
                String tag = switch (e.getCertainty()) {
                    case CONFIRMED -> "[确认]";
                    case ESTIMATED -> "[估算]";
                    case OUTDATED -> "[过时]";
                    case DECOY -> "[疑兵]";
                };
                sb.append("- ").append(e.getUnitCode()).append(" ").append(e.getName())
                  .append(" ").append(tag).append(" @ (").append(String.format("%.2f", e.getLat()))
                  .append(", ").append(String.format("%.2f", e.getLng())).append(")");
                if (e.getUncertaintyRadiusKm() > 0)
                    sb.append(" ±").append((int)e.getUncertaintyRadiusKm()).append("km");
                if (e.getCertainty() == IntelCertainty.OUTDATED)
                    sb.append(" (最后观测: Round ").append(e.getLastObservedRound()).append(")");
                sb.append(" ").append(e.getReportedType()).append("\n");
            }
        }

        // Phantoms
        if (!phantoms.isEmpty()) {
            sb.append("\n### 可疑目标 (疑兵/假象)\n");
            for (SideIntelEntry e : phantoms) {
                sb.append("- ").append(e.getName()).append(" [疑兵] @ (")
                  .append(String.format("%.2f", e.getLat())).append(", ")
                  .append(String.format("%.2f", e.getLng())).append(") ")
                  .append(e.getReportedType()).append("\n");
            }
        }

        // Explored regions
        if (!exploredBounds.isEmpty()) {
            sb.append("\n### 侦察态势\n");
            sb.append("已探索区域:\n");
            for (double[] b : exploredBounds) {
                sb.append("- ").append(String.format("%.2f", b[0])).append("-")
                  .append(String.format("%.2f", b[1])).append("N, ")
                  .append(String.format("%.2f", b[2])).append("-")
                  .append(String.format("%.2f", b[3])).append("E\n");
            }
        }

        if (summary != null && !summary.isEmpty()) {
            sb.append("\n### 情报摘要\n").append(summary).append("\n");
        }

        return sb.toString();
    }

    // Getters and setters
    public String getSide() { return side; }
    public int getRoundNumber() { return roundNumber; }
    public List<double[]> getExploredBounds() { return exploredBounds; }
    public String getSummary() { return summary; }
    public void setSide(String v) { side = v; }
    public void setRoundNumber(int v) { roundNumber = v; }
    public void setEntries(List<SideIntelEntry> v) { entries = v; }
    public void setExploredBounds(List<double[]> v) { exploredBounds = v; }
    public void setSummary(String v) { summary = v; }
}
