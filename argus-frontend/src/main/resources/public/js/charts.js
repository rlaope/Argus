/**
 * Chart management for Argus Dashboard
 */
import {
    eventsRateData,
    activeThreadsData,
    durationBuckets,
    chartDataPoints,
    currentSecondEvents,
    lastSecondTimestamp,
    resetCurrentSecondEvents,
    updateLastSecondTimestamp,
    stateCounts,
    gcData,
    cpuData
} from './state.js';

let eventsRateChart = null;
let activeThreadsChart = null;
let durationChart = null;
let gcTimelineChart = null;
let heapChart = null;
let cpuChart = null;

const gridColor = 'rgba(48, 54, 61, 0.8)';
const textColor = '#8b949e';

/**
 * Initialize all charts
 */
export function initCharts(canvases) {
    // Events Rate Chart (Line)
    eventsRateChart = new Chart(canvases.eventsRate, {
        type: 'line',
        data: {
            labels: eventsRateData.labels,
            datasets: [
                {
                    label: 'START',
                    data: eventsRateData.start,
                    borderColor: '#3fb950',
                    backgroundColor: 'rgba(63, 185, 80, 0.1)',
                    fill: true,
                    tension: 0.3,
                    pointRadius: 0
                },
                {
                    label: 'END',
                    data: eventsRateData.end,
                    borderColor: '#58a6ff',
                    backgroundColor: 'rgba(88, 166, 255, 0.1)',
                    fill: true,
                    tension: 0.3,
                    pointRadius: 0
                },
                {
                    label: 'PINNED',
                    data: eventsRateData.pinned,
                    borderColor: '#f85149',
                    backgroundColor: 'rgba(248, 81, 73, 0.1)',
                    fill: true,
                    tension: 0.3,
                    pointRadius: 0
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: { duration: 0 },
            plugins: {
                legend: {
                    position: 'top',
                    labels: { color: textColor, boxWidth: 12, padding: 8, font: { size: 10 } }
                }
            },
            scales: {
                x: { display: false },
                y: {
                    beginAtZero: true,
                    grid: { color: gridColor },
                    ticks: { color: textColor, font: { size: 10 } }
                }
            }
        }
    });

    // Active Threads Chart (Line)
    activeThreadsChart = new Chart(canvases.activeThreads, {
        type: 'line',
        data: {
            labels: activeThreadsData.labels,
            datasets: [{
                label: 'Active Threads',
                data: activeThreadsData.values,
                borderColor: '#a371f7',
                backgroundColor: 'rgba(163, 113, 247, 0.2)',
                fill: true,
                tension: 0.3,
                pointRadius: 0
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: { duration: 0 },
            plugins: { legend: { display: false } },
            scales: {
                x: { display: false },
                y: {
                    beginAtZero: true,
                    grid: { color: gridColor },
                    ticks: { color: textColor, font: { size: 10 } }
                }
            }
        }
    });

    // Duration Distribution Chart (Bar)
    durationChart = new Chart(canvases.duration, {
        type: 'bar',
        data: {
            labels: durationBuckets.labels,
            datasets: [{
                label: 'Threads',
                data: durationBuckets.values,
                backgroundColor: [
                    'rgba(63, 185, 80, 0.7)',
                    'rgba(88, 166, 255, 0.7)',
                    'rgba(163, 113, 247, 0.7)',
                    'rgba(210, 153, 34, 0.7)',
                    'rgba(248, 81, 73, 0.7)',
                    'rgba(248, 81, 73, 0.8)',
                    'rgba(248, 81, 73, 0.9)'
                ],
                borderWidth: 0
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: { duration: 0 },
            plugins: { legend: { display: false } },
            scales: {
                x: {
                    grid: { display: false },
                    ticks: { color: textColor, font: { size: 9 } }
                },
                y: {
                    beginAtZero: true,
                    grid: { color: gridColor },
                    ticks: { color: textColor, font: { size: 10 } }
                }
            }
        }
    });

    // GC Timeline Chart (Bar)
    if (canvases.gcTimeline) {
        gcTimelineChart = new Chart(canvases.gcTimeline, {
            type: 'bar',
            data: {
                labels: gcData.timeline.labels,
                datasets: [{
                    label: 'GC Pause (ms)',
                    data: gcData.timeline.pauseTimes,
                    backgroundColor: 'rgba(163, 113, 247, 0.7)',
                    borderColor: '#a371f7',
                    borderWidth: 1
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: { duration: 0 },
                plugins: { legend: { display: false } },
                scales: {
                    x: { display: false },
                    y: {
                        beginAtZero: true,
                        grid: { color: gridColor },
                        ticks: { color: textColor, font: { size: 10 } }
                    }
                }
            }
        });
    }

    // Heap Usage Chart (Line)
    if (canvases.heap) {
        heapChart = new Chart(canvases.heap, {
            type: 'line',
            data: {
                labels: gcData.heapHistory.labels,
                datasets: [
                    {
                        label: 'Used',
                        data: gcData.heapHistory.used,
                        borderColor: '#a371f7',
                        backgroundColor: 'rgba(163, 113, 247, 0.2)',
                        fill: true,
                        tension: 0.3,
                        pointRadius: 0
                    },
                    {
                        label: 'Committed',
                        data: gcData.heapHistory.committed,
                        borderColor: '#8b949e',
                        backgroundColor: 'rgba(139, 148, 158, 0.1)',
                        fill: true,
                        tension: 0.3,
                        pointRadius: 0,
                        borderDash: [5, 5]
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: { duration: 0 },
                plugins: {
                    legend: {
                        position: 'top',
                        labels: { color: textColor, boxWidth: 12, padding: 8, font: { size: 10 } }
                    }
                },
                scales: {
                    x: { display: false },
                    y: {
                        beginAtZero: true,
                        grid: { color: gridColor },
                        ticks: {
                            color: textColor,
                            font: { size: 10 },
                            callback: function(value) {
                                return formatBytes(value);
                            }
                        }
                    }
                }
            }
        });
    }

    // CPU Load Chart (Line)
    if (canvases.cpu) {
        cpuChart = new Chart(canvases.cpu, {
            type: 'line',
            data: {
                labels: cpuData.history.labels,
                datasets: [
                    {
                        label: 'JVM CPU',
                        data: cpuData.history.jvm,
                        borderColor: '#58a6ff',
                        backgroundColor: 'rgba(88, 166, 255, 0.2)',
                        fill: true,
                        tension: 0.3,
                        pointRadius: 0
                    },
                    {
                        label: 'System CPU',
                        data: cpuData.history.machine,
                        borderColor: '#f85149',
                        backgroundColor: 'rgba(248, 81, 73, 0.1)',
                        fill: true,
                        tension: 0.3,
                        pointRadius: 0
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: { duration: 0 },
                plugins: {
                    legend: {
                        position: 'top',
                        labels: { color: textColor, boxWidth: 12, padding: 8, font: { size: 10 } }
                    }
                },
                scales: {
                    x: { display: false },
                    y: {
                        beginAtZero: true,
                        max: 100,
                        grid: { color: gridColor },
                        ticks: {
                            color: textColor,
                            font: { size: 10 },
                            callback: function(value) {
                                return value + '%';
                            }
                        }
                    }
                }
            }
        });
    }
}

/**
 * Format bytes to human readable string
 */
function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

/**
 * Update charts with latest data
 */
export function updateCharts() {
    const now = Math.floor(Date.now() / 1000);
    const timeLabel = new Date().toLocaleTimeString('en-US', {
        hour12: false,
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
    });

    // If we've moved to a new second, push the current counts
    if (now > lastSecondTimestamp) {
        eventsRateData.labels.push(timeLabel);
        eventsRateData.start.push(currentSecondEvents.start);
        eventsRateData.end.push(currentSecondEvents.end);
        eventsRateData.pinned.push(currentSecondEvents.pinned);

        activeThreadsData.labels.push(timeLabel);
        // Use running count from state (real-time mirroring)
        activeThreadsData.values.push(stateCounts.running + stateCounts.pinned);

        // Keep only last N data points
        while (eventsRateData.labels.length > chartDataPoints) {
            eventsRateData.labels.shift();
            eventsRateData.start.shift();
            eventsRateData.end.shift();
            eventsRateData.pinned.shift();
        }
        while (activeThreadsData.labels.length > chartDataPoints) {
            activeThreadsData.labels.shift();
            activeThreadsData.values.shift();
        }

        resetCurrentSecondEvents();
        updateLastSecondTimestamp(now);
    }

    // Update chart displays
    if (eventsRateChart) eventsRateChart.update('none');
    if (activeThreadsChart) activeThreadsChart.update('none');
    if (durationChart) durationChart.update('none');
    if (gcTimelineChart) gcTimelineChart.update('none');
    if (heapChart) heapChart.update('none');
    if (cpuChart) cpuChart.update('none');
}

/**
 * Update GC chart data from server response
 */
export function updateGCCharts(data) {
    // Update GC state
    gcData.totalEvents = data.totalGCEvents || 0;
    gcData.totalPauseMs = data.totalPauseTimeMs || 0;
    gcData.avgPauseMs = parseFloat(data.avgPauseTimeMs) || 0;
    gcData.maxPauseMs = data.maxPauseTimeMs || 0;
    gcData.currentHeapUsed = data.currentHeapUsed || 0;
    gcData.currentHeapCommitted = data.currentHeapCommitted || 0;

    // Update timeline from recent GCs
    if (data.recentGCs && data.recentGCs.length > 0) {
        gcData.timeline.labels = [];
        gcData.timeline.pauseTimes = [];
        gcData.heapHistory.labels = [];
        gcData.heapHistory.used = [];
        gcData.heapHistory.committed = [];

        data.recentGCs.slice(-30).forEach(gc => {
            const time = new Date(gc.timestamp).toLocaleTimeString('en-US', {
                hour12: false,
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit'
            });
            gcData.timeline.labels.push(time);
            gcData.timeline.pauseTimes.push(parseFloat(gc.pauseTimeMs) || 0);

            if (gc.heapUsedAfter > 0) {
                gcData.heapHistory.labels.push(time);
                gcData.heapHistory.used.push(gc.heapUsedAfter);
                gcData.heapHistory.committed.push(data.currentHeapCommitted);
            }
        });
    }

    if (gcTimelineChart) gcTimelineChart.update('none');
    if (heapChart) heapChart.update('none');
}

/**
 * Update CPU chart data from server response
 */
export function updateCPUCharts(data) {
    // Update CPU state
    cpuData.currentJvmPercent = parseFloat(data.currentJvmPercent) || 0;
    cpuData.currentMachinePercent = parseFloat(data.currentMachinePercent) || 0;
    cpuData.peakJvmPercent = (parseFloat(data.peakJvmTotal) || 0) * 100;

    // Update history from server
    if (data.history && data.history.length > 0) {
        cpuData.history.labels = [];
        cpuData.history.jvm = [];
        cpuData.history.machine = [];

        data.history.forEach(snapshot => {
            const time = new Date(snapshot.timestamp).toLocaleTimeString('en-US', {
                hour12: false,
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit'
            });
            cpuData.history.labels.push(time);
            cpuData.history.jvm.push(parseFloat(snapshot.jvmPercent) || 0);
            cpuData.history.machine.push(parseFloat(snapshot.machinePercent) || 0);
        });
    }

    if (cpuChart) cpuChart.update('none');
}

/**
 * Track event for chart data
 */
export function trackEventForCharts(event) {
    if (event.type === 'START') {
        currentSecondEvents.start++;
    } else if (event.type === 'END') {
        currentSecondEvents.end++;
    } else if (event.type === 'PINNED') {
        currentSecondEvents.pinned++;
    }
}
