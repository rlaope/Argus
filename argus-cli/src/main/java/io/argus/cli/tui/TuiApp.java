package io.argus.cli.tui;

import io.argus.cli.command.Command;
import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.ProcessInfo;
import io.argus.cli.provider.ProcessProvider;
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
 *
 * <p>Flow: Logo + Process List → Select PID → Command List → Execute → Output
 */
public final class TuiApp {

    private static final String LOGO = """
            \033[36m
               _____
              /  _  \\_______  ____  __ __  ______
             /  /_\\  \\_  __ \\/ ___\\|  |  \\/  ___/
            /    |    \\  | \\/ /_/  >  |  /\\___ \\
            \\____|__  /__|  \\___  /|____//____  >
                    \\/     /_____/            \\/
            \033[0m""";

    private final Map<String, Command> commands;
    private final CliConfig config;
    private final ProviderRegistry registry;
    private final Messages messages;

    // State
    private enum Phase { PROCESS_SELECT, COMMAND_LIST, OUTPUT_VIEW }
    private Phase phase = Phase.PROCESS_SELECT;
    private List<ProcessInfo> processes = List.of();
    private long selectedPid = 0;
    private final List<CommandEntry> allEntries = new ArrayList<>();
    private final List<CommandEntry> filtered = new ArrayList<>();
    private int selectedIdx = 0;
    private int scrollOffset = 0;
    private String searchQuery = "";
    private boolean searching = false;
    private String lastOutput = "";
    private String lastCommandName = "";
    private boolean running = true;

    public TuiApp(Map<String, Command> commands, CliConfig config,
                  ProviderRegistry registry, Messages messages) {
        this.commands = commands;
        this.config = config;
        this.registry = registry;
        this.messages = messages;
        buildCommandEntries();
    }

    private void buildCommandEntries() {
        Map<CommandGroup, List<Command>> grouped = new LinkedHashMap<>();
        for (CommandGroup g : CommandGroup.values()) grouped.put(g, new ArrayList<>());
        for (Command cmd : commands.values()) {
            if (!cmd.name().equals("tui") && !cmd.name().equals("init"))
                grouped.get(cmd.group()).add(cmd);
        }
        for (var entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            allEntries.add(new CommandEntry(null, entry.getKey().displayName(), true));
            for (Command cmd : entry.getValue()) {
                allEntries.add(new CommandEntry(cmd, cmd.name(), false));
            }
        }
    }

    private void applyFilter() {
        filtered.clear();
        if (searchQuery.isEmpty()) {
            filtered.addAll(allEntries);
        } else {
            String q = searchQuery.toLowerCase();
            CommandEntry lastHeader = null;
            boolean headerHasMatch = false;
            for (CommandEntry e : allEntries) {
                if (e.isHeader) {
                    if (lastHeader != null && headerHasMatch) filtered.add(lastHeader);
                    lastHeader = e;
                    headerHasMatch = false;
                } else if (e.name.toLowerCase().contains(q)
                        || (e.cmd != null && e.cmd.description(messages).toLowerCase().contains(q))) {
                    if (lastHeader != null && !headerHasMatch) {
                        filtered.add(lastHeader);
                        headerHasMatch = true;
                    }
                    filtered.add(e);
                }
            }
        }
        selectedIdx = Math.min(selectedIdx, Math.max(0, filtered.size() - 1));
        while (selectedIdx < filtered.size() && filtered.get(selectedIdx).isHeader) selectedIdx++;
    }

    public void run() {
        try (Terminal terminal = TerminalBuilder.builder()
                .system(true).jansi(true).build()) {
            terminal.enterRawMode();
            NonBlockingReader reader = terminal.reader();

            // Load processes initially
            refreshProcesses();

            while (running) {
                int height = terminal.getHeight() > 0 ? terminal.getHeight() : 30;
                int width = terminal.getWidth() > 0 ? terminal.getWidth() : 80;
                render(terminal, width, height);

                int key = reader.read(100);
                if (key == -2) continue;
                if (key == -1) break;
                handleKey(key, reader);
            }

            terminal.writer().print("\033[2J\033[H\033[?25h");
            terminal.writer().flush();
        } catch (Exception e) {
            System.err.println("TUI error: " + e.getMessage());
        }
    }

    private void refreshProcesses() {
        ProcessProvider pp = registry.findProcessProvider();
        if (pp != null) processes = pp.listProcesses();
    }

    private void handleKey(int key, NonBlockingReader reader) throws Exception {
        // Arrow keys: ESC [ A/B
        if (key == 27) {
            int next = reader.read(50);
            if (next == '[') {
                int arrow = reader.read(50);
                if (arrow == 'A') { moveUp(); return; }
                if (arrow == 'B') { moveDown(); return; }
            }
            // Plain ESC — go back
            if (phase == Phase.OUTPUT_VIEW) { phase = Phase.COMMAND_LIST; return; }
            if (phase == Phase.COMMAND_LIST) {
                if (!searchQuery.isEmpty()) { searchQuery = ""; applyFilter(); }
                else { phase = Phase.PROCESS_SELECT; selectedIdx = 0; }
                return;
            }
            return;
        }

        if (searching) {
            if (key == 10 || key == 13) { searching = false; }
            else if (key == 127 || key == 8) {
                if (!searchQuery.isEmpty()) { searchQuery = searchQuery.substring(0, searchQuery.length() - 1); applyFilter(); }
            } else if (key >= 32 && key < 127) { searchQuery += (char) key; applyFilter(); }
            return;
        }

        switch (key) {
            case 'q', 'Q' -> {
                if (phase == Phase.OUTPUT_VIEW) phase = Phase.COMMAND_LIST;
                else if (phase == Phase.COMMAND_LIST) { phase = Phase.PROCESS_SELECT; selectedIdx = 0; }
                else running = false;
            }
            case 'j' -> moveDown();
            case 'k' -> moveUp();
            case '/' -> { if (phase == Phase.COMMAND_LIST) { searching = true; searchQuery = ""; } }
            case 'r', 'R' -> { if (phase == Phase.PROCESS_SELECT) refreshProcesses(); }
            case 10, 13 -> handleEnter(); // Enter
            default -> {}
        }
    }

    private void handleEnter() {
        if (phase == Phase.PROCESS_SELECT) {
            if (selectedIdx >= 0 && selectedIdx < processes.size()) {
                selectedPid = processes.get(selectedIdx).pid();
                phase = Phase.COMMAND_LIST;
                selectedIdx = 0;
                scrollOffset = 0;
                searchQuery = "";
                applyFilter();
                // Skip first header
                while (selectedIdx < filtered.size() && filtered.get(selectedIdx).isHeader) selectedIdx++;
            }
        } else if (phase == Phase.COMMAND_LIST) {
            if (selectedIdx < filtered.size() && !filtered.get(selectedIdx).isHeader) {
                executeCommand(filtered.get(selectedIdx));
            }
        } else if (phase == Phase.OUTPUT_VIEW) {
            phase = Phase.COMMAND_LIST;
        }
    }

    private void executeCommand(CommandEntry entry) {
        if (entry.cmd == null) return;
        lastCommandName = entry.name;
        PrintStream original = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture));
        try {
            String[] args = selectedPid > 0 ? new String[]{String.valueOf(selectedPid)} : new String[0];
            entry.cmd.execute(args, config, registry, messages);
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        } finally {
            System.setOut(original);
        }
        lastOutput = capture.toString();
        phase = Phase.OUTPUT_VIEW;
    }

    private void moveDown() {
        if (phase == Phase.PROCESS_SELECT) {
            if (selectedIdx < processes.size() - 1) selectedIdx++;
        } else if (phase == Phase.COMMAND_LIST) {
            int next = selectedIdx + 1;
            while (next < filtered.size() && filtered.get(next).isHeader) next++;
            if (next < filtered.size()) selectedIdx = next;
        }
    }

    private void moveUp() {
        if (phase == Phase.PROCESS_SELECT) {
            if (selectedIdx > 0) selectedIdx--;
        } else if (phase == Phase.COMMAND_LIST) {
            int prev = selectedIdx - 1;
            while (prev >= 0 && filtered.get(prev).isHeader) prev--;
            if (prev >= 0) selectedIdx = prev;
        }
    }

    // ── Rendering ──

    private void render(Terminal terminal, int width, int height) {
        StringBuilder sb = new StringBuilder();
        sb.append("\033[?25l\033[H"); // hide cursor, home

        switch (phase) {
            case PROCESS_SELECT -> renderProcessSelect(sb, width, height);
            case COMMAND_LIST -> renderCommandList(sb, width, height);
            case OUTPUT_VIEW -> renderOutputView(sb, width, height);
        }

        terminal.writer().print(sb);
        terminal.writer().flush();
    }

    private void renderProcessSelect(StringBuilder sb, int width, int height) {
        // Logo
        for (String line : LOGO.split("\n")) {
            sb.append("\033[2K").append(line).append("\n");
        }
        sb.append("\033[2K\n");
        sb.append("\033[2K \033[1;35mv1.0.0\033[0m  \033[2m")
                .append(commands.size()).append(" commands | Java 21+ | ")
                .append(processes.size()).append(" JVM process(es)\033[0m\n");
        sb.append("\033[2K\033[36m").append("─".repeat(Math.min(width, 80))).append("\033[0m\n");
        sb.append("\033[2K \033[1mSelect a JVM process:\033[0m\n\033[2K\n");

        // Process list
        int listStart = 10; // lines used by logo+header
        int listHeight = height - listStart - 3;

        for (int i = 0; i < Math.min(processes.size(), listHeight); i++) {
            ProcessInfo p = processes.get(i);
            boolean sel = (i == selectedIdx);
            sb.append("\033[2K");
            if (sel) {
                sb.append(" \033[7;36m ▶ ")
                        .append(String.format("%-8d", p.pid()))
                        .append(truncate(p.mainClass(), 35))
                        .append(" \033[0m");
            } else {
                sb.append("   \033[36m").append(String.format("%-8d", p.pid())).append("\033[0m")
                        .append("\033[2m").append(truncate(p.mainClass(), 35)).append("\033[0m");
            }
            // Show version/uptime if available
            if (!p.javaVersion().isEmpty()) {
                sb.append("  \033[2m").append(truncate(p.javaVersion(), 25)).append("\033[0m");
            }
            sb.append("\n");
        }

        // Fill
        int rendered = Math.min(processes.size(), listHeight);
        for (int i = rendered; i < listHeight; i++) sb.append("\033[2K\n");

        // Footer
        sb.append("\033[2K\033[36m").append("─".repeat(Math.min(width, 80))).append("\033[0m\n");
        sb.append("\033[2K \033[2m↑↓:select  Enter:connect  r:refresh  q:quit\033[0m");
    }

    private void renderCommandList(StringBuilder sb, int width, int height) {
        sb.append("\033[2K \033[1;36m⚡ Argus\033[0m")
                .append("  \033[35mpid:").append(selectedPid).append("\033[0m")
                .append("  \033[2m").append(filtered.size()).append(" commands\033[0m\n");
        sb.append("\033[2K\033[36m").append("─".repeat(Math.min(width, 80))).append("\033[0m\n");

        int listHeight = height - 4;
        if (selectedIdx >= scrollOffset + listHeight) scrollOffset = selectedIdx - listHeight + 1;
        if (selectedIdx < scrollOffset) scrollOffset = selectedIdx;
        scrollOffset = Math.max(0, scrollOffset);

        int nameCol = 16;
        int descCol = Math.max(20, width - nameCol - 6);

        int rendered = 0;
        for (int i = scrollOffset; i < Math.min(filtered.size(), scrollOffset + listHeight); i++) {
            CommandEntry e = filtered.get(i);
            sb.append("\033[2K");
            if (e.isHeader) {
                sb.append("\n\033[2K \033[1;35m▸ ").append(e.name).append("\033[0m\n");
                rendered += 2;
                continue;
            }
            boolean sel = (i == selectedIdx);
            String desc = e.cmd != null ? e.cmd.description(messages) : "";
            if (sel) {
                sb.append(" \033[7;36m ▶ ").append(pad(e.name, nameCol))
                        .append(truncate(desc, descCol)).append(" \033[0m\n");
            } else {
                sb.append("   \033[36m").append(pad(e.name, nameCol)).append("\033[0m")
                        .append("\033[2m").append(truncate(desc, descCol)).append("\033[0m\n");
            }
            rendered++;
        }
        for (int i = rendered; i < listHeight; i++) sb.append("\033[2K\n");

        sb.append("\033[2K\033[36m").append("─".repeat(Math.min(width, 80))).append("\033[0m\n");
        if (searching) {
            sb.append("\033[2K \033[33m/\033[0m").append(searchQuery).append("\033[5m▏\033[0m");
        } else {
            sb.append("\033[2K \033[2m↑↓:navigate  Enter:execute  /:search  Esc:back  q:back\033[0m");
        }
    }

    private void renderOutputView(StringBuilder sb, int width, int height) {
        sb.append("\033[2K \033[1;33m◀ ").append(lastCommandName)
                .append("\033[0m  \033[35mpid:").append(selectedPid).append("\033[0m\n");
        sb.append("\033[2K\033[36m").append("─".repeat(Math.min(width, 80))).append("\033[0m\n");

        int outputHeight = height - 4;
        String[] lines = lastOutput.split("\n");
        int start = Math.max(0, lines.length - outputHeight);
        for (int i = start; i < lines.length && i < start + outputHeight; i++) {
            sb.append("\033[2K ").append(truncate(lines[i], width - 2)).append("\n");
        }
        int rendered = Math.min(lines.length - start, outputHeight);
        for (int i = rendered; i < outputHeight; i++) sb.append("\033[2K\n");

        sb.append("\033[2K\033[36m").append("─".repeat(Math.min(width, 80))).append("\033[0m\n");
        sb.append("\033[2K \033[2mEsc:back  Enter:back  q:back\033[0m");
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s.substring(0, w) : s + " ".repeat(w - s.length());
    }

    private static String truncate(String s, int max) {
        if (max <= 0) return "";
        String plain = s.replaceAll("\033\\[[\\d;]*m", "");
        if (plain.length() <= max) return s;
        return s.substring(0, Math.min(s.length(), max - 1)) + "…";
    }

    record CommandEntry(Command cmd, String name, boolean isHeader) {}
}
