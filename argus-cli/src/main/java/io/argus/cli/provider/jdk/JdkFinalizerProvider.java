package io.argus.cli.provider.jdk;

import io.argus.cli.model.FinalizerResult;
import io.argus.cli.provider.FinalizerProvider;

/**
 * FinalizerProvider that uses {@code jcmd GC.finalizer_info}.
 */
public final class JdkFinalizerProvider implements FinalizerProvider {

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() { return 10; }

    @Override
    public String source() { return "jdk"; }

    @Override
    public FinalizerResult getFinalizerInfo(long pid) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "GC.finalizer_info");
        } catch (RuntimeException e) {
            return new FinalizerResult(0, "UNKNOWN");
        }
        return parseOutput(output);
    }

    static FinalizerResult parseOutput(String output) {
        int pendingCount = 0;
        String threadState = "UNKNOWN";

        for (String line : output.split("\n")) {
            String trimmed = line.trim().toLowerCase();

            if (trimmed.contains("no pending finalizer") || trimmed.contains("0 pending")) {
                pendingCount = 0;
            } else if (trimmed.contains("pending")) {
                // Try to extract count: "N pending finalizers" or "pending: N"
                for (String token : trimmed.split("\\s+")) {
                    try {
                        pendingCount = Integer.parseInt(token);
                        break;
                    } catch (NumberFormatException ignored) {}
                }
            }

            // Finalizer thread state
            String upper = line.trim();
            if (upper.contains("Finalizer thread")) {
                if (upper.contains("WAITING")) threadState = "WAITING";
                else if (upper.contains("RUNNABLE")) threadState = "RUNNABLE";
                else if (upper.contains("BLOCKED")) threadState = "BLOCKED";
                else if (upper.contains("idle")) threadState = "WAITING";
                else if (upper.contains("running")) threadState = "RUNNABLE";
            }
        }

        return new FinalizerResult(pendingCount, threadState);
    }
}
