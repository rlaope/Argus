package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Explains JVM metrics, GC causes, and flags in plain English.
 */
public final class ExplainCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    private static final Properties KB = loadKnowledgeBase();

    private static Properties loadKnowledgeBase() {
        Properties p = new Properties();
        try (InputStream in = ExplainCommand.class.getResourceAsStream("/explain.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // empty knowledge base — explain will show "no match"
        }
        return p;
    }

    @Override
    public String name() { return "explain"; }

    @Override
    public CommandGroup group() { return CommandGroup.PROFILING; }

    @Override
    public CommandMode mode() { return CommandMode.READ; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.explain.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println("Usage: argus explain <term>");
            System.err.println("Examples:");
            System.err.println("  argus explain \"G1 Evacuation Pause\"");
            System.err.println("  argus explain -XX:MaxGCPauseMillis");
            System.err.println("  argus explain throughput");
            System.err.println("  argus explain gc-overhead");
            return;
        }

        boolean json = "json".equals(config.format());
        boolean useColor = config.color();

        // Collect the query (join remaining non-option args)
        StringBuilder queryBuilder = new StringBuilder();
        for (String arg : args) {
            if (arg.equals("--format=json")) {
                json = true;
            } else if (!arg.startsWith("--")) {
                if (queryBuilder.length() > 0) queryBuilder.append(' ');
                queryBuilder.append(arg);
            }
        }

        String query = queryBuilder.toString().trim();
        if (query.isEmpty()) {
            System.err.println("Usage: argus explain <term>");
            return;
        }

        // Look up: exact match first, then fuzzy
        String exactKey = "explain." + query;
        String explanation = KB.getProperty(exactKey);
        String matchedTerm = query;

        if (explanation == null) {
            // Fuzzy: find all keys whose suffix contains the query (case-insensitive)
            String lowerQuery = query.toLowerCase();
            List<String> fuzzyMatches = new ArrayList<>();
            for (String key : KB.stringPropertyNames()) {
                if (!key.startsWith("explain.")) continue;
                String term = key.substring("explain.".length());
                if (term.toLowerCase().contains(lowerQuery)) {
                    fuzzyMatches.add(term);
                }
            }

            if (fuzzyMatches.size() == 1) {
                matchedTerm = fuzzyMatches.get(0);
                explanation = KB.getProperty("explain." + matchedTerm);
            } else if (fuzzyMatches.size() > 1) {
                if (json) {
                    printJsonSuggestions(query, fuzzyMatches);
                } else {
                    printSuggestions(useColor, query, fuzzyMatches);
                }
                return;
            }
        }

        if (explanation == null) {
            if (json) {
                System.out.println("{\"query\":\"" + RichRenderer.escapeJson(query)
                        + "\",\"found\":false,\"explanation\":null}");
            } else {
                System.out.println(RichRenderer.boxHeader(useColor, "explain", WIDTH, "\"" + query + "\""));
                System.out.println(RichRenderer.emptyLine(WIDTH));
                System.out.println(RichRenderer.boxLine(
                        AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "No explanation found for: " + query
                                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
                System.out.println(RichRenderer.emptyLine(WIDTH));
                System.out.println(RichRenderer.boxLine("Try: argus explain gc-overhead", WIDTH));
                System.out.println(RichRenderer.boxLine("     argus explain throughput", WIDTH));
                System.out.println(RichRenderer.boxLine("     argus explain \"G1 Evacuation Pause\"", WIDTH));
                System.out.println(RichRenderer.emptyLine(WIDTH));
                System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
            }
            return;
        }

        if (json) {
            printJson(query, matchedTerm, explanation);
            return;
        }

        printExplanation(useColor, matchedTerm, explanation);
    }

    private static void printExplanation(boolean useColor, String term, String explanation) {
        System.out.println(RichRenderer.boxHeader(useColor, "explain", WIDTH, "\"" + term + "\""));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Term name in bold
        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(bold + term + reset, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Word-wrap explanation at (WIDTH - 4) characters
        int wrapAt = WIDTH - 4;
        for (String line : wordWrap(explanation, wrapAt)) {
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printSuggestions(boolean useColor, String query, List<String> matches) {
        System.out.println(RichRenderer.boxHeader(useColor, "explain", WIDTH, "\"" + query + "\""));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "Multiple matches found:"
                        + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        for (String match : matches) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(useColor, AnsiStyle.CYAN) + match
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        }
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine("Use a more specific term to get a full explanation.", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(String query, String term, String explanation) {
        System.out.println("{\"query\":\"" + RichRenderer.escapeJson(query)
                + "\",\"found\":true"
                + ",\"term\":\"" + RichRenderer.escapeJson(term) + "\""
                + ",\"explanation\":\"" + RichRenderer.escapeJson(explanation) + "\"}");
    }

    private static void printJsonSuggestions(String query, List<String> matches) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":\"").append(RichRenderer.escapeJson(query))
          .append("\",\"found\":false,\"suggestions\":[");
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(matches.get(i))).append('"');
        }
        sb.append("]}");
        System.out.println(sb);
    }

    /** Splits text into lines no longer than maxWidth, breaking on spaces. */
    private static List<String> wordWrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() == 0) {
                current.append(word);
            } else if (current.length() + 1 + word.length() <= maxWidth) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }
}
