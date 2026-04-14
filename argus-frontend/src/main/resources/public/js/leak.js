/**
 * Argus Dashboard - Memory Leak Detection Module
 *
 * Reads leak data from /gc-analysis (polled) and WebSocket GC events.
 * Renders: R² gauge, status badge, heap trend mini-chart.
 */

import { gcData } from './state.js';

let leakChart = null;
const heapAfterGC = { labels: [], values: [] };
const MAX_HEAP_POINTS = 40;

/**
 * Initialize the leak detection panel.
 */
export function initLeak() {
    initLeakChart();
}

/**
 * Update leak panel from GC analysis data fetched by app.js.
 * Called whenever fetchGCAnalysis completes.
 *
 * @param {object} data - /gc-analysis JSON response
 */
export function updateLeakPanel(data) {
    const leakSuspected = data.leakSuspected === true;
    const confidence = typeof data.leakConfidencePercent === 'number' ? data.leakConfidencePercent : 0;

    // Collect heap-after-GC data points from recentGCs
    if (Array.isArray(data.recentGCs)) {
        for (const gc of data.recentGCs) {
            if (gc.heapUsedAfter > 0 && gc.timestamp) {
                const ts = new Date(gc.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
                // Avoid duplicate timestamps
                if (!heapAfterGC.labels.includes(ts)) {
                    heapAfterGC.labels.push(ts);
                    heapAfterGC.values.push(gc.heapUsedAfter / (1024 * 1024)); // bytes -> MB
                    if (heapAfterGC.labels.length > MAX_HEAP_POINTS) {
                        heapAfterGC.labels.shift();
                        heapAfterGC.values.shift();
                    }
                }
            }
        }
    }

    renderLeakGauge(confidence / 100); // convert percent 0-100 to ratio 0-1
    renderLeakStatus(leakSuspected, confidence);
    updateLeakChart();
}

/**
 * Called from WebSocket GC events to add individual heap-after-GC points.
 *
 * @param {object} gcEvent - WebSocket GC event payload
 */
export function addGCEventToLeakChart(gcEvent) {
    if (!gcEvent || gcEvent.heapUsedAfter == null || gcEvent.heapUsedAfter <= 0) return;

    const ts = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    heapAfterGC.labels.push(ts);
    heapAfterGC.values.push(gcEvent.heapUsedAfter / (1024 * 1024));
    if (heapAfterGC.labels.length > MAX_HEAP_POINTS) {
        heapAfterGC.labels.shift();
        heapAfterGC.values.shift();
    }
    updateLeakChart();
}

function renderLeakGauge(ratio) {
    const arcEl = document.getElementById('leak-r2-arc');
    const valueEl = document.getElementById('leak-r2-value');

    if (!arcEl || !valueEl) return;

    // SVG circle r=44, circumference ≈ 276.5
    const circ = 2 * Math.PI * 44;
    const filled = Math.min(ratio, 1) * circ;
    arcEl.style.strokeDasharray = `${filled.toFixed(1)} ${circ.toFixed(1)}`;

    // Color: green < 0.5, amber 0.5-0.7, red > 0.7
    arcEl.className = 'leak-arc-fill ' + r2Class(ratio);
    valueEl.textContent = (ratio * 100).toFixed(0) + '%';
    valueEl.className = 'leak-r2-number ' + r2Class(ratio);
}

function renderLeakStatus(leakSuspected, confidence) {
    const statusEl = document.getElementById('leak-status-badge');
    if (!statusEl) return;

    if (leakSuspected) {
        statusEl.textContent = 'LEAK SUSPECTED';
        statusEl.className = 'leak-status-badge leak-status--danger pulsing';
    } else {
        statusEl.textContent = 'Healthy';
        statusEl.className = 'leak-status-badge leak-status--ok';
    }

    const confEl = document.getElementById('leak-confidence-text');
    if (confEl) {
        confEl.textContent = `R² confidence: ${confidence.toFixed(1)}%`;
    }
}

function initLeakChart() {
    const canvas = document.getElementById('leak-heap-chart');
    if (!canvas || typeof Chart === 'undefined') return;

    leakChart = new Chart(canvas, {
        type: 'line',
        data: {
            labels: heapAfterGC.labels,
            datasets: [{
                label: 'Heap after GC (MB)',
                data: heapAfterGC.values,
                borderColor: '#1565C0',
                backgroundColor: 'rgba(21, 101, 192, 0.08)',
                borderWidth: 1.5,
                pointRadius: 2,
                tension: 0.3,
                fill: true
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: false,
            plugins: {
                legend: { display: false }
            },
            scales: {
                x: {
                    ticks: { maxTicksLimit: 6, font: { size: 10 }, color: '#888' },
                    grid: { color: 'rgba(0,0,0,0.05)' }
                },
                y: {
                    ticks: { font: { size: 10 }, color: '#888', callback: v => v.toFixed(0) + ' MB' },
                    grid: { color: 'rgba(0,0,0,0.05)' },
                    beginAtZero: false
                }
            }
        }
    });
}

function updateLeakChart() {
    if (!leakChart) return;
    leakChart.data.labels = [...heapAfterGC.labels];
    leakChart.data.datasets[0].data = [...heapAfterGC.values];
    leakChart.update('none');
}

function r2Class(ratio) {
    if (ratio < 0.5) return 'r2-green';
    if (ratio < 0.7) return 'r2-amber';
    return 'r2-red';
}
