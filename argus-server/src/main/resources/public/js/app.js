(function() {
    'use strict';

    // DOM elements
    const connectionStatus = document.getElementById('connection-status');
    const statusText = connectionStatus.querySelector('.status-text');
    const totalEventsEl = document.getElementById('total-events');
    const startEventsEl = document.getElementById('start-events');
    const endEventsEl = document.getElementById('end-events');
    const pinnedEventsEl = document.getElementById('pinned-events');
    const eventsLog = document.getElementById('events-log');
    const clearBtn = document.getElementById('clear-events');
    const autoScrollCheckbox = document.getElementById('auto-scroll');
    const pinnedCard = pinnedEventsEl.closest('.metric-card');

    // State
    let ws = null;
    let reconnectAttempts = 0;
    const maxReconnectAttempts = 10;
    const reconnectDelay = 2000;
    const maxEvents = 500;

    const counts = {
        total: 0,
        START: 0,
        END: 0,
        PINNED: 0,
        SUBMIT_FAILED: 0
    };

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

        updateCounters();
        addEventToLog(event);
    }

    function updateCounters() {
        totalEventsEl.textContent = formatNumber(counts.total);
        startEventsEl.textContent = formatNumber(counts.START);
        endEventsEl.textContent = formatNumber(counts.END);
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
            <span class="event-details">${details}</span>
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
            parts.push(`Thread: ${escapeHtml(event.threadName)}`);
        } else if (event.threadId) {
            parts.push(`Thread ID: ${event.threadId}`);
        }

        if (event.carrierThread) {
            parts.push(`Carrier: ${event.carrierThread}`);
        }

        if (event.duration && event.duration > 0) {
            parts.push(`Duration: ${formatDuration(event.duration)}`);
        }

        if (event.stackTrace) {
            parts.push(`Stack: ${escapeHtml(event.stackTrace.substring(0, 100))}...`);
        }

        return parts.join(' | ') || 'No details';
    }

    function formatDuration(nanos) {
        if (nanos < 1000) return nanos + 'ns';
        if (nanos < 1000000) return (nanos / 1000).toFixed(2) + 'us';
        if (nanos < 1000000000) return (nanos / 1000000).toFixed(2) + 'ms';
        return (nanos / 1000000000).toFixed(2) + 's';
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    function clearEvents() {
        eventsLog.innerHTML = '<div class="event-placeholder">Waiting for events...</div>';
        counts.total = 0;
        counts.START = 0;
        counts.END = 0;
        counts.PINNED = 0;
        counts.SUBMIT_FAILED = 0;
        updateCounters();
        pinnedCard.classList.remove('has-pinned');
    }

    // Event listeners
    clearBtn.addEventListener('click', clearEvents);

    // Initialize
    connect();
})();
