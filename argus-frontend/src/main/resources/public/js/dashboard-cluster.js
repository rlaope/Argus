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
//   - Render an above-the-fold banner explaining that selected-pod REST
//     snapshots are active while per-pod WebSocket streaming is not.
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

    var podContext = window.ArgusPodContext;
    if (!podContext) return;
    var state = { clusterMode: false, selectedPod: null, pods: [] };

    function updateModeStrip(podId, pod) {
        var title = document.getElementById('dashboard-mode-title');
        var detail = document.getElementById('dashboard-mode-detail');
        var fleet = document.getElementById('dashboard-mode-fleet-link');
        var profiles = document.getElementById('dashboard-mode-profiles-link');
        var consoleLink = document.getElementById('dashboard-mode-console-link');
        if (!title || !detail) return;
        if (!podId || !pod) {
            title.textContent = 'Snapshot polling';
            detail.textContent = 'No selected pod';
            return;
        }
        var links = podContext.contextUrls(podId);
        var scrape = pod.scrapeOk ? 'scrape OK' : 'scrape failed';
        var last = pod.lastScrapeAt ? new Date(pod.lastScrapeAt).toLocaleTimeString() : 'never scraped';
        title.textContent = 'Snapshot polling';
        detail.textContent = podContext.podLabel(podId, pod) + ' - ' + scrape + ', last scrape ' + last;
        if (fleet) fleet.href = links.fleet;
        if (profiles) profiles.href = links.profilesCpu;
        if (consoleLink) consoleLink.href = links.console;
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
        var links = podContext.contextUrls(podId);
        updateModeStrip(podId, pod);
        b.innerHTML =
            '<strong>Cluster mode</strong> &middot; ' +
            'Selected: <code>' + escapeHtml(podId) + '</code> (' + status + '). ' +
            'Selected-pod REST snapshots are active; WebSocket event streaming is ' +
            'disabled in cluster mode. Use ' +
            '<a href="' + links.console + '" title="Run diagnostic commands against the selected pod">Console</a>, ' +
            '<a href="' + links.profilesCpu + '" title="Open profiles for the selected pod">Profiles</a>, or ' +
            '<a href="' + links.fleet + '">Fleet</a> ' +
            'for pod-specific drilldowns.';
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

        var grouped = podContext.groupPods(pods);

        var knownIds = new Set(pods.map(function (p) { return p.podId; }));
        var initial = podContext.selectInitialPod(pods);

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
            if (e.key !== podContext.storageKey || !e.newValue) return;
            if (e.newValue === state.selectedPod) return;
            if (!knownIds.has(e.newValue)) return;
            select.value = e.newValue;
            applySelection(e.newValue, pods);
        });
    }

    function applySelection(podId, pods) {
        state.selectedPod = podId;
        podContext.writeStoredPod(podId);
        podContext.writePodParam(podId);
        var pod = pods.find(function (p) { return p.podId === podId; }) || null;
        setBannerForPod(podId, pod);
    }

    var probeResolve;
    var probeReady = new Promise(function (r) { probeResolve = r; });

    function start() {
        fetch('/api/pods')
            .then(function (res) { return res.ok ? res.json() : null; })
            .then(function (data) {
                if (data && Array.isArray(data.pods)) {
                    state.clusterMode = true;
                    state.pods = data.pods;
                    renderPicker(data.pods);
                }
            })
            .catch(function () { /* standalone — silent */ })
            .finally(function () { probeResolve(); });
    }

    async function clusterFetchImpl(path, init) {
        await probeReady;
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
        selectedPodInfo: function () {
            return state.pods.find(function (p) { return p.podId === state.selectedPod; }) || null;
        },
        fetch: clusterFetchImpl
    });

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
