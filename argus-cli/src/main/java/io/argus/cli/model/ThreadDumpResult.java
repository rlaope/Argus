package io.argus.cli.model;

import io.argus.cli.json.JsonWritable;
import io.argus.cli.render.RichRenderer;

import java.util.List;

/**
 * Result of a full thread dump capture via jcmd Thread.print.
 */
public final class ThreadDumpResult implements JsonWritable {

    private final int totalThreads;
    private final List<ThreadInfo> threads;
    private final String rawOutput;

    public ThreadDumpResult(int totalThreads, List<ThreadInfo> threads, String rawOutput) {
        this.totalThreads = totalThreads;
        this.threads = threads;
        this.rawOutput = rawOutput;
    }

    public int totalThreads() { return totalThreads; }
    public List<ThreadInfo> threads() { return threads; }
    public String rawOutput() { return rawOutput; }

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"totalThreads\":").append(totalThreads)
           .append(",\"threads\":[");
        for (int i = 0; i < threads.size(); i++) {
            ThreadInfo t = threads.get(i);
            if (i > 0) out.append(',');
            out.append("{\"name\":\"").append(RichRenderer.escapeJson(t.name())).append('"')
               .append(",\"tid\":").append(t.tid())
               .append(",\"nid\":").append(t.nid())
               .append(",\"state\":\"").append(t.state()).append('"')
               .append(",\"daemon\":").append(t.daemon())
               .append(",\"priority\":").append(t.priority());
            if (!t.waitingOn().isEmpty()) {
                out.append(",\"waitingOn\":\"").append(RichRenderer.escapeJson(t.waitingOn())).append('"');
            }
            out.append(",\"locksHeld\":[");
            for (int j = 0; j < t.locksHeld().size(); j++) {
                if (j > 0) out.append(',');
                out.append('"').append(RichRenderer.escapeJson(t.locksHeld().get(j))).append('"');
            }
            out.append("],\"stackTrace\":[");
            for (int j = 0; j < t.stackTrace().size(); j++) {
                if (j > 0) out.append(',');
                out.append('"').append(RichRenderer.escapeJson(t.stackTrace().get(j))).append('"');
            }
            out.append("]}");
        }
        out.append("]}");
    }

    public static final class ThreadInfo {
        private final String name;
        private final long tid;
        private final long nid;
        private final String state;
        private final boolean daemon;
        private final int priority;
        private final List<String> stackTrace;
        private final List<String> locksHeld;
        private final String waitingOn;

        public ThreadInfo(String name, long tid, long nid, String state, boolean daemon,
                          int priority, List<String> stackTrace, List<String> locksHeld, String waitingOn) {
            this.name = name;
            this.tid = tid;
            this.nid = nid;
            this.state = state;
            this.daemon = daemon;
            this.priority = priority;
            this.stackTrace = stackTrace;
            this.locksHeld = locksHeld;
            this.waitingOn = waitingOn;
        }

        public String name() { return name; }
        public long tid() { return tid; }
        public long nid() { return nid; }
        public String state() { return state; }
        public boolean daemon() { return daemon; }
        public int priority() { return priority; }
        public List<String> stackTrace() { return stackTrace; }
        public List<String> locksHeld() { return locksHeld; }
        public String waitingOn() { return waitingOn; }
    }
}
