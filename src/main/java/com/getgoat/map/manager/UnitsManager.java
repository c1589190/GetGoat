package com.getgoat.map.manager;

import com.getgoat.map.model.Unit;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Manages all Units on the map — CRUD, movement, filtering by source.
 */
public class UnitsManager {

    private static final Logger LOG = Logger.getLogger(UnitsManager.class.getName());
    private final Map<String, Unit> units = new ConcurrentHashMap<>();

    // ---- Create ----

    public Unit create(String code, String name, String source, String type,
                       double lat, double lng) {
        if (units.containsKey(code))
            throw new IllegalArgumentException("Unit code already exists: " + code);
        Unit u = new Unit(code, name, source, type, lat, lng);
        units.put(code, u);
        LOG.info("Created " + u);
        return u;
    }

    // ---- Read ----

    public Unit get(String code) {
        return units.get(code);
    }

    public List<Unit> listAll() {
        return new ArrayList<>(units.values());
    }

    public List<Unit> listBySource(String source) {
        return units.values().stream()
            .filter(u -> source.equalsIgnoreCase(u.getSource()))
            .collect(Collectors.toList());
    }

    public List<String> listSources() {
        return units.values().stream()
            .map(Unit::getSource)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    public int count() { return units.size(); }

    /** Exact match by status. */
    public List<Unit> listByStatus(String status) {
        return units.values().stream()
            .filter(u -> status.equalsIgnoreCase(u.getStatus()))
            .collect(Collectors.toList());
    }

    /** Exact match by name. */
    public List<Unit> listByName(String name) {
        return units.values().stream()
            .filter(u -> name.equalsIgnoreCase(u.getName()))
            .collect(Collectors.toList());
    }

    /**
     * Fuzzy keyword search across source, status, and name.
     * Each keyword (space-separated) must appear in at least one of the three fields.
     * Case-insensitive substring match.
     */
    public List<Unit> search(String query) {
        if (query == null || query.isBlank()) return listAll();
        String[] keywords = query.toLowerCase().split("\\s+");
        return units.values().stream()
            .filter(u -> {
                String haystack = (u.getSource() + " " + u.getStatus() + " " + u.getName()).toLowerCase();
                for (String kw : keywords) {
                    if (!haystack.contains(kw)) return false;
                }
                return true;
            })
            .collect(Collectors.toList());
    }

    // ---- Update ----

    public Unit update(String code, String name, String description,
                       String source, String type, String color) {
        Unit u = units.get(code);
        if (u == null) throw new IllegalArgumentException("Unit not found: " + code);
        u.setName(name);
        u.setDescription(description);
        u.setSource(source);
        u.setType(type);
        u.setColor(color);
        return u;
    }

    /** Move unit to new position. */
    public Unit move(String code, double lat, double lng) {
        Unit u = units.get(code);
        if (u == null) throw new IllegalArgumentException("Unit not found: " + code);
        u.setPosition(lat, lng);
        LOG.info("Moved " + code + " → (" + lat + "," + lng + ")");
        return u;
    }

    // ---- Delete ----

    public boolean delete(String code) {
        Unit removed = units.remove(code);
        if (removed != null) LOG.info("Deleted " + removed);
        return removed != null;
    }

    public void deleteAll() {
        int n = units.size();
        units.clear();
        LOG.info("Deleted all " + n + " units");
    }

    // ---- JSON export (for API) ----

    public String exportJson(List<Unit> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Unit u : list) {
            if (!first) sb.append(",");
            first = false;
            sb.append(unitJson(u));
        }
        sb.append("]");
        return sb.toString();
    }

    public String exportAllJson() { return exportJson(listAll()); }

    public String exportOneJson(String code) {
        Unit u = get(code);
        if (u == null) return "{\"error\":\"not found: " + esc(code) + "\"}";
        return unitJson(u);
    }

    private static String unitJson(Unit u) {
        return String.format(java.util.Locale.US,
            "{\"code\":\"%s\",\"name\":\"%s\",\"description\":\"%s\"," +
            "\"source\":\"%s\",\"status\":\"%s\",\"type\":\"%s\",\"color\":\"%s\"," +
            "\"lat\":%.6f,\"lng\":%.6f,\"created\":%d}",
            esc(u.getCode()), esc(u.getName()), esc(u.getDescription()),
            esc(u.getSource()), esc(u.getStatus()), esc(u.getType()), esc(u.getColor()),
            u.getLat(), u.getLng(), u.getCreatedAt());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }
}
