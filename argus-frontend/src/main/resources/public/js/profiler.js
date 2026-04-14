/**
 * Allocation Profiler (#140)
 * Start/Stop recording, status indicator, results table.
 */

let _recording = false;
let _startedAt = null;
let _timerInterval = null;

export function initProfiler() {
    const startBtn = document.getElementById('profiler-start-btn');
    const stopBtn  = document.getElementById('profiler-stop-btn');
    if (!startBtn || !stopBtn) return;

    startBtn.addEventListener('click', _startRecording);
    stopBtn.addEventListener('click',  _stopRecording);
    _setStatus('idle');
}

async function _startRecording() {
    try {
        const res = await fetch('/api/profiler/start', { method: 'POST' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        _recording = true;
        _startedAt = Date.now();
        _setStatus('recording');
        _startTimer();
    } catch (e) {
        _setStatus('unavailable', e.message);
    }
}

async function _stopRecording() {
    _stopTimer();
    _recording = false;
    _setStatus('loading');
    try {
        const res = await fetch('/api/profiler/stop', { method: 'POST' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        _setStatus('idle');
        _renderResults(data);
    } catch (e) {
        _setStatus('unavailable', e.message);
        _renderResults(null);
    }
}

function _startTimer() {
    const el = document.getElementById('profiler-timer');
    if (!el) return;
    _timerInterval = setInterval(() => {
        const elapsed = Math.floor((Date.now() - _startedAt) / 1000);
        el.textContent = `${elapsed}s`;
    }, 1000);
}

function _stopTimer() {
    clearInterval(_timerInterval);
    _timerInterval = null;
    const el = document.getElementById('profiler-timer');
    if (el) el.textContent = '0s';
}

function _setStatus(status, detail) {
    const startBtn  = document.getElementById('profiler-start-btn');
    const stopBtn   = document.getElementById('profiler-stop-btn');
    const statusEl  = document.getElementById('profiler-status');
    const timerWrap = document.getElementById('profiler-timer-wrap');

    if (!statusEl) return;

    switch (status) {
        case 'idle':
            statusEl.textContent = 'Idle';
            statusEl.className = 'profiler-status profiler-status--idle';
            if (startBtn) startBtn.disabled = false;
            if (stopBtn)  stopBtn.disabled = true;
            if (timerWrap) timerWrap.hidden = true;
            break;
        case 'recording':
            statusEl.textContent = 'Recording...';
            statusEl.className = 'profiler-status profiler-status--recording';
            if (startBtn) startBtn.disabled = true;
            if (stopBtn)  stopBtn.disabled = false;
            if (timerWrap) timerWrap.hidden = false;
            break;
        case 'loading':
            statusEl.textContent = 'Fetching results...';
            statusEl.className = 'profiler-status profiler-status--loading';
            if (startBtn) startBtn.disabled = true;
            if (stopBtn)  stopBtn.disabled = true;
            break;
        case 'unavailable':
            statusEl.textContent = 'Not available' + (detail ? `: ${detail}` : '');
            statusEl.className = 'profiler-status profiler-status--error';
            if (startBtn) startBtn.disabled = false;
            if (stopBtn)  stopBtn.disabled = true;
            if (timerWrap) timerWrap.hidden = true;
            break;
    }
}

function _renderResults(data) {
    const container = document.getElementById('profiler-results');
    if (!container) return;

    if (!data || !Array.isArray(data.topMethods) || data.topMethods.length === 0) {
        container.innerHTML = '<div class="empty-state">No profiling data available.</div>';
        return;
    }

    const rows = data.topMethods.map((m, i) => `
        <tr>
            <td class="profiler-rank">${i + 1}</td>
            <td class="profiler-method">${_esc(m.method || m.className || '—')}</td>
            <td class="profiler-allocs">${_fmt(m.allocations)}</td>
            <td class="profiler-size">${_fmtBytes(m.allocatedBytes)}</td>
        </tr>`).join('');

    container.innerHTML = `
        <table class="profiler-table">
            <thead>
                <tr>
                    <th>#</th>
                    <th>Method</th>
                    <th>Allocations</th>
                    <th>Total Size</th>
                </tr>
            </thead>
            <tbody>${rows}</tbody>
        </table>`;
}

function _esc(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

function _fmt(n) {
    if (n == null) return '—';
    return Number(n).toLocaleString();
}

function _fmtBytes(bytes) {
    if (!bytes) return '—';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(Math.max(1, bytes)) / Math.log(k));
    return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i];
}
