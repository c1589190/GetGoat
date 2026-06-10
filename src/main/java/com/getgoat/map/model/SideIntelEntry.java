package com.getgoat.map.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What one side "knows" about a single unit (real or phantom).
 *
 * Real units reference {@code unitCode}; phantom entries have {@code unitCode == null}
 * and use {@code phantomId} instead.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SideIntelEntry {

    private String unitCode;        // null for phantom entries
    private String phantomId;       // null for real-unit entries
    private String name;            // displayed name (may differ from real unit name)
    private String apparentSource;  // which side this unit APPEARS to belong to
    private String reportedType;    // reported unit type (may differ from real type)
    private IntelCertainty certainty;
    private double lat, lng;
    private double uncertaintyRadiusKm;  // 0 = exact position
    private int lastObservedRound;       // round number when last confirmed

    public SideIntelEntry() {}

    @JsonCreator
    public SideIntelEntry(
            @JsonProperty("unitCode") String unitCode,
            @JsonProperty("phantomId") String phantomId,
            @JsonProperty("name") String name,
            @JsonProperty("apparentSource") String apparentSource,
            @JsonProperty("reportedType") String reportedType,
            @JsonProperty("certainty") IntelCertainty certainty,
            @JsonProperty("lat") double lat,
            @JsonProperty("lng") double lng,
            @JsonProperty("uncertaintyRadiusKm") double uncertaintyRadiusKm,
            @JsonProperty("lastObservedRound") int lastObservedRound) {
        this.unitCode = unitCode;
        this.phantomId = phantomId;
        this.name = name;
        this.apparentSource = apparentSource;
        this.reportedType = reportedType;
        this.certainty = certainty;
        this.lat = lat;
        this.lng = lng;
        this.uncertaintyRadiusKm = uncertaintyRadiusKm;
        this.lastObservedRound = lastObservedRound;
    }

    /** Factory: confirmed observation of a real unit. */
    public static SideIntelEntry confirmed(String unitCode, String name, String source,
                                            String type, double lat, double lng, int round) {
        SideIntelEntry e = new SideIntelEntry();
        e.unitCode = unitCode;
        e.name = name;
        e.apparentSource = source;
        e.reportedType = type;
        e.certainty = IntelCertainty.CONFIRMED;
        e.lat = lat;
        e.lng = lng;
        e.uncertaintyRadiusKm = 0;
        e.lastObservedRound = round;
        return e;
    }

    /** Factory: estimated position of a real unit. */
    public static SideIntelEntry estimated(String unitCode, String name, String source,
                                            String type, double lat, double lng,
                                            double uncertaintyKm, int round) {
        SideIntelEntry e = confirmed(unitCode, name, source, type, lat, lng, round);
        e.certainty = IntelCertainty.ESTIMATED;
        e.uncertaintyRadiusKm = uncertaintyKm;
        return e;
    }

    /** Factory: outdated position of a real unit. */
    public static SideIntelEntry outdated(String unitCode, String name, String source,
                                           String type, double lat, double lng,
                                           double uncertaintyKm, int round) {
        SideIntelEntry e = estimated(unitCode, name, source, type, lat, lng, uncertaintyKm, round);
        e.certainty = IntelCertainty.OUTDATED;
        return e;
    }

    /** Factory: phantom/decoy unit. */
    public static SideIntelEntry phantom(String phantomId, String name, String apparentSource,
                                          String type, double lat, double lng, int round) {
        SideIntelEntry e = new SideIntelEntry();
        e.phantomId = phantomId;
        e.name = name;
        e.apparentSource = apparentSource;
        e.reportedType = type;
        e.certainty = IntelCertainty.DECOY;
        e.lat = lat;
        e.lng = lng;
        e.uncertaintyRadiusKm = 0;
        e.lastObservedRound = round;
        return e;
    }

    public boolean isPhantom() { return phantomId != null; }

    // Getters
    public String getUnitCode() { return unitCode; }
    public String getPhantomId() { return phantomId; }
    public String getName() { return name; }
    public String getApparentSource() { return apparentSource; }
    public String getReportedType() { return reportedType; }
    public IntelCertainty getCertainty() { return certainty; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public double getUncertaintyRadiusKm() { return uncertaintyRadiusKm; }
    public int getLastObservedRound() { return lastObservedRound; }

    // Setters (for Jackson and manual construction)
    public void setUnitCode(String v) { unitCode = v; }
    public void setPhantomId(String v) { phantomId = v; }
    public void setName(String v) { name = v; }
    public void setApparentSource(String v) { apparentSource = v; }
    public void setReportedType(String v) { reportedType = v; }
    public void setCertainty(IntelCertainty v) { certainty = v; }
    public void setLat(double v) { lat = v; }
    public void setLng(double v) { lng = v; }
    public void setUncertaintyRadiusKm(double v) { uncertaintyRadiusKm = v; }
    public void setLastObservedRound(int v) { lastObservedRound = v; }
}
