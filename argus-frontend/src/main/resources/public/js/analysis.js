/**
 * File Analysis Modal System
 * Handles file input, validation, upload, and result rendering.
 */

const COMMANDS = {
    gclog: {
        title: 'GC Log Analysis',
        fields: [{ name: 'file1', label: 'GC Log File', type: 'file', accept: '.log,.gz,.txt', required: true }],
        endpoint: '/api/analyze/gclog'
    },
    gclogdiff: {
        title: 'GC Log Comparison',
        fields: [
            { name: 'file1', label: 'Before (file 1)', type: 'file', accept: '.log,.gz,.txt', required: true },
            { name: 'file2', label: 'After (file 2)', type: 'file', accept: '.log,.gz,.txt', required: true }
        ],
        endpoint: '/api/analyze/gclogdiff'
    }
};

export function initAnalysis() {
    document.querySelectorAll('.analysis-cmd-btn').forEach(btn => {
        btn.addEventListener('click', () => openModal(btn.dataset.cmd));
    });

    document.querySelector('#analysis-modal .modal-backdrop').addEventListener('click', closeModal);
    document.querySelector('#analysis-modal .modal-close').addEventListener('click', closeModal);
    document.getElementById('analysis-cancel-btn').addEventListener('click', closeModal);
    document.getElementById('analysis-submit-btn').addEventListener('click', submitAnalysis);

    document.querySelector('#analysis-result .modal-backdrop').addEventListener('click', closeResult);
    document.querySelector('#analysis-result .modal-close').addEventListener('click', closeResult);
}

function openModal(cmdName) {
    const cmd = COMMANDS[cmdName];
    if (!cmd) return;

    document.getElementById('analysis-modal-title').textContent = cmd.title;
    const form = document.getElementById('analysis-modal-form');
    form.innerHTML = '';
    form.dataset.cmd = cmdName;

    cmd.fields.forEach(field => {
        const div = document.createElement('div');
        div.className = 'form-group';
        div.innerHTML = `
            <label for="analysis-${field.name}">${field.label}</label>
            <input type="file" id="analysis-${field.name}" name="${field.name}"
                   accept="${field.accept}" ${field.required ? 'required' : ''}>
            <span class="file-status" id="status-${field.name}"></span>
        `;
        form.appendChild(div);
    });

    form.querySelectorAll('input[type=file]').forEach(input => {
        input.addEventListener('change', validateForm);
    });

    document.getElementById('analysis-submit-btn').disabled = true;
    document.getElementById('analysis-modal-validation').textContent = '';
    document.getElementById('analysis-progress').classList.add('hidden');
    document.getElementById('analysis-modal').classList.remove('hidden');
}

function closeModal() {
    document.getElementById('analysis-modal').classList.add('hidden');
}

function closeResult() {
    document.getElementById('analysis-result').classList.add('hidden');
}

function validateForm() {
    const form = document.getElementById('analysis-modal-form');
    const cmdName = form.dataset.cmd;
    const cmd = COMMANDS[cmdName];

    let valid = true;
    cmd.fields.forEach(field => {
        const input = document.getElementById(`analysis-${field.name}`);
        const status = document.getElementById(`status-${field.name}`);
        if (field.required && (!input.files || input.files.length === 0)) {
            valid = false;
            status.textContent = '';
            status.className = 'file-status';
        } else if (input.files && input.files.length > 0) {
            const file = input.files[0];
            const sizeMB = (file.size / (1024 * 1024)).toFixed(1);
            if (file.size > 200 * 1024 * 1024) {
                status.textContent = `File too large: ${sizeMB}MB (max 200MB)`;
                status.className = 'file-status error';
                valid = false;
            } else {
                status.textContent = `${file.name} (${sizeMB}MB)`;
                status.className = 'file-status ok';
            }
        }
    });

    document.getElementById('analysis-submit-btn').disabled = !valid;
    if (valid) document.getElementById('analysis-modal-validation').textContent = '';
}

async function submitAnalysis() {
    const form = document.getElementById('analysis-modal-form');
    const cmdName = form.dataset.cmd;
    const cmd = COMMANDS[cmdName];

    const formData = new FormData();
    cmd.fields.forEach(field => {
        const input = document.getElementById(`analysis-${field.name}`);
        if (input.files && input.files.length > 0) {
            formData.append(field.name, input.files[0]);
        }
    });

    const progress = document.getElementById('analysis-progress');
    progress.classList.remove('hidden');
    document.getElementById('analysis-submit-btn').disabled = true;

    try {
        const resp = await fetch(cmd.endpoint, { method: 'POST', body: formData });
        if (!resp.ok) {
            const text = await resp.text().catch(() => resp.statusText);
            throw new Error(`Server error ${resp.status}: ${text}`);
        }
        const result = await resp.json();
        closeModal();
        showResult(cmdName, result);
    } catch (e) {
        document.getElementById('analysis-modal-validation').textContent = 'Error: ' + e.message;
    } finally {
        progress.classList.add('hidden');
        document.getElementById('analysis-submit-btn').disabled = false;
    }
}

function showResult(cmdName, data) {
    document.getElementById('result-title').textContent = COMMANDS[cmdName].title + ' — Results';
    const body = document.getElementById('result-body');

    if (cmdName === 'gclog') {
        body.innerHTML = renderGcLogResult(data);
    } else if (cmdName === 'gclogdiff') {
        body.innerHTML = renderGcDiffResult(data);
    } else {
        body.innerHTML = `<pre>${JSON.stringify(data, null, 2)}</pre>`;
    }

    document.getElementById('analysis-result').classList.remove('hidden');
}

function renderGcLogResult(d) {
    const tp = parseFloat(d.throughputPercent);
    const tpClass = tp >= 95 ? 'good' : tp >= 90 ? 'warn' : 'bad';
    const pauses = d.pauses || {};

    let causesHtml = '';
    if (d.causes && Object.keys(d.causes).length > 0) {
        const rows = Object.entries(d.causes).map(([cause, stats]) =>
            `<tr><td>${escapeHtml(cause)}</td><td>${stats.count}</td><td>${stats.avgMs}ms</td><td>${stats.maxMs}ms</td></tr>`
        ).join('');
        causesHtml = `
        <div class="result-section">
            <h4>GC Causes</h4>
            <table class="result-table">
                <tr><th>Cause</th><th>Count</th><th>Avg</th><th>Max</th></tr>
                ${rows}
            </table>
        </div>`;
    }

    let recsHtml = '';
    if (d.recommendations && d.recommendations.length > 0) {
        const items = d.recommendations.map(r =>
            `<li class="rec-${r.severity.toLowerCase()}">
                <strong>[${escapeHtml(r.severity)}]</strong> ${escapeHtml(r.problem)}
                ${r.flag ? `<br><code>${escapeHtml(r.flag)}</code>` : ''}
            </li>`
        ).join('');
        recsHtml = `
        <div class="result-section full-width">
            <h4>Recommendations</h4>
            <ul class="rec-list">${items}</ul>
        </div>`;
    }

    return `<div class="result-grid">
        <div class="result-section">
            <h4>Summary</h4>
            <table class="result-table">
                <tr><td>Total Events</td><td>${d.totalEvents} (${d.pauseEvents} pauses, ${d.fullGcEvents} full)</td></tr>
                <tr><td>Duration</td><td>${d.durationSec}s</td></tr>
                <tr><td>Throughput</td><td class="${tpClass}">${d.throughputPercent}%</td></tr>
            </table>
        </div>
        <div class="result-section">
            <h4>Pause Distribution</h4>
            <table class="result-table">
                <tr><td>p50</td><td>${pauses.p50Ms}ms</td></tr>
                <tr><td>p95</td><td>${pauses.p95Ms}ms</td></tr>
                <tr><td>p99</td><td>${pauses.p99Ms}ms</td></tr>
                <tr><td>Max</td><td>${pauses.maxMs}ms</td></tr>
                <tr><td>Avg</td><td>${pauses.avgMs}ms</td></tr>
            </table>
        </div>
        ${causesHtml}
        ${recsHtml}
    </div>`;
}

function renderGcDiffResult(d) {
    const metrics = d.metrics || {};
    const rows = Object.entries(metrics).map(([k, v]) => {
        const before = Array.isArray(v) ? v[0] : '-';
        const after = Array.isArray(v) ? v[1] : '-';
        return `<tr><td>${escapeHtml(k)}</td><td>${before}</td><td>${after}</td></tr>`;
    }).join('');

    const statusClass = d.regression ? 'bad' : 'good';
    const statusText = d.regression ? 'Regression detected' : 'No regression detected';

    return `<div class="result-section full-width">
        <p class="${statusClass}" style="margin-bottom:1rem;font-weight:600;">${statusText}</p>
        <table class="result-table">
            <tr><th>Metric</th><th>Before</th><th>After</th></tr>
            ${rows}
        </table>
    </div>`;
}

function escapeHtml(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
