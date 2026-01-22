/**
 * Argus Dashboard - Event Filter Module
 *
 * Provides filtering capabilities for virtual thread events:
 * - Thread name search (contains, glob, regex)
 * - Time range selection
 * - Event type filtering
 * - URL state persistence for shareable filters
 */

// Filter state
const filterState = {
    search: '',
    searchMode: 'contains',
    timeFrom: null,
    timeTo: null,
    eventTypes: {
        START: true,
        END: true,
        PINNED: true,
        SUBMIT_FAILED: true
    }
};

// All events storage (unfiltered)
let allEvents = [];
const MAX_STORED_EVENTS = 2000;

// DOM elements
let elements = {};

// Callback for when filters change
let onFilterChange = null;

/**
 * Initialize the filter module
 * @param {Object} domElements - DOM element references
 * @param {Function} callback - Called when filters change with filtered events
 */
export function initFilters(domElements, callback) {
    elements = {
        searchInput: document.getElementById('thread-search'),
        searchMode: document.getElementById('search-mode'),
        timeFrom: document.getElementById('time-from'),
        timeTo: document.getElementById('time-to'),
        timeClear: document.getElementById('time-clear'),
        filterStart: document.getElementById('filter-start'),
        filterEnd: document.getElementById('filter-end'),
        filterPinned: document.getElementById('filter-pinned'),
        filterSubmitFailed: document.getElementById('filter-submit-failed'),
        applyBtn: document.getElementById('apply-filters'),
        resetBtn: document.getElementById('reset-filters'),
        shareBtn: document.getElementById('share-filters'),
        filterStatus: document.getElementById('filter-status'),
        filterCount: document.getElementById('filter-count'),
        filterInfo: document.getElementById('filter-info'),
        eventsLog: domElements.eventsLog
    };

    onFilterChange = callback;

    // Load filters from URL
    loadFiltersFromUrl();

    // Setup event listeners
    setupFilterListeners();

    // Apply initial filters
    applyFilters();
}

/**
 * Setup event listeners for filter controls
 */
function setupFilterListeners() {
    // Search input - apply on Enter key
    elements.searchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            applyFilters();
        }
    });

    // Search mode change
    elements.searchMode.addEventListener('change', () => {
        if (filterState.search) {
            applyFilters();
        }
    });

    // Time range inputs
    elements.timeFrom.addEventListener('change', applyFilters);
    elements.timeTo.addEventListener('change', applyFilters);

    // Time clear button
    elements.timeClear.addEventListener('click', () => {
        elements.timeFrom.value = '';
        elements.timeTo.value = '';
        applyFilters();
    });

    // Event type checkboxes
    elements.filterStart.addEventListener('change', applyFilters);
    elements.filterEnd.addEventListener('change', applyFilters);
    elements.filterPinned.addEventListener('change', applyFilters);
    elements.filterSubmitFailed.addEventListener('change', applyFilters);

    // Apply button
    elements.applyBtn.addEventListener('click', applyFilters);

    // Reset button
    elements.resetBtn.addEventListener('click', resetFilters);

    // Share button
    elements.shareBtn.addEventListener('click', shareFilters);
}

/**
 * Add an event to storage and return whether it should be displayed
 * @param {Object} event - The event to add
 * @returns {boolean} - Whether the event passes current filters
 */
export function addEvent(event) {
    allEvents.push(event);

    // Trim old events if exceeds max
    while (allEvents.length > MAX_STORED_EVENTS) {
        allEvents.shift();
    }

    return eventPassesFilter(event);
}

/**
 * Get all stored events
 * @returns {Array} - All stored events
 */
export function getAllEvents() {
    return allEvents;
}

/**
 * Get filtered events
 * @returns {Array} - Events that pass current filters
 */
export function getFilteredEvents() {
    return allEvents.filter(event => eventPassesFilter(event));
}

/**
 * Clear all stored events
 */
export function clearEvents() {
    allEvents = [];
    updateFilterStatus();
}

/**
 * Check if an event passes current filters
 * @param {Object} event - Event to check
 * @returns {boolean} - Whether event passes all filters
 */
function eventPassesFilter(event) {
    // Check event type filter
    if (!filterState.eventTypes[event.type]) {
        return false;
    }

    // Check thread name search
    if (filterState.search) {
        const threadName = event.threadName || `Thread-${event.threadId}`;
        if (!matchesSearch(threadName, filterState.search, filterState.searchMode)) {
            return false;
        }
    }

    // Check time range
    const eventTime = new Date(event.timestamp);
    if (filterState.timeFrom && eventTime < filterState.timeFrom) {
        return false;
    }
    if (filterState.timeTo && eventTime > filterState.timeTo) {
        return false;
    }

    return true;
}

/**
 * Check if a string matches the search pattern
 * @param {string} text - Text to search in
 * @param {string} pattern - Search pattern
 * @param {string} mode - Search mode (contains, glob, regex)
 * @returns {boolean} - Whether text matches pattern
 */
function matchesSearch(text, pattern, mode) {
    if (!pattern) return true;

    const lowerText = text.toLowerCase();
    const lowerPattern = pattern.toLowerCase();

    switch (mode) {
        case 'contains':
            return lowerText.includes(lowerPattern);

        case 'glob':
            return matchesGlob(lowerText, lowerPattern);

        case 'regex':
            try {
                const regex = new RegExp(pattern, 'i');
                return regex.test(text);
            } catch (e) {
                // Invalid regex, fall back to contains
                return lowerText.includes(lowerPattern);
            }

        default:
            return lowerText.includes(lowerPattern);
    }
}

/**
 * Match a string against a glob pattern
 * Supports * (any characters) and ? (single character)
 * @param {string} text - Text to match
 * @param {string} pattern - Glob pattern
 * @returns {boolean} - Whether text matches pattern
 */
function matchesGlob(text, pattern) {
    // Convert glob to regex
    let regexPattern = pattern
        .replace(/[.+^${}()|[\]\\]/g, '\\$&')  // Escape special regex chars
        .replace(/\*/g, '.*')                   // * -> .*
        .replace(/\?/g, '.');                   // ? -> .

    try {
        const regex = new RegExp(`^${regexPattern}$`, 'i');
        return regex.test(text);
    } catch (e) {
        return text.includes(pattern);
    }
}

/**
 * Read current filter values from UI and apply them
 */
function applyFilters() {
    // Read values from UI
    filterState.search = elements.searchInput.value.trim();
    filterState.searchMode = elements.searchMode.value;

    filterState.timeFrom = elements.timeFrom.value ?
        new Date(elements.timeFrom.value) : null;
    filterState.timeTo = elements.timeTo.value ?
        new Date(elements.timeTo.value) : null;

    filterState.eventTypes.START = elements.filterStart.checked;
    filterState.eventTypes.END = elements.filterEnd.checked;
    filterState.eventTypes.PINNED = elements.filterPinned.checked;
    filterState.eventTypes.SUBMIT_FAILED = elements.filterSubmitFailed.checked;

    // Update URL
    saveFiltersToUrl();

    // Update status display
    updateFilterStatus();

    // Trigger callback with filtered events
    if (onFilterChange) {
        onFilterChange(getFilteredEvents());
    }
}

/**
 * Reset all filters to defaults
 */
function resetFilters() {
    elements.searchInput.value = '';
    elements.searchMode.value = 'contains';
    elements.timeFrom.value = '';
    elements.timeTo.value = '';
    elements.filterStart.checked = true;
    elements.filterEnd.checked = true;
    elements.filterPinned.checked = true;
    elements.filterSubmitFailed.checked = true;

    applyFilters();

    // Clear URL params
    const url = new URL(window.location.href);
    url.search = '';
    window.history.replaceState({}, '', url);
}

/**
 * Copy shareable URL to clipboard
 */
async function shareFilters() {
    const url = window.location.href;

    try {
        await navigator.clipboard.writeText(url);

        // Show feedback
        const originalTitle = elements.shareBtn.title;
        elements.shareBtn.title = 'Copied!';
        elements.shareBtn.style.borderColor = 'var(--accent-green)';

        setTimeout(() => {
            elements.shareBtn.title = originalTitle;
            elements.shareBtn.style.borderColor = '';
        }, 2000);
    } catch (e) {
        console.error('[Argus] Failed to copy URL:', e);
    }
}

/**
 * Update filter status display
 */
function updateFilterStatus() {
    const filtered = getFilteredEvents();
    const total = allEvents.length;

    const hasActiveFilters = filterState.search ||
        filterState.timeFrom ||
        filterState.timeTo ||
        !filterState.eventTypes.START ||
        !filterState.eventTypes.END ||
        !filterState.eventTypes.PINNED ||
        !filterState.eventTypes.SUBMIT_FAILED;

    if (hasActiveFilters && total > 0) {
        elements.filterStatus.classList.remove('hidden');
        elements.filterCount.textContent = filtered.length;
        elements.filterInfo.textContent = `(${total} total)`;
    } else {
        elements.filterStatus.classList.add('hidden');
    }
}

/**
 * Save current filter state to URL parameters
 */
function saveFiltersToUrl() {
    const url = new URL(window.location.href);
    const params = url.searchParams;

    // Clear existing filter params
    params.delete('search');
    params.delete('mode');
    params.delete('from');
    params.delete('to');
    params.delete('types');

    // Add current filter values
    if (filterState.search) {
        params.set('search', filterState.search);
        if (filterState.searchMode !== 'contains') {
            params.set('mode', filterState.searchMode);
        }
    }

    if (filterState.timeFrom) {
        params.set('from', filterState.timeFrom.toISOString());
    }

    if (filterState.timeTo) {
        params.set('to', filterState.timeTo.toISOString());
    }

    // Only save event types if not all are selected
    const types = [];
    if (filterState.eventTypes.START) types.push('START');
    if (filterState.eventTypes.END) types.push('END');
    if (filterState.eventTypes.PINNED) types.push('PINNED');
    if (filterState.eventTypes.SUBMIT_FAILED) types.push('SUBMIT_FAILED');

    if (types.length > 0 && types.length < 4) {
        params.set('types', types.join(','));
    }

    // Update URL without reloading
    window.history.replaceState({}, '', url);
}

/**
 * Load filter state from URL parameters
 */
function loadFiltersFromUrl() {
    const params = new URLSearchParams(window.location.search);

    // Search
    const search = params.get('search');
    if (search) {
        elements.searchInput.value = search;
        filterState.search = search;
    }

    // Search mode
    const mode = params.get('mode');
    if (mode && ['contains', 'glob', 'regex'].includes(mode)) {
        elements.searchMode.value = mode;
        filterState.searchMode = mode;
    }

    // Time range
    const from = params.get('from');
    if (from) {
        try {
            const date = new Date(from);
            filterState.timeFrom = date;
            // Format for datetime-local input
            elements.timeFrom.value = formatDateTimeLocal(date);
        } catch (e) {
            // Invalid date
        }
    }

    const to = params.get('to');
    if (to) {
        try {
            const date = new Date(to);
            filterState.timeTo = date;
            elements.timeTo.value = formatDateTimeLocal(date);
        } catch (e) {
            // Invalid date
        }
    }

    // Event types
    const types = params.get('types');
    if (types) {
        const typeList = types.split(',');
        filterState.eventTypes.START = typeList.includes('START');
        filterState.eventTypes.END = typeList.includes('END');
        filterState.eventTypes.PINNED = typeList.includes('PINNED');
        filterState.eventTypes.SUBMIT_FAILED = typeList.includes('SUBMIT_FAILED');

        elements.filterStart.checked = filterState.eventTypes.START;
        elements.filterEnd.checked = filterState.eventTypes.END;
        elements.filterPinned.checked = filterState.eventTypes.PINNED;
        elements.filterSubmitFailed.checked = filterState.eventTypes.SUBMIT_FAILED;
    }
}

/**
 * Format a Date for datetime-local input
 * @param {Date} date - Date to format
 * @returns {string} - Formatted date string
 */
function formatDateTimeLocal(date) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    return `${year}-${month}-${day}T${hours}:${minutes}`;
}

/**
 * Check if current filter state is active (not default)
 * @returns {boolean} - Whether any filter is active
 */
export function hasActiveFilters() {
    return filterState.search ||
        filterState.timeFrom ||
        filterState.timeTo ||
        !filterState.eventTypes.START ||
        !filterState.eventTypes.END ||
        !filterState.eventTypes.PINNED ||
        !filterState.eventTypes.SUBMIT_FAILED;
}

/**
 * Get current filter state
 * @returns {Object} - Current filter state
 */
export function getFilterState() {
    return { ...filterState };
}