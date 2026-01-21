/**
 * WebSocket connection management for Argus Dashboard
 */

let ws = null;
let reconnectAttempts = 0;
const maxReconnectAttempts = 10;
const reconnectDelay = 2000;

let statusElement = null;
let statusTextElement = null;
let eventHandler = null;
let stateUpdateHandler = null;

/**
 * Initialize WebSocket with DOM elements and handlers
 */
export function initWebSocket(elements, handlers) {
    statusElement = elements.connectionStatus;
    statusTextElement = elements.statusText;
    eventHandler = handlers.onEvent;
    stateUpdateHandler = handlers.onStateUpdate;

    connect();
}

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

            // Handle different message types
            if (data.type === 'THREAD_STATE_UPDATE') {
                if (stateUpdateHandler) {
                    stateUpdateHandler(data);
                }
            } else {
                // Regular event
                if (eventHandler) {
                    eventHandler(data);
                }
            }
        } catch (e) {
            console.error('[Argus] Failed to parse message:', e);
        }
    };
}

function setConnected(connected) {
    if (connected) {
        statusElement.classList.remove('disconnected');
        statusElement.classList.add('connected');
        statusTextElement.textContent = 'Connected';
    } else {
        statusElement.classList.remove('connected');
        statusElement.classList.add('disconnected');
        statusTextElement.textContent = 'Disconnected';
    }
}

function scheduleReconnect() {
    if (reconnectAttempts >= maxReconnectAttempts) {
        console.log('[Argus] Max reconnect attempts reached');
        statusTextElement.textContent = 'Connection Failed';
        return;
    }

    reconnectAttempts++;
    statusTextElement.textContent = `Reconnecting (${reconnectAttempts}/${maxReconnectAttempts})...`;

    setTimeout(connect, reconnectDelay);
}

export function isConnected() {
    return ws && ws.readyState === WebSocket.OPEN;
}
