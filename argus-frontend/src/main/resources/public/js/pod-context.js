// Shared selected-pod context for the static dashboard surfaces.
(function () {
    'use strict';

    if (window.ArgusPodContext) return;

    var STORAGE_KEY = 'argus.console.pod';

    function readStoredPod() {
        try { return window.localStorage.getItem(STORAGE_KEY); }
        catch (e) { return null; }
    }

    function writeStoredPod(podId) {
        if (!podId) return;
        try { window.localStorage.setItem(STORAGE_KEY, podId); }
        catch (e) { /* URL context still preserves the active selection. */ }
    }

    function readQueryParam(name) {
        return new URLSearchParams(window.location.search).get(name);
    }

    function writeParams(params) {
        var url = new URL(window.location.href);
        Object.keys(params).forEach(function (key) {
            var value = params[key];
            if (value === null || value === undefined || value === '') {
                url.searchParams.delete(key);
            } else {
                url.searchParams.set(key, value);
            }
        });
        window.history.replaceState({}, '', url.toString());
    }

    function readHashPod() {
        var hash = window.location.hash;
        if (!hash || hash.indexOf('#pod/') !== 0) return null;
        var raw = hash.slice('#pod/'.length);
        if (!raw) return null;
        try { return decodeURIComponent(raw); }
        catch (e) { return null; }
    }

    function selectInitialPod(pods, options) {
        if (!Array.isArray(pods) || pods.length === 0) return null;
        var known = new Set(pods.map(function (p) { return p.podId; }));
        var opts = options || {};
        var hashPod = opts.hash ? readHashPod() : null;
        var urlPod = readQueryParam('pod');
        var stored = readStoredPod();
        return (hashPod && known.has(hashPod)) ? hashPod
            : (urlPod && known.has(urlPod)) ? urlPod
            : (stored && known.has(stored)) ? stored
            : pods[0].podId;
    }

    function groupPods(pods) {
        var grouped = {};
        (pods || []).forEach(function (p) {
            var key = p.deployment || p.namespace || 'other';
            (grouped[key] = grouped[key] || []).push(p);
        });
        return grouped;
    }

    function podLabel(podId, pod) {
        if (!pod) return podId || '';
        return pod.podName || pod.podId || podId || '';
    }

    function contextUrls(podId, options) {
        var encoded = encodeURIComponent(podId || '');
        var opts = options || {};
        return {
            dashboard: opts.dashboard || '/?pod=' + encoded,
            fleet: '/fleet.html#pod/' + encoded,
            profilesCpu: '/profiles.html?pod=' + encoded + '&event=cpu&range=3600',
            profilesAlloc: '/profiles.html?pod=' + encoded + '&event=alloc&range=3600',
            console: '/console.html?pod=' + encoded
        };
    }

    window.ArgusPodContext = Object.freeze({
        storageKey: STORAGE_KEY,
        readStoredPod: readStoredPod,
        writeStoredPod: writeStoredPod,
        readQueryParam: readQueryParam,
        writeParams: writeParams,
        writePodParam: function (podId) { writeParams({ pod: podId }); },
        readHashPod: readHashPod,
        selectInitialPod: selectInitialPod,
        groupPods: groupPods,
        podLabel: podLabel,
        contextUrls: contextUrls
    });
})();
