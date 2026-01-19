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
        SUBMIT_FAILED: 0
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
        if (e.key === 'Escape' && !helpModal.classList.contains('hidden')) {
            helpModal.classList.add('hidden');
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
        activeThreadsEl.textContent = formatNumber(activeThreads.size);
        pinnedEventsEl.textContent = formatNumber(counts.PINNED);

        if (counts.PINNED > 0) {
            pinnedCard.classList.add('has-pinned');
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

    function clearEvents() {
        eventsLog.innerHTML = '<div class="event-placeholder">Waiting for virtual thread events...</div>';
        counts.total = 0;
        counts.START = 0;
        counts.END = 0;
        counts.PINNED = 0;
        counts.SUBMIT_FAILED = 0;
        activeThreads.clear();
        pinnedAlerts.length = 0;
        updateCounters();
        updateThreadsView();
        updatePinnedSection();
        pinnedCard.classList.remove('has-pinned');
    }

    // Update thread view periodically to refresh durations
    setInterval(() => {
        if (activeThreads.size > 0) {
            updateThreadsView();
        }
    }, 1000);

    // Event listeners
    clearBtn.addEventListener('click', clearEvents);

    // Initialize
    connect();
})();
