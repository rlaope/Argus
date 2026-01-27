/**
 * Application state management for Argus Dashboard
 */

// Event counts
export const counts = {
    total: 0,
    START: 0,
    END: 0,
    PINNED: 0,
    SUBMIT_FAILED: 0,
    active: 0
};

// Thread states from server (real-time mirroring)
export const threadStates = {
    running: new Map(),  // threadId -> thread info
    pinned: new Map(),   // threadId -> thread info
    ended: new Map()     // threadId -> thread info
};

// State counts from server
export const stateCounts = {
    running: 0,
    pinned: 0,
    ended: 0
};

// Pinned alerts
export const pinnedAlerts = [];
export const maxPinnedAlerts = 50;

// Expanded hotspots tracking
export const expandedHotspots = new Set();

// Chart data
export const chartDataPoints = 60;
export const eventsRateData = {
    labels: [],
    start: [],
    end: [],
    pinned: []
};
export const activeThreadsData = {
    labels: [],
    values: []
};
export const durationBuckets = {
    labels: ['<10ms', '10-50ms', '50-100ms', '100-500ms', '500ms-1s', '1-5s', '>5s'],
    values: [0, 0, 0, 0, 0, 0, 0]
};

// GC data
export const gcData = {
    totalEvents: 0,
    totalPauseMs: 0,
    avgPauseMs: 0,
    maxPauseMs: 0,
    currentHeapUsed: 0,
    currentHeapCommitted: 0,
    timeline: {
        labels: [],
        pauseTimes: []
    },
    heapHistory: {
        labels: [],
        used: [],
        committed: []
    }
};

// CPU data
export const cpuData = {
    currentJvmPercent: 0,
    currentMachinePercent: 0,
    peakJvmPercent: 0,
    history: {
        labels: [],
        jvm: [],
        machine: []
    }
};

// Allocation data
export const allocationData = {
    totalAllocations: 0,
    totalAllocatedMB: 0,
    allocationRateMBPerSec: 0,
    peakAllocationRateMBPerSec: 0,
    topAllocatingClasses: [],
    history: {
        labels: [],
        rates: []
    }
};

// Metaspace data
export const metaspaceData = {
    currentUsedMB: 0,
    currentCommittedMB: 0,
    peakUsedMB: 0,
    growthRateMBPerMin: 0,
    classCount: 0,
    history: {
        labels: [],
        used: [],
        committed: []
    }
};

// Method profiling data
export const profilingData = {
    totalSamples: 0,
    topMethods: []
};

// Contention data
export const contentionData = {
    totalContentionEvents: 0,
    totalContentionTimeMs: 0,
    hotspots: []
};

// Correlation data
export const correlationData = {
    gcCpuCorrelations: [],
    gcPinningCorrelations: [],
    recommendations: []
};

// Per-second event counters for charts
export let currentSecondEvents = { start: 0, end: 0, pinned: 0 };
export let lastSecondTimestamp = Math.floor(Date.now() / 1000);

export function resetCurrentSecondEvents() {
    currentSecondEvents = { start: 0, end: 0, pinned: 0 };
}

export function updateLastSecondTimestamp(ts) {
    lastSecondTimestamp = ts;
}

/**
 * Update thread states from server THREAD_STATE_UPDATE message
 */
export function updateThreadStates(data) {
    // Clear current maps
    threadStates.running.clear();
    threadStates.pinned.clear();
    threadStates.ended.clear();

    // Update counts
    stateCounts.running = data.counts.running;
    stateCounts.pinned = data.counts.pinned;
    stateCounts.ended = data.counts.ended;

    // Populate maps
    for (const thread of data.threads) {
        const info = {
            threadId: thread.threadId,
            threadName: thread.threadName,
            carrierThread: thread.carrierThread,
            startTime: new Date(thread.startTime),
            endTime: thread.endTime ? new Date(thread.endTime) : null,
            isPinned: thread.isPinned
        };

        switch (thread.state) {
            case 'RUNNING':
                threadStates.running.set(thread.threadId, info);
                break;
            case 'PINNED':
                threadStates.pinned.set(thread.threadId, info);
                break;
            case 'ENDED':
                threadStates.ended.set(thread.threadId, info);
                break;
        }
    }
}

/**
 * Add to duration bucket for histogram
 */
export function addToDurationBucket(durationMs) {
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
