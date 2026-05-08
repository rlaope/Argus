package io.argus.cli.classleak;

/**
 * A single classloader row from {@code jcmd VM.classloader_stats}.
 *
 * <p>Example line:
 * <pre>
 * 0x0000000500068770  0x0000000500069008  0x0000000cb30b9400     735   5586944   5581408  jdk.internal.loader.ClassLoaders$AppClassLoader
 * </pre>
 */
public final class ClassLoaderEntry {
    private final String address;
    private final String parent;
    private final String type;
    private final long classCount;
    private final long chunkBytes;
    private final long blockBytes;

    public ClassLoaderEntry(String address, String parent, String type,
                            long classCount, long chunkBytes, long blockBytes) {
        this.address = address;
        this.parent = parent;
        this.type = type;
        this.classCount = classCount;
        this.chunkBytes = chunkBytes;
        this.blockBytes = blockBytes;
    }

    public String address() { return address; }
    public String parent() { return parent; }
    public String type() { return type; }
    public long classCount() { return classCount; }
    public long chunkBytes() { return chunkBytes; }
    public long blockBytes() { return blockBytes; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClassLoaderEntry)) return false;
        ClassLoaderEntry that = (ClassLoaderEntry) o;
        return classCount == that.classCount
                && chunkBytes == that.chunkBytes
                && blockBytes == that.blockBytes
                && java.util.Objects.equals(address, that.address)
                && java.util.Objects.equals(parent, that.parent)
                && java.util.Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(address, parent, type, classCount, chunkBytes, blockBytes);
    }

    @Override
    public String toString() {
        return "ClassLoaderEntry[address=" + address + ", parent=" + parent
                + ", type=" + type + ", classCount=" + classCount
                + ", chunkBytes=" + chunkBytes + ", blockBytes=" + blockBytes + "]";
    }
}
