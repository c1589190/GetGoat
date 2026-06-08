package com.getgoat.map.branch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getgoat.map.manager.UnitsManager;
import com.getgoat.map.model.Unit;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages all BranchTree objects — persistence to branches.json, capture/restore.
 */
public class BranchManager {

    private static final Logger LOG = Logger.getLogger(BranchManager.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_STORE = "branches.json";

    private final Map<String, BranchTree> trees = new LinkedHashMap<>();
    private Path storePath;
    private Path workspaceDir;
    private boolean workspaceReady = false;
    private final UnitsManager unitsManager;

    public BranchManager(UnitsManager unitsManager) {
        this.unitsManager = unitsManager;
        this.storePath = Paths.get(System.getProperty("user.dir")).resolve(DEFAULT_STORE);
    }

    /** Set workspace directory and load branches from it. */
    public void setWorkspace(String workspaceDir) {
        try {
            Path dir = Paths.get(workspaceDir);
            if (!dir.isAbsolute()) dir = Paths.get(System.getProperty("user.dir")).resolve(workspaceDir);
            if (!Files.isDirectory(dir)) Files.createDirectories(dir);
            this.workspaceDir = dir;
            this.storePath = dir.resolve(DEFAULT_STORE);
            workspaceReady = true;
            loadFromDisk();
        } catch (IOException e) {
            LOG.warning("Failed to set workspace: " + e.getMessage());
        }
    }

    public boolean isWorkspaceReady() { return workspaceReady; }
    public Path getStorePath() { return storePath; }
    public Path getWorkspaceDir() { return workspaceDir; }

    /** Reload branches + units from workspace json files. */
    public String reloadWorkspace() {
        if (workspaceDir == null) return "{\"error\":\"no workspace configured\"}";
        // Reload branches
        loadFromDisk();
        // Also reload units if units.json exists
        Path unitsFile = workspaceDir.resolve("units.json");
        int unitsLoaded = 0;
        if (Files.exists(unitsFile)) {
            try {
                unitsManager.deleteAll();
                String content = Files.readString(unitsFile);
                var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
                for (var node : arr) {
                    String code = node.has("code") ? node.get("code").asText() : null;
                    if (code == null || code.isEmpty()) continue;
                    String name = node.has("name") ? node.get("name").asText() : code;
                    String source = node.has("source") ? node.get("source").asText() : "custom";
                    String type = node.has("type") ? node.get("type").asText() : "generic";
                    double lat = node.has("lat") ? node.get("lat").asDouble() : 0;
                    double lng = node.has("lng") ? node.get("lng").asDouble() : 0;
                    var u = unitsManager.create(code, name, source, type, lat, lng);
                    if (node.has("color")) u.setColor(node.get("color").asText());
                    if (node.has("status")) u.setStatus(node.get("status").asText());
                    if (node.has("description")) u.setDescription(node.get("description").asText());
                    unitsLoaded++;
                }
            } catch (Exception e) {
                LOG.warning("Failed to reload units from workspace: " + e.getMessage());
            }
        }
        return "{\"ok\":true,\"branches\":" + trees.size() + ",\"units\":" + unitsLoaded + "}";
    }

    // ---- Persistence ----

    public void loadFromDisk() {
        if (!Files.exists(storePath)) return;
        try {
            BranchTree[] arr = MAPPER.readValue(storePath.toFile(), BranchTree[].class);
            trees.clear();
            for (BranchTree t : arr) trees.put(t.getId(), t);
            LOG.info("Loaded " + trees.size() + " branch trees from " + DEFAULT_STORE);
        } catch (Exception e) {
            LOG.warning("Failed to load branches: " + e.getMessage());
        }
    }

    public synchronized void saveToDisk() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(storePath.toFile(), new ArrayList<>(trees.values()));
            LOG.info("Saved " + trees.size() + " branch trees to " + DEFAULT_STORE);
        } catch (IOException e) {
            LOG.warning("Failed to save branches: " + e.getMessage());
        }
    }

    // ---- CRUD ----

    public BranchTree createTree(String name) {
        List<UnitSnapshot> snapshots = captureSnapshot();
        BranchTree t = BranchTree.create(name, snapshots);
        trees.put(t.getId(), t);
        saveToDisk();
        return t;
    }

    public List<BranchTree> listTrees() {
        return new ArrayList<>(trees.values());
    }

    public BranchTree getTree(String treeId) {
        return trees.get(treeId);
    }

    public boolean deleteTree(String treeId) {
        BranchTree removed = trees.remove(treeId);
        if (removed != null) { saveToDisk(); return true; }
        return false;
    }

    // ---- Node operations ----

    /**
     * Add a new round node under parentNodeId in the given tree.
     * Captures current live unit positions as the snapshot for this round.
     * Computes UnitChanges (move/hold/create/delete) by diffing parent vs current.
     */
    public BranchNode addRound(String treeId, String parentNodeId, String name,
                                int round, String strategy, String outcome) {
        BranchTree tree = trees.get(treeId);
        if (tree == null) return null;

        BranchNode parent = parentNodeId == null || "root".equals(parentNodeId)
            ? tree.getRoot() : tree.findNode(parentNodeId);
        if (parent == null) return null;

        List<UnitSnapshot> currentSnapshots = captureSnapshot();
        List<UnitChange> changes = computeUnitChanges(parent.getUnitSnapshots(), currentSnapshots);
        // Also compute legacy moves for backward compatibility
        List<Movement> moves = computeMoves(parent.getUnitSnapshots(), currentSnapshots);

        BranchNode child = BranchNode.createRound(
            parent.getId(), name, round, strategy,
            currentSnapshots, moves, changes, outcome);
        parent.addChild(child);
        saveToDisk();
        return child;
    }

    /**
     * Compute UnitChanges by comparing parent and current snapshots.
     * Covers: move (position changed), hold (same position), create (new unit), delete (unit removed).
     */
    private List<UnitChange> computeUnitChanges(List<UnitSnapshot> parentSnaps,
                                                 List<UnitSnapshot> currentSnaps) {
        List<UnitChange> changes = new ArrayList<>();
        Map<String, UnitSnapshot> parentMap = new LinkedHashMap<>();
        for (UnitSnapshot s : parentSnaps) parentMap.put(s.getCode(), s);

        Map<String, UnitSnapshot> currentMap = new LinkedHashMap<>();
        for (UnitSnapshot s : currentSnaps) currentMap.put(s.getCode(), s);

        // Process current units: moved, held, or created
        for (UnitSnapshot cur : currentSnaps) {
            UnitSnapshot prev = parentMap.get(cur.getCode());
            if (prev == null) {
                // New unit created this round
                changes.add(UnitChange.created(cur.getCode(), cur.getLat(), cur.getLng(), "deploy"));
            } else {
                double dlat = Math.abs(cur.getLat() - prev.getLat());
                double dlng = Math.abs(cur.getLng() - prev.getLng());
                if (dlat < 0.0001 && dlng < 0.0001) {
                    // Position unchanged — check status change
                    if (!cur.getStatus().equals(prev.getStatus())) {
                        changes.add(UnitChange.statusChange(cur.getCode(),
                            cur.getLat(), cur.getLng(), prev.getStatus(), cur.getStatus()));
                    } else {
                        changes.add(UnitChange.held(cur.getCode(), cur.getLat(), cur.getLng()));
                    }
                } else {
                    // Position changed
                    String action = inferAction(cur.getLat(), cur.getLng(),
                        prev.getLat(), prev.getLng());
                    changes.add(UnitChange.moved(cur.getCode(),
                        prev.getLat(), prev.getLng(), cur.getLat(), cur.getLng(), action));
                }
            }
        }

        // Process deleted units (in parent but not in current)
        for (UnitSnapshot prev : parentSnaps) {
            if (!currentMap.containsKey(prev.getCode())) {
                changes.add(UnitChange.deleted(prev.getCode(), prev.getLat(), prev.getLng()));
            }
        }

        return changes;
    }

    private String inferAction(double toLat, double toLng, double fromLat, double fromLng) {
        double dlat = Math.abs(toLat - fromLat);
        double dlng = Math.abs(toLng - fromLng);
        double dist = Math.sqrt(dlat * dlat + dlng * dlng);
        if (dist < 0.01) return "maneuver";
        double fromDist = haversine(fromLat, fromLng, 31.2, 121.5);
        double toDist = haversine(toLat, toLng, 31.2, 121.5);
        if (toDist < fromDist - 0.01) return "advance";
        if (toDist > fromDist + 0.01) return "retreat";
        return "advance";
    }

    /** Export UnitChanges computed from parent→child snapshot diff. */
    public String exportChangesJson(String treeId, String nodeId) {
        BranchTree tree = trees.get(treeId);
        if (tree == null) return "[]";
        BranchNode node = tree.findNode(nodeId);
        if (node == null) return "[]";
        if (node.getParentId() == null) return "[]";

        BranchNode parent = tree.findNode(node.getParentId());
        if (parent == null) return "[]";

        List<UnitChange> changes = computeUnitChanges(
            parent.getUnitSnapshots(), node.getUnitSnapshots());
        try {
            return MAPPER.writeValueAsString(changes);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Apply a node's snapshot to the live unit map.
     */
    public boolean applyNode(String treeId, String nodeId) {
        BranchTree tree = trees.get(treeId);
        if (tree == null) return false;
        BranchNode node = tree.findNode(nodeId);
        if (node == null) return false;

        for (UnitSnapshot s : node.getUnitSnapshots()) {
            Unit u = unitsManager.get(s.getCode());
            if (u != null) {
                u.setPosition(s.getLat(), s.getLng());
                u.setStatus(s.getStatus());
            } else {
                // Unit doesn't exist — create it from snapshot
                unitsManager.create(s.getCode(), s.getName(), s.getSource(),
                    s.getType(), s.getLat(), s.getLng());
                Unit created = unitsManager.get(s.getCode());
                if (created != null) {
                    created.setColor(s.getColor());
                    created.setStatus(s.getStatus());
                    created.setDescription(s.getDescription());
                }
            }
        }
        LOG.info("Applied node " + nodeId + " from tree " + treeId);
        return true;
    }

    // ---- Snapshot utilities ----

    /** Capture all current unit positions as snapshots. */
    public List<UnitSnapshot> captureSnapshot() {
        List<UnitSnapshot> list = new ArrayList<>();
        for (Unit u : unitsManager.listAll()) {
            list.add(new UnitSnapshot(u));
        }
        return list;
    }

    /** Compute movements by comparing previous snapshots to current positions. */
    private List<Movement> computeMoves(List<UnitSnapshot> previous, List<UnitSnapshot> current) {
        List<Movement> moves = new ArrayList<>();
        Map<String, UnitSnapshot> prevMap = new LinkedHashMap<>();
        for (UnitSnapshot s : previous) prevMap.put(s.getCode(), s);

        for (UnitSnapshot c : current) {
            UnitSnapshot p = prevMap.get(c.getCode());
            if (p == null) continue;
            double dlat = Math.abs(c.getLat() - p.getLat());
            double dlng = Math.abs(c.getLng() - p.getLng());
            if (dlat < 0.0001 && dlng < 0.0001) continue; // no movement
            String action = "move";
            // Heuristic: if moved > 0.1°, call it advance or retreat
            if (dlat > 0.1 || dlng > 0.1) action = "advance";
            moves.add(new Movement(c.getCode(), p.getLat(), p.getLng(),
                c.getLat(), c.getLng(), action));
        }
        return moves;
    }

    // ---- JSON export for API ----

    public String exportTreeJson(String treeId) {
        BranchTree tree = trees.get(treeId);
        if (tree == null) return "{\"error\":\"tree not found: " + esc(treeId) + "\"}";
        try {
            return MAPPER.writeValueAsString(tree);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public String exportFlatListJson(String treeId) {
        BranchTree tree = trees.get(treeId);
        if (tree == null) return "{\"error\":\"tree not found: " + esc(treeId) + "\"}";
        try {
            return MAPPER.writeValueAsString(tree.flatList());
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public String exportTreeListJson() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (BranchTree t : trees.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("nodeCount", t.countNodes());
            m.put("createdAt", t.getCreatedAt());
            list.add(m);
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Compute movement paths by diffing parent snapshots vs this node's snapshots.
     * Always accurate — derived from actual snapshot data, not saved moves.
     */
    public String exportMovesFromSnapshots(String treeId, String nodeId) {
        BranchTree tree = trees.get(treeId);
        if (tree == null) return "{\"error\":\"tree not found\"}";
        BranchNode node = tree.findNode(nodeId);
        if (node == null) return "{\"error\":\"node not found\"}";
        if (node.getParentId() == null) return "[]"; // root has no parent

        BranchNode parent = tree.findNode(node.getParentId());
        if (parent == null) return "[]";

        List<Movement> computed = computeMoves(parent.getUnitSnapshots(), node.getUnitSnapshots());
        // Also compute actions heuristically
        Map<String, UnitSnapshot> parentMap = new LinkedHashMap<>();
        for (UnitSnapshot s : parent.getUnitSnapshots()) parentMap.put(s.getCode(), s);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Movement m : computed) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", m.getCode());
            entry.put("fromLat", m.getFromLat()); entry.put("fromLng", m.getFromLng());
            entry.put("toLat", m.getToLat()); entry.put("toLng", m.getToLng());
            // Determine action by comparing parent → child
            UnitSnapshot ps = parentMap.get(m.getCode());
            String action = inferAction(ps, m);
            entry.put("action", action);
            result.add(entry);
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String inferAction(UnitSnapshot parent, Movement m) {
        if (parent == null) return "deploy";
        double dlat = Math.abs(m.getToLat() - m.getFromLat());
        double dlng = Math.abs(m.getToLng() - m.getFromLng());
        double dist = Math.sqrt(dlat * dlat + dlng * dlng);
        if (dist < 0.001) return "hold"; // < ~100m
        // Check if moving toward or away from Shanghai (31.2, 121.5) as heuristic
        double fromDist = haversine(m.getFromLat(), m.getFromLng(), 31.2, 121.5);
        double toDist = haversine(m.getToLat(), m.getToLng(), 31.2, 121.5);
        if (toDist < fromDist - 0.01) return "advance"; // moving toward Shanghai
        if (toDist > fromDist + 0.01) return "retreat";  // moving away
        return dist > 0.05 ? "advance" : "move";
    }

    private double haversine(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng/2) * Math.sin(dLng/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    /** Export unit snapshots from a specific branch node as JSON array. */
    public String exportUnitSnapshotsJson(String treeId, String nodeId) {
        BranchTree tree = trees.get(treeId);
        if (tree == null) return "[]";
        BranchNode node = tree.findNode(nodeId);
        if (node == null) return "[]";
        try {
            return MAPPER.writeValueAsString(node.getUnitSnapshots());
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Delete a node from the tree (and all its children recursively). */
    public boolean deleteNode(String treeId, String nodeId) {
        BranchTree tree = trees.get(treeId);
        if (tree == null) return false;
        BranchNode root = tree.getRoot();
        if (root == null) return false;
        if (root.getId().equals(nodeId)) return false; // can't delete root
        boolean removed = root.removeChild(nodeId);
        if (removed) { saveToDisk(); LOG.info("Deleted node " + nodeId + " from tree " + treeId); }
        return removed;
    }

    /** Update a node's metadata (name, strategy, outcome). */
    public boolean updateNode(String treeId, String nodeId, String newName, String newStrategy, String newOutcome) {
        BranchTree tree = trees.get(treeId);
        if (tree == null) return false;
        BranchNode node = tree.findNode(nodeId);
        if (node == null) return false;
        if (newName != null && !newName.isEmpty()) node.setName(newName);
        if (newStrategy != null && !newStrategy.isEmpty()) node.setStrategy(newStrategy);
        if (newOutcome != null) node.setOutcome(newOutcome);
        saveToDisk();
        return true;
    }

    /** Get a specific node's detail (snapshots, moves, etc.) */
    public String exportNodeJson(String treeId, String nodeId) {
        BranchTree tree = trees.get(treeId);
        if (tree == null) return "{\"error\":\"tree not found\"}";
        BranchNode node = tree.findNode(nodeId);
        if (node == null) return "{\"error\":\"node not found\"}";
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }
}
