/**
 * Interactive GC Pause Timeline
 * Scatter plot showing GC events by type with pause duration on Y axis.
 * Data comes from /gc-analysis recentGCs array.
 */

// Per-type dataset indices
const DS_YOUNG = 0;
const DS_MIXED = 1;
const DS_FULL  = 2;

let gcTimelineScatter = null;

// Start time anchor for relative X axis (seconds)
let startTimestamp = null;

const gridColor  = '#e0e0e0';
const textColor  = '#757575';

/**
 * Classify a GC event into Young / Mixed / Full based on name and cause.
 * Returns 0 (Young), 1 (Mixed), or 2 (Full).
 */
function classifyGC(gc) {
    const name  = (gc.gcName  || '').toLowerCase();
    const cause = (gc.cause   || gc.gcCause || '').toLowerCase();
    if (name.includes('full') || cause.includes('system.gc') || cause.includes('ergonomics') && name.includes('major')) {
        return DS_FULL;
    }
    if (name.includes('mixed') || cause.includes('g1 mixed')) {
        return DS_MIXED;
    }
    return DS_YOUNG;
}

/**
 * Compute dot radius proportional to memory reclaimed.
 * Min radius 4, max 14.
 */
function radiusForReclaimed(gc) {
    const before = gc.heapUsedBefore || 0;
    const after  = gc.heapUsedAfter  || 0;
    const reclaimed = Math.max(0, before - after);
    if (reclaimed <= 0) return 4;
    // Scale: 1 MB → 4, 512 MB → 14
    const mb = reclaimed / (1024 * 1024);
    return Math.min(14, 4 + Math.sqrt(mb / 512) * 10);
}

function formatBytes(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(Math.max(1, bytes)) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

/**
 * Build scatter point from a GC record.
 */
function buildPoint(gc) {
    const ts = new Date(gc.timestamp).getTime();
    if (startTimestamp === null) startTimestamp = ts;
    return {
        x: (ts - startTimestamp) / 1000,
        y: parseFloat(gc.pauseTimeMs) || 0,
        cause: gc.cause || gc.gcCause || '—',
        heapBefore: formatBytes(gc.heapUsedBefore),
        heapAfter:  formatBytes(gc.heapUsedAfter),
        reclaimed:  formatBytes(Math.max(0, (gc.heapUsedBefore || 0) - (gc.heapUsedAfter || 0))),
        r: radiusForReclaimed(gc)
    };
}

/**
 * Initialize the GC scatter timeline chart on the given canvas element.
 */
export function initGCTimeline(canvas) {
    if (!canvas) return;

    gcTimelineScatter = new Chart(canvas, {
        type: 'scatter',
        data: {
            datasets: [
                {
                    label: 'Young GC',
                    data: [],
                    backgroundColor: 'rgba(54, 162, 235, 0.65)',
                    borderColor: 'rgba(54, 162, 235, 0.9)',
                    borderWidth: 1,
                    pointRadius: 5,
                    pointHoverRadius: 7
                },
                {
                    label: 'Mixed GC',
                    data: [],
                    backgroundColor: 'rgba(249, 168, 37, 0.75)',
                    borderColor: 'rgba(249, 168, 37, 1)',
                    borderWidth: 1,
                    pointRadius: 6,
                    pointHoverRadius: 8
                },
                {
                    label: 'Full GC',
                    data: [],
                    backgroundColor: 'rgba(198, 40, 40, 0.8)',
                    borderColor: 'rgba(198, 40, 40, 1)',
                    borderWidth: 1,
                    pointRadius: 8,
                    pointHoverRadius: 10
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: { duration: 0 },
            interaction: { mode: 'nearest', intersect: true },
            plugins: {
                legend: {
                    position: 'top',
                    labels: { color: textColor, boxWidth: 12, padding: 10, font: { size: 10 } }
                },
                tooltip: {
                    callbacks: {
                        label(ctx) {
                            const d = ctx.raw;
                            return [
                                `Cause: ${d.cause}`,
                                `Pause: ${d.y.toFixed(2)}ms`,
                                `Heap: ${d.heapBefore} → ${d.heapAfter}`,
                                `Reclaimed: ${d.reclaimed}`
                            ];
                        }
                    }
                }
            },
            scales: {
                x: {
                    type: 'linear',
                    title: {
                        display: true,
                        text: 'Time (s)',
                        color: textColor,
                        font: { size: 10 }
                    },
                    grid: { color: gridColor },
                    ticks: { color: textColor, font: { size: 10 } }
                },
                y: {
                    beginAtZero: true,
                    title: {
                        display: true,
                        text: 'Pause (ms)',
                        color: textColor,
                        font: { size: 10 }
                    },
                    grid: { color: gridColor },
                    ticks: { color: textColor, font: { size: 10 } }
                }
            }
        }
    });
}

/**
 * Update the scatter chart from a /gc-analysis response.
 * Called whenever GC analysis data is refreshed.
 */
export function updateGCTimeline(data) {
    if (!gcTimelineScatter || !data.recentGCs || data.recentGCs.length === 0) return;

    // Reset start anchor so X axis is relative to oldest event in the current batch
    startTimestamp = null;

    const youngPts = [];
    const mixedPts = [];
    const fullPts  = [];

    data.recentGCs.forEach(gc => {
        const pt  = buildPoint(gc);
        const idx = classifyGC(gc);
        if (idx === DS_YOUNG) youngPts.push(pt);
        else if (idx === DS_MIXED) mixedPts.push(pt);
        else fullPts.push(pt);
    });

    // Apply per-point radius via pointRadius array
    gcTimelineScatter.data.datasets[DS_YOUNG].data = youngPts;
    gcTimelineScatter.data.datasets[DS_MIXED].data = mixedPts;
    gcTimelineScatter.data.datasets[DS_FULL].data  = fullPts;

    gcTimelineScatter.data.datasets[DS_YOUNG].pointRadius = youngPts.map(p => p.r);
    gcTimelineScatter.data.datasets[DS_MIXED].pointRadius = mixedPts.map(p => p.r);
    gcTimelineScatter.data.datasets[DS_FULL].pointRadius  = fullPts.map(p => p.r);

    gcTimelineScatter.update('none');
}
