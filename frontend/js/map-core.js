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
window.currentViewedSide = 'god';
window.currentTreeIdForIntel = null;
window.currentNodeIdForIntel = null;

document.getElementById('sidebar-toggle').addEventListener('click',function(){
    var sb=document.getElementById('sidebar');sb.classList.toggle('collapsed');
    this.textContent=sb.classList.contains('collapsed')?'▶':'◀';map.invalidateSize();
});

// Side selector tab clicks
document.addEventListener('click',function(e){
	if(e.target.classList.contains('side-tab')){
		var side=e.target.getAttribute('data-side');
		window.currentViewedSide=side;
		document.querySelectorAll('.side-tab').forEach(function(t){
			t.style.opacity=t.getAttribute('data-side')===side?'1':'0.5';
		});
		if(typeof loadUnits==='function')loadUnits();
	}
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

// Old map click handler removed — unified handler at bottom

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

// ---- Unit icon system ----
var UNIT_ICONS = {
    infantry: '⚔', naval: '⚓', air: '✈', civilian: '🏘', supply: '📦', generic: '●'
};
var UNIT_LABELS = {
    infantry: '步兵', naval: '海军', air: '空军', civilian: '民事', supply: '后勤', generic: '通用'
};
function unitIcon(type) { return UNIT_ICONS[type] || UNIT_ICONS.generic; }
function unitLabel(type) { return UNIT_LABELS[type] || type; }

function makeUnitMarker(u, certainty) {
    var icon = u.icon || unitIcon(u.type);
    var bg = u.color || '#888';
    var cert = certainty || 'confirmed';
    var borderStyle = '1.5px solid rgba(255,255,255,0.9)';
    var bgStyle = 'background:'+bg+';';
    if(cert==='estimated' || cert==='outdated'){
        borderStyle = '2px dashed rgba(255,255,255,0.6)';
        if(cert==='outdated'){bgStyle='background:'+bg+';opacity:0.5;';}
    }else if(cert==='decoy'){
        borderStyle = '2px dashed '+bg;
        bgStyle='background:transparent;';
    }
    var badgeHtml='';
    if(cert==='outdated') badgeHtml='<span style="position:absolute;top:-4px;right:-4px;font-size:8px">⏰</span>';
    else if(cert==='decoy') badgeHtml='<span style="position:absolute;top:-4px;right:-4px;font-size:9px;color:'+bg+'">?</span>';
    return L.divIcon({
        className: 'unit-marker',
        html: '<span style="display:flex;align-items:center;justify-content:center;width:26px;height:26px;'+bgStyle+'border-radius:5px;border:'+borderStyle+';font-size:13px;color:#fff;text-shadow:0 1px 2px rgba(0,0,0,0.5);box-shadow:0 1px 4px rgba(0,0,0,0.5);position:relative">'+icon+badgeHtml+'</span>',
        iconSize: [28, 28],
        iconAnchor: [14, 14]
    });
}

function loadUnits(filterSource){
    var side=window.currentViewedSide;
    var url='/api/map/units';
    if(side && side!=='god' && window.currentTreeIdForIntel && window.currentNodeIdForIntel){
        url+='?viewedBy='+encodeURIComponent(side)+'&tree='+encodeURIComponent(window.currentTreeIdForIntel)+'&node='+encodeURIComponent(window.currentNodeIdForIntel);
    }else if(filterSource){
        url+='?source='+encodeURIComponent(filterSource);
    }
    fetch(url).then(function(r){return r.json();}).then(function(units){
        if(!Array.isArray(units)) return;
        unitLayer.clearLayers(); unitMarkers={};
        var list=document.getElementById('unit-list'); list.innerHTML='';
        var cnt=document.getElementById('unit-count');
        if(cnt) cnt.textContent='('+units.length+')';
        if(units.length===0){list.innerHTML='<span style="color:#888">No units</span>';return;}
        for(var i=0;i<units.length;i++){(function(u){
            var cert=u.certainty||'confirmed';
            var icon=makeUnitMarker(u,cert);
            var mk=L.marker([u.lat,u.lng],{icon:icon,opacity:cert==='decoy'?0.7:1})
                .bindTooltip(u.name+(cert!=='confirmed'?' ['+cert+']':''),{direction:'top'}).addTo(unitLayer);
            mk.on('click',function(e){L.DomEvent.stopPropagation(e);selectUnit(u);});
            mk._unitData = u; // store unit data for combat detection
            unitMarkers[u.code]=mk;
            // Uncertainty circle for estimated/outdated
            if(u.uncertaintyRadiusKm && u.uncertaintyRadiusKm>0){
                L.circle([u.lat,u.lng],{radius:u.uncertaintyRadiusKm*1000,color:u.color||'#f39c12',
                    weight:1,dashArray:'6,6',fillOpacity:0.05,interactive:false}).addTo(unitLayer);
            }
            // List card
            var item=document.createElement('div');
            item.className='unit-card';
            if(u.isPhantom) item.className+=' phantom-card';
            item.style.borderLeft='3px solid '+(u.color||'#888');
            var certBadge=cert!=='confirmed'?' <span style="font-size:8px;color:'+(u.color||'#888')+'">['+cert+']</span>':'';
            item.innerHTML='<span class="uc-icon">'+unitIcon(u.type)+'</span>' +
                '<span class="uc-name">'+u.name+certBadge+'</span>' +
                '<span class="uc-badges">' +
                '<span class="uc-badge src">'+u.source+'</span>' +
                '<span class="uc-badge status-'+u.status+'">'+(u.isPhantom?'decoy':u.status)+'</span></span>';
            item.onclick=function(e){selectUnit(u);map.setView([u.lat,u.lng],6);};
            list.appendChild(item);
        })(units[i]);}
        // Combat zones: opposing units at same location get battle box
        if (typeof window.combatZoneLayer === 'undefined') {
            window.combatZoneLayer = L.layerGroup().addTo(map);
        }
        if (window.combatZoneLayer) window.combatZoneLayer.clearLayers();
        var combatPairs = [];
        for (var i=0;i<units.length;i++) {
            for (var j=i+1;j<units.length;j++) {
                var a=units[i], b=units[j];
                if (a.source===b.source) continue;
                var d=haversine(a.lat,a.lng,b.lat,b.lng);
                if (d<0.005) { // within ~500m = same position = combat
                    combatPairs.push([a,b]);
                } else if (d<10) { // within 10km = near engagement
                    var mid2Lat=(a.lat+b.lat)/2, mid2Lng=(a.lng+b.lng)/2;
                    L.circle([mid2Lat,mid2Lng], {radius:2000, color:'#f39c12', weight:1,
                        dashArray:'3,6', fillOpacity:0, interactive:false}).addTo(window.combatZoneLayer);
                }
            }
        }
        // Render combat boxes for overlapping pairs
        for (var p=0;p<combatPairs.length;p++) {
            var ua=combatPairs[p][0], ub=combatPairs[p][1];
            var midLat=(ua.lat+ub.lat)/2, midLng=(ua.lng+ub.lng)/2;
            var spread=0.012; // ~1.3km offset each side
            // Offset the two unit markers apart
            var mkA=unitMarkers[ua.code], mkB=unitMarkers[ub.code];
            if (mkA) mkA.setLatLng([ua.lat-spread, ua.lng]);
            if (mkB) mkB.setLatLng([ub.lat+spread, ub.lng]);
            // Draw combat boundary box
            var bounds=[[ua.lat-spread-0.004, ua.lng-0.005],[ua.lat+spread+0.004, ua.lng+0.005]];
            L.rectangle(bounds, {color:'#e74c3c', weight:2, dashArray:'6,3',
                fillColor:'#e74c3c', fillOpacity:0.08, interactive:false}).addTo(window.combatZoneLayer);
            // Red line between the two markers
            L.polyline([[ua.lat-spread, ua.lng],[ub.lat+spread, ub.lng]],
                {color:'#e74c3c', weight:2, dashArray:'4,2'}).addTo(window.combatZoneLayer);
            // Combat emoji in the middle — force top z-index
            var emoji = L.marker([midLat,midLng], {icon: L.divIcon({
                className:'combat-emoji', html:'<div style="font-size:28px;text-shadow:0 0 10px #e74c3c,0 0 20px #c0392b;filter:drop-shadow(0 2px 4px rgba(0,0,0,0.5))">⚔️</div>',
                iconSize:[32,32], iconAnchor:[16,16]
            }), interactive:false, zIndexOffset: 10000, pane: 'markerPane'}).addTo(window.combatZoneLayer);
        }
    }).catch(function(e){console.warn(e);});
}

// Haversine distance helper
function haversine(lat1,lng1,lat2,lng2){
    var R=6371,dLat=(lat2-lat1)*Math.PI/180,dLng=(lng2-lng1)*Math.PI/180;
    var a=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(lat1*Math.PI/180)*Math.cos(lat2*Math.PI/180)*Math.sin(dLng/2)*Math.sin(dLng/2);
    return R*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
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

    // Fill editable detail panel
    var detailEl = document.getElementById('unit-detail');
    detailEl.style.display='block';
    var iconEl = document.getElementById('ud-icon');
    iconEl.textContent = u.icon || unitIcon(u.type);
    iconEl.style.background = (u.color||'#888')+'33';
    // Icon picker
    var pickerEl = document.getElementById('ud-icon-picker');
    pickerEl.innerHTML = '';
    pickerEl.style.display = 'none';
    iconEl.onclick = function(){
        if(pickerEl.style.display==='flex'){pickerEl.style.display='none';return;}
        pickerEl.style.display='flex';
        var allIcons = ['⚔','⚓','✈','🏘','📦','●','⬟','▲','★','♦','⚑','♜','🛡','🎯','💣','🔰','⚡','🔥','💀','👁'];
        allIcons.forEach(function(ic){
            var opt = document.createElement('span');
            opt.textContent = ic;
            opt.style.cssText = 'font-size:16px;width:24px;height:24px;display:flex;align-items:center;justify-content:center;cursor:pointer;border-radius:3px;background:rgba(255,255,255,0.08)';
            opt.onmouseenter=function(){opt.style.background='rgba(255,255,255,0.2)';};
            opt.onmouseleave=function(){opt.style.background='rgba(255,255,255,0.08)';};
            opt.onclick=function(e){e.stopPropagation();
                u.icon = ic; iconEl.textContent = ic;
                pickerEl.style.display = 'none';
            };
            pickerEl.appendChild(opt);
        });
    };
    document.getElementById('ud-name-inp').value = u.name||'';
    document.getElementById('ud-code').textContent = '代号: '+(u.code||'?');
    document.getElementById('ud-type-sel').value = u.type||'generic';
    document.getElementById('ud-status-sel').value = u.status||'active';
    document.getElementById('ud-color-inp').value = u.color||'#888';
    document.getElementById('ud-color-hex').textContent = u.color||'#888';
    document.getElementById('ud-desc-inp').value = u.description||'';
    document.getElementById('ud-coords').textContent = 'Lat: '+(u.lat||0).toFixed(4)+'  Lng: '+(u.lng||0).toFixed(4);

    // Visibility tags
    var visEl = document.getElementById('ud-visibility');
    visEl.innerHTML = '';
    var visSet = u.visibleTo || [u.source||'custom'];
    visSet.forEach(function(src){
        var tag = document.createElement('span');
        tag.className = 'vis-tag';
        tag.textContent = src;
        tag.title = '点击移除此阵营的视野';
        tag.onclick = function(){
            var newVis = (u.visibleTo||[u.source]).filter(function(s){return s!==src;});
            if(newVis.length===0) newVis=[u.source]; // must keep at least own source
            u.visibleTo = newVis;
            selectUnit(u); // re-render
        };
        visEl.appendChild(tag);
    });
    // Add button
    var addBtn = document.createElement('span');
    addBtn.className = 'vis-tag vis-add';
    addBtn.textContent = '+';
    addBtn.title = '添加阵营视野';
    addBtn.onclick = function(){
        var newSrc = prompt('输入阵营名称 (如 japanese, nationalist):');
        if(!newSrc) return;
        var vis = u.visibleTo || [u.source||'custom'];
        if(vis.indexOf(newSrc)<0){ vis.push(newSrc); u.visibleTo = vis; }
        selectUnit(u);
    };
    visEl.appendChild(addBtn);

    // Populate source dropdown
    var srcSel = document.getElementById('ud-source-sel');
    fetch('/api/map/units/sources').then(function(r){return r.json();}).then(function(d){
        srcSel.innerHTML = '';
        (d.sources||[]).forEach(function(s){
            srcSel.innerHTML += '<option value="'+s+'">'+s+'</option>';
        });
        srcSel.value = u.source||'custom';
    }).catch(function(){});

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

// ---- Save unit edits ----
function saveUnitEdits() {
    if (!selectedUnit) return;
    var code = selectedUnit.code;
    var body = JSON.stringify({
        name: document.getElementById('ud-name-inp').value.trim() || selectedUnit.name,
        type: document.getElementById('ud-type-sel').value,
        status: document.getElementById('ud-status-sel').value,
        source: document.getElementById('ud-source-sel').value,
        icon: selectedUnit.icon || null,
        color: document.getElementById('ud-color-inp').value,
        description: document.getElementById('ud-desc-inp').value,
        visibleTo: selectedUnit.visibleTo || [selectedUnit.source]
    });
    var btn = document.getElementById('unit-save-btn');
    btn.textContent = '...'; btn.disabled = true;
    fetch('/api/map/units/'+encodeURIComponent(code), {method:'PATCH', body: body})
        .then(function(r){return r.json();}).then(function(d){
            if(d.error){alert(d.error);btn.textContent='保存修改';btn.disabled=false;return;}
            // Reload to reflect changes
            loadUnits(document.getElementById('unit-source-filter').value);
            // Re-select the unit after reload
            setTimeout(function(){
                selectedUnit = null;
                document.getElementById('unit-detail').style.display = 'none';
                btn.textContent = '保存修改'; btn.disabled = false;
            }, 200);
        }).catch(function(e){
            alert('Save error: '+e);
            btn.textContent = '保存修改'; btn.disabled = false;
        });
}
window.saveUnitEdits = saveUnitEdits;

document.getElementById('unit-save-btn').addEventListener('click', saveUnitEdits);
document.getElementById('ud-color-inp').addEventListener('input', function(){
    document.getElementById('ud-color-hex').textContent = this.value;
});

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

// Demo units removed — units load from workspace or are created manually

// Export for branch-tree.js and global access
window.loadUnits = loadUnits;
window.loadSources = loadSources;
window.showUnitPath = showUnitPath;
window.renderMovePaths = renderMovePaths;
window.clearMovePaths = clearMovePaths;
window.refreshMoveHighlights = refreshMoveHighlights;
window.deleteSelectedUnit = deleteSelectedUnit;
window.currentTreeId = null;
window.combatRadiusLayer = combatRadiusLayer;
window.combatRadiusCircle = combatRadiusCircle;

// ---- Display Mode ----
var currentMode = 'units'; // 'units' | 'terrain'
var terrainProbeCircle = null;
var terrainImageOverlay = null;

function setMode(mode) {
    currentMode = mode;
    document.getElementById('mode-units').classList.toggle('active', mode === 'units');
    document.getElementById('mode-terrain').classList.toggle('active', mode === 'terrain');
    document.getElementById('mode-radius-row').style.display = mode === 'terrain' ? 'flex' : 'none';

    // Toggle unit marker interactivity
    for (var k in unitMarkers) {
        var mk = unitMarkers[k];
        if (mode === 'terrain') {
            mk.setOpacity(0.4);
            mk.unbindTooltip();
            mk.off('click');
        } else {
            mk.setOpacity(1);
        }
    }

    // Clear terrain probe when switching to unit mode
    if (mode === 'units') {
        if (terrainProbeCircle) { map.removeLayer(terrainProbeCircle); terrainProbeCircle = null; }
        if (terrainImageOverlay) { map.removeLayer(terrainImageOverlay); terrainImageOverlay = null; }
        terrainCellLayer.clearLayers();
        riverLayer.clearLayers();
        roadLayer.clearLayers();
        cityMarkerLayer.clearLayers();
    } else {
        // Deselect unit when switching to terrain mode
        selectedUnit = null;
        document.getElementById('unit-detail').style.display = 'none';
        if (combatRadiusCircle) { combatRadiusLayer.removeLayer(combatRadiusCircle); combatRadiusCircle = null; }
    }
}

document.getElementById('mode-units').onclick = function() { setMode('units'); };
document.getElementById('mode-terrain').onclick = function() { setMode('terrain'); };
document.getElementById('mode-terrain-edit').onclick = function() {
    if (terrainEditMode) exitTerrainEditMode(); else enterTerrainEditMode();
};

document.getElementById('unit-delete-btn').addEventListener('click', function() {
    deleteSelectedUnit();
});

// Mode-aware terrain probe: server-rendered PNG image overlay
function probeTerrain(lat, lng) {
    var r = parseInt(document.getElementById('mode-radius').value) || 100;
    // Draw border circle
    if (terrainProbeCircle) map.removeLayer(terrainProbeCircle);
    terrainProbeCircle = drawCircle(lat, lng, r * 1000);

    // Load text info (profile, cities, roads, rivers) via existing API
    loadData(lat, lng, r);

    // Replace GeoJSON terrain cells with server-rendered image
    // Clear old GeoJSON layer, add image overlay
    terrainCellLayer.clearLayers();
    if (terrainImageOverlay) map.removeLayer(terrainImageOverlay);
    var deg = r / 111.32;
    var bounds = [[lat - deg, lng - deg], [lat + deg, lng + deg]];
    terrainImageOverlay = L.imageOverlay(
        '/api/map/terrain-image?lat=' + lat.toFixed(4) + '&lng=' + lng.toFixed(4) + '&r=' + r + '&size=512',
        bounds, {opacity: 0.65, className: 'terrain-probe-img'}
    ).addTo(map);
}

// ---- Unified map click handler ----
map.on('click', function(e) {
    lastClickLat = e.latlng.lat;
    lastClickLng = e.latlng.lng;

    if (e.originalEvent.shiftKey) return; // distance tool handles shift-clicks separately

    if (currentMode === 'terrain') {
        // Terrain mode: probe terrain at click point
        selectedUnit = null;
        document.getElementById('unit-detail').style.display = 'none';
        if (combatRadiusCircle) { combatRadiusLayer.removeLayer(combatRadiusCircle); combatRadiusCircle = null; }
        probeTerrain(e.latlng.lat, e.latlng.lng);
    } else {
        // Unit mode: deselect unit, clear combat radius
        if (combatRadiusCircle) { combatRadiusLayer.removeLayer(combatRadiusCircle); combatRadiusCircle = null; }
        terrainCellLayer.clearLayers();
        clearLayers();
        selectedUnit = null;
        document.getElementById('unit-detail').style.display = 'none';
        // Preserve move paths when viewing a branch node
        if (!window.currentNodeIdForIntel && typeof clearMovePaths === 'function') clearMovePaths();
    }
});

// Terrain mode radius slider
document.getElementById('mode-radius').addEventListener('input', function() {
    var r = parseInt(this.value) || 100;
    document.getElementById('mode-radius-val').textContent = r + 'km';
    // Re-probe at last click position if we have a probe circle
    if (currentMode === 'terrain' && terrainProbeCircle) {
        probeTerrain(lastClickLat || 0, lastClickLng || 0);
    }
});

// Load units and sources on start (no demo units)
// ====================================================================
//  Terrain Edit Mode — grid selection + ASCII art rendering
// ====================================================================

var terrainEditMode = false;
var editRectangle = null;
var editStartPoint = null;
var editDragging = false;
var selectedGridCell = null; // {row, col, lat, lng}

function enterTerrainEditMode() {
    terrainEditMode = true;
    document.getElementById('mode-terrain-edit').classList.add('active');
    document.getElementById('terrain-edit-panel').style.display = 'block';
    document.getElementById('mode-units').classList.remove('active');
    document.getElementById('mode-terrain').classList.remove('active');
    map.getContainer().style.cursor = 'crosshair';
    map.dragging.disable();
}

function exitTerrainEditMode() {
    terrainEditMode = false;
    editStartPoint = null;
    editDragging = false;
    document.getElementById('mode-terrain-edit').classList.remove('active');
    document.getElementById('mode-units').classList.add('active');
    if (editRectangle) { map.removeLayer(editRectangle); editRectangle = null; }
    map.getContainer().style.cursor = '';
    map.dragging.enable();
    document.getElementById('cell-editor').style.display = 'none';
    selectedGridCell = null;
}

// Rectangle drag on map
map.on('mousedown', function(e) {
    if (!terrainEditMode) return;
    if (e.originalEvent.shiftKey) {
        // Shift+drag = area select
        editStartPoint = e.latlng;
        editDragging = false;
        L.DomEvent.disableClickPropagation(e.originalEvent.target);
    }
});

map.on('mousemove', function(e) {
    if (!terrainEditMode || !editStartPoint) return;
    editDragging = true;
    var bounds = L.latLngBounds(editStartPoint, e.latlng);
    if (editRectangle) map.removeLayer(editRectangle);
    editRectangle = L.rectangle(bounds, {
        color: '#ff9800', weight: 2, fillColor: '#ff9800', fillOpacity: 0.1,
        dashArray: '5 5'
    }).addTo(map);
});

map.on('mouseup', function(e) {
    if (!terrainEditMode || !editStartPoint) return;
    if (editDragging) {
        var bounds = L.latLngBounds(editStartPoint, e.latlng);
        loadGridForBounds(bounds);
    }
    if (editRectangle) { map.removeLayer(editRectangle); editRectangle = null; }
    editStartPoint = null;
    editDragging = false;
});

// Load ASCII grid view into panel
function loadGridForBounds(bounds) {
    var qBounds = {
        south: bounds.getSouth().toFixed(6),
        north: bounds.getNorth().toFixed(6),
        west: bounds.getWest().toFixed(6),
        east: bounds.getEast().toFixed(6)
    };
    API.getGridView(qBounds).then(function(text) {
        var pre = document.getElementById('grid-ascii');
        pre.textContent = text;
        pre.classList.add('grid-interactive');

        // Parse snapped bounds from header
        var m = text.match(/Snapped grid:\s*([\d.-]+)-([\d.-]+)([NS]),\s*([\d.-]+)-([\d.-]+)([EW])\s*\((\d+)×(\d+)\s*cells/i);
        if (m) {
            var info = document.getElementById('grid-snap-info');
            info.innerHTML = '吸附: ' + parseFloat(m[1]).toFixed(4) + '-' + parseFloat(m[2]).toFixed(4) + m[3]
                + ', ' + parseFloat(m[4]).toFixed(4) + '-' + parseFloat(m[5]).toFixed(4) + m[6]
                + ' (' + m[7] + '×' + m[8] + ' cells)';
            // Store snapped bounds for grid cell click resolution
            window._snappedBounds = {
                south: parseFloat(m[1]), north: parseFloat(m[2]),
                west: parseFloat(m[4]), east: parseFloat(m[5]),
                rows: parseInt(m[7]), cols: parseInt(m[8])
            };
        }

        document.getElementById('terrain-edit-panel').style.display = 'block';
        document.getElementById('cell-editor').style.display = 'none';
        selectedGridCell = null;
    });
}

// Click on ASCII grid to select a cell
document.getElementById('grid-ascii').addEventListener('click', function(e) {
    var sb = window._snappedBounds;
    if (!sb) return;

    var pre = this;
    var rect = pre.getBoundingClientRect();
    var x = e.clientX - rect.left + pre.scrollLeft;
    var y = e.clientY - rect.top + pre.scrollTop;

    // Find terrain layer section
    var text = pre.textContent;
    var terrainIdx = text.indexOf('=== 地貌 (Terrain) ===');
    if (terrainIdx < 0) return;

    // Find the grid part — lines after the terrain section legend
    var afterTerrain = text.substring(terrainIdx);
    var lines = afterTerrain.split('\n');
    // Skip lines until we hit the first grid line (single-char-per-cell pattern)
    var gridStart = 0;
    for (var i = 0; i < lines.length; i++) {
        if (/^[~≈.n▲^▢:♣♠τ*# █▓▒░\t]+$/.test(lines[i]) && lines[i].trim().length > 0) {
            gridStart = i;
            break;
        }
    }

    // Estimate which cell was clicked based on character position
    var lineHeight = 11; // approximate from CSS font-size:10px line-height:1.1
    // Count preceding text lines including the legend lines
    var preLines = afterTerrain.substring(0, afterTerrain.indexOf(lines[gridStart])).split('\n').length - 1;
    var rowIdx = Math.floor(y / lineHeight) - preLines;
    if (rowIdx < 0 || rowIdx >= sb.rows) return;

    var line = lines[gridStart + rowIdx] || '';
    var colIdx = Math.floor(x / 6.5); // approximate monospace char width at 10px
    if (colIdx < 0 || colIdx >= line.length || colIdx >= sb.cols) return;

    // Convert grid position to lat/lng
    var cellSize = (sb.north - sb.south) / sb.rows;
    var lat = sb.north - (rowIdx + 0.5) * cellSize; // north-up: row 0 is top
    var lng = sb.west + (colIdx + 0.5) * cellSize;

    selectedGridCell = { rowIdx: rowIdx, colIdx: colIdx, lat: lat, lng: lng };

    // Show cell editor
    document.getElementById('cell-coord').textContent =
        '行' + rowIdx + '列' + colIdx + ' (' + lat.toFixed(4) + ', ' + lng.toFixed(4) + ')';
    document.getElementById('cell-editor').style.display = 'block';
    document.getElementById('terrain-type-select').value = '';
    document.getElementById('elevation-input').value = '';

    // Load existing override if any
    API.getTerrainOverrides({south: lat-0.001, north: lat+0.001, west: lng-0.001, east: lng+0.001})
        .then(function(d) {
            if (d && d.overrides && d.overrides.length > 0) {
                var ov = d.overrides[0];
                if (ov.terrain) document.getElementById('terrain-type-select').value = ov.terrain;
                if (ov.elevation != null) document.getElementById('elevation-input').value = ov.elevation;
            }
        });
});

// Apply override button
document.getElementById('apply-override-btn').addEventListener('click', function() {
    if (!selectedGridCell) return;
    var sel = document.getElementById('terrain-type-select');
    var terrain = sel.value || null;
    var elevStr = document.getElementById('elevation-input').value;
    var elevation = elevStr ? parseFloat(elevStr) : null;
    if (!terrain && elevation == null) return;

    API.setTerrainOverride(selectedGridCell.lat, selectedGridCell.lng, terrain, elevation)
        .then(function(r) {
            if (r.ok) {
                // Refresh grid
                if (window._snappedBounds) {
                    loadGridForBounds({
                        getSouth: function() { return window._snappedBounds.south; },
                        getNorth: function() { return window._snappedBounds.north; },
                        getWest: function() { return window._snappedBounds.west; },
                        getEast: function() { return window._snappedBounds.east; }
                    });
                }
            }
        });
});

// Clear override button
document.getElementById('clear-override-btn').addEventListener('click', function() {
    if (!selectedGridCell) return;
    API.deleteTerrainOverride(selectedGridCell.lat, selectedGridCell.lng).then(function(r) {
        if (r.ok) {
            document.getElementById('terrain-type-select').value = '';
            document.getElementById('elevation-input').value = '';
            // Refresh grid
            if (window._snappedBounds) {
                loadGridForBounds({
                    getSouth: function() { return window._snappedBounds.south; },
                    getNorth: function() { return window._snappedBounds.north; },
                    getWest: function() { return window._snappedBounds.west; },
                    getEast: function() { return window._snappedBounds.east; }
                });
            }
        }
    });
});

// Close button
document.getElementById('terrain-edit-close').addEventListener('click', function() {
    exitTerrainEditMode();
});

setTimeout(function() {
    loadUnits();
    loadSources();
}, 1000);
})();
