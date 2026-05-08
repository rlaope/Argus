package io.argus.cli.heapanalyze;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Summary of an HPROF heap dump analysis.
 */
public final class HprofSummary {
    private final String fileName;
    private final long fileSize;
    private final int idSize;
    private final Map<String, long[]> histogram;   // className → [count, shallowBytes]
    private final long totalInstances;
    private final long totalBytes;
    private final long totalArrays;
    private final long totalArrayBytes;
    private final long stringCount;
    private final int classCount;

    public HprofSummary(String fileName, long fileSize, int idSize,
                        Map<String, long[]> histogram,
                        long totalInstances, long totalBytes,
                        long totalArrays, long totalArrayBytes,
                        long stringCount, int classCount) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.idSize = idSize;
        this.histogram = histogram;
        this.totalInstances = totalInstances;
        this.totalBytes = totalBytes;
        this.totalArrays = totalArrays;
        this.totalArrayBytes = totalArrayBytes;
        this.stringCount = stringCount;
        this.classCount = classCount;
    }

    public String fileName() { return fileName; }
    public long fileSize() { return fileSize; }
    public int idSize() { return idSize; }
    public Map<String, long[]> histogram() { return histogram; }
    public long totalInstances() { return totalInstances; }
    public long totalBytes() { return totalBytes; }
    public long totalArrays() { return totalArrays; }
    public long totalArrayBytes() { return totalArrayBytes; }
    public long stringCount() { return stringCount; }
    public int classCount() { return classCount; }

    /**
     * Returns the top N classes by shallow size, descending.
     */
    public List<Map.Entry<String, long[]>> topBySize(int n) {
        return histogram.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .limit(n)
                .collect(Collectors.toList());
    }

    /**
     * Returns the top N classes by instance count, descending.
     */
    public List<Map.Entry<String, long[]>> topByCount(int n) {
        return histogram.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(n)
                .collect(Collectors.toList());
    }

    /**
     * Returns total object count (instances + arrays).
     */
    public long totalObjects() {
        return totalInstances + totalArrays;
    }

    /**
     * Returns total shallow bytes (instances + arrays).
     */
    public long totalShallowBytes() {
        return totalBytes + totalArrayBytes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HprofSummary)) return false;
        HprofSummary that = (HprofSummary) o;
        return fileSize == that.fileSize
                && idSize == that.idSize
                && totalInstances == that.totalInstances
                && totalBytes == that.totalBytes
                && totalArrays == that.totalArrays
                && totalArrayBytes == that.totalArrayBytes
                && stringCount == that.stringCount
                && classCount == that.classCount
                && java.util.Objects.equals(fileName, that.fileName)
                && java.util.Objects.equals(histogram, that.histogram);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fileName, fileSize, idSize, histogram,
                totalInstances, totalBytes, totalArrays, totalArrayBytes,
                stringCount, classCount);
    }

    @Override
    public String toString() {
        return "HprofSummary[fileName=" + fileName + ", fileSize=" + fileSize
                + ", idSize=" + idSize + ", histogram=" + histogram
                + ", totalInstances=" + totalInstances + ", totalBytes=" + totalBytes
                + ", totalArrays=" + totalArrays + ", totalArrayBytes=" + totalArrayBytes
                + ", stringCount=" + stringCount + ", classCount=" + classCount + "]";
    }
}
