package io.argus.cli.model;

/**
 * Result of a JFR Flight Recorder control command.
 */
public record JfrResult(String status, String message, String recordingInfo) {}
