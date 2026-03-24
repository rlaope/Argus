package io.argus.cli.model;

import java.util.Map;

/**
 * Snapshot of JVM system properties from jcmd VM.system_properties.
 */
public record SysPropsResult(Map<String, String> properties) {}
