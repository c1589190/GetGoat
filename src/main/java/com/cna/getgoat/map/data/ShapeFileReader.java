package com.cna.getgoat.map.data;

import org.locationtech.jts.geom.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ESRI Shapefile (.shp) reader for PolyLine geometries.
 *
 * Only supports shape type 3 (PolyLine). Reads coordinate data only
 * (no attribute/.dbf parsing). All coordinates are WGS84.
 *
 * Shapefile binary layout:
 *   Header (100 bytes): file code, length, version, shape type, bbox
 *   Records: 8-byte header + variable-length content
 *
 * PolyLine record content:
 *   shapeType(4) + bbox(32) + numParts(4) + numPoints(4) +
 *   parts[numParts](4 each) + points[numPoints](16 each)
 */
public class ShapeFileReader {

    private static final int HEADER_SIZE = 100;
    private static final int RECORD_HEADER_SIZE = 8;

    private final GeometryFactory geomFactory;

    public ShapeFileReader() {
        this.geomFactory = new GeometryFactory(new PrecisionModel(), 4326);
    }

    /**
     * Read all PolyLine geometries from a .shp file.
     */
    public List<LineString> readLines(String filePath) throws IOException {
        return readLines(new File(filePath));
    }

    public List<LineString> readLines(File file) throws IOException {
        List<LineString> lines = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file), 1 << 20))) {

            // Read header
            byte[] header = new byte[HEADER_SIZE];
            dis.readFully(header);
            ByteBuffer hbuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int shapeType = hbuf.getInt(32);

            // Read records
            int recordNum = 0;
            while (true) {
                try {
                    // Record header (big-endian)
                    int recNum = dis.readInt();
                    int contentLen = dis.readInt(); // in 16-bit words

                    // Read record content
                    byte[] content = new byte[contentLen * 2];
                    dis.readFully(content);
                    ByteBuffer rbuf = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);

                    int recShapeType = rbuf.getInt();
                    if (recShapeType == 0) continue; // null shape
                    if (recShapeType != 3 && recShapeType != 13 && recShapeType != 23) {
                        // Skip non-polyline types
                        continue;
                    }

                    // Bounding box (4 doubles)
                    rbuf.getDouble(); // xmin
                    rbuf.getDouble(); // ymin
                    rbuf.getDouble(); // xmax
                    rbuf.getDouble(); // ymax

                    int numParts = rbuf.getInt();
                    int numPoints = rbuf.getInt();

                    // Parts array
                    int[] parts = new int[numParts + 1];
                    for (int i = 0; i < numParts; i++) {
                        parts[i] = rbuf.getInt();
                    }
                    parts[numParts] = numPoints; // sentinel

                    // Points array
                    Coordinate[] allPoints = new Coordinate[numPoints];
                    for (int i = 0; i < numPoints; i++) {
                        double x = rbuf.getDouble();
                        double y = rbuf.getDouble();
                        allPoints[i] = new Coordinate(x, y);
                    }

                    // Extract each part as a separate LineString
                    for (int p = 0; p < numParts; p++) {
                        int start = parts[p];
                        int end = parts[p + 1];
                        if (end - start >= 2) {
                            Coordinate[] coords = new Coordinate[end - start];
                            System.arraycopy(allPoints, start, coords, 0, end - start);
                            lines.add(geomFactory.createLineString(coords));
                        }
                    }

                    recordNum++;
                } catch (EOFException e) {
                    break; // end of file
                }
            }
        }
        return lines;
    }
}
