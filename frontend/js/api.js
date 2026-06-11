const API = {
    getStats:      () => fetch('/api/map/stats').then(r => r.json()),
    getRegions:    () => fetch('/api/map/regions').then(r => r.json()),
    getLabels:     () => fetch('/api/map/labels').then(r => r.json()),
    getAnnotations:() => fetch('/api/map/annotations').then(r => r.json()),
    getDistance:   (lat1,lng1,lat2,lng2) =>
        fetch(`/api/map/distance?lat1=${lat1}&lng1=${lng1}&lat2=${lat2}&lng2=${lng2}`).then(r => r.json()),
    getRadius:     (lat, lng, radiusKm) =>
        fetch(`/api/map/radius?lat=${lat.toFixed(4)}&lng=${lng.toFixed(4)}&r=${radiusKm||100}`).then(r => r.json()),
    // Terrain override CRUD
    getTerrainAt:  (lat, lng) =>
        fetch(`/api/map/terrain-at?lat=${lat}&lng=${lng}`).then(r => r.json()),
    getTerrainOverrides: (bounds) => {
        const p = bounds ? `?south=${bounds.south}&north=${bounds.north}&west=${bounds.west}&east=${bounds.east}` : '';
        return fetch('/api/map/terrain-overrides' + p).then(r => r.json());
    },
    setTerrainOverride: (lat, lng, terrain, elevation) =>
        fetch('/api/map/terrain-overrides', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({lat, lng, terrain: terrain||null, elevation: elevation!=null ? elevation : null})
        }).then(r => r.json()),
    deleteTerrainOverride: (lat, lng) =>
        fetch('/api/map/terrain-overrides', {
            method: 'DELETE',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({overrides: [{lat, lng}]})
        }).then(r => r.json()),
};
