/**
 * Argus Dashboard - Main Application
 *
 * This is the main entry point that initializes all modules and coordinates
 * the dashboard functionality.
 */
import { initWebSocket } from './websocket.js';
import { initCharts, updateCharts, trackEventForCharts } from './charts.js';
import { initThreadView, renderThreadStateView, captureAllThreadsDump } from './threads.js';
import {
    counts,
    pinnedAlerts,
    maxPinnedAlerts,
    expandedHotspots,
    updateThreadStates,
    addToDurationBucket,
    threadStates
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
    durationCanvas: document.getElementById('duration-chart')
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
        duration: elements.durationCanvas
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

    // Initial data fetch
    fetchMetrics();
    fetchPinningAnalysis();

    // Setup periodic updates
    setInterval(updateCharts, 1000);
    setInterval(fetchMetrics, 1000);
    setInterval(fetchPinningAnalysis, 5000);
    setInterval(() => {
        renderThreadStateView(elements.threadsContainer, elements.threadCount);
    }, 1000);
}

function setupEventListeners() {
    // Tab switching
    elements.tabButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            const tabId = btn.dataset.tab;
            elements.tabButtons.forEach(b => b.classList.remove('active'));
            elements.tabContents.forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            document.getElementById(`${tabId}-tab`).classList.add('active');
        });
    });

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

// Start the application
init();
