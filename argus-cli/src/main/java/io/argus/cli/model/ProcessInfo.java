package io.argus.cli.model;

/**
 * Information about a running JVM process.
 */
public record ProcessInfo(long pid, String mainClass, String arguments) {}
