package com.cna.getgoat.map.terrain;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Static HSV-based terrain classification from RGB pixel colors.
 *
 * Extracted from {@link ReliefMapLoader} so that terrain classification logic is
 * independent of any particular image-loading strategy. Both
 * {@link GeoTIFFMapLoader} (regional reads) and the legacy
 * {@link ReliefMapLoader} (full-image load) use these rules.
 */
public final class ColorClassifier {

    private static final Logger LOG = Logger.getLogger(ColorClassifier.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile List<Rule> rules;
    private static volatile List<FallbackRule> fallbacks;
    private static volatile boolean configLoaded;

    private ColorClassifier() {} // static utility

    // ---- Records ----

    public record Rule(TerrainType terrain, float hueMin, float hueMax,
                       float satMin, float satMax, float valMin, float valMax, boolean greenDom) {
        public Rule(TerrainType t, float hMin, float hMax, float sMin, float sMax,
                    float vMin, float vMax) {
            this(t, hMin, hMax, sMin, sMax, vMin, vMax, false);
        }
    }

    public record FallbackRule(TerrainType terrain, float valMax) {}

    // ---- Configuration loading ----

    public static void loadConfigFromFile(File f) throws IOException {
        rules = new ArrayList<>();
        fallbacks = new ArrayList<>();
        var root = MAPPER.readTree(f);
        for (var r : root.get("rules")) {
            rules.add(new Rule(TerrainType.valueOf(r.get("terrain").asText()),
                r.has("hue_min") ? (float) r.get("hue_min").asDouble() : 0,
                r.has("hue_max") ? (float) r.get("hue_max").asDouble() : 360,
                r.has("sat_min") ? (float) r.get("sat_min").asDouble() : 0,
                r.has("sat_max") ? (float) r.get("sat_max").asDouble() : 1,
                r.has("val_min") ? (float) r.get("val_min").asDouble() : 0,
                r.has("val_max") ? (float) r.get("val_max").asDouble() : 1,
                r.has("green_dominant") && r.get("green_dominant").asBoolean()));
        }
        if (root.has("fallback")) {
            for (var fb : root.get("fallback"))
                fallbacks.add(new FallbackRule(
                    TerrainType.valueOf(fb.get("terrain").asText()),
                    fb.has("val_max") ? (float) fb.get("val_max").asDouble() : 1));
        }
        configLoaded = true;
    }

    /**
     * Load classification rules from the configured JSON file, or build
     * built-in defaults if no config file is found.
     */
    public static void loadConfig() {
        try {
            String path = com.cna.getgoat.config.ConfigsManager.getColorsJson();
            if (path != null && new java.io.File(path).exists()) {
                try (InputStream is = new java.io.FileInputStream(path)) {
                    var root = MAPPER.readTree(is);
                    rules = new ArrayList<>();
                    fallbacks = new ArrayList<>();
                    for (var r : root.get("rules")) {
                        rules.add(new Rule(TerrainType.valueOf(r.get("terrain").asText()),
                            r.has("hue_min") ? (float) r.get("hue_min").asDouble() : 0,
                            r.has("hue_max") ? (float) r.get("hue_max").asDouble() : 360,
                            r.has("sat_min") ? (float) r.get("sat_min").asDouble() : 0,
                            r.has("sat_max") ? (float) r.get("sat_max").asDouble() : 1,
                            r.has("val_min") ? (float) r.get("val_min").asDouble() : 0,
                            r.has("val_max") ? (float) r.get("val_max").asDouble() : 1,
                            r.has("green_dominant") && r.get("green_dominant").asBoolean()));
                    }
                    if (root.has("fallback")) {
                        for (var fb : root.get("fallback"))
                            fallbacks.add(new FallbackRule(
                                TerrainType.valueOf(fb.get("terrain").asText()),
                                fb.has("val_max") ? (float) fb.get("val_max").asDouble() : 1));
                    }
                    configLoaded = true;
                    LOG.info("Loaded " + rules.size() + " terrain rules from config");
                    return;
                }
            }
        } catch (Exception e) {
            LOG.warning("Failed to load terrain color config: " + e.getMessage());
        }
        buildDefaultRules();
    }

    private static void buildDefaultRules() {
        rules = new ArrayList<>();
        fallbacks = new ArrayList<>();
        rules.add(new Rule(TerrainType.ICE, 0, 360, 0, 0.10f, 0.92f, 1));
        rules.add(new Rule(TerrainType.OCEAN, 185, 270, 0.08f, 1, 0, 0.45f));
        rules.add(new Rule(TerrainType.COASTAL_WATER, 185, 270, 0.08f, 1, 0, 1));
        rules.add(new Rule(TerrainType.DESERT, 22, 58, 0, 0.45f, 0.58f, 1));
        rules.add(new Rule(TerrainType.HIGH_MOUNTAIN, 0, 360, 0, 0.10f, 0.80f, 1));
        rules.add(new Rule(TerrainType.FOREST, 70, 170, 0, 1, 0, 0.42f, true));
        rules.add(new Rule(TerrainType.FOREST, 70, 170, 0, 1, 0, 1, true));
        rules.add(new Rule(TerrainType.PLAINS, 130, 210, 0, 0.35f, 0.50f, 0.78f));
        rules.add(new Rule(TerrainType.PLAINS, 55, 130, 0.06f, 1, 0.50f, 0.78f));
        rules.add(new Rule(TerrainType.HILLS, 30, 160, 0.04f, 1, 0.32f, 0.70f));
        rules.add(new Rule(TerrainType.MOUNTAIN, 10, 45, 0.12f, 1, 0.35f, 0.80f));
        rules.add(new Rule(TerrainType.TUNDRA, 50, 360, 0, 0.22f, 0.45f, 0.82f));
        rules.add(new Rule(TerrainType.PLATEAU, 0, 360, 0.05f, 0.35f, 0.68f, 1));
        rules.add(new Rule(TerrainType.WETLAND, 130, 210, 0, 1, 0, 0.38f));
        fallbacks.add(new FallbackRule(TerrainType.OCEAN, 0.35f));
        fallbacks.add(new FallbackRule(TerrainType.FOREST, 0.50f));
        fallbacks.add(new FallbackRule(TerrainType.HILLS, 0.63f));
        fallbacks.add(new FallbackRule(TerrainType.MOUNTAIN, 0.78f));
        fallbacks.add(new FallbackRule(TerrainType.HIGH_MOUNTAIN, 1));
        configLoaded = true;
    }

    // ---- Classification ----

    /**
     * Classify a single RGB pixel into a terrain type using HSV rules.
     */
    public static TerrainType classifyColor(int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        float[] hsv = java.awt.Color.RGBtoHSB(r, g, b, null);
        float h = hsv[0] * 360, s = hsv[1], v = hsv[2];
        ensureConfig();
        for (Rule rule : rules) {
            if (h < rule.hueMin || h > rule.hueMax) continue;
            if (s < rule.satMin || s > rule.satMax) continue;
            if (v < rule.valMin || v > rule.valMax) continue;
            if (rule.greenDom && !(g >= r && g >= b - 10)) continue;
            return rule.terrain;
        }
        for (FallbackRule fb : fallbacks) if (v <= fb.valMax) return fb.terrain;
        return TerrainType.HIGH_MOUNTAIN;
    }

    /**
     * Estimate elevation (meters) from an RGB pixel's classification.
     * This is a heuristic based on Natural Earth hypsometric tints.
     */
    public static double estimateElevation(int rgb, TerrainType t) {
        return elevationForType(t);
    }

    public static int elevationFromRgb(int rgb) {
        return elevationForType(classifyColor(rgb));
    }

    /** Categorize a pixel by broad color family (for profile/sampling). */
    public static String colorCategory(int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        float[] hsv = java.awt.Color.RGBtoHSB(r, g, b, null);
        float h = hsv[0] * 360, s = hsv[1];
        if (s < 0.1) return "gray";
        if (h < 30) return "redBrown";
        if (h < 90) return "yellowGreen";
        if (h < 150) return "green";
        if (h < 210) return "cyan";
        return "blue";
    }

    /**
     * Sample a neighbourhood around a pixel and return a 6-bucket
     * colour-category profile (percentages).
     */
    public static int[] sampleColorProfile(BufferedImage img, double lat, double lng, int sampleR) {
        int w = img.getWidth(), hh = img.getHeight();
        int cx = (int) ((lng + 180) / 360 * w), cy = (int) ((90 - lat) / 180 * hh);
        int[] buckets = new int[6];
        int total = 0;
        for (int dy = -sampleR; dy <= sampleR; dy++) {
            for (int dx = -sampleR; dx <= sampleR; dx++) {
                int sx = Math.max(0, Math.min(w - 1, cx + dx));
                int sy = Math.max(0, Math.min(hh - 1, cy + dy));
                switch (colorCategory(img.getRGB(sx, sy))) {
                    case "redBrown": buckets[0]++; break;
                    case "yellowGreen": buckets[1]++; break;
                    case "green": buckets[2]++; break;
                    case "cyan": buckets[3]++; break;
                    case "blue": buckets[4]++; break;
                    case "gray": buckets[5]++; break;
                }
                total++;
            }
        }
        if (total > 0) for (int i = 0; i < 6; i++) buckets[i] = buckets[i] * 100 / total;
        return buckets;
    }

    // ---- Elevation lookup ----

    /**
     * Representative elevation (meters) for each terrain type on the
     * Natural Earth 10m hypsometric tints.
     */
    public static int elevationForType(TerrainType t) {
        return switch (t) {
            case OCEAN          -> -3500;
            case COASTAL_WATER  -> -80;
            case PLAINS         -> 80;
            case HILLS          -> 450;
            case MOUNTAIN       -> 1600;
            case HIGH_MOUNTAIN  -> 3200;
            case PLATEAU        -> 1800;
            case DESERT         -> 200;
            case FOREST         -> 250;
            case TAIGA          -> 300;
            case TUNDRA         -> 100;
            case ICE            -> 2500;
            case WETLAND        -> 15;
            case URBAN          -> 50;
        };
    }

    // ---- Internal ----

    private static void ensureConfig() {
        if (!configLoaded) {
            synchronized (ColorClassifier.class) {
                if (!configLoaded) loadConfig();
            }
        }
    }
}
