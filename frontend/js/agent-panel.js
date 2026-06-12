/**
 * AI Agent Panel — per-node + per-side state, sessionStorage persistence.
 * Survives node switches, tab switches, and page refreshes.
 */
(function() {
'use strict';

var SIDES = ['nationalist', 'japanese', 'cpc'];
var SIDE_LABELS = { nationalist: '国军', japanese: '日军', cpc: '八路军' };
var STORAGE_KEY = 'getgoat_agent_cache';

// ---- Persistent state: keyed by "treeId:nodeId" ----
var nodeCache = {};  // { "treeId:nodeId": { sides: {...}, simState: '...' } }
var currentTreeId = null;
var currentNodeId = null;
var deployPreviewMarkers = [];

// Load from sessionStorage
try {
    var saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY));
    if (saved) nodeCache = saved;
} catch(e) {}

function saveCache() {
    try { sessionStorage.setItem(STORAGE_KEY, JSON.stringify(nodeCache)); } catch(e) {}
}

function nodeKey(treeId, nodeId) {
    return (treeId || '?') + ':' + (nodeId || '?');
}

function getNodeState(treeId, nodeId) {
    var key = nodeKey(treeId, nodeId);
    if (!nodeCache[key]) {
        nodeCache[key] = { sides: {} };
        SIDES.forEach(function(s) {
            nodeCache[key].sides[s] = { status: 'pending', plan: null, guidance: '' };
        });
    }
    return nodeCache[key];
}

function getSideState(treeId, nodeId, side) {
    return getNodeState(treeId, nodeId).sides[side];
}

// ---- DOM ----
function buildPanel() {
    var panel = document.createElement('div');
    panel.id = 'agent-panel';
    panel.innerHTML =
        '<div id="agent-header"><span>🤖 AI Commander</span>' +
        '<button id="agent-toggle-btn" title="Collapse">−</button></div>' +
        '<div id="agent-body">' +
        '<div class="agent-section">' +
        '  <div class="agent-section-title">阵营选择</div>' +
        '  <div id="agent-side-tabs">' +
            SIDES.map(function(s) {
                return '<button class="agent-side-tab" data-side="' + s + '">' + SIDE_LABELS[s] + '</button>';
            }).join('') +
        '  </div>' +
        '  <label>作战指令 (Guidance)</label>' +
        '  <textarea id="agent-guidance" rows="3" placeholder="输入作战指令..."></textarea>' +
        '  <button id="agent-generate-btn">⚡ 生成指挥命令</button>' +
        '  <div id="agent-gen-status"></div>' +
        '</div>' +
        '<div id="agent-streams">' +
            SIDES.map(function(s) {
                return '<div id="agent-stream-' + s + '" class="agent-section agent-stream-box" style="display:none">' +
                    '<div class="agent-section-title">🧠 AI 思维链 — ' + SIDE_LABELS[s] + '</div>' +
                    '<div class="agent-stream-output"></div></div>';
            }).join('') +
        '</div>' +
        '<div class="agent-section">' +
        '  <div class="agent-section-title">📋 部署状态</div>' +
        '  <div id="agent-side-status">' +
            SIDES.map(function(s) {
                return '<div class="side-status-row" data-side="' + s + '">' +
                    '<span class="side-status-icon pending">○</span>' +
                    '<span class="side-status-name">' + SIDE_LABELS[s] + '</span>' +
                    '<span class="side-status-text">待生成</span></div>';
            }).join('') +
        '  </div>' +
        '</div>' +
        '<div class="agent-section">' +
        '  <div class="agent-section-title">⚔ 推演</div>' +
        '  <textarea id="agent-sim-guidance" rows="2" placeholder="推演指令..."></textarea>' +
        '  <button id="agent-sim-btn" disabled>▶ 生成推演</button>' +
        '  <div id="agent-sim-result" style="display:none"></div>' +
        '</div>' +
        '</div>';
    document.body.appendChild(panel);
}

function bindEvents() {
    document.getElementById('agent-toggle-btn').addEventListener('click', function() {
        var p = document.getElementById('agent-panel');
        var b = document.getElementById('agent-toggle-btn');
        p.classList.toggle('collapsed');
        b.textContent = p.classList.contains('collapsed') ? '+' : '−';
    });

    document.querySelectorAll('.agent-side-tab').forEach(function(btn) {
        btn.addEventListener('click', function() {
            selectSide(this.dataset.side);
        });
    });

    document.getElementById('agent-guidance').addEventListener('input', function() {
        var side = getSelectedSide();
        if (currentTreeId && currentNodeId) {
            getSideState(currentTreeId, currentNodeId, side).guidance = this.value;
            saveCache();
        }
    });

    document.getElementById('agent-generate-btn').addEventListener('click', function() {
        startSseStream(getSelectedSide());
    });

    document.getElementById('agent-sim-btn').addEventListener('click', executeSimulation);
}

// ---- Side Selection ----
function getSelectedSide() {
    var active = document.querySelector('.agent-side-tab.active');
    return active ? active.dataset.side : 'nationalist';
}

function selectSide(side) {
    document.querySelectorAll('.agent-side-tab').forEach(function(b) {
        b.classList.toggle('active', b.dataset.side === side);
    });

    // Show only this side's stream container
    SIDES.forEach(function(s) {
        document.getElementById('agent-stream-' + s).style.display = (s === side) ? 'block' : 'none';
    });

    syncUI(side);

    // Auto-draw deployment preview for this side's plan
    clearDeployPreviewLocal();
    if (currentTreeId && currentNodeId) {
        var plan = getSideState(currentTreeId, currentNodeId, side).plan;
        if (plan && plan.finalPlan && Array.isArray(plan.finalPlan)) {
            if (typeof window.previewDeployment === 'function') {
                window.previewDeployment(plan.finalPlan);
            } else {
                previewDeploymentLocal(plan.finalPlan);
            }
        }
    }
}

/** Pull current node state into the UI (guidance, stream HTML, status icons, sim button). */
function syncUI(side) {
    if (!currentTreeId || !currentNodeId) {
        // No node selected
        document.getElementById('agent-guidance').value = '';
        document.getElementById('agent-gen-status').innerHTML = '';
        document.getElementById('agent-generate-btn').disabled = false;
        document.getElementById('agent-generate-btn').textContent = '⚡ 生成指挥命令';
        return;
    }

    var st = getSideState(currentTreeId, currentNodeId, side);

    // Restore guidance
    document.getElementById('agent-guidance').value = st.guidance || '';

    // Restore stream content from cache, or render plan commands
    var output = document.querySelector('#agent-stream-' + side + ' .agent-stream-output');
    if (st.streamHtml) {
        output.innerHTML = st.streamHtml;
        output.scrollTop = output.scrollHeight;
    } else if (st.status === 'generated' && st.plan && st.plan.finalPlan) {
        output.innerHTML = renderPlanCommands(st.plan);
    } else if (st.status !== 'generating') {
        output.innerHTML = '';
    }

    // Update button + status
    if (st.status === 'generating') {
        document.getElementById('agent-generate-btn').disabled = true;
        document.getElementById('agent-generate-btn').textContent = '⏳ 生成中...';
        document.getElementById('agent-gen-status').innerHTML =
            '<span class="gen-status-badge generating">⚙ LLM 正在推理中...</span>';
    } else if (st.status === 'generated') {
        document.getElementById('agent-generate-btn').disabled = false;
        document.getElementById('agent-generate-btn').textContent = '⚡ 重新生成';
        document.getElementById('agent-gen-status').innerHTML =
            '<span class="gen-status-badge done">✓ 已有部署计划</span>';
    } else {
        document.getElementById('agent-generate-btn').disabled = false;
        document.getElementById('agent-generate-btn').textContent = '⚡ 生成指挥命令';
        document.getElementById('agent-gen-status').innerHTML = '';
    }

    // Refresh all side status icons for current node
    SIDES.forEach(function(s) {
        updateSideStatusIcon(s, getSideState(currentTreeId, currentNodeId, s).status);
    });
    updateSimButtonForCurrentNode();
}

// ---- SSE Streaming ----
function startSseStream(side) {
    if (!currentTreeId || !currentNodeId) {
        alert('请先在分支树中选择一个节点'); return;
    }

    var guidance = document.getElementById('agent-guidance').value.trim();
    if (!guidance) { alert('请输入作战指令 (Guidance)'); return; }

    var st = getSideState(currentTreeId, currentNodeId, side);
    st.guidance = guidance;
    st.status = 'generating';
    st.streamHtml = '';
    saveCache();

    updateSideStatusIcon(side, 'generating');
    var genBtn = document.getElementById('agent-generate-btn');
    genBtn.disabled = true; genBtn.textContent = '⏳ 生成中...';
    document.getElementById('agent-gen-status').innerHTML =
        '<span class="gen-status-badge generating">⚙ LLM 正在推理中...</span>';

    // Show and clear this side's stream
    document.getElementById('agent-stream-' + side).style.display = 'block';
    var output = document.querySelector('#agent-stream-' + side + ' .agent-stream-output');
    output.innerHTML = '<div class="subround-block" style="border-left-color:#f39c12">' +
        '<span class="sr-header">⏳ 连接 AI 指挥官...</span></div>';

    // Abort this side's existing stream only (other sides keep running)
    var st = getSideState(currentTreeId, currentNodeId, side);
    if (st._eventSource && typeof st._eventSource.close === 'function') { st._eventSource.close(); st._eventSource = null; }

    var url = '/api/commander/' + encodeURIComponent(side) + '/deploy-stream' +
        '?tree=' + encodeURIComponent(currentTreeId) +
        '&node=' + encodeURIComponent(currentNodeId) +
        '&guidance=' + encodeURIComponent(guidance);
    console.log('[agent] EventSource URL:', url);

    var es = new EventSource(url);
    st._eventSource = es;

    es.addEventListener('subround', function(e) {
        console.log('[agent] subround received for', side, 'data length:', e.data.length);
        try {
            var data = JSON.parse(e.data);
            console.log('[agent] parsed:', data.iteration, 'tools:', (data.toolCalls||[]).length, 'done:', !!data.done);
            renderSubRound(side, data);
        } catch (err) { console.error('[agent] subround error:', err); }
    });

    es.addEventListener('done', function(e) {
        console.log('[agent] done received for', side);
        try {
            var data = JSON.parse(e.data);
            handleDoneEvent(side, data);
        } catch (err) { console.error('[agent] done error:', err); }
        es.close(); getSideState(currentTreeId, currentNodeId, side)._eventSource = null;
    });

    var errorHandled = false;
    es.addEventListener('error', function(e) {
        if (errorHandled) return;
        errorHandled = true;
        var msg = 'Connection error';
        try { if (e.data) { var d = JSON.parse(e.data); msg = d.error || msg; } } catch (ex) {}
        handleErrorEvent(side, msg);
        es.close(); getSideState(currentTreeId, currentNodeId, side)._eventSource = null;
    });
    es.onerror = function() {
        if (es.readyState === EventSource.CLOSED && !errorHandled) {
            errorHandled = true;
            handleErrorEvent(side, 'Connection closed unexpectedly');
            getSideState(currentTreeId, currentNodeId, side)._eventSource = null;
        }
    };
}

function renderSubRound(side, data) {
    var output = document.querySelector('#agent-stream-' + side + ' .agent-stream-output');
    console.log('[agent] renderSubRound side=', side, 'output found=', !!output);
    if (!output) return;

    var block = document.createElement('div');
    block.className = 'subround-block';

    var header = document.createElement('div');
    header.className = 'sr-header';
    header.textContent = '🔄 SubRound ' + data.iteration;
    block.appendChild(header);

    if (data.rationale) {
        var ratDiv = document.createElement('div');
        ratDiv.className = 'sr-rationale';
        var text = data.rationale;
        if (text.length > 200) {
            ratDiv.textContent = text.substring(0, 200) + '...';
            ratDiv.title = 'Click to expand'; ratDiv.style.cursor = 'pointer';
            ratDiv.onclick = function() { ratDiv.textContent = text; ratDiv.title = ''; ratDiv.style.cursor = 'default'; };
        } else { ratDiv.textContent = text; }
        block.appendChild(ratDiv);
    }

    if (data.toolCalls && Array.isArray(data.toolCalls)) {
        for (var i = 0; i < data.toolCalls.length; i++) {
            var tc = data.toolCalls[i], fn = tc.function || {}, args = fn.arguments;
            if (typeof args === 'string') { try { args = JSON.parse(args); } catch (e) {} }
            var d = document.createElement('div');
            d.className = 'sr-tool';
            d.textContent = '🔧 ' + (fn.name || '?') + ': ' + (typeof args === 'object' ? JSON.stringify(args) : String(args));
            block.appendChild(d);
        }
    }

    if (data.toolResults && Array.isArray(data.toolResults)) {
        for (var j = 0; j < data.toolResults.length; j++) {
            var tr = data.toolResults[j];
            var c = typeof tr.content === 'string' ? tr.content : JSON.stringify(tr.content);
            var rd = document.createElement('div');
            rd.className = 'sr-result';
            rd.textContent = '  → ' + (c.length > 100 ? c.substring(0, 100) + '...' : c);
            block.appendChild(rd);
        }
    }

    if (data.done) {
        var dd = document.createElement('div'); dd.className = 'sr-done';
        dd.textContent = '✓ 计划完成'; block.appendChild(dd);
    }

    output.appendChild(block);
    output.scrollTop = output.scrollHeight;

    // Persist HTML to cache
    var st = getSideState(currentTreeId, currentNodeId, side);
    st.streamHtml = output.innerHTML;
    saveCache();
}

function handleDoneEvent(side, data) {
    var st = getSideState(currentTreeId, currentNodeId, side);
    st.status = 'generated';
    st.plan = data;
    st.streamHtml = document.querySelector('#agent-stream-' + side + ' .agent-stream-output').innerHTML;
    saveCache();

    updateSideStatusIcon(side, 'generated');
    document.getElementById('agent-generate-btn').disabled = false;
    document.getElementById('agent-generate-btn').textContent = '⚡ 重新生成';
    document.getElementById('agent-gen-status').innerHTML =
        '<span class="gen-status-badge done">✓ 部署计划已生成 (' + (data.subRounds || '?') + ' 轮推理)</span>';

    if (data.finalPlan && Array.isArray(data.finalPlan)) {
        clearDeployPreviewLocal();
        if (typeof window.previewDeployment === 'function') window.previewDeployment(data.finalPlan);
        else previewDeploymentLocal(data.finalPlan);
    }
    updateSimButtonForCurrentNode();
}

function handleErrorEvent(side, msg) {
    var st = getSideState(currentTreeId, currentNodeId, side);
    st.status = 'pending';
    saveCache();

    updateSideStatusIcon(side, 'pending');
    document.getElementById('agent-generate-btn').disabled = false;
    document.getElementById('agent-generate-btn').textContent = '⚡ 生成指挥命令';
    document.getElementById('agent-gen-status').innerHTML =
        '<span class="gen-status-badge error">✗ ' + msg + '</span>';

    var output = document.querySelector('#agent-stream-' + side + ' .agent-stream-output');
    if (output) {
        var errDiv = document.createElement('div');
        errDiv.className = 'subround-block'; errDiv.style.borderLeftColor = '#e74c3c';
        errDiv.innerHTML = '<span style="color:#e74c3c">✗ 错误: ' + msg + '</span>';
        output.appendChild(errDiv); output.scrollTop = output.scrollHeight;
        st.streamHtml = output.innerHTML; saveCache();
    }
}

// ---- Side Status ----
function updateSideStatusIcon(side, status) {
    var row = document.querySelector('#agent-side-status .side-status-row[data-side="' + side + '"]');
    if (!row) return;
    var icon = row.querySelector('.side-status-icon');
    var text = row.querySelector('.side-status-text');
    icon.className = 'side-status-icon ' + status;
    if (status === 'generated') { icon.textContent = '✓'; text.textContent = '已生成'; }
    else if (status === 'generating') { icon.textContent = '◉'; text.textContent = '生成中...'; }
    else { icon.textContent = '○'; text.textContent = '待生成'; }
}

function updateSimButtonForCurrentNode() {
    var btn = document.getElementById('agent-sim-btn');
    if (!currentTreeId || !currentNodeId) { btn.disabled = true; return; }
    var allDone = SIDES.every(function(s) {
        return getSideState(currentTreeId, currentNodeId, s).status === 'generated';
    });
    btn.disabled = !allDone;
    if (allDone) btn.textContent = '▶ 生成推演';
}

// ---- Node Change Hook ----
/** Called externally when the branch tree selects a new node. */
window.agentOnNodeChange = function(treeId, nodeId) {
    // Save active side's guidance before switching
    if (currentTreeId && currentNodeId) {
        var activeGuidance = document.getElementById('agent-guidance').value;
        var activeSide = getSelectedSide();
        getSideState(currentTreeId, currentNodeId, activeSide).guidance = activeGuidance;
        saveCache();
    }

    // Abort all running streams for the OLD node
    if (currentTreeId && currentNodeId) {
        var oldNs = getNodeState(currentTreeId, currentNodeId);
        SIDES.forEach(function(s) {
            var es = oldNs.sides[s]._eventSource;
            if (es && typeof es.close === 'function') {
                es.close();
                oldNs.sides[s]._eventSource = null;
                oldNs.sides[s].status = 'pending';
            }
        });
    }

    currentTreeId = treeId;
    currentNodeId = nodeId;

    if (treeId && nodeId) {
        // Load this node's state into the UI
        var side = getSelectedSide();
        syncUI(side);

        // Restore sim result for this node
        var ns = getNodeState(treeId, nodeId);
        if (ns.simResultHtml) {
            document.getElementById('agent-sim-result').style.display = 'block';
            document.getElementById('agent-sim-result').innerHTML = ns.simResultHtml;
        } else {
            document.getElementById('agent-sim-result').style.display = 'none';
            document.getElementById('agent-sim-result').innerHTML = '';
        }
    } else {
        // No node selected — reset UI
        document.getElementById('agent-guidance').value = '';
        document.getElementById('agent-gen-status').innerHTML = '';
        document.getElementById('agent-generate-btn').disabled = false;
        document.getElementById('agent-sim-btn').disabled = true;
        SIDES.forEach(function(s) {
            updateSideStatusIcon(s, 'pending');
            document.querySelector('#agent-stream-' + s + ' .agent-stream-output').innerHTML = '';
        });
    }
};

// ---- Simulation ----
function executeSimulation() {
    if (!currentTreeId || !currentNodeId) { alert('请先选择分支节点'); return; }

    var btn = document.getElementById('agent-sim-btn');
    btn.disabled = true; btn.textContent = '⏳ 推演中...';

    var resultDiv = document.getElementById('agent-sim-result');
    resultDiv.style.display = 'block';
    resultDiv.innerHTML = '<span style="color:#f39c12">推演计算中...</span>';

    fetch('/api/simulate?tree=' + encodeURIComponent(currentTreeId) + '&node=' + encodeURIComponent(currentNodeId), { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function(d) {
            if (d.error) {
                resultDiv.innerHTML = '<span style="color:#e74c3c">错误: ' + d.error + '</span>';
                btn.disabled = false; btn.textContent = '▶ 生成推演'; return;
            }
            var html =
                '<div style="color:#2ecc71;font-weight:bold">推演完成: Round ' + (d.round || '?') + '</div>' +
                '<div style="font-size:10px;color:#ccc;margin-top:4px">' +
                '移动: ' + (d.unitsReachedDestination || 0) + '/' + (d.movementsResolved || 0) + ' 抵达, ' +
                '接敌: ' + (d.engagementsDetected || 0) + ' 对, ' +
                '战果: ' + (d.destroyed || 0) + ' 消灭/' + (d.retreated || 0) + ' 撤退/' + (d.engaged || 0) + ' 交战中' +
                (d.summary ? '<br><div class="sim-summary">' + d.summary + '</div>' : '') + '</div>';
            resultDiv.innerHTML = html;

            // Persist sim result for this node
            var ns = getNodeState(currentTreeId, currentNodeId);
            ns.simResultHtml = html;
            saveCache();

            // Reset sides for this node
            SIDES.forEach(function(s) {
                var st = getSideState(currentTreeId, currentNodeId, s);
                st.status = 'pending'; st.plan = null; st.streamHtml = '';
                document.querySelector('#agent-stream-' + s + ' .agent-stream-output').innerHTML = '';
                document.getElementById('agent-stream-' + s).style.display = 'none';
                updateSideStatusIcon(s, 'pending');
            });
            saveCache();
            updateSimButtonForCurrentNode();
            btn.textContent = '▶ 重新推演'; btn.disabled = true;

            if (typeof window.loadUnits === 'function') window.loadUnits();
            if (typeof window.loadBranchTree === 'function') window.loadBranchTree();
        })
        .catch(function(e) {
            resultDiv.innerHTML = '<span style="color:#e74c3c">网络错误: ' + e.message + '</span>';
            btn.disabled = false; btn.textContent = '▶ 生成推演';
        });
}

// ---- Map Preview ----
function previewDeploymentLocal(plan) {
    var map = window.map || window.leafletMap;
    if (!map || !window.unitMarkers) return;
    clearDeployPreviewLocal();
    for (var i = 0; i < plan.length; i++) {
        var tc = plan[i], fn = tc.function || {}, args = fn.arguments;
        if (typeof args === 'string') { try { args = JSON.parse(args); } catch (e) { continue; } }
        if (fn.name === 'move_unit' && args.code && args.lat && args.lng) {
            var m = window.unitMarkers[args.code];
            if (m) {
                var line = L.polyline([m.getLatLng(), L.latLng(args.lat, args.lng)],
                    { color: '#f39c12', weight: 2, dashArray: '6,4', opacity: 0.7 }).addTo(map);
                var arrow = L.marker(L.latLng(args.lat, args.lng),
                    { icon: L.divIcon({ className: 'deploy-arrow', html: '▼', iconSize: [12,12], iconAnchor: [6,6] }) }).addTo(map);
                deployPreviewMarkers.push(line, arrow);
            }
        }
    }
}
function clearDeployPreviewLocal() {
    deployPreviewMarkers.forEach(function(m) { if (m._map) m.remove(); });
    deployPreviewMarkers = [];
}

// ---- Plan rendering ----
function renderPlanCommands(plan) {
    var html = '<div class="subround-block" style="border-left-color:#2ecc71">' +
        '<div class="sr-header">📋 部署命令 (' + (plan.subRounds || '?') + ' 轮推理)</div>';
    if (plan.rationale) {
        html += '<div class="sr-rationale">' + escHtml(plan.rationale).substring(0, 200) + '</div>';
    }
    var planArr = plan.finalPlan || [];
    if (planArr.length === 0) {
        html += '<div style="color:#888;font-size:10px">无行动命令</div>';
    } else {
        for (var i = 0; i < planArr.length; i++) {
            var tc = planArr[i], fn = tc.function || {};
            var args = fn.arguments;
            if (typeof args === 'string') { try { args = JSON.parse(args); } catch (e) {} }
            var code = args.code || '?';
            var icon = fn.name === 'move_unit' ? '➤' : '✦';
            var detail = fn.name === 'move_unit'
                ? code + ' → (' + (args.lat||'?') + ', ' + (args.lng||'?') + ')'
                : code + ' ' + (args.name||'') + ' @ (' + (args.lat||'?') + ', ' + (args.lng||'?') + ')';
            html += '<div class="sr-tool" style="font-size:10px">' + icon + ' ' + detail + '</div>';
        }
    }
    if (plan.risks) {
        html += '<div style="color:#e67e22;font-size:9px;margin-top:4px">⚠ ' + escHtml(plan.risks).substring(0, 150) + '</div>';
    }
    html += '</div>';
    return html;
}

function escHtml(s) {
    if (!s) return '';
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

// ---- Init ----
// Clean stale state from refreshed/crashed sessions
Object.keys(nodeCache).forEach(function(key) {
    var ns = nodeCache[key];
    if (ns && ns.sides) {
        Object.keys(ns.sides).forEach(function(s) {
            if (ns.sides[s].status === 'generating') {
                ns.sides[s].status = 'pending';
            }
            // Purge non-serializable _eventSource (JSON → {}) left from last session
            if (ns.sides[s]._eventSource && typeof ns.sides[s]._eventSource.close !== 'function') {
                delete ns.sides[s]._eventSource;
            }
        });
    }
});
saveCache();

buildPanel();
bindEvents();
selectSide('nationalist');
console.log('Agent panel initialized (node-persistent)');
})();
