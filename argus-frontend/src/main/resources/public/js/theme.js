/* Argus theme toggle — persists across pages via localStorage.
   Load this BEFORE the first paint via inline include at the top of <head>. */
(function() {
    const STORAGE_KEY = 'argus-theme';
    const valid = ['light', 'dark'];

    function apply(theme) {
        if (!valid.includes(theme)) theme = 'light';
        document.documentElement.dataset.theme = theme;
    }

    function read() {
        try {
            const stored = localStorage.getItem(STORAGE_KEY);
            if (valid.includes(stored)) return stored;
        } catch (_) { /* SSR or quota */ }
        return 'light';
    }

    let warnedNoStorage = false;
    function write(theme) {
        try {
            localStorage.setItem(STORAGE_KEY, theme);
        } catch (e) {
            // Safari private mode (pre-15) throws QuotaExceededError on every write;
            // sandboxed iframes throw SecurityError. Warn ONCE so engineers can diagnose
            // why the theme isn't sticking across reloads, instead of silently failing.
            if (!warnedNoStorage && typeof console !== 'undefined' && console.warn) {
                warnedNoStorage = true;
                console.warn('[argus-theme] localStorage unavailable (' + (e && e.name) +
                    '); theme will reset on next reload.');
            }
        }
    }

    apply(read());

    function toggle() {
        const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
        apply(next);
        write(next);
        renderButton();
    }

    function renderButton() {
        const btns = document.querySelectorAll('.argus-theme-toggle');
        const isDark = document.documentElement.dataset.theme === 'dark';
        btns.forEach(b => {
            b.innerHTML = isDark
                ? '<svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M8 11a3 3 0 1 1 0-6 3 3 0 0 1 0 6Zm0 1.5a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9ZM8 0a.5.5 0 0 1 .5.5v1a.5.5 0 0 1-1 0v-1A.5.5 0 0 1 8 0Zm0 13a.5.5 0 0 1 .5.5v1a.5.5 0 0 1-1 0v-1A.5.5 0 0 1 8 13Zm8-5a.5.5 0 0 1-.5.5h-1a.5.5 0 0 1 0-1h1a.5.5 0 0 1 .5.5ZM3 8a.5.5 0 0 1-.5.5h-1a.5.5 0 0 1 0-1h1A.5.5 0 0 1 3 8Zm10.657-5.657a.5.5 0 0 1 0 .707l-.707.707a.5.5 0 1 1-.707-.707l.707-.707a.5.5 0 0 1 .707 0Zm-9.193 9.193a.5.5 0 0 1 0 .707l-.707.707a.5.5 0 1 1-.707-.707l.707-.707a.5.5 0 0 1 .707 0Zm9.193 1.414a.5.5 0 0 1-.707 0l-.707-.707a.5.5 0 0 1 .707-.707l.707.707a.5.5 0 0 1 0 .707ZM4.464 4.464a.5.5 0 0 1-.707 0l-.707-.707a.5.5 0 0 1 .707-.707l.707.707a.5.5 0 0 1 0 .707Z"/></svg>'
                : '<svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M6 .278a.768.768 0 0 1 .08.858 7.208 7.208 0 0 0-.878 3.46c0 4.021 3.278 7.277 7.318 7.277.527 0 1.04-.055 1.533-.16a.768.768 0 0 1 .81 1.21A8.5 8.5 0 1 1 6 .278Z"/></svg>';
            b.title = isDark ? 'Switch to light theme' : 'Switch to dark theme';
            b.setAttribute('aria-label', b.title);
        });
    }

    function init() {
        renderButton();
        document.querySelectorAll('.argus-theme-toggle').forEach(b => {
            b.addEventListener('click', toggle);
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    window.__argusTheme = { toggle, apply, read };
})();
