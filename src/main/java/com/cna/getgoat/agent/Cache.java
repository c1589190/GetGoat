package com.cna.getgoat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Maintains an OpenAI-format LLM message cache.
 * Feed CacheUnits in order to build a conversation context that can be sent to an LLM.
 */
public class Cache {
    private final List<CacheUnit> units = new ArrayList<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Cache() {}

    public Cache(List<CacheUnit> initial) {
        units.addAll(initial);
    }

    public void add(CacheUnit unit) {
        units.add(unit);
    }

    public void addAll(List<CacheUnit> batch) {
        units.addAll(batch);
    }

    /** Render all units as an OpenAI-format messages array. */
    public ArrayNode toMessages() {
        ArrayNode arr = MAPPER.createArrayNode();
        for (CacheUnit u : units) {
            arr.add(u.toJson(MAPPER));
        }
        return arr;
    }

    public int size() { return units.size(); }
    public void clear() { units.clear(); }
    public List<CacheUnit> getUnits() { return new ArrayList<>(units); }

    /** Deep clone */
    public Cache clone() {
        Cache c = new Cache();
        c.units.addAll(this.units);
        return c;
    }
}
