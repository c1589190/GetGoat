package com.cna.getgoat.map.terrain;

import com.cna.getgoat.map.geometry.GeoBounds;
import com.cna.getgoat.map.geometry.GeoPoint;
import com.cna.getgoat.map.geometry.SphericalEngine;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Unified terrain query facade — the single entry point for all terrain data needs.
 *
 * <p>Usage:
 * <pre>{@code
 *   TerrainUnitManager tum = new TerrainUnitManager(
 *       new TerrainCacheLoader(cache),          // primary: fast runtime access
 *       GeoTIFFMapLoader.createIfAvailable(path, res)  // fine: regional pixel reads
 *   );
 *   tum.setWorkspace(workspaceDir, cellSizeDegrees);
 *
 *   TerrainCell cell = tum.getTerrainAt(31.23, 121.47);
 *   var result = tum.queryConsolidated(31.23, 121.47, 100);
 * }</pre>
 */
public class TerrainUnitManager {

    private static final Logger LOG = Logger.getLogger(TerrainUnitManager.class.getName());

    // ---- Loaders ----
    private final TerrainLoader primary;
    private final TerrainLoader fine;   // nullable

    // ---- Override layer ----
    private final TerrainOverrideStore overrideStore;

    // ---- Quick cache (lat,lng → "name|elev") ----
    private static final int QUICK_CACHE_MAX = 2048;
    private final Map<String, String> quickCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > QUICK_CACHE_MAX;
        }
    };

    // ---- Stats cache ----
    private volatile MapStats cachedStats;

    // ---- Relief thresholds ----
    private volatile int reliefElevThreshold = 500;
    private volatile int reliefRoughThreshold = 60;

    // ---- JTS factory ----
    private static final GeometryFactory GF =
        new GeometryFactory(new PrecisionModel(), 4326);

    // ---- Relief enum ----
    public enum ReliefClass { PLAINS, HILLS, PLATEAU, MOUNTAINS }

    // ---- Result records ----

    /** Consolidated single-pass radius query result. */
    public record ConsolidatedResult(
        GeoPoint center,
        double radiusKm,
        TerrainCell centerCell,
        List<TerrainCell> cellsInRadius,
        Map<String, Double> terrainProfile,     // terrain type → %
        Map<String, Double> reliefProfile,       // relief class → %
        Map<String, Double> elevBands,           // elevation band → %
        ElevationStats elevStats,
        long queryTimeMs
    ) {}

    public record ElevationStats(int min, int max, int mean, int range) {}

    public record MapStats(
        int totalCells, int landCells, int waterCells,
        int regionCount, int labelCount, int annotationCount,
        Map<String, Integer> terrainDistribution
    ) {}

    // ---- Construction ----

    /**
     * @param primary       primary terrain loader (usually {@link TerrainCacheLoader})
     * @param fine          fine-resolution loader, may be null (usually {@link GeoTIFFMapLoader})
     * @param overrideStore shared override store, may be null to create a new one
     */
    public TerrainUnitManager(TerrainLoader primary, TerrainLoader fine,
                               TerrainOverrideStore overrideStore) {
        this.primary = Objects.requireNonNull(primary);
        this.fine = fine;
        this.overrideStore = overrideStore != null ? overrideStore : new TerrainOverrideStore();
    }

    public TerrainUnitManager(TerrainLoader primary, TerrainLoader fine) {
        this(primary, fine, null);
    }

    public TerrainUnitManager(TerrainLoader primary) {
        this(primary, null, null);
    }

    public TerrainLoader getPrimaryLoader() { return primary; }
    public TerrainLoader getFineLoader()   { return fine; }
    public TerrainOverrideStore getOverrideStore() { return overrideStore; }

    public void setWorkspace(Path workspaceDir, double cellSizeDegrees) {
        overrideStore.setWorkspace(workspaceDir, cellSizeDegrees);
    }

    // ---- Relief thresholds ----

    public int getReliefElevThreshold()   { return reliefElevThreshold; }
    public int getReliefRoughThreshold()  { return reliefRoughThreshold; }
    public void setReliefThresholds(int elev, int rough) {
        this.reliefElevThreshold = elev;
        this.reliefRoughThreshold = rough;
    }

    // =========================================================================
    //  Single-cell queries
    // =========================================================================

    /** Get a full TerrainCell (with override applied) for a lat/lng point. */
    public TerrainCell getTerrainAt(double lat, double lng) {
        int row = primary.latToRow(lat);
        int col = primary.lngToCol(lng);
        if (row < 0 || row >= primary.getRows() || col < 0 || col >= primary.getCols())
            return null;

        TerrainType terrain = primary.getTerrain(row, col);
        double elev = primary.getElevation(row, col);
        TerrainCell cell = new TerrainCell(row, col, new GeoPoint(lat, lng));
        if (terrain != null) {
            cell.setTerrain(terrain);
            cell.setElevationMeters(elev);
        }
        applyOverrides(cell);
        return cell;
    }

    /** Lightweight "name|elev" lookup with LRU cache. */
    public String getTerrainQuick(double lat, double lng) {
        String key = String.format("%.3f,%.3f", lat, lng);
        return quickCache.computeIfAbsent(key, k -> {
            TerrainCell cell = getTerrainAt(lat, lng);
            if (cell == null) return "?|0";
            return cell.getTerrain().getDisplayName() + "|" + (int) cell.getElevationMeters();
        });
    }

    /** Elevation with override check. */
    public double getElevationAt(double lat, double lng) {
        if (overrideStore.isReady()) {
            int row = primary.latToRow(lat);
            int col = primary.lngToCol(lng);
            var ov = overrideStore.get(row, col);
            if (ov != null && ov.elevation() != null) return ov.elevation();
        }
        TerrainCell cell = getTerrainAt(lat, lng);
        return cell != null ? cell.getElevationMeters() : Double.NaN;
    }

    /** All cells within bounds, with overrides applied. */
    public List<TerrainCell> getTerrainInBounds(GeoBounds bounds) {
        List<TerrainCell> result = new ArrayList<>();
        int startRow = Math.max(0, primary.latToRow(bounds.getSouthLat()));
        int endRow = Math.min(primary.getRows() - 1, primary.latToRow(bounds.getNorthLat()));
        int startCol = Math.max(0, primary.lngToCol(bounds.getWestLng()));
        int endCol = Math.min(primary.getCols() - 1, primary.lngToCol(bounds.getEastLng()));
        boolean hasOverrides = overrideStore.isReady();
        for (int r = startRow; r <= endRow; r++) {
            for (int c = startCol; c <= endCol; c++) {
                int col = ((c % primary.getCols()) + primary.getCols()) % primary.getCols();
                TerrainType t = primary.getTerrain(r, col);
                double elev = primary.getElevation(r, col);
                double lat = primary.rowToLat(r);
                double lng = primary.colToLng(col);
                TerrainCell cell = new TerrainCell(r, col, new GeoPoint(lat, lng));
                if (t != null) { cell.setTerrain(t); cell.setElevationMeters(elev); }
                if (hasOverrides) applyOverrides(cell);
                result.add(cell);
            }
        }
        return result;
    }

    // =========================================================================
    //  Consolidated radius query (single pass, replaces 9 separate traversals)
    // =========================================================================

    /**
     * Query all terrain data within a radius in a single pass.
     *
     * <p>Computes in ONE bbox iteration:
     * <ul>
     *   <li>Cells within radius</li>
     *   <li>Terrain type percentages</li>
     *   <li>Relief class percentages (3×3 stddev)</li>
     *   <li>Elevation band percentages</li>
     *   <li>Elevation min/max/mean/range</li>
     * </ul>
     */
    public ConsolidatedResult queryConsolidated(double lat, double lng, double radiusKm) {
        long t0 = System.currentTimeMillis();

        // Center cell
        TerrainCell centerCell = getTerrainAt(lat, lng);

        // Bounding box in degrees
        double degLat = Math.min(90, radiusKm / 111.32);
        double degLng = Math.min(180, radiusKm / (111.32 * Math.cos(Math.toRadians(lat))));

        int startRow = Math.max(0, primary.latToRow(lat - degLat));
        int endRow   = Math.min(primary.getRows() - 1, primary.latToRow(lat + degLat));
        int startCol = Math.max(0, primary.lngToCol(lng - degLng));
        int endCol   = Math.min(primary.getCols() - 1, primary.lngToCol(lng + degLng));

        // Accumulators
        List<TerrainCell> cellsInRadius = new ArrayList<>();
        Map<TerrainType, Integer> terrainCounts = new LinkedHashMap<>();
        int[] reliefCounts = new int[ReliefClass.values().length];
        int[] bandCounts = new int[ElevationBand.values().length];
        int total = 0;
        int minElev = Integer.MAX_VALUE, maxElev = Integer.MIN_VALUE;
        long sumElev = 0;

        boolean hasOverrides = overrideStore.isReady();
        var cache = (primary instanceof TerrainCacheLoader tcl) ? tcl.getCache() : null;

        for (int r = startRow; r <= endRow; r++) {
            double clat = primary.rowToLat(r);
            for (int c = startCol; c <= endCol; c++) {
                int col = ((c % primary.getCols()) + primary.getCols()) % primary.getCols();
                double clng = primary.colToLng(col);
                if (SphericalEngine.haversineDistance(lat, lng, clat, clng) > radiusKm)
                    continue;

                TerrainType terr = primary.getTerrain(r, col);
                if (terr == null) continue;

                // Read elevation — prefer raw short from cache for speed
                int elev;
                if (cache != null) {
                    elev = cache.getElevationShort(r, col);
                } else {
                    elev = (int) primary.getElevation(r, col);
                }

                // Terrain cell
                TerrainCell cell = new TerrainCell(r, col, new GeoPoint(clat, clng));
                cell.setTerrain(terr);
                cell.setElevationMeters(elev);
                if (hasOverrides) applyOverrides(cell);
                cellsInRadius.add(cell);

                // Terrain type profile
                terrainCounts.merge(terr, 1, Integer::sum);

                // Relief class (3×3 stddev)
                ReliefClass rc = computeReliefClass(r, col, elev, cache);
                reliefCounts[rc.ordinal()]++;

                // Elevation band
                ElevationBand band = classifyElevation(elev);
                bandCounts[band.ordinal()]++;

                // Elevation stats
                if (elev < minElev) minElev = elev;
                if (elev > maxElev) maxElev = elev;
                sumElev += elev;
                total++;
            }
        }

        // Compute percentages
        Map<String, Double> terrainPct = new LinkedHashMap<>();
        Map<String, Double> reliefPct = new LinkedHashMap<>();
        Map<String, Double> bandPct = new LinkedHashMap<>();
        if (total > 0) {
            for (var e : terrainCounts.entrySet())
                terrainPct.put(e.getKey().getDisplayName(), roundPct(e.getValue(), total));
            for (ReliefClass rc : ReliefClass.values()) {
                double p = roundPct(reliefCounts[rc.ordinal()], total);
                if (p > 0) reliefPct.put(capitalize(rc.name()), p);
            }
            for (ElevationBand b : ElevationBand.values()) {
                double p = roundPct(bandCounts[b.ordinal()], total);
                if (p > 0) bandPct.put(capitalize(b.name()), p);
            }
        }

        ElevationStats elevStats;
        if (total == 0) {
            elevStats = new ElevationStats(0, 0, 0, 0);
        } else {
            elevStats = new ElevationStats(minElev, maxElev, (int)(sumElev / total), maxElev - minElev);
        }

        long elapsed = System.currentTimeMillis() - t0;
        return new ConsolidatedResult(
            new GeoPoint(lat, lng), radiusKm, centerCell,
            cellsInRadius, terrainPct, reliefPct, bandPct, elevStats, elapsed
        );
    }

    // =========================================================================
    //  Relief & elevation profiles (standalone)
    // =========================================================================

    public ReliefClass computeReliefClass(int row, int col) {
        int elev = (int) primary.getElevation(row, col);
        var cache = (primary instanceof TerrainCacheLoader tcl) ? tcl.getCache() : null;
        return computeReliefClass(row, col, elev, cache);
    }

    private ReliefClass computeReliefClass(int row, int col, int selfElev, TerrainCache cache) {
        if (!primary.isClassified(row, col)) return ReliefClass.PLAINS;

        long sum = 0, sumSq = 0;
        int n = 0;
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                int nr = row + dr, nc = col + dc;
                if (nr < 0 || nr >= primary.getRows() || nc < 0 || nc >= primary.getCols())
                    continue;
                if (!primary.isClassified(nr, nc)) continue;
                int e;
                if (cache != null) {
                    e = cache.getElevationShort(nr, nc);
                } else {
                    e = (int) primary.getElevation(nr, nc);
                }
                sum += e;
                sumSq += (long) e * e;
                n++;
            }
        }
        if (n < 3) return ReliefClass.PLAINS;

        int mean = (int) (sum / n);
        int stddev = (int) Math.sqrt(Math.max(0, sumSq / n - (long) mean * mean));

        boolean high = selfElev >= reliefElevThreshold;
        boolean rough = stddev >= reliefRoughThreshold;

        if (!high && !rough) return ReliefClass.PLAINS;
        if (!high && rough)  return ReliefClass.HILLS;
        if (high && !rough)  return ReliefClass.PLATEAU;
        return ReliefClass.MOUNTAINS;
    }

    public Map<String, Double> computeReliefProfile(double lat, double lng, double radiusKm) {
        var cache = (primary instanceof TerrainCacheLoader tcl) ? tcl.getCache() : null;
        int[] counts = new int[ReliefClass.values().length];
        int total = 0;
        double deg = radiusKm / 111.32;

        int startRow = Math.max(0, primary.latToRow(lat - deg));
        int endRow   = Math.min(primary.getRows() - 1, primary.latToRow(lat + deg));
        int startCol = Math.max(0, primary.lngToCol(lng - deg));
        int endCol   = Math.min(primary.getCols() - 1, primary.lngToCol(lng + deg));

        for (int row = startRow; row <= endRow; row++) {
            double clat = primary.rowToLat(row);
            for (int col = startCol; col <= endCol; col++) {
                int c = ((col % primary.getCols()) + primary.getCols()) % primary.getCols();
                double clng = primary.colToLng(c);
                if (SphericalEngine.haversineDistance(lat, lng, clat, clng) > radiusKm)
                    continue;
                if (!primary.isClassified(row, c)) continue;

                int elev;
                if (cache != null) elev = cache.getElevationShort(row, c);
                else elev = (int) primary.getElevation(row, c);

                ReliefClass rc = computeReliefClass(row, c, elev, cache);
                counts[rc.ordinal()]++;
                total++;
            }
        }
        Map<String, Double> result = new LinkedHashMap<>();
        if (total > 0) {
            for (ReliefClass rc : ReliefClass.values()) {
                double pct = roundPct(counts[rc.ordinal()], total);
                if (pct > 0) result.put(capitalize(rc.name()), pct);
            }
        }
        return result;
    }

    public enum ElevationBand { DEEP_WATER, SHALLOW_WATER, LOWLAND, UPLAND, HIGHLAND, ALPINE }

    public static ElevationBand classifyElevation(double meters) {
        if (meters < -500)  return ElevationBand.DEEP_WATER;
        if (meters < 0)     return ElevationBand.SHALLOW_WATER;
        if (meters < 200)   return ElevationBand.LOWLAND;
        if (meters < 800)   return ElevationBand.UPLAND;
        if (meters < 2500)  return ElevationBand.HIGHLAND;
        return ElevationBand.ALPINE;
    }

    public Map<String, Double> computeElevationBandProfile(double lat, double lng, double radiusKm) {
        int[] counts = new int[ElevationBand.values().length];
        int total = 0;
        double deg = radiusKm / 111.32;

        int startRow = Math.max(0, primary.latToRow(lat - deg));
        int endRow   = Math.min(primary.getRows() - 1, primary.latToRow(lat + deg));
        int startCol = Math.max(0, primary.lngToCol(lng - deg));
        int endCol   = Math.min(primary.getCols() - 1, primary.lngToCol(lng + deg));

        for (int r = startRow; r <= endRow; r++) {
            double clat = primary.rowToLat(r);
            for (int col = startCol; col <= endCol; col++) {
                int c = ((col % primary.getCols()) + primary.getCols()) % primary.getCols();
                double clng = primary.colToLng(c);
                if (SphericalEngine.haversineDistance(lat, lng, clat, clng) > radiusKm)
                    continue;
                int elev = (int) primary.getElevation(r, c);
                ElevationBand band = classifyElevation(elev);
                counts[band.ordinal()]++;
                total++;
            }
        }
        Map<String, Double> result = new LinkedHashMap<>();
        if (total > 0) {
            for (ElevationBand b : ElevationBand.values()) {
                double pct = roundPct(counts[b.ordinal()], total);
                if (pct > 0) result.put(capitalize(b.name()), pct);
            }
        }
        return result;
    }

    // =========================================================================
    //  Fine-resolution sampling (uses fineLoader if available)
    // =========================================================================

    public TerrainCell sampleFineAt(double lat, double lng) {
        if (fine != null && fine.isReady()) {
            int row = fine.latToRow(lat);
            int col = fine.lngToCol(lng);
            TerrainType terrain = fine.getTerrain(row, col);
            double elev = fine.getElevation(row, col);
            TerrainCell cell = new TerrainCell(row, col, new GeoPoint(lat, lng));
            if (terrain != null) { cell.setTerrain(terrain); cell.setElevationMeters(elev); }
            return cell;
        }
        return getTerrainAt(lat, lng);
    }

    public List<TerrainCell> sampleFineInGeometry(Geometry geom, double baseCellSizeDeg) {
        List<TerrainCell> result = new ArrayList<>();
        org.locationtech.jts.geom.Envelope env = geom.getEnvelopeInternal();
        double latStep = Math.max(baseCellSizeDeg, 0.05);
        for (double lat = env.getMinY(); lat <= env.getMaxY(); lat += latStep) {
            double cosLat = Math.cos(Math.toRadians(Math.min(85, Math.abs(lat))));
            double lngStep = latStep / Math.max(cosLat, 0.05);
            for (double lng = env.getMinX(); lng <= env.getMaxX(); lng += lngStep) {
                var pt = GF.createPoint(new Coordinate(lng, lat));
                if (!geom.contains(pt)) continue;

                TerrainCell cell;
                if (fine != null && fine.isReady()) {
                    int row = fine.latToRow(lat);
                    int col = fine.lngToCol(lng);
                    TerrainType terrain = fine.getTerrain(row, col);
                    double elev = fine.getElevation(row, col);
                    cell = new TerrainCell(0, 0, new GeoPoint(lat, lng));
                    if (terrain != null) { cell.setTerrain(terrain); cell.setElevationMeters(elev); }
                } else {
                    cell = getTerrainAt(lat, lng);
                    if (cell == null) {
                        cell = new TerrainCell(0, 0, new GeoPoint(lat, lng));
                        cell.setTerrain(TerrainType.OCEAN);
                        cell.setElevationMeters(-3000);
                    }
                }
                result.add(cell);
            }
        }
        return result;
    }

    // =========================================================================
    //  Stats (cached after first computation)
    // =========================================================================

    public MapStats getStats() {
        MapStats cached = this.cachedStats;
        if (cached != null) return cached;

        int land = 0, water = 0;
        Map<TerrainType, Integer> dist = new LinkedHashMap<>();
        for (int r = 0; r < primary.getRows(); r++) {
            for (int c = 0; c < primary.getCols(); c++) {
                TerrainType t = primary.getTerrain(r, c);
                if (t != null) {
                    dist.merge(t, 1, Integer::sum);
                    if (t.isWater()) water++; else land++;
                } else {
                    water++;
                }
            }
        }
        Map<String, Integer> displayDist = new LinkedHashMap<>();
        dist.forEach((k, v) -> displayDist.put(k.getDisplayName(), v));

        MapStats stats = new MapStats(
            primary.totalCells(), land, water, 0, 0, 0, displayDist
        );
        this.cachedStats = stats;
        return stats;
    }

    public void invalidateStats() { this.cachedStats = null; }

    // =========================================================================
    //  Compact cell string (for debug / HTTP /cell endpoint)
    // =========================================================================

    public String buildCellCompact(double lat, double lng) {
        int row = primary.latToRow(lat);
        int col = primary.lngToCol(lng);
        TerrainCell cell = getTerrainAt(lat, lng);
        if (cell == null) return "?";
        int elev = (int) cell.getElevationMeters();
        ReliefClass rc = computeReliefClass(row, col);
        return "E:" + elev + "|T:" + TerrainCell.terrainCode(cell.getTerrain())
            + "|R:" + rc.name().charAt(0) + rc.name().substring(1).toLowerCase()
            + "|O:" + (overrideStore.isReady() && overrideStore.get(row, col) != null ? "+" : "-");
    }

    // =========================================================================
    //  Modifiers (delegate to override store)
    // =========================================================================

    public void modifyTerrain(double lat, double lng, TerrainType newType) {
        int row = primary.latToRow(lat);
        int col = primary.lngToCol(lng);
        overrideStore.put(row, col, newType, null, lat, lng);
    }

    public void modifyTerrainRegion(Geometry boundary, TerrainType newType) {
        for (int r = 0; r < primary.getRows(); r++) {
            for (int c = 0; c < primary.getCols(); c++) {
                double lat = primary.rowToLat(r);
                double lng = primary.colToLng(c);
                var pt = GF.createPoint(new Coordinate(lng, lat));
                if (boundary.contains(pt)) {
                    overrideStore.put(r, c, newType, null, lat, lng);
                }
            }
        }
        invalidateStats();
    }

    public void modifyElevation(double lat, double lng, double newElevation) {
        int row = primary.latToRow(lat);
        int col = primary.lngToCol(lng);
        TerrainType existing = primary.getTerrain(row, col);
        overrideStore.put(row, col, existing, newElevation, lat, lng);
    }

    // =========================================================================
    //  Internal helpers
    // =========================================================================

    private TerrainCell applyOverrides(TerrainCell cell) {
        if (cell == null || !overrideStore.isReady()) return cell;
        var ov = overrideStore.get(cell.getRow(), cell.getCol());
        if (ov != null) {
            if (ov.terrain() != null) cell.setTerrain(ov.terrain());
            if (ov.elevation() != null) cell.setElevationMeters(ov.elevation());
        }
        return cell;
    }

    private static double roundPct(int count, int total) {
        return Math.round(1000.0 * count / total) / 10.0;
    }

    private static String capitalize(String s) {
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char ch : s.toCharArray()) {
            if (ch == '_') { sb.append(' '); up = true; }
            else { sb.append(up ? Character.toUpperCase(ch) : Character.toLowerCase(ch)); up = false; }
        }
        return sb.toString();
    }
}
