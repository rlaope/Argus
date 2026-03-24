package io.argus.cli.model;

import java.util.List;

/**
 * Snapshot of VM flags from jcmd VM.flags.
 */
public final class VmFlagResult {
    private final List<VmFlag> flags;

    public VmFlagResult(List<VmFlag> flags) {
        this.flags = flags;
    }

    public List<VmFlag> flags() { return flags; }

    public static final class VmFlag {
        private final String name;
        private final String value;

        public VmFlag(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String name() { return name; }
        public String value() { return value; }
    }
}
