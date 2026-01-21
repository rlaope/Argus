/**
 * Thread view management for Argus Dashboard
 */
import { threadStates, stateCounts } from './state.js';
import { escapeHtml, formatDurationMs, formatTimestamp, formatDuration } from './utils.js';

let threadModal = null;
let modalElements = {};
let dumpModal = null;
let dumpElements = {};
let currentDumpText = '';

/**
 * Initialize thread view with DOM elements
 */
export function initThreadView(elements) {
    threadModal = elements.threadModal;
    modalElements = {
        backdrop: elements.threadModalBackdrop,
        close: elements.threadModalClose,
        name: elements.modalThreadName,
        id: elements.modalThreadId,
        status: elements.modalThreadStatus,
        duration: elements.modalThreadDuration,
        eventCount: elements.modalEventCount,
        eventsList: elements.modalEventsList,
        dumpBtn: elements.modalDumpBtn
    };

    dumpModal = elements.dumpModal;
    dumpElements = {
        backdrop: elements.dumpModalBackdrop,
        close: elements.dumpModalClose,
        title: elements.dumpModalTitle,
        timestamp: elements.dumpTimestamp,
        threadCount: elements.dumpThreadCount,
        copyBtn: elements.dumpCopyBtn,
        output: elements.dumpOutput
    };

    // Modal handlers
    modalElements.backdrop.addEventListener('click', () => threadModal.classList.add('hidden'));
    modalElements.close.addEventListener('click', () => threadModal.classList.add('hidden'));
    dumpElements.backdrop.addEventListener('click', () => dumpModal.classList.add('hidden'));
    dumpElements.close.addEventListener('click', () => dumpModal.classList.add('hidden'));

    // Copy to clipboard
    dumpElements.copyBtn.addEventListener('click', () => {
        if (currentDumpText) {
            navigator.clipboard.writeText(currentDumpText).then(() => {
                const originalText = dumpElements.copyBtn.textContent;
                dumpElements.copyBtn.textContent = 'Copied!';
                setTimeout(() => {
                    dumpElements.copyBtn.textContent = originalText;
                }, 2000);
            });
        }
    });
}

/**
 * Render the thread state view grouped by state
 */
export function renderThreadStateView(container, countEl) {
    const running = [...threadStates.running.values()];
    const pinned = [...threadStates.pinned.values()];
    const ended = [...threadStates.ended.values()];
    const total = running.length + pinned.length + ended.length;

    countEl.textContent = `${stateCounts.running} running, ${stateCounts.pinned} pinned, ${stateCounts.ended} recent`;

    if (total === 0) {
        container.innerHTML = '<div class="empty-state">No active threads</div>';
        return;
    }

    const fragment = document.createDocumentFragment();

    // Pinned threads section (critical - show first)
    if (pinned.length > 0) {
        const section = createStateSection('Pinned', 'pinned', pinned);
        fragment.appendChild(section);
    }

    // Running threads section
    if (running.length > 0) {
        const section = createStateSection('Running', 'running', running);
        fragment.appendChild(section);
    }

    // Recently ended threads section
    if (ended.length > 0) {
        const section = createStateSection('Recently Ended', 'ended', ended);
        fragment.appendChild(section);
    }

    container.innerHTML = '';
    container.appendChild(fragment);
}

function createStateSection(title, stateClass, threads) {
    const section = document.createElement('div');
    section.className = `thread-state-section ${stateClass}`;

    const header = document.createElement('div');
    header.className = 'thread-state-header';
    header.innerHTML = `
        <span class="state-indicator ${stateClass}"></span>
        <span class="state-title">${title}</span>
        <span class="state-count">${threads.length}</span>
    `;
    section.appendChild(header);

    const grid = document.createElement('div');
    grid.className = 'thread-cards-grid';

    const now = new Date();
    threads.forEach(thread => {
        const card = createThreadCard(thread, stateClass, now);
        grid.appendChild(card);
    });

    section.appendChild(grid);
    return section;
}

function createThreadCard(thread, stateClass, now) {
    const card = document.createElement('div');
    card.className = `thread-card ${stateClass}`;
    if (thread.isPinned) card.classList.add('pinned');
    card.dataset.threadId = thread.threadId;

    const duration = now - thread.startTime;
    const durationPercent = Math.min(100, (duration / 10000) * 100);

    let statusClass = stateClass;
    let statusText = stateClass.charAt(0).toUpperCase() + stateClass.slice(1);
    if (thread.isPinned && stateClass !== 'pinned') {
        statusText += ' (was pinned)';
    }

    card.innerHTML = `
        <div class="thread-card-header">
            <span class="thread-name">${escapeHtml(thread.threadName)}</span>
            <span class="thread-status ${statusClass}">${statusText}</span>
        </div>
        <div class="thread-card-body">
            <div class="thread-info">
                <span class="thread-info-label">ID</span>
                <span class="thread-info-value">${thread.threadId}</span>
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
                <div class="thread-duration-fill ${statusClass}" style="width: ${durationPercent}%"></div>
            </div>
        </div>
    `;

    card.style.cursor = 'pointer';
    card.addEventListener('click', () => showThreadDetails(thread));

    return card;
}

async function showThreadDetails(thread) {
    threadModal.classList.remove('hidden');
    modalElements.name.textContent = thread.threadName;
    modalElements.id.textContent = thread.threadId;

    // Set status
    let statusText = 'Running';
    let statusClass = 'running';
    if (thread.isPinned || threadStates.pinned.has(thread.threadId)) {
        statusText = 'Pinned';
        statusClass = 'pinned';
    } else if (threadStates.ended.has(thread.threadId)) {
        statusText = 'Ended';
        statusClass = 'ended';
    }
    modalElements.status.textContent = statusText;
    modalElements.status.className = 'thread-stat-value ' + statusClass;

    const duration = new Date() - thread.startTime;
    modalElements.duration.textContent = formatDurationMs(duration);
    modalElements.eventsList.innerHTML = '<div class="loading">Loading events...</div>';
    modalElements.eventCount.textContent = '-';

    // Setup dump button
    modalElements.dumpBtn.onclick = () => captureSingleThreadDump(thread.threadId);

    // Fetch thread events
    try {
        const response = await fetch(`/threads/${thread.threadId}/events`);
        if (response.ok) {
            const data = await response.json();
            modalElements.eventCount.textContent = data.eventCount;
            renderThreadEvents(data.events);
        } else {
            modalElements.eventsList.innerHTML = '<div class="empty">Failed to load events</div>';
        }
    } catch (e) {
        console.error('[Argus] Failed to fetch thread events:', e);
        modalElements.eventsList.innerHTML = '<div class="empty">Failed to load events</div>';
    }
}

function renderThreadEvents(events) {
    if (!events || events.length === 0) {
        modalElements.eventsList.innerHTML = '<div class="empty">No events recorded</div>';
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

    modalElements.eventsList.innerHTML = html;
}

async function captureSingleThreadDump(threadId) {
    dumpModal.classList.remove('hidden');
    dumpElements.title.textContent = `Thread Dump (Thread ${threadId})`;
    dumpElements.timestamp.textContent = 'Loading...';
    dumpElements.threadCount.textContent = '';
    dumpElements.output.textContent = 'Capturing stack trace...';
    currentDumpText = '';

    try {
        const response = await fetch(`/threads/${threadId}/dump`);
        if (response.ok) {
            const data = await response.json();
            displaySingleThreadDump(data);
        } else {
            dumpElements.output.textContent = 'Failed to capture stack trace';
        }
    } catch (e) {
        console.error('[Argus] Failed to capture stack trace:', e);
        dumpElements.output.textContent = 'Error: ' + e.message;
    }
}

function displaySingleThreadDump(data) {
    dumpElements.timestamp.textContent = `Captured at: ${formatTimestamp(data.timestamp)}`;
    dumpElements.threadCount.textContent = '';

    if (data.error) {
        dumpElements.output.textContent = data.error;
        currentDumpText = data.error;
        return;
    }

    const virtualTag = data.isVirtual ? ' [Virtual]' : '';
    let text = `"${data.threadName}" #${data.threadId}${virtualTag}\n`;
    text += `State: ${data.state}\n`;
    text += '-'.repeat(60) + '\n';
    text += data.stackTrace.replace(/\\n/g, '\n');

    currentDumpText = text;
    dumpElements.output.textContent = text;
}

export async function captureAllThreadsDump() {
    dumpModal.classList.remove('hidden');
    dumpElements.title.textContent = 'Thread Dump (All Threads)';
    dumpElements.timestamp.textContent = 'Loading...';
    dumpElements.threadCount.textContent = '';
    dumpElements.output.textContent = 'Capturing thread dump...';
    currentDumpText = '';

    try {
        const response = await fetch('/thread-dump');
        if (response.ok) {
            const data = await response.json();
            displayAllThreadsDump(data);
        } else {
            dumpElements.output.textContent = 'Failed to capture thread dump';
        }
    } catch (e) {
        console.error('[Argus] Failed to capture thread dump:', e);
        dumpElements.output.textContent = 'Error: ' + e.message;
    }
}

function displayAllThreadsDump(data) {
    dumpElements.timestamp.textContent = `Captured at: ${formatTimestamp(data.timestamp)}`;
    dumpElements.threadCount.textContent = `${data.totalThreads} threads`;

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
    dumpElements.output.textContent = text;
}
