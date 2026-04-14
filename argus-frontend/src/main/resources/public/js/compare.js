/**
 * JVM Compare Page (#142)
 * Fetch /prometheus from two Argus instances, parse, compare.
 */

export function initCompare() {
    const btn = document.getElementById('compare-fetch-btn');
    if (!btn) return;
    btn.addEventListener('click', _runComparison);

    // Collapsible toggle
    const toggle = document.getElementById('compare-section-toggle');
    const body   = document.getElementById('compare-section-body');
    if (toggle && body) {
        toggle.addEventListener('click', () => {
            const collapsed = body.hidden;
            body.hidden = !collapsed;
            toggle.textContent = collapsed ? '▲' : '▼';
        });
    }
}

async function _runComparison() {
    const hostA = (document.getElementById('compare-host-a') || {}).value || 'localhost:9202';
    const hostB = (document.getElementById('compare-host-b') || {}).value || 'localhost:9202';

    const resultsEl = document.getElementById('compare-results');
    const verdictEl = document.getElementById('compare-verdict');
    if (!resultsEl) return;

    resultsEl.innerHTML = '<div class="empty-state">Fetching...</div>';
    if (verdictEl) verdictEl.textContent = '';

    let metricsA, metricsB;
    try {
        metricsA = await _fetchPrometheus(hostA);
    } catch (e) {
        resultsEl.innerHTML = `<div class="empty-state compare-error">Instance A (${_esc(hostA)}) unreachable: ${_esc(e.message)}</div>`;
        return;
    }
    try {
        metricsB = await _fetchPrometheus(hostB);
    } catch (e) {
        resultsEl.innerHTML = `<div class="empty-state compare-error">Instance B (${_esc(hostB)}) unreachable: ${_esc(e.message)}</div>`;
        return;
    }

    const merged = _mergeMetrics(metricsA, metricsB);
    _renderTable(resultsEl, merged, hostA, hostB);
    if (verdictEl) _renderVerdict(verdictEl, merged, hostA, hostB);
}

async function _fetchPrometheus(host) {
    const url = `http://${host}/prometheus`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const text = await res.text();
    return _parsePrometheus(text);
}

/**
 * Minimal Prometheus text format parser.
 * Returns Map<metricName, number>
 */
function _parsePrometheus(text) {
    const map = new Map();
    for (const line of text.split('\n')) {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith('#')) continue;
        // metric_name{labels} value [timestamp]
        const spaceIdx = trimmed.lastIndexOf(' ');
        if (spaceIdx < 0) continue;
        const valuePart = trimmed.slice(spaceIdx + 1);
        const val = parseFloat(valuePart);
        if (isNaN(val)) continue;
        // Strip labels
        const nameRaw = trimmed.slice(0, spaceIdx);
        const braceIdx = nameRaw.indexOf('{');
        const name = braceIdx >= 0 ? nameRaw.slice(0, braceIdx) : nameRaw;
        // Keep last seen value for this metric name
        map.set(name.trim(), val);
    }
    return map;
}

function _mergeMetrics(a, b) {
    const keys = new Set([...a.keys(), ...b.keys()]);
    const rows = [];
    for (const key of [...keys].sort()) {
        const va = a.has(key) ? a.get(key) : null;
        const vb = b.has(key) ? b.get(key) : null;
        rows.push({ key, va, vb });
    }
    return rows;
}

function _renderTable(container, rows, hostA, hostB) {
    if (rows.length === 0) {
        container.innerHTML = '<div class="empty-state">No metrics found.</div>';
        return;
    }

    const tbody = rows.map(({ key, va, vb }) => {
        const fA = va != null ? _fmtNum(va) : '<span class="compare-missing">—</span>';
        const fB = vb != null ? _fmtNum(vb) : '<span class="compare-missing">—</span>';
        let deltaCell = '<span class="compare-missing">—</span>';
        if (va != null && vb != null) {
            const delta = vb - va;
            const cls = delta > 0 ? 'compare-delta-pos' : delta < 0 ? 'compare-delta-neg' : 'compare-delta-zero';
            const sign = delta > 0 ? '+' : '';
            deltaCell = `<span class="${cls}">${sign}${_fmtNum(delta)}</span>`;
        }
        return `<tr>
            <td class="compare-metric-name">${_esc(key)}</td>
            <td class="compare-value">${fA}</td>
            <td class="compare-value">${fB}</td>
            <td class="compare-delta">${deltaCell}</td>
        </tr>`;
    }).join('');

    container.innerHTML = `
        <table class="compare-table">
            <thead>
                <tr>
                    <th>Metric</th>
                    <th>A (${_esc(hostA)})</th>
                    <th>B (${_esc(hostB)})</th>
                    <th>Delta (B − A)</th>
                </tr>
            </thead>
            <tbody>${tbody}</tbody>
        </table>`;
}

function _renderVerdict(el, rows, hostA, hostB) {
    // Simple score: lower heap + lower GC + lower CPU = healthier
    const HEALTH_METRICS = [
        'jvm_gc_pause_seconds_sum',
        'jvm_memory_used_bytes',
        'process_cpu_usage',
        'jvm_threads_states_threads',
    ];
    let scoreA = 0, scoreB = 0;
    for (const { key, va, vb } of rows) {
        if (!HEALTH_METRICS.some(m => key.includes(m.replace('jvm_', '').replace('_bytes', '').replace('_seconds', '')))) continue;
        if (va != null && vb != null) {
            if (va < vb) scoreA++;
            else if (vb < va) scoreB++;
        }
    }

    if (scoreA === 0 && scoreB === 0) {
        el.textContent = 'Insufficient data to determine a verdict.';
        el.className = 'compare-verdict';
        return;
    }
    if (scoreA > scoreB) {
        el.textContent = `Instance A (${hostA}) appears healthier (score ${scoreA} vs ${scoreB}).`;
        el.className = 'compare-verdict compare-verdict--a';
    } else if (scoreB > scoreA) {
        el.textContent = `Instance B (${hostB}) appears healthier (score ${scoreB} vs ${scoreA}).`;
        el.className = 'compare-verdict compare-verdict--b';
    } else {
        el.textContent = `Instances appear comparable (score ${scoreA} each).`;
        el.className = 'compare-verdict compare-verdict--tie';
    }
}

function _fmtNum(n) {
    if (Math.abs(n) >= 1e9) return (n / 1e9).toFixed(2) + 'G';
    if (Math.abs(n) >= 1e6) return (n / 1e6).toFixed(2) + 'M';
    if (Math.abs(n) >= 1e3) return (n / 1e3).toFixed(2) + 'K';
    if (Number.isInteger(n)) return n.toString();
    return n.toFixed(4);
}

function _esc(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}
