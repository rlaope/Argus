package io.argus.cli.llm;

import io.argus.cli.config.Messages;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the advisory LLM root-cause block. Always labelled as advisory and
 * always printed alongside the deterministic findings (the caller prints those
 * first). When no advisory is available, prints a short note explaining why and
 * that the deterministic output above stands on its own.
 */
public final class LlmAdvisoryRenderer {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    private LlmAdvisoryRenderer() {}

    public static void print(LlmRootCause.Result result, boolean color, Messages messages) {
        System.out.println(RichRenderer.boxSeparator(WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(color, AnsiStyle.BOLD, AnsiStyle.CYAN)
                        + messages.get("llm.rca.header")
                        + AnsiStyle.style(color, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (result.hasAdvisory()) {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(color, AnsiStyle.YELLOW)
                            + messages.get("llm.rca.advisoryLabel", result.providerName())
                            + AnsiStyle.style(color, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
            for (String line : wordWrap(result.advisory(), WIDTH - 6)) {
                System.out.println(RichRenderer.boxLine("  " + line, WIDTH));
            }
        } else {
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(color, AnsiStyle.DIM)
                            + messages.get("llm.rca.skipped", result.skippedReason())
                            + AnsiStyle.style(color, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(color, AnsiStyle.DIM)
                            + messages.get("llm.rca.deterministicStands")
                            + AnsiStyle.style(color, AnsiStyle.RESET), WIDTH));
        }
        System.out.println(RichRenderer.emptyLine(WIDTH));
    }

    private static List<String> wordWrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split("\\s+")) {
                if (current.length() == 0) {
                    current.append(word);
                } else if (current.length() + 1 + word.length() <= maxWidth) {
                    current.append(' ').append(word);
                } else {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                }
            }
            if (current.length() > 0) lines.add(current.toString());
        }
        return lines;
    }
}
