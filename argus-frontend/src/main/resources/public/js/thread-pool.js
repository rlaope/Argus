/**
 * Thread Pool Visualization (#139)
 * Donut chart for JVM thread states + deadlock banner + virtual thread stats.
 */

let threadDonut = null;

const STATE_COLORS = {
    RUNNABLE:      '#2E7D32',
    WAITING:       '#1565C0',
    TIMED_WAITING: '#00838F',
    BLOCKED:       '#C62828',
};

/**
 * Initialize the thread pool section.
 * Call once after DOM is ready.
 */
export function initThreadPool() {
    const canvas = document.getElementById('thread-pool-donut');
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    threadDonut = new Chart(ctx, {
        type: 'doughnut',
        data: {
            labels: ['RUNNABLE', 'WAITING', 'TIMED_WAITING', 'BLOCKED'],
            datasets: [{
                data: [0, 0, 0, 0],
                backgroundColor: [
                    STATE_COLORS.RUNNABLE,
                    STATE_COLORS.WAITING,
                    STATE_COLORS.TIMED_WAITING,
                    STATE_COLORS.BLOCKED,
                ],
                borderWidth: 1,
                borderColor: '#fff',
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            cutout: '68%',
            plugins: {
                legend: {
                    position: 'right',
                    labels: {
                        font: { size: 11, family: "'SF Mono', monospace" },
                        color: '#444',
                        boxWidth: 12,
                        padding: 10,
                    }
                },
                tooltip: {
                    callbacks: {
                        label(ctx) {
                            const total = ctx.dataset.data.reduce((a, b) => a + b, 0);
                            const pct = total > 0 ? ((ctx.parsed / total) * 100).toFixed(1) : '0.0';
                            return ` ${ctx.label}: ${ctx.parsed} (${pct}%)`;
                        }
                    }
                }
            }
        }
    });
}

/**
 * Update the thread pool section with fresh data.
 * @param {Object} data  prometheus-parsed object or JVM thread info
 *   Expected keys (all optional, fall back to 0):
 *     runnable, waiting, timedWaiting, blocked, deadlocked,
 *     virtualActive, virtualPinned
 */
export function updateThreadPool(data) {
    if (!data) return;

    const runnable     = data.runnable     || 0;
    const waiting      = data.waiting      || 0;
    const timedWaiting = data.timedWaiting || 0;
    const blocked      = data.blocked      || 0;
    const deadlocked   = data.deadlocked   || 0;
    const virtualActive = data.virtualActive || 0;
    const virtualPinned = data.virtualPinned || 0;

    // Update donut chart
    if (threadDonut) {
        threadDonut.data.datasets[0].data = [runnable, waiting, timedWaiting, blocked];
        threadDonut.update('none');
    }

    // Deadlock banner
    const banner = document.getElementById('thread-pool-deadlock-banner');
    if (banner) {
        banner.hidden = deadlocked === 0;
        const countEl = banner.querySelector('.deadlock-count');
        if (countEl) countEl.textContent = deadlocked;
    }

    // VT stats
    _setText('thread-pool-vt-active', virtualActive);
    _setText('thread-pool-vt-pinned', virtualPinned);

    const total = runnable + waiting + timedWaiting + blocked;
    _setText('thread-pool-total', total);
    _setText('thread-pool-runnable', runnable);
    _setText('thread-pool-blocked', blocked);

    // Pinning rate
    const pinRate = total > 0 ? ((virtualPinned / total) * 100).toFixed(1) + '%' : '—';
    _setText('thread-pool-pin-rate', pinRate);
}

function _setText(id, value) {
    const el = document.getElementById(id);
    if (el) el.textContent = value;
}
