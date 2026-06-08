package com.getgoat.map.model;

import com.getgoat.map.geometry.SphericalEngine;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.logging.Logger;

/**
 * Lazy-loaded road network — only parses road segments within queried regions.
 *
 * Uses a grid cache (5° cells). When a query comes in:
 *   1. Check which 5° cells intersect the query radius
 *   2. For any uncached cells, stream the .shp file, filter by bbox
 *   3. Build a local subgraph for just those segments
 *   4. Cache and reuse
 */
public class RoadNetwork {

    private static final Logger LOG = Logger.getLogger(RoadNetwork.class.getName());
    private static final double MERGE_THRESHOLD_KM = 5.0; // wider merge for 1:10m simplified roads
    private static final int CELL_SIZE_DEG = 5; // 5° grid cells

    private final String shpPath;
    private final GeometryFactory geomFactory;
    private boolean shpAvailable;

    // Cache: "latBin:lngBin" → subgraph
    private final Map<String, RegionGraph> cache = new LinkedHashMap<>();
    private static final int MAX_CACHED_REGIONS = 16;


    public RoadNetwork() {
        this.shpPath = com.getgoat.map.ConfigManager.getRoadsShapefile();
        this.geomFactory = new GeometryFactory(new PrecisionModel(), 4326);
        this.shpAvailable = new java.io.File(shpPath).exists();
        if (shpAvailable) LOG.info("Road shapefile found: " + shpPath);
    }

    public boolean isAvailable() { return shpAvailable; }

    // ---- Region-based lazy loading ----

    /**
     * Ensure road data is loaded for the region around (lat, lng) within radiusKm.
     * Called before any query.
     */
    public void ensureRegion(double lat, double lng, double radiusKm) {
        if (!shpAvailable) return;

        double margin = radiusKm / 111.32 + CELL_SIZE_DEG;
        int minLatBin = (int) Math.floor((lat - margin) / CELL_SIZE_DEG);
        int maxLatBin = (int) Math.floor((lat + margin) / CELL_SIZE_DEG);
        int minLngBin = (int) Math.floor((lng - margin) / CELL_SIZE_DEG);
        int maxLngBin = (int) Math.floor((lng + margin) / CELL_SIZE_DEG);

        for (int lb = minLatBin; lb <= maxLatBin; lb++) {
            for (int mb = minLngBin; mb <= maxLngBin; mb++) {
                String key = lb + ":" + mb;
                if (!cache.containsKey(key)) {
                    loadCell(lb, mb);
                }
            }
        }

        // Evict oldest if too many cached
        if (cache.size() > MAX_CACHED_REGIONS) {
            String oldest = cache.keySet().iterator().next();
            cache.remove(oldest);
        }
    }

    /** Load a single 5° grid cell from the shapefile. */
    private void loadCell(int latBin, int lngBin) {
        double cellSouth = latBin * CELL_SIZE_DEG;
        double cellNorth = cellSouth + CELL_SIZE_DEG;
        double cellWest = lngBin * CELL_SIZE_DEG;
        double cellEast = cellWest + CELL_SIZE_DEG;

        String key = latBin + ":" + lngBin;
        RegionGraph graph = new RegionGraph();
        long t0 = System.currentTimeMillis();

        try (InputStream is = new BufferedInputStream(new FileInputStream(shpPath), 1 << 20)) {
            // Skip 100-byte header
            is.skipNBytes(100);

            byte[] headerBuf = new byte[8];
            byte[] contentBuf = new byte[0];

            while (true) {
                // Read record header (big-endian)
                int n = is.readNBytes(headerBuf, 0, 8);
                if (n < 8) break;
                int recNum = ByteBuffer.wrap(headerBuf, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                int contentLen = ByteBuffer.wrap(headerBuf, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt();

                // Read content
                int byteLen = contentLen * 2;
                if (contentBuf.length < byteLen) contentBuf = new byte[byteLen];
                is.readNBytes(contentBuf, 0, byteLen);
                ByteBuffer cb = ByteBuffer.wrap(contentBuf, 0, byteLen).order(ByteOrder.LITTLE_ENDIAN);

                int shapeType = cb.getInt();
                if (shapeType == 0 || (shapeType != 3 && shapeType != 13 && shapeType != 23)) continue;

                double xmin = cb.getDouble();
                double ymin = cb.getDouble();
                double xmax = cb.getDouble();
                double ymax = cb.getDouble();

                // Quick bbox reject
                if (ymax < cellSouth || ymin > cellNorth ||
                    xmax < cellWest || xmin > cellEast) continue;

                // Parse full geometry
                int numParts = cb.getInt();
                int numPoints = cb.getInt();

                int[] parts = new int[numParts + 1];
                for (int i = 0; i < numParts; i++) parts[i] = cb.getInt();
                parts[numParts] = numPoints;

                Coordinate[] allPoints = new Coordinate[numPoints];
                for (int i = 0; i < numPoints; i++) {
                    allPoints[i] = new Coordinate(cb.getDouble(), cb.getDouble());
                }

                // Add each part as a segment
                for (int p = 0; p < numParts; p++) {
                    int start = parts[p], end = parts[p + 1];
                    if (end - start < 2) continue;

                    Coordinate[] coords = new Coordinate[end - start];
                    System.arraycopy(allPoints, start, coords, 0, end - start);
                    LineString line = geomFactory.createLineString(coords);

                    double lenKm = 0;
                    for (int i = 1; i < coords.length; i++)
                        lenKm += SphericalEngine.haversineDistance(
                            coords[i-1].y, coords[i-1].x, coords[i].y, coords[i].x);

                    String sId = "rs:" + key + ":" + graph.segments.size();
                    String fromId = graph.findOrCreateNode(coords[0].y, coords[0].x);
                    String toId = graph.findOrCreateNode(coords[coords.length-1].y, coords[coords.length-1].x);

                    RoadSegment seg = new RoadSegment(sId, fromId, toId, line, lenKm, "road");
                    graph.segments.put(sId, seg);
                    graph.nodes.get(fromId).addSegment(sId);
                    graph.nodes.get(toId).addSegment(sId);
                    graph.adjacency.computeIfAbsent(fromId, k -> new ArrayList<>()).add(toId);
                    graph.adjacency.computeIfAbsent(toId, k -> new ArrayList<>()).add(fromId);
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to load road cell " + key + ": " + e.getMessage());
        }

        cache.put(key, graph);
        if (graph.segments.size() > 0) {
            LOG.info(String.format("Loaded road cell %s: %d nodes, %d segments (%dms)",
                key, graph.nodes.size(), graph.segments.size(),
                System.currentTimeMillis() - t0));
        }
    }

    // ---- Queries (aggregate across relevant cached cells) ----

    public List<RoadNode> findNodesInRadius(double lat, double lng, double radiusKm) {
        if (!shpAvailable) return List.of();
        List<RoadNode> result = new ArrayList<>();
        for (RegionGraph g : matchingCells(lat, lng, radiusKm)) {
            for (RoadNode node : g.nodes.values()) {
                double d = SphericalEngine.haversineDistance(lat, lng,
                    node.getLocation().getLatitude(), node.getLocation().getLongitude());
                if (d <= radiusKm) result.add(node);
            }
        }
        return result;
    }

    public List<RoadSegment> findSegmentsInRadius(double lat, double lng, double radiusKm) {
        if (!shpAvailable) return List.of();
        Set<String> seen = new HashSet<>();
        List<RoadSegment> result = new ArrayList<>();
        for (RoadNode node : findNodesInRadius(lat, lng, radiusKm)) {
            for (String sid : node.getSegmentIds()) {
                for (RegionGraph g : matchingCells(lat, lng, radiusKm)) {
                    RoadSegment seg = g.segments.get(sid);
                    if (seg != null && seen.add(sid)) result.add(seg);
                }
            }
        }
        return result;
    }

    /** All road segments currently cached across all loaded cells. */
    public List<RoadSegment> allSegments() {
        List<RoadSegment> all = new ArrayList<>();
        for (RegionGraph g : cache.values()) {
            all.addAll(g.segments.values());
        }
        return all;
    }

    public RoadSegment getSegment(String segId) {
        for (RegionGraph g : cache.values()) {
            RoadSegment seg = g.segments.get(segId);
            if (seg != null) return seg;
        }
        return null;
    }

    public RoadNode getNode(String nodeId) {
        for (RegionGraph g : cache.values()) {
            RoadNode node = g.nodes.get(nodeId);
            if (node != null) return node;
        }
        return null;
    }

    /** Find the nearest road node to a place. */
    public String findNearestNode(String placeName) {
        double[] coords = RoadNetwork.lookupPlace(placeName);
        if (coords == null) return null;
        return findNearestNode(coords[0], coords[1]);
    }

    public String findNearestNode(double lat, double lng) {
        if (!shpAvailable) return null;
        // Ensure data is loaded around this point
        ensureRegion(lat, lng, 50);

        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (RegionGraph g : matchingCells(lat, lng, 200)) {
            for (RoadNode node : g.nodes.values()) {
                double d = SphericalEngine.haversineDistance(lat, lng,
                    node.getLocation().getLatitude(), node.getLocation().getLongitude());
                if (d < bestDist) { bestDist = d; best = node.getId(); }
            }
        }
        return best;
    }

    /** Dijkstra shortest path between two nodes. */
    public PathResult shortestPath(String fromNodeId, String toNodeId) {
        // Collect all nodes and adjacency from all cached cells
        Map<String, RoadNode> allNodes = new LinkedHashMap<>();
        Map<String, List<String>> allAdj = new LinkedHashMap<>();
        Map<String, RoadSegment> allSegs = new LinkedHashMap<>();
        for (RegionGraph g : cache.values()) {
            allNodes.putAll(g.nodes);
            for (var entry : g.adjacency.entrySet()) {
                allAdj.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            }
            allSegs.putAll(g.segments);
        }

        if (!allNodes.containsKey(fromNodeId) || !allNodes.containsKey(toNodeId))
            return new PathResult(List.of(), List.of(), 0, false);
        if (fromNodeId.equals(toNodeId))
            return new PathResult(List.of(fromNodeId), List.of(), 0, true);

        Map<String, Double> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Map<String, String> edgeUsed = new HashMap<>();
        var pq = new PriorityQueue<double[]>(Comparator.comparingDouble(a -> a[1]));

        for (String n : allNodes.keySet()) dist.put(n, Double.POSITIVE_INFINITY);
        dist.put(fromNodeId, 0.0);
        pq.add(new double[]{fromNodeId.hashCode(), 0}); // use hash as proxy

        // Better: use proper NodeDist
        record ND(String n, double d) {}
        var pq2 = new PriorityQueue<ND>(Comparator.comparingDouble(nd -> nd.d));
        pq2.add(new ND(fromNodeId, 0));

        while (!pq2.isEmpty()) {
            ND cur = pq2.poll();
            if (cur.d > dist.get(cur.n)) continue;
            if (cur.n.equals(toNodeId)) break;

            for (String v : allAdj.getOrDefault(cur.n, List.of())) {
                RoadNode un = allNodes.get(cur.n);
                RoadNode vn = allNodes.get(v);
                double w = SphericalEngine.haversineDistance(
                    un.getLocation(), vn.getLocation());
                // Check if there's a road segment with a more precise length
                for (String sid : un.getSegmentIds()) {
                    RoadSegment seg = allSegs.get(sid);
                    if (seg != null && (seg.getFromNodeId().equals(v) || seg.getToNodeId().equals(v))) {
                        w = seg.getLengthKm();
                        edgeUsed.put(v, sid);
                        break;
                    }
                }
                double alt = dist.get(cur.n) + w;
                if (alt < dist.get(v)) {
                    dist.put(v, alt);
                    prev.put(v, cur.n);
                    pq2.add(new ND(v, alt));
                }
            }
        }

        if (!prev.containsKey(toNodeId)) return new PathResult(List.of(), List.of(), 0, false);

        LinkedList<String> pathNodes = new LinkedList<>();
        LinkedList<String> pathSegs = new LinkedList<>();
        String cur = toNodeId;
        while (cur != null) {
            pathNodes.addFirst(cur);
            String sid = edgeUsed.get(cur);
            if (sid != null) pathSegs.addFirst(sid);
            cur = prev.get(cur);
        }
        return new PathResult(pathNodes, pathSegs, dist.get(toNodeId), true);
    }

    // ---- Helpers ----

    private Set<RegionGraph> matchingCells(double lat, double lng, double radiusKm) {
        double margin = radiusKm / 111.32 + CELL_SIZE_DEG;
        Set<RegionGraph> result = new LinkedHashSet<>();
        int minLatBin = (int) Math.floor((lat - margin) / CELL_SIZE_DEG);
        int maxLatBin = (int) Math.floor((lat + margin) / CELL_SIZE_DEG);
        int minLngBin = (int) Math.floor((lng - margin) / CELL_SIZE_DEG);
        int maxLngBin = (int) Math.floor((lng + margin) / CELL_SIZE_DEG);
        for (int lb = minLatBin; lb <= maxLatBin; lb++)
            for (int mb = minLngBin; mb <= maxLngBin; mb++)
                result.add(cache.get(lb + ":" + mb));
        result.remove(null);
        return result;
    }

    // ---- Inner: subgraph for one 5° cell ----
    private static class RegionGraph {
        final Map<String, RoadNode> nodes = new LinkedHashMap<>();
        final Map<String, RoadSegment> segments = new LinkedHashMap<>();
        final Map<String, List<String>> adjacency = new LinkedHashMap<>();
        final Map<String, List<RoadNode>> nodeGrid = new LinkedHashMap<>();

        String findOrCreateNode(double lat, double lng) {
            // Snap to grid (~0.02° ≈ 2km) for globally consistent node IDs
            int snapLat = (int) Math.round(lat * 50);  // 0.02° steps
            int snapLng = (int) Math.round(lng * 50);
            String id = "rn:" + snapLat + ":" + snapLng;

            if (nodes.containsKey(id)) return id;

            RoadNode node = new RoadNode(id, new GeoPoint(
                snapLat / 50.0, snapLng / 50.0));
            nodes.put(id, node);
            return id;
        }

        void buildSpatialIndex() {
            // STRtree built lazily — nodes are indexed via the grid hash
        }
    }

    // ---- Data types ----

    public record PathResult(
        List<String> nodeIds, List<String> segmentIds,
        double totalDistanceKm, boolean reachable
    ) {}

    // ---- Gazetteer (unchanged) ----
    private static final Map<String, double[]> GAZETTEER = new LinkedHashMap<>();
    static {
        GAZETTEER.put("London",       new double[]{51.5074, -0.1278});
        GAZETTEER.put("Paris",        new double[]{48.8566, 2.3522});
        GAZETTEER.put("Berlin",       new double[]{52.5200, 13.4050});
        GAZETTEER.put("Moscow",       new double[]{55.7558, 37.6173});
        GAZETTEER.put("Rome",         new double[]{41.9028, 12.4964});
        GAZETTEER.put("Madrid",       new double[]{40.4168, -3.7038});
        GAZETTEER.put("Vienna",       new double[]{48.2082, 16.3738});
        GAZETTEER.put("Warsaw",       new double[]{52.2297, 21.0122});
        GAZETTEER.put("Istanbul",     new double[]{41.0082, 28.9784});
        GAZETTEER.put("Athens",       new double[]{37.9838, 23.7275});
        GAZETTEER.put("Brussels",     new double[]{50.8503, 4.3517});
        GAZETTEER.put("Amsterdam",    new double[]{52.3676, 4.9041});
        GAZETTEER.put("Frankfurt",    new double[]{50.1109, 8.6821});
        GAZETTEER.put("Munich",       new double[]{48.1351, 11.5820});
        GAZETTEER.put("Prague",       new double[]{50.0755, 14.4378});
        GAZETTEER.put("Copenhagen",   new double[]{55.6761, 12.5683});
        GAZETTEER.put("Zurich",       new double[]{47.3769, 8.5417});
        GAZETTEER.put("Milan",        new double[]{45.4642, 9.1900});
        GAZETTEER.put("Barcelona",    new double[]{41.3874, 2.1686});
        GAZETTEER.put("Lisbon",       new double[]{38.7223, -9.1393});
        GAZETTEER.put("Dublin",       new double[]{53.3498, -6.2603});
        GAZETTEER.put("Oslo",         new double[]{59.9139, 10.7522});
        GAZETTEER.put("Stockholm",    new double[]{59.3293, 18.0686});
        GAZETTEER.put("Helsinki",     new double[]{60.1699, 24.9384});
        GAZETTEER.put("Kiev",         new double[]{50.4501, 30.5234});
        GAZETTEER.put("Budapest",     new double[]{47.4979, 19.0402});
        GAZETTEER.put("New York",     new double[]{40.7128, -74.0060});
        GAZETTEER.put("Los Angeles",  new double[]{34.0522, -118.2437});
        GAZETTEER.put("Chicago",      new double[]{41.8781, -87.6298});
        GAZETTEER.put("Toronto",      new double[]{43.6532, -79.3832});
        GAZETTEER.put("Mexico City",  new double[]{19.4326, -99.1332});
        GAZETTEER.put("Buenos Aires", new double[]{-34.6037, -58.3816});
        GAZETTEER.put("Sao Paulo",    new double[]{-23.5505, -46.6333});
        GAZETTEER.put("Lima",         new double[]{-12.0464, -77.0428});
        GAZETTEER.put("Beijing",      new double[]{39.9042, 116.4074});
        GAZETTEER.put("Shanghai",     new double[]{31.2304, 121.4737});
        GAZETTEER.put("Tokyo",        new double[]{35.6762, 139.6503});
        GAZETTEER.put("Seoul",        new double[]{37.5665, 126.9780});
        GAZETTEER.put("Delhi",        new double[]{28.6139, 77.2090});
        GAZETTEER.put("Mumbai",       new double[]{19.0760, 72.8777});
        GAZETTEER.put("Bangkok",      new double[]{13.7563, 100.5018});
        GAZETTEER.put("Singapore",    new double[]{1.3521, 103.8198});
        GAZETTEER.put("Jakarta",      new double[]{-6.2088, 106.8456});
        GAZETTEER.put("Dubai",        new double[]{25.2048, 55.2708});
        GAZETTEER.put("Tehran",       new double[]{35.6892, 51.3890});
        GAZETTEER.put("Baghdad",      new double[]{33.3152, 44.3661});
        GAZETTEER.put("Cairo",        new double[]{30.0444, 31.2357});
        GAZETTEER.put("Lagos",        new double[]{6.5244, 3.3792});
        GAZETTEER.put("Nairobi",      new double[]{-1.2921, 36.8219});
        GAZETTEER.put("Cape Town",    new double[]{-33.9249, 18.4241});
        GAZETTEER.put("Addis Ababa",  new double[]{9.0320, 38.7469});
        GAZETTEER.put("Sydney",       new double[]{-33.8688, 151.2093});
        GAZETTEER.put("Melbourne",    new double[]{-37.8136, 144.9631});
    }

    public static double[] lookupPlace(String name) {
        if (GAZETTEER.containsKey(name)) return GAZETTEER.get(name);
        for (var e : GAZETTEER.entrySet())
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        if (name.contains(",")) {
            String[] p = name.split(",");
            try { return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])}; }
            catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public GeoPoint getPlaceLocation(String name) {
        double[] c = lookupPlace(name);
        return c != null ? new GeoPoint(c[0], c[1]) : null;
    }

    public static Set<String> getPlaceNames() {
        return Collections.unmodifiableSet(GAZETTEER.keySet());
    }
}
