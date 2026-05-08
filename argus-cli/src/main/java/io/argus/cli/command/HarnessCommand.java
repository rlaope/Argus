package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.harness.HarnessEngine;
import io.argus.cli.harness.HarnessProfile;
import io.argus.cli.harness.HarnessReporter;
import io.argus.cli.harness.HarnessResult;
import io.argus.cli.harness.TrendRule;
import io.argus.cli.harness.rules.GcOverheadTrendRule;
import io.argus.cli.harness.rules.HeapGrowthLeakRule;
import io.argus.cli.harness.rules.PauseTrendRule;
import io.argus.cli.harness.rules.ThreadGrowthRule;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.core.command.CommandGroup;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * {@code argus harness <pid>} — continuous JVM monitoring + optimization +
 * troubleshooting. Runs the existing doctor rules plus a small set of
 * trend rules on a sampled time window and emits a session report.
 *
 * <p>This command is observation-only: no JVM mutation, no jcmd writes
 * beyond what doctor already does. Findings produce recommendations.
 */
public final class HarnessCommand implements Command {

    private static final List<TrendRule> TREND_RULES = List.of(
            new HeapGrowthLeakRule(),
            new GcOverheadTrendRule(),
            new ThreadGrowthRule(),
            new PauseTrendRule()
    );

    @Override
    public String name() { return "harness"; }

    @Override
    public CommandGroup group() { return CommandGroup.MONITORING; }

    @Override
    public String description(Messages messages) { return messages.get("cmd.harness.desc"); }

    @Override
    public boolean supportsTui() { return false; }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        boolean json = "json".equals(config.format());
        boolean color = config.color();
        long pid = 0;
        Duration interval = null;
        Duration duration = null;
        Path outFile = null;
        HarnessProfile profile = HarnessProfile.DEEP;

        for (String arg : args) {
            if (arg.startsWith("--interval=")) {
                interval = parseDuration(arg.substring(11));
            } else if (arg.startsWith("--duration=")) {
                duration = parseDuration(arg.substring(11));
            } else if (arg.startsWith("--profile=")) {
                profile = HarnessProfile.parse(arg.substring(10));
            } else if (arg.startsWith("--out=")) {
                outFile = Path.of(arg.substring(6));
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (!arg.startsWith("--")) {
                try { pid = Long.parseLong(arg); } catch (NumberFormatException ignored) {}
            }
        }

        if (interval == null) interval = profile.interval();
        if (duration == null) duration = profile.defaultDuration();

        if (!json) {
            System.out.println(String.format(messages.get("harness.starting"),
                    String.valueOf(pid > 0 ? pid : ProcessHandle.current().pid()),
                    interval.toString(), duration.toString(), profile.name().toLowerCase()));
        }

        HarnessReporter reporter = new HarnessReporter(color, json, System.out);
        HarnessEngine engine = new HarnessEngine(pid, profile, interval, duration,
                TREND_RULES, reporter::onTick);
        HarnessResult result = engine.runUntilDone();
        reporter.onComplete(result, outFile);

        if (result.exitCode() > 0) throw new CommandExitException(result.exitCode());
    }

    static Duration parseDuration(String s) {
        if (s == null || s.isEmpty()) throw new IllegalArgumentException("empty duration");
        char unit = s.charAt(s.length() - 1);
        if (Character.isDigit(unit)) {
            return Duration.ofSeconds(Long.parseLong(s));
        }
        long n = Long.parseLong(s.substring(0, s.length() - 1));
        switch (unit) {
            case 's': return Duration.ofSeconds(n);
            case 'm': return Duration.ofMinutes(n);
            case 'h': return Duration.ofHours(n);
            default: throw new IllegalArgumentException("Unknown duration unit: " + unit);
        }
    }
}
