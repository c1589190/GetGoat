const API = {
    getStats:      () => fetch('/api/map/stats').then(r => r.json()),
    getRegions:    () => fetch('/api/map/regions').then(r => r.json()),
    getLabels:     () => fetch('/api/map/labels').then(r => r.json()),
    getAnnotations:() => fetch('/api/map/annotations').then(r => r.json()),
    getDistance:   (lat1,lng1,lat2,lng2) =>
        fetch(`/api/map/distance?lat1=${lat1}&lng1=${lng1}&lat2=${lat2}&lng2=${lng2}`).then(r => r.json()),
    getRadius:     (lat, lng, radiusKm) =>
        fetch(`/api/map/radius?lat=${lat.toFixed(4)}&lng=${lng.toFixed(4)}&r=${radiusKm||100}`).then(r => r.json()),
};
