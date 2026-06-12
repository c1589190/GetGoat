package com.cna.getgoat.map.terrain;

import com.cna.getgoat.map.terrain.TerrainCell;
import com.cna.getgoat.map.terrain.TerrainGrid;
import com.cna.getgoat.map.terrain.TerrainType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Terrain classification from Natural Earth GeoTIFF using HSV rules.
 */
public class ReliefMapLoader {

    private static final Logger LOG = Logger.getLogger(ReliefMapLoader.class.getName());
    private static List<Rule> rules;
    private static List<FallbackRule> fallbacks;
    private static boolean configLoaded;
    private BufferedImage reliefImage;

    public BufferedImage getImage() { return reliefImage; }

    public void load(String tiffPath) throws IOException {
        File f = new File(tiffPath);
        if (!f.exists()) throw new IOException("File not found: " + tiffPath);
        reliefImage = ImageIO.read(f);
        if (reliefImage == null) throw new IOException("Cannot decode " + tiffPath);
        LOG.info("Relief image loaded: " + reliefImage.getWidth() + "×" + reliefImage.getHeight());
        loadConfig();
    }

    public void loadConfigFromFile(File f) throws IOException {
        rules = new ArrayList<>(); fallbacks = new ArrayList<>();
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(f);
        for (var r : root.get("rules")) {
            rules.add(new Rule(TerrainType.valueOf(r.get("terrain").asText()),
                r.has("hue_min")?(float)r.get("hue_min").asDouble():0,
                r.has("hue_max")?(float)r.get("hue_max").asDouble():360,
                r.has("sat_min")?(float)r.get("sat_min").asDouble():0,
                r.has("sat_max")?(float)r.get("sat_max").asDouble():1,
                r.has("val_min")?(float)r.get("val_min").asDouble():0,
                r.has("val_max")?(float)r.get("val_max").asDouble():1,
                r.has("green_dominant")&&r.get("green_dominant").asBoolean()));
        }
        if (root.has("fallback")) for (var fb : root.get("fallback"))
            fallbacks.add(new FallbackRule(TerrainType.valueOf(fb.get("terrain").asText()),
                fb.has("val_max")?(float)fb.get("val_max").asDouble():1));
        configLoaded = true;
    }

    private void loadConfig() throws IOException {
        rules = new ArrayList<>(); fallbacks = new ArrayList<>();
        InputStream is = null;
        String path = com.cna.getgoat.config.ConfigsManager.getColorsJson();
            if (new java.io.File(path).exists()) { is = new java.io.FileInputStream(path); }
        if (is == null) { buildDefaultRules(); return; }
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(is); is.close();
        for (var r : root.get("rules")) {
            rules.add(new Rule(TerrainType.valueOf(r.get("terrain").asText()),
                r.has("hue_min")?(float)r.get("hue_min").asDouble():0,
                r.has("hue_max")?(float)r.get("hue_max").asDouble():360,
                r.has("sat_min")?(float)r.get("sat_min").asDouble():0,
                r.has("sat_max")?(float)r.get("sat_max").asDouble():1,
                r.has("val_min")?(float)r.get("val_min").asDouble():0,
                r.has("val_max")?(float)r.get("val_max").asDouble():1,
                r.has("green_dominant")&&r.get("green_dominant").asBoolean()));
        }
        if (root.has("fallback")) for (var fb : root.get("fallback"))
            fallbacks.add(new FallbackRule(TerrainType.valueOf(fb.get("terrain").asText()),
                fb.has("val_max")?(float)fb.get("val_max").asDouble():1));
        configLoaded = true;
        LOG.info("Loaded " + rules.size() + " terrain rules from config");
    }

    private void buildDefaultRules() {
        rules = new ArrayList<>(); fallbacks = new ArrayList<>();
        rules.add(new Rule(TerrainType.ICE, 0,360, 0,0.10f, 0.92f,1));
        rules.add(new Rule(TerrainType.OCEAN, 185,270, 0.08f,1, 0,0.45f));
        rules.add(new Rule(TerrainType.COASTAL_WATER, 185,270, 0.08f,1, 0,1));
        rules.add(new Rule(TerrainType.DESERT, 22,58, 0,0.45f, 0.58f,1));
        rules.add(new Rule(TerrainType.HIGH_MOUNTAIN, 0,360, 0,0.10f, 0.80f,1));
        rules.add(new Rule(TerrainType.FOREST, 70,170, 0,1, 0,0.42f, true));
        rules.add(new Rule(TerrainType.FOREST, 70,170, 0,1, 0,1, true));
        rules.add(new Rule(TerrainType.PLAINS, 130,210, 0,0.35f, 0.50f,0.78f));
        rules.add(new Rule(TerrainType.PLAINS, 55,130, 0.06f,1, 0.50f,0.78f));
        rules.add(new Rule(TerrainType.HILLS, 30,160, 0.04f,1, 0.32f,0.70f));
        rules.add(new Rule(TerrainType.MOUNTAIN, 10,45, 0.12f,1, 0.35f,0.80f));
        rules.add(new Rule(TerrainType.TUNDRA, 50,360, 0,0.22f, 0.45f,0.82f));
        rules.add(new Rule(TerrainType.PLATEAU, 0,360, 0.05f,0.35f, 0.68f,1));
        rules.add(new Rule(TerrainType.WETLAND, 130,210, 0,1, 0,0.38f));
        fallbacks.add(new FallbackRule(TerrainType.OCEAN, 0.35f));
        fallbacks.add(new FallbackRule(TerrainType.FOREST, 0.50f));
        fallbacks.add(new FallbackRule(TerrainType.HILLS, 0.63f));
        fallbacks.add(new FallbackRule(TerrainType.MOUNTAIN, 0.78f));
        fallbacks.add(new FallbackRule(TerrainType.HIGH_MOUNTAIN, 1));
    }

    public void sampleToGrid(TerrainGrid grid) {
        if (reliefImage == null) { new ElevationLoader().loadElevation(grid, null); return; }
        int w = reliefImage.getWidth(), hh = reliefImage.getHeight();
        LOG.info("Sampling terrain into " + grid.getRows() + "×" + grid.getCols() + " grid");
        for (int row = 0; row < grid.getRows(); row++) {
            for (int col = 0; col < grid.getCols(); col++) {
                TerrainCell cell = grid.getCell(row, col);
                int px = (int)((cell.getCenter().getLongitude()+180)/360*w);
                int py = (int)((90-cell.getCenter().getLatitude())/180*hh);
                int rgb = reliefImage.getRGB(Math.max(0,Math.min(w-1,px)), Math.max(0,Math.min(hh-1,py)));
                cell.setTerrain(classifyColor(rgb));
                cell.setElevationMeters(0);
            }
        }
        LOG.info("Terrain sampling complete");
    }

    public static TerrainType classifyColor(int rgb) {
        int r=(rgb>>16)&0xff,g=(rgb>>8)&0xff,b=rgb&0xff;
        float[] hsv=java.awt.Color.RGBtoHSB(r,g,b,null);
        float h=hsv[0]*360,s=hsv[1],v=hsv[2];
        if (!configLoaded) { var rl=new ReliefMapLoader(); try{rl.loadConfig();}catch(Exception e){rl.buildDefaultRules();} }
        for (Rule rule : rules) {
            if(h<rule.hueMin||h>rule.hueMax)continue;
            if(s<rule.satMin||s>rule.satMax)continue;
            if(v<rule.valMin||v>rule.valMax)continue;
            if(rule.greenDom&&!(g>=r&&g>=b-10))continue;
            return rule.terrain;
        }
        for(FallbackRule fb:fallbacks) if(v<=fb.valMax)return fb.terrain;
        return TerrainType.HIGH_MOUNTAIN;
    }

    public static double estimateElevation(int rgb, TerrainType t) { return elevationForType(t); }
    public static int elevationFromRgb(int rgb) { return elevationForType(classifyColor(rgb)); }

    /** Representative elevation (meters) for each terrain type on the 10m hypsometric tints. */
    private static int elevationForType(TerrainType t) {
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

    public static String colorCategory(int rgb) {
        int r=(rgb>>16)&0xff,g=(rgb>>8)&0xff,b=rgb&0xff;
        float[] hsv=java.awt.Color.RGBtoHSB(r,g,b,null);
        float h=hsv[0]*360, s=hsv[1];
        if(s<0.1)return"gray";if(h<30)return"redBrown";if(h<90)return"yellowGreen";
        if(h<150)return"green";if(h<210)return"cyan";return"blue";
    }
    public static int[] sampleColorProfile(BufferedImage img,double lat,double lng,int sampleR){
        int w=img.getWidth(),hh=img.getHeight();
        int cx=(int)((lng+180)/360*w),cy=(int)((90-lat)/180*hh);
        int[] buckets=new int[6];int total=0;
        for(int dy=-sampleR;dy<=sampleR;dy++)for(int dx=-sampleR;dx<=sampleR;dx++){
            int sx=Math.max(0,Math.min(w-1,cx+dx)),sy=Math.max(0,Math.min(hh-1,cy+dy));
            switch(colorCategory(img.getRGB(sx,sy))){
                case"redBrown":buckets[0]++;break;case"yellowGreen":buckets[1]++;break;
                case"green":buckets[2]++;break;case"cyan":buckets[3]++;break;
                case"blue":buckets[4]++;break;case"gray":buckets[5]++;break;
            }total++;}
        if(total>0)for(int i=0;i<6;i++)buckets[i]=buckets[i]*100/total;
        return buckets;
    }

    private record Rule(TerrainType terrain, float hueMin, float hueMax,
                        float satMin, float satMax, float valMin, float valMax, boolean greenDom) {
        Rule(TerrainType t,float hMin,float hMax,float sMin,float sMax,float vMin,float vMax)
        {this(t,hMin,hMax,sMin,sMax,vMin,vMax,false);}
    }
    private record FallbackRule(TerrainType terrain, float valMax) {}
}
