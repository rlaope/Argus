package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.core.command.CommandGroup;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;

import java.time.Duration;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Real-time slow method detection via JFR streaming.
 * Streams method execution events that exceed a threshold duration.
 *
 * <p>Usage:
 * <pre>
 * argus slowlog 12345 --threshold 100ms
 * argus slowlog 12345 --filter com.example.*
 * argus slowlog 12345 --duration 60
 * </pre>
 */
public final class SlowlogCommand implements Command {

    @Override public String name() { return "slowlog"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }
    @Override public CommandMode mode() { return CommandMode.WRITE; }
    @Override public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.slowlog.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println("Usage: argus slowlog <pid> [--threshold 100] [--filter com.example.*] [--duration 60]");
            return;
        }

        long pid = CommandUtils.parsePidOrExit(args, messages);

        long thresholdMs = 100;
        String filter = "";
        int durationSec = 0; // 0 = infinite
        boolean useColor = config.color();
        boolean json = "json".equals(config.format());

        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--threshold=")) {
                try { thresholdMs = Long.parseLong(args[i].substring(12)); } catch (NumberFormatException ignored) {}
            } else if (args[i].equals("--threshold") && i + 1 < args.length) {
                try { thresholdMs = Long.parseLong(args[++i]); } catch (NumberFormatException ignored) {}
            } else if (args[i].startsWith("--filter=")) {
                filter = args[i].substring(9);
            } else if (args[i].startsWith("--duration=")) {
                try { durationSec = Integer.parseInt(args[i].substring(11)); } catch (NumberFormatException ignored) {}
            } else if (args[i].equals("--format=json")) {
                json = true;
            }
        }

        System.out.println();
        System.out.println("  " + AnsiStyle.style(useColor, AnsiStyle.BOLD, AnsiStyle.CYAN)
                + "argus slowlog" + AnsiStyle.style(useColor, AnsiStyle.RESET));
        System.out.println("  Monitoring PID " + pid + " | threshold: " + thresholdMs + "ms"
                + (filter.isEmpty() ? "" : " | filter: " + filter));
        System.out.println("  Press Ctrl+C to stop");
        System.out.println();

        // For local JVM, use JFR RecordingStream
        // For remote, use periodic jcmd JFR.dump + parse
        if (pid == ProcessHandle.current().pid()) {
            streamLocal(thresholdMs, filter, durationSec, useColor, json);
        } else {
            streamRemote(pid, thresholdMs, filter, durationSec, useColor, json);
        }
    }

    private void streamLocal(long thresholdMs, String filter, int durationSec,
                             boolean useColor, boolean json) {
        int feature = Runtime.version().feature();
        if (feature < 14) {
            System.err.println("argus slowlog: requires Java 14+ runtime (current: Java " + feature + ").");
            System.err.println("This command uses jdk.jfr.consumer.RecordingStream which was added in Java 14.");
            System.err.println("Most other argus commands work on Java 11+; only argus slowlog requires 14+.");
            return;
        }
        // RecordingStream is loaded reflectively to keep this source file compatible with --release 11.
        try {
            Class<?> rsClass = Class.forName("jdk.jfr.consumer.RecordingStream");
            // RecordingStream implements AutoCloseable
            try (AutoCloseable rs = (AutoCloseable) rsClass.getConstructor().newInstance()) {
                Method enable = rsClass.getMethod("enable", String.class);
                Class<?> esClass = enable.getReturnType(); // EventSettings
                Method withPeriod = esClass.getMethod("withPeriod", Duration.class);
                Method withThreshold = esClass.getMethod("withThreshold", Duration.class);

                withPeriod.invoke(enable.invoke(rs, "jdk.ExecutionSample"), Duration.ofMillis(20));
                withThreshold.invoke(enable.invoke(rs, "jdk.JavaMonitorEnter"), Duration.ofMillis(thresholdMs));
                withThreshold.invoke(enable.invoke(rs, "jdk.ThreadSleep"), Duration.ofMillis(thresholdMs));
                withThreshold.invoke(enable.invoke(rs, "jdk.FileRead"), Duration.ofMillis(thresholdMs));
                withThreshold.invoke(enable.invoke(rs, "jdk.FileWrite"), Duration.ofMillis(thresholdMs));
                withThreshold.invoke(enable.invoke(rs, "jdk.SocketRead"), Duration.ofMillis(thresholdMs));
                withThreshold.invoke(enable.invoke(rs, "jdk.SocketWrite"), Duration.ofMillis(thresholdMs));

                final String f = filter;
                final AutoCloseable rsRef = rs;
                // onEvent(RecordedEvent consumer)
                Method onEvent = rsClass.getMethod("onEvent", java.util.function.Consumer.class);
                onEvent.invoke(rs, (java.util.function.Consumer<RecordedEvent>) event -> {
                    String method = extractMethod(event);
                    if (method.isEmpty()) return;
                    if (!f.isEmpty() && !matchesFilter(method, f)) return;

                    long durationMs = event.getDuration().toMillis();
                    if (durationMs < thresholdMs && !event.getEventType().getName().equals("jdk.ExecutionSample")) return;

                    String tag = categorize(event.getEventType().getName(), method);
                    printEvent(method, durationMs, tag, event.getEventType().getName(), useColor, json);
                });

                if (durationSec > 0) {
                    Thread t = new Thread(() -> {
                        try {
                            Thread.sleep(durationSec * 1000L);
                            rsRef.close();
                        } catch (Exception ignored) {}
                    });
                    t.setDaemon(true);
                    t.start();
                }

                rsClass.getMethod("start").invoke(rs);
            }
        } catch (Exception e) {
            System.err.println("JFR streaming error: " + e.getMessage());
        }
    }

    private void streamRemote(long pid, long thresholdMs, String filter, int durationSec,
                              boolean useColor, boolean json) {
        // For remote JVMs: use jcmd JFR.start + periodic JFR.dump + parse
        System.out.println("  " + AnsiStyle.style(useColor, AnsiStyle.YELLOW)
                + "Remote slowlog uses periodic JFR dump (5s intervals)"
                + AnsiStyle.style(useColor, AnsiStyle.RESET));

        try {
            // Start JFR recording on target
            String jcmd = System.getProperty("java.home") + "/bin/jcmd";
            new ProcessBuilder(jcmd, String.valueOf(pid), "JFR.start",
                    "name=argus-slowlog", "settings=profile")
                    .redirectErrorStream(true).start().waitFor();

            int elapsed = 0;
            int interval = 5;
            String tmpFile = System.getProperty("java.io.tmpdir") + "/argus-slowlog-" + pid + ".jfr";

            while (durationSec == 0 || elapsed < durationSec) {
                Thread.sleep(interval * 1000L);
                elapsed += interval;

                // Dump and parse
                new ProcessBuilder(jcmd, String.valueOf(pid), "JFR.dump",
                        "name=argus-slowlog", "filename=" + tmpFile)
                        .redirectErrorStream(true).start().waitFor();

                // Parse events from dump
                try (var rf = new jdk.jfr.consumer.RecordingFile(java.nio.file.Path.of(tmpFile))) {
                    while (rf.hasMoreEvents()) {
                        RecordedEvent event = rf.readEvent();
                        long durationMs = event.getDuration().toMillis();
                        if (durationMs < thresholdMs) continue;

                        String method = extractMethod(event);
                        if (method.isEmpty()) continue;
                        if (!filter.isEmpty() && !matchesFilter(method, filter)) continue;

                        String tag = categorize(event.getEventType().getName(), method);
                        printEvent(method, durationMs, tag, event.getEventType().getName(), useColor, json);
                    }
                }
            }

            // Stop recording
            new ProcessBuilder(jcmd, String.valueOf(pid), "JFR.stop",
                    "name=argus-slowlog")
                    .redirectErrorStream(true).start().waitFor();

        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            System.err.println("Remote slowlog error: " + e.getMessage());
        }
    }

    private static String extractMethod(RecordedEvent event) {
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null || stack.getFrames().isEmpty()) return "";
        RecordedFrame frame = stack.getFrames().get(0);
        RecordedMethod method = frame.getMethod();
        if (method == null) return "";
        return method.getType().getName() + "." + method.getName();
    }

    private static boolean matchesFilter(String method, String filter) {
        String regex = filter.replace(".", "\\.").replace("*", ".*");
        return method.matches(regex);
    }

    private static String categorize(String eventType, String method) {
        switch (eventType) {
            case "jdk.JavaMonitorEnter": return "\u26a0 Lock";
            case "jdk.FileRead":
            case "jdk.FileWrite": return "\u26a0 I/O";
            case "jdk.SocketRead":
            case "jdk.SocketWrite": return "\u26a0 Network";
            case "jdk.ThreadSleep": return "Sleep";
            default: {
                String lower = method.toLowerCase();
                if (lower.contains("jdbc") || lower.contains("sql") || lower.contains("datasource"))
                    return "\u26a0 SQL?";
                if (lower.contains("http") || lower.contains("socket") || lower.contains("rest"))
                    return "\u26a0 Network?";
                return "";
            }
        }
    }

    private static void printEvent(String method, long durationMs, String tag,
                                   String eventType, boolean useColor, boolean json) {
        if (json) {
            System.out.printf("{\"time\":\"%tT\",\"method\":\"%s\",\"durationMs\":%d,\"tag\":\"%s\"}%n",
                    System.currentTimeMillis(), method, durationMs, tag);
            return;
        }

        String timeStr = String.format("%tT", System.currentTimeMillis());
        String durColor = durationMs > 500
                ? AnsiStyle.style(useColor, AnsiStyle.RED)
                : durationMs > 200
                ? AnsiStyle.style(useColor, AnsiStyle.YELLOW)
                : AnsiStyle.style(useColor, AnsiStyle.GREEN);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);

        System.out.printf("  %s[%s]%s  %-50s  %s%6dms%s  %s%n",
                AnsiStyle.style(useColor, AnsiStyle.DIM), timeStr, reset,
                truncate(method, 50),
                durColor, durationMs, reset,
                tag);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : "..." + s.substring(s.length() - max + 3);
    }
}
