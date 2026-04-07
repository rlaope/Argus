package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.ClassLoaderResult;
import io.argus.cli.model.ClassLoaderResult.ClassLoaderInfo;
import io.argus.cli.provider.ClassLoaderProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Shows the classloader hierarchy and total loaded class count.
 */
public final class ClassLoaderCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "classloader";
    }

    @Override public CommandGroup group() { return CommandGroup.RUNTIME; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.classloader.desc");
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

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            }
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        ClassLoaderProvider provider = registry.findClassLoaderProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        ClassLoaderResult result = provider.getClassLoaders(pid);

        if (json) {
            printJson(result);
        } else {
            boolean useColor = config.color();
            System.out.print(RichRenderer.brandedHeader(useColor, "classloader",
                    messages.get("desc.classloader")));
            printTree(result, pid, source, useColor, messages);
        }
    }

    private static void printTree(ClassLoaderResult result, long pid, String source,
                                  boolean useColor, Messages messages) {
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.classloader"),
                WIDTH, "pid:" + pid, "source:" + source));

        if (result.loaders().isEmpty()) {
            System.out.println(RichRenderer.boxLine("(no classloader data available)", WIDTH));
        } else {
            // Render hierarchy with indentation based on parent chain depth
            int[] depths = computeDepths(result);
            for (int i = 0; i < result.loaders().size(); i++) {
                ClassLoaderInfo loader = result.loaders().get(i);
                int depth = depths[i];
                String indent = "  ".repeat(depth);
                String connector = depth == 0 ? "" : "+-- ";
                String countSuffix = loader.classCount() > 0
                        ? AnsiStyle.style(useColor, AnsiStyle.DIM)
                          + " (" + loader.classCount() + " classes)"
                          + AnsiStyle.style(useColor, AnsiStyle.RESET)
                        : "";
                String line = indent + connector
                        + AnsiStyle.style(useColor, AnsiStyle.BOLD)
                        + RichRenderer.humanClassName(loader.name())
                        + AnsiStyle.style(useColor, AnsiStyle.RESET)
                        + countSuffix;
                System.out.println(RichRenderer.boxLine(line, WIDTH));
            }
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        String totalLine = AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Total loaded classes: "
                + AnsiStyle.style(useColor, AnsiStyle.RESET)
                + result.totalClasses();
        System.out.println(RichRenderer.boxLine(totalLine, WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    /**
     * Computes the tree depth of each loader entry by walking the parent chain.
     * Depth 0 = root (no parent). Depth increases by 1 per level.
     */
    private static int[] computeDepths(ClassLoaderResult result) {
        int size = result.loaders().size();
        int[] depths = new int[size];
        for (int i = 0; i < size; i++) {
            String parent = result.loaders().get(i).parent();
            if (parent == null) {
                depths[i] = 0;
            } else {
                // Find the most recent loader whose name matches parent
                int parentDepth = 0;
                for (int j = i - 1; j >= 0; j--) {
                    if (parent.equals(result.loaders().get(j).name())) {
                        parentDepth = depths[j];
                        break;
                    }
                }
                depths[i] = parentDepth + 1;
            }
        }
        return depths;
    }

    private static void printJson(ClassLoaderResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"loaders\":[");
        boolean first = true;
        for (ClassLoaderInfo loader : result.loaders()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":\"").append(RichRenderer.escapeJson(loader.name())).append('"');
            sb.append(",\"classCount\":").append(loader.classCount());
            if (loader.parent() != null) {
                sb.append(",\"parent\":\"").append(RichRenderer.escapeJson(loader.parent())).append('"');
            } else {
                sb.append(",\"parent\":null");
            }
            sb.append('}');
        }
        sb.append("],\"totalClasses\":").append(result.totalClasses()).append('}');
        System.out.println(sb);
    }
}
