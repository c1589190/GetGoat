package com.cna.getgoat.map.network;

import com.cna.getgoat.map.geometry.SphericalEngine;
import org.locationtech.jts.geom.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.logging.Logger;

/**
 * Lazy-loaded river network — streams the .shp file per 5° cell,
 * filtering by bounding box. Returns river segments as JTS LineStrings.
 */
public class RiverNetwork {

    private static final Logger LOG = Logger.getLogger(RiverNetwork.class.getName());
    private static final int CELL_SIZE = 5;

    private final String shpPath;
    private final GeometryFactory gf;
    private final Map<String, List<LineString>> cache = new LinkedHashMap<>();
    private boolean available;

    private static String findFile(String relPath) {
        for (String base : new String[]{"", "../", "src/main/resources/geodata/",
                "../../src/main/resources/geodata/", "../../../src/main/resources/geodata/",
                "../../../../src/main/resources/geodata/"}) {
            File f = new File(base + relPath);
            if (f.exists()) return f.getPath();
        }
        return null;
    }

    public RiverNetwork() {
        this.shpPath = findFile("rivers/ne_10m_rivers_lake_centerlines.shp");
        this.available = shpPath != null;
        this.gf = new GeometryFactory(new PrecisionModel(), 4326);
        if (available) LOG.info("River shapefile found — lazy loading enabled");
    }

    public boolean isAvailable() { return available; }

    public void ensureRegion(double lat, double lng, double radiusKm) {
        if (!available) return;
        double margin = radiusKm / 111.32 + CELL_SIZE;
        int minLb = (int) Math.floor((lat - margin) / CELL_SIZE);
        int maxLb = (int) Math.floor((lat + margin) / CELL_SIZE);
        int minMb = (int) Math.floor((lng - margin) / CELL_SIZE);
        int maxMb = (int) Math.floor((lng + margin) / CELL_SIZE);

        for (int lb = minLb; lb <= maxLb; lb++)
            for (int mb = minMb; mb <= maxMb; mb++) {
                String key = lb + ":" + mb;
                if (!cache.containsKey(key)) loadCell(lb, mb);
            }

        if (cache.size() > 12) cache.remove(cache.keySet().iterator().next());
    }

    private void loadCell(int latBin, int lngBin) {
        double s = latBin * CELL_SIZE, n = s + CELL_SIZE;
        double w = lngBin * CELL_SIZE, e = w + CELL_SIZE;
        String key = latBin + ":" + lngBin;
        List<LineString> rivers = new ArrayList<>();
        long t0 = System.currentTimeMillis();

        try (InputStream is = new BufferedInputStream(new FileInputStream(shpPath), 1 << 20)) {
            is.skipNBytes(100);
            byte[] hdr = new byte[8];
            byte[] buf = new byte[0];
            while (true) {
                if (is.readNBytes(hdr, 0, 8) < 8) break;
                int len = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                int blen = len * 2;
                if (buf.length < blen) buf = new byte[blen];
                is.readNBytes(buf, 0, blen);
                ByteBuffer cb = ByteBuffer.wrap(buf, 0, blen).order(ByteOrder.LITTLE_ENDIAN);
                int st = cb.getInt();
                if (st != 3 && st != 13 && st != 23) continue;
                double xmin = cb.getDouble(), ymin = cb.getDouble(), xmax = cb.getDouble(), ymax = cb.getDouble();
                if (ymax < s || ymin > n || xmax < w || xmin > e) continue;
                int np = cb.getInt(), npt = cb.getInt();
                int[] parts = new int[np + 1];
                for (int i = 0; i < np; i++) parts[i] = cb.getInt();
                parts[np] = npt;
                Coordinate[] pts = new Coordinate[npt];
                for (int i = 0; i < npt; i++) pts[i] = new Coordinate(cb.getDouble(), cb.getDouble());
                for (int p = 0; p < np; p++) {
                    int stIdx = parts[p], endIdx = parts[p + 1];
                    if (endIdx - stIdx >= 2) {
                        rivers.add(gf.createLineString(
                            Arrays.copyOfRange(pts, stIdx, endIdx)));
                    }
                }
            }
        } catch (IOException ex) { LOG.warning("River cell failed: " + key); }
        cache.put(key, rivers);
        if (!rivers.isEmpty())
            LOG.info(String.format("River cell %s: %d segments (%dms)", key, rivers.size(), System.currentTimeMillis() - t0));
    }

    /** All river segments within a radius of (lat, lng). */
    public List<LineString> findInRadius(double lat, double lng, double radiusKm) {
        if (!available) return List.of();
        List<LineString> result = new ArrayList<>();
        for (List<LineString> cellRivers : cache.values()) {
            for (LineString line : cellRivers) {
                // Quick check: is any part of the line within radius?
                for (Coordinate c : line.getCoordinates()) {
                    double d = SphericalEngine.haversineDistance(lat, lng, c.y, c.x);
                    if (d <= radiusKm * 1.5) { result.add(line); break; }
                }
            }
        }
        return result;
    }

    /** Find which terrain cells a river intersects + the terrain types it passes through. */
    public record RiverCellInfo(String riverId, int cellRow, int cellCol, String terrainType) {}
}
