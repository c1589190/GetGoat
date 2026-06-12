package com.cna.getgoat.map.campaigns.node;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;

/**
 * A container for one branch tree — holds the root node and metadata.
 */
public class BranchTree {
    private final String id;
    private String name;
    private long createdAt;
    private BranchNode root;

    @JsonCreator
    public BranchTree(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("createdAt") long createdAt,
            @JsonProperty("root") BranchNode root) {
        this.id = id;
        this.name = name != null ? name : "Untitled";
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
        this.root = root;
    }

    public static BranchTree create(String name, List<UnitSnapshot> snapshots) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        BranchNode root = BranchNode.createRoot(name + " — 初始部署", snapshots);
        return new BranchTree(id, name, System.currentTimeMillis(), root);
    }

    // ---- Getters ----
    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { if (n != null) this.name = n; }
    public long getCreatedAt() { return createdAt; }
    public BranchNode getRoot() { return root; }

    /** Find a node anywhere in the tree. */
    @JsonIgnore
    public BranchNode findNode(String nodeId) {
        return root != null ? root.find(nodeId) : null;
    }

    /** Count total nodes in the tree. */
    @JsonIgnore
    public int countNodes() {
        return countRecursive(root);
    }

    private int countRecursive(BranchNode n) {
        if (n == null) return 0;
        int c = 1;
        for (BranchNode child : n.getChildren()) c += countRecursive(child);
        return c;
    }

    /** Build a flat list of [nodeId -> {id, name, round, parentId, strategy}] for the frontend. */
    @JsonIgnore
    public List<Map<String, Object>> flatList() {
        List<Map<String, Object>> list = new ArrayList<>();
        collectFlat(root, list);
        return list;
    }

    private void collectFlat(BranchNode n, List<Map<String, Object>> out) {
        if (n == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("name", n.getName());
        m.put("round", n.getRound());
        m.put("parentId", n.getParentId());
        m.put("strategy", n.getStrategy());
        m.put("outcome", n.getOutcome());
        List<String> childIds = new ArrayList<>();
        for (BranchNode c : n.getChildren()) childIds.add(c.getId());
        m.put("children", childIds);
        out.add(m);
        for (BranchNode c : n.getChildren()) collectFlat(c, out);
    }

    @Override
    public String toString() {
        return "BranchTree[" + id + " \"" + name + "\" nodes=" + countNodes() + "]";
    }
}
