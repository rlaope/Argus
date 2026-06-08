/**
 * Argus Fleet Command Center
 *
 * Polls GET /fleet/list and GET /fleet/summary every 5s.
 * Renders a tile grid of up to 1000+ pods with color coding.
 * Click a tile to show side panel; drill-down navigates to single-JVM dashboard.
 */

const podContext = window.ArgusPodContext;
const POLL_INTERVAL_MS = 5000;
const AGGREGATOR_BASE = '';  // same origin; aggregator serves this frontend

/* ----------------------------------------------------------------
   State
---------------------------------------------------------------- */
let allTiles = [];
let selectedPodId = null;
let activeColorFilters = new Set(['green', 'yellow', 'red', 'grey']);
let filterNamespace = '';
let filterDeployment = '';
let filterSearch = '';
let pollTimer = null;

/* ----------------------------------------------------------------
   DOM refs
---------------------------------------------------------------- */
const grid          = document.getElementById('fleet-grid');
const emptyState    = document.getElementById('fleet-empty-state');
const sidePanel     = document.getElementById('fleet-side-panel');
const tileCountEl   = document.getElementById('fleet-tile-count');
const pollStatusEl  = document.getElementById('fleet-poll-status');
const pollStatusTxt = pollStatusEl.querySelector('.status-text');
const nsSelect      = document.getElementById('filter-namespace');
const depSelect     = document.getElementById('filter-deployment');
const searchInput   = document.getElementById('filter-search');
const refreshBtn    = document.getElementById('fleet-refresh-btn');
const panelClose    = document.getElementById('fleet-panel-close');

/* Summary strip */
const sumTotal      = document.getElementById('fleet-total');
const sumGreen      = document.getElementById('fleet-green');
const sumYellow     = document.getElementById('fleet-yellow');
const sumRed        = document.getElementById('fleet-red');
const sumGrey       = document.getElementById('fleet-grey');
const sumAlerts     = document.getElementById('fleet-alerts');
const sumHeapAvg    = document.getElementById('fleet-heap-avg');
const sumGcAvg      = document.getElementById('fleet-gc-avg');
const sumUpdated    = document.getElementById('fleet-last-updated');

/* Panel elements */
const panelColorDot    = document.getElementById('panel-color-dot');
const panelPodName     = document.getElementById('panel-pod-name');
const panelMeta        = document.getElementById('panel-meta');
const panelHeap        = document.getElementById('panel-heap');
const panelGc          = document.getElementById('panel-gc');
const panelCpu         = document.getElementById('panel-cpu');
const panelVt          = document.getElementById('panel-vt');
const panelAlerts      = document.getElementById('panel-alerts');
const panelAlertCount  = document.getElementById('panel-alert-count');
const panelScrapeUrl   = document.getElementById('panel-scrape-url');
const panelLastScrape  = document.getElementById('panel-last-scrape');
const panelScrapeOk    = document.getElementById('panel-scrape-ok');
const panelDrillLink   = document.getElementById('panel-drill-link');
const panelReasons     = document.getElementById('panel-reasons');
const panelProfilesCpu = document.getElementById('panel-profiles-cpu-link');
const panelProfilesAlloc = document.getElementById('panel-profiles-alloc-link');
const panelConsoleLink = document.getElementById('panel-console-link');

/* ----------------------------------------------------------------
   Polling
---------------------------------------------------------------- */
async function poll() {
    try {
        const [listResp, summaryResp] = await Promise.all([
            fetch(`${AGGREGATOR_BASE}/fleet/list`),
            fetch(`${AGGREGATOR_BASE}/fleet/summary`)
        ]);

        if (!listResp.ok || !summaryResp.ok) {
            setStatus('error');
            return;
        }

        const listData    = await listResp.json();
        const summaryData = await summaryResp.json();

        allTiles = listData.tiles || [];
        setStatus('connected');
        updateSummary(summaryData.summary);
        rebuildFilterOptions();
        renderGrid();
        sumUpdated.textContent = new Date().toLocaleTimeString();

        /* Refresh side panel if a pod is selected */
        if (selectedPodId) {
            const tile = allTiles.find(t => t.podId === selectedPodId);
            if (tile) {
                showPanel(tile);
            }
        }
    } catch (e) {
        setStatus('error');
        console.warn('[fleet] poll error:', e);
    }
}

function setStatus(state) {
    pollStatusEl.className = 'status ' + (state === 'connected' ? 'connected' : 'disconnected');
    pollStatusTxt.textContent = state === 'connected' ? 'Live' : state === 'error' ? 'Error' : 'Connecting';
}

/* ----------------------------------------------------------------
   Summary strip
---------------------------------------------------------------- */
function updateSummary(s) {
    if (!s) return;
    sumTotal.textContent  = s.totalTargets  ?? '—';
    sumGreen.textContent  = s.greenCount    ?? '0';
    sumYellow.textContent = s.yellowCount   ?? '0';
    sumRed.textContent    = s.redCount      ?? '0';
    sumGrey.textContent   = s.greyCount     ?? '0';
    sumAlerts.textContent = s.totalAlerts   ?? '0';
    sumHeapAvg.textContent = s.heap?.avg != null ? fmt1(s.heap.avg) + '%' : '—';
    sumGcAvg.textContent   = s.gc?.avg   != null ? fmt1(s.gc.avg)   + '%' : '—';
}

/* ----------------------------------------------------------------
   Filter options rebuild (namespace / deployment dropdowns)
---------------------------------------------------------------- */
function rebuildFilterOptions() {
    const namespaces  = [...new Set(allTiles.map(t => t.target?.namespace).filter(Boolean))].sort();
    const deployments = [...new Set(allTiles.map(t => t.target?.deployment).filter(Boolean))].sort();

    rebuildSelect(nsSelect,  namespaces,  filterNamespace);
    rebuildSelect(depSelect, deployments, filterDeployment);
}

function rebuildSelect(sel, values, currentVal) {
    const prev = sel.value;
    sel.innerHTML = '<option value="">All</option>';
    values.forEach(v => {
        const opt = document.createElement('option');
        opt.value = v;
        opt.textContent = v;
        if (v === (currentVal || prev)) opt.selected = true;
        sel.appendChild(opt);
    });
}

/* ----------------------------------------------------------------
   Filtering
---------------------------------------------------------------- */
function getFilteredTiles() {
    return allTiles.filter(tile => {
        if (!activeColorFilters.has(tile.color)) return false;
        if (filterNamespace  && tile.target?.namespace  !== filterNamespace)  return false;
        if (filterDeployment && tile.target?.deployment !== filterDeployment) return false;
        if (filterSearch) {
            const q = filterSearch.toLowerCase();
            const name = (tile.target?.podName || tile.podId || '').toLowerCase();
            if (!name.includes(q)) return false;
        }
        return true;
    });
}

/* ----------------------------------------------------------------
   Grid Rendering
   Uses DocumentFragment + grouped-by-namespace rendering.
   For 1000+ tiles this is fast: no DOM queries inside the loop.
---------------------------------------------------------------- */
function renderGrid() {
    const filtered = getFilteredTiles();
    tileCountEl.textContent = filtered.length + ' pod' + (filtered.length !== 1 ? 's' : '');

    if (filtered.length === 0) {
        grid.innerHTML = '';
        emptyState.style.display = 'flex';
        grid.appendChild(emptyState);
        return;
    }

    emptyState.style.display = 'none';

    /* Group by namespace/deployment */
    const groups = groupTiles(filtered);

    const frag = document.createDocumentFragment();

    for (const [groupKey, tiles] of groups) {
        /* Group header */
        const header = document.createElement('div');
        header.className = 'fleet-group-header';
        header.innerHTML =
            `<span class="fleet-group-label">${escHtml(groupKey)}</span>` +
            `<span class="fleet-group-line"></span>` +
            `<span class="fleet-group-count">${tiles.length}</span>`;
        frag.appendChild(header);

        /* Tiles */
        for (const tile of tiles) {
            frag.appendChild(buildTileEl(tile));
        }
    }

    /* Swap DOM in one shot */
    grid.replaceChildren(frag);
}

function groupTiles(tiles) {
    const map = new Map();
    for (const tile of tiles) {
        const ns  = tile.target?.namespace  || '—';
        const dep = tile.target?.deployment || '—';
        const key = dep !== '—' ? `${ns} / ${dep}` : ns;
        if (!map.has(key)) map.set(key, []);
        map.get(key).push(tile);
    }
    /* Sort groups by key */
    return new Map([...map.entries()].sort((a, b) => a[0].localeCompare(b[0])));
}

function buildTileEl(tile) {
    const el = document.createElement('div');
    const color = tile.color || 'grey';
    el.className = `fleet-tile fleet-tile--${color}` +
        (tile.alertCount > 0 && color === 'red' ? ' has-alerts' : '') +
        (tile.podId === selectedPodId ? ' selected' : '');
    el.dataset.podId = tile.podId;

    const m = tile.metrics || {};
    const podName = tile.target?.podName || tile.podId || '—';
    const ns      = tile.target?.namespace || '';

    /* Alert badge */
    const alertBadge = tile.alertCount > 0
        ? `<span class="fleet-tile-alert-badge">${tile.alertCount}</span>`
        : '';

    /* Metric chips */
    const chips = buildMetricChips(m);

    el.innerHTML =
        alertBadge +
        `<span class="fleet-tile-name">${escHtml(podName)}</span>` +
        (ns ? `<span class="fleet-tile-ns">${escHtml(ns)}</span>` : '') +
        `<div class="fleet-tile-metrics">${chips}</div>`;

    el.addEventListener('click', () => selectTile(tile));
    return el;
}

function buildMetricChips(m) {
    const chips = [];
    if (m.heapPercent != null) {
        const cls = m.heapPercent >= 90 ? '--crit' : m.heapPercent >= 80 ? '--warn' : '';
        chips.push(`<span class="fleet-tile-metric${cls}">H:${fmt1(m.heapPercent)}%</span>`);
    }
    if (m.gcOverheadPercent != null) {
        const cls = m.gcOverheadPercent >= 10 ? '--crit' : m.gcOverheadPercent >= 5 ? '--warn' : '';
        chips.push(`<span class="fleet-tile-metric${cls}">G:${fmt1(m.gcOverheadPercent)}%</span>`);
    }
    if (m.cpuPercent != null) {
        const cls = m.cpuPercent >= 90 ? '--crit' : m.cpuPercent >= 70 ? '--warn' : '';
        chips.push(`<span class="fleet-tile-metric${cls}">C:${fmt1(m.cpuPercent)}%</span>`);
    }
    return chips.join('');
}

/* ----------------------------------------------------------------
   Side Panel
---------------------------------------------------------------- */
function selectTile(tile) {
    /* Deselect previous */
    if (selectedPodId) {
        const prev = grid.querySelector(`[data-pod-id="${CSS.escape(selectedPodId)}"]`);
        if (prev) prev.classList.remove('selected');
    }
    selectedPodId = tile.podId;
    const el = grid.querySelector(`[data-pod-id="${CSS.escape(tile.podId)}"]`);
    if (el) el.classList.add('selected');

    /* Sync pod selection so Console/Profile/Dashboard pick it up. */
    podContext.writeStoredPod(tile.podId);

    showPanel(tile);
    fetchPodDetail(tile.podId);
}

function showPanel(tile) {
    const color  = tile.color || 'grey';
    const target = tile.target || {};
    const m      = tile.metrics || {};

    /* Color dot */
    panelColorDot.className = `fleet-panel-color-dot fleet-panel-color-dot--${color}`;

    /* Names */
    panelPodName.textContent = target.podName || tile.podId || '—';
    panelMeta.textContent =
        [target.namespace, target.deployment].filter(Boolean).join(' / ') || '—';

    /* Metrics */
    setMetricValue(panelHeap, m.heapPercent, '%');
    setMetricValue(panelGc,   m.gcOverheadPercent, '%');
    setMetricValue(panelCpu,  m.cpuPercent, '%');
    panelVt.textContent      = m.activeVThreads ?? '—';
    panelVt.className        = 'fleet-panel-metric-value';

    /* Scrape info */
    panelScrapeUrl.textContent    = target.scrapeUrl || '—';
    panelLastScrape.textContent   = target.lastScrapeAt
        ? new Date(target.lastScrapeAt).toLocaleTimeString()
        : 'Never';
    panelScrapeOk.textContent     = target.scrapeOk ? 'OK' : 'Failed';
    panelScrapeOk.className       = 'fleet-panel-scrape-val ' +
        (target.scrapeOk ? 'fleet-panel-scrape-ok--yes' : 'fleet-panel-scrape-ok--no');

    /* Alert count badge */
    if (tile.alertCount > 0) {
        panelAlertCount.textContent = tile.alertCount;
        panelAlertCount.classList.add('visible');
    } else {
        panelAlertCount.classList.remove('visible');
    }

    /* Drill-down URL: navigate to single-JVM dashboard with pod query param */
    const urls = podContext.contextUrls(tile.podId, { dashboard: tile.drillDownUrl });
    panelDrillLink.href = urls.dashboard;
    if (panelProfilesCpu) panelProfilesCpu.href = urls.profilesCpu;
    if (panelProfilesAlloc) panelProfilesAlloc.href = urls.profilesAlloc;
    if (panelConsoleLink) panelConsoleLink.href = urls.console;
    renderPanelReasons(tile);

    sidePanel.hidden = false;
}

function setMetricValue(el, val, suffix) {
    if (val == null) {
        el.textContent = '—';
        el.className   = 'fleet-panel-metric-value';
        return;
    }
    el.textContent = fmt1(val) + suffix;
    if (val >= 90) {
        el.className = 'fleet-panel-metric-value fleet-panel-metric-value--crit';
    } else if (val >= 70) {
        el.className = 'fleet-panel-metric-value fleet-panel-metric-value--warn';
    } else {
        el.className = 'fleet-panel-metric-value';
    }
}

function renderPanelReasons(tile) {
    if (!panelReasons) return;
    const reasons = buildTopReasons(tile).slice(0, 3);
    if (reasons.length === 0) {
        panelReasons.innerHTML = '<div class="fleet-panel-empty">No threshold drivers</div>';
        return;
    }
    panelReasons.innerHTML = reasons.map(r =>
        `<div class="fleet-panel-reason fleet-panel-reason--${r.severity}">` +
        `<div class="fleet-panel-reason-title">${escHtml(r.title)}</div>` +
        `<div class="fleet-panel-reason-detail">${escHtml(r.detail)}</div>` +
        `</div>`
    ).join('');
}

function buildTopReasons(tile) {
    const target = tile.target || {};
    const m = tile.metrics || {};
    const reasons = [];
    if (target.scrapeOk === false || tile.color === 'grey') {
        reasons.push(reason('critical', 100, 'Scrape unavailable', target.lastError || 'The aggregator cannot refresh this pod.'));
    }
    if ((tile.alertCount || 0) > 0) {
        reasons.push(reason('critical', 95, 'Active alerts', tile.alertCount + ' alert' + (tile.alertCount === 1 ? '' : 's') + ' firing.'));
    }
    if (m.heapPercent != null) {
        if (m.heapPercent >= 90) reasons.push(reason('critical', 90, 'Heap saturation', fmt1(m.heapPercent) + '% used.'));
        else if (m.heapPercent >= 80) reasons.push(reason('warning', 70, 'Heap pressure', fmt1(m.heapPercent) + '% used.'));
    }
    if (m.gcOverheadPercent != null) {
        if (m.gcOverheadPercent >= 10) reasons.push(reason('critical', 88, 'GC overhead', fmt1(m.gcOverheadPercent) + '% of runtime.'));
        else if (m.gcOverheadPercent >= 5) reasons.push(reason('warning', 68, 'GC overhead rising', fmt1(m.gcOverheadPercent) + '% of runtime.'));
    }
    if (m.cpuPercent != null) {
        if (m.cpuPercent >= 90) reasons.push(reason('critical', 84, 'CPU saturation', fmt1(m.cpuPercent) + '% JVM CPU.'));
        else if (m.cpuPercent >= 70) reasons.push(reason('warning', 60, 'CPU high', fmt1(m.cpuPercent) + '% JVM CPU.'));
    }
    if (m.activeVThreads != null && m.activeVThreads > 10000) {
        reasons.push(reason('warning', 50, 'Virtual thread backlog', fmt1(m.activeVThreads) + ' active virtual threads.'));
    }
    return reasons.sort((a, b) => b.rank - a.rank);
}

function reason(severity, rank, title, detail) {
    return { severity, rank, title, detail };
}

async function fetchPodDetail(podId) {
    try {
        const encoded = encodeURIComponent(podId).replace(/%2F/gi, '%2F');
        const resp = await fetch(`${AGGREGATOR_BASE}/fleet/pod/${encoded}`);
        if (!resp.ok) return;
        const data = await resp.json();
        renderPanelAlerts(data.alerts || []);
    } catch (e) {
        /* Non-fatal: panel still shows summary metrics */
    }
}

function renderPanelAlerts(alerts) {
    if (alerts.length === 0) {
        panelAlerts.innerHTML = '<div class="fleet-panel-empty">No active alerts</div>';
        panelAlertCount.classList.remove('visible');
        return;
    }

    panelAlertCount.textContent = alerts.length;
    panelAlertCount.classList.add('visible');

    panelAlerts.innerHTML = alerts.map(a => {
        const sev = (a.severity || 'info').toLowerCase();
        return `<div class="fleet-panel-alert-item fleet-panel-alert-item--${sev}">
            <div class="fleet-panel-alert-name">${escHtml(a.ruleName || a.alertId)}</div>
            <div class="fleet-panel-alert-detail">${escHtml(a.metric)}: ${fmt1(a.value)} ${a.comparator} ${fmt1(a.threshold)}</div>
        </div>`;
    }).join('');
}

function closePanel() {
    sidePanel.hidden = true;
    if (selectedPodId) {
        const el = grid.querySelector(`[data-pod-id="${CSS.escape(selectedPodId)}"]`);
        if (el) el.classList.remove('selected');
    }
    selectedPodId = null;
}

/* ----------------------------------------------------------------
   Event listeners
---------------------------------------------------------------- */
panelClose.addEventListener('click', closePanel);

document.addEventListener('keydown', e => {
    if (e.key === 'Escape') closePanel();
});

nsSelect.addEventListener('change', () => {
    filterNamespace = nsSelect.value;
    renderGrid();
});

depSelect.addEventListener('change', () => {
    filterDeployment = depSelect.value;
    renderGrid();
});

searchInput.addEventListener('input', () => {
    filterSearch = searchInput.value.trim();
    renderGrid();
});

refreshBtn.addEventListener('click', () => {
    clearTimeout(pollTimer);
    poll().finally(schedulePoll);
});

document.querySelectorAll('.fleet-color-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        const color = btn.dataset.color;
        if (activeColorFilters.has(color)) {
            activeColorFilters.delete(color);
            btn.classList.remove('active');
        } else {
            activeColorFilters.add(color);
            btn.classList.add('active');
        }
        renderGrid();
    });
});

/* ----------------------------------------------------------------
   Scheduler
---------------------------------------------------------------- */
function schedulePoll() {
    pollTimer = setTimeout(() => poll().finally(schedulePoll), POLL_INTERVAL_MS);
}

/* ----------------------------------------------------------------
   Utilities
---------------------------------------------------------------- */
function fmt1(v) {
    return (Math.round((v || 0) * 10) / 10).toFixed(1);
}

function escHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

/* ----------------------------------------------------------------
   Boot
---------------------------------------------------------------- */

/**
 * After tiles are rendered, find the tile matching podId and open its panel.
 * Safe to call with a null/unknown podId — does nothing in that case.
 */
function applyInitialSelection(podId) {
    if (!podId) return;
    const tile = allTiles.find(t => t.podId === podId);
    if (!tile) return;
    const el = grid.querySelector(`[data-pod-id="${CSS.escape(podId)}"]`);
    if (el) {
        selectedPodId = podId;
        el.classList.add('selected');
        showPanel(tile);
        fetchPodDetail(podId);
    }
}

/* First poll: run, then apply initial selection if we have one */
poll().then(() => {
    const initialPodId = podContext.selectInitialPod(allTiles.map(t => ({ podId: t.podId })), { hash: true });
    if (initialPodId && !selectedPodId) {
        applyInitialSelection(initialPodId);
    }
}).finally(schedulePoll);

/* Cross-tab sync: when console.html writes argus.console.pod, reflect it here */
window.addEventListener('storage', (e) => {
    if (e.key !== podContext.storageKey || !e.newValue) return;
    const podId = e.newValue;
    if (podId === selectedPodId) return;
    applyInitialSelection(podId);
});
