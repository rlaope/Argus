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
    stateCounts
} from './state.js';

let eventsRateChart = null;
let activeThreadsChart = null;
let durationChart = null;

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
