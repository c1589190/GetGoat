package com.getgoat.map.manager;

import com.getgoat.map.ConfigManager;
import com.getgoat.map.data.AdminDivisionLoader;
import com.getgoat.map.data.DataPathConfig;
import com.getgoat.map.data.GeoJsonWriter;
import com.getgoat.map.geometry.SphericalEngine;
import com.getgoat.map.model.*;
import com.getgoat.map.terrain.TerrainCache;
import com.getgoat.map.terrain.TerrainGenerator;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Central manager for all map operations.
 *
 * This is THE entry point for interacting with the game map. It owns:
 *   - TerrainGrid (the raster terrain data)
 *   - Regions (named polygonal areas)
 *   - RegionLabels (text labels on the map)
 *   - Annotations (point/line/polygon markers)
 *
 * All queries and mutations go through this class. The API is designed so that
 * an LLM can call these methods via function-calling with minimal adapter code.
 */
public class MapManager {

    private static final Logger LOG = Logger.getLogger(MapManager.class.getName());

    // ---- Owned data ----
    private volatile TerrainGrid terrainGrid;
    private final Map<String, Region> regions;
    private final Map<String, RegionLabel> labels;
    private final Map<String, Annotation> annotations;

    // ---- Subsystems ----
    private final TerrainGenerator terrainGenerator;
    private final GeoJsonWriter geoJsonWriter;
    private final DataPathConfig dataPath;
    private final RoadNetwork roadNetwork;
    private final RiverNetwork riverNetwork;
    private final AdminDivisionLoader adminDivisions;

    // ---- Spatial index (simple: rebuild on mutation) ----
    private org.locationtech.jts.index.strtree.STRtree spatialIndex;

    // ---- Query cache (LRU, max 256 entries) ----
    private static final int CACHE_MAX = 256;
    private final Map<String, RadiusQueryResult> queryCache =
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RadiusQueryResult> eldest) {
                return size() > CACHE_MAX;
            }
        };

    public MapManager() {
        this.regions = new ConcurrentHashMap<>();
        this.labels = new ConcurrentHashMap<>();
        this.annotations = new ConcurrentHashMap<>();
        this.terrainGenerator = new TerrainGenerator();
        this.geoJsonWriter = new GeoJsonWriter();
        this.dataPath = new DataPathConfig();
        this.roadNetwork = new RoadNetwork();
        this.riverNetwork = new RiverNetwork();
        this.adminDivisions = new AdminDivisionLoader();
        this.spatialIndex = null;
        this.reliefElevThreshold = ConfigManager.getReliefElevThreshold();
        this.reliefRoughThreshold = ConfigManager.getReliefRoughThreshold();
    }

    public MapManager(DataPathConfig dataPath) {
        this();
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    /**
     * Load all map data: terrain + regions + annotations.
     * Call once at application startup.
     */
    public void initialize(double cellSizeDegrees) {
        LOG.info("Initializing MapManager...");
        this.terrainGrid = terrainGenerator.generate(cellSizeDegrees, null);
        // Load admin divisions (provinces + cities)
        try {
            // Try multiple paths — Maven exec:java may have different CWD
            String provPath = com.getgoat.map.ConfigManager.getProvincesJson();
            String cityPath = com.getgoat.map.ConfigManager.getCitiesJson();
            if (new java.io.File(provPath).exists()) adminDivisions.loadProvinces(provPath);
            if (new java.io.File(cityPath).exists()) adminDivisions.loadCities(cityPath);
        } catch (Exception e) {
            LOG.warning("Admin divisions not loaded: " + e.getMessage());
        }

        LOG.info("MapManager initialized with " + terrainGrid
            + (roadNetwork.isAvailable() ? " + lazy road network" : "")
            + (adminDivisions.isLoaded() ? " + " + adminDivisions.getProvinces().size() + " provinces" : ""));
    }

    /**
     * Reload the terrain grid from scratch.
     */
    public void reloadTerrain(double cellSizeDegrees) {
        this.terrainGrid = terrainGenerator.generate(cellSizeDegrees, null);
        rebuildSpatialIndex();
    }

    // =========================================================================
    // Terrain queries
    // =========================================================================

    public TerrainGrid getTerrainGrid() {
        return terrainGrid;
    }

    /** Lightweight terrain name cache — avoids TerrainCell allocations for repeated lookups. */
    private final java.util.concurrent.ConcurrentHashMap<String, String> terrainQuickCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Quick terrain+elevation lookup without TerrainCell allocation. Returns "name|elev". */
    public String getTerrainQuick(double lat, double lng) {
        String key = String.format("%.3f,%.3f", lat, lng);
        return terrainQuickCache.computeIfAbsent(key, k -> {
            TerrainCell cell = getTerrainAt(lat, lng);
            if (cell == null) return "?|0";
            return cell.getTerrain().getDisplayName() + "|" + (int)cell.getElevationMeters();
        });
    }

    public TerrainCell getTerrainAt(double lat, double lng) {
        checkInitialized();
        return terrainGrid.getCellAt(lat, lng);
    }

    public TerrainCell getTerrainAt(GeoPoint point) {
        return getTerrainAt(point.getLatitude(), point.getLongitude());
    }

    public double getElevationAt(double lat, double lng) {
        TerrainCell cell = getTerrainAt(lat, lng);
        return cell != null ? cell.getElevationMeters() : Double.NaN;
    }

    public List<TerrainCell> getTerrainInBounds(GeoBounds bounds) {
        checkInitialized();
        return terrainGrid.getCellsInBounds(bounds);
    }

    public List<TerrainCell> getTerrainByType(TerrainType type) {
        checkInitialized();
        return terrainGrid.getCellsByType(type);
    }

    // =========================================================================
    // Radius query — the main LLM-facing lookup
    // =========================================================================

    /**
     * Query everything within a given radius of a point.
     *
     * Returns terrain cells, regions, annotations, and labels all in one result.
     * Results are LRU-cached by (lat,lng,radius) key.
     *
     * @param lat       center latitude
     * @param lng       center longitude
     * @param radiusKm  search radius in kilometers
     * @return RadiusQueryResult containing all features within the radius
     */
    public RadiusQueryResult queryRadius(double lat, double lng, double radiusKm) {
        checkInitialized();
        long t0 = System.currentTimeMillis();

        // Cache key
        String cacheKey = String.format("%.3f,%.3f:%.0f", lat, lng, radiusKm);
        RadiusQueryResult cached = queryCache.get(cacheKey);
        if (cached != null) {
            LOG.fine("Cache hit: " + cacheKey);
            return cached;
        }

        GeoPoint center = new GeoPoint(lat, lng);

        // 1. Compute bounding box in degrees (approximate)
        // 1° lat ≈ 111.32 km; 1° lng ≈ 111.32 * cos(lat) km
        double degLat = radiusKm / 111.32;
        double degLng = radiusKm / (111.32 * Math.cos(Math.toRadians(lat)));
        // Clamp to reasonable limits
        degLat = Math.min(degLat, 90);
        degLng = Math.min(degLng, 180);

        double south = Math.max(-90, lat - degLat);
        double north = Math.min(90, lat + degLat);
        double west = lng - degLng;
        double east = lng + degLng;
        // Handle longitude wrap
        GeoBounds bbox = new GeoBounds(south, north, west, east);

        // 2. Find center cell
        TerrainCell centerCell = terrainGrid.getCellAt(lat, lng);

        // 3. Find all cells in bbox, then filter by exact distance
        List<TerrainCell> bboxCells = terrainGrid.getCellsInBounds(bbox);
        List<TerrainCell> cellsInRadius = new ArrayList<>();
        for (TerrainCell cell : bboxCells) {
            double dist = SphericalEngine.haversineDistance(
                lat, lng,
                cell.getCenter().getLatitude(), cell.getCenter().getLongitude());
            if (dist <= radiusKm) {
                cellsInRadius.add(cell);
            }
        }

        // 4. Find regions whose boundary intersects the radius
        List<Region> regionsInRadius = new ArrayList<>();
        // Build a JTS point for center, and a buffer polygon for radius
        org.locationtech.jts.geom.GeometryFactory gf =
            new org.locationtech.jts.geom.GeometryFactory();
        Point jtsCenter = gf.createPoint(
            new org.locationtech.jts.geom.Coordinate(lng, lat));
        // Buffer by radius in degrees (approximate but correct for intersection check)
        double bufferDeg = radiusKm / 111.32;
        Geometry searchArea = jtsCenter.buffer(bufferDeg);

        for (Region region : regions.values()) {
            if (region.getBoundary().intersects(searchArea)) {
                regionsInRadius.add(region);
            }
        }

        // 5. Find annotations within radius
        List<Annotation> annotationsInRadius = new ArrayList<>();
        for (Annotation ann : annotations.values()) {
            GeoPoint annCenter = centroidOf(ann.getGeometry());
            double dist = SphericalEngine.haversineDistance(lat, lng,
                annCenter.getLatitude(), annCenter.getLongitude());
            if (dist <= radiusKm) {
                annotationsInRadius.add(ann);
            }
        }

        // 6. Find labels within radius
        List<RegionLabel> labelsInRadius = new ArrayList<>();
        for (RegionLabel l : labels.values()) {
            double dist = SphericalEngine.haversineDistance(lat, lng,
                l.getPosition().getLatitude(), l.getPosition().getLongitude());
            if (dist <= radiusKm) {
                labelsInRadius.add(l);
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        RadiusQueryResult result = new RadiusQueryResult(
            center, radiusKm, centerCell,
            cellsInRadius, regionsInRadius, annotationsInRadius, labelsInRadius,
            elapsed);

        // Cache
        queryCache.put(cacheKey, result);
        LOG.info("Radius query: " + result);
        return result;
    }

    // =========================================================================
    // Admin division queries
    // =========================================================================

    /**
     * Full province info: metadata + all terrain cells it covers + cities within.
     */
    public String exportProvinceWithCells(double lat, double lng, double fineRes) {
        if (!adminDivisions.isLoaded()) return "{\"error\":\"no admin data loaded\"}";

        AdminDivision province = adminDivisions.findProvinceAt(lat, lng);
        if (province == null) return "{\"found\":false}";

        // Terrain cells covered by this province
        List<TerrainCell> cells = findCellsInGeometry(province.getBoundary(), fineRes);

        // Cities within this province
        List<AdminDivision> provinceCities = new ArrayList<>();
        GeoPoint center = province.getCenter();
        for (var city : adminDivisions.getCities()) {
            if (province.getBoundary().contains(
                GF.createPoint(new org.locationtech.jts.geom.Coordinate(
                    city.getCenter().getLongitude(), city.getCenter().getLatitude())))) {
                provinceCities.add(city);
            }
        }

        // Build river cell lookup for province cells
        Set<String> riverCellKeys = new HashSet<>();
        if (riverNetwork.isAvailable()) {
            riverNetwork.ensureRegion(center.getLatitude(), center.getLongitude(),
                Math.max(province.getBoundary().getEnvelopeInternal().getHeight() * 111, 200));
            for (var river : riverNetwork.findInRadius(center.getLatitude(), center.getLongitude(),
                Math.max(province.getBoundary().getEnvelopeInternal().getHeight() * 111, 200))) {
                for (org.locationtech.jts.geom.Coordinate rc : river.getCoordinates()) {
                    int rr = (int) Math.round((90.0 - rc.y) / fineRes);
                    int cc = (int) Math.round((rc.x + 180.0) / fineRes);
                    riverCellKeys.add(rr + ":" + cc);
                }
            }
        }

        // Build JSON
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"province\":").append(adminDivisions.exportProvinceGeoJson(province)).append(",");
        sb.append("\"center\":{\"lat\":").append(center.getLatitude())
          .append(",\"lng\":").append(center.getLongitude()).append("},");
        sb.append("\"cells\":[");
        boolean first = true;
        for (TerrainCell cell : cells) {
            if (!first) sb.append(",");
            first = false;
            double clat = cell.getCenter().getLatitude();
            double clng = cell.getCenter().getLongitude();
            boolean hasRiver = riverCellKeys.contains(
                (int)Math.round((90.0-clat)/fineRes) + ":" + (int)Math.round((clng+180.0)/fineRes));
            sb.append(String.format(Locale.US,
                "{\"lat\":%.4f,\"lng\":%.4f,\"terrain\":\"%s\",\"elevation\":%.0f,\"color\":\"%s\",\"hasRiver\":%b}",
                clat, clng, cell.getTerrain().getDisplayName(),
                cell.getElevationMeters(), cell.getColorHex(), hasRiver));
        }
        sb.append("],\"cellCount\":").append(cells.size());
        sb.append(",\"cities\":[");
        first = true;
        for (var c : provinceCities) {
            if (!first) sb.append(",");
            first = false;
            sb.append(String.format("{\"name\":\"%s\",\"lat\":%.4f,\"lng\":%.4f,\"population\":%d}",
                esc(c.getName()), c.getCenter().getLatitude(), c.getCenter().getLongitude(),
                c.getPopulation()));
        }
        sb.append("],\"cityCount\":").append(provinceCities.size());
        sb.append("}");
        return sb.toString();
    }

    /** Find all terrain cells within a geometry using equal-area sampling.
     *  Longitude step is scaled by 1/cos(lat) so each cell covers ~same km². */
    private List<TerrainCell> findCellsInGeometry(Geometry geom, double baseCellSizeDeg) {
        var reliefImage = terrainGenerator.getReliefImage();
        double imgW = reliefImage != null ? reliefImage.getWidth() : 0;
        double imgH = reliefImage != null ? reliefImage.getHeight() : 0;

        List<TerrainCell> result = new ArrayList<>();
        org.locationtech.jts.geom.Envelope env = geom.getEnvelopeInternal();
        // Limit max step to avoid infinite loop near poles
        double latStep = Math.max(baseCellSizeDeg, 0.05);
        for (double lat = env.getMinY(); lat <= env.getMaxY(); lat += latStep) {
            // Equal-area: longitude step scales with 1/cos(lat)
            double cosLat = Math.cos(Math.toRadians(Math.min(85, Math.abs(lat))));
            double lngStep = latStep / Math.max(cosLat, 0.05);
            for (double lng = env.getMinX(); lng <= env.getMaxX(); lng += lngStep) {
                org.locationtech.jts.geom.Point pt = GF.createPoint(
                    new org.locationtech.jts.geom.Coordinate(lng, lat));
                if (!geom.contains(pt)) continue;

                TerrainType terrain;
                double elevation;
                if (reliefImage != null) {
                    int px = (int)((lng + 180.0) / 360.0 * imgW);
                    int py = (int)((90.0 - lat) / 180.0 * imgH);
                    px = Math.max(0, Math.min((int)imgW - 1, px));
                    py = Math.max(0, Math.min((int)imgH - 1, py));
                    int rgb = reliefImage.getRGB(px, py);
                    terrain = com.getgoat.map.terrain.ReliefMapLoader.classifyColor(rgb);
                    elevation = com.getgoat.map.terrain.ReliefMapLoader.elevationFromRgb(rgb);
                } else {
                    TerrainCell c = terrainGrid.getCellAt(lat, lng);
                    terrain = c != null ? c.getTerrain() : TerrainType.OCEAN;
                    elevation = c != null ? c.getElevationMeters() : -3000;
                }
                TerrainCell cell = new TerrainCell(0, 0, new GeoPoint(lat, lng));
                cell.setTerrain(terrain);
                cell.setElevationMeters(elevation);
                result.add(cell);
            }
        }
        return result;
    }

    private static final org.locationtech.jts.geom.GeometryFactory GF =
        new org.locationtech.jts.geom.GeometryFactory(new org.locationtech.jts.geom.PrecisionModel(), 4326);

    /**
     * Debug: show the full classification pipeline for a single point.
     */
    public String debugClassificationPipeline(double lat, double lng) {
        var img = terrainGenerator.getReliefImage();
        if (img == null) return "{\"error\":\"no relief image\"}";
        int w = img.getWidth(), hh = img.getHeight();
        int px = (int)((lng+180)/360*w), py = (int)((90-lat)/180*hh);
        px = Math.max(0,Math.min(w-1,px)); py = Math.max(0,Math.min(hh-1,py));

        // Step 1: Center pixel
        int rgb = img.getRGB(px, py);
        int r=(rgb>>16)&0xff,g=(rgb>>8)&0xff,b=rgb&0xff;
        float[] hsv = java.awt.Color.RGBtoHSB(r,g,b,null);
        TerrainType base = com.getgoat.map.terrain.ReliefMapLoader.elevationFromRgb(rgb) > 0 ? com.getgoat.map.model.TerrainType.PLAINS : com.getgoat.map.model.TerrainType.OCEAN;
        double elev = com.getgoat.map.terrain.ReliefMapLoader.elevationFromRgb(rgb);

        // Step 2: Neighborhood color profile (6×6 samples)
        int sampleR = Math.max(3, (int)(w/(360.0/terrainGrid.getCellSizeDegrees()))/3);
        int yg=0,rb2=0,gr2=0,cy2=0,bl2=0,gy=0,total=0;
        for(int dy=-sampleR;dy<=sampleR;dy++)
            for(int dx=-sampleR;dx<=sampleR;dx++){
                int sx=Math.max(0,Math.min(w-1,px+dx)),sy=Math.max(0,Math.min(hh-1,py+dy));
                int rgb2=img.getRGB(sx,sy);
                int r2=(rgb2>>16)&0xff,g2=(rgb2>>8)&0xff,b2=rgb2&0xff;
                float[] hsv2=java.awt.Color.RGBtoHSB(r2,g2,b2,null);
                float h2=hsv2[0]*360,s2=hsv2[1];
                if(s2<0.1)gy++;else if(h2<30)rb2++;else if(h2<90)yg++;else if(h2<150)gr2++;else if(h2<210)cy2++;else bl2++;
                total++;
            }

        // Step 3: Read upgrade rules
        String rulesStr = "{}";
        double ygR=999,rbR=999,gyR=999,grR=999,blR=999;
        try {
            java.io.File cfg = new java.io.File("terrain-colors.json");
            if(!cfg.exists())cfg=new java.io.File("src/main/resources/geodata/terrain-colors.json");
            if(!cfg.exists())cfg=new java.io.File("../terrain-colors.json");
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(cfg);
            var ur = root.get("upgradeRules");
            if(ur!=null){
                rulesStr = ur.toString();
                if(ur.has("yellowGreen_to_Hills"))ygR=ur.get("yellowGreen_to_Hills").asDouble();
                if(ur.has("redBrown_to_Mountain"))rbR=ur.get("redBrown_to_Mountain").asDouble();
                if(ur.has("gray_to_HighMountain"))gyR=ur.get("gray_to_HighMountain").asDouble();
                if(ur.has("green_to_Forest"))grR=ur.get("green_to_Forest").asDouble();
                if(ur.has("blue_to_Ocean"))blR=ur.get("blue_to_Ocean").asDouble();
            }
        } catch(Exception e){}

        // Step 4: Determine final terrain
        double ygPct=100.0*yg/total,rbPct=100.0*rb2/total,gyPct=100.0*gy/total,grPct=100.0*gr2/total,blPct=100.0*bl2/total;
        TerrainType finalT = base;
        String reason = "base rule match";
        if(blPct>blR){finalT=TerrainType.COASTAL_WATER;reason="blue_to_Ocean ("+String.format("%.0f",blPct)+"%>"+String.format("%.0f",blR)+"%)";}
        else if(gyPct>gyR&&base!=TerrainType.ICE){finalT=TerrainType.HIGH_MOUNTAIN;reason="gray_to_HighMountain ("+String.format("%.0f",gyPct)+"%>"+String.format("%.0f",gyR)+"%)";}
        else if(rbPct>rbR&&base!=TerrainType.DESERT){finalT=TerrainType.MOUNTAIN;reason="redBrown_to_Mountain ("+String.format("%.0f",rbPct)+"%>"+String.format("%.0f",rbR)+"%)";}
        else if(ygPct>ygR){finalT=TerrainType.HILLS;reason="yellowGreen_to_Hills ("+String.format("%.0f",ygPct)+"%>"+String.format("%.0f",ygR)+"%)";}
        else if(grPct>grR){finalT=TerrainType.FOREST;reason="green_to_Forest ("+String.format("%.0f",grPct)+"%>"+String.format("%.0f",grR)+"%)";}

        return String.format(java.util.Locale.US,
            "{\"location\":{\"lat\":%.4f,\"lng\":%.4f}," +
            "\"step1_centerPixel\":{\"rgb\":[%d,%d,%d],\"hsv\":[%.0f,%.2f,%.2f],\"baseTerrain\":\"%s\",\"elevation\":%.0f}," +
            "\"step2_colorProfile\":{\"samples\":%d,\"redBrown_pct\":%.1f,\"yellowGreen_pct\":%.1f,\"green_pct\":%.1f,\"cyan_pct\":%.1f,\"blue_pct\":%.1f,\"gray_pct\":%.1f}," +
            "\"step3_upgradeRules\":%s," +
            "\"step4_final\":{\"terrain\":\"%s\",\"reason\":\"%s\"}}",
            lat,lng, r,g,b, hsv[0]*360,hsv[1],hsv[2], base.getDisplayName(),elev,
            total, rbPct,ygPct,grPct,cy2*100.0/total,blPct,gyPct,
            rulesStr,
            finalT.getDisplayName(), reason);
    }

    /**
     * Apply color-percentage upgrade rules to the terrain grid.
     * Rules are read from terrain-colors.json "upgradeRules" section.
     */
    /** Reload terrain after HSV config change. */
    public String cacheAll() { return terrainGenerator.cacheAll(); }
    public boolean isCacheExists() { return terrainGenerator.getTerrainCache().exists(); }
    public String getCachePath() { return terrainGenerator.getTerrainCache().getCachePath().toString(); }

    public String applyUpgradeRules() {
        var img = terrainGenerator.getReliefImage();
        if (img == null) return "{\"ok\":false}";
        // Force reload config and re-classify
        try {
            var rl = new com.getgoat.map.terrain.ReliefMapLoader();
            // Load the config fresh
            var f = new java.io.File("terrain-colors.json");
            if(!f.exists()) f = new java.io.File("src/main/resources/geodata/terrain-colors.json");
            if(!f.exists()) f = new java.io.File("../terrain-colors.json");
            if(f.exists()) rl.loadConfigFromFile(f);
            rl.sampleToGrid(terrainGrid);
            // Re-apply land mask
            var landMask = new TerrainGenerator().getLandMask();
            if(landMask != null) {
                var gf = new org.locationtech.jts.geom.GeometryFactory(new org.locationtech.jts.geom.PrecisionModel(), 4326);
                for(int r=0;r<terrainGrid.getRows();r++) for(int c=0;c<terrainGrid.getCols();c++) {
                    var cell = terrainGrid.getCell(r,c);
                    var pt = gf.createPoint(new org.locationtech.jts.geom.Coordinate(cell.getCenter().getLongitude(), cell.getCenter().getLatitude()));
                    if(!landMask.contains(pt)) { cell.setTerrain(TerrainType.OCEAN); cell.setElevationMeters(-3000); }
                    else if(cell.isWater()) { cell.setTerrain(TerrainType.PLAINS); cell.setElevationMeters(0); }
                }
            }
            invalidateCache();
        } catch(Exception e) { return "{\"ok\":false,\"error\":\""+e.getMessage()+"\"}"; }
        MapStats s = getStats();
        return "{\"ok\":true,\"hills\":"+s.terrainDistribution().getOrDefault("Hills",0)
            +",\"mountain\":"+s.terrainDistribution().getOrDefault("Mountain",0)+"}";
    }

    /** Old applyUpgradeRules kept for reference */
    public String _applyUpgradeRules_old() {
        var img = terrainGenerator.getReliefImage();
        if (img == null) return "{\"ok\":false,\"error\":\"no relief image\"}";
        int w = img.getWidth(), hh = img.getHeight();
        int sampleR = Math.max(3, (int)(w/(360.0/terrainGrid.getCellSizeDegrees()))/3);
        record B(double yg,double rb,double gy,double gr,double bl){}
        B tr=new B(999,999,999,999,999),te=new B(999,999,999,999,999),bo=new B(999,999,999,999,999);
        try{java.io.File cfg=new java.io.File("terrain-colors.json");if(!cfg.exists())cfg=new java.io.File("src/main/resources/geodata/terrain-colors.json");if(!cfg.exists())cfg=new java.io.File("../terrain-colors.json");
            var ur=new com.fasterxml.jackson.databind.ObjectMapper().readTree(cfg).get("upgradeRules");if(ur!=null){
                var t=ur.get("tropical");if(t!=null)tr=new B(d(t,"yellowGreen_to_Hills"),d(t,"redBrown_to_Mountain"),d(t,"gray_to_HighMountain"),d(t,"green_to_Forest"),d(t,"blue_to_Ocean"));
                var e=ur.get("temperate");if(e!=null)te=new B(d(e,"yellowGreen_to_Hills"),d(e,"redBrown_to_Mountain"),d(e,"gray_to_HighMountain"),d(e,"green_to_Forest"),d(e,"blue_to_Ocean"));
                var o=ur.get("boreal");if(o!=null)bo=new B(d(o,"yellowGreen_to_Hills"),d(o,"redBrown_to_Mountain"),d(o,"gray_to_HighMountain"),d(o,"green_to_Forest"),d(o,"blue_to_Ocean"));}}catch(Exception x){}
        int upgraded=0;
        for(int row=0;row<terrainGrid.getRows();row++)for(int col=0;col<terrainGrid.getCols();col++){
            TerrainCell cell=terrainGrid.getCell(row,col);if(cell.isWater())continue;
            double absLat=Math.abs(cell.getCenter().getLatitude());
            B b=absLat<30?tr:absLat<55?te:bo;
            int cx=(int)((cell.getCenter().getLongitude()+180)/360*w),cy=(int)((90-cell.getCenter().getLatitude())/180*hh);
            int yg=0,rb2=0,gr2=0,bl2=0,gy2=0,total=0;
            for(int dy=-sampleR;dy<=sampleR;dy++)for(int dx=-sampleR;dx<=sampleR;dx++){
                int sx=Math.max(0,Math.min(w-1,cx+dx)),sy=Math.max(0,Math.min(hh-1,cy+dy));
                int rgb=img.getRGB(sx,sy);int r=(rgb>>16)&0xff,g=(rgb>>8)&0xff,b2=rgb&0xff;
                float[] h=java.awt.Color.RGBtoHSB(r,g,b2,null);float hue=h[0]*360,sat=h[1];
                if(sat<0.1)gy2++;else if(hue<30)rb2++;else if(hue<90)yg++;else if(hue<150)gr2++;else if(hue<210)/*cyan*/;else bl2++;total++;}
            double ygP=100.0*yg/total,rbP=100.0*rb2/total,gyP=100.0*gy2/total,grP=100.0*gr2/total,blP=100.0*bl2/total;
            TerrainType orig=cell.getTerrain();
            if(blP>b.bl){cell.setTerrain(TerrainType.COASTAL_WATER);upgraded++;}
            else if(gyP>b.gy&&orig!=TerrainType.ICE){cell.setTerrain(TerrainType.HIGH_MOUNTAIN);upgraded++;}
            else if(rbP>b.rb&&orig!=TerrainType.DESERT){cell.setTerrain(TerrainType.MOUNTAIN);upgraded++;}
            else if(ygP>b.yg){cell.setTerrain(TerrainType.HILLS);upgraded++;}
            else if(grP>b.gr){cell.setTerrain(TerrainType.FOREST);upgraded++;}
        }
        return String.format("{\"ok\":true,\"upgraded\":%d,\"bands\":{"+
            "\"tropical\":{\"yg→Hills\":%.0f,\"rb→Mtn\":%.0f,\"gy→High\":%.0f,\"gr→Forest\":%.0f,\"bl→Ocean\":%.0f},"+
            "\"temperate\":{\"yg→Hills\":%.0f,\"rb→Mtn\":%.0f,\"gy→High\":%.0f,\"gr→Forest\":%.0f,\"bl→Ocean\":%.0f},"+
            "\"boreal\":{\"yg→Hills\":%.0f,\"rb→Mtn\":%.0f,\"gy→High\":%.0f,\"gr→Forest\":%.0f,\"bl→Ocean\":%.0f}}}",
            upgraded,tr.yg,tr.rb,tr.gy,tr.gr,tr.bl,te.yg,te.rb,te.gy,te.gr,te.bl,bo.yg,bo.rb,bo.gy,bo.gr,bo.bl);
    }
    private static double d(com.fasterxml.jackson.databind.JsonNode n,String k){return n.has(k)?n.get(k).asDouble():999;}


    /**
     * Re-run roughness detection with a new threshold on the existing grid.
     */
    public String setRoughnessThreshold(double threshold) {
        // removed: com.getgoat.map.terrain.ReliefMapLoader.setRoughnessThreshold(threshold);
        var img = terrainGenerator.getReliefImage();
        if (img == null) return "{\"ok\":false,\"error\":\"no relief image\"}";

        int w = img.getWidth(), hh = img.getHeight();
        int pixelsPerCell = (int)(w / (360.0 / terrainGrid.getCellSizeDegrees()));
        int sampleRadius = Math.max(3, pixelsPerCell / 3);
        int upgraded = 0;

        // Reset all hills back to their base type (re-run full classification? no — just roughness)
        for (int row = 0; row < terrainGrid.getRows(); row++) {
            for (int col = 0; col < terrainGrid.getCols(); col++) {
                TerrainCell cell = terrainGrid.getCell(row, col);
                if (cell.isWater()) continue;
                // Reset: re-classify from pixel color
                int px = (int)((cell.getCenter().getLongitude()+180)/360*w);
                int py = (int)((90-cell.getCenter().getLatitude())/180*hh);
                int rgb = img.getRGB(Math.max(0,Math.min(w-1,px)), Math.max(0,Math.min(hh-1,py)));
                TerrainType base = com.getgoat.map.terrain.ReliefMapLoader.elevationFromRgb(rgb) > 0 ? com.getgoat.map.model.TerrainType.PLAINS : com.getgoat.map.model.TerrainType.OCEAN;
                cell.setTerrain(base);
                cell.setElevationMeters(com.getgoat.map.terrain.ReliefMapLoader.elevationFromRgb(rgb));

                // Yellow-green pixel percentage check
                if (cell.isWater() || base == TerrainType.HILLS || base == TerrainType.MOUNTAIN
                    || base == TerrainType.HIGH_MOUNTAIN || base == TerrainType.PLATEAU
                    || cell.getElevationMeters() > 500 || base == TerrainType.DESERT
                    || base == TerrainType.ICE) continue;

                int cx = (int)((cell.getCenter().getLongitude()+180)/360*w);
                int cy = (int)((90-cell.getCenter().getLatitude())/180*hh);
                int yellow=0,total=0;
                for(int dy=-sampleRadius;dy<=sampleRadius;dy++)
                    for(int dx=-sampleRadius;dx<=sampleRadius;dx++){
                        int sx=Math.max(0,Math.min(w-1,cx+dx)),sy=Math.max(0,Math.min(hh-1,cy+dy));
                        int rgb2=img.getRGB(sx,sy);
                        float h=java.awt.Color.RGBtoHSB((rgb2>>16)&0xff,(rgb2>>8)&0xff,rgb2&0xff,null)[0]*360;
                        if(h>=30&&h<=90)yellow++;total++;
                    }
                double pct=100.0*yellow/total;
                if(Math.pow(pct/threshold,2.0)>=1.0){cell.setTerrain(TerrainType.HILLS);upgraded++;}
            }
        }
        MapStats stats = getStats();
        return String.format("{\"ok\":true,\"threshold\":%.1f,\"hillsUpgraded\":%d,\"totalHills\":%d}",
            threshold, upgraded, stats.terrainDistribution().getOrDefault("Hills",0));
    }

    // =========================================================================
    // Road network queries
    // =========================================================================

    public RoadNetwork getRoadNetwork() { return roadNetwork; }
    public boolean hasRoadNetwork() { return roadNetwork.isAvailable(); }

    // ---- Query 1: Enhanced radius query (roads + regions + terrain by direction) ----

    /**
     * Result of an enhanced radius query.
     */
    public record EnhancedRadiusResult(
        GeoPoint center,
        double radiusKm,
        TerrainCell centerCell,
        List<RoadNode> roadNodes,
        List<RoadSegment> roadSegments,
        List<AdminDivision> cities,
        List<Region> regions,
        List<Annotation> annotations,
        TerrainDirectionBreakdown terrainBreakdown,
        Map<TerrainType, Double> terrainProfile,
        ElevationProfile elevationProfile,
        long queryTimeMs
    ) {}

    /** Terrain percentage breakdown by cardinal direction. */
    public record TerrainDirectionBreakdown(
        String northSummary,
        String southSummary,
        String eastSummary,
        String westSummary
    ) {}

    /** Elevation stats. */
    public record ElevationProfile(int min, int max, int mean, int range) {}

    /**
     * Combined relief classification: roughness × elevation.
     * Flat + low = PLAINS, Rough + low = HILLS, Flat + high = PLATEAU, Rough + high = MOUNTAINS.
     */
    public enum ReliefClass { PLAINS, HILLS, PLATEAU, MOUNTAINS }

    // Relief thresholds — mutable via /api/map/relief-config
    private volatile int reliefElevThreshold = 500;  // meters: >= this = high elevation
    private volatile int reliefRoughThreshold = 60;  // meters: >= this 3×3 stddev = rough

    public int getReliefElevThreshold() { return reliefElevThreshold; }
    public int getReliefRoughThreshold() { return reliefRoughThreshold; }
    public void setReliefThresholds(int elev, int rough) {
        this.reliefElevThreshold = elev;
        this.reliefRoughThreshold = rough;
    }

    /**
     * Query 1: Radius query with roads, regions, and terrain-by-direction.
     */
    public EnhancedRadiusResult queryRadiusEnhanced(double lat, double lng, double radiusKm) {
        long t0 = System.currentTimeMillis();

        GeoPoint center = new GeoPoint(lat, lng);
        TerrainCell centerCell = getTerrainAt(lat, lng);

        // Roads (lazy-load region on first query)
        if (roadNetwork.isAvailable()) {
            roadNetwork.ensureRegion(lat, lng, radiusKm);
        }
        List<RoadNode> roadNodes = roadNetwork.isAvailable()
            ? roadNetwork.findNodesInRadius(lat, lng, radiusKm)
            : List.of();
        List<RoadSegment> roadSegments = roadNetwork.isAvailable()
            ? roadNetwork.findSegmentsInRadius(lat, lng, radiusKm)
            : List.of();

        // Cities within radius (for LLM keyword lookup)
        List<AdminDivision> nearbyCities = adminDivisions.isLoaded()
            ? adminDivisions.findCitiesInRadius(lat, lng, radiusKm)
            : List.of();

        // Regions
        RadiusQueryResult rqr = queryRadius(lat, lng, radiusKm);

        // Terrain by direction
        TerrainDirectionBreakdown terrain = computeTerrainByDirection(lat, lng, radiusKm);

        // Terrain type distribution + roughness + elevation bands within radius
        Map<TerrainType, Double> terrainProfile = computeTerrainProfile(lat, lng, radiusKm);
        Map<String, Double> roughnessProfile = computeReliefProfile(lat, lng, radiusKm);
        Map<String, Double> bandProfile = computeElevationBandProfile(lat, lng, radiusKm);
        ElevationProfile elevProfile = computeElevationProfile(lat, lng, radiusKm, terrainGenerator.getReliefImage());

        long elapsed = System.currentTimeMillis() - t0;
        return new EnhancedRadiusResult(center, radiusKm, centerCell,
            roadNodes, roadSegments, nearbyCities,
            rqr.regionsInRadius(), rqr.annotationsInRadius(),
            terrain, terrainProfile, elevProfile, elapsed);
    }

    private ElevationProfile computeElevationProfile(double lat, double lng, double radiusKm, BufferedImage img) {
        double deg = radiusKm / 111.32;
        double step = 0.03125;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, total = 0;
        long sum = 0;
        for (double clat = lat - deg; clat <= lat + deg; clat += step) {
            for (double clng = lng - deg; clng <= lng + deg; clng += step) {
                if (SphericalEngine.haversineDistance(lat, lng, clat, clng) > radiusKm) continue;
                TerrainCell cell = getTerrainAt(clat, clng);
                int elev = cell != null ? (int) cell.getElevationMeters() : -3000;
                if (elev < min) min = elev;
                if (elev > max) max = elev;
                sum += elev;
                total++;
            }
        }
        if (total == 0) return new ElevationProfile(0, 0, 0, 0);
        return new ElevationProfile(min, max, (int) (sum / total), max - min);
    }

    /**
     * Combined relief classification: roughness (3×3 stddev) × elevation.
     * Result keys: "Plains", "Hills", "Plateau", "Mountains".
     */
    public Map<String, Double> computeElevationBandProfile(double lat, double lng, double radiusKm) {
        int[] counts = new int[ElevationBand.values().length];
        int total = 0;
        double deg = radiusKm / 111.32;
        double step = terrainGrid.getCellSizeDegrees();
        for (double clat = lat - deg; clat <= lat + deg; clat += step) {
            for (double clng = lng - deg; clng <= lng + deg; clng += step) {
                if (SphericalEngine.haversineDistance(lat, lng, clat, clng) > radiusKm) continue;
                TerrainCell cell = getTerrainAt(clat, clng);
                if (cell == null) continue;
                ElevationBand band = classifyElevation(cell.getElevationMeters());
                counts[band.ordinal()]++; total++;
            }
        }
        Map<String, Double> result = new LinkedHashMap<>();
        if (total > 0) for (ElevationBand b : ElevationBand.values()) {
            double pct = Math.round(1000.0 * counts[b.ordinal()] / total) / 10.0;
            if (pct > 0) result.put(capitalize(b.name()), pct);
        }
        return result;
    }

    public Map<String, Double> computeReliefProfile(double lat, double lng, double radiusKm) {
        var cache = terrainGrid.getCache();
        int[] counts = new int[ReliefClass.values().length];
        int total = 0;
        double deg = radiusKm / 111.32;
        double step = terrainGrid.getCellSizeDegrees();

        for (double clat = lat - deg; clat <= lat + deg; clat += step) {
            for (double clng = lng - deg; clng <= lng + deg; clng += step) {
                if (SphericalEngine.haversineDistance(lat, lng, clat, clng) > radiusKm) continue;
                int row = cache.latToRow(clat);
                int col = cache.lngToCol(clng);
                if (!cache.isClassified(row, col)) continue;

                // Local 3×3 stddev
                long sum = 0, sumSq = 0;
                int n = 0;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int nr = row + dr, nc = col + dc;
                        if (nr < 0 || nr >= cache.getRows() || nc < 0 || nc >= cache.getCols()) continue;
                        if (!cache.isClassified(nr, nc)) continue;
                        int e = cache.getElevationShort(nr, nc);
                        sum += e; sumSq += (long) e * e; n++;
                    }
                }
                if (n < 3) continue;
                int mean = (int) (sum / n);
                int stddev = (int) Math.sqrt(Math.max(0, sumSq / n - (long) mean * mean));

                // Self elevation
                int selfElev = cache.getElevationShort(row, col);
                boolean high = selfElev >= reliefElevThreshold;
                boolean rough = stddev >= reliefRoughThreshold;

                ReliefClass rc;
                if (!high && !rough) rc = ReliefClass.PLAINS;
                else if (!high && rough) rc = ReliefClass.HILLS;
                else if (high && !rough) rc = ReliefClass.PLATEAU;
                else rc = ReliefClass.MOUNTAINS;
                counts[rc.ordinal()]++;
                total++;
            }
        }
        Map<String, Double> result = new LinkedHashMap<>();
        if (total > 0) {
            for (ReliefClass rc : ReliefClass.values()) {
                double pct = Math.round(1000.0 * counts[rc.ordinal()] / total) / 10.0;
                if (pct > 0) result.put(capitalize(rc.name()), pct);
            }
        }
        return result;
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

    /** Compute relief class for a single cell by row/col. */
    public ReliefClass computeReliefClass(int row, int col) {
        var cache = terrainGrid.getCache();
        if (!cache.isClassified(row, col)) return null;

        int selfElev = cache.getElevationShort(row, col);
        // 3×3 stddev
        long sum = 0, sumSq = 0; int n = 0;
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                int nr = row + dr, nc = col + dc;
                if (nr < 0 || nr >= cache.getRows() || nc < 0 || nc >= cache.getCols()) continue;
                if (!cache.isClassified(nr, nc)) continue;
                int e = cache.getElevationShort(nr, nc);
                sum += e; sumSq += (long) e * e; n++;
            }
        }
        if (n < 3) return ReliefClass.PLAINS;
        int mean = (int) (sum / n);
        int stddev = (int) Math.sqrt(Math.max(0, sumSq / n - (long) mean * mean));

        boolean high = selfElev >= reliefElevThreshold;
        boolean rough = stddev >= reliefRoughThreshold;

        if (!high && !rough) return ReliefClass.PLAINS;
        if (!high && rough) return ReliefClass.HILLS;
        if (high && !rough) return ReliefClass.PLATEAU;
        return ReliefClass.MOUNTAINS;
    }

    /** Elevation band from actual altitude. */
    public enum ElevationBand {
        DEEP_OCEAN,  // < -200m
        SHALLOW,     // -200 – 0m
        LOWLAND,     // 0 – 200m
        HILL,        // 200 – 800m
        HIGHLAND,    // 800 – 2500m
        ALPINE       // > 2500m
    }

    public static ElevationBand classifyElevation(double meters) {
        if (meters < -200) return ElevationBand.DEEP_OCEAN;
        if (meters < 0)    return ElevationBand.SHALLOW;
        if (meters < 200)  return ElevationBand.LOWLAND;
        if (meters < 800)  return ElevationBand.HILL;
        if (meters < 2500) return ElevationBand.HIGHLAND;
        return ElevationBand.ALPINE;
    }

    /** Color hex for relief class. */
    public static String reliefColor(ReliefClass rc) {
        return switch (rc) {
            case PLAINS -> "#7dce82";
            case HILLS -> "#a2b573";
            case PLATEAU -> "#c9a96e";
            case MOUNTAINS -> "#8b7355";
        };
    }

    /** Classify terrain from elevation stats. */
    public static String terrainFromElevation(ElevationProfile ep) {
        if(ep.mean<0)return"Ocean";
        if(ep.range<150)return"Plains";
        if(ep.range<500)return"Hills";
        if(ep.mean<2500)return"Mountain";
        return"High Mountain";
    }

    private Map<TerrainType, Double> computeTerrainProfile(double lat, double lng, double radiusKm) {
        Map<TerrainType, Integer> counts = new LinkedHashMap<>();
        double deg = radiusKm / 111.32;
        double step = 0.03125; // grid resolution
        int total = 0;
        for (double clat = lat - deg; clat <= lat + deg; clat += step) {
            for (double clng = lng - deg; clng <= lng + deg; clng += step) {
                if (SphericalEngine.haversineDistance(lat, lng, clat, clng) > radiusKm) continue;
                TerrainCell cell = getTerrainAt(clat, clng);
                if (cell != null) { counts.merge(cell.getTerrain(), 1, Integer::sum); total++; }
            }
        }
        Map<TerrainType, Double> pct = new LinkedHashMap<>();
        if (total > 0) {
            for (var e : counts.entrySet())
                pct.put(e.getKey(), Math.round(1000.0 * e.getValue() / total) / 10.0);
        }
        return pct;
    }

    private TerrainDirectionBreakdown computeTerrainByDirection(
            double lat, double lng, double radiusKm) {

        double halfDeg = radiusKm / 2.0 / 111.32;

        return new TerrainDirectionBreakdown(
            terrainSummaryInSector(lat + halfDeg, lng, radiusKm / 2.0),   // north
            terrainSummaryInSector(lat - halfDeg, lng, radiusKm / 2.0),   // south
            terrainSummaryInSector(lat, lng + halfDeg, radiusKm / 2.0),   // east
            terrainSummaryInSector(lat, lng - halfDeg, radiusKm / 2.0)    // west
        );
    }

    private String terrainSummaryInSector(double lat, double lng, double radiusKm) {
        TerrainCell cell = getTerrainAt(lat, lng);
        if (cell == null) return "unknown";

        // Sample a small radius around the sector center
        List<TerrainCell> cells = getTerrainInBounds(
            new GeoBounds(lat - 0.5, lat + 0.5, lng - 0.5, lng + 0.5));
        Map<TerrainType, Integer> counts = new LinkedHashMap<>();
        for (TerrainCell c : cells) {
            double d = SphericalEngine.haversineDistance(lat, lng,
                c.getCenter().getLatitude(), c.getCenter().getLongitude());
            if (d <= radiusKm) {
                counts.merge(c.getTerrain(), 1, Integer::sum);
            }
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return cell.getTerrain().getDisplayName();

        return counts.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(3)
            .map(e -> Math.round(100.0 * e.getValue() / total) + "% " + e.getKey().getDisplayName())
            .reduce((a, b) -> a + ", " + b)
            .orElse("unknown");
    }

    // ---- Query 2: Neighbor query (places connected by roads) ----

    /**
     * Result: a place and its neighbors reachable within a radius.
     */
    public record NeighborResult(
        String placeName,
        GeoPoint placeLocation,
        String nearestNodeId,
        double radiusKm,
        List<NeighborEntry> neighbors,
        List<Region> regionsInArea
    ) {}

    public record NeighborEntry(
        String placeName,
        GeoPoint placeLocation,
        String connectingNodeId,
        double distanceKm,
        List<String> viaSegments
    ) {}

    /**
     * Query 2: Given a place name and radius, find adjacent places
     * connected by roads, and the roads that connect them.
     */
    public NeighborResult queryNeighbors(String placeName, double radiusKm) {
        double[] coords = RoadNetwork.lookupPlace(placeName);
        if (coords == null) {
            return new NeighborResult(placeName, null, null, radiusKm, List.of(), List.of());
        }

        GeoPoint placeLoc = new GeoPoint(coords[0], coords[1]);
        if (roadNetwork.isAvailable()) {
            roadNetwork.ensureRegion(coords[0], coords[1], radiusKm);
        }
        String myNodeId = roadNetwork.findNearestNode(coords[0], coords[1]);

        // Find nearby nodes within radius
        List<RoadNode> nearbyNodes = roadNetwork.findNodesInRadius(coords[0], coords[1], radiusKm);

        // For each nearby node, check if it's associated with a known place
        List<NeighborEntry> neighbors = new ArrayList<>();
        for (RoadNode node : nearbyNodes) {
            if (node.getId().equals(myNodeId)) continue;

            // Find if this node is near a known place
            String nearestPlace = findPlaceNearNode(node);
            if (nearestPlace == null || nearestPlace.equals(placeName)) continue;

            double dist = distance(placeLoc,
                new GeoPoint(node.getLocation().getLatitude(), node.getLocation().getLongitude()));

            // Find connecting road segments between my node and this node
            List<String> viaSegs = new ArrayList<>();
            for (String segId : node.getSegmentIds()) {
                RoadSegment seg = roadNetwork.getSegment(segId);
                if (seg != null) {
                    RoadNode other = null;
                    if (seg.getFromNodeId().equals(node.getId())) {
                        other = roadNetwork.getNode(seg.getToNodeId());
                    } else if (seg.getToNodeId().equals(node.getId())) {
                        other = roadNetwork.getNode(seg.getFromNodeId());
                    }
                    if (other != null) {
                        double otherDist = distance(placeLoc,
                            new GeoPoint(other.getLocation().getLatitude(),
                                         other.getLocation().getLongitude()));
                        if (otherDist <= radiusKm) {
                            viaSegs.add(segId);
                        }
                    }
                }
            }

            // Deduplicate by place name
            boolean alreadyAdded = neighbors.stream().anyMatch(n -> n.placeName().equals(nearestPlace));
            if (!alreadyAdded) {
                neighbors.add(new NeighborEntry(nearestPlace,
                    new GeoPoint(node.getLocation().getLatitude(), node.getLocation().getLongitude()),
                    node.getId(), dist, viaSegs));
            }
        }

        // Regions in area
        RadiusQueryResult rq = queryRadius(coords[0], coords[1], radiusKm);

        return new NeighborResult(placeName, placeLoc, myNodeId, radiusKm,
            neighbors, rq.regionsInRadius());
    }

    private String findPlaceNearNode(RoadNode node) {
        double bestDist = 5.0; // within 5km
        String best = null;
        GeoPoint nodeLoc = node.getLocation();
        for (String placeName : RoadNetwork.getPlaceNames()) {
            double[] coords = RoadNetwork.lookupPlace(placeName);
            if (coords == null) continue;
            double d = SphericalEngine.haversineDistance(
                nodeLoc.getLatitude(), nodeLoc.getLongitude(), coords[0], coords[1]);
            if (d < bestDist) {
                bestDist = d;
                best = placeName;
            }
        }
        return best;
    }

    // ---- Query 3: Shortest path between two places ----

    /**
     * Query 3: Find the shortest road-network path between two places.
     */
    public RoadNetwork.PathResult findShortestPath(String fromPlace, String toPlace) {
        if (!roadNetwork.isAvailable()) {
            return new RoadNetwork.PathResult(List.of(), List.of(), 0, false);
        }

        double[] fromCoords = RoadNetwork.lookupPlace(fromPlace);
        double[] toCoords = RoadNetwork.lookupPlace(toPlace);
        if (fromCoords == null || toCoords == null) {
            return new RoadNetwork.PathResult(List.of(), List.of(), 0, false);
        }

        // Load all cells along the corridor between from and to
        double estDist = SphericalEngine.haversineDistance(
            fromCoords[0], fromCoords[1], toCoords[0], toCoords[1]);
        // Load cells around both endpoints and along the great-circle path
        roadNetwork.ensureRegion(fromCoords[0], fromCoords[1], 200);
        roadNetwork.ensureRegion(toCoords[0], toCoords[1], 200);
        // Also sample midpoint and quarter points to ensure corridor coverage
        double midLat = (fromCoords[0] + toCoords[0]) / 2;
        double midLng = (fromCoords[1] + toCoords[1]) / 2;
        roadNetwork.ensureRegion(midLat, midLng, estDist * 0.6);
        // Quarter points
        double q1Lat = fromCoords[0] * 0.75 + toCoords[0] * 0.25;
        double q1Lng = fromCoords[1] * 0.75 + toCoords[1] * 0.25;
        double q3Lat = fromCoords[0] * 0.25 + toCoords[0] * 0.75;
        double q3Lng = fromCoords[1] * 0.25 + toCoords[1] * 0.75;
        roadNetwork.ensureRegion(q1Lat, q1Lng, estDist * 0.4);
        roadNetwork.ensureRegion(q3Lat, q3Lng, estDist * 0.4);

        String fromNode = roadNetwork.findNearestNode(fromPlace);
        String toNode = roadNetwork.findNearestNode(toPlace);

        if (fromNode == null) {
            LOG.warning("Place not found: " + fromPlace);
            return new RoadNetwork.PathResult(List.of(), List.of(), 0, false);
        }
        if (toNode == null) {
            LOG.warning("Place not found: " + toPlace);
            return new RoadNetwork.PathResult(List.of(), List.of(), 0, false);
        }

        return roadNetwork.shortestPath(fromNode, toNode);
    }

    // ---- Query 4: Mark a road segment ----

    /**
     * Result of marking a road segment.
     */
    public record MarkSegmentResult(
        String segmentId,
        String markLabel,
        RoadNode fromNode,
        RoadNode toNode,
        double lengthKm,
        boolean success
    ) {}

    /**
     * Query 4: Mark a road segment and return its endpoints.
     */
    public MarkSegmentResult markRoadSegment(String segmentId, String label) {
        if (!roadNetwork.isAvailable()) {
            return new MarkSegmentResult(segmentId, label, null, null, 0, false);
        }

        RoadSegment seg = roadNetwork.getSegment(segmentId);
        if (seg == null) {
            return new MarkSegmentResult(segmentId, label, null, null, 0, false);
        }

        seg.mark(label);
        RoadNode fromNode = roadNetwork.getNode(seg.getFromNodeId());
        RoadNode toNode = roadNetwork.getNode(seg.getToNodeId());

        return new MarkSegmentResult(segmentId, label, fromNode, toNode,
            seg.getLengthKm(), true);
    }

    /**
     * Export fine-resolution terrain cells within a radius as colored GeoJSON.
     * Samples the GeoTIFF directly at 0.03125° (~3.5km) for display — 16× finer than backend grid.
     */
    public String exportTerrainCellsInRadius(double lat, double lng, double radiusKm) {
        return exportTerrainCellsInRadius(lat, lng, radiusKm, 0);
    }

    /** @param displayRes 0 = use native full precision, otherwise use this resolution */
    public String exportTerrainCellsInRadius(double lat, double lng, double radiusKm, double displayRes) {
        checkInitialized();
        long t0 = System.currentTimeMillis();
        double nativeRes = com.getgoat.map.ConfigManager.getDisplayFineRes();

        // Always use full native resolution unless explicitly overridden
        if (displayRes <= 0) displayRes = nativeRes;

        double degRadius = radiusKm / 111.32;
        double latStart = lat - degRadius;
        double latEnd = lat + degRadius;
        double lngStart = lng - degRadius;
        double lngEnd = lng + degRadius;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        int cellCount = 0;
        final int MAX_CELLS = 600;

        // Use FIXED world-aligned DISPLAY grid
        int startRow = (int) Math.ceil((90.0 - latEnd) / displayRes);
        int endRow   = (int) Math.floor((90.0 - latStart) / displayRes);
        int startCol = (int) Math.ceil((lngStart + 180.0) / displayRes);
        int endCol   = (int) Math.floor((lngEnd + 180.0) / displayRes);

        for (int row = startRow; row <= endRow && cellCount < MAX_CELLS; row++) {
            double clat = 90.0 - (row + 0.5) * displayRes;
            for (int col = startCol; col <= endCol && cellCount < MAX_CELLS; col++) {
                double clng = -180.0 + (col + 0.5) * displayRes;
                double dist = SphericalEngine.haversineDistance(lat, lng, clat, clng);
                if (dist > radiusKm) continue;

                double h = displayRes / 2.0;
                double s = clat - h, n = clat + h, w = clng - h, e = clng + h;

                TerrainCell cell = terrainGrid.getCellAt(clat, clng);
                TerrainType terrain = cell != null ? cell.getTerrain() : TerrainType.OCEAN;
                double elevation = cell != null ? cell.getElevationMeters() : -3000;
                int cRow = terrainGrid.latToRow(clat);
                int cCol = terrainGrid.lngToCol(clng);
                ReliefClass relief = computeReliefClass(cRow, cCol);
                String color = terrain.isWater() ? terrain.getColorHex()
                    : (relief != null ? reliefColor(relief) : terrain.getColorHex());
                String reliefName = relief != null ? capitalize(relief.name()) : "Water";

                if (!first) sb.append(",");
                first = false;
                cellCount++;
                ElevationBand band = classifyElevation(elevation);
                sb.append(String.format(Locale.US,
                    "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[["
                    + "[%.4f,%.4f],[%.4f,%.4f],[%.4f,%.4f],[%.4f,%.4f],[%.4f,%.4f]]]},"
                    + "\"properties\":{\"terrain\":\"%s\",\"relief\":\"%s\",\"band\":\"%s\",\"color\":\"%s\",\"elevation\":%.0f}}",
                    w, s, e, s, e, n, w, n, w, s,
                    terrain.getDisplayName(), reliefName, capitalize(band.name()), color, elevation));
            }
        }
        sb.append("],\"cellCount\":").append(cellCount)
          .append(",\"resolution\":").append(displayRes)
          .append(",\"maxCells\":").append(MAX_CELLS)
          .append(",\"queryTimeMs\":").append(System.currentTimeMillis() - t0)
          .append("}");
        return sb.toString();
    }

    /**
     * Export river segments within a radius as GeoJSON, annotated with
     * which terrain types they flow through.
     */
    public String exportRiversInRadius(double lat, double lng, double radiusKm) {
        if (riverNetwork.isAvailable()) {
            riverNetwork.ensureRegion(lat, lng, radiusKm);
        }
        List<org.locationtech.jts.geom.LineString> rivers = riverNetwork.isAvailable()
            ? riverNetwork.findInRadius(lat, lng, radiusKm)
            : List.of();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        for (var line : rivers) {
            if (!first) sb.append(",");
            first = false;

            // Determine terrain types along this river
            Set<String> terrains = new LinkedHashSet<>();
            for (org.locationtech.jts.geom.Coordinate c : line.getCoordinates()) {
                TerrainCell cell = terrainGrid.getCellAt(c.y, c.x);
                if (cell != null) terrains.add(cell.getTerrain().getDisplayName());
            }

            sb.append(String.format(
                "{\"type\":\"Feature\",\"geometry\":%s,\"properties\":{"
                    + "\"terrains\":\"%s\",\"points\":%d}}",
                geoJsonWriter.writeGeometry(line),
                String.join(", ", terrains),
                line.getNumPoints()));
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Export road segments within a radius as GeoJSON (for map overlay).
     */
    public String exportRoadsInRadius(double lat, double lng, double radiusKm) {
        if (roadNetwork.isAvailable()) {
            roadNetwork.ensureRegion(lat, lng, radiusKm);
        }
        List<RoadSegment> segs = roadNetwork.isAvailable()
            ? roadNetwork.findSegmentsInRadius(lat, lng, radiusKm)
            : List.of();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        for (RoadSegment seg : segs) {
            if (!first) sb.append(",");
            first = false;
            sb.append(String.format(
                "{\"type\":\"Feature\",\"geometry\":%s,\"properties\":{"
                    + "\"id\":\"%s\",\"lengthKm\":%.1f,\"marked\":%b,\"label\":\"%s\"}}",
                geoJsonWriter.writeGeometry(seg.getGeometry()),
                seg.getId(), seg.getLengthKm(), seg.isMarked(),
                seg.getMarkLabel() != null ? esc(seg.getMarkLabel()) : ""));
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Export all currently cached road segments as GeoJSON for map rendering.
     */
    public String exportRoadsGeoJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");

        boolean first = true;
        for (var seg : roadNetwork.allSegments()) {
            if (!first) sb.append(",");
            first = false;
            String geomJson = geoJsonWriter.writeGeometry(seg.getGeometry());
            sb.append(String.format(
                "{\"type\":\"Feature\",\"geometry\":%s,\"properties\":{"
                    + "\"id\":\"%s\",\"lengthKm\":%.1f,\"marked\":%b,"
                    + "\"markLabel\":\"%s\",\"fromNode\":\"%s\",\"toNode\":\"%s\"}}",
                geomJson,
                esc(seg.getId()), seg.getLengthKm(), seg.isMarked(),
                esc(seg.getMarkLabel() != null ? seg.getMarkLabel() : ""),
                esc(seg.getFromNodeId()), esc(seg.getToNodeId())));
        }
        sb.append("]}");
        return sb.toString();
    }

    // =========================================================================
    // Grid-based A* pathfinding (terrain cells as nodes)
    // =========================================================================

    /**
     * Result of a grid-based A* path search.
     */
    public record GridPathResult(
        List<GeoPoint> path,           // ordered waypoints
        double totalCostKm,            // weighted path cost
        double straightLineKm,         // direct Haversine distance
        int nodesExplored,             // A* nodes visited
        long queryTimeMs
    ) {
        public int waypoints() { return path.size(); }
    }

    /**
     * A* shortest path on the fine terrain grid, with terrain + road modifiers.
     *
     * @param allowLand  if false, land cells are blocked (water-only path)
     * @param allowWater if true, water cells get a movement penalty instead of being blocked
     */
    public GridPathResult findGridPath(double fromLat, double fromLng,
                                        double toLat, double toLng,
                                        double cellSizeDeg,
                                        boolean allowLand, boolean allowWater) {
        long t0 = System.currentTimeMillis();
        double straightKm = SphericalEngine.haversineDistance(fromLat, fromLng, toLat, toLng);

        // Build a local grid covering the bounding box + margin
        double margin = 0.5;
        double minLat = Math.min(fromLat, toLat) - margin;
        double maxLat = Math.max(fromLat, toLat) + margin;
        double minLng = Math.min(fromLng, toLng) - margin;
        double maxLng = Math.max(fromLng, toLng) + margin;

        // Pre-compute terrain + road masks for the bounding box
        Map<String, Double> cellCost = new LinkedHashMap<>();
        Set<String> blockedCells = new HashSet<>();

        // Build road cell lookup
        Set<String> roadMask = new HashSet<>();
        if (roadNetwork.isAvailable()) {
            double midLat = (minLat + maxLat) / 2, midLng = (minLng + maxLng) / 2;
            roadNetwork.ensureRegion(midLat, midLng, straightKm * 0.7);
            for (var seg : roadNetwork.findSegmentsInRadius(midLat, midLng, straightKm * 0.6)) {
                for (org.locationtech.jts.geom.Coordinate c : seg.getGeometry().getCoordinates()) {
                    roadMask.add(cellKey(c.y, c.x, cellSizeDeg));
                }
            }
        }

        // Pre-sample by row/col index (guarantees cellKey consistency)
        int minRow = (int)Math.round((90.0 - maxLat) / cellSizeDeg);
        int maxRow = (int)Math.round((90.0 - minLat) / cellSizeDeg);
        int minCol = (int)Math.round((minLng + 180.0) / cellSizeDeg);
        int maxCol = (int)Math.round((maxLng + 180.0) / cellSizeDeg);

        for (int row = minRow; row <= maxRow; row++) {
            double lat = 90.0 - (row + 0.5) * cellSizeDeg;
            for (int col = minCol; col <= maxCol; col++) {
                double lng = -180.0 + (col + 0.5) * cellSizeDeg;
                String key = row + ":" + col;

                TerrainCell c = terrainGrid.getCellAt(lat, lng);
                TerrainType tt = c != null ? c.getTerrain() : TerrainType.OCEAN;
                boolean isWater = tt.isWater();
                boolean isLand = !isWater;

                // Apply land/water traversal flags
                if (isLand && !allowLand) { blockedCells.add(key); continue; }
                if (isWater && !allowWater) { blockedCells.add(key); continue; }

                double terrainPenalty = tt.movementPenalty();
                // Water penalty when allowed: treat as slower movement
                if (isWater && allowWater) terrainPenalty = 0.6;
                // Polar ice crossing (simplified)
                if (isWater && Math.abs(lat) > 60) terrainPenalty = Math.min(terrainPenalty, 0.4);

                double roadMult = (!isWater && roadMask.contains(key)) ? 0.4 : 1.0;
                cellCost.put(key, terrainPenalty * roadMult);
            }
        }

        // A* search — ensure start/goal cells are in the cost map
        String startKey = cellKey(fromLat, fromLng, cellSizeDeg);
        String goalKey = cellKey(toLat, toLng, cellSizeDeg);
        if (!cellCost.containsKey(startKey)) cellCost.put(startKey, 1.0); // assume plains
        if (!cellCost.containsKey(goalKey)) cellCost.put(goalKey, 1.0);
        blockedCells.remove(startKey);
        blockedCells.remove(goalKey);

        Map<String, Double> gScore = new HashMap<>();
        Map<String, Double> fScore = new HashMap<>();
        Map<String, String> cameFrom = new HashMap<>();
        PriorityQueue<String> openSet = new PriorityQueue<>(
            Comparator.comparingDouble(k -> fScore.getOrDefault(k, Double.POSITIVE_INFINITY)));

        gScore.put(startKey, 0.0);
        fScore.put(startKey, heuristic(fromLat, fromLng, toLat, toLng));
        openSet.add(startKey);
        int explored = 0;

        while (!openSet.isEmpty() && explored < 50000) {
            String current = openSet.poll();
            explored++;
            if (current.equals(goalKey)) break;
            if (gScore.getOrDefault(current, Double.POSITIVE_INFINITY) > fScore.getOrDefault(current, 0.0) + 100)
                continue;

            double[] rc = keyToLatLng(current, cellSizeDeg);
            double curLat = rc[0], curLng = rc[1];

            // 8-connected neighbors
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    if (dr == 0 && dc == 0) continue;
                    double nlat = curLat + dr * cellSizeDeg;
                    double nlng = curLng + dc * cellSizeDeg;
                    String nkey = cellKey(nlat, nlng, cellSizeDeg);
                    if (!cellCost.containsKey(nkey) || blockedCells.contains(nkey)) continue;

                    // Edge cost = Haversine distance × average terrain penalty
                    // Haversine naturally accounts for diagonal vs orthogonal path length
                    double dist = SphericalEngine.haversineDistance(curLat, curLng, nlat, nlng);
                    double avgPenalty = (cellCost.get(current) + cellCost.get(nkey)) / 2.0;
                    double edgeCost = dist * avgPenalty;

                    double tentativeG = gScore.getOrDefault(current, Double.POSITIVE_INFINITY) + edgeCost;
                    if (tentativeG < gScore.getOrDefault(nkey, Double.POSITIVE_INFINITY)) {
                        cameFrom.put(nkey, current);
                        gScore.put(nkey, tentativeG);
                        fScore.put(nkey, tentativeG + heuristic(nlat, nlng, toLat, toLng));
                        openSet.add(nkey);
                    }
                }
            }
        }

        // Reconstruct path
        LinkedList<GeoPoint> path = new LinkedList<>();
        if (cameFrom.containsKey(goalKey) || startKey.equals(goalKey)) {
            String cur = goalKey;
            while (cur != null) {
                double[] rc = keyToLatLng(cur, cellSizeDeg);
                path.addFirst(new GeoPoint(rc[0], rc[1]));
                cur = cameFrom.get(cur);
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        return new GridPathResult(path,
            gScore.getOrDefault(goalKey, Double.POSITIVE_INFINITY),
            straightKm, explored, elapsed);
    }

    private static double heuristic(double lat1, double lng1, double lat2, double lng2) {
        // Admissible: straight-line distance × minimum terrain penalty (road bonus)
        return SphericalEngine.haversineDistance(lat1, lng1, lat2, lng2) * 0.3;
    }

    private static double[] keyToLatLng(String key, double res) {
        String[] parts = key.split(":");
        int r = Integer.parseInt(parts[0]);
        int c = Integer.parseInt(parts[1]);
        return new double[]{90.0 - (r + 0.5) * res, -180.0 + (c + 0.5) * res};
    }

    private static String cellKey(double lat, double lng, double res) {
        return (int)Math.floor((90.0-lat)/res) + ":" + (int)Math.floor((lng+180.0)/res);
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Invalidate the query cache (call after mutations).
     */
    public void invalidateCache() {
        queryCache.clear();
    }

    /** Rough centroid of a JTS geometry. */
    private GeoPoint centroidOf(Geometry g) {
        org.locationtech.jts.geom.Point c = g.getCentroid();
        return new GeoPoint(c.getY(), c.getX());
    }

    /**
     * Get the terrain type name at a location.
     * Useful for LLM queries like "what's the terrain at 30°N 85°E?"
     */
    public String getTerrainTypeNameAt(double lat, double lng) {
        TerrainCell cell = getTerrainAt(lat, lng);
        return cell != null ? cell.getTerrain().getDisplayName() : "unknown";
    }

    // =========================================================================
    // Spherical geometry queries (delegated to SphericalEngine)
    // =========================================================================

    /** Great-circle distance between two points (Haversine, km). */
    public double distance(GeoPoint a, GeoPoint b) {
        return SphericalEngine.haversineDistance(a, b);
    }

    public double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        return SphericalEngine.haversineDistance(lat1, lng1, lat2, lng2);
    }

    /** High-precision ellipsoidal distance (Vincenty, km). */
    public double vincentyDistance(GeoPoint a, GeoPoint b) {
        return SphericalEngine.vincentyDistance(a, b);
    }

    /** Initial bearing from a to b (0=North, 90=East). */
    public double bearing(GeoPoint from, GeoPoint to) {
        return SphericalEngine.bearing(from, to);
    }

    /** Great-circle midpoint at a given fraction along the path. */
    public GeoPoint greatCircleMidpoint(GeoPoint a, GeoPoint b, double fraction) {
        return SphericalEngine.greatCircleInterpolate(a, b, fraction);
    }

    /** Full great-circle path between two points. */
    public List<GeoPoint> greatCirclePath(GeoPoint a, GeoPoint b, int numPoints) {
        return SphericalEngine.greatCirclePath(a, b, numPoints);
    }

    /** Check if a point falls inside a JTS geometry. */
    public boolean containsPoint(Geometry polygon, GeoPoint point) {
        org.locationtech.jts.geom.GeometryFactory gf =
            new org.locationtech.jts.geom.GeometryFactory();
        Point jtsPoint = gf.createPoint(
            new org.locationtech.jts.geom.Coordinate(point.getLongitude(), point.getLatitude()));
        return polygon.contains(jtsPoint);
    }

    // =========================================================================
    // Region operations
    // =========================================================================

    public Region createRegion(String name, Geometry boundary, String category) {
        invalidateCache();
        String id = UUID.randomUUID().toString().substring(0, 8);
        Region region = new Region(id, name, boundary, category);
        regions.put(id, region);
        LOG.info("Created region: " + region);
        return region;
    }

    public Region updateRegion(String id, Region updated) {
        if (!regions.containsKey(id)) {
            throw new IllegalArgumentException("Region not found: " + id);
        }
        regions.put(id, updated);
        return updated;
    }

    public void deleteRegion(String id) {
        invalidateCache();
        regions.remove(id);
        // Remove associated labels
        labels.values().removeIf(l -> id.equals(l.getAssociatedRegionId()));
    }

    public Region getRegion(String id) {
        return regions.get(id);
    }

    public List<Region> getAllRegions() {
        return new ArrayList<>(regions.values());
    }

    public List<Region> getRegionsByCategory(String category) {
        return regions.values().stream()
            .filter(r -> category.equals(r.getCategory()))
            .collect(Collectors.toList());
    }

    public List<Region> getRegionsByTag(String tag) {
        return regions.values().stream()
            .filter(r -> r.getTags().contains(tag))
            .collect(Collectors.toList());
    }

    /**
     * Find all regions that contain the given point.
     */
    public List<Region> getRegionsContaining(GeoPoint point) {
        org.locationtech.jts.geom.GeometryFactory gf =
            new org.locationtech.jts.geom.GeometryFactory();
        Point jtsPoint = gf.createPoint(
            new org.locationtech.jts.geom.Coordinate(point.getLongitude(), point.getLatitude()));
        return regions.values().stream()
            .filter(r -> r.getBoundary().contains(jtsPoint))
            .collect(Collectors.toList());
    }

    // =========================================================================
    // Label operations
    // =========================================================================

    public RegionLabel addLabel(String text, GeoPoint position) {
        String id = "lbl_" + UUID.randomUUID().toString().substring(0, 8);
        RegionLabel label = new RegionLabel(id, text, position);
        labels.put(id, label);
        return label;
    }

    public void removeLabel(String id) {
        labels.remove(id);
    }

    public List<RegionLabel> getLabelsInBounds(GeoBounds bounds) {
        return labels.values().stream()
            .filter(l -> bounds.contains(l.getPosition()))
            .collect(Collectors.toList());
    }

    public List<RegionLabel> getAllLabels() {
        return new ArrayList<>(labels.values());
    }

    // =========================================================================
    // Annotation operations
    // =========================================================================

    public Annotation addAnnotation(Annotation ann) {
        annotations.put(ann.getId(), ann);
        return ann;
    }

    public void removeAnnotation(String id) {
        annotations.remove(id);
    }

    public List<Annotation> getAnnotationsInBounds(GeoBounds bounds) {
        return annotations.values().stream()
            .filter(a -> bounds.intersects(boundingBoxOf(a.getGeometry())))
            .collect(Collectors.toList());
    }

    public List<Annotation> getAllAnnotations() {
        return new ArrayList<>(annotations.values());
    }

    // =========================================================================
    // Terrain modification
    // =========================================================================

    public void modifyTerrain(double lat, double lng, TerrainType newType) {
        invalidateCache();
        checkInitialized();
        terrainGrid.setTerrainAt(lat, lng, newType);
    }

    public void modifyTerrainRegion(Geometry boundary, TerrainType newType) {
        checkInitialized();
        GeoBounds bounds = boundingBoxOf(boundary);
        List<TerrainCell> cells = terrainGrid.getCellsInBounds(bounds);
        org.locationtech.jts.geom.GeometryFactory gf =
            new org.locationtech.jts.geom.GeometryFactory();
        for (TerrainCell cell : cells) {
            Point p = gf.createPoint(
                new org.locationtech.jts.geom.Coordinate(
                    cell.getCenter().getLongitude(), cell.getCenter().getLatitude()));
            if (boundary.contains(p)) {
                cell.setTerrain(newType);
            }
        }
    }

    public void modifyElevation(double lat, double lng, double newElevation) {
        checkInitialized();
        terrainGrid.setElevationAt(lat, lng, newElevation);
    }

    // =========================================================================
    // GeoJSON export (for frontend rendering)
    // =========================================================================

    /**
     * Export terrain data as GeoJSON (optionally filtered by bounds).
     */
    public String exportTerrainGeoJson(GeoBounds bounds) {
        checkInitialized();
        return geoJsonWriter.writeTerrainGrid(terrainGrid, bounds);
    }

    /**
     * Export terrain data within a lat/lng bounding box.
     */
    public String exportTerrainGeoJson(double south, double north, double west, double east) {
        return exportTerrainGeoJson(new GeoBounds(south, north, west, east));
    }

    /**
     * Export terrain data as a compact tile-friendly format.
     * Returns a JSON object with rows×cols grid of terrain type ordinals and colors.
     */
    public String exportTerrainTileJson(GeoBounds bounds) {
        checkInitialized();
        List<TerrainCell> cells = terrainGrid.getCellsInBounds(bounds);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"cells\":[");
        boolean first = true;
        for (TerrainCell cell : cells) {
            if (!first) sb.append(",");
            first = false;
            sb.append(String.format(Locale.US,
                "{\"r\":%d,\"c\":%d,\"t\":%d,\"e\":%.0f,\"clr\":\"%s\"}",
                cell.getRow(), cell.getCol(),
                cell.getTerrain().ordinal(),
                cell.getElevationMeters(),
                cell.getColorHex()
            ));
        }
        sb.append("],\"rows\":").append(terrainGrid.getRows());
        sb.append(",\"cols\":").append(terrainGrid.getCols());
        sb.append(",\"cellSize\":").append(terrainGrid.getCellSizeDegrees());
        sb.append("}");
        return sb.toString();
    }

    /**
     * Export regions as GeoJSON FeatureCollection.
     */
    public String exportRegionsGeoJson() {
        return geoJsonWriter.writeRegions(getAllRegions());
    }

    /**
     * Export annotations as GeoJSON FeatureCollection.
     */
    public String exportAnnotationsGeoJson(GeoBounds bounds) {
        return geoJsonWriter.writeAnnotations(getAnnotationsInBounds(bounds));
    }

    /**
     * Export labels as JSON array.
     */
    public String exportLabelsJson(GeoBounds bounds) {
        return geoJsonWriter.writeLabels(getLabelsInBounds(bounds));
    }

    /**
     * Export all labels as JSON.
     */
    public String exportAllLabelsJson() {
        return geoJsonWriter.writeLabels(getAllLabels());
    }

    /**
     * Export a combined map bundle for the frontend.
     * Includes terrain summary, regions, labels, and annotations.
     */
    public String exportFullMapBundle(GeoBounds bounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"terrain\":").append(exportTerrainTileJson(bounds)).append(",");
        sb.append("\"regions\":").append(exportRegionsGeoJson()).append(",");
        sb.append("\"labels\":").append(exportAllLabelsJson()).append(",");
        sb.append("\"annotations\":").append(exportAnnotationsGeoJson(bounds)).append(",");
        sb.append("\"stats\":").append(exportStatsJson());
        sb.append("}");
        return sb.toString();
    }

    // =========================================================================
    // Spatial index
    // =========================================================================

    public void rebuildSpatialIndex() {
        LOG.info("Rebuilding spatial index...");
        org.locationtech.jts.index.strtree.STRtree tree =
            new org.locationtech.jts.index.strtree.STRtree();
        for (Region region : regions.values()) {
            tree.insert(region.getBoundary().getEnvelopeInternal(), region);
        }
        tree.build();
        this.spatialIndex = tree;
    }

    // =========================================================================
    // Statistics
    // =========================================================================

    public record MapStats(
        int totalCells,
        int landCells,
        int waterCells,
        int regionCount,
        int labelCount,
        int annotationCount,
        Map<String, Integer> terrainDistribution
    ) {}

    public MapStats getStats() {
        checkInitialized();
        TerrainCache cache = terrainGrid.getCache();
        int land = 0, water = 0;
        Map<TerrainType, Integer> dist = new LinkedHashMap<>();

        // Single pass: count land/water and terrain distribution directly from cache
        for (int r = 0; r < terrainGrid.getRows(); r++) {
            for (int c = 0; c < terrainGrid.getCols(); c++) {
                TerrainType t = cache.getTerrain(r, c);
                if (t != null) {
                    dist.merge(t, 1, Integer::sum);
                    if (t.isWater()) water++; else land++;
                } else {
                    water++; // unclassified → default water
                }
            }
        }

        Map<String, Integer> displayDist = new LinkedHashMap<>();
        dist.forEach((k, v) -> displayDist.put(k.getDisplayName(), v));

        return new MapStats(
            terrainGrid.totalCells(),
            land, water,
            regions.size(),
            labels.size(),
            annotations.size(),
            displayDist
        );
    }

    public String exportStatsJson() {
        MapStats s = getStats();
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"totalCells\":").append(s.totalCells).append(",");
        sb.append("\"landCells\":").append(s.landCells).append(",");
        sb.append("\"waterCells\":").append(s.waterCells).append(",");
        sb.append("\"regionCount\":").append(s.regionCount).append(",");
        sb.append("\"labelCount\":").append(s.labelCount).append(",");
        sb.append("\"annotationCount\":").append(s.annotationCount).append(",");
        sb.append("\"terrainDistribution\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : s.terrainDistribution.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
        }
        sb.append("}}");
        return sb.toString();
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void checkInitialized() {
        if (terrainGrid == null) {
            throw new IllegalStateException(
                "MapManager not initialized. Call initialize() first.");
        }
    }

    private GeoBounds boundingBoxOf(Geometry g) {
        org.locationtech.jts.geom.Envelope env = g.getEnvelopeInternal();
        return new GeoBounds(env.getMinY(), env.getMaxY(), env.getMinX(), env.getMaxX());
    }
}
