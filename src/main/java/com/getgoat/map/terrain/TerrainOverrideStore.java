package com.getgoat.map.terrain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getgoat.map.model.GeoBounds;
import com.getgoat.map.model.TerrainType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Separate override layer for terrain corrections that survives cache regeneration.
 *
 * Each override maps a grid cell (identified by row:col) to optional terrain and/or
 * elevation overrides. These are persisted to {@code terrain_overrides.json} in the
 * workspace directory — completely independent of the {@code terrain_cache.bin} binary
 * file, so {@code cacheAll()} never destroys manual edits.
 *
 * <h3>Key design</h3>
 * <ul>
 *   <li>Row/col is the canonical key (stable across cache rebuilds).</li>
 *   <li>terrain and elevation are independently nullable — you can override just one.</li>
 *   <li>{@link #load()} validates that the stored gridCellSize matches the current
 *       configuration; mismatches cause a warning and the store remains empty.</li>
 * </ul>
 */
public class TerrainOverrideStore {

    private static final Logger LOG = Logger.getLogger(TerrainOverrideStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE_NAME = "terrain_overrides.json";

    private final Map<String, TerrainOverride> overrides = new ConcurrentHashMap<>();
    private Path workspaceDir;
    private double gridCellSize;

    // ---- Inner record ----

    /**
     * A single manual correction for a terrain cell.
     *
     * @param row        grid row (canonical key)
     * @param col        grid column (canonical key)
     * @param lat        original latitude (for human readability)
     * @param lng        original longitude (for human readability)
     * @param terrain    overridden terrain type, or null to keep base
     * @param elevation  overridden elevation in meters, or null to keep base
     * @param timestamp  epoch millis when the override was created / last modified
     */
    public record TerrainOverride(
        int row,
        int col,
        double lat,
        double lng,
        TerrainType terrain,
        Double elevation,
        long timestamp
    ) {
        /** Key used in the backing map: {@code "row:col"}. */
        public String key() {
            return row + ":" + col;
        }
    }

    // ---- Workspace lifecycle ----

    /**
     * Set the workspace directory and load any existing overrides from it.
     * Must be called before any query or mutation.
     */
    public void setWorkspace(Path dir, double cellSizeDegrees) {
        this.workspaceDir = dir;
        this.gridCellSize = cellSizeDegrees;
        load();
    }

    /** Whether a workspace has been configured. */
    public boolean isReady() {
        return workspaceDir != null;
    }

    // ---- Persistence ----

    /** Load overrides from workspace/terrain_overrides.json. */
    public synchronized void load() {
        overrides.clear();
        if (workspaceDir == null) return;
        Path file = workspaceDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            LOG.fine("No terrain_overrides.json found — starting fresh");
            return;
        }
        try {
            String json = Files.readString(file);
            var root = MAPPER.readTree(json);

            // Validate stored gridCellSize
            double storedRes = root.has("gridCellSize") ? root.get("gridCellSize").asDouble() : 0;
            if (Math.abs(storedRes - gridCellSize) > 1e-9) {
                LOG.warning("terrain_overrides.json gridCellSize " + storedRes
                    + " differs from current " + gridCellSize + " — ignoring overrides");
                return;
            }

            var arr = root.get("overrides");
            if (arr != null && arr.isArray()) {
                for (var node : arr) {
                    int row = node.get("row").asInt();
                    int col = node.get("col").asInt();
                    double lat = node.has("lat") ? node.get("lat").asDouble() : 0;
                    double lng = node.has("lng") ? node.get("lng").asDouble() : 0;
                    TerrainType terrain = null;
                    if (node.has("terrain") && !node.get("terrain").isNull()) {
                        terrain = TerrainType.fromString(node.get("terrain").asText());
                    }
                    Double elevation = node.has("elevation") && !node.get("elevation").isNull()
                        ? node.get("elevation").asDouble() : null;
                    long ts = node.has("timestamp") ? node.get("timestamp").asLong() : System.currentTimeMillis();

                    if (terrain != null || elevation != null) {
                        var ov = new TerrainOverride(row, col, lat, lng, terrain, elevation, ts);
                        overrides.put(ov.key(), ov);
                    }
                }
            }
            LOG.info("Loaded " + overrides.size() + " terrain overrides from " + FILE_NAME);
        } catch (IOException e) {
            LOG.warning("Failed to load terrain overrides: " + e.getMessage());
        }
    }

    /** Persist overrides to workspace/terrain_overrides.json. */
    public synchronized void save() {
        if (workspaceDir == null) return;
        Path file = workspaceDir.resolve(FILE_NAME);
        try {
            Files.createDirectories(workspaceDir);
            var root = MAPPER.createObjectNode();
            root.put("gridCellSize", gridCellSize);
            var arr = root.putArray("overrides");
            for (var ov : overrides.values()) {
                var obj = arr.addObject();
                obj.put("row", ov.row);
                obj.put("col", ov.col);
                obj.put("lat", ov.lat);
                obj.put("lng", ov.lng);
                if (ov.terrain != null) obj.put("terrain", ov.terrain.name());
                else obj.putNull("terrain");
                if (ov.elevation != null) obj.put("elevation", ov.elevation);
                else obj.putNull("elevation");
                obj.put("timestamp", ov.timestamp);
            }
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            LOG.fine("Saved " + overrides.size() + " terrain overrides");
        } catch (IOException e) {
            LOG.warning("Failed to save terrain overrides: " + e.getMessage());
        }
    }

    // ---- CRUD ----

    /**
     * Get the override for a specific cell, or null if none exists.
     */
    public TerrainOverride get(int row, int col) {
        return overrides.get(row + ":" + col);
    }

    /**
     * Put (create or update) an override for a cell.
     *
     * @param row       grid row
     * @param col       grid column
     * @param terrain   overridden terrain, or null to keep base
     * @param elevation overridden elevation in meters, or null to keep base
     * @param lat       latitude (stored for human reference)
     * @param lng       longitude (stored for human reference)
     * @return the stored override
     */
    public TerrainOverride put(int row, int col, TerrainType terrain, Double elevation, double lat, double lng) {
        var ov = new TerrainOverride(row, col, lat, lng, terrain, elevation, System.currentTimeMillis());
        overrides.put(ov.key(), ov);
        save();
        return ov;
    }

    /**
     * Remove the override for a specific cell.
     * @return true if an override was actually removed
     */
    public boolean remove(int row, int col) {
        boolean existed = overrides.remove(row + ":" + col) != null;
        if (existed) save();
        return existed;
    }

    /** Remove all overrides. */
    public void clear() {
        int n = overrides.size();
        overrides.clear();
        if (n > 0) save();
    }

    // ---- Query helpers ----

    /** All overrides (for enumeration). */
    public Collection<TerrainOverride> all() {
        return List.copyOf(overrides.values());
    }

    /** Overrides whose cell center falls within the given bounds. */
    public List<TerrainOverride> inBounds(GeoBounds bounds, double cellSizeDeg) {
        List<TerrainOverride> result = new ArrayList<>();
        for (var ov : overrides.values()) {
            // Approximate cell center from row/col
            double lat = -90.0 + (ov.row + 0.5) * cellSizeDeg;
            double lng = -180.0 + (ov.col + 0.5) * cellSizeDeg;
            if (lat >= bounds.getSouthLat() && lat <= bounds.getNorthLat()
                && lng >= bounds.getWestLng() && lng <= bounds.getEastLng()) {
                result.add(ov);
            }
        }
        return result;
    }

    /** Total number of overrides. */
    public int count() {
        return overrides.size();
    }
}
