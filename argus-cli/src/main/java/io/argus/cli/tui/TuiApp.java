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
import java.io.PrintWriter;
import java.util.*;

public final class TuiApp {

    private static final String[] LOGO = {
        "    _____",
        "   /  _  \\_______  ____  __ __  ______",
        "  /  /_\\  \\_  __ \\/ ___\\|  |  \\/  ___/",
        " /    |    \\  | \\/ /_/  >  |  /\\___ \\",
        " \\____|__  /__|  \\___  /|____//____  >",
        "         \\/     /_____/            \\/"
    };
    private static final String[] LANGS = {"en", "ko", "ja", "zh"};
    private static final String[] THEME_NAMES = {"skyblue", "green", "gray"};
    // fg color per theme
    private static final String[] THEME_FG = {"\033[36m", "\033[32m", "\033[37m"};
    private static final String[] THEME_HL = {"\033[30;46m", "\033[30;42m", "\033[30;47m"};
    private static final String[] THEME_ACC = {"\033[35m", "\033[33m", "\033[97m"};
    private static final String R = "\033[0m";
    private static final String DIM = "\033[2m";

    private final Map<String, Command> commands;
    private final CliConfig config;
    private final ProviderRegistry registry;
    private Messages messages;
    private int langIdx = 0, themeIdx = 0;

    private enum Phase { PS, CMD, OUT }
    private Phase phase = Phase.PS;
    private List<ProcessInfo> procs = List.of();
    private long pid = 0; private String pidName = "";
    private int psIdx = 0;

    private final List<CE> allCmds = new ArrayList<>();
    private List<CE> fCmds = new ArrayList<>();
    private int cIdx = 0, cScr = 0;
    private String sq = ""; private boolean searching = false;

    private String output = "", outName = "";
    private int oScr = 0;
    private boolean running = true;

    // Language select overlay
    private boolean langSelect = false;
    private int langSelIdx = 0;

    public TuiApp(Map<String, Command> commands, CliConfig config,
                  ProviderRegistry registry, Messages messages) {
        this.commands = commands; this.config = config;
        this.registry = registry; this.messages = messages;
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i].equals(config.lang())) langIdx = i;
        langSelIdx = langIdx;
        buildCmds();
    }

    private String fg() { return THEME_FG[themeIdx]; }
    private String hl() { return THEME_HL[themeIdx]; }
    private String acc() { return THEME_ACC[themeIdx]; }

    private void buildCmds() {
        allCmds.clear();
        var g = new LinkedHashMap<CommandGroup, List<Command>>();
        for (CommandGroup cg : CommandGroup.values()) g.put(cg, new ArrayList<>());
        for (Command c : commands.values())
            if (!c.name().equals("tui") && !c.name().equals("init")) g.get(c.group()).add(c);
        for (var e : g.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            allCmds.add(new CE(null, e.getKey().displayName(), true));
            for (Command c : e.getValue()) allCmds.add(new CE(c, c.name(), false));
        }
        filter();
    }
    private void filter() {
        fCmds = new ArrayList<>();
        if (sq.isEmpty()) { fCmds.addAll(allCmds); return; }
        String q = sq.toLowerCase(); CE hdr = null; boolean u = false;
        for (CE e : allCmds) {
            if (e.h) { hdr = e; u = false; continue; }
            if (e.n.contains(q) || (e.c != null && e.c.description(messages).toLowerCase().contains(q))) {
                if (hdr != null && !u) { fCmds.add(hdr); u = true; } fCmds.add(e);
            }
        }
        cIdx = Math.min(cIdx, Math.max(0, fCmds.size()-1));
        while (cIdx < fCmds.size() && fCmds.get(cIdx).h) cIdx++;
    }

    public void run() {
        // Pre-load processes before JLine init (saves ~1s)
        refreshPs();

        System.out.print("\033[?1049h\033[?25l\033[H\033[2J");
        for (String l : LOGO) System.out.println("\033[36m  " + l + R);
        System.out.flush();

        try (Terminal t = TerminalBuilder.builder().system(true).jansi(true).build()) {
            t.enterRawMode(); NonBlockingReader rd = t.reader(); PrintWriter w = t.writer();
            while (running) {
                int H = Math.max(t.getHeight(), 20);
                int TW = Math.max(t.getWidth(), 40);
                int W = Math.min(TW, 120); // box max 120 chars
                int margin = (TW - W) / 2;
                String ml = margin > 0 ? " ".repeat(margin) : "";
                StringBuilder sb = new StringBuilder("\033[H\033[2J"); // home + clear entire screen
                switch (phase) {
                    case PS -> drawPS(sb, W, H, ml);
                    case CMD -> drawCMD(sb, W, H, ml);
                    case OUT -> drawOUT(sb, W, H, ml);
                }
                w.print(sb); w.flush();
                int key = rd.read(80);
                if (key == -2) continue; if (key == -1) break;
                onKey(key, rd);
            }
            w.print("\033[?25h\033[?1049l"); w.flush();
        } catch (Exception e) { System.out.print("\033[?25h\033[?1049l"); }
    }

    private void onKey(int key, NonBlockingReader rd) throws Exception {
        if (key == 27) {
            int n = rd.read(30);
            if (n == '[') {
                int a = rd.read(30);
                if (langSelect) {
                    if (a == 'A' && langSelIdx > 0) langSelIdx--;
                    else if (a == 'B' && langSelIdx < LANGS.length-1) langSelIdx++;
                    return;
                }
                if (a == 'A') up(); else if (a == 'B') dn();
                return;
            }
            if (langSelect) { langSelect = false; return; }
            back(); return;
        }
        if (langSelect) {
            switch (key) {
                case 'j' -> { if (langSelIdx < LANGS.length-1) langSelIdx++; }
                case 'k' -> { if (langSelIdx > 0) langSelIdx--; }
                case 10, 13 -> { langIdx = langSelIdx; messages = new Messages(LANGS[langIdx]); langSelect = false; }
                case 'q', 'Q' -> langSelect = false;
            }
            return;
        }
        if (searching) {
            if (key == 10 || key == 13) searching = false;
            else if (key == 127 || key == 8) { if (!sq.isEmpty()) { sq = sq.substring(0, sq.length()-1); filter(); } }
            else if (key >= 32 && key < 127) { sq += (char)key; filter(); }
            return;
        }
        switch (key) {
            case 'q','Q' -> back();
            case 'j' -> dn(); case 'k' -> up();
            case '/' -> { if (phase == Phase.CMD) { searching = true; sq = ""; } }
            case 'r','R' -> { if (phase == Phase.PS) refreshPs(); }
            case 'l','L' -> { langSelect = true; langSelIdx = langIdx; }
            case 't','T' -> themeIdx = (themeIdx+1) % THEME_NAMES.length;
            case 10, 13 -> enter();
            default -> {}
        }
    }

    private void back() { switch(phase){ case OUT->{phase=Phase.CMD;oScr=0;} case CMD->{if(!sq.isEmpty()){sq="";filter();}else{phase=Phase.PS;psIdx=0;}} case PS->running=false; }}
    private void dn() { switch(phase){ case PS->{if(psIdx<procs.size()-1)psIdx++;} case CMD->{int n=cIdx+1;while(n<fCmds.size()&&fCmds.get(n).h)n++;if(n<fCmds.size())cIdx=n;} case OUT->oScr++; }}
    private void up() { switch(phase){ case PS->{if(psIdx>0)psIdx--;} case CMD->{int p=cIdx-1;while(p>=0&&fCmds.get(p).h)p--;if(p>=0)cIdx=p;} case OUT->{if(oScr>0)oScr--;} }}
    private void enter() { switch(phase){
        case PS->{if(psIdx<procs.size()){pid=procs.get(psIdx).pid();pidName=procs.get(psIdx).mainClass();phase=Phase.CMD;cIdx=0;cScr=0;sq="";filter();while(cIdx<fCmds.size()&&fCmds.get(cIdx).h)cIdx++;}}
        case CMD->{if(cIdx<fCmds.size()&&!fCmds.get(cIdx).h)exec(fCmds.get(cIdx));}
        case OUT->phase=Phase.CMD;
    }}
    private void exec(CE e) {
        if(e.c==null)return; outName=e.n;
        PrintStream origOut=System.out, origErr=System.err;
        var cap=new ByteArrayOutputStream();
        var ps=new PrintStream(cap);
        System.setOut(ps); System.setErr(ps);

        // Run with timeout — prevents hanging on long-running commands (profile, slowlog)
        Thread cmdThread = new Thread(() -> {
            try{e.c.execute(pid>0?new String[]{String.valueOf(pid)}:new String[0],config,registry,messages);}
            catch(Exception ex){System.out.println("Error: "+ex.getMessage());}
        }, "argus-tui-exec");
        cmdThread.setDaemon(true);
        cmdThread.start();
        try { cmdThread.join(30_000); } // 30s timeout
        catch (InterruptedException ignored) {}
        if (cmdThread.isAlive()) {
            cmdThread.interrupt();
            System.out.println("\n[Timed out after 30s — use CLI directly for long-running commands]");
        }

        System.setOut(origOut); System.setErr(origErr);
        output=cap.toString();oScr=0;phase=Phase.OUT;
    }
    private void refreshPs() { ProcessProvider pp=registry.findProcessProvider(); if(pp!=null) procs=pp.listProcesses(); }

    // ═══ SAFE ROW BUILDER — all padding uses plain-text length ═══

    /** Build a full-width row: │ content... │ with exact padding */
    private String boxRow(String plain, int W) {
        String t = plain.length() > W-4 ? plain.substring(0, W-4) + "…" : plain;
        return fg() + "│" + R + " " + t + " ".repeat(Math.max(0, W-3-t.length())) + fg() + "│" + R;
    }
    /** Highlighted row */
    private String hlRow(String plain, int W) {
        String t = plain.length() > W-4 ? plain.substring(0, W-4) + "…" : plain;
        return fg() + "│" + hl() + " " + t + " ".repeat(Math.max(0, W-3-t.length())) + R + fg() + "│" + R;
    }
    /** Colored text row — color applied to content, padding is plain */
    private String colorRow(String color, String plain, int W) {
        String t = plain.length() > W-4 ? plain.substring(0, W-4) + "…" : plain;
        return fg() + "│" + R + " " + color + t + R + " ".repeat(Math.max(0, W-3-t.length())) + fg() + "│" + R;
    }
    /** Centered row */
    private String centerRow(String plain, int W) {
        String t = plain.length() > W-4 ? plain.substring(0, W-4) : plain;
        int pad = (W-2-t.length()) / 2;
        return fg() + "│" + R + " ".repeat(pad) + t + " ".repeat(W-2-pad-t.length()) + fg() + "│" + R;
    }
    private String centerColorRow(String color, String plain, int W) {
        String t = plain.length() > W-4 ? plain.substring(0, W-4) : plain;
        int pad = (W-2-t.length()) / 2;
        return fg() + "│" + R + " ".repeat(pad) + color + t + R + " ".repeat(W-2-pad-t.length()) + fg() + "│" + R;
    }
    private String emptyRow(int W) { return fg() + "│" + R + " ".repeat(W-2) + fg() + "│" + R; }
    private String topLine(int W) { return fg() + "╭" + "─".repeat(W-2) + "╮" + R; }
    private String botLine(int W) { return fg() + "╰" + "─".repeat(W-2) + "╯" + R; }
    private String midLine(int W) { return fg() + "├" + "─".repeat(W-2) + "┤" + R; }

    // ═══ Draw screens ═══

    private void drawPS(StringBuilder s, int W, int H, String ml) {
        List<String> rows = new ArrayList<>();
        rows.add(topLine(W));
        rows.add(emptyRow(W));
        for (String l : LOGO) rows.add(centerColorRow(fg(), l, W));
        rows.add(emptyRow(W));
        rows.add(centerRow("Lightweight JVM Diagnostic Toolkit — v1.0.0", W));
        rows.add(centerColorRow(DIM, commands.size()+" commands  |  "+LANGS[langIdx].toUpperCase()+"  |  "+THEME_NAMES[themeIdx]+"  |  "+procs.size()+" JVMs", W));
        rows.add(emptyRow(W));
        rows.add(midLine(W));
        rows.add(colorRow(acc(), "  JVM Processes", W));
        rows.add(colorRow(DIM, "  " + pad("PID",10) + pad("Main Class", Math.max(15, W-42)) + "Version", W));

        int bodyH = H - rows.size() - 3;
        for (int i = 0; i < bodyH; i++) {
            if (i < procs.size()) {
                ProcessInfo p = procs.get(i);
                int cw = Math.max(15, W-42);
                String line = "  " + pad(String.valueOf(p.pid()),10) + pad(trn(p.mainClass(),cw-1),cw) + trn(p.javaVersion(),20);
                rows.add(i == psIdx ? hlRow(line, W) : boxRow(line, W));
            } else { rows.add(emptyRow(W)); }
        }
        rows.add(midLine(W));
        rows.add(centerColorRow(DIM, "↑↓ select  ⏎ connect  r refresh  l lang  t theme  q quit", W));
        rows.add(botLine(W));

        for (String r : rows) s.append(ml).append(r).append("\n");

        if (langSelect) drawLangOverlay(s, W, H);
    }

    private void drawCMD(StringBuilder s, int W, int H, String ml) {
        List<String> rows = new ArrayList<>();
        rows.add(topLine(W));
        rows.add(colorRow(acc(), "  ⚡ ARGUS   " + trn(pidName,20) + "   pid:" + pid + "   " + LANGS[langIdx].toUpperCase(), W));
        rows.add(midLine(W));

        int bodyH = H - rows.size() - 3;
        if (cIdx >= cScr+bodyH) cScr = cIdx-bodyH+1;
        if (cIdx < cScr) cScr = cIdx;

        int drawn = 0;
        for (int i = cScr; i < Math.min(fCmds.size(), cScr+bodyH) && drawn < bodyH; i++) {
            CE e = fCmds.get(i);
            if (e.h) {
                rows.add(colorRow(acc(), "  ▸ " + e.n, W));
            } else {
                String desc = e.c != null ? trn(e.c.description(messages), W-26) : "";
                if (i == cIdx) {
                    rows.add(hlRow("    " + pad(e.n, 18) + desc, W));
                } else {
                    // Command name bold+color, description dim
                    String plain = "    " + pad(e.n, 18) + desc;
                    String colored = fg()+"│"+R+" " + "   \033[1m"+fg()+pad(e.n,18)+R+DIM+desc+R;
                    int plen = plain.length();
                    rows.add(colored+" ".repeat(Math.max(0,W-3-plen))+fg()+"│"+R);
                }
            }
            drawn++;
        }
        for (int i = drawn; i < bodyH; i++) rows.add(emptyRow(W));

        rows.add(midLine(W));
        if (searching) rows.add(centerRow("/" + sq + "▏", W));
        else rows.add(centerColorRow(DIM, "↑↓ navigate  ⏎ execute  / search  esc back  l lang  t theme", W));
        rows.add(botLine(W));

        for (String r : rows) s.append(ml).append(r).append("\n");

        if (langSelect) drawLangOverlay(s, W, H);
    }

    private void drawOUT(StringBuilder s, int W, int H, String ml) {
        List<String> rows = new ArrayList<>();
        rows.add(topLine(W));
        rows.add(colorRow(acc(), "  ◀ " + outName + "   pid:" + pid, W));
        rows.add(midLine(W));

        int bodyH = H - rows.size() - 3;
        String[] lines = output.split("\n");
        oScr = Math.max(0, Math.min(oScr, Math.max(0, lines.length-bodyH)));
        for (int i = 0; i < bodyH; i++) {
            int li = i + oScr;
            if (li < lines.length) {
                String plain = lines[li].replaceAll("\033\\[[\\d;]*m", "");
                rows.add(boxRow(plain, W));
            } else rows.add(emptyRow(W));
        }
        rows.add(midLine(W));
        rows.add(centerColorRow(DIM, "↑↓ scroll  esc/⏎/q back", W));
        rows.add(botLine(W));

        for (String r : rows) s.append(ml).append(r).append("\n");
    }

    private void drawLangOverlay(StringBuilder s, int W, int H) {
        // Position overlay in center
        int ow = 30, oh = LANGS.length + 4;
        int ox = (W - ow) / 2, oy = (H - oh) / 2;
        s.append("\033[").append(oy).append(";").append(ox+1).append("H");
        s.append(fg()).append("╭").append("─".repeat(ow-2)).append("╮").append(R);
        s.append("\033[").append(oy+1).append(";").append(ox+1).append("H");
        s.append(fg()).append("│").append(R).append(acc()).append(pad(" Select Language", ow-2)).append(R).append(fg()).append("│").append(R);
        for (int i = 0; i < LANGS.length; i++) {
            s.append("\033[").append(oy+2+i).append(";").append(ox+1).append("H");
            String label = " " + (i == langSelIdx ? "▶ " : "  ") + LANGS[i].toUpperCase() + " (" + langLabel(i) + ")";
            if (i == langSelIdx) {
                s.append(fg()).append("│").append(hl()).append(pad(label, ow-2)).append(R).append(fg()).append("│").append(R);
            } else {
                s.append(fg()).append("│").append(R).append(pad(label, ow-2)).append(fg()).append("│").append(R);
            }
        }
        s.append("\033[").append(oy+2+LANGS.length).append(";").append(ox+1).append("H");
        s.append(fg()).append("╰").append("─".repeat(ow-2)).append("╯").append(R);
    }

    private static String langLabel(int i) {
        return switch(i) { case 0->"English"; case 1->"한국어"; case 2->"日本語"; case 3->"中文"; default->""; };
    }

    private static String pad(String s, int w) { return s.length()>=w?s.substring(0,w):s+" ".repeat(w-s.length()); }
    private static String trn(String s, int m) { return m<=0?"":s.length()<=m?s:s.substring(0,m-1)+"…"; }

    record CE(Command c, String n, boolean h) {}
}
