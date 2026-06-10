package com.getgoat.map;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Central configuration for all map parameters.
 * Loads from config.properties in project root, falls back to defaults.
 */
public class ConfigManager {

    private static final Properties props = new Properties();

    static {
        // Defaults
        props.setProperty("grid.cellSizeDegrees", "0.03125");
        props.setProperty("terrain.reliefTiff", "src/main/resources/geodata/elevation/HYP_HR_SR_OB_DR.tif");
        props.setProperty("terrain.fallbackTiff", "src/main/resources/geodata/elevation/HYP_50M_SR_W.tif");
        props.setProperty("terrain.colorsJson", "src/main/resources/geodata/terrain-colors.json");
        props.setProperty("roads.shapefile", "roads/ne_10m_roads.shp");
        props.setProperty("rivers.shapefile", "rivers/ne_10m_rivers_lake_centerlines.shp");
        props.setProperty("admin.provincesJson", "ne_10m_admin_1_states_provinces.json");
        props.setProperty("admin.citiesJson", "ne_10m_populated_places.json");
        props.setProperty("relief.elevThreshold", "500");
        props.setProperty("relief.roughThreshold", "60");

        // Load from file
        for (String path : new String[]{"config.properties", "../config.properties", "src/main/resources/config.properties"}) {
            try (InputStream is = new FileInputStream(path)) { props.load(is); break; }
            catch (IOException ignored) {}
        }
    }

    public static double getGridCellSize()          { return Double.parseDouble(props.getProperty("grid.cellSizeDegrees")); }
    public static String getReliefTiff()            { return findFile(props.getProperty("terrain.reliefTiff")); }
    public static String getFallbackTiff()          { return findFile(props.getProperty("terrain.fallbackTiff")); }
    public static String getColorsJson()            { return findFile(props.getProperty("terrain.colorsJson")); }
    public static String getRoadsShapefile()        { return findFile(props.getProperty("roads.shapefile")); }
    public static String getRiversShapefile()       { return findFile(props.getProperty("rivers.shapefile")); }
    public static String getProvincesJson()         { return findFile(props.getProperty("admin.provincesJson")); }
    public static String getCitiesJson()            { return findFile(props.getProperty("admin.citiesJson")); }
    public static double getDisplayFineRes()        { return getGridCellSize(); }
    public static int getReliefElevThreshold()       { return Integer.parseInt(props.getProperty("relief.elevThreshold", "500")); }
    public static int getReliefRoughThreshold()      { return Integer.parseInt(props.getProperty("relief.roughThreshold", "60")); }

    // ---- LLM config (global defaults, overridable per-commander) ----

    public static String getLlmProvider()    { return props.getProperty("llm.provider", "anthropic"); }
    public static String getLlmModel()       { return props.getProperty("llm.model", "claude-sonnet-4-6"); }
    public static String getLlmEndpoint()    { return props.getProperty("llm.endpoint", ""); }
    public static String getLlmApiKey()      {
        String key = props.getProperty("llm.apiKey", "");
        if (key.startsWith("env:")) {
            String v = System.getenv(key.substring(4));
            return v != null ? v : "";
        }
        return key;
    }
    public static int getLlmMaxTokens()      { return Integer.parseInt(props.getProperty("llm.maxTokens", "4096")); }
    public static double getSimRoundHours()   { return Double.parseDouble(props.getProperty("sim.roundHours", "24")); }


    public static String getProperty(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    private static String findFile(String relPath) {
        for (String base : new String[]{"", "../", "src/main/resources/geodata/", "geodata/",
                "../../src/main/resources/geodata/", "../../../src/main/resources/geodata/",
                "../../../../src/main/resources/geodata/"}) {
            Path p = Paths.get(base + relPath);
            if (Files.exists(p)) return p.toString();
        }
        return relPath; // return as-is, let caller handle missing
    }
}
