package io.argus.cli.model;

import io.argus.cli.json.JsonWritable;
import io.argus.cli.render.RichRenderer;

import java.util.List;
import java.util.Map;

/**
 * Thread pool analysis result.
 */
public final class PoolResult implements JsonWritable {
    private final int totalThreads;
    private final int totalPools;
    private final List<PoolInfo> pools;

    public PoolResult(int totalThreads, int totalPools, List<PoolInfo> pools) {
        this.totalThreads = totalThreads;
        this.totalPools = totalPools;
        this.pools = pools;
    }

    public int totalThreads() { return totalThreads; }
    public int totalPools() { return totalPools; }
    public List<PoolInfo> pools() { return pools; }

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"totalThreads\":").append(totalThreads)
           .append(",\"totalPools\":").append(totalPools)
           .append(",\"pools\":[");
        for (int i = 0; i < pools.size(); i++) {
            PoolInfo p = pools.get(i);
            if (i > 0) out.append(',');
            out.append("{\"name\":\"").append(RichRenderer.escapeJson(p.name())).append('"')
               .append(",\"threadCount\":").append(p.threadCount())
               .append(",\"states\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> e : p.stateDistribution().entrySet()) {
                if (!first) out.append(',');
                out.append('"').append(e.getKey()).append("\":").append(e.getValue());
                first = false;
            }
            out.append("}}");
        }
        out.append("]}");
    }

    public static final class PoolInfo {
        private final String name;
        private final int threadCount;
        private final Map<String, Integer> stateDistribution;

        public PoolInfo(String name, int threadCount, Map<String, Integer> stateDistribution) {
            this.name = name;
            this.threadCount = threadCount;
            this.stateDistribution = stateDistribution;
        }

        public String name() { return name; }
        public int threadCount() { return threadCount; }
        public Map<String, Integer> stateDistribution() { return stateDistribution; }
    }
}
