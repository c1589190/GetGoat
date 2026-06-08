package com.getgoat.map.branch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One unit's state change between two rounds in a branch.
 * Supports move, hold, create, delete, and status-only updates.
 */
public class UnitChange {

    /** changeType values */
    public static final String MOVE = "move";
    public static final String HOLD = "hold";
    public static final String CREATE = "create";
    public static final String DELETE = "delete";
    public static final String STATUS_CHANGE = "status_change";

    private final String code;
    private final String changeType;    // move, hold, create, delete, status_change
    private final double fromLat, fromLng;
    private final double toLat, toLng;
    private final String action;        // advance, retreat, engage, flank, reinforce, deploy, hold
    private final String oldStatus, newStatus;
    private final String description;

    @JsonCreator
    public UnitChange(
            @JsonProperty("code") String code,
            @JsonProperty("changeType") String changeType,
            @JsonProperty("fromLat") double fromLat,
            @JsonProperty("fromLng") double fromLng,
            @JsonProperty("toLat") double toLat,
            @JsonProperty("toLng") double toLng,
            @JsonProperty("action") String action,
            @JsonProperty("oldStatus") String oldStatus,
            @JsonProperty("newStatus") String newStatus,
            @JsonProperty("description") String description) {
        this.code = code;
        this.changeType = changeType != null ? changeType : MOVE;
        this.fromLat = fromLat; this.fromLng = fromLng;
        this.toLat = toLat; this.toLng = toLng;
        this.action = action != null ? action : "move";
        this.oldStatus = oldStatus != null ? oldStatus : "";
        this.newStatus = newStatus != null ? newStatus : "";
        this.description = description != null ? description : "";
    }

    // Factory methods
    public static UnitChange moved(String code, double fromLat, double fromLng,
                                     double toLat, double toLng, String action) {
        return new UnitChange(code, MOVE, fromLat, fromLng, toLat, toLng,
            action, null, null, null);
    }

    public static UnitChange held(String code, double lat, double lng) {
        return new UnitChange(code, HOLD, lat, lng, lat, lng,
            "hold", null, null, "Stationary this round");
    }

    public static UnitChange created(String code, double lat, double lng, String action) {
        return new UnitChange(code, CREATE, lat, lng, lat, lng,
            action != null ? action : "deploy", null, "active", "New unit deployed");
    }

    public static UnitChange deleted(String code, double lat, double lng) {
        return new UnitChange(code, DELETE, lat, lng, lat, lng,
            "destroyed", "active", "destroyed", "Unit destroyed/withdrawn");
    }

    public static UnitChange statusChange(String code, double lat, double lng,
                                           String oldStatus, String newStatus) {
        return new UnitChange(code, STATUS_CHANGE, lat, lng, lat, lng,
            "status", oldStatus, newStatus, oldStatus + " → " + newStatus);
    }

    // Getters
    public String getCode() { return code; }
    public String getChangeType() { return changeType; }
    public double getFromLat() { return fromLat; }
    public double getFromLng() { return fromLng; }
    public double getToLat() { return toLat; }
    public double getToLng() { return toLng; }
    public String getAction() { return action; }
    public String getOldStatus() { return oldStatus; }
    public String getNewStatus() { return newStatus; }
    public String getDescription() { return description; }

    @Override
    public String toString() {
        return "UnitChange[" + code + " " + changeType + " " + action + "]";
    }
}
