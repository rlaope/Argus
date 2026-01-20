(function() {
    'use strict';

    // DOM elements
    const connectionStatus = document.getElementById('connection-status');
    const statusText = connectionStatus.querySelector('.status-text');
    const totalEventsEl = document.getElementById('total-events');
    const startEventsEl = document.getElementById('start-events');
    const endEventsEl = document.getElementById('end-events');
    const activeThreadsEl = document.getElementById('active-threads');
    const pinnedEventsEl = document.getElementById('pinned-events');
    const eventsLog = document.getElementById('events-log');
    const clearBtn = document.getElementById('clear-events');
    const autoScrollCheckbox = document.getElementById('auto-scroll');
    const pinnedCard = pinnedEventsEl.closest('.metric-card');
    const pinnedSection = document.getElementById('pinned-section');
    const pinnedList = document.getElementById('pinned-list');
    const pinnedBadge = document.getElementById('pinned-badge');
    const threadsContainer = document.getElementById('threads-container');
    const threadCountEl = document.getElementById('thread-count');
    const helpBtn = document.getElementById('help-btn');
    const helpModal = document.getElementById('help-modal');
    const modalBackdrop = helpModal.querySelector('.modal-backdrop');
    const modalClose = helpModal.querySelector('.modal-close');
    const tabButtons = document.querySelectorAll('.tab-btn');
    const tabContents = document.querySelectorAll('.tab-content');

    // Thread detail modal elements
    const threadModal = document.getElementById('thread-modal');
    const threadModalBackdrop = threadModal.querySelector('.modal-backdrop');
    const threadModalClose = threadModal.querySelector('.modal-close');
    const modalThreadName = document.getElementById('modal-thread-name');
    const modalThreadId = document.getElementById('modal-thread-id');
    const modalThreadStatus = document.getElementById('modal-thread-status');
    const modalThreadDuration = document.getElementById('modal-thread-duration');
    const modalEventCount = document.getElementById('modal-event-count');
    const modalEventsList = document.getElementById('modal-events-list');
    const modalDumpBtn = document.getElementById('modal-dump-btn');

    // Dump modal elements
    const dumpModal = document.getElementById('dump-modal');
    const dumpModalBackdrop = dumpModal.querySelector('.modal-backdrop');
    const dumpModalClose = dumpModal.querySelector('.modal-close');
    const dumpModalTitle = document.getElementById('dump-modal-title');
    const dumpTimestamp = document.getElementById('dump-timestamp');
    const dumpThreadCount = document.getElementById('dump-thread-count');
    const dumpCopyBtn = document.getElementById('dump-copy-btn');
    const dumpOutput = document.getElementById('dump-output');
    const threadDumpBtn = document.getElementById('thread-dump-btn');

    // Current state for dump
    let currentDumpText = '';

    // Chart elements
    const eventsRateCanvas = document.getElementById('events-rate-chart');
    const activeThreadsCanvas = document.getElementById('active-threads-chart');
    const durationCanvas = document.getElementById('duration-chart');

    // Chart instances
    let eventsRateChart = null;
    let activeThreadsChart = null;
    let durationChart = null;

    // Chart data
    const chartDataPoints = 60; // 60 seconds of data
    const eventsRateData = {
        labels: [],
        start: [],
        end: [],
        pinned: []
    };
    const activeThreadsData = {
        labels: [],
        values: []
    };
    const durationBuckets = {
        labels: ['<10ms', '10-50ms', '50-100ms', '100-500ms', '500ms-1s', '1-5s', '>5s'],
        values: [0, 0, 0, 0, 0, 0, 0]
    };

    // Per-second event counters
    let currentSecondEvents = { start: 0, end: 0, pinned: 0 };
    let lastSecondTimestamp = Math.floor(Date.now() / 1000);

    // State
    let ws = null;
    let reconnectAttempts = 0;
    const maxReconnectAttempts = 10;
    const reconnectDelay = 2000;
    const maxEvents = 500;
    const maxPinnedAlerts = 50;

    const counts = {
        total: 0,
        START: 0,
        END: 0,
        PINNED: 0,
        SUBMIT_FAILED: 0,
        active: 0
    };

    // Active threads map: threadId -> { threadName, carrierThread, startTime, isPinned, endTime, status }
    const activeThreads = new Map();
    const pinnedAlerts = [];
    const threadRetentionMs = 3000; // Keep ended threads visible for 3 seconds

    // Tab switching
    tabButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            const tabId = btn.dataset.tab;
            tabButtons.forEach(b => b.classList.remove('active'));
            tabContents.forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            document.getElementById(`${tabId}-tab`).classList.add('active');
        });
    });

    // Modal handling
    helpBtn.addEventListener('click', () => {
        helpModal.classList.remove('hidden');
    });

    modalBackdrop.addEventListener('click', () => {
        helpModal.classList.add('hidden');
    });

    modalClose.addEventListener('click', () => {
        helpModal.classList.add('hidden');
    });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            if (!helpModal.classList.contains('hidden')) {
                helpModal.classList.add('hidden');
            }
            if (!threadModal.classList.contains('hidden')) {
                threadModal.classList.add('hidden');
            }
            if (!dumpModal.classList.contains('hidden')) {
                dumpModal.classList.add('hidden');
            }
        }
    });

    // Thread modal handlers
    threadModalBackdrop.addEventListener('click', () => {
        threadModal.classList.add('hidden');
    });

    threadModalClose.addEventListener('click', () => {
        threadModal.classList.add('hidden');
    });

    // Dump modal handlers
    dumpModalBackdrop.addEventListener('click', () => {
        dumpModal.classList.add('hidden');
    });

    dumpModalClose.addEventListener('click', () => {
        dumpModal.classList.add('hidden');
    });

    // Thread dump button (all threads)
    threadDumpBtn.addEventListener('click', () => {
        captureAllThreadsDump();
    });

    // Single thread dump button (in thread detail modal)
    modalDumpBtn.addEventListener('click', () => {
        const threadId = modalThreadId.textContent;
        if (threadId && threadId !== '-') {
            captureSingleThreadDump(parseInt(threadId));
        }
    });

    // Copy to clipboard
    dumpCopyBtn.addEventListener('click', () => {
        if (currentDumpText) {
            navigator.clipboard.writeText(currentDumpText).then(() => {
                const originalText = dumpCopyBtn.textContent;
                dumpCopyBtn.textContent = 'Copied!';
                setTimeout(() => {
                    dumpCopyBtn.textContent = originalText;
                }, 2000);
            }).catch(err => {
                console.error('[Argus] Failed to copy:', err);
            });
        }
    });

    function connect() {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/events`;

        ws = new WebSocket(wsUrl);

        ws.onopen = function() {
            console.log('[Argus] WebSocket connected');
            reconnectAttempts = 0;
            setConnected(true);
        };

        ws.onclose = function() {
            console.log('[Argus] WebSocket disconnected');
            setConnected(false);
            scheduleReconnect();
        };

        ws.onerror = function(error) {
            console.error('[Argus] WebSocket error:', error);
        };

        ws.onmessage = function(event) {
            try {
                const data = JSON.parse(event.data);
                handleEvent(data);
            } catch (e) {
                console.error('[Argus] Failed to parse event:', e);
            }
        };
    }

    function setConnected(connected) {
        if (connected) {
            connectionStatus.classList.remove('disconnected');
            connectionStatus.classList.add('connected');
            statusText.textContent = 'Connected';
        } else {
            connectionStatus.classList.remove('connected');
            connectionStatus.classList.add('disconnected');
            statusText.textContent = 'Disconnected';
        }
    }

    function scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            console.log('[Argus] Max reconnect attempts reached');
            statusText.textContent = 'Connection Failed';
            return;
        }

        reconnectAttempts++;
        statusText.textContent = `Reconnecting (${reconnectAttempts}/${maxReconnectAttempts})...`;

        setTimeout(connect, reconnectDelay);
    }

    function handleEvent(event) {
        counts.total++;
        counts[event.type] = (counts[event.type] || 0) + 1;

        // Track for charts
        trackEventForCharts(event);

        // Track active threads
        if (event.type === 'START') {
            activeThreads.set(event.threadId, {
                threadName: event.threadName || `Thread-${event.threadId}`,
                carrierThread: event.carrierThread,
                startTime: new Date(event.timestamp),
                isPinned: false,
                status: 'running',
                endTime: null
            });
        } else if (event.type === 'END') {
            const thread = activeThreads.get(event.threadId);
            if (thread) {
                thread.status = 'ended';
                thread.endTime = Date.now();
            }
        } else if (event.type === 'PINNED') {
            // Mark thread as pinned if it exists
            const thread = activeThreads.get(event.threadId);
            if (thread) {
                thread.isPinned = true;
            }
            // Add to pinned alerts
            addPinnedAlert(event);
        }

        updateCounters();
        updateThreadsView();
        addEventToLog(event);
    }

    function updateCounters() {
        totalEventsEl.textContent = formatNumber(counts.total);
        startEventsEl.textContent = formatNumber(counts.START);
        endEventsEl.textContent = formatNumber(counts.END);
        activeThreadsEl.textContent = formatNumber(counts.active);
        pinnedEventsEl.textContent = formatNumber(counts.PINNED);

        if (counts.PINNED > 0) {
            pinnedCard.classList.add('has-pinned');
        }
    }

    // Fetch metrics from HTTP endpoint to sync with server state
    async function fetchMetrics() {
        try {
            const response = await fetch('/metrics');
            if (response.ok) {
                const data = await response.json();
                // Update counts from server metrics
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

    // Fetch active threads from server to restore state
    async function fetchActiveThreads() {
        try {
            const response = await fetch('/active-threads');
            if (response.ok) {
                const threads = await response.json();
                const serverThreadIds = new Set(threads.map(t => t.threadId));

                // Add new threads from server
                threads.forEach(thread => {
                    const existing = activeThreads.get(thread.threadId);
                    if (!existing) {
                        activeThreads.set(thread.threadId, {
                            threadName: thread.threadName || `Thread-${thread.threadId}`,
                            carrierThread: thread.carrierThread,
                            startTime: new Date(thread.timestamp),
                            isPinned: false,
                            status: 'running',
                            endTime: null
                        });
                    } else if (existing.status === 'ended') {
                        // Server says it's still running, update status
                        existing.status = 'running';
                        existing.endTime = null;
                    }
                });

                // Mark threads as ended if they're not on server anymore
                for (const [threadId, thread] of activeThreads) {
                    if (thread.status === 'running' && !serverThreadIds.has(threadId)) {
                        thread.status = 'ended';
                        thread.endTime = Date.now();
                    }
                }

                updateThreadsView();
            }
        } catch (e) {
            console.error('[Argus] Failed to fetch active threads:', e);
        }
    }

    function formatNumber(num) {
        if (num >= 1000000) {
            return (num / 1000000).toFixed(1) + 'M';
        }
        if (num >= 1000) {
            return (num / 1000).toFixed(1) + 'K';
        }
        return num.toString();
    }

    function updateThreadsView() {
        const now = Date.now();

        // Remove threads that ended more than retention time ago
        for (const [threadId, thread] of activeThreads) {
            if (thread.status === 'ended' && thread.endTime && (now - thread.endTime > threadRetentionMs)) {
                activeThreads.delete(threadId);
            }
        }

        const runningCount = [...activeThreads.values()].filter(t => t.status === 'running').length;
        const totalCount = activeThreads.size;
        threadCountEl.textContent = `${runningCount} running, ${totalCount} visible`;

        if (totalCount === 0) {
            threadsContainer.innerHTML = '<div class="empty-state">No active threads</div>';
            return;
        }

        // Build thread cards
        const fragment = document.createDocumentFragment();
        const nowDate = new Date();

        // Sort: running first, then ended (newest first)
        const sortedThreads = [...activeThreads.entries()].sort((a, b) => {
            if (a[1].status === 'running' && b[1].status !== 'running') return -1;
            if (a[1].status !== 'running' && b[1].status === 'running') return 1;
            return b[1].startTime - a[1].startTime;
        });

        sortedThreads.forEach(([threadId, thread]) => {
            const card = document.createElement('div');
            const isEnded = thread.status === 'ended';
            let cardClass = 'thread-card';
            if (thread.isPinned) cardClass += ' pinned';
            if (isEnded) cardClass += ' ended';
            card.className = cardClass;
            card.dataset.threadId = threadId;

            const duration = nowDate - thread.startTime;
            const durationPercent = Math.min(100, (duration / 10000) * 100); // 10s = 100%

            let statusClass = 'running';
            let statusText = 'Running';
            if (thread.isPinned) {
                statusClass = 'pinned';
                statusText = 'Pinned';
            } else if (isEnded) {
                statusClass = 'ended';
                statusText = 'Ended';
            }

            card.innerHTML = `
                <div class="thread-card-header">
                    <span class="thread-name">${escapeHtml(thread.threadName)}</span>
                    <span class="thread-status ${statusClass}">
                        ${statusText}
                    </span>
                </div>
                <div class="thread-card-body">
                    <div class="thread-info">
                        <span class="thread-info-label">ID</span>
                        <span class="thread-info-value">${threadId}</span>
                    </div>
                    <div class="thread-info">
                        <span class="thread-info-label">Duration</span>
                        <span class="thread-info-value">${formatDurationMs(duration)}</span>
                    </div>
                    ${thread.carrierThread ? `
                    <div class="thread-info">
                        <span class="thread-info-label">Carrier</span>
                        <span class="thread-info-value">${thread.carrierThread}</span>
                    </div>
                    ` : ''}
                    <div class="thread-duration-bar">
                        <div class="thread-duration-fill" style="width: ${durationPercent}%"></div>
                    </div>
                </div>
            `;

            // Add click handler to show thread details
            card.style.cursor = 'pointer';
            card.addEventListener('click', () => showThreadDetails(threadId));

            fragment.appendChild(card);
        });

        threadsContainer.innerHTML = '';
        threadsContainer.appendChild(fragment);
    }

    function addPinnedAlert(event) {
        pinnedAlerts.unshift({
            threadId: event.threadId,
            threadName: event.threadName || `Thread-${event.threadId}`,
            timestamp: event.timestamp,
            stackTrace: event.stackTrace || 'No stack trace available',
            duration: event.duration
        });

        // Limit alerts
        while (pinnedAlerts.length > maxPinnedAlerts) {
            pinnedAlerts.pop();
        }

        updatePinnedSection();
    }

    function updatePinnedSection() {
        if (pinnedAlerts.length === 0) {
            pinnedSection.classList.add('hidden');
            return;
        }

        pinnedSection.classList.remove('hidden');
        pinnedBadge.textContent = pinnedAlerts.length;

        pinnedList.innerHTML = pinnedAlerts.map(alert => `
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
        // Remove placeholder if exists
        const placeholder = eventsLog.querySelector('.event-placeholder');
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

        eventsLog.appendChild(item);

        // Limit events
        while (eventsLog.children.length > maxEvents) {
            eventsLog.removeChild(eventsLog.firstChild);
        }

        // Auto-scroll
        if (autoScrollCheckbox.checked) {
            eventsLog.scrollTop = eventsLog.scrollHeight;
        }
    }

    function formatTimestamp(timestamp) {
        if (!timestamp) return '--:--:--';

        try {
            const date = new Date(timestamp);
            return date.toLocaleTimeString('en-US', {
                hour12: false,
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit'
            });
        } catch (e) {
            return timestamp.substring(11, 19) || '--:--:--';
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

        if (event.threadName) {
            parts.push(`Thread: ${event.threadName}`);
        }
        if (event.threadId) {
            parts.push(`ID: ${event.threadId}`);
        }
        if (event.carrierThread) {
            parts.push(`Carrier: ${event.carrierThread}`);
        }
        if (event.duration && event.duration > 0) {
            parts.push(`Duration: ${formatDuration(event.duration)}`);
        }
        if (event.stackTrace) {
            parts.push(`Stack: ${event.stackTrace.substring(0, 200)}...`);
        }

        return parts.join('\n');
    }

    function formatDuration(nanos) {
        if (nanos < 1000) return nanos + 'ns';
        if (nanos < 1000000) return (nanos / 1000).toFixed(2) + 'us';
        if (nanos < 1000000000) return (nanos / 1000000).toFixed(2) + 'ms';
        return (nanos / 1000000000).toFixed(2) + 's';
    }

    function formatDurationMs(ms) {
        if (ms < 1000) return ms + 'ms';
        if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
        return (ms / 60000).toFixed(1) + 'm';
    }

    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // Thread detail modal functions
    async function showThreadDetails(threadId) {
        const thread = activeThreads.get(threadId);
        if (!thread) {
            console.error('[Argus] Thread not found:', threadId);
            return;
        }

        // Show modal with loading state
        threadModal.classList.remove('hidden');
        modalThreadName.textContent = thread.threadName;
        modalThreadId.textContent = threadId;

        // Set status
        let statusText = 'Running';
        let statusClass = 'running';
        if (thread.isPinned) {
            statusText = 'Pinned';
            statusClass = 'pinned';
        } else if (thread.status === 'ended') {
            statusText = 'Ended';
            statusClass = 'ended';
        }
        modalThreadStatus.textContent = statusText;
        modalThreadStatus.className = 'thread-stat-value ' + statusClass;

        // Set duration
        const duration = new Date() - thread.startTime;
        modalThreadDuration.textContent = formatDurationMs(duration);

        // Show loading
        modalEventsList.innerHTML = '<div class="loading">Loading events...</div>';
        modalEventCount.textContent = '-';

        // Fetch thread events from API
        try {
            const response = await fetch(`/threads/${threadId}/events`);
            if (response.ok) {
                const data = await response.json();
                modalEventCount.textContent = data.eventCount;
                renderThreadEvents(data.events);
            } else {
                modalEventsList.innerHTML = '<div class="empty">Failed to load events</div>';
            }
        } catch (e) {
            console.error('[Argus] Failed to fetch thread events:', e);
            modalEventsList.innerHTML = '<div class="empty">Failed to load events</div>';
        }
    }

    // Thread dump functions
    async function captureAllThreadsDump() {
        // Show dump modal with loading state
        dumpModal.classList.remove('hidden');
        dumpModalTitle.textContent = 'Thread Dump (All Threads)';
        dumpTimestamp.textContent = 'Loading...';
        dumpThreadCount.textContent = '';
        dumpOutput.textContent = 'Capturing thread dump...';
        currentDumpText = '';

        try {
            const response = await fetch('/thread-dump');
            if (response.ok) {
                const data = await response.json();
                displayAllThreadsDump(data);
            } else {
                dumpOutput.textContent = 'Failed to capture thread dump';
            }
        } catch (e) {
            console.error('[Argus] Failed to capture thread dump:', e);
            dumpOutput.textContent = 'Error: ' + e.message;
        }
    }

    async function captureSingleThreadDump(threadId) {
        // Show dump modal with loading state
        dumpModal.classList.remove('hidden');
        dumpModalTitle.textContent = `Thread Dump (Thread ${threadId})`;
        dumpTimestamp.textContent = 'Loading...';
        dumpThreadCount.textContent = '';
        dumpOutput.textContent = 'Capturing stack trace...';
        currentDumpText = '';

        try {
            const response = await fetch(`/threads/${threadId}/dump`);
            if (response.ok) {
                const data = await response.json();
                displaySingleThreadDump(data);
            } else {
                dumpOutput.textContent = 'Failed to capture stack trace';
            }
        } catch (e) {
            console.error('[Argus] Failed to capture stack trace:', e);
            dumpOutput.textContent = 'Error: ' + e.message;
        }
    }

    function displayAllThreadsDump(data) {
        dumpTimestamp.textContent = `Captured at: ${formatTimestamp(data.timestamp)}`;
        dumpThreadCount.textContent = `${data.totalThreads} threads`;

        let text = `Thread Dump - ${data.timestamp}\n`;
        text += `Total Threads: ${data.totalThreads}\n`;
        text += '='.repeat(80) + '\n\n';

        data.threads.forEach(thread => {
            const virtualTag = thread.isVirtual ? ' [Virtual]' : '';
            text += `"${thread.threadName}" #${thread.threadId}${virtualTag}\n`;
            text += `   State: ${thread.state}\n`;
            text += thread.stackTrace.replace(/\\n/g, '\n');
            text += '\n';
        });

        currentDumpText = text;
        dumpOutput.textContent = text;
    }

    function displaySingleThreadDump(data) {
        dumpTimestamp.textContent = `Captured at: ${formatTimestamp(data.timestamp)}`;
        dumpThreadCount.textContent = '';

        if (data.error) {
            dumpOutput.textContent = data.error;
            currentDumpText = data.error;
            return;
        }

        const virtualTag = data.isVirtual ? ' [Virtual]' : '';
        let text = `"${data.threadName}" #${data.threadId}${virtualTag}\n`;
        text += `State: ${data.state}\n`;
        text += '-'.repeat(60) + '\n';
        text += data.stackTrace.replace(/\\n/g, '\n');

        currentDumpText = text;
        dumpOutput.textContent = text;
    }

    function renderThreadEvents(events) {
        if (!events || events.length === 0) {
            modalEventsList.innerHTML = '<div class="empty">No events recorded</div>';
            return;
        }

        const html = events.map(event => {
            const type = event.type.toLowerCase();
            const iconText = type === 'start' ? '▶' : type === 'end' ? '■' : '⚠';
            const typeLabel = type === 'start' ? 'Started' : type === 'end' ? 'Ended' : 'Pinned';

            let details = '';
            if (event.carrierThread) {
                details += `Carrier Thread: ${event.carrierThread}`;
            }
            if (event.duration && event.duration > 0) {
                if (details) details += ' | ';
                details += `Duration: ${formatDuration(event.duration)}`;
            }

            let stackHtml = '';
            if (event.stackTrace) {
                stackHtml = `<div class="thread-event-stack">${escapeHtml(event.stackTrace)}</div>`;
            }

            return `
                <div class="thread-event-item">
                    <div class="thread-event-icon ${type}">${iconText}</div>
                    <div class="thread-event-content">
                        <div class="thread-event-type ${type}">${typeLabel}</div>
                        <div class="thread-event-time">${formatTimestamp(event.timestamp)}</div>
                        ${details ? `<div class="thread-event-details">${details}</div>` : ''}
                        ${stackHtml}
                    </div>
                </div>
            `;
        }).join('');

        modalEventsList.innerHTML = html;
    }

    function clearEvents() {
        eventsLog.innerHTML = '<div class="event-placeholder">Waiting for virtual thread events...</div>';
        // Note: Don't reset counts since they're synced from server
        // Just clear local UI state
        activeThreads.clear();
        pinnedAlerts.length = 0;
        updateThreadsView();
        updatePinnedSection();
        pinnedCard.classList.remove('has-pinned');
        // Re-fetch metrics to restore accurate counts
        fetchMetrics();
    }

    // Update thread view periodically to refresh durations
    setInterval(() => {
        if (activeThreads.size > 0) {
            updateThreadsView();
        }
    }, 1000);

    // Event listeners
    clearBtn.addEventListener('click', clearEvents);

    // Initialize charts
    function initCharts() {
        // Common chart options for dark theme
        const gridColor = 'rgba(48, 54, 61, 0.8)';
        const textColor = '#8b949e';

        // Events Rate Chart (Line)
        eventsRateChart = new Chart(eventsRateCanvas, {
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
                    x: {
                        display: false
                    },
                    y: {
                        beginAtZero: true,
                        grid: { color: gridColor },
                        ticks: { color: textColor, font: { size: 10 } }
                    }
                }
            }
        });

        // Active Threads Chart (Line)
        activeThreadsChart = new Chart(activeThreadsCanvas, {
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
                plugins: {
                    legend: {
                        display: false
                    }
                },
                scales: {
                    x: {
                        display: false
                    },
                    y: {
                        beginAtZero: true,
                        grid: { color: gridColor },
                        ticks: { color: textColor, font: { size: 10 } }
                    }
                }
            }
        });

        // Duration Distribution Chart (Bar)
        durationChart = new Chart(durationCanvas, {
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
                plugins: {
                    legend: {
                        display: false
                    }
                },
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

    // Update charts every second
    function updateCharts() {
        const now = Math.floor(Date.now() / 1000);
        const timeLabel = new Date().toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });

        // If we've moved to a new second, push the current counts
        if (now > lastSecondTimestamp) {
            // Add data points
            eventsRateData.labels.push(timeLabel);
            eventsRateData.start.push(currentSecondEvents.start);
            eventsRateData.end.push(currentSecondEvents.end);
            eventsRateData.pinned.push(currentSecondEvents.pinned);

            activeThreadsData.labels.push(timeLabel);
            activeThreadsData.values.push(counts.active);

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

            // Reset counters for next second
            currentSecondEvents = { start: 0, end: 0, pinned: 0 };
            lastSecondTimestamp = now;
        }

        // Update chart displays
        if (eventsRateChart) eventsRateChart.update('none');
        if (activeThreadsChart) activeThreadsChart.update('none');
        if (durationChart) durationChart.update('none');
    }

    // Track event for charts
    function trackEventForCharts(event) {
        // Count events per second
        if (event.type === 'START') {
            currentSecondEvents.start++;
        } else if (event.type === 'END') {
            currentSecondEvents.end++;
            // Track duration for histogram
            const thread = activeThreads.get(event.threadId);
            if (thread && thread.startTime) {
                const durationMs = new Date(event.timestamp) - thread.startTime;
                addToDurationBucket(durationMs);
            }
        } else if (event.type === 'PINNED') {
            currentSecondEvents.pinned++;
        }
    }

    // Add duration to appropriate bucket
    function addToDurationBucket(durationMs) {
        if (durationMs < 10) {
            durationBuckets.values[0]++;
        } else if (durationMs < 50) {
            durationBuckets.values[1]++;
        } else if (durationMs < 100) {
            durationBuckets.values[2]++;
        } else if (durationMs < 500) {
            durationBuckets.values[3]++;
        } else if (durationMs < 1000) {
            durationBuckets.values[4]++;
        } else if (durationMs < 5000) {
            durationBuckets.values[5]++;
        } else {
            durationBuckets.values[6]++;
        }
    }

    // Initialize
    initCharts();
    connect();
    fetchMetrics(); // Initial fetch
    fetchActiveThreads(); // Restore active threads state

    // Periodically sync with server
    setInterval(fetchMetrics, 1000);
    setInterval(fetchActiveThreads, 2000); // Sync active threads less frequently
    setInterval(updateCharts, 1000); // Update charts every second
})();
