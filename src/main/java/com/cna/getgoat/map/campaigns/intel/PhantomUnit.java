package com.cna.getgoat.map.campaigns.intel;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.cna.getgoat.map.campaigns.unit.Unit;

/**
 * A phantom/decoy unit — exists only in the target side's intelligence map.
 * Does NOT correspond to a real Unit and does NOT enter UnitsManager.
 */
public class PhantomUnit {

    private String id;
    private String name;
    private String targetSide;     // which side SEES this phantom
    private String createdBySide;  // which side PLANTED it
    private String type;
    private double lat, lng;
    private int createdInRound;
    private boolean active = true;

    public PhantomUnit() {}

    @JsonCreator
    public PhantomUnit(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("targetSide") String targetSide,
            @JsonProperty("createdBySide") String createdBySide,
            @JsonProperty("type") String type,
            @JsonProperty("lat") double lat,
            @JsonProperty("lng") double lng,
            @JsonProperty("createdInRound") int createdInRound) {
        this.id = id;
        this.name = name;
        this.targetSide = targetSide;
        this.createdBySide = createdBySide;
        this.type = type;
        this.lat = lat;
        this.lng = lng;
        this.createdInRound = createdInRound;
    }

    /** Convert to a SideIntelEntry for injection into a SideIntelMap. */
    public SideIntelEntry toIntelEntry() {
        return SideIntelEntry.phantom(id, name, createdBySide, type, lat, lng, createdInRound);
    }

    // Getters and setters
    public String getId() { return id; }
    public String getName() { return name; }
    public String getTargetSide() { return targetSide; }
    public String getCreatedBySide() { return createdBySide; }
    public String getType() { return type; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public int getCreatedInRound() { return createdInRound; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { active = v; }
    public void setId(String v) { id = v; }
    public void setName(String v) { name = v; }
    public void setTargetSide(String v) { targetSide = v; }
    public void setCreatedBySide(String v) { createdBySide = v; }
    public void setType(String v) { type = v; }
    public void setLat(double v) { lat = v; }
    public void setLng(double v) { lng = v; }
    public void setCreatedInRound(int v) { createdInRound = v; }
}
