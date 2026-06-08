/**
 * Argus Continuous Profiles view (W1).
 *
 * Cluster-mode-aware: populates a pod picker from GET /api/pods (same source
 * fleet.js / dashboard-cluster.js use), an event selector (cpu/alloc/lock/wall),
 * and time-range inputs. Calls the aggregator's continuous-profiling endpoints
 * directly on the same origin:
 *   - GET /profile/query?pod=&event=&from=&to=  → merged flamegraph
 *   - GET /profile/diff?pod=&event=&baseFrom=&baseTo=&headFrom=&headTo= → diff
 * and renders via the vendored window.ArgusFlame renderer (js/flamegraph.js).
 */
(function () {
    'use strict';

    var podContext = window.ArgusPodContext;
    var AGGREGATOR_BASE = ''; // same origin

    var els = {};
    var pods = [];

    function $(id) { return document.getElementById(id); }

    function applyQueryStateToControls() {
        var event = podContext.readQueryParam('event');
        var range = podContext.readQueryParam('range');
        if (event && Array.from(els.event.options).some(function (opt) { return opt.value === event; })) {
            els.event.value = event;
        }
        if (range && Array.from(els.range.options).some(function (opt) { return opt.value === range; })) {
            els.range.value = range;
        }
    }

    function syncUrlState() {
        podContext.writeParams({
            pod: els.pod.value,
            event: els.event.value,
            range: els.range.value
        });
    }

    function setStatus(msg, kind) {
        if (!els.status) return;
        els.status.textContent = msg;
        els.status.className = 'profiles-status' + (kind ? ' profiles-status--' + kind : '');
    }

    /* ---- Pod picker -------------------------------------------------- */
    function populatePods(list) {
        pods = list || [];
        els.pod.replaceChildren();
        if (!pods.length) {
            var opt = document.createElement('option');
            opt.value = '';
            opt.textContent = 'No pods registered';
            opt.disabled = true;
            els.pod.appendChild(opt);
            return;
        }
        var grouped = podContext.groupPods(pods);
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
            els.pod.appendChild(og);
        });
    }

    function loadPods() {
        return fetch(AGGREGATOR_BASE + '/api/pods')
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (data) {
                if (data && Array.isArray(data.pods)) {
                    populatePods(data.pods);
                } else {
                    populatePods([]);
                }
            })
            .catch(function () { populatePods([]); });
    }

    /* ---- Time helpers ------------------------------------------------ */
    // The "Last N" select sets a trailing window ending now.
    function trailingWindowMillis() {
        var sec = parseInt(els.range.value, 10) || 3600;
        var to = Date.now();
        var from = to - sec * 1000;
        return { from: from, to: to };
    }

    /* ---- Query (merged flamegraph) ----------------------------------- */
    function runQuery() {
        var pod = els.pod.value;
        var event = els.event.value;
        if (!pod) { setStatus('Select a pod first.', 'warn'); return; }
        podContext.writeStoredPod(pod);
        syncUrlState();
        var w = trailingWindowMillis();
        var url = AGGREGATOR_BASE + '/profile/query?pod=' + encodeURIComponent(pod) +
            '&event=' + encodeURIComponent(event) +
            '&from=' + w.from + '&to=' + w.to;
        setStatus('Loading…', 'loading');
        fetch(url)
            .then(function (r) { return r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)); })
            .then(function (data) {
                var fg = data.flamegraph;
                renderFlame(fg, 'plain');
                var n = (fg && fg.value) || 0;
                setStatus(n > 0 ? (n + ' samples · ' + pod + ' · ' + event) : 'No samples in window.',
                    n > 0 ? 'ok' : 'warn');
            })
            .catch(function (e) { setStatus('Query failed: ' + e.message, 'error'); });
    }

    /* ---- Diff (differential flamegraph) ------------------------------ */
    function runDiff() {
        var pod = els.pod.value;
        var event = els.event.value;
        if (!pod) { setStatus('Select a pod first.', 'warn'); return; }
        podContext.writeStoredPod(pod);
        syncUrlState();
        var now = Date.now();
        var sec = parseInt(els.range.value, 10) || 3600;
        // base = the window before head; head = the trailing window.
        var headTo = now;
        var headFrom = now - sec * 1000;
        var baseTo = headFrom;
        var baseFrom = headFrom - sec * 1000;
        var url = AGGREGATOR_BASE + '/profile/diff?pod=' + encodeURIComponent(pod) +
            '&event=' + encodeURIComponent(event) +
            '&baseFrom=' + baseFrom + '&baseTo=' + baseTo +
            '&headFrom=' + headFrom + '&headTo=' + headTo;
        setStatus('Computing diff…', 'loading');
        fetch(url)
            .then(function (r) { return r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)); })
            .then(function (data) {
                var fg = data.flamegraph;
                renderFlame(fg, 'diff');
                var d = (fg && fg.delta) || 0;
                var sign = d > 0 ? '+' : '';
                setStatus('Diff (head vs prior window) · root Δ ' + sign + d, 'ok');
            })
            .catch(function (e) { setStatus('Diff failed: ' + e.message, 'error'); });
    }

    function renderFlame(root, mode) {
        if (!window.ArgusFlame) {
            setStatus('Flamegraph renderer not loaded.', 'error');
            return;
        }
        window.ArgusFlame.render(els.flame, root, { mode: mode });
        els.legend.hidden = mode !== 'diff';
    }

    /* ---- Boot -------------------------------------------------------- */
    function init() {
        els.pod = $('profiles-pod');
        els.event = $('profiles-event');
        els.range = $('profiles-range');
        els.queryBtn = $('profiles-query-btn');
        els.diffBtn = $('profiles-diff-btn');
        els.flame = $('profiles-flame');
        els.status = $('profiles-status');
        els.legend = $('profiles-diff-legend');
        if (!els.pod || !els.flame) return;

        els.queryBtn.addEventListener('click', runQuery);
        els.diffBtn.addEventListener('click', runDiff);
        els.pod.addEventListener('change', function () {
            podContext.writeStoredPod(els.pod.value);
            syncUrlState();
        });
        els.event.addEventListener('change', syncUrlState);
        els.range.addEventListener('change', syncUrlState);
        // Re-resolve to current pod selection on cross-tab change.
        window.addEventListener('storage', function (e) {
            if (e.key !== podContext.storageKey || !e.newValue) return;
            var known = new Set(pods.map(function (p) { return p.podId; }));
            if (known.has(e.newValue)) {
                els.pod.value = e.newValue;
                syncUrlState();
            }
        });

        applyQueryStateToControls();
        loadPods().then(function () {
            if (els.pod.value) runQuery();
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
