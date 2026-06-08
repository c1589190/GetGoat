/**
 * Text label rendering on the map.
 *
 * Labels are rendered as Leaflet divIcons so they appear as
 * styled text on the map, scaling with zoom level.
 */

const MapLabels = (function() {

    let labelLayer = null;

    /** Initialize label rendering. */
    function init(layerGroup) {
        labelLayer = layerGroup;
    }

    /** Load labels from backend and render them. */
    async function load() {
        try {
            const labels = await API.getLabels();
            labelLayer.clearLayers();

            labels.forEach(function(label) {
                const icon = L.divIcon({
                    className: 'map-label',
                    html: `<span style="
                        font-family: sans-serif;
                        font-size: ${label.fontSize || 12}px;
                        color: ${label.color || '#333'};
                        text-align: ${label.alignment || 'center'};
                        text-shadow: 0 0 3px rgba(255,255,255,0.8);
                        white-space: nowrap;
                    ">${label.text}</span>`,
                    iconSize: [0, 0],
                    iconAnchor: [0, 0],
                });

                L.marker([label.lat, label.lng], {
                    icon: icon,
                    interactive: false,
                }).addTo(labelLayer);
            });

            console.log(`Rendered ${labels.length} labels`);
        } catch (err) {
            console.warn('Failed to load labels:', err);
        }
    }

    return { init, load };

})();
