/**
 * Branch Tree Panel — visual branching timeline for wargame scenarios.
 * Depends on: unitLayer, unitMarkers, loadUnits, showUnitPath (from map-core.js)
 */
(function() {
'use strict';

var currentTreeId = null;
var currentNodeId = null;
var flatNodes = {}; // nodeId -> {id, name, round, parentId, strategy, children[], outcome}

// ---- Panel DOM ----

var panel = document.createElement('div');
panel.id = 'branch-panel';
panel.innerHTML =
    '<div id="branch-header">' +
        '<span id="branch-tree-name">&#9776; Branches</span>' +
        '<select id="branch-tree-select" style="display:none;width:100%;margin-top:4px;background:#333;color:#ccc;border:1px solid #555;border-radius:3px;font-size:10px;padding:2px 4px"><option value="">-- 选择分支 --</option></select>' +
        '<div id="branch-desc" style="display:none;font-size:9px;color:#888;margin-top:3px;line-height:1.3;max-height:60px;overflow-y:auto"></div>' +
        '<button id="branch-save-btn" class="primary" title="Save current state as new round">+R</button>' +
        '<button id="branch-new-btn" title="New branch from current node">&#10558;</button>' +
        '<button id="branch-newtree-btn" title="New tree from current units">&#9733;</button>' +
        '<button id="branch-toggle-btn">&minus;</button>' +
    '</div>' +
    '<div id="branch-body"><div style="color:#666;padding:8px;font-size:10px">加载中...</div></div>' +
    '<div id="branch-actions">' +
        '<button class="save-btn" id="branch-save-round">Save Round</button>' +
        '<button class="new-branch-btn" id="branch-fork">New Branch</button>' +
        '<button id="branch-deselect">Deselect</button>' +
    '</div>';
document.body.appendChild(panel);

// Companion: branch narrative text window (right of branch panel)
var changesWin = document.createElement('div');
changesWin.id = 'branch-changes';
changesWin.innerHTML = '<div id="changes-header" style="font-size:12px;font-weight:bold;color:#FF5722;padding:6px 8px;border-bottom:1px solid #444">战场态势</div>' +
    '<div id="changes-body" style="font-size:11px;color:#ccc;padding:8px 12px;max-height:500px;overflow-y:auto;line-height:1.7;font-family:sans-serif"><span style="color:#555">点击分支节点查看战况</span></div>';
changesWin.style.cssText = 'position:absolute;top:10px;left:380px;z-index:999;max-width:300px;min-width:220px;' +
    'background:rgba(20,22,28,0.94);color:#ccc;border:1px solid #444;border-radius:6px;' +
    'box-shadow:0 2px 12px rgba(0,0,0,0.6);display:none;';
document.body.appendChild(changesWin);

/** Show a narrative description of the current branch round. */
function updateChangesWindow(nodeId) {
    if (!currentTreeId || !nodeId) { changesWin.style.display = 'none'; return; }
    var node = flatNodes[nodeId];
    if (!node) { changesWin.style.display = 'none'; return; }

    var badge = node.strategy === 'historical' ? '历史线' :
        node.strategy === 'alt1' ? '备选方案A1' :
        node.strategy === 'alt2' ? '备选方案A2' :
        node.strategy === 'initial' ? '初始部署' : node.strategy;
    var header = document.getElementById('changes-header');
    var body = document.getElementById('changes-body');
    if (!header || !body) return;

    header.textContent = '📍 Round ' + node.round + ' ' + badge;
    header.style.color = node.strategy === 'alt1' ? '#f39c12' :
        node.strategy === 'alt2' ? '#e74c3c' : '#FF5722';

    // Build narrative from the node's outcome + unit count info
    var html = '<div style="margin-bottom:8px;font-weight:bold;font-size:13px">' + node.name + '</div>';
    if (node.outcome) {
        html += '<div style="color:#ddd;margin-bottom:12px;padding:8px;background:rgba(255,255,255,0.04);border-left:3px solid #FF5722;border-radius:3px">' + node.outcome + '</div>';
    }

    // Add a summary of key changes (compact)
    fetch('/api/branches/' + currentTreeId + '/changes/' + nodeId)
        .then(function(r){return r.json();})
        .then(function(changes){
            if (!Array.isArray(changes)) return;
            var moves=[], news=[], lost=[];
            for (var i=0;i<changes.length;i++){
                var c=changes[i];
                if (c.changeType==='move') moves.push(c);
                else if (c.changeType==='create') news.push(c);
                else if (c.changeType==='delete') lost.push(c);
            }
            var extra = '';
            if (moves.length) extra += '<div style="margin:4px 0"><b style="color:#f39c12">行动单位 ' + moves.length + ':</b> ' + moves.slice(0,6).map(function(m){return m.code;}).join(', ') + (moves.length>6?' ...':'') + '</div>';
            if (news.length) extra += '<div style="margin:4px 0"><b style="color:#2ecc71">新增单位 ' + news.length + ':</b> ' + news.map(function(m){return m.code;}).join(', ') + '</div>';
            if (lost.length) extra += '<div style="margin:4px 0"><b style="color:#e74c3c">损失单位 ' + lost.length + ':</b> ' + lost.map(function(m){return m.code;}).join(', ') + '</div>';
            if (!moves.length && !news.length && !lost.length) extra = '<div style="color:#888">各单位占据阵地，暂无重大变化</div>';
            body.innerHTML = html + extra;
            changesWin.style.display = 'block';
        })
        .catch(function(){ body.innerHTML = html; changesWin.style.display = 'block'; });
}

// ---- Event Handlers ----

document.getElementById('branch-toggle-btn').onclick = function() {
    var body = document.getElementById('branch-body');
    var actions = document.getElementById('branch-actions');
    var btn = this;
    if (body.style.display === 'none') {
        body.style.display = 'block';
        actions.style.display = 'flex';
        btn.innerHTML = '&minus;';
        panel.classList.remove('collapsed');
    } else {
        body.style.display = 'none';
        actions.style.display = 'none';
        btn.innerHTML = '+';
        panel.classList.add('collapsed');
    }
};

// Create new tree from current units
document.getElementById('branch-newtree-btn').onclick = function() {
    var name = prompt('Branch tree name:', '淞沪会战推演');
    if (!name) return;
    fetch('/api/branches', {
        method: 'POST',
        body: JSON.stringify({name: name})
    }).then(function(r){return r.json();}).then(function(d){
        if (d.error) { alert(d.error); return; }
        initTreeSelector(); // refresh dropdown, then load new tree
    }).catch(function(e){ alert('Error: '+e); });
};

// Save current round
document.getElementById('branch-save-btn').onclick = function() { saveRound(); };
document.getElementById('branch-save-round').onclick = function() { saveRound(); };

function saveRound() {
    if (!currentTreeId) {
        alert('Create a tree first (click ★)');
        return;
    }
    var parentId = currentNodeId || 'root';
    var name = prompt('Round name:', 'Round');
    if (!name) return;
    var strategy = prompt('Strategy (historical/alt1/alt2):', flatNodes[parentId] ? (flatNodes[parentId].strategy || 'historical') : 'historical');
    if (!strategy) strategy = 'historical';
    var outcome = prompt('Outcome:', '');
    if (outcome === null) return;

    var roundNum = flatNodes[parentId] ? (flatNodes[parentId].round + 1) : 1;

    fetch('/api/branches/' + currentTreeId + '/nodes', {
        method: 'POST',
        body: JSON.stringify({
            parentId: parentId,
            name: name,
            round: roundNum,
            strategy: strategy,
            outcome: outcome
        })
    }).then(function(r){return r.json();}).then(function(d){
        if (d.error) { alert(d.error); return; }
        loadTree();
    }).catch(function(e){ alert('Error: '+e); });
}

// Fork new branch from current node
document.getElementById('branch-fork').onclick = function() {
    if (!currentTreeId || !currentNodeId) {
        alert('Select a node first to branch from it');
        return;
    }
    var name = prompt('Branch name:', 'Alt Strategy');
    if (!name) return;
    var strategy = prompt('Strategy tag:', 'alt1');
    if (!strategy) strategy = 'alt1';
    var outcome = prompt('Outcome:', '');
    if (outcome === null) return;

    var roundNum = flatNodes[currentNodeId] ? (flatNodes[currentNodeId].round + 1) : 1;

    fetch('/api/branches/' + currentTreeId + '/nodes', {
        method: 'POST',
        body: JSON.stringify({
            parentId: currentNodeId,
            name: name,
            round: roundNum,
            strategy: strategy,
            outcome: outcome
        })
    }).then(function(r){return r.json();}).then(function(d){
        if (d.error) { alert(d.error); return; }
        loadTree();
    }).catch(function(e){ alert('Error: '+e); });
};

// Deselect node
document.getElementById('branch-deselect').onclick = function() {
    currentNodeId = null;
    renderTree();
};

// ---- Core functions ----

var currentTreeName = '';

function loadTree() {
    if (!currentTreeId) return;
    // Get tree metadata
    fetch('/api/branches').then(function(r){return r.json();}).then(function(trees){
        for(var i=0;i<trees.length;i++){if(trees[i].id===currentTreeId)currentTreeName=trees[i].name;}
    });
    fetch('/api/branches/' + currentTreeId + '/flat')
        .then(function(r){return r.json();})
        .then(function(nodes){
            flatNodes = {};
            for (var i = 0; i < nodes.length; i++) {
                flatNodes[nodes[i].id] = nodes[i];
            }
            renderTree();
        })
        .catch(function(e){ console.warn('Branch load error:', e); });
}

function renderTree() {
    var body = document.getElementById('branch-body');
    if (!currentTreeId || Object.keys(flatNodes).length === 0) {
        body.innerHTML = '<div style="color:#666;padding:8px;font-size:10px">No tree. Click &#9733; to create.</div>';
        return;
    }

    // Find root (node with no parentId)
    var root = null;
    for (var id in flatNodes) {
        if (!flatNodes[id].parentId) { root = flatNodes[id]; break; }
    }
    if (!root) {
        body.innerHTML = '<div style="color:#f55;padding:8px">Tree error: no root</div>';
        return;
    }

    var html = buildTreeHtml(root, 0);
    body.innerHTML = '<ul>' + html + '</ul>';

    // Attach click handlers
    var allNodes = body.querySelectorAll('.tree-node');
    for (var i = 0; i < allNodes.length; i++) {
        (function(el){
            el.onclick = function(e){
                e.stopPropagation();
                var nid = el.getAttribute('data-nodeid');
                applyBranchNode(nid);
            };
        })(allNodes[i]);
    }
}

function buildTreeHtml(node, depth) {
    var isActive = currentNodeId === node.id;
    var cls = 'tree-node' + (isActive ? ' active' : '');
    var badgeHtml = '';
    if (node.strategy === 'alt') badgeHtml = '<span class="badge badge-alt">A</span>';
    else if (node.strategy === 'alt1') badgeHtml = '<span class="badge badge-alt">A1</span>';
    else if (node.strategy === 'alt2') badgeHtml = '<span class="badge badge-planb">A2</span>';
    else if (node.strategy === 'historical') badgeHtml = '<span class="badge badge-historical">史</span>';
    else if (node.strategy !== 'initial') badgeHtml = '<span class="badge" style="background:#7f8c8d">' + node.strategy + '</span>';

    var prefix = node.round === 0 ? '📍' : ('R' + node.round);
    var moveCount = node.outcome ? '' : '';
    var html = '<li><span class="' + cls + '" data-nodeid="' + node.id + '">' +
        '<span class="round-num">' + prefix + '</span>' +
        node.name + badgeHtml +
        (node.outcome ? '<span class="move-info">→ ' + truncate(node.outcome, 40) + '</span>' : '') +
        '</span>';

    if (node.children && node.children.length > 0) {
        html += '<ul>';
        for (var i = 0; i < node.children.length; i++) {
            var child = flatNodes[node.children[i]];
            if (child) html += buildTreeHtml(child, depth + 1);
        }
        html += '</ul>';
    }

    html += '</li>';
    return html;
}

function truncate(str, len) {
    if (!str) return '';
    return str.length > len ? str.substring(0, len) + '...' : str;
}

// ---- Apply a branch node to the map ----

function applyBranchNode(nodeId) {
    if (!currentTreeId) return;
    currentNodeId = nodeId;

    // Clear combat radius
    if (window.combatRadiusLayer && window.combatRadiusCircle) {
        window.combatRadiusLayer.removeLayer(window.combatRadiusCircle);
        window.combatRadiusCircle = null;
    }

    fetch('/api/branches/' + currentTreeId + '/apply/' + nodeId, { method: 'POST' })
        .then(function(r){return r.json();})
        .then(function(d){
            if (d.error) { alert(d.error); return; }
            // Reload units from server to update map
            if (typeof loadUnits === 'function') loadUnits();
            if (typeof loadSources === 'function') setTimeout(loadSources, 300);
            renderTree();

            // Fetch UnitChanges computed from parent→child snapshot comparison
            fetch('/api/branches/' + currentTreeId + '/changes/' + nodeId)
                .then(function(r2){return r2.json();})
                .then(function(changes){
                    if (Array.isArray(changes) && changes.length > 0) {
                        if (typeof renderMovePaths === 'function') renderMovePaths(changes);
                    } else {
                        if (typeof clearMovePaths === 'function') clearMovePaths();
                    }
                });

            // Update context display + changes window
            updateBranchContext(nodeId);
            updateChangesWindow(nodeId);

            // Flash notification
            var node = flatNodes[nodeId];
            var msg = node ? ('Loaded: ' + node.name) : 'Loaded';
            flashNotify(msg);
        })
        .catch(function(e){ alert('Apply error: ' + e); });
}

/** Show current branch/round context in sidebar AND branch panel. */
function updateBranchContext(nodeId) {
    var node = flatNodes[nodeId];
    var el = document.getElementById('branch-context');
    var descEl = document.getElementById('branch-desc');
    var nameEl = document.getElementById('branch-tree-name');

    if (node) {
        var badge = node.strategy === 'historical' ? '[历史线]' :
            node.strategy === 'alt1' ? '[备选A1]' :
            node.strategy === 'alt2' ? '[备选A2]' : '[' + node.strategy + ']';
        if (el) { el.textContent = '📍 Round ' + node.round + ' ' + badge + ' ' + node.name; el.style.display = 'block'; }

        // Branch panel header: show tree name + current node summary only (tree view already shows full chain)
        if (nameEl && currentTreeName) nameEl.textContent = '📋 ' + currentTreeName;
        if (descEl) {
            var prefix = node.round === 0 ? '📍' : 'R' + node.round;
            descEl.innerHTML = '<span style="color:#FF5722">' + prefix + '</span> <b>' + badge + '</b>: ' + node.name;
            descEl.style.display = 'block';
        }
    } else {
        if (el) el.style.display = 'none';
        if (descEl) descEl.style.display = 'none';
        if (nameEl) nameEl.textContent = '☰ Branches';
    }
}

function flashNotify(msg) {
    var el = document.createElement('div');
    el.textContent = msg;
    el.style.cssText = 'position:fixed;top:10px;left:50%;transform:translateX(-50%);' +
        'background:#4CAF50;color:#fff;padding:6px 20px;border-radius:4px;z-index:9999;' +
        'font-size:12px;font-weight:bold;transition:opacity 1s';
    document.body.appendChild(el);
    setTimeout(function(){ el.style.opacity = '0'; }, 1500);
    setTimeout(function(){ el.remove(); }, 2500);
}

// ---- Tree selector ----

/** Load tree list into dropdown, then load the specified (or last) tree. */
function initTreeSelector() {
    fetch('/api/branches')
        .then(function(r){return r.json();})
        .then(function(trees){
            var sel = document.getElementById('branch-tree-select');
            if (!sel) return;
            sel.innerHTML = '<option value="">-- 选择分支 --</option>';
            if (!trees || trees.length === 0) {
                sel.style.display = 'none';
                var body = document.getElementById('branch-body');
                if (body) body.innerHTML = '<div style="color:#666;padding:8px;font-size:10px">暂无分支。点击 ★ 从当前单位创建。</div>';
                return;
            }
            sel.style.display = 'block';
            for (var i = 0; i < trees.length; i++) {
                sel.innerHTML += '<option value="' + trees[i].id + '">' + trees[i].name + '</option>';
            }
            // Select last tree by default
            var lastId = trees[trees.length - 1].id;
            sel.value = lastId;
            selectTree(lastId);
        })
        .catch(function(){});
}

/** Switch to the selected tree. */
function selectTree(treeId) {
    if (!treeId) return;
    currentTreeId = treeId;
    currentNodeId = null;
    flatNodes = {};
    if (window.combatRadiusLayer && window.combatRadiusCircle) {
        window.combatRadiusLayer.removeLayer(window.combatRadiusCircle);
        window.combatRadiusCircle = null;
    }
    if (typeof clearMovePaths === 'function') clearMovePaths();
    document.getElementById('branch-desc').style.display = 'none';
    document.getElementById('branch-tree-name').textContent = '📋 加载中...';
    loadTree();
}

document.getElementById('branch-tree-select').onchange = function() {
    selectTree(this.value);
};

// ---- Auto-load on init ----
setTimeout(initTreeSelector, 5000);

})();