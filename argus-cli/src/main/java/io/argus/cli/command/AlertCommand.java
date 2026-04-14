package io.argus.cli.command;

import io.argus.cli.alert.AlertEngine;
import io.argus.cli.alert.AlertRule;
import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitors Argus instances via their /prometheus endpoint and sends webhook
 * alerts when metric thresholds are breached.
 *
 * <p>Usage:
 * <pre>
 *   argus alert localhost:9202 --gc-overhead=10 --leak --webhook=https://hooks.slack.com/xxx
 *   argus alert --config=alerts.yml
 * </pre>
 *
 * <p>Config file format (simple key=value, no YAML library required):
 * <pre>
 *   target=localhost:9202
 *   interval=30
 *   rule.gc-overhead.metric=argus_gc_overhead_ratio
 *   rule.gc-overhead.threshold=0.10
 *   rule.gc-overhead.severity=warning
 *   rule.leak.metric=argus_gc_leak_suspected
 *   rule.leak.threshold=1
 *   rule.leak.severity=critical
 *   webhook=https://hooks.slack.com/xxx
 * </pre>
 */
public final class AlertCommand implements Command {

    private static final int DEFAULT_INTERVAL = 30;

    @Override
    public String name() { return "alert"; }

    @Override
    public CommandGroup group() { return CommandGroup.MONITORING; }

    @Override
    public CommandMode mode() { return CommandMode.WRITE; }

    @Override
    public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.alert.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        String configFile = null;
        String target = null;
        String webhook = null;
        int interval = DEFAULT_INTERVAL;
        double gcOverhead = -1;
        boolean leak = false;

        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                configFile = arg.substring(9);
            } else if (arg.startsWith("--gc-overhead=")) {
                try { gcOverhead = Double.parseDouble(arg.substring(14)); } catch (NumberFormatException ignored) {}
            } else if (arg.equals("--leak")) {
                leak = true;
            } else if (arg.startsWith("--webhook=")) {
                webhook = arg.substring(10);
            } else if (arg.startsWith("--interval=")) {
                try { interval = Integer.parseInt(arg.substring(11)); } catch (NumberFormatException ignored) {}
            } else if (!arg.startsWith("--")) {
                target = arg;
            }
        }

        List<AlertRule> rules;
        if (configFile != null) {
            ConfigResult cfg = loadConfig(configFile);
            if (cfg == null) return;
            target = cfg.target != null ? cfg.target : target;
            interval = cfg.interval > 0 ? cfg.interval : interval;
            webhook = cfg.webhook != null ? cfg.webhook : webhook;
            rules = cfg.rules;
        } else {
            rules = buildRulesFromFlags(gcOverhead, leak, webhook);
        }

        if (target == null || target.isBlank()) {
            System.err.println("Error: no target specified. Provide host:port or use --config=FILE");
            printHelp();
            return;
        }

        if (rules.isEmpty()) {
            System.err.println("Error: no alert rules configured. Use --gc-overhead=N, --leak, or --config=FILE");
            printHelp();
            return;
        }

        AlertEngine engine = new AlertEngine(target, interval, rules, config.color());
        engine.run();
    }

    // ── Rule construction from CLI flags ────────────────────────────────────

    private List<AlertRule> buildRulesFromFlags(double gcOverhead, boolean leak, String webhook) {
        List<AlertRule> rules = new ArrayList<>();
        if (gcOverhead >= 0) {
            // User passes percentage (10 = 10%), Prometheus metric is a ratio (0.10)
            rules.add(new AlertRule(
                    "gc-overhead",
                    "argus_gc_overhead_ratio",
                    gcOverhead / 100.0,
                    ">",
                    "warning",
                    webhook));
        }
        if (leak) {
            rules.add(new AlertRule(
                    "leak",
                    "argus_gc_leak_suspected",
                    1.0,
                    ">=",
                    "critical",
                    webhook));
        }
        return rules;
    }

    // ── Config file loading ─────────────────────────────────────────────────

    private ConfigResult loadConfig(String filePath) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(filePath));
        } catch (IOException e) {
            System.err.println("Error: cannot read config file '" + filePath + "': " + e.getMessage());
            return null;
        }

        String target = null;
        int interval = DEFAULT_INTERVAL;
        String webhook = null;

        // Collect rule sub-keys: rule.<name>.<field>=<value>
        Map<String, Map<String, String>> ruleProps = new HashMap<>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();

            if ("target".equals(key)) {
                target = value;
            } else if ("interval".equals(key)) {
                try { interval = Integer.parseInt(value); } catch (NumberFormatException ignored) {}
            } else if ("webhook".equals(key)) {
                webhook = value;
            } else if (key.startsWith("rule.")) {
                // rule.<name>.<field>
                String rest = key.substring(5); // <name>.<field>
                int dot = rest.lastIndexOf('.');
                if (dot < 0) continue;
                String ruleName = rest.substring(0, dot);
                String field = rest.substring(dot + 1);
                ruleProps.computeIfAbsent(ruleName, k -> new HashMap<>()).put(field, value);
            }
        }

        List<AlertRule> rules = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : ruleProps.entrySet()) {
            String ruleName = entry.getKey();
            Map<String, String> props = entry.getValue();
            String metric = props.get("metric");
            String thresholdStr = props.get("threshold");
            String severity = props.getOrDefault("severity", "warning");
            String comparator = props.getOrDefault("comparator", ">");
            String ruleWebhook = props.getOrDefault("webhook", webhook);

            if (metric == null || thresholdStr == null) {
                System.err.println("Warning: rule '" + ruleName + "' missing metric or threshold — skipped");
                continue;
            }
            double threshold;
            try {
                threshold = Double.parseDouble(thresholdStr);
            } catch (NumberFormatException e) {
                System.err.println("Warning: rule '" + ruleName + "' invalid threshold '" + thresholdStr + "' — skipped");
                continue;
            }
            rules.add(new AlertRule(ruleName, metric, threshold, comparator, severity, ruleWebhook));
        }

        return new ConfigResult(target, interval, webhook, rules);
    }

    private record ConfigResult(String target, int interval, String webhook, List<AlertRule> rules) {}

    // ── Help ────────────────────────────────────────────────────────────────

    private static void printHelp() {
        System.out.println();
        System.out.println("Usage: argus alert <target> [options]");
        System.out.println("       argus alert --config=FILE");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --gc-overhead=N    Alert when GC overhead exceeds N% (e.g. 10)");
        System.out.println("  --leak             Alert when memory leak is suspected");
        System.out.println("  --webhook=URL      Webhook URL for notifications");
        System.out.println("  --interval=N       Poll interval in seconds (default: 30)");
        System.out.println("  --config=FILE      Load rules from config file");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  argus alert localhost:9202 --gc-overhead=10 --webhook=https://hooks.slack.com/xxx");
        System.out.println("  argus alert localhost:9202 --leak --interval=60");
        System.out.println("  argus alert --config=alerts.yml");
    }
}
