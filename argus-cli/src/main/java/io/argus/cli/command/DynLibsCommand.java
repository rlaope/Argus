package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.DynLibsResult;
import io.argus.cli.provider.DynLibsProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Shows native libraries loaded in the JVM.
 */
public final class DynLibsCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() { return "dynlibs"; }

    @Override public CommandGroup group() { return CommandGroup.RUNTIME; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.dynlibs.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) { System.err.println(messages.get("error.pid.required")); return; }

        long pid;
        try { pid = Long.parseLong(args[0]); }
        catch (NumberFormatException e) { System.err.println(messages.get("error.pid.invalid", args[0])); return; }

        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        String filter = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--source=")) sourceOverride = args[i].substring(9);
            else if (args[i].equals("--format=json")) json = true;
            else if (args[i].startsWith("--filter=")) filter = args[i].substring(9).toLowerCase();
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        DynLibsProvider provider = registry.findDynLibsProvider(pid, sourceOverride);
        if (provider == null) { System.err.println(messages.get("error.provider.none", pid)); return; }

        DynLibsResult result = provider.getDynLibs(pid);

        if (json) { printJson(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "dynlibs", messages.get("desc.dynlibs")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.dynlibs"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Count by category
        int jdkCount = 0, appCount = 0, sysCount = 0;
        for (DynLibsResult.LibInfo lib : result.libraries()) {
            switch (lib.category()) {
                case "jdk" -> jdkCount++;
                case "app" -> appCount++;
                default -> sysCount++;
            }
        }

        String summary = messages.get("label.total") + ": " + result.totalCount()
                + "  (JDK: " + jdkCount + "  App: " + appCount + "  System: " + sysCount + ")";
        System.out.println(RichRenderer.boxLine(summary, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // List by category
        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);

        String[] categories = {"app", "jdk", "system"};
        String[] labels = {"App", "JDK", "System"};
        String[] colors = {AnsiStyle.style(useColor, AnsiStyle.CYAN), AnsiStyle.style(useColor, AnsiStyle.GREEN), AnsiStyle.style(useColor, AnsiStyle.DIM)};

        for (int c = 0; c < categories.length; c++) {
            boolean hasEntries = false;
            for (DynLibsResult.LibInfo lib : result.libraries()) {
                if (!lib.category().equals(categories[c])) continue;
                if (filter != null && !lib.path().toLowerCase().contains(filter)) continue;

                if (!hasEntries) {
                    System.out.println(RichRenderer.boxLine(bold + labels[c] + reset, WIDTH));
                    hasEntries = true;
                }

                String path = RichRenderer.truncate(lib.path(), WIDTH - 8);
                System.out.println(RichRenderer.boxLine("  " + colors[c] + path + reset, WIDTH));
            }
            if (hasEntries) {
                System.out.println(RichRenderer.emptyLine(WIDTH));
            }
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(DynLibsResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"totalCount\":").append(result.totalCount()).append(",\"libraries\":[");
        for (int i = 0; i < result.libraries().size(); i++) {
            DynLibsResult.LibInfo lib = result.libraries().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"path\":\"").append(RichRenderer.escapeJson(lib.path())).append('"');
            sb.append(",\"category\":\"").append(lib.category()).append("\"}");
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
