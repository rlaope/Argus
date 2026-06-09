package io.argus.apm.model;

public enum ApmFindingKind {
    GC_PAUSE,
    GC_PRESSURE,
    MEMORY_LEAK,
    LOCK_CONTENTION,
    VIRTUAL_THREAD_PINNING,
    CPU_SATURATION,
    PROFILE_HOTSPOT,
    DEPLOYMENT_REGRESSION
}
