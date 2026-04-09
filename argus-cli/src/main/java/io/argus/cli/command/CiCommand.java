package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.doctor.*;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.List;

/**
 * CI/CD health gate command. Runs doctor checks against a JVM and returns
 * machine-readable output with exit codes for build gating.
 *
 * <p>Usage:
 * <pre>
 * argus ci --pid=auto                          # auto-detect JVM
 * argus ci 12345 --fail-on=critical            # fail only on critical
 * argus ci 12345 --fail-on=warning             # fail on any warning
 * argus ci 12345 --format=github-annotations   # GitHub Actions format
 * argus ci 12345 --format=summary              # one-line pass/fail
 * </pre>
 *
 * <p>Exit codes: 0=pass, 1=warnings, 2=critical
 */
public final class CiCommand implements Command {

    @Override public String name() { return "ci"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }
    @Override public CommandMode mode() { return CommandMode.WRITE; }
    @Override public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.ci.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        long pid = 0;
        String failOn = "critical";
        String format = "summary";

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--pid=")) {
                String v = arg.substring(6);
                if (!"auto".equals(v)) {
                    try { pid = Long.parseLong(v); } catch (NumberFormatException ignored) {}
                }
            } else if (arg.startsWith("--fail-on=")) {
                failOn = arg.substring(10);
            } else if (arg.startsWith("--format=")) {
                format = arg.substring(9);
            } else if (!arg.startsWith("--")) {
                try { pid = Long.parseLong(arg); } catch (NumberFormatException ignored) {}
            }
        }

        JvmSnapshot snapshot = JvmSnapshotCollector.collect(pid);
        List<Finding> findings = DoctorEngine.diagnose(snapshot);
        int exitCode = DoctorEngine.exitCode(findings);

        // Adjust exit code based on --fail-on
        if ("critical".equals(failOn) && exitCode == 1) exitCode = 0; // warnings OK

        switch (format) {
            case "github-annotations" -> printGitHubAnnotations(findings);
            case "json" -> printJson(findings, exitCode);
            case "junit" -> printJunit(findings);
            default -> printSummary(findings, exitCode);
        }

        if (exitCode > 0) throw new CommandExitException(exitCode);
    }

    private void printSummary(List<Finding> findings, int exitCode) {
        long critical = findings.stream().filter(f -> f.severity() == Severity.CRITICAL).count();
        long warnings = findings.stream().filter(f -> f.severity() == Severity.WARNING).count();

        if (findings.isEmpty()) {
            System.out.println("PASS: all JVM health checks passed");
        } else {
            String status = exitCode > 0 ? "FAIL" : "PASS";
            System.out.println(status + ": " + critical + " critical, " + warnings + " warning(s)");
            for (Finding f : findings) {
                System.out.println("  [" + f.severity().label() + "] " + f.title());
            }
        }
    }

    private void printGitHubAnnotations(List<Finding> findings) {
        for (Finding f : findings) {
            String level = switch (f.severity()) {
                case CRITICAL -> "error";
                case WARNING -> "warning";
                case INFO -> "notice";
            };
            // GitHub Actions annotation format
            System.out.println("::" + level + " title=Argus " + f.category() + "::" + f.title());
            if (!f.detail().isEmpty()) {
                System.out.println("  " + f.detail());
            }
            for (String rec : f.recommendations()) {
                System.out.println("  → " + rec);
            }
        }
        if (findings.isEmpty()) {
            System.out.println("::notice title=Argus::All JVM health checks passed");
        }
    }

    private void printJson(List<Finding> findings, int exitCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"exitCode\":").append(exitCode);
        sb.append(",\"findings\":[");
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"severity\":\"").append(f.severity().label()).append('"');
            sb.append(",\"category\":\"").append(RichRenderer.escapeJson(f.category())).append('"');
            sb.append(",\"title\":\"").append(RichRenderer.escapeJson(f.title())).append('"');
            sb.append(",\"detail\":\"").append(RichRenderer.escapeJson(f.detail())).append('"');
            sb.append('}');
        }
        sb.append("]}");
        System.out.println(sb);
    }

    private void printJunit(List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        int failures = (int) findings.stream().filter(f -> f.severity() != Severity.INFO).count();
        sb.append("<testsuite name=\"argus-ci\" tests=\"").append(findings.isEmpty() ? 1 : findings.size())
                .append("\" failures=\"").append(failures).append("\">\n");

        if (findings.isEmpty()) {
            sb.append("  <testcase name=\"jvm-health\" classname=\"argus.ci\"/>\n");
        } else {
            for (Finding f : findings) {
                sb.append("  <testcase name=\"").append(escapeXml(f.category() + "-" + f.title()))
                        .append("\" classname=\"argus.ci\">\n");
                if (f.severity() != Severity.INFO) {
                    sb.append("    <failure message=\"").append(escapeXml(f.title())).append("\">");
                    sb.append(escapeXml(f.detail())).append("</failure>\n");
                }
                sb.append("  </testcase>\n");
            }
        }
        sb.append("</testsuite>");
        System.out.println(sb);
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
