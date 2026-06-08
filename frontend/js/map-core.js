(function(){
'use strict';
var map=L.map('map',{center:[25,10],zoom:3,minZoom:2,maxZoom:12,zoomControl:true,worldCopyJump:true});
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{attribution:'&copy; OSM',maxZoom:18}).addTo(map);
var regionLayer=L.geoJSON(null,{style:function(f){return {fillColor:f.properties.color||'#3388ff',color:f.properties.color||'#3388ff',weight:2,opacity:0.7,fillOpacity:f.properties.opacity||0.25};}}).addTo(map);
var terrainCellLayer=L.geoJSON(null,{renderer:L.canvas(),style:function(f){return {fillColor:f.properties.color||'#888',color:'transparent',weight:0,opacity:1,fillOpacity:0.55};},onEachFeature:function(f,l){var t=f.properties.terrain;l.bindTooltip(t,{direction:'center'});}}).addTo(map);
var riverLayer=L.geoJSON(null,{style:{color:'#2980b9',weight:1.5,opacity:0.7}}).addTo(map);
var roadLayer=L.geoJSON(null,{style:function(f){return {color:f.properties.marked?'#FF5722':'#FF9800',weight:f.properties.marked?3:1.5,opacity:f.properties.marked?1:0.65};}}).addTo(map);
var labelGroup=L.layerGroup().addTo(map),cityMarkerLayer=L.layerGroup().addTo(map);
var selectCircle=null,selectMarker=null,currentRadius=100;

document.getElementById('sidebar-toggle').addEventListener('click',function(){
    var sb=document.getElementById('sidebar');sb.classList.toggle('collapsed');
    this.textContent=sb.classList.contains('collapsed')?'▶':'◀';map.invalidateSize();
});
var dt=document.getElementById('distance-tool');
var rdv=document.createElement('div');rdv.style.marginTop='12px';
rdv.innerHTML='<h3>Radius</h3><p style="font-size:10px;color:#888">Click map, drag slider</p>';
var rs=document.createElement('input');rs.type='range';rs.min=10;rs.max=500;rs.value=100;rs.step=10;rs.style.width='100%';
var rl=document.createElement('span');rl.textContent='100 km';rl.style.cssText='font-size:12px;color:#FF5722;font-weight:bold';
rs.addEventListener('input',function(){currentRadius=parseInt(this.value);rl.textContent=currentRadius+' km';
    if(selectMarker){var ll=selectMarker.getLatLng();if(selectCircle)map.removeLayer(selectCircle);
    selectCircle=drawCircle(ll.lat,ll.lng,currentRadius*1000);loadData(ll.lat,ll.lng,currentRadius);}});
rdv.appendChild(rl);rdv.appendChild(rs);dt.parentNode.insertBefore(rdv,dt);

map.on('click',function(e){if(e.originalEvent.shiftKey)return;
    // Clear all overlays on map click
    if(combatRadiusCircle){combatRadiusLayer.removeLayer(combatRadiusCircle);combatRadiusCircle=null;}
    terrainCellLayer.clearLayers();
    clearLayers();
    selectedUnit=null;
    document.getElementById('unit-detail').style.display='none';
    if(typeof clearMovePaths==='function') clearMovePaths();
});

async function loadData(lat,lng,r){
    var p=document.getElementById('info-panel');p.classList.remove('hidden');
    document.getElementById('info-title').textContent=lat.toFixed(4)+'°, '+lng.toFixed(4)+'°';
    document.getElementById('info-body').innerHTML='Loading...';
    try{
        var d=await(await fetch('/api/map/radius-enhanced?lat='+lat.toFixed(4)+'&lng='+lng.toFixed(4)+'&r='+r)).json();
        var h='';h+=row('Terrain',d.terrain||'—');h+=row('Elevation',(d.elevation?d.elevation.mean:'?')+'m');
        if(d.terrainProfile){var tp=d.terrainProfile;
            h+='<div style="margin:4px 0;font-size:10px;line-height:1.5"><b>Terrain:</b> ';
            var entries=Object.entries(tp).sort(function(a,b){return b[1]-a[1];});
            entries.forEach(function(e){h+='<span>'+e[0]+' '+e[1]+'%</span> | ';});
            h=h.slice(0,-3)+'</div>';}
        if(d.roughness){var rp=d.roughness;
            h+='<div style="margin:4px 0;font-size:10px;line-height:1.5"><b>Relief:</b> ';
            var re=Object.entries(rp).sort(function(a,b){return b[1]-a[1];});
            re.forEach(function(e){h+='<span>'+e[0]+' '+e[1]+'%</span> | ';});
            h=h.slice(0,-3)+'</div>';}
        h+=row('Roads',d.roadNodes+' nodes / '+d.roadSegments+' segs');
        if(d.cities&&d.cities.length)h+=row('Cities',d.cities.map(function(c){return c.name;}).join(', '));
        document.getElementById('info-body').innerHTML=h;
        var prov=await(await fetch('/api/map/province-at?lat='+lat.toFixed(4)+'&lng='+lng.toFixed(4)+'&res=1.0')).json();
        if(prov.province&&prov.found!==false)document.getElementById('info-body').innerHTML+='<br>'+row('Province',prov.province.properties.name+', '+prov.province.properties.country);
        var cells=await(await fetch('/api/map/terrain-cells?lat='+lat.toFixed(4)+'&lng='+lng.toFixed(4)+'&r='+r)).json();
        terrainCellLayer.clearLayers();if(cells&&cells.features)terrainCellLayer.addData(cells);
        cityMarkerLayer.clearLayers();if(d.cities)d.cities.forEach(function(c){L.circleMarker([c.lat,c.lng],{radius:4,fillColor:'#fff',color:'#333',weight:1.5,fillOpacity:1}).bindTooltip(c.name,{direction:'top'}).addTo(cityMarkerLayer);});
        var rv=await(await fetch('/api/map/rivers-in-radius?lat='+lat.toFixed(4)+'&lng='+lng.toFixed(4)+'&r='+r)).json();
        riverLayer.clearLayers();if(rv&&rv.features)riverLayer.addData(rv);
        var rd=await(await fetch('/api/map/roads-in-radius?lat='+lat.toFixed(4)+'&lng='+lng.toFixed(4)+'&r='+r)).json();
        roadLayer.clearLayers();if(rd&&rd.features)roadLayer.addData(rd);
    }catch(err){document.getElementById('info-body').innerHTML='Error: '+err.message;}
}
function row(l,v){return '<div class="info-row"><span class="info-label">'+l+'</span><span class="info-value">'+v+'</span></div>';}
function clearLayers(){if(selectCircle){map.removeLayer(selectCircle);selectCircle=null;}if(selectMarker){map.removeLayer(selectMarker);selectMarker=null;}terrainCellLayer.clearLayers();cityMarkerLayer.clearLayers();riverLayer.clearLayers();roadLayer.clearLayers();}
function drawCircle(lat,lng,radiusM){var pts=[],steps=64;for(var i=0;i<=steps;i++){var b=2*Math.PI*i/steps;var d=radiusM/6371000;var p1=Math.asin(Math.sin(lat*Math.PI/180)*Math.cos(d)+Math.cos(lat*Math.PI/180)*Math.sin(d)*Math.cos(b));var l1=lng*Math.PI/180+Math.atan2(Math.sin(b)*Math.sin(d)*Math.cos(lat*Math.PI/180),Math.cos(d)-Math.sin(lat*Math.PI/180)*Math.sin(p1));pts.push([p1*180/Math.PI,l1*180/Math.PI]);}return L.polygon(pts,{color:'#FF5722',weight:2,fillColor:'#FF5722',fillOpacity:0.06,interactive:false}).addTo(map);}
var fromPt=null,toPt=null,fromMk=null,toMk=null,distLn=null;
map._container.addEventListener('click',function(e){if(!e.shiftKey)return;L.DomEvent.stopPropagation(e);
    var rect=map._container.getBoundingClientRect();var ll=map.containerPointToLatLng([e.clientX-rect.left,e.clientY-rect.top]);if(!ll)return;
    if(!fromPt){fromPt=ll;document.getElementById('from-coord').textContent=ll.lat.toFixed(4)+'°, '+ll.lng.toFixed(4)+'°';if(fromMk)map.removeLayer(fromMk);fromMk=L.circleMarker(ll,{radius:6,fillColor:'#2196F3',color:'#fff',weight:2,fillOpacity:1}).addTo(map);}
    else if(!toPt){toPt=ll;document.getElementById('to-coord').textContent=ll.lat.toFixed(4)+'°, '+ll.lng.toFixed(4)+'°';if(toMk)map.removeLayer(toMk);toMk=L.circleMarker(ll,{radius:6,fillColor:'#F44336',color:'#fff',weight:2,fillOpacity:1}).addTo(map);
        fetch('/api/map/distance?lat1='+fromPt.lat+'&lng1='+fromPt.lng+'&lat2='+toPt.lat+'&lng2='+toPt.lng).then(function(r){return r.json();}).then(function(r){document.getElementById('distance-result').classList.remove('hidden');document.getElementById('distance-value').textContent=Math.round(r.distanceKm)+' km';document.getElementById('bearing-value').textContent='Bearing: '+r.bearingDeg.toFixed(1)+'°';if(distLn)map.removeLayer(distLn);distLn=L.polyline(gc(fromPt.lat,fromPt.lng,toPt.lat,toPt.lng,60),{color:'#FF5722',weight:2,dashArray:'8,4',opacity:0.8}).addTo(map);});}
},true);
function gc(l1,n1,l2,n2,c){var tr=Math.PI/180,td=180/Math.PI,p=[],p1=l1*tr,a1=n1*tr,p2=l2*tr,a2=n2*tr;var d=2*Math.asin(Math.sqrt(Math.pow(Math.sin((p2-p1)/2),2)+Math.cos(p1)*Math.cos(p2)*Math.pow(Math.sin((a2-a1)/2),2)));for(var i=0;i<=c;i++){var f=i/c,A=Math.sin((1-f)*d)/Math.sin(d),B=Math.sin(f*d)/Math.sin(d);var x=A*Math.cos(p1)*Math.cos(a1)+B*Math.cos(p2)*Math.cos(a2);var y=A*Math.cos(p1)*Math.sin(a1)+B*Math.cos(p2)*Math.sin(a2);var z=A*Math.sin(p1)+B*Math.sin(p2);p.push([Math.atan2(z,Math.sqrt(x*x+y*y))*td,Math.atan2(y,x)*td]);}return p;}
document.getElementById('clear-distance').addEventListener('click',function(){fromPt=toPt=null;if(fromMk){map.removeLayer(fromMk);fromMk=null;}if(toMk){map.removeLayer(toMk);toMk=null;}if(distLn){map.removeLayer(distLn);distLn=null;}document.getElementById('from-coord').textContent='—';document.getElementById('to-coord').textContent='—';document.getElementById('distance-result').classList.add('hidden');});
var sl=L.control({position:'bottomright'});sl.onAdd=function(){var d=L.DomUtil.create('div','');d.style.cssText='background:rgba(0,0,0,0.7);color:#fff;padding:4px 8px;font-size:10px;border-radius:3px';d.innerHTML='<span id="tsrc">loading...</span>';return d;};sl.addTo(map);
async function init(){
    try{var s=await(await fetch('/api/map/stats')).json();document.getElementById('stats-body').innerHTML='Cells: '+(s.totalCells||0).toLocaleString()+'<br>Land: '+(s.landCells||0).toLocaleString()+' / Water: '+(s.waterCells||0).toLocaleString()+'<br>Regions: '+s.regionCount+' | Labels: '+s.labelCount;var el=document.getElementById('tsrc');if(el&&s.terrainDistribution){var parts=[];Object.entries(s.terrainDistribution).forEach(function(x){if(x[1]>0&&x[0]!=='Coastal Water'&&x[0]!=='Ocean')parts.push(x[0]+':'+x[1].toLocaleString());});el.textContent='10m ('+parts.join(', ')+')';}}catch(e){}
    try{var r=await(await fetch('/api/map/regions')).json();if(r&&r.features)regionLayer.addData(r);}catch(e){}
    try{var lbls=await(await fetch('/api/map/labels')).json();if(Array.isArray(lbls))lbls.forEach(function(l){L.marker([l.lat,l.lng],{icon:L.divIcon({className:'map-label',html:'<span style="font-family:sans-serif;font-size:'+(l.fontSize||12)+'px;color:'+(l.color||'#333')+';text-shadow:0 0 4px rgba(255,255,255,0.9);white-space:nowrap">'+l.text+'</span>',iconSize:[0,0],iconAnchor:[0,0]}),interactive:false}).addTo(labelGroup);});}catch(e){}
    window.loadData=loadData;window.selectMarker=selectMarker;window.currentRadius=currentRadius;
}
map.whenReady(init);

// ===== Unit management =====
var unitLayer=L.layerGroup().addTo(map);
var unitPathLayer=L.layerGroup().addTo(map);
var movePathLayer=L.layerGroup().addTo(map);       // branch movement polylines
var combatRadiusLayer=L.layerGroup().addTo(map);   // unit operational radius
var combatRadiusCircle=null;
var unitMarkers={}, selectedUnit=null, lastClickLat=0, lastClickLng=0;
var currentMovesData=[];  // cached movement data for current branch node

function loadUnits(filterSource){
    var url='/api/map/units'+(filterSource?'?source='+encodeURIComponent(filterSource):'');
    fetch(url).then(function(r){return r.json();}).then(function(units){
        if(!Array.isArray(units)) return;
        unitLayer.clearLayers(); unitMarkers={};
        var list=document.getElementById('unit-list'); list.innerHTML='';
        var cnt=document.getElementById('unit-count');
        if(cnt) cnt.textContent='('+units.length+')';
        if(units.length===0){list.innerHTML='<span style="color:#888">No units</span>';return;}
        for(var i=0;i<units.length;i++){(function(u){
            var mk=L.circleMarker([u.lat,u.lng],{radius:5,fillColor:u.color,color:'#fff',weight:1,fillOpacity:0.85})
                .bindTooltip(u.name,{direction:'top'}).addTo(unitLayer);
            mk.on('click',function(e){L.DomEvent.stopPropagation(e);selectUnit(u);});
            unitMarkers[u.code]=mk;
            var item=document.createElement('div');
            item.style.cssText='cursor:pointer;padding:2px 4px;margin:1px 0;border-left:3px solid '+u.color+';font-size:10px';
            item.textContent=u.name;
            item.onclick=function(e){selectUnit(u);map.setView([u.lat,u.lng],6);};
            list.appendChild(item);
        })(units[i]);}
    }).catch(function(e){console.warn(e);});
}

function loadSources(){
    fetch('/api/map/units/sources').then(function(r){return r.json();}).then(function(d){
        var sel=document.getElementById('unit-source-filter');
        if(!sel) return; sel.innerHTML='<option value="">All</option>';
        (d.sources||[]).forEach(function(s){sel.innerHTML+='<option value="'+s+'">'+s+'</option>';});
    }).catch(function(){});
}

function selectUnit(u){
    selectedUnit=u;

    // Build unit detail with branch context
    var detailEl = document.getElementById('unit-detail');
    detailEl.style.display='block';
    document.getElementById('ud-name').textContent=u.name||'?';
    document.getElementById('ud-code').textContent=u.code||'?';
    document.getElementById('ud-desc').textContent=u.description||'';
    document.getElementById('ud-source').textContent='Source: '+(u.source||'?')+' | Type: '+(u.type||'?')+' | Status: '+(u.status||'active');
    document.getElementById('ud-lat').textContent='Lat: '+(u.lat||0).toFixed(4)+' Lng: '+(u.lng||0).toFixed(4);
    // Show branch context if available
    var bc = document.getElementById('branch-context');
    if (bc && bc.style.display !== 'none') {
        document.getElementById('ud-type').textContent = '📍 Current branch: ' + bc.textContent.replace('📍 ','');
    } else {
        document.getElementById('ud-type').textContent = '';
    }

    // Big combat radius + terrain cells
    combatRadiusLayer.clearLayers(); combatRadiusCircle = null;
    var radiusKm = {infantry:12, naval:60, air:150, civilian:5, supply:20, generic:10}[u.type] || 10;
    combatRadiusCircle = L.circle([u.lat, u.lng], {
        radius: radiusKm * 1000,
        color: u.color || '#888',
        fillColor: u.color || '#888',
        fillOpacity: 0.06,
        weight: 2,
        dashArray: '8,4',
        interactive: false
    }).addTo(combatRadiusLayer);

    // Fetch terrain cells within combat radius
    var url = '/api/map/terrain-cells?lat=' + u.lat.toFixed(4) + '&lng=' + u.lng.toFixed(4) + '&r=' + radiusKm;
    terrainCellLayer.clearLayers();
    fetch(url).then(function(r){return r.json();}).then(function(data){
        if (data && data.features) terrainCellLayer.addData(data);
    }).catch(function(){});

    refreshMoveHighlights();
    showUnitPath();
}

function deleteSelectedUnit(){
    if(!selectedUnit) return;
    fetch('/api/map/units/'+encodeURIComponent(selectedUnit.code),{method:'DELETE'})
        .then(function(r){return r.json();}).then(function(d){
            document.getElementById('unit-detail').style.display='none';
            selectedUnit=null;
            unitPathLayer.clearLayers();
            loadUnits(document.getElementById('unit-source-filter').value);
        });
}

// ---- Movement path rendering (branch round moves) ----

function renderMovePaths(changesOrNodeData) {
    movePathLayer.clearLayers();
    currentMovesData = [];
    if (!changesOrNodeData) return;

    // Accept flat node object or raw changes array
    var changes = Array.isArray(changesOrNodeData) ? changesOrNodeData
        : (changesOrNodeData.moves || changesOrNodeData.unitChanges || []);
    if (!changes.length) return;

    currentMovesData = changes;
    var showAll = document.getElementById('show-moves')
        ? document.getElementById('show-moves').checked : true;

    var actionColors = {
        advance: '#27ae60', retreat: '#e74c3c', engage: '#f39c12',
        flank: '#9b59b6', reinforce: '#2ecc71', deploy: '#3498db',
        maneuver: '#f39c12', hold: '#7f8c8d', destroyed: '#e74c3c'
    };

    var typeStyles = {
        move: {line: true, dash: null},
        hold: {line: false, marker: true},
        create: {line: false, marker: true, dashed: '4,2'},
        delete: {line: false, marker: true, cross: true},
        status_change: {line: false, marker: true}
    };

    for (var i = 0; i < changes.length; i++) {
        var ch = changes[i];
        var ct = ch.changeType || 'move';  // UnitChange or legacy Movement
        var action = ch.action || 'move';
        var color = actionColors[action] || '#f39c12';
        var style = typeStyles[ct] || typeStyles['move'];
        var code = ch.code || '';
        var desc = ch.description || '';

        if (ct === 'move' || (style.line && ch.fromLat !== undefined && ch.toLat !== undefined)) {
            // Draw movement line
            var dash = action === 'retreat' ? '6,4' :
                (action === 'engage' ? '4,3' : style.dash);
            var opts = {color: color, weight: 2.5, opacity: 0.8, interactive: false};
            if (dash) opts.dashArray = dash;

            var line = L.polyline(
                [[ch.fromLat || ch.fromLng, ch.fromLng || ch.fromLat],
                 [ch.toLat || ch.lat, ch.toLng || ch.lng]],
                opts
            ).addTo(movePathLayer);

            // Direction arrow at midpoint
            var flat = (ch.fromLat || 0), flng = (ch.fromLng || 0);
            var tlat = (ch.toLat || ch.lat || 0), tlng = (ch.toLng || ch.lng || 0);
            var midLat = (flat + tlat) / 2;
            var midLng = (flng + tlng) / 2;
            L.circleMarker([midLat, midLng], {
                radius: 3, fillColor: color, color: '#fff',
                weight: 0.5, fillOpacity: 1, interactive: false
            }).bindTooltip(code + ' [' + action + ']', {direction:'top'})
              .addTo(movePathLayer);

            if (!showAll) line.setStyle({opacity: 0.15, weight: 1});

        } else if (ct === 'hold') {
            // Stationary indicator — small ring around unit
            var plat = ch.toLat || ch.lat || 0, plng = ch.toLng || ch.lng || 0;
            L.circleMarker([plat, plng], {
                radius: 8, fillColor: 'transparent', color: '#7f8c8d',
                weight: 1.5, dashArray: '3,3', fillOpacity: 0,
                interactive: false
            }).bindTooltip(code + ' [hold]', {direction:'top'})
              .addTo(movePathLayer);

        } else if (ct === 'create') {
            // New unit — larger dashed circle
            var clat = ch.toLat || ch.lat || 0, clng = ch.toLng || ch.lng || 0;
            L.circleMarker([clat, clng], {
                radius: 10, fillColor: color, color: '#fff',
                weight: 2, fillOpacity: 0.3, dashArray: '4,2',
                interactive: false
            }).bindTooltip(code + ' [NEW]', {direction:'top'})
              .addTo(movePathLayer);

        } else if (ct === 'delete') {
            // Destroyed unit — red X at old position
            var dlat = ch.fromLat || ch.lat || 0, dlng = ch.fromLng || ch.lng || 0;
            L.circleMarker([dlat, dlng], {
                radius: 7, fillColor: '#e74c3c', color: '#fff',
                weight: 1.5, fillOpacity: 0.4,
                interactive: false
            }).bindTooltip(code + ' [DESTROYED]', {direction:'top'})
              .addTo(movePathLayer);

        } else if (ct === 'status_change') {
            // Status change — small diamond
            var slat = ch.toLat || ch.lat || 0, slng = ch.toLng || ch.lng || 0;
            var oldS = ch.oldStatus || '?', newS = ch.newStatus || '?';
            L.circleMarker([slat, slng], {
                radius: 6, fillColor: '#9b59b6', color: '#fff',
                weight: 1, fillOpacity: 0.5,
                interactive: false
            }).bindTooltip(code + ' [' + oldS + '→' + newS + ']', {direction:'top'})
              .addTo(movePathLayer);
        }
    }
}

function refreshMoveHighlights() {
    var showAll = document.getElementById('show-moves')
        ? document.getElementById('show-moves').checked : true;
    movePathLayer.eachLayer(function(layer) {
        if (layer instanceof L.Polyline) {
            layer.setStyle(showAll
                ? {opacity: 0.8, weight: 2.5}
                : {opacity: 0.15, weight: 1});
        }
    });
    // If unit selected and not showAll, highlight its path
    if (selectedUnit && !showAll) {
        movePathLayer.eachLayer(function(layer) {
            if (layer instanceof L.Polyline) {
                var tooltip = layer.getTooltip();
                if (tooltip) {
                    var content = tooltip.getContent ? tooltip.getContent() : '';
                    if (content.indexOf(selectedUnit.code) === 0) {
                        layer.setStyle({opacity: 1, weight: 3, color: selectedUnit.color || '#fff'});
                        layer.bringToFront();
                    }
                }
            }
        });
    }
}

function clearMovePaths() {
    movePathLayer.clearLayers();
    currentMovesData = [];
}

function showUnitPath(){
    unitPathLayer.clearLayers();
    var units=[];
    for(var k in unitMarkers){var u=unitMarkers[k];units.push(u.getLatLng());}
    if(units.length<2) return;
    var a=units[0],b=units[1];
    var allowLand=document.getElementById('pt-land')?document.getElementById('pt-land').checked:true;
    var allowWater=document.getElementById('pt-water')?document.getElementById('pt-water').checked:false;
    var url='/api/map/grid-path?lat1='+a.lat+'&lng1='+a.lng+'&lat2='+b.lat+'&lng2='+b.lng+'&res=0.125&land='+allowLand+'&water='+allowWater;
    fetch(url).then(function(r){return r.json();}).then(function(d){
            if(!d.path||d.path.length<2) return;
            var pts=[];
            for(var i=0;i<d.path.length;i++) pts.push([d.path[i][0],d.path[i][1]]);
            L.polyline(pts,{color:'#FF5722',weight:3,opacity:0.9}).addTo(unitPathLayer);
            L.circleMarker(pts[0],{radius:8,fillColor:'#2196F3',color:'#fff',weight:2,fillOpacity:1}).bindTooltip('Start').addTo(unitPathLayer);
            L.circleMarker(pts[pts.length-1],{radius:8,fillColor:'#F44336',color:'#fff',weight:2,fillOpacity:1}).bindTooltip('End').addTo(unitPathLayer);
            document.getElementById('distance-result').classList.remove('hidden');
            document.getElementById('distance-value').textContent=Math.round(d.straightKm)+' km';
        });
}

document.getElementById('unit-create-btn').onclick=function(){
    var f=document.getElementById('unit-create-form');
    f.style.display=f.style.display==='none'?'block':'none';
    if(f.style.display==='block'){selectedUnit=null;document.getElementById('unit-detail').style.display='none';}
};
document.getElementById('uc-submit').onclick=function(){
    var code=document.getElementById('uc-code').value.trim();
    if(!code){alert('Enter a code');return;}
    var lat=lastClickLat||0,lng=lastClickLng||0;
    if(!lat&&!lng){alert('Click map first');return;}
    fetch('/api/map/units',{method:'POST',body:JSON.stringify({
        code:code,name:document.getElementById('uc-name').value.trim()||code,
        source:document.getElementById('uc-source').value.trim()||'custom',
        type:document.getElementById('uc-type').value,
        lat:lat,lng:lng})})
        .then(function(r){return r.json();}).then(function(d){
            if(d.error){alert(d.error);return;}
            document.getElementById('unit-create-form').style.display='none';
            document.getElementById('uc-code').value='';document.getElementById('uc-name').value='';
            loadUnits(document.getElementById('unit-source-filter').value);
            setTimeout(showUnitPath,500);
        });
};
document.getElementById('unit-source-filter').onchange=function(){loadUnits(this.value);};
map.on('click',function(e){lastClickLat=e.latlng.lat;lastClickLng=e.latlng.lng;});

// Auto-create demo units on first load
function ensureDemoUnits(){
    var needed=[{code:'BJ-GARRISON',name:'Beijing Garrison',source:'base',type:'infantry',lat:39.9,lng:116.4},
                {code:'SH-FLEET',name:'Shanghai Fleet',source:'base',type:'naval',lat:31.2,lng:121.5}];
    fetch('/api/map/units').then(function(r){return r.json();}).then(function(existing){
        var codes=(existing||[]).map(function(u){return u.code;});
        var missing=needed.filter(function(n){return codes.indexOf(n.code)<0;});
        function createNext(){
            if(missing.length===0){loadUnits();loadSources();setTimeout(showUnitPath,1000);return;}
            var m=missing.shift();
            fetch('/api/map/units',{method:'POST',body:JSON.stringify(m)})
                .then(function(r){return r.json();}).then(function(){createNext();});
        }
        createNext();
    }).catch(function(){loadUnits();loadSources();});
}
document.getElementById('pt-refresh').onclick=showUnitPath;
setTimeout(ensureDemoUnits,4000);

// Export for branch-tree.js
window.loadUnits = loadUnits;
window.loadSources = loadSources;
window.showUnitPath = showUnitPath;
window.renderMovePaths = renderMovePaths;
window.clearMovePaths = clearMovePaths;
window.refreshMoveHighlights = refreshMoveHighlights;
window.currentTreeId = null;
window.combatRadiusLayer = combatRadiusLayer;
window.combatRadiusCircle = combatRadiusCircle;
})();