/**
 * Layer management for the GetGoat map.
 *
 * Layers (bottom to top):
 *   1. TerrainTileLayer (custom canvas grid)
 *   2. Region polygons (GeoJSON, semi-transparent)
 *   3. Annotation markers (point/line/polygon)
 *   4. Labels (divIcon text)
 *   5. Interaction overlay (clickable province proxy)
 */

const MapLayers = (function() {

    let map = null;
    let terrainLayer = null;
    let regionLayer = null;
    let annotationLayer = null;
    let labelLayer = null;

    /** Initialize all layers on the given Leaflet map instance. */
    function init(mapInstance) {
        map = mapInstance;

        // 1. Terrain tile layer
        terrainLayer = new TerrainTileLayer({ minZoom: 1, maxZoom: 10 });
        terrainLayer.addTo(map);

        // Load initial terrain data
        const worldBounds = L.latLngBounds([[-90, -180], [90, 180]]);
        terrainLayer.loadTerrain(worldBounds);

        // 2. Region layer (empty until data loads)
        regionLayer = L.geoJSON(null, {
            style: function(feature) {
                return {
                    fillColor: feature.properties.color || '#3388ff',
                    color: feature.properties.color || '#3388ff',
                    weight: 2,
                    opacity: 0.7,
                    fillOpacity: feature.properties.opacity || 0.3,
                };
            },
            onEachFeature: function(feature, layer) {
                layer.bindTooltip(feature.properties.name, {
                    permanent: false,
                    direction: 'center',
                });
                layer.on('click', function(e) {
                    L.DomEvent.stopPropagation(e);
                    showRegionInfo(feature.properties);
                });
            }
        }).addTo(map);

        // 3. Annotation layer
        annotationLayer = L.geoJSON(null, {
            pointToLayer: function(feature, latlng) {
                return L.circleMarker(latlng, {
                    radius: 6,
                    fillColor: '#ff6b6b',
                    color: '#fff',
                    weight: 2,
                    opacity: 1,
                    fillOpacity: 0.8,
                });
            },
            onEachFeature: function(feature, layer) {
                if (feature.properties.label) {
                    layer.bindTooltip(feature.properties.label);
                }
            }
        }).addTo(map);

        // 4. Label layer (rendered separately via MapLabels module)
        labelLayer = L.layerGroup().addTo(map);

        // Load annotations
        loadAnnotations();
    }

    /** Load regions from backend and add to map. */
    async function loadRegions() {
        try {
            const geojson = await API.getRegions();
            if (geojson && geojson.features) {
                regionLayer.clearLayers();
                regionLayer.addData(geojson);
            }
        } catch (err) {
            console.warn('Failed to load regions:', err);
        }
    }

    /** Load annotations from backend and add to map. */
    async function loadAnnotations() {
        try {
            const geojson = await API.getAnnotations();
            if (geojson && geojson.features) {
                annotationLayer.clearLayers();
                annotationLayer.addData(geojson);
            }
        } catch (err) {
            console.warn('Failed to load annotations:', err);
        }
    }

    /** Refresh all data layers. */
    async function refresh() {
        await Promise.all([
            loadRegions(),
            loadAnnotations(),
        ]);
    }

    /** Show region info in the sidebar. */
    function showRegionInfo(props) {
        const panel = document.getElementById('info-panel');
        const title = document.getElementById('info-title');
        const body = document.getElementById('info-body');

        panel.classList.remove('hidden');
        title.textContent = props.name || 'Region';

        let html = '';
        html += `<div class="info-row"><span class="info-label">Category</span><span class="info-value">${props.category || '—'}</span></div>`;
        html += `<div class="info-row"><span class="info-label">ID</span><span class="info-value">${props.id || '—'}</span></div>`;

        body.innerHTML = html;
    }

    /** Show terrain info at a clicked point. */
    async function showTerrainInfo(lat, lng) {
        const panel = document.getElementById('info-panel');
        const title = document.getElementById('info-title');
        const body = document.getElementById('info-body');

        panel.classList.remove('hidden');
        title.textContent = `Location: ${lat.toFixed(2)}°, ${lng.toFixed(2)}°`;

        try {
            const info = await API.getTerrainAt(lat, lng);
            let html = '';
            html += `<div class="info-row"><span class="info-label">Terrain</span><span class="info-value">${info.terrain}</span></div>`;
            html += `<div class="info-row"><span class="info-label">Elevation</span><span class="info-value">${Math.round(info.elevation)} m</span></div>`;
            if (info.regions && info.regions.length > 0) {
                html += `<div class="info-row"><span class="info-label">Regions</span><span class="info-value">${info.regions.join(', ')}</span></div>`;
            }
            body.innerHTML = html;
        } catch (err) {
            body.innerHTML = '<div class="info-row">Could not load terrain data</div>';
        }
    }

    return {
        init,
        refresh,
        loadRegions,
        showTerrainInfo,
        showRegionInfo,
        get terrainLayer() { return terrainLayer; },
    };

})();
