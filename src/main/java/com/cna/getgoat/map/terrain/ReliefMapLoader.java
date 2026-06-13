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
 *
 * @deprecated Prefer {@link ColorClassifier} for classification logic and
 *             {@link GeoTIFFMapLoader} for regional image access. This class
 *             is retained for full-image batch operations (initial cache
 *             generation and admin re-classification).
 */
@Deprecated
public class ReliefMapLoader {

    private static final Logger LOG = Logger.getLogger(ReliefMapLoader.class.getName());
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
        ColorClassifier.loadConfigFromFile(f);
    }

    private void loadConfig() throws IOException {
        ColorClassifier.loadConfig();
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
        return ColorClassifier.classifyColor(rgb);
    }

    public static double estimateElevation(int rgb, TerrainType t) {
        return ColorClassifier.estimateElevation(rgb, t);
    }

    public static int elevationFromRgb(int rgb) {
        return ColorClassifier.elevationFromRgb(rgb);
    }

    public static int elevationForType(TerrainType t) {
        return ColorClassifier.elevationForType(t);
    }

    public static String colorCategory(int rgb) {
        return ColorClassifier.colorCategory(rgb);
    }

    public static int[] sampleColorProfile(BufferedImage img, double lat, double lng, int sampleR) {
        return ColorClassifier.sampleColorProfile(img, lat, lng, sampleR);
    }
}
