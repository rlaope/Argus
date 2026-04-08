package io.argus.cli.tui;

import io.argus.cli.command.Command;
import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.core.command.CommandGroup;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

/**
 * k9s-style interactive terminal UI for Argus.
 * Browse commands by category, see descriptions, execute with Enter.
 */
public final class TuiApp {

    private final Map<String, Command> commands;
    private final CliConfig config;
    private final ProviderRegistry registry;
    private final Messages messages;
    private final long targetPid;

    // State
    private final List<CommandEntry> allEntries = new ArrayList<>();
    private final List<CommandEntry> filtered = new ArrayList<>();
    private int selectedIdx = 0;
    private int scrollOffset = 0;
    private String searchQuery = "";
    private boolean searching = false;
    private String lastOutput = "";
    private boolean showOutput = false;
    private boolean running = true;

    public TuiApp(Map<String, Command> commands, CliConfig config,
                  ProviderRegistry registry, Messages messages, long targetPid) {
        this.commands = commands;
        this.config = config;
        this.registry = registry;
        this.messages = messages;
        this.targetPid = targetPid;
        buildEntries();
    }

    private void buildEntries() {
        Map<CommandGroup, List<Command>> grouped = new LinkedHashMap<>();
        for (CommandGroup g : CommandGroup.values()) grouped.put(g, new ArrayList<>());
        for (Command cmd : commands.values()) grouped.get(cmd.group()).add(cmd);

        for (var entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            allEntries.add(new CommandEntry(null, entry.getKey().displayName(), true));
            for (Command cmd : entry.getValue()) {
                allEntries.add(new CommandEntry(cmd, cmd.name(), false));
            }
        }
        applyFilter();
    }

    private void applyFilter() {
        filtered.clear();
        if (searchQuery.isEmpty()) {
            filtered.addAll(allEntries);
        } else {
            String q = searchQuery.toLowerCase();
            for (CommandEntry e : allEntries) {
                if (e.isHeader) {
                    filtered.add(e);
                } else if (e.name.toLowerCase().contains(q)
                        || e.cmd.description(messages).toLowerCase().contains(q)) {
                    filtered.add(e);
                }
            }
            // Remove orphan headers
            filtered.removeIf(e -> e.isHeader && (filtered.indexOf(e) == filtered.size() - 1
                    || filtered.get(filtered.indexOf(e) + 1).isHeader));
        }
        selectedIdx = Math.min(selectedIdx, Math.max(0, filtered.size() - 1));
        // Skip headers
        while (selectedIdx < filtered.size() && filtered.get(selectedIdx).isHeader) selectedIdx++;
    }

    public void run() {
        try (Terminal terminal = TerminalBuilder.builder()
                .system(true).jansi(true).build()) {
            terminal.enterRawMode();
            NonBlockingReader reader = terminal.reader();

            while (running) {
                int height = terminal.getHeight() > 0 ? terminal.getHeight() : 30;
                int width = terminal.getWidth() > 0 ? terminal.getWidth() : 80;
                render(terminal, width, height);

                int key = reader.read(100);
                if (key == -2) continue; // timeout
                if (key == -1) break;

                handleKey(key, reader);
            }

            // Restore
            terminal.writer().print("\033[2J\033[H\033[?25h");
            terminal.writer().flush();
        } catch (Exception e) {
            System.err.println("TUI error: " + e.getMessage());
        }
    }

    private void handleKey(int key, NonBlockingReader reader) throws Exception {
        if (searching) {
            if (key == 27 || key == 10 || key == 13) { // Esc or Enter
                searching = false;
            } else if (key == 127 || key == 8) { // Backspace
                if (!searchQuery.isEmpty()) {
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                    applyFilter();
                }
            } else if (key >= 32 && key < 127) {
                searchQuery += (char) key;
                applyFilter();
            }
            return;
        }

        switch (key) {
            case 'q', 'Q' -> running = false;
            case '/' -> { searching = true; searchQuery = ""; }
            case 27 -> { // Escape — back from output
                if (showOutput) showOutput = false;
                else if (!searchQuery.isEmpty()) { searchQuery = ""; applyFilter(); }
            }
            case 'j', 66 -> moveDown(); // j or ↓ (66 = arrow down after ESC[)
            case 'k', 65 -> moveUp();   // k or ↑
            case 10, 13 -> executeSelected(); // Enter
            case 'p', 'P' -> {} // PID change — future
            case '?' -> {} // Help — future
            default -> {
                // Handle arrow key sequences: ESC [ A/B
                if (key == 27) {
                    int next = reader.read(50);
                    if (next == '[') {
                        int arrow = reader.read(50);
                        if (arrow == 'A') moveUp();
                        else if (arrow == 'B') moveDown();
                    }
                }
            }
        }
    }

    private void moveDown() {
        int next = selectedIdx + 1;
        while (next < filtered.size() && filtered.get(next).isHeader) next++;
        if (next < filtered.size()) selectedIdx = next;
    }

    private void moveUp() {
        int prev = selectedIdx - 1;
        while (prev >= 0 && filtered.get(prev).isHeader) prev--;
        if (prev >= 0) selectedIdx = prev;
    }

    private void executeSelected() {
        if (selectedIdx >= filtered.size()) return;
        CommandEntry entry = filtered.get(selectedIdx);
        if (entry.isHeader || entry.cmd == null) return;

        // Capture command output
        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture));
        try {
            String[] args = targetPid > 0 ? new String[]{String.valueOf(targetPid)} : new String[0];
            entry.cmd.execute(args, config, registry, messages);
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        } finally {
            System.setOut(original);
        }
        lastOutput = capture.toString();
        showOutput = true;
    }

    private void render(Terminal terminal, int width, int height) {
        StringBuilder sb = new StringBuilder();
        sb.append("\033[?25l"); // hide cursor
        sb.append("\033[H");   // home

        // Header
        String title = " \033[1;36m⚡ Argus Interactive\033[0m";
        String pidInfo = targetPid > 0 ? "  \033[2mpid:" + targetPid + "\033[0m" : "";
        sb.append("\033[2K").append(title).append(pidInfo);
        sb.append("  \033[2m").append(commands.size()).append(" commands\033[0m\n");
        sb.append("\033[2K\033[36m").append("─".repeat(Math.min(width, 80))).append("\033[0m\n");

        if (showOutput) {
            renderOutput(sb, width, height);
        } else {
            renderCommandList(sb, width, height);
        }

        // Footer
        sb.append("\033[").append(height).append(";1H\033[2K");
        sb.append("\033[36m").append("─".repeat(Math.min(width, 80))).append("\033[0m\n");
        if (searching) {
            sb.append("\033[2K \033[33m/\033[0m").append(searchQuery).append("\033[5m▏\033[0m");
        } else if (showOutput) {
            sb.append("\033[2K \033[2mEsc:back  q:quit\033[0m");
        } else {
            sb.append("\033[2K \033[2m↑↓:navigate  Enter:execute  /:search  q:quit\033[0m");
        }

        terminal.writer().print(sb);
        terminal.writer().flush();
    }

    private void renderCommandList(StringBuilder sb, int width, int height) {
        int listHeight = height - 4;
        if (selectedIdx >= scrollOffset + listHeight) scrollOffset = selectedIdx - listHeight + 1;
        if (selectedIdx < scrollOffset) scrollOffset = selectedIdx;
        scrollOffset = Math.max(0, scrollOffset);

        int nameCol = 16;
        int descCol = Math.max(20, width - nameCol - 6);

        for (int i = scrollOffset; i < Math.min(filtered.size(), scrollOffset + listHeight); i++) {
            CommandEntry e = filtered.get(i);
            sb.append("\033[2K");

            if (e.isHeader) {
                sb.append("\n\033[2K \033[1;35m▸ ").append(e.name).append("\033[0m\n");
                continue;
            }

            boolean selected = (i == selectedIdx);
            String desc = e.cmd != null ? e.cmd.description(messages) : "";

            if (selected) {
                sb.append("\033[2K \033[7;36m ▶ ")
                        .append(pad(e.name, nameCol))
                        .append(truncate(desc, descCol))
                        .append(" \033[0m\n");
            } else {
                sb.append("\033[2K   \033[36m")
                        .append(pad(e.name, nameCol))
                        .append("\033[0m\033[2m")
                        .append(truncate(desc, descCol))
                        .append("\033[0m\n");
            }
        }

        // Fill remaining lines
        int rendered = Math.min(filtered.size(), scrollOffset + listHeight) - scrollOffset;
        for (int i = rendered; i < listHeight; i++) sb.append("\033[2K\n");
    }

    private void renderOutput(StringBuilder sb, int width, int height) {
        int outputHeight = height - 4;
        sb.append("\033[2K \033[1;33m◀ ").append(filtered.get(selectedIdx).name)
                .append(" output\033[0m\n\033[2K\n");

        String[] lines = lastOutput.split("\n");
        int start = Math.max(0, lines.length - outputHeight + 2);
        for (int i = start; i < lines.length && i < start + outputHeight - 2; i++) {
            sb.append("\033[2K ").append(truncate(lines[i], width - 2)).append("\n");
        }

        int rendered = Math.min(lines.length - start, outputHeight - 2);
        for (int i = rendered + 2; i < outputHeight; i++) sb.append("\033[2K\n");
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    private static String truncate(String s, int max) {
        if (max <= 0) return "";
        // Strip ANSI for length calculation but keep for display
        String plain = s.replaceAll("\033\\[[\\d;]*m", "");
        if (plain.length() <= max) return s;
        return s.substring(0, Math.min(s.length(), max - 1)) + "…";
    }

    record CommandEntry(Command cmd, String name, boolean isHeader) {}
}
