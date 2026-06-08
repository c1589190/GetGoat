package com.getgoat.map.geometry;

import com.getgoat.map.model.GeoPoint;

/**
 * Utility methods for converting between spherical (lat/lng) and
 * planar (x/y) coordinate systems for map rendering.
 *
 * Primary projections:
 *   - Equirectangular (Plate Carrée): simplest, lat/lng → x/y directly
 *   - Mercator: conformal, preserves angles, distorts area near poles
 *   - Robinson: compromise, good for world maps
 */
public class ProjectionUtil {

    /**
     * Equirectangular projection (Plate Carrée).
     * Simplest projection: lat → y, lng → x linearly.
     * Used as the basis for Leaflet's default CRS.Simple / EPSG:4326 display.
     */
    public static class Equirectangular {
        public static double lngToX(double lng) { return lng; }
        public static double latToY(double lat) { return lat; }
        public static double xToLng(double x) { return x; }
        public static double yToLat(double y) { return y; }
    }

    /**
     * Web Mercator projection (EPSG:3857).
     * Used by Leaflet by default. Lat/lng → pixel coordinates at a given zoom level.
     *
     * Tile size: 256px, world bounds in lat: ~±85.0511°
     */
    public static class WebMercator {
        private static final double TILE_SIZE = 256.0;
        private static final double MAX_LAT = 85.0511287798066;

        /**
         * Latitude/longitude → pixel XY at a given zoom level.
         * Origin (0,0) at lat ~85°, lng -180°.
         */
        public static double lngToPixelX(double lng, int zoom) {
            double scale = TILE_SIZE * Math.pow(2, zoom) / 360.0;
            return (lng + 180.0) * scale;
        }

        public static double latToPixelY(double lat, int zoom) {
            double scale = TILE_SIZE * Math.pow(2, zoom) / 360.0;
            double latRad = Math.toRadians(Math.max(-MAX_LAT, Math.min(MAX_LAT, lat)));
            double mercatorN = Math.log(Math.tan(Math.PI / 4.0 + latRad / 2.0));
            return (180.0 - Math.toDegrees(mercatorN)) * scale;
        }

        public static double pixelXToLng(double px, int zoom) {
            double scale = TILE_SIZE * Math.pow(2, zoom) / 360.0;
            return px / scale - 180.0;
        }

        public static double pixelYToLat(double py, int zoom) {
            double scale = TILE_SIZE * Math.pow(2, zoom) / 360.0;
            double mercatorN = -Math.toRadians(py / scale - 180.0);
            return Math.toDegrees(Math.atan(Math.sinh(mercatorN)));
        }

        /**
         * Tile coordinates (x, y) for a given lat/lng at a zoom level.
         * Leaflet uses tile coordinates where origin (0,0) is top-left.
         */
        public static int lngToTileX(double lng, int zoom) {
            return (int) Math.floor((lng + 180.0) / 360.0 * Math.pow(2, zoom));
        }

        public static int latToTileY(double lat, int zoom) {
            double latRad = Math.toRadians(Math.max(-MAX_LAT, Math.min(MAX_LAT, lat)));
            return (int) Math.floor(
                (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)
                    / 2.0 * Math.pow(2, zoom)
            );
        }
    }

    /**
     * Robinson projection — compromise projection good for world maps.
     * Looks more natural than equirectangular.
     *
     * X = 0.8487 * R * (λ - λ₀) * A(φ)  scaled
     * Y = 1.3523 * R * B(φ)             scaled
     *
     * where A(φ) and B(φ) are lookup-table-based.
     */
    public static class Robinson {
        // Robinson projection lookup table entries {lat_degree, X_PLEN, Y_PLEN}
        // From Robinson (1974), normalized to length 1.0
        private static final double[][] TABLE = {
            {0,    1.0000, 0.0000},
            {5,    0.9986, 0.0620},
            {10,   0.9954, 0.1240},
            {15,   0.9900, 0.1860},
            {20,   0.9822, 0.2480},
            {25,   0.9730, 0.3100},
            {30,   0.9600, 0.3720},
            {35,   0.9427, 0.4340},
            {40,   0.9216, 0.4958},
            {45,   0.8962, 0.5571},
            {50,   0.8679, 0.6176},
            {55,   0.8350, 0.6769},
            {60,   0.7986, 0.7346},
            {65,   0.7597, 0.7903},
            {70,   0.7186, 0.8435},
            {75,   0.6732, 0.8936},
            {80,   0.6213, 0.9394},
            {85,   0.5722, 0.9761},
            {90,   0.5322, 1.0000},
        };

        /**
         * Project lat/lng to Robinson XY (normalized to [-1, 1] range).
         */
        public static double[] project(double lat, double lng) {
            double absLat = Math.abs(lat);
            double[] row = interpolateTable(absLat);

            double x = 0.8487 * row[0] * (lng / 180.0);
            double y = 1.3523 * row[1] * Math.signum(lat);

            return new double[]{x, y};
        }

        private static double[] interpolateTable(double lat) {
            if (lat >= 90) return new double[]{TABLE[18][1], TABLE[18][2]};
            if (lat <= 0) return new double[]{TABLE[0][1], TABLE[0][2]};

            int loIdx = (int) (lat / 5.0);
            int hiIdx = loIdx + 1;
            double t = (lat - TABLE[loIdx][0]) / 5.0;

            double x = TABLE[loIdx][1] + t * (TABLE[hiIdx][1] - TABLE[loIdx][1]);
            double y = TABLE[loIdx][2] + t * (TABLE[hiIdx][2] - TABLE[loIdx][2]);
            return new double[]{x, y};
        }
    }

    /**
     * Convert a GeoPoint to an (x, y) pair using the equirectangular projection.
     */
    public static double[] toEquirectangular(GeoPoint point) {
        return new double[]{
            Equirectangular.lngToX(point.getLongitude()),
            Equirectangular.latToY(point.getLatitude())
        };
    }

    /**
     * Convert a GeoPoint to pixel coordinates (Web Mercator) at a given zoom.
     */
    public static double[] toWebMercatorPixel(GeoPoint point, int zoom) {
        return new double[]{
            WebMercator.lngToPixelX(point.getLongitude(), zoom),
            WebMercator.latToPixelY(point.getLatitude(), zoom)
        };
    }
}
