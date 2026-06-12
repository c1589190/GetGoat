package com.cna.getgoat.map.network;

import java.util.*;
import com.cna.getgoat.map.geometry.GeoPoint;

/**
 * A node in the road network — could be an intersection, dead-end, or
 * a point where a road changes classification.
 */
public class RoadNode {
    private final String id;
    private final GeoPoint location;
    private final Set<String> segmentIds;  // road segments connected to this node
    private String name;

    public RoadNode(String id, GeoPoint location) {
        this.id = id;
        this.location = location;
        this.segmentIds = new HashSet<>();
        this.name = null;
    }

    public String getId() { return id; }
    public GeoPoint getLocation() { return location; }
    public Set<String> getSegmentIds() { return Collections.unmodifiableSet(segmentIds); }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public void addSegment(String segId) { segmentIds.add(segId); }

    @Override
    public String toString() {
        return String.format("RoadNode[%s %s segments=%d]", id, location, segmentIds.size());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoadNode r)) return false;
        return id.equals(r.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }
}
