/**
 * JFR Recording Manager (#143)
 * Start/Stop JFR recordings, timer, download link.
 */

let _recording = false;
let _startedAt = null;
let _timerInterval = null;

export function initJfrManager() {
    const startBtn    = document.getElementById('jfr-start-btn');
    const stopBtn     = document.getElementById('jfr-stop-btn');
    const downloadBtn = document.getElementById('jfr-download-btn');
    if (!startBtn) return;

    startBtn.addEventListener('click',    _startRecording);
    stopBtn.addEventListener('click',     _stopRecording);
    if (downloadBtn) {
        downloadBtn.addEventListener('click', _download);
    }
    _setStatus('idle');
}

async function _startRecording() {
    const durationInput = document.getElementById('jfr-duration');
    const duration = durationInput ? parseInt(durationInput.value, 10) || 60 : 60;

    try {
        const res = await fetch('/api/jfr/start', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ duration }),
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        _recording = true;
        _startedAt = Date.now();
        _setStatus('recording', duration);
        _startTimer(duration);
    } catch (e) {
        _setStatus('unavailable', e.message);
    }
}

async function _stopRecording() {
    _stopTimer();
    try {
        const res = await fetch('/api/jfr/stop', { method: 'POST' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        _recording = false;
        _setStatus('available');
    } catch (e) {
        _recording = false;
        _setStatus('unavailable', e.message);
    }
}

function _download() {
    window.location.href = '/api/jfr/download';
}

function _startTimer(maxSeconds) {
    const el = document.getElementById('jfr-elapsed');
    if (!el) return;
    _timerInterval = setInterval(() => {
        const elapsed = Math.floor((Date.now() - _startedAt) / 1000);
        el.textContent = `${elapsed}s`;
        if (maxSeconds && elapsed >= maxSeconds) {
            _stopTimer();
            _recording = false;
            _setStatus('available');
        }
    }, 1000);
}

function _stopTimer() {
    clearInterval(_timerInterval);
    _timerInterval = null;
    const el = document.getElementById('jfr-elapsed');
    if (el) el.textContent = '0s';
}

function _setStatus(status, detail) {
    const statusEl    = document.getElementById('jfr-status');
    const elapsedWrap = document.getElementById('jfr-elapsed-wrap');
    const startBtn    = document.getElementById('jfr-start-btn');
    const stopBtn     = document.getElementById('jfr-stop-btn');
    const downloadBtn = document.getElementById('jfr-download-btn');

    if (!statusEl) return;

    switch (status) {
        case 'idle':
            statusEl.textContent = 'No active recording';
            statusEl.className = 'jfr-status jfr-status--idle';
            if (startBtn)    startBtn.disabled = false;
            if (stopBtn)     stopBtn.disabled = true;
            if (downloadBtn) downloadBtn.hidden = true;
            if (elapsedWrap) elapsedWrap.hidden = true;
            break;
        case 'recording':
            statusEl.textContent = typeof detail === 'number'
                ? `Recording in progress (${detail}s requested)`
                : 'Recording in progress';
            statusEl.className = 'jfr-status jfr-status--recording';
            if (startBtn)    startBtn.disabled = true;
            if (stopBtn)     stopBtn.disabled = false;
            if (downloadBtn) downloadBtn.hidden = true;
            if (elapsedWrap) elapsedWrap.hidden = false;
            break;
        case 'available':
            statusEl.textContent = 'Recording complete — ready to download';
            statusEl.className = 'jfr-status jfr-status--available';
            if (startBtn)    startBtn.disabled = false;
            if (stopBtn)     stopBtn.disabled = true;
            if (downloadBtn) downloadBtn.hidden = false;
            if (elapsedWrap) elapsedWrap.hidden = true;
            break;
        case 'unavailable':
            statusEl.textContent = 'Not available' + (detail ? `: ${detail}` : '');
            statusEl.className = 'jfr-status jfr-status--error';
            if (startBtn)    startBtn.disabled = false;
            if (stopBtn)     stopBtn.disabled = true;
            if (downloadBtn) downloadBtn.hidden = true;
            if (elapsedWrap) elapsedWrap.hidden = true;
            break;
    }
}
