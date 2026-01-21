/**
 * Utility functions for Argus Dashboard
 */
export function formatNumber(num) {
    if (num >= 1000000) {
        return (num / 1000000).toFixed(1) + 'M';
    }
    if (num >= 1000) {
        return (num / 1000).toFixed(1) + 'K';
    }
    return num.toString();
}

export function formatTimestamp(timestamp) {
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

export function formatDuration(nanos) {
    if (nanos < 1000) return nanos + 'ns';
    if (nanos < 1000000) return (nanos / 1000).toFixed(2) + 'us';
    if (nanos < 1000000000) return (nanos / 1000000).toFixed(2) + 'ms';
    return (nanos / 1000000000).toFixed(2) + 's';
}

export function formatDurationMs(ms) {
    if (ms < 1000) return ms + 'ms';
    if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
    return (ms / 60000).toFixed(1) + 'm';
}

export function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
