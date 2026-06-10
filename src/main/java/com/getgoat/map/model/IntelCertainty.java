package com.getgoat.map.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How certain a side's intelligence is about a unit's existence and position.
 * Used in SideIntelEntry to mark LLM-assigned confidence levels.
 */
public enum IntelCertainty {
    /** Directly observed — own unit or confirmed by friendly visual contact */
    CONFIRMED("confirmed", "#2ecc71"),
    /** Position estimated from signals/patrols, within uncertainty radius */
    ESTIMATED("estimated", "#f39c12"),
    /** Last known position, no recent update — unit may have moved */
    OUTDATED("outdated", "#95a5a6"),
    /** Enemy deception — appears on intel map but is a phantom/decoy */
    DECOY("decoy", "#9b59b6");

    private final String displayName;
    private final String colorHex;

    IntelCertainty(String displayName, String colorHex) {
        this.displayName = displayName;
        this.colorHex = colorHex;
    }

    @JsonValue
    public String getDisplayName() { return displayName; }
    public String getColorHex() { return colorHex; }
}
