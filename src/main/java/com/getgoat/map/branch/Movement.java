package com.getgoat.map.branch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Records a single unit's movement within a round.
 */
public class Movement {
    private final String code;
    private final double fromLat, fromLng, toLat, toLng;
    private final String action; // advance, retreat, hold, engage, flank, etc.

    @JsonCreator
    public Movement(
            @JsonProperty("code") String code,
            @JsonProperty("fromLat") double fromLat,
            @JsonProperty("fromLng") double fromLng,
            @JsonProperty("toLat") double toLat,
            @JsonProperty("toLng") double toLng,
            @JsonProperty("action") String action) {
        this.code = code; this.fromLat = fromLat; this.fromLng = fromLng;
        this.toLat = toLat; this.toLng = toLng;
        this.action = action != null ? action : "move";
    }

    public String getCode() { return code; }
    public double getFromLat() { return fromLat; }
    public double getFromLng() { return fromLng; }
    public double getToLat() { return toLat; }
    public double getToLng() { return toLng; }
    public String getAction() { return action; }
}
