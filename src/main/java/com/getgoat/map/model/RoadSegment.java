package com.getgoat.map.model;

import org.locationtech.jts.geom.LineString;

/**
 * A road segment — an edge in the road network graph.
 *
 * Each segment connects exactly two RoadNodes (from → to).
 * The geometry is stored for rendering and distance calculation.
 */
public class RoadSegment {
    private final String id;
    private final String fromNodeId;
    private final String toNodeId;
    private final LineString geometry;    // JTS LineString (WGS84)
    private final double lengthKm;        // great-circle length
    private final String highwayType;     // motorway, primary, secondary, etc.
    private String name;                  // road name (if known)
    private boolean marked;
    private String markLabel;

    public RoadSegment(String id, String fromNodeId, String toNodeId,
                       LineString geometry, double lengthKm, String highwayType) {
        this.id = id;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.geometry = geometry;
        this.lengthKm = lengthKm;
        this.highwayType = highwayType;
        this.marked = false;
        this.markLabel = null;
    }

    public String getId() { return id; }
    public String getFromNodeId() { return fromNodeId; }
    public String getToNodeId() { return toNodeId; }
    public LineString getGeometry() { return geometry; }
    public double getLengthKm() { return lengthKm; }
    public String getHighwayType() { return highwayType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isMarked() { return marked; }
    public String getMarkLabel() { return markLabel; }

    /** Mark this segment with a label. Returns the two endpoint node IDs. */
    public String[] mark(String label) {
        this.marked = true;
        this.markLabel = label;
        return new String[]{fromNodeId, toNodeId};
    }

    @Override
    public String toString() {
        return String.format("RoadSegment[%s %s→%s %.1fkm %s]",
            id, fromNodeId, toNodeId, lengthKm, highwayType);
    }
}
