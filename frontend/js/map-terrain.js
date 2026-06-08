/**
 * Custom Leaflet GridLayer that renders terrain cells using Canvas.
 *
 * Each tile is a 256×256 canvas. The backend provides terrain data as
 * a flat array of {r, c, t, e, clr} objects for the tile's bounds.
 *
 * For efficiency, we cache tile data and use LOD: at low zoom levels,
 * we aggregate cells to reduce draw calls.
 */

// Terrain color lookup by terrain type ordinal
const TERRAIN_COLORS = [
    '#1a5276',  // 0: OCEAN
    '#2980b9',  // 1: COASTAL_WATER
    '#7dce82',  // 2: PLAINS
    '#a2b573',  // 3: HILLS
    '#8b7355',  // 4: MOUNTAIN
    '#d5d5d5',  // 5: HIGH_MOUNTAIN
    '#c9a96e',  // 6: PLATEAU
    '#e8c382',  // 7: DESERT
    '#2d7d3a',  // 8: FOREST
    '#4a6b4a',  // 9: TAIGA
    '#b8c9a8',  // 10: TUNDRA
    '#e8e8f0',  // 11: ICE
    '#5d8a6e',  // 12: WETLAND
    '#888888',  // 13: URBAN
];

// We store the terrain grid data globally after first fetch
let terrainData = null;  // { cells: [...], rows: N, cols: M, cellSize: S }
let terrainColors = TERRAIN_COLORS; // can be overridden from backend

const TerrainTileLayer = L.GridLayer.extend({

    options: {
        tileSize: 256,
        minZoom: 1,
        maxZoom: 10,
    },

    initialize: function(options) {
        L.setOptions(this, options);
        this._tileCache = {};
    },

    createTile: function(coords) {
        const tile = L.DomUtil.create('canvas', 'leaflet-tile');
        tile.width = this.options.tileSize;
        tile.height = this.options.tileSize;
        const ctx = tile.getContext('2d');

        const bounds = this._tileCoordsToBounds(coords);
        this._drawTerrainTile(ctx, bounds, coords.z);

        return tile;
    },

    _drawTerrainTile: function(ctx, bounds, zoom) {
        const south = bounds.getSouth();
        const north = bounds.getNorth();
        const west = bounds.getWest();
        const east = bounds.getEast();

        if (!terrainData || !terrainData.cells || terrainData.cells.length === 0) {
            // No data — fill with ocean blue
            ctx.fillStyle = '#1a5276';
            ctx.fillRect(0, 0, ctx.canvas.width, ctx.canvas.height);
            return;
        }

        const width = ctx.canvas.width;
        const height = ctx.canvas.height;
        const cellSize = terrainData.cellSize;

        // Determine which cells are visible
        const latSpan = north - south;
        const lngSpan = east - west;

        // Draw each cell that falls within this tile
        const cells = terrainData.cells;
        for (let i = 0; i < cells.length; i++) {
            const cell = cells[i];
            // Cell center
            const cellLat = -90 + (cell.r + 0.5) * cellSize;
            const cellLng = -180 + (cell.c + 0.5) * cellSize;

            // Check if cell overlaps this tile
            const cellSouth = cellLat - cellSize / 2;
            const cellNorth = cellLat + cellSize / 2;
            const cellWest = cellLng - cellSize / 2;
            const cellEast = cellLng + cellSize / 2;

            if (cellNorth < south || cellSouth > north) continue;
            if (cellEast < west || cellWest > east) continue;

            // Project to pixel coordinates within this tile
            const px1 = ((cellWest - west) / lngSpan) * width;
            const px2 = ((cellEast - west) / lngSpan) * width;
            const py1 = ((north - cellNorth) / latSpan) * height;
            const py2 = ((north - cellSouth) / latSpan) * height;

            const color = cell.clr || (cell.t !== undefined ? TERRAIN_COLORS[cell.t] : '#1a5276');
            ctx.fillStyle = color;
            ctx.fillRect(
                Math.floor(px1),
                Math.floor(py1),
                Math.ceil(px2 - px1),
                Math.ceil(py2 - py1)
            );
        }
    },

    /**
     * Load terrain data for the given bounds from the backend.
     */
    loadTerrain: async function(bounds) {
        try {
            const data = await API.getTerrain(
                bounds.getSouth(), bounds.getNorth(),
                bounds.getWest(), bounds.getEast()
            );
            terrainData = data;
            this.redraw();
        } catch (err) {
            console.warn('Failed to load terrain data:', err);
        }
    }
});
