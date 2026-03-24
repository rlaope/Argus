package io.argus.cli.model;

import java.util.List;

/**
 * Snapshot of VM flags from jcmd VM.flags.
 */
public record VmFlagResult(List<VmFlag> flags) {
    public record VmFlag(String name, String value) {}
}
