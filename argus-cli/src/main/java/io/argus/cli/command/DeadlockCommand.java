package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.DeadlockResult;
import io.argus.cli.provider.DeadlockProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

/**
 * Detects and displays Java-level deadlocks for a given PID.
 */
public final class DeadlockCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "deadlock";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.deadlock.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println(messages.get("error.pid.required"));
            return;
        }

        long pid;
        try {
            pid = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println(messages.get("error.pid.invalid", args[0]));
            return;
        }

        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            }
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        DeadlockProvider provider = registry.findDeadlockProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        DeadlockResult result = provider.detectDeadlocks(pid);

        if (json) {
            printJson(result);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "deadlock", messages.get("desc.deadlock")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.deadlock"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (result.deadlockCount() == 0) {
            String ok = AnsiStyle.style(useColor, AnsiStyle.GREEN) + "\u2714 "
                    + messages.get("deadlock.none")
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(ok, WIDTH));
        } else {
            String warn = AnsiStyle.style(useColor, AnsiStyle.RED, AnsiStyle.BOLD) + "\u26a0 "
                    + messages.get("deadlock.found", result.deadlockCount())
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(warn, WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));

            int chainNum = 1;
            for (DeadlockResult.DeadlockChain chain : result.chains()) {
                String chainHeader = AnsiStyle.style(useColor, AnsiStyle.BOLD, AnsiStyle.YELLOW)
                        + messages.get("deadlock.chain", chainNum, chain.threads().size())
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine(chainHeader, WIDTH));

                for (DeadlockResult.DeadlockThread t : chain.threads()) {
                    System.out.println(RichRenderer.emptyLine(WIDTH));

                    // Thread name and state
                    String nameLine = "  " + AnsiStyle.style(useColor, AnsiStyle.CYAN)
                            + RichRenderer.truncate(t.name(), 40)
                            + AnsiStyle.style(useColor, AnsiStyle.RESET)
                            + "  [" + t.state() + "]";
                    System.out.println(RichRenderer.boxLine(nameLine, WIDTH));

                    // Waiting for lock
                    if (!t.waitingLock().isEmpty()) {
                        String waitLine = "    " + messages.get("deadlock.waiting")
                                + " " + AnsiStyle.style(useColor, AnsiStyle.RED)
                                + t.waitingLock()
                                + AnsiStyle.style(useColor, AnsiStyle.RESET);
                        if (!t.waitingLockClass().isEmpty()) {
                            waitLine += "  (" + t.waitingLockClass() + ")";
                        }
                        System.out.println(RichRenderer.boxLine(waitLine, WIDTH));
                    }

                    // Held lock
                    if (!t.heldLock().isEmpty()) {
                        String heldLine = "    " + messages.get("deadlock.holding")
                                + " " + AnsiStyle.style(useColor, AnsiStyle.GREEN)
                                + t.heldLock()
                                + AnsiStyle.style(useColor, AnsiStyle.RESET);
                        if (!t.heldLockClass().isEmpty()) {
                            heldLine += "  (" + t.heldLockClass() + ")";
                        }
                        System.out.println(RichRenderer.boxLine(heldLine, WIDTH));
                    }

                    // Stack top
                    if (!t.stackTop().isEmpty()) {
                        String stackLine = "    at " + AnsiStyle.style(useColor, AnsiStyle.DIM)
                                + RichRenderer.truncate(t.stackTop(), WIDTH - 14)
                                + AnsiStyle.style(useColor, AnsiStyle.RESET);
                        System.out.println(RichRenderer.boxLine(stackLine, WIDTH));
                    }
                }

                System.out.println(RichRenderer.emptyLine(WIDTH));
                if (chainNum < result.deadlockCount()) {
                    System.out.println(RichRenderer.boxSeparator(WIDTH));
                }
                chainNum++;
            }
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(DeadlockResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"deadlockCount\":").append(result.deadlockCount());
        sb.append(",\"chains\":[");
        for (int c = 0; c < result.chains().size(); c++) {
            DeadlockResult.DeadlockChain chain = result.chains().get(c);
            if (c > 0) sb.append(',');
            sb.append("{\"threads\":[");
            for (int t = 0; t < chain.threads().size(); t++) {
                DeadlockResult.DeadlockThread thread = chain.threads().get(t);
                if (t > 0) sb.append(',');
                sb.append("{\"name\":\"").append(RichRenderer.escapeJson(thread.name())).append('"');
                sb.append(",\"state\":\"").append(RichRenderer.escapeJson(thread.state())).append('"');
                sb.append(",\"waitingLock\":\"").append(RichRenderer.escapeJson(thread.waitingLock())).append('"');
                sb.append(",\"waitingLockClass\":\"").append(RichRenderer.escapeJson(thread.waitingLockClass())).append('"');
                sb.append(",\"heldLock\":\"").append(RichRenderer.escapeJson(thread.heldLock())).append('"');
                sb.append(",\"heldLockClass\":\"").append(RichRenderer.escapeJson(thread.heldLockClass())).append('"');
                sb.append(",\"stackTop\":\"").append(RichRenderer.escapeJson(thread.stackTop())).append('"');
                sb.append('}');
            }
            sb.append("]}");
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
