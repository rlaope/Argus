// Dashboard cluster-mode bootstrap.
//
// Loaded BEFORE app.js so the picker + banner exist by the time the
// modular dashboard starts wiring up its own DOM. Runs as a classic
// script (not a module) so it can fire synchronously without import
// gymnastics.
//
// Cluster mode is decided by the presence of /api/pods on the same
// origin. When present:
//   - Unhide the header pod picker (populated, grouped by deployment).
//   - Render an above-the-fold banner explaining that live per-pod
//     widgets need the aggregator metrics proxy (a follow-up graduate).
//     This prevents the operator from staring at frozen widgets and
//     thinking the page is broken.
//   - Persist the selection across pages (via window.localStorage key
//     'argus.console.pod', shared with /console.html and /fleet.html)
//     and the URL (`?pod=<id>` so a tile drill-down from /fleet lands
//     here with context).
// In standalone mode (`/api/pods` returns non-2xx or errors), this
// module does nothing — the dashboard behaves exactly as it did
// before.
(function () {
    'use strict';
    if (window.__argusCluster) return; // idempotent

    var POD_STORAGE_KEY = 'argus.console.pod';
    var state = { clusterMode: false, selectedPod: null, pods: [] };

    function readStored() {
        try { return window.localStorage.getItem(POD_STORAGE_KEY); }
        catch (e) { return null; }
    }
    function writeStored(podId) {
        try { window.localStorage.setItem(POD_STORAGE_KEY, podId); }
        catch (e) { /* localStorage disabled — URL is still authoritative */ }
    }

    function readUrlPod() {
        var m = new URLSearchParams(window.location.search).get('pod');
        return m ? decodeURIComponent(m) : null;
    }

    function writeUrlPod(podId) {
        var url = new URL(window.location.href);
        url.searchParams.set('pod', podId);
        window.history.replaceState({}, '', url.toString());
    }

    function ensureBanner() {
        var existing = document.getElementById('cluster-mode-banner');
        if (existing) return existing;
        var b = document.createElement('div');
        b.id = 'cluster-mode-banner';
        b.className = 'cluster-mode-banner';
        b.hidden = true;
        var main = document.querySelector('main');
        if (main && main.parentNode) main.parentNode.insertBefore(b, main);
        return b;
    }

    function setBannerForPod(podId, pod) {
        var b = ensureBanner();
        if (!podId || !pod) {
            b.hidden = true;
            b.innerHTML = '';
            return;
        }
        b.hidden = false;
        var status = pod.scrapeOk ? 'online' : 'offline';
        b.innerHTML =
            '<strong>Cluster mode</strong> &middot; ' +
            'Selected: <code>' + escapeHtml(podId) + '</code> (' + status + '). ' +
            'Live widgets below show this JVM only when the dashboard data proxy ' +
            'graduate lands. For now use ' +
            '<a href="/console.html" title="Run diagnostic commands against the selected pod">Console</a> ' +
            'to run commands against the selected pod, or ' +
            '<a href="/fleet.html#pod/' + encodeURIComponent(podId) + '">Fleet</a> ' +
            'for fleet-level summary metrics.';
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
    }

    function renderPicker(pods) {
        var wrap = document.getElementById('pod-picker-wrap');
        var select = document.getElementById('pod-picker');
        if (!wrap || !select) return;
        wrap.hidden = false;
        select.replaceChildren();

        if (!pods.length) {
            var opt = document.createElement('option');
            opt.value = '';
            opt.textContent = 'No pods registered';
            opt.disabled = true;
            opt.selected = true;
            select.appendChild(opt);
            return;
        }

        var grouped = {};
        pods.forEach(function (p) {
            var k = p.deployment || p.namespace || 'other';
            (grouped[k] = grouped[k] || []).push(p);
        });

        var knownIds = new Set(pods.map(function (p) { return p.podId; }));
        var stored = readStored();
        var url = readUrlPod();
        // Priority: URL > localStorage > pods[0].
        var initial = (url && knownIds.has(url)) ? url
                    : (stored && knownIds.has(stored)) ? stored
                    : pods[0].podId;

        Object.keys(grouped).sort().forEach(function (group) {
            var og = document.createElement('optgroup');
            og.label = group;
            grouped[group]
                .sort(function (a, b) { return a.podName.localeCompare(b.podName); })
                .forEach(function (p) {
                    var opt = document.createElement('option');
                    opt.value = p.podId;
                    opt.textContent = p.podName + (p.scrapeOk ? '' : ' ·offline');
                    if (p.podId === initial) opt.selected = true;
                    og.appendChild(opt);
                });
            select.appendChild(og);
        });

        applySelection(initial, pods);

        select.addEventListener('change', function () {
            applySelection(select.value, pods);
        });

        // Cross-tab sync from /console.html and /fleet.html.
        window.addEventListener('storage', function (e) {
            if (e.key !== POD_STORAGE_KEY || !e.newValue) return;
            if (e.newValue === state.selectedPod) return;
            if (!knownIds.has(e.newValue)) return;
            select.value = e.newValue;
            applySelection(e.newValue, pods);
        });
    }

    function applySelection(podId, pods) {
        state.selectedPod = podId;
        writeStored(podId);
        writeUrlPod(podId);
        var pod = pods.find(function (p) { return p.podId === podId; }) || null;
        setBannerForPod(podId, pod);
    }

    function start() {
        fetch('/api/pods')
            .then(function (res) { return res.ok ? res.json() : null; })
            .then(function (data) {
                if (!data || !Array.isArray(data.pods)) return; // standalone mode
                state.clusterMode = true;
                state.pods = data.pods;
                renderPicker(data.pods);
            })
            .catch(function () { /* standalone — silent */ });
    }

    function clusterFetchImpl(path, init) {
        // Bypass for absolute URLs.
        if (typeof path === 'string' && (path.indexOf('http://') === 0 || path.indexOf('https://') === 0)) {
            return fetch(path, init);
        }
        var podId = state.selectedPod;
        if (state.clusterMode && podId) {
            return fetch('/pod/' + encodeURIComponent(podId) + path, init);
        }
        return fetch(path, init);
    }

    window.__argusCluster = Object.freeze({
        isClusterMode: function () { return state.clusterMode; },
        selectedPod: function () { return state.selectedPod; },
        fetch: clusterFetchImpl
    });

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
