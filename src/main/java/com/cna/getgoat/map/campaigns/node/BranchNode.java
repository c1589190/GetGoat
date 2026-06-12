package com.cna.getgoat.map.campaigns.node;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.cna.getgoat.map.campaigns.intel.SideIntelMap;

import java.util.*;

/**
 * A single node in the branch tree. Represents one round of the scenario.
 * Each node holds unit snapshots + movement records + optional outcome text.
 */
public class BranchNode {
    private final String id;
    private String name;
    private String description;
    private int round;
    private String strategy;
    private String parentId;            // null for root
    private List<BranchNode> children;
    private List<UnitSnapshot> unitSnapshots;
    private List<Movement> moves;
    private List<UnitChange> unitChanges;
    private Map<String, List<CommanderAction>> commanderActions; // side → actions for this round
    private Map<String, SideIntelMap> sideIntelMaps;    // side → intel snapshot after this round
    private String outcome;
    private long createdAt;

    @JsonCreator
    public BranchNode(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("round") int round,
            @JsonProperty("strategy") String strategy,
            @JsonProperty("parentId") String parentId,
            @JsonProperty("children") List<BranchNode> children,
            @JsonProperty("unitSnapshots") List<UnitSnapshot> unitSnapshots,
            @JsonProperty("moves") List<Movement> moves,
            @JsonProperty("unitChanges") List<UnitChange> unitChanges,
            @JsonProperty("commanderActions") Map<String, List<CommanderAction>> commanderActions,
            @JsonProperty("sideIntelMaps") Map<String, SideIntelMap> sideIntelMaps,
            @JsonProperty("outcome") String outcome,
            @JsonProperty("createdAt") long createdAt) {
        this.id = id;
        this.name = name != null ? name : "";
        this.description = description != null ? description : "";
        this.round = round;
        this.strategy = strategy != null ? strategy : "historical";
        this.parentId = parentId;
        this.children = children != null ? new ArrayList<>(children) : new ArrayList<>();
        this.unitSnapshots = unitSnapshots != null ? new ArrayList<>(unitSnapshots) : new ArrayList<>();
        this.moves = moves != null ? new ArrayList<>(moves) : new ArrayList<>();
        this.unitChanges = unitChanges != null ? new ArrayList<>(unitChanges) : new ArrayList<>();
        this.commanderActions = commanderActions != null ? new LinkedHashMap<>(commanderActions) : new LinkedHashMap<>();
        this.sideIntelMaps = sideIntelMaps != null ? new LinkedHashMap<>(sideIntelMaps) : new LinkedHashMap<>();
        this.outcome = outcome != null ? outcome : "";
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
    }

    /** Factory: create a root node from current unit state. */
    public static BranchNode createRoot(String name, List<UnitSnapshot> snapshots) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return new BranchNode(id, name, "Initial deployment", 0, "initial",
            null, new ArrayList<>(), snapshots, new ArrayList<>(),
            new ArrayList<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
            "Starting positions", System.currentTimeMillis());
    }

    /** Factory: create a child node for a new round. */
    public static BranchNode createRound(String parentId, String name, int round,
                                          String strategy, List<UnitSnapshot> snapshots,
                                          List<Movement> moves, List<UnitChange> unitChanges,
                                          String outcome) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return new BranchNode(id, name, "", round, strategy,
            parentId, new ArrayList<>(), snapshots, moves, unitChanges,
            new LinkedHashMap<>(), new LinkedHashMap<>(), outcome, System.currentTimeMillis());
    }

    // ---- Getters / Setters ----

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { if (n != null) this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { if (d != null) this.description = d; }
    public int getRound() { return round; }
    public void setRound(int r) { this.round = r; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String s) { if (s != null) this.strategy = s; }
    public String getParentId() { return parentId; }
    public List<BranchNode> getChildren() { return children; }
    public List<UnitSnapshot> getUnitSnapshots() { return unitSnapshots; }
    public List<Movement> getMoves() { return moves; }
    public List<UnitChange> getUnitChanges() { return unitChanges; }
    public Map<String, List<CommanderAction>> getCommanderActions() { return commanderActions; }
    public Map<String, SideIntelMap> getSideIntelMaps() { return sideIntelMaps; }
    public SideIntelMap getSideIntelMap(String side) { return sideIntelMaps.get(side); }
    public void putSideIntelMap(String side, SideIntelMap m) { sideIntelMaps.put(side, m); }
    public String getOutcome() { return outcome; }
    public void setOutcome(String o) { if (o != null) this.outcome = o; }
    public long getCreatedAt() { return createdAt; }

    /** Get the latest commander action for a side at this node. */
    public CommanderAction getCommanderAction(String side) {
        List<CommanderAction> actions = commanderActions.get(side);
        return actions != null && !actions.isEmpty() ? actions.get(actions.size() - 1) : null;
    }

    /** Add or update a commander action for a side. */
    public void putCommanderAction(String side, CommanderAction action) {
        commanderActions.computeIfAbsent(side, k -> new ArrayList<>()).add(action);
    }

    /** Add a child node. */
    public void addChild(BranchNode child) { children.add(child); }

    /** Find a node by ID, recursively. */
    @JsonIgnore
    public BranchNode find(String nodeId) {
        if (id.equals(nodeId)) return this;
        for (BranchNode child : children) {
            BranchNode found = child.find(nodeId);
            if (found != null) return found;
        }
        return null;
    }

    /** Remove a child by ID. */
    @JsonIgnore
    public boolean removeChild(String nodeId) {
        return children.removeIf(c -> c.getId().equals(nodeId) || c.removeChild(nodeId));
    }

    @Override
    public String toString() {
        return "BranchNode[" + id + " r" + round + " \"" + name + "\"]";
    }
}
