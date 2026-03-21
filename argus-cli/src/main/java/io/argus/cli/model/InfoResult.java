package io.argus.cli.model;

import java.util.List;
import java.util.Map;

/**
 * JVM information result.
 */
public record InfoResult(
        String vmName,
        String vmVersion,
        String vmVendor,
        long uptimeMs,
        long pid,
        List<String> vmFlags,
        Map<String, String> systemProperties
) {}
