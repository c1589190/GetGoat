package com.cna.getgoat.map.data;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Central configuration for geographic data file paths.
 *
 * Data directory layout (under classpath geodata/ or external directory):
 *   elevation/   — GeoTIFF or raw binary elevation files
 *   coastline.geojson
 *   rivers.geojson
 *   lakes.geojson
 *   climate_zones.geojson
 *   regions/     — custom region GeoJSON files
 */
public class DataPathConfig {

    private final Path basePath;

    /**
     * Use the classpath geodata/ directory (for resources bundled in JAR).
     */
    public DataPathConfig() {
        this.basePath = null; // signals classpath loading
    }

    /**
     * Use an external data directory (for development).
     */
    public DataPathConfig(String baseDir) {
        this.basePath = Paths.get(baseDir);
    }

    /** Is the data loaded from an external directory? */
    public boolean isExternal() { return basePath != null; }

    /** Get the base path, or null for classpath. */
    public Path getBasePath() { return basePath; }

    // ---- Resource paths (classpath-relative, under geodata/) ----

    public String coastlinePath()       { return "coastline.geojson"; }
    public String riversPath()          { return "rivers.geojson"; }
    public String lakesPath()           { return "lakes.geojson"; }
    public String climateZonesPath()    { return "climate_zones.geojson"; }
    public String elevationPath()       { return "elevation/earth_elevation.tif"; }
    public String regionsDir()          { return "regions"; }

    // ---- Resolved paths ----

    /**
     * Resolve a resource to either a classpath path or an absolute file path.
     */
    public String resolve(String resourceName) {
        if (basePath != null) {
            return basePath.resolve(resourceName).toString();
        }
        return resourceName;
    }

    /**
     * Check if an external file exists.
     */
    public boolean fileExists(String resourceName) {
        if (basePath != null) {
            return basePath.resolve(resourceName).toFile().exists();
        }
        return false;
    }

    /**
     * Create the data directories if external path is set.
     */
    public void ensureDirectories() throws IOException {
        if (basePath != null) {
            File dir = basePath.toFile();
            if (!dir.exists()) dir.mkdirs();
            File elevDir = basePath.resolve("elevation").toFile();
            if (!elevDir.exists()) elevDir.mkdirs();
            File regionsDir = basePath.resolve("regions").toFile();
            if (!regionsDir.exists()) regionsDir.mkdirs();
        }
    }
}
