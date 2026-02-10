/**
 * Argus Dashboard - Main Application
 *
 * This is the main entry point that initializes all modules and coordinates
 * the dashboard functionality.
 */
import { initWebSocket } from './websocket.js';
import { initCharts, updateCharts, trackEventForCharts, updateGCCharts, updateCPUCharts, updateAllocationCharts, updateMetaspaceCharts, updateProfilingCharts, updateContentionCharts } from './charts.js';
import { initThreadView, renderThreadStateView, captureAllThreadsDump } from './threads.js';
import {
    counts,
    pinnedAlerts,
    maxPinnedAlerts,
    expandedHotspots,
    updateThreadStates,
    addToDurationBucket,
    threadStates,
    gcData,
    cpuData,
    allocationData,
    metaspaceData,
    profilingData,
    contentionData,
    correlationData
} from './state.js';
import { formatNumber, formatTimestamp, escapeHtml, formatDuration } from './utils.js';
import { initFilters, addEvent as addEventToFilter, clearEvents as clearFilterEvents } from './filter.js';

// DOM elements
const elements = {
    // Metrics
    totalEvents: document.getElementById('total-events'),
    startEvents: document.getElementById('start-events'),
    endEvents: document.getElementById('end-events'),
    activeThreads: document.getElementById('active-threads'),
    pinnedEvents: document.getElementById('pinned-events'),
    pinnedCard: document.getElementById('pinned-events').closest('.metric-card'),

    // Connection
    connectionStatus: document.getElementById('connection-status'),
    statusText: document.getElementById('connection-status').querySelector('.status-text'),

    // Events log
    eventsLog: document.getElementById('events-log'),
    clearBtn: document.getElementById('clear-events'),
    autoScrollCheckbox: document.getElementById('auto-scroll'),

    // Thread view
    threadsContainer: document.getElementById('threads-container'),
    threadCount: document.getElementById('thread-count'),
    threadDumpBtn: document.getElementById('thread-dump-btn'),

    // Thread modal
    threadModal: document.getElementById('thread-modal'),
    threadModalBackdrop: document.querySelector('#thread-modal .modal-backdrop'),
    threadModalClose: document.querySelector('#thread-modal .modal-close'),
    modalThreadName: document.getElementById('modal-thread-name'),
    modalThreadId: document.getElementById('modal-thread-id'),
    modalThreadStatus: document.getElementById('modal-thread-status'),
    modalThreadDuration: document.getElementById('modal-thread-duration'),
    modalEventCount: document.getElementById('modal-event-count'),
    modalEventsList: document.getElementById('modal-events-list'),
    modalDumpBtn: document.getElementById('modal-dump-btn'),

    // Dump modal
    dumpModal: document.getElementById('dump-modal'),
    dumpModalBackdrop: document.querySelector('#dump-modal .modal-backdrop'),
    dumpModalClose: document.querySelector('#dump-modal .modal-close'),
    dumpModalTitle: document.getElementById('dump-modal-title'),
    dumpTimestamp: document.getElementById('dump-timestamp'),
    dumpThreadCount: document.getElementById('dump-thread-count'),
    dumpCopyBtn: document.getElementById('dump-copy-btn'),
    dumpOutput: document.getElementById('dump-output'),

    // Pinned section
    pinnedSection: document.getElementById('pinned-section'),
    pinnedList: document.getElementById('pinned-list'),
    pinnedBadge: document.getElementById('pinned-badge'),

    // Hotspots
    hotspotsTotal: document.getElementById('hotspots-total'),
    hotspotsUnique: document.getElementById('hotspots-unique'),
    hotspotsList: document.getElementById('hotspots-list'),
    refreshHotspotsBtn: document.getElementById('refresh-hotspots'),

    // Export modal
    exportBtn: document.getElementById('export-btn'),
    exportModal: document.getElementById('export-modal'),
    exportModalBackdrop: document.querySelector('#export-modal .modal-backdrop'),
    exportModalClose: document.querySelector('#export-modal .modal-close'),
    exportDownloadBtn: document.getElementById('export-download-btn'),
    exportCancelBtn: document.getElementById('export-cancel-btn'),
    exportTypeStart: document.getElementById('export-type-start'),
    exportTypeEnd: document.getElementById('export-type-end'),
    exportTypePinned: document.getElementById('export-type-pinned'),
    exportTypeSubmitFailed: document.getElementById('export-type-submit-failed'),
    exportTimeFrom: document.getElementById('export-time-from'),
    exportTimeTo: document.getElementById('export-time-to'),

    // Help modal
    helpBtn: document.getElementById('help-btn'),
    helpModal: document.getElementById('help-modal'),
    helpModalBackdrop: document.querySelector('#help-modal .modal-backdrop'),
    helpModalClose: document.querySelector('#help-modal .modal-close'),

    // Tabs
    tabButtons: document.querySelectorAll('.tab-btn'),
    tabContents: document.querySelectorAll('.tab-content'),

    // Charts
    eventsRateCanvas: document.getElementById('events-rate-chart'),
    activeThreadsCanvas: document.getElementById('active-threads-chart'),
    durationCanvas: document.getElementById('duration-chart'),
    gcTimelineCanvas: document.getElementById('gc-timeline-chart'),
    heapCanvas: document.getElementById('heap-chart'),
    cpuCanvas: document.getElementById('cpu-chart'),

    // GC metrics
    gcEvents: document.getElementById('gc-events'),
    heapUsed: document.getElementById('heap-used'),
    gcTotalEvents: document.getElementById('gc-total-events'),
    gcTotalPause: document.getElementById('gc-total-pause'),
    gcAvgPause: document.getElementById('gc-avg-pause'),
    gcMaxPause: document.getElementById('gc-max-pause'),

    // CPU metrics
    jvmCpu: document.getElementById('jvm-cpu'),
    cpuJvmCurrent: document.getElementById('cpu-jvm-current'),
    cpuSystemCurrent: document.getElementById('cpu-system-current'),
    cpuPeakJvm: document.getElementById('cpu-peak-jvm'),

    // GC Overhead
    gcOverhead: document.getElementById('gc-overhead'),

    // Allocation metrics
    allocRate: document.getElementById('alloc-rate'),
    allocTotal: document.getElementById('alloc-total'),
    metaspaceUsed: document.getElementById('metaspace-used'),
    classCount: document.getElementById('class-count'),
    allocationRateCanvas: document.getElementById('allocation-rate-chart'),
    metaspaceCanvas: document.getElementById('metaspace-chart'),

    // Profiling metrics
    cpuSamples: document.getElementById('cpu-samples'),
    contentionEvents: document.getElementById('contention-events'),
    contentionTime: document.getElementById('contention-time'),
    hotMethodsCanvas: document.getElementById('hot-methods-chart'),
    contentionCanvas: document.getElementById('contention-chart'),

    // Recommendations
    recommendationsList: document.getElementById('recommendations-list'),
    refreshRecommendationsBtn: document.getElementById('refresh-recommendations'),

    // Flame graph
    flamegraphContainer: document.getElementById('flamegraph-container'),
    flamegraphSamples: document.getElementById('flamegraph-samples'),
    flamegraphTimestamp: document.getElementById('flamegraph-timestamp'),
    flamegraphPause: document.getElementById('flamegraph-pause'),
    flamegraphReset: document.getElementById('flamegraph-reset'),
    flamegraphDownload: document.getElementById('flamegraph-download')
};

const maxEvents = 500;

// Initialize modules
function init() {
    // Initialize thread view
    initThreadView(elements);

    // Initialize charts
    initCharts({
        eventsRate: elements.eventsRateCanvas,
        activeThreads: elements.activeThreadsCanvas,
        duration: elements.durationCanvas,
        gcTimeline: elements.gcTimelineCanvas,
        heap: elements.heapCanvas,
        cpu: elements.cpuCanvas,
        allocationRate: elements.allocationRateCanvas,
        metaspace: elements.metaspaceCanvas,
        hotMethods: elements.hotMethodsCanvas,
        contention: elements.contentionCanvas
    });

    // Initialize filters
    initFilters({ eventsLog: elements.eventsLog }, handleFilterChange);

    // Initialize WebSocket
    initWebSocket(
        {
            connectionStatus: elements.connectionStatus,
            statusText: elements.statusText
        },
        {
            onEvent: handleEvent,
            onStateUpdate: handleStateUpdate
        }
    );

    // Setup event listeners
    setupEventListeners();

    // Fetch feature flags (once)
    fetchConfig();

    // Initial data fetch
    fetchMetrics();
    fetchPinningAnalysis();
    fetchGCAnalysis();
    fetchCPUMetrics();
    fetchAllocationAnalysis();
    fetchMetaspaceMetrics();
    fetchMethodProfiling();
    fetchContentionAnalysis();
    fetchCorrelation();
    fetchFlameGraph();

    // Setup periodic updates
    setInterval(updateCharts, 1000);
    setInterval(fetchMetrics, 1000);
    setInterval(fetchPinningAnalysis, 5000);
    setInterval(fetchGCAnalysis, 2000);
    setInterval(fetchCPUMetrics, 1000);
    setInterval(fetchAllocationAnalysis, 2000);
    setInterval(fetchMetaspaceMetrics, 5000);
    setInterval(fetchMethodProfiling, 5000);
    setInterval(fetchContentionAnalysis, 5000);
    setInterval(fetchCorrelation, 10000);
    setInterval(fetchFlameGraph, 5000);
    setInterval(() => {
        renderThreadStateView(elements.threadsContainer, elements.threadCount);
    }, 1000);
}

function setupEventListeners() {
    // Main tab switching (top-level: Virtual Threads / JVM Overview)
    document.querySelectorAll('.main-tab-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const tabId = btn.dataset.mainTab;
            document.querySelectorAll('.main-tab-btn').forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.main-tab-content').forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            document.getElementById(`${tabId}-tab`).classList.add('active');
        });
    });

    // Sub-tab switching (Thread View / Event History)
    elements.tabButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            const tabId = btn.dataset.tab;
            elements.tabButtons.forEach(b => b.classList.remove('active'));
            elements.tabContents.forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            document.getElementById(`${tabId}-tab`).classList.add('active');
        });
    });

    // Flame graph pause/resume
    if (elements.flamegraphPause) {
        elements.flamegraphPause.addEventListener('click', () => {
            flameGraphPaused = !flameGraphPaused;
            elements.flamegraphPause.textContent = flameGraphPaused ? 'Resume' : 'Pause';
            if (flameGraphPaused && elements.flamegraphTimestamp) {
                elements.flamegraphTimestamp.textContent =
                    'Paused at ' + new Date().toLocaleTimeString();
            }
        });
    }

    // Flame graph reset (clears server data + resets chart)
    if (elements.flamegraphReset) {
        elements.flamegraphReset.addEventListener('click', async () => {
            await fetch('/flame-graph?reset=true');
            flameChart = null;
            flameGraphPaused = false;
            if (elements.flamegraphPause) {
                elements.flamegraphPause.textContent = 'Pause';
            }
            if (elements.flamegraphContainer) {
                elements.flamegraphContainer.innerHTML =
                    '<div class="flamegraph-placeholder">Collecting samples...</div>';
            }
            if (elements.flamegraphTimestamp) {
                elements.flamegraphTimestamp.textContent =
                    'Reset at ' + new Date().toLocaleTimeString();
            }
        });
    }

    // Help modal
    elements.helpBtn.addEventListener('click', () => elements.helpModal.classList.remove('hidden'));
    elements.helpModalBackdrop.addEventListener('click', () => elements.helpModal.classList.add('hidden'));
    elements.helpModalClose.addEventListener('click', () => elements.helpModal.classList.add('hidden'));

    // Keyboard shortcuts
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            elements.helpModal.classList.add('hidden');
            elements.threadModal.classList.add('hidden');
            elements.dumpModal.classList.add('hidden');
            elements.exportModal.classList.add('hidden');
        }
    });

    // Thread dump button
    elements.threadDumpBtn.addEventListener('click', captureAllThreadsDump);

    // Clear events
    elements.clearBtn.addEventListener('click', clearEvents);

    // Hotspots refresh
    elements.refreshHotspotsBtn.addEventListener('click', fetchPinningAnalysis);

    // Export modal
    elements.exportBtn.addEventListener('click', () => elements.exportModal.classList.remove('hidden'));
    elements.exportModalBackdrop.addEventListener('click', () => elements.exportModal.classList.add('hidden'));
    elements.exportModalClose.addEventListener('click', () => elements.exportModal.classList.add('hidden'));
    elements.exportCancelBtn.addEventListener('click', () => elements.exportModal.classList.add('hidden'));
    elements.exportDownloadBtn.addEventListener('click', handleExport);
}

function handleEvent(event) {
    counts.total++;
    counts[event.type] = (counts[event.type] || 0) + 1;

    // Track for charts
    trackEventForCharts(event);

    // Track duration for histogram
    if (event.type === 'END' && event.duration) {
        addToDurationBucket(event.duration / 1000000); // Convert ns to ms
    }

    // Add pinned alert
    if (event.type === 'PINNED') {
        addPinnedAlert(event);
    }

    updateCounters();

    // Add to filter storage and only display if passes filter
    const passesFilter = addEventToFilter(event);
    if (passesFilter) {
        addEventToLog(event);
    }
}

/**
 * Handle filter changes - re-render the events log with filtered events
 * @param {Array} filteredEvents - Events that pass current filters
 */
function handleFilterChange(filteredEvents) {
    // Clear current log
    elements.eventsLog.innerHTML = '';

    // Re-render filtered events
    if (filteredEvents.length === 0) {
        elements.eventsLog.innerHTML = '<div class="event-placeholder">No events match current filters</div>';
        return;
    }

    filteredEvents.forEach(event => {
        addEventToLogDirect(event);
    });

    // Scroll to bottom if auto-scroll is enabled
    if (elements.autoScrollCheckbox.checked) {
        elements.eventsLog.scrollTop = elements.eventsLog.scrollHeight;
    }
}

/**
 * Add event to log without filter check (used during re-render)
 */
function addEventToLogDirect(event) {
    const item = document.createElement('div');
    item.className = 'event-item ' + event.type.toLowerCase();

    const time = formatTimestamp(event.timestamp);
    const details = formatEventDetails(event);

    item.innerHTML = `
        <span class="event-time">${time}</span>
        <span class="event-type ${event.type.toLowerCase()}">${event.type}</span>
        <span class="event-details" title="${escapeHtml(formatEventDetailsFull(event))}">${details}</span>
    `;

    elements.eventsLog.appendChild(item);
}

function handleStateUpdate(data) {
    // Update thread states from server
    updateThreadStates(data);

    // Update active count in counters
    counts.active = data.counts.running + data.counts.pinned;
    updateCounters();

    // Re-render thread view
    renderThreadStateView(elements.threadsContainer, elements.threadCount);
}

function updateCounters() {
    elements.totalEvents.textContent = formatNumber(counts.total);
    elements.startEvents.textContent = formatNumber(counts.START);
    elements.endEvents.textContent = formatNumber(counts.END);
    elements.activeThreads.textContent = formatNumber(counts.active);
    elements.pinnedEvents.textContent = formatNumber(counts.PINNED);

    if (counts.PINNED > 0) {
        elements.pinnedCard.classList.add('has-pinned');
    }
}

async function fetchMetrics() {
    try {
        const response = await fetch('/metrics');
        if (response.ok) {
            const data = await response.json();
            counts.total = data.totalEvents || 0;
            counts.START = data.startEvents || 0;
            counts.END = data.endEvents || 0;
            counts.PINNED = data.pinnedEvents || 0;
            counts.SUBMIT_FAILED = data.submitFailedEvents || 0;
            counts.active = data.activeThreads || 0;
            updateCounters();
        }
    } catch (e) {
        console.error('[Argus] Failed to fetch metrics:', e);
    }
}

async function fetchPinningAnalysis() {
    try {
        const response = await fetch('/pinning-analysis');
        if (response.ok) {
            const data = await response.json();
            renderHotspots(data);
        }
    } catch (e) {
        console.error('[Argus] Failed to fetch pinning analysis:', e);
    }
}

async function fetchGCAnalysis() {
    try {
        const response = await fetch('/gc-analysis');
        if (response.ok) {
            const data = await response.json();
            updateGCDisplay(data);
            updateGCCharts(data);
        }
    } catch (e) {
        console.error('[Argus] Failed to fetch GC analysis:', e);
    }
}

async function fetchCPUMetrics() {
    try {
        const response = await fetch('/cpu-metrics');
        if (response.ok) {
            const data = await response.json();
            updateCPUDisplay(data);
            updateCPUCharts(data);
        }
    } catch (e) {
        console.error('[Argus] Failed to fetch CPU metrics:', e);
    }
}

function updateGCDisplay(data) {
    if (elements.gcEvents) {
        elements.gcEvents.textContent = formatNumber(data.totalGCEvents || 0);
    }
    if (elements.heapUsed && data.currentHeapUsed > 0) {
        elements.heapUsed.textContent = formatBytes(data.currentHeapUsed);
    }
    if (elements.gcTotalEvents) {
        elements.gcTotalEvents.textContent = formatNumber(data.totalGCEvents || 0);
    }
    if (elements.gcTotalPause) {
        elements.gcTotalPause.textContent = (data.totalPauseTimeMs || 0) + 'ms';
    }
    if (elements.gcAvgPause) {
        elements.gcAvgPause.textContent = (parseFloat(data.avgPauseTimeMs) || 0).toFixed(2) + 'ms';
    }
    if (elements.gcMaxPause) {
        elements.gcMaxPause.textContent = (data.maxPauseTimeMs || 0) + 'ms';
    }
    if (elements.gcOverhead) {
        const overhead = parseFloat(data.gcOverheadPercent) || 0;
        elements.gcOverhead.textContent = overhead.toFixed(1) + '%';
        // Add warning class if overhead > 10%
        const card = elements.gcOverhead.closest('.metric-card');
        if (card) {
            if (data.isOverheadWarning) {
                card.classList.add('warning');
            } else {
                card.classList.remove('warning');
            }
        }
    }
}

function updateCPUDisplay(data) {
    if (elements.jvmCpu) {
        elements.jvmCpu.textContent = (parseFloat(data.currentJvmPercent) || 0).toFixed(1) + '%';
    }
    if (elements.cpuJvmCurrent) {
        elements.cpuJvmCurrent.textContent = (parseFloat(data.currentJvmPercent) || 0).toFixed(1) + '%';
    }
    if (elements.cpuSystemCurrent) {
        elements.cpuSystemCurrent.textContent = (parseFloat(data.currentMachinePercent) || 0).toFixed(1) + '%';
    }
    if (elements.cpuPeakJvm) {
        elements.cpuPeakJvm.textContent = ((parseFloat(data.peakJvmTotal) || 0) * 100).toFixed(1) + '%';
    }
}

function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}


function renderHotspots(data) {
    elements.hotspotsTotal.textContent = formatNumber(data.totalPinnedEvents);
    elements.hotspotsUnique.textContent = data.uniqueStackTraces;

    if (data.hotspots.length === 0) {
        elements.hotspotsList.innerHTML = '<div class="empty-state">No pinning events detected yet</div>';
        return;
    }

    elements.hotspotsList.innerHTML = data.hotspots.map(hotspot => {
        const isExpanded = expandedHotspots.has(hotspot.stackTraceHash);
        return `
        <div class="hotspot-item" data-hash="${hotspot.stackTraceHash}">
            <div class="hotspot-header">
                <div class="hotspot-rank">
                    <span class="hotspot-rank-number">${hotspot.rank}</span>
                    <span class="hotspot-count"><strong>${formatNumber(hotspot.count)}</strong> events</span>
                </div>
                <span class="hotspot-percentage">${hotspot.percentage}%</span>
            </div>
            <div class="hotspot-frame">${escapeHtml(hotspot.topFrame)}</div>
            <button class="hotspot-toggle">${isExpanded ? 'Hide stack trace' : 'Show stack trace'}</button>
            <div class="hotspot-stack${isExpanded ? ' expanded' : ''}">${escapeHtml(hotspot.fullStackTrace)}</div>
        </div>
    `}).join('');

    // Add click handlers for toggle buttons
    elements.hotspotsList.querySelectorAll('.hotspot-toggle').forEach(btn => {
        btn.addEventListener('click', function() {
            const item = this.closest('.hotspot-item');
            const hash = item.dataset.hash;
            const stack = this.nextElementSibling;
            const isExpanded = stack.classList.toggle('expanded');
            this.textContent = isExpanded ? 'Hide stack trace' : 'Show stack trace';
            if (isExpanded) {
                expandedHotspots.add(hash);
            } else {
                expandedHotspots.delete(hash);
            }
        });
    });
}

function addPinnedAlert(event) {
    pinnedAlerts.unshift({
        threadId: event.threadId,
        threadName: event.threadName || `Thread-${event.threadId}`,
        timestamp: event.timestamp,
        stackTrace: event.stackTrace || 'No stack trace available',
        duration: event.duration
    });

    while (pinnedAlerts.length > maxPinnedAlerts) {
        pinnedAlerts.pop();
    }

    updatePinnedSection();
}

function updatePinnedSection() {
    if (pinnedAlerts.length === 0) {
        elements.pinnedSection.classList.add('hidden');
        return;
    }

    elements.pinnedSection.classList.remove('hidden');
    elements.pinnedBadge.textContent = pinnedAlerts.length;

    elements.pinnedList.innerHTML = pinnedAlerts.map(alert => `
        <div class="pinned-item">
            <div class="pinned-item-header">
                <span class="pinned-thread-name">${escapeHtml(alert.threadName)}</span>
                <span class="pinned-time">${formatTimestamp(alert.timestamp)}${alert.duration ? ` (${formatDuration(alert.duration)})` : ''}</span>
            </div>
            <div class="pinned-stack">${escapeHtml(alert.stackTrace)}</div>
        </div>
    `).join('');
}

function addEventToLog(event) {
    const placeholder = elements.eventsLog.querySelector('.event-placeholder');
    if (placeholder) {
        placeholder.remove();
    }

    const item = document.createElement('div');
    item.className = 'event-item ' + event.type.toLowerCase();

    const time = formatTimestamp(event.timestamp);
    const details = formatEventDetails(event);

    item.innerHTML = `
        <span class="event-time">${time}</span>
        <span class="event-type ${event.type.toLowerCase()}">${event.type}</span>
        <span class="event-details" title="${escapeHtml(formatEventDetailsFull(event))}">${details}</span>
    `;

    elements.eventsLog.appendChild(item);

    // Limit displayed events to maxEvents
    while (elements.eventsLog.children.length > maxEvents) {
        elements.eventsLog.removeChild(elements.eventsLog.firstChild);
    }

    if (elements.autoScrollCheckbox.checked) {
        elements.eventsLog.scrollTop = elements.eventsLog.scrollHeight;
    }
}

function formatEventDetails(event) {
    const parts = [];
    if (event.threadName) {
        parts.push(event.threadName);
    } else if (event.threadId) {
        parts.push(`ID: ${event.threadId}`);
    }
    if (event.carrierThread) {
        parts.push(`Carrier: ${event.carrierThread}`);
    }
    if (event.duration && event.duration > 0) {
        parts.push(formatDuration(event.duration));
    }
    return parts.join(' | ') || 'No details';
}

function formatEventDetailsFull(event) {
    const parts = [];
    if (event.threadName) parts.push(`Thread: ${event.threadName}`);
    if (event.threadId) parts.push(`ID: ${event.threadId}`);
    if (event.carrierThread) parts.push(`Carrier: ${event.carrierThread}`);
    if (event.duration && event.duration > 0) parts.push(`Duration: ${formatDuration(event.duration)}`);
    if (event.stackTrace) parts.push(`Stack: ${event.stackTrace.substring(0, 200)}...`);
    return parts.join('\n');
}

function clearEvents() {
    elements.eventsLog.innerHTML = '<div class="event-placeholder">Waiting for virtual thread events...</div>';
    pinnedAlerts.length = 0;
    updatePinnedSection();
    elements.pinnedCard.classList.remove('has-pinned');
    clearFilterEvents(); // Clear filter storage
    fetchMetrics();
}

/**
 * Handle export button click - build URL and trigger download
 */
function handleExport() {
    // Get selected format
    const formatRadios = document.querySelectorAll('input[name="export-format"]');
    let format = 'json';
    for (const radio of formatRadios) {
        if (radio.checked) {
            format = radio.value;
            break;
        }
    }

    // Get selected event types
    const types = [];
    if (elements.exportTypeStart.checked) types.push('START');
    if (elements.exportTypeEnd.checked) types.push('END');
    if (elements.exportTypePinned.checked) types.push('PINNED');
    if (elements.exportTypeSubmitFailed.checked) types.push('SUBMIT_FAILED');

    if (types.length === 0) {
        alert('Please select at least one event type');
        return;
    }

    // Build URL with query parameters
    const params = new URLSearchParams();
    params.set('format', format);
    params.set('types', types.join(','));

    // Add time range if specified
    if (elements.exportTimeFrom.value) {
        const fromDate = new Date(elements.exportTimeFrom.value);
        params.set('from', fromDate.toISOString());
    }
    if (elements.exportTimeTo.value) {
        const toDate = new Date(elements.exportTimeTo.value);
        params.set('to', toDate.toISOString());
    }

    // Trigger download
    const url = `/export?${params.toString()}`;
    window.location.href = url;

    // Close modal
    elements.exportModal.classList.add('hidden');
}

async function fetchAllocationAnalysis() {
    try {
        const response = await fetch('/allocation-analysis');
        if (response.ok) {
            const data = await response.json();
            if (!data.error) {
                updateAllocationDisplay(data);
                updateAllocationCharts(data);
            }
        }
    } catch (e) {
        // Allocation tracking might not be enabled
    }
}

async function fetchMetaspaceMetrics() {
    try {
        const response = await fetch('/metaspace-metrics');
        if (response.ok) {
            const data = await response.json();
            if (!data.error) {
                updateMetaspaceDisplay(data);
                updateMetaspaceCharts(data);
            }
        }
    } catch (e) {
        // Metaspace monitoring might not be enabled
    }
}

async function fetchMethodProfiling() {
    try {
        const response = await fetch('/method-profiling');
        if (response.ok) {
            const data = await response.json();
            if (!data.error) {
                updateProfilingDisplay(data);
                updateProfilingCharts(data);
            }
        }
    } catch (e) {
        // Method profiling might not be enabled
    }
}

async function fetchContentionAnalysis() {
    try {
        const response = await fetch('/contention-analysis');
        if (response.ok) {
            const data = await response.json();
            if (!data.error) {
                updateContentionDisplay(data);
                updateContentionCharts(data);
            }
        }
    } catch (e) {
        // Contention tracking might not be enabled
    }
}

// Flame graph state
let flameChart = null;
let flameGraphPaused = false;

async function fetchFlameGraph() {
    if (flameGraphPaused) return;
    try {
        const response = await fetch('/flame-graph');
        if (response.ok) {
            const data = await response.json();
            if (!data.error && data.value > 0) {
                renderFlameGraph(data);
                if (elements.flamegraphSamples) {
                    elements.flamegraphSamples.textContent = formatNumber(data.value);
                }
                if (elements.flamegraphTimestamp) {
                    elements.flamegraphTimestamp.textContent =
                        'Updated: ' + new Date().toLocaleTimeString();
                }
            }
        }
    } catch (e) {
        // Flame graph / profiling might not be enabled
    }
}

function renderFlameGraph(data) {
    if (!elements.flamegraphContainer || typeof flamegraph === 'undefined') return;

    const container = elements.flamegraphContainer;
    const width = container.clientWidth - 32;

    if (!flameChart) {
        // Clear placeholder
        container.innerHTML = '';

        flameChart = flamegraph()
            .width(width)
            .cellHeight(18)
            .transitionDuration(300)
            .minFrameSize(2)
            .transitionEase(d3.easeCubic)
            .sort(true)
            .title('')
            .selfValue(false);

        d3.select(container)
            .datum(data)
            .call(flameChart);
    } else {
        flameChart.width(width);
        flameChart.update(data);
    }

    // Update download link
    if (elements.flamegraphDownload) {
        elements.flamegraphDownload.href = '/flame-graph?format=collapsed';
        elements.flamegraphDownload.download = 'argus-flamegraph.collapsed';
    }
}

async function fetchCorrelation() {
    try {
        const response = await fetch('/correlation');
        if (response.ok) {
            const data = await response.json();
            if (!data.error) {
                updateRecommendations(data);
            }
        }
    } catch (e) {
        // Correlation analysis might not be enabled
    }
}

function updateAllocationDisplay(data) {
    if (elements.allocRate) {
        elements.allocRate.textContent = (parseFloat(data.allocationRateMBPerSec) || 0).toFixed(1) + ' MB/s';
    }
    if (elements.allocTotal) {
        elements.allocTotal.textContent = (parseFloat(data.totalAllocatedMB) || 0).toFixed(1) + ' MB';
    }
}

function updateMetaspaceDisplay(data) {
    if (elements.metaspaceUsed) {
        elements.metaspaceUsed.textContent = (parseFloat(data.currentUsedMB) || 0).toFixed(1) + ' MB';
    }
    if (elements.classCount) {
        elements.classCount.textContent = formatNumber(data.currentClassCount || 0);
    }
}

function updateProfilingDisplay(data) {
    if (elements.cpuSamples) {
        elements.cpuSamples.textContent = formatNumber(data.totalSamples || 0);
    }
}

function updateContentionDisplay(data) {
    if (elements.contentionEvents) {
        elements.contentionEvents.textContent = formatNumber(data.totalContentionEvents || 0);
    }
    if (elements.contentionTime) {
        elements.contentionTime.textContent = (data.totalContentionTimeMs || 0) + 'ms';
    }
}

function updateRecommendations(data) {
    if (!elements.recommendationsList) return;

    const recommendations = data.recommendations || [];

    if (recommendations.length === 0) {
        elements.recommendationsList.innerHTML = '<div class="empty-state">No recommendations at this time</div>';
        return;
    }

    elements.recommendationsList.innerHTML = recommendations.map(rec => {
        const severityClass = rec.severity.toLowerCase();
        return `
            <div class="recommendation-item ${severityClass}">
                <div class="recommendation-header">
                    <span class="recommendation-type">${rec.type.replace(/_/g, ' ')}</span>
                    <span class="recommendation-severity ${severityClass}">${rec.severity}</span>
                </div>
                <div class="recommendation-title">${escapeHtml(rec.title)}</div>
                <div class="recommendation-description">${escapeHtml(rec.description)}</div>
            </div>
        `;
    }).join('');
}

async function fetchConfig() {
    try {
        const response = await fetch('/config');
        if (response.ok) {
            const data = await response.json();
            renderFeatureFlags(data.features);
        }
    } catch (e) {
        console.error('[Argus] Failed to fetch config:', e);
    }
}

function renderFeatureFlags(features) {
    const container = document.getElementById('feature-flags-list');
    if (!container || !features) return;

    const featureNames = {
        gc: 'GC',
        cpu: 'CPU',
        metaspace: 'Metaspace',
        allocation: 'Allocation',
        profiling: 'Profiling',
        contention: 'Contention',
        correlation: 'Correlation',
        prometheus: 'Prometheus'
    };

    container.innerHTML = Object.entries(features).map(([key, info]) => {
        const name = featureNames[key] || key;
        const enabledClass = info.enabled ? 'enabled' : 'disabled';
        const overheadTag = `<span class="overhead-tag ${info.overhead}">${info.overhead}</span>`;
        return `<span class="feature-badge ${enabledClass}" title="${name}: ${info.enabled ? 'Enabled' : 'Disabled'} (${info.overhead} overhead)">${name} ${overheadTag}</span>`;
    }).join('');
}

// Start the application
init();
