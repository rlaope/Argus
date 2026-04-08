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
 * k9s-style full-screen interactive terminal UI for Argus.
 * Uses alternative screen buffer to prevent scroll corruption.
 */
public final class TuiApp {

    private static final String[] LOGO_LINES = {
            "       _____                          ",
            "      /  _  \\_______  ____  __ __  ______",
            "     /  /_\\  \\_  __ \\/ ___\\|  |  \\/  ___/",
            "    /    |    \\  | \\/ /_/  >  |  /\\___ \\",
            "    \\____|__  /__|  \\___  /|____//____  >",
            "            \\/     /_____/            \\/"
    };

    private static final String TAGLINE = "Lightweight JVM Diagnostic Toolkit — 50 commands, zero config";
    private static final String ALT_SCREEN_ON = "\033[?1049h";
    private static final String ALT_SCREEN_OFF = "\033[?1049l";
    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";

    private static final String[] LANGS = {"en", "ko", "ja", "zh"};

    private final Map<String, Command> commands;
    private final CliConfig config;
    private final ProviderRegistry registry;
    private Messages messages;
    private int langIdx = 0;

    private enum Phase { PROCESS_SELECT, COMMAND_LIST, OUTPUT_VIEW }
    private Phase phase = Phase.PROCESS_SELECT;

    private List<ProcessInfo> processes = List.of();
    private long selectedPid = 0;
    private String selectedProcessName = "";

    // Command list — flat list with headers for navigation
    private final List<CmdEntry> cmdEntries = new ArrayList<>();
    private int cmdIdx = 0;
    private int cmdScroll = 0;
    private String searchQuery = "";
    private boolean searching = false;
    private List<CmdEntry> filteredCmds = new ArrayList<>();

    // Process selection
    private int psIdx = 0;

    // Output
    private String lastOutput = "";
    private String lastCmdName = "";
    private int outputScroll = 0;

    private boolean running = true;

    public TuiApp(Map<String, Command> commands, CliConfig config,
                  ProviderRegistry registry, Messages messages) {
        this.commands = commands;
        this.config = config;
        this.registry = registry;
        this.messages = messages;
        for (int i = 0; i < LANGS.length; i++) {
            if (LANGS[i].equals(config.lang())) { langIdx = i; break; }
        }
        buildCmdEntries();
    }

    private void buildCmdEntries() {
        cmdEntries.clear();
        Map<CommandGroup, List<Command>> grouped = new LinkedHashMap<>();
        for (CommandGroup g : CommandGroup.values()) grouped.put(g, new ArrayList<>());
        for (Command cmd : commands.values()) {
            if (!cmd.name().equals("tui") && !cmd.name().equals("init"))
                grouped.get(cmd.group()).add(cmd);
        }
        for (var entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            cmdEntries.add(new CmdEntry(null, entry.getKey().displayName(), true));
            for (Command cmd : entry.getValue()) {
                cmdEntries.add(new CmdEntry(cmd, cmd.name(), false));
            }
        }
        applyFilter();
    }

    private void applyFilter() {
        filteredCmds = new ArrayList<>();
        if (searchQuery.isEmpty()) {
            filteredCmds.addAll(cmdEntries);
        } else {
            String q = searchQuery.toLowerCase();
            CmdEntry lastHdr = null;
            boolean hdrUsed = false;
            for (CmdEntry e : cmdEntries) {
                if (e.header) { lastHdr = e; hdrUsed = false; continue; }
                if (e.name.contains(q) || (e.cmd != null && e.cmd.description(messages).toLowerCase().contains(q))) {
                    if (lastHdr != null && !hdrUsed) { filteredCmds.add(lastHdr); hdrUsed = true; }
                    filteredCmds.add(e);
                }
            }
        }
        cmdIdx = Math.min(cmdIdx, Math.max(0, filteredCmds.size() - 1));
        while (cmdIdx < filteredCmds.size() && filteredCmds.get(cmdIdx).header) cmdIdx++;
    }

    public void run() {
        try (Terminal term = TerminalBuilder.builder().system(true).jansi(true).build()) {
            term.enterRawMode();
            NonBlockingReader reader = term.reader();
            System.out.print(ALT_SCREEN_ON + HIDE_CURSOR);

            refreshProcesses();

            while (running) {
                int h = term.getHeight() > 0 ? term.getHeight() : 30;
                int w = term.getWidth() > 0 ? term.getWidth() : 80;
                StringBuilder sb = new StringBuilder();
                sb.append("\033[H"); // cursor home (no clear — we overwrite every line)

                switch (phase) {
                    case PROCESS_SELECT -> drawProcessScreen(sb, w, h);
                    case COMMAND_LIST -> drawCommandScreen(sb, w, h);
                    case OUTPUT_VIEW -> drawOutputScreen(sb, w, h);
                }

                term.writer().print(sb);
                term.writer().flush();

                int key = reader.read(100);
                if (key == -2) continue;
                if (key == -1) break;
                handleKey(key, reader);
            }

            System.out.print(SHOW_CURSOR + ALT_SCREEN_OFF);
        } catch (Exception e) {
            System.out.print(SHOW_CURSOR + ALT_SCREEN_OFF);
            System.err.println("TUI error: " + e.getMessage());
        }
    }

    // ── Key handling ──

    private void handleKey(int key, NonBlockingReader reader) throws Exception {
        if (key == 27) { // ESC or arrow
            int n = reader.read(50);
            if (n == '[') {
                int a = reader.read(50);
                if (a == 'A') { moveUp(); return; }
                if (a == 'B') { moveDown(); return; }
            }
            goBack();
            return;
        }
        if (searching) {
            if (key == 10 || key == 13) searching = false;
            else if (key == 127 || key == 8) {
                if (!searchQuery.isEmpty()) { searchQuery = searchQuery.substring(0, searchQuery.length() - 1); applyFilter(); }
            } else if (key >= 32 && key < 127) { searchQuery += (char) key; applyFilter(); }
            return;
        }
        switch (key) {
            case 'q', 'Q' -> goBack();
            case 'j' -> moveDown();
            case 'k' -> moveUp();
            case '/' -> { if (phase == Phase.COMMAND_LIST) { searching = true; searchQuery = ""; } }
            case 'r', 'R' -> { if (phase == Phase.PROCESS_SELECT) refreshProcesses(); }
            case 'l', 'L' -> cycleLang();
            case 10, 13 -> handleEnter();
            default -> {}
        }
    }

    private void goBack() {
        switch (phase) {
            case OUTPUT_VIEW -> { phase = Phase.COMMAND_LIST; outputScroll = 0; }
            case COMMAND_LIST -> {
                if (!searchQuery.isEmpty()) { searchQuery = ""; applyFilter(); }
                else { phase = Phase.PROCESS_SELECT; psIdx = 0; }
            }
            case PROCESS_SELECT -> running = false;
        }
    }

    private void cycleLang() {
        langIdx = (langIdx + 1) % LANGS.length;
        messages = new Messages(LANGS[langIdx]);
    }

    private void handleEnter() {
        switch (phase) {
            case PROCESS_SELECT -> {
                if (psIdx >= 0 && psIdx < processes.size()) {
                    selectedPid = processes.get(psIdx).pid();
                    selectedProcessName = processes.get(psIdx).mainClass();
                    phase = Phase.COMMAND_LIST;
                    cmdIdx = 0; cmdScroll = 0; searchQuery = "";
                    applyFilter();
                    while (cmdIdx < filteredCmds.size() && filteredCmds.get(cmdIdx).header) cmdIdx++;
                }
            }
            case COMMAND_LIST -> {
                if (cmdIdx < filteredCmds.size() && !filteredCmds.get(cmdIdx).header) {
                    execCmd(filteredCmds.get(cmdIdx));
                }
            }
            case OUTPUT_VIEW -> phase = Phase.COMMAND_LIST;
        }
    }

    private void moveDown() {
        switch (phase) {
            case PROCESS_SELECT -> { if (psIdx < processes.size() - 1) psIdx++; }
            case COMMAND_LIST -> {
                int n = cmdIdx + 1;
                while (n < filteredCmds.size() && filteredCmds.get(n).header) n++;
                if (n < filteredCmds.size()) cmdIdx = n;
            }
            case OUTPUT_VIEW -> outputScroll++;
        }
    }

    private void moveUp() {
        switch (phase) {
            case PROCESS_SELECT -> { if (psIdx > 0) psIdx--; }
            case COMMAND_LIST -> {
                int p = cmdIdx - 1;
                while (p >= 0 && filteredCmds.get(p).header) p--;
                if (p >= 0) cmdIdx = p;
            }
            case OUTPUT_VIEW -> { if (outputScroll > 0) outputScroll--; }
        }
    }

    private void execCmd(CmdEntry entry) {
        if (entry.cmd == null) return;
        lastCmdName = entry.name;
        PrintStream orig = System.out;
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        System.setOut(new PrintStream(cap));
        try {
            String[] args = selectedPid > 0 ? new String[]{String.valueOf(selectedPid)} : new String[0];
            entry.cmd.execute(args, config, registry, messages);
        } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
        finally { System.setOut(orig); }
        lastOutput = cap.toString();
        outputScroll = 0;
        phase = Phase.OUTPUT_VIEW;
    }

    private void refreshProcesses() {
        ProcessProvider pp = registry.findProcessProvider();
        if (pp != null) processes = pp.listProcesses();
    }

    // ── Drawing ──

    private void drawProcessScreen(StringBuilder sb, int w, int h) {
        int line = 0;
        // Logo
        sb.append(cl(w)).append("\n"); line++;
        for (String l : LOGO_LINES) {
            sb.append(cl(w)).append("  \033[36m").append(l).append("\033[0m\n"); line++;
        }
        sb.append(cl(w)).append("\n"); line++;
        sb.append(cl(w)).append("  \033[1;35mv1.0.0\033[0m  \033[2m").append(TAGLINE).append("\033[0m\n"); line++;
        sb.append(cl(w)).append("  \033[2mLanguage: ").append(LANGS[langIdx].toUpperCase())
                .append("  (press L to change)\033[0m\n"); line++;
        sb.append(cl(w)).append(bar(w)).append("\n"); line++;
        sb.append(cl(w)).append("  \033[1mJVM Processes:\033[0m  \033[2m")
                .append(processes.size()).append(" found\033[0m\n"); line++;
        sb.append(cl(w)).append("\n"); line++;

        // Process grid
        int listH = h - line - 3;
        for (int i = 0; i < Math.min(processes.size(), listH); i++) {
            ProcessInfo p = processes.get(i);
            boolean sel = (i == psIdx);
            sb.append(cl(w));
            if (sel) {
                sb.append("  \033[7;36m ▶ ").append(fmt(p.pid(), 8))
                        .append(pad(trunc(p.mainClass(), 30), 32))
                        .append(pad(trunc(p.javaVersion(), 25), 26))
                        .append(" \033[0m");
            } else {
                sb.append("    \033[36m").append(fmt(p.pid(), 8)).append("\033[0m")
                        .append("\033[0m").append(pad(trunc(p.mainClass(), 30), 32))
                        .append("\033[2m").append(pad(trunc(p.javaVersion(), 25), 26)).append("\033[0m");
            }
            sb.append("\n");
        }
        for (int i = Math.min(processes.size(), listH); i < listH; i++) sb.append(cl(w)).append("\n");

        // Footer
        sb.append(cl(w)).append(bar(w)).append("\n");
        sb.append(cl(w)).append("  \033[2m↑↓/jk:select  Enter:connect  r:refresh  l:language  q:quit\033[0m");
    }

    private void drawCommandScreen(StringBuilder sb, int w, int h) {
        // Header
        sb.append(cl(w)).append("  \033[1;36m⚡ Argus\033[0m")
                .append("  \033[35m").append(selectedProcessName).append("\033[0m")
                .append("  \033[2mpid:").append(selectedPid).append("\033[0m\n");
        sb.append(cl(w)).append(bar(w)).append("\n");

        int bodyH = h - 4; // header(2) + footer(2)
        // Scroll management
        if (cmdIdx >= cmdScroll + bodyH) cmdScroll = cmdIdx - bodyH + 1;
        if (cmdIdx < cmdScroll) cmdScroll = cmdIdx;
        cmdScroll = Math.max(0, cmdScroll);

        // 2-column layout
        int colW = (w - 4) / 2;
        List<String> lines = new ArrayList<>();
        for (int i = cmdScroll; i < Math.min(filteredCmds.size(), cmdScroll + bodyH * 2); i++) {
            CmdEntry e = filteredCmds.get(i);
            if (e.header) {
                lines.add("\033[1;35m▸ " + e.name + "\033[0m");
            } else {
                boolean sel = (i == cmdIdx);
                String desc = e.cmd != null ? trunc(e.cmd.description(messages), colW - 18) : "";
                if (sel) {
                    lines.add("\033[7;36m▶ " + pad(e.name, 15) + desc + "\033[0m");
                } else {
                    lines.add(" \033[36m" + pad(e.name, 15) + "\033[0m\033[2m" + desc + "\033[0m");
                }
            }
        }

        // Render as 2 columns
        int half = (lines.size() + 1) / 2;
        for (int row = 0; row < bodyH; row++) {
            sb.append(cl(w)).append("  ");
            if (row < half && row < lines.size()) {
                sb.append(padAnsi(lines.get(row), colW));
            } else {
                sb.append(" ".repeat(colW));
            }
            sb.append("  ");
            int rightIdx = row + half;
            if (rightIdx < lines.size()) {
                sb.append(padAnsi(lines.get(rightIdx), colW));
            }
            sb.append("\n");
        }

        // Footer
        sb.append(cl(w)).append(bar(w)).append("\n");
        if (searching) {
            sb.append(cl(w)).append("  \033[33m/\033[0m").append(searchQuery).append("\033[5m▏\033[0m");
        } else {
            sb.append(cl(w)).append("  \033[2m↑↓/jk:navigate  Enter:execute  /:search  Esc:back  l:language\033[0m");
        }
    }

    private void drawOutputScreen(StringBuilder sb, int w, int h) {
        sb.append(cl(w)).append("  \033[1;33m◀ ").append(lastCmdName)
                .append("\033[0m  \033[35mpid:").append(selectedPid).append("\033[0m\n");
        sb.append(cl(w)).append(bar(w)).append("\n");

        int bodyH = h - 4;
        String[] outLines = lastOutput.split("\n");
        outputScroll = Math.max(0, Math.min(outputScroll, Math.max(0, outLines.length - bodyH)));

        for (int i = 0; i < bodyH; i++) {
            int li = i + outputScroll;
            sb.append(cl(w));
            if (li < outLines.length) {
                sb.append(" ").append(trunc(outLines[li], w - 2));
            }
            sb.append("\n");
        }

        sb.append(cl(w)).append(bar(w)).append("\n");
        sb.append(cl(w)).append("  \033[2m↑↓:scroll  Esc/Enter/q:back  l:language\033[0m");
    }

    // ── Helpers ──

    private static String cl(int w) { return "\033[2K"; } // clear line
    private static String bar(int w) { return "\033[36m" + "─".repeat(Math.min(w, 80)) + "\033[0m"; }
    private static String pad(String s, int w) { return s.length() >= w ? s.substring(0, w) : s + " ".repeat(w - s.length()); }
    private static String trunc(String s, int m) { if (m <= 0) return ""; return s.length() <= m ? s : s.substring(0, m - 1) + "…"; }
    private static String fmt(long v, int w) { String s = String.valueOf(v); return pad(s, w); }
    private static String padAnsi(String s, int w) {
        int plain = s.replaceAll("\033\\[[\\d;]*m", "").length();
        if (plain >= w) return s;
        return s + " ".repeat(w - plain);
    }

    record CmdEntry(Command cmd, String name, boolean header) {}
}
