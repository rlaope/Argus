package io.argus.diagnostics.heapgraph;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a full {@link ObjectGraph} (reference edges + GC roots + retained-size
 * inputs) from an HPROF binary dump via streaming passes.
 *
 * <p><b>Memory discipline.</b> HPROF files can be tens of GB. This builder never
 * materializes heap objects as Java objects. It:
 * <ul>
 *   <li>Assigns every heap object a dense {@code int} index keyed by its HPROF
 *       id, using a single open-addressing primitive long→int map.</li>
 *   <li>Stores per-object shallow size ({@code long[]}) and class index
 *       ({@code int[]}) in flat arrays.</li>
 *   <li>Stores reference edges in CSR form ({@code int[]} targets +
 *       {@code int[]} offsets) — 4 bytes per edge.</li>
 * </ul>
 * Three streaming passes are used: (1) class structure + object id indexing +
 * GC roots, (2) per-object out-degree counting, (3) edge writing. The working
 * set is bounded by the primitive arrays, so a 4 GB dump analyzes in well under
 * 2 GB of analyzer heap.
 *
 * <p>This reuses the record layout already validated by the CLI's streaming
 * histogram parser, but additionally decodes object references.
 */
public final class HprofGraphBuilder {

    // Top-level record tags
    private static final int TAG_STRING        = 0x01;
    private static final int TAG_LOAD_CLASS    = 0x02;
    private static final int TAG_HEAP_DUMP     = 0x0C;
    private static final int TAG_HEAP_DUMP_SEG = 0x1C;
    private static final int TAG_HEAP_DUMP_END = 0x2C;

    // Heap dump sub-record tags
    private static final int SUB_GC_ROOT_UNKNOWN      = 0xFF;
    private static final int SUB_GC_ROOT_JNI_GLOBAL   = 0x01;
    private static final int SUB_GC_ROOT_JNI_LOCAL    = 0x02;
    private static final int SUB_GC_ROOT_JAVA_FRAME   = 0x03;
    private static final int SUB_GC_ROOT_NATIVE_STACK = 0x04;
    private static final int SUB_GC_ROOT_STICKY_CLASS = 0x05;
    private static final int SUB_GC_ROOT_THREAD_BLOCK = 0x06;
    private static final int SUB_GC_ROOT_MONITOR_USED = 0x07;
    private static final int SUB_GC_ROOT_THREAD_OBJ   = 0x08;
    private static final int SUB_GC_CLASS_DUMP        = 0x20;
    private static final int SUB_GC_INSTANCE_DUMP     = 0x21;
    private static final int SUB_GC_OBJ_ARRAY_DUMP    = 0x22;
    private static final int SUB_GC_PRIM_ARRAY_DUMP   = 0x23;

    private HprofGraphBuilder() {}

    /** Parses {@code file} and builds the full object graph. */
    public static ArrayObjectGraph build(File file) throws IOException {
        int idSize;
        // Header read once to learn idSize; reused per pass.
        try (DataInputStream probe = open(file)) {
            String header = readCString(probe);
            if (!header.startsWith("JAVA PROFILE")) {
                throw new IOException("Not a valid HPROF file: " + header);
            }
            idSize = probe.readInt();
        }

        // ---- Pass A: strings, class load, class dumps, object indexing, roots ----
        PassA a = new PassA(idSize);
        try (DataInputStream in = open(file)) {
            skipHeader(in, idSize);
            a.run(in);
        }

        int objCount = a.objCount;
        long[] shallow = a.shallow;          // by object index
        int[] classIdx = a.classIdx;         // by object index
        LongIntMap idToIndex = a.idToIndex;  // hprof id -> dense index

        // ---- Pass B: count out-degree per object ----
        int[] outDeg = new int[objCount];
        try (DataInputStream in = open(file)) {
            skipHeader(in, idSize);
            new EdgePass(idSize, a, idToIndex, outDeg, null, null).run(in);
        }
        // CSR offsets
        int[] edgeOff = new int[objCount + 1];
        for (int i = 0; i < objCount; i++) edgeOff[i + 1] = edgeOff[i] + outDeg[i];
        int[] edges = new int[edgeOff[objCount]];
        int[] writeCursor = new int[objCount];
        System.arraycopy(edgeOff, 0, writeCursor, 0, objCount);

        // ---- Pass C: write edges ----
        try (DataInputStream in = open(file)) {
            skipHeader(in, idSize);
            new EdgePass(idSize, a, idToIndex, null, edges, writeCursor).run(in);
        }

        // GC roots → dense indices (dedup, drop unknown ids)
        int[] rootsRaw = new int[a.roots.size()];
        int rc = 0;
        for (long rootId : a.roots) {
            int idx = idToIndex.get(rootId);
            if (idx >= 0) rootsRaw[rc++] = idx;
        }
        int[] roots = new int[rc];
        System.arraycopy(rootsRaw, 0, roots, 0, rc);

        String[] classNames = a.classNamesByIndex();

        return new ArrayObjectGraph(
                java.util.Arrays.copyOf(shallow, objCount),
                java.util.Arrays.copyOf(classIdx, objCount),
                edgeOff, edges, roots, classNames);
    }

    private static DataInputStream open(File file) throws IOException {
        InputStream fis = new FileInputStream(file);
        return new DataInputStream(new BufferedInputStream(fis, 1 << 16));
    }

    private static void skipHeader(DataInputStream in, int idSize) throws IOException {
        readCString(in);   // header string
        in.readInt();      // idSize
        in.readLong();     // timestamp
    }

    // =====================================================================
    // Pass A — class structure, object indexing, GC roots
    // =====================================================================
    private static final class PassA {
        final int idSize;

        // string table + class metadata
        final Map<Long, String> strings = new HashMap<>();
        final Map<Long, Long> classIdToNameId = new HashMap<>();
        final Map<Long, ClassDef> classDefs = new HashMap<>();

        // dense object indexing
        final LongIntMap idToIndex = new LongIntMap(1 << 16);
        long[] shallow = new long[1 << 16];
        int[] classIdx = new int[1 << 16];
        int objCount = 0;

        // class index space (one entry per distinct class obj id seen at LOAD_CLASS)
        final Map<Long, Integer> classObjIdToClassIdx = new HashMap<>();
        final List<String> classNameList = new ArrayList<>();

        final List<Long> roots = new ArrayList<>();

        PassA(int idSize) { this.idSize = idSize; }

        ClassDef classFor(long classObjId) { return classDefs.get(classObjId); }

        int classIndexFor(long classObjId) {
            Integer ci = classObjIdToClassIdx.get(classObjId);
            if (ci != null) return ci;
            int idx = classNameList.size();
            classNameList.add(resolveClassName(classObjId));
            classObjIdToClassIdx.put(classObjId, idx);
            return idx;
        }

        String resolveClassName(long classObjId) {
            Long nameId = classIdToNameId.get(classObjId);
            if (nameId == null) return "unknown_class";
            String name = strings.get(nameId);
            if (name == null) return "unknown_name";
            return name.replace('/', '.');
        }

        String[] classNamesByIndex() {
            return classNameList.toArray(new String[0]);
        }

        int addObject(long objId, int classIndex, long shallowBytes) {
            int idx = objCount++;
            if (idx >= shallow.length) {
                int cap = shallow.length + (shallow.length >> 1);
                shallow = java.util.Arrays.copyOf(shallow, cap);
                classIdx = java.util.Arrays.copyOf(classIdx, cap);
            }
            shallow[idx] = shallowBytes;
            classIdx[idx] = classIndex;
            idToIndex.put(objId, idx);
            return idx;
        }

        void run(DataInputStream in) throws IOException {
            while (true) {
                int tag;
                try { tag = in.readUnsignedByte(); }
                catch (EOFException e) { break; }
                in.readInt();                            // ts delta
                long length = Integer.toUnsignedLong(in.readInt());
                switch (tag) {
                    case TAG_STRING: {
                        long id = readId(in, idSize);
                        int len = (int) (length - idSize);
                        byte[] buf = new byte[len];
                        in.readFully(buf);
                        strings.put(id, new String(buf, StandardCharsets.UTF_8));
                        break;
                    }
                    case TAG_LOAD_CLASS: {
                        in.readInt();                     // serial
                        long classObjId = readId(in, idSize);
                        in.readInt();                     // stack trace serial
                        long nameId = readId(in, idSize);
                        classIdToNameId.put(classObjId, nameId);
                        break;
                    }
                    case TAG_HEAP_DUMP:
                    case TAG_HEAP_DUMP_SEG:
                        runHeapDump(in, length);
                        break;
                    case TAG_HEAP_DUMP_END:
                        break;
                    default:
                        if (length > 0) skipBytes(in, length);
                        break;
                }
            }
        }

        private void runHeapDump(DataInputStream in, long length) throws IOException {
            long remaining = length;
            while (remaining > 0) {
                int sub = in.readUnsignedByte();
                remaining--;
                switch (sub) {
                    case SUB_GC_CLASS_DUMP: {
                        long classObjId = readId(in, idSize);
                        in.readInt();                    // stack trace serial
                        readId(in, idSize);              // super
                        readId(in, idSize);              // loader
                        readId(in, idSize);              // signers
                        readId(in, idSize);              // protection domain
                        readId(in, idSize);              // reserved1
                        readId(in, idSize);              // reserved2
                        in.readInt();                    // instance size (unused here)
                        remaining -= idSize * 7L + 4 + 4;

                        int cpSize = in.readUnsignedShort();
                        remaining -= 2;
                        for (int i = 0; i < cpSize; i++) {
                            in.readUnsignedShort();
                            int t = in.readUnsignedByte();
                            remaining -= 3;
                            int sz = typeSize(t, idSize);
                            skipBytes(in, sz);
                            remaining -= sz;
                        }
                        int sfCount = in.readUnsignedShort();
                        remaining -= 2;
                        for (int i = 0; i < sfCount; i++) {
                            readId(in, idSize);          // name id
                            int t = in.readUnsignedByte();
                            remaining -= idSize + 1;
                            int sz = typeSize(t, idSize);
                            skipBytes(in, sz);
                            remaining -= sz;
                        }
                        int ifCount = in.readUnsignedShort();
                        remaining -= 2;
                        byte[] fieldTypes = new byte[ifCount];
                        for (int i = 0; i < ifCount; i++) {
                            readId(in, idSize);          // name id
                            int t = in.readUnsignedByte();
                            remaining -= idSize + 1;
                            fieldTypes[i] = (byte) t;
                        }
                        classDefs.put(classObjId, new ClassDef(fieldTypes));
                        classIndexFor(classObjId);       // ensure class index exists
                        break;
                    }
                    case SUB_GC_INSTANCE_DUMP: {
                        long objId = readId(in, idSize);
                        in.readInt();                    // stack trace serial
                        long classObjId = readId(in, idSize);
                        int dataSize = in.readInt();
                        skipBytes(in, dataSize);
                        remaining -= idSize * 2L + 4 + 4 + dataSize;
                        long shallowSize = (long) idSize + 4 + idSize + 4 + dataSize;
                        addObject(objId, classIndexFor(classObjId), shallowSize);
                        break;
                    }
                    case SUB_GC_OBJ_ARRAY_DUMP: {
                        long objId = readId(in, idSize);
                        in.readInt();                    // stack trace serial
                        int num = in.readInt();
                        if (num < 0) throw new IOException("corrupt HPROF: negative object-array length " + num);
                        long arrClassId = readId(in, idSize);
                        long dataSize = (long) num * idSize;
                        skipBytes(in, dataSize);
                        remaining -= idSize * 2L + 4 + 4 + dataSize;
                        long shallowSize = (long) idSize + 4 + 4 + idSize + dataSize;
                        addObject(objId, classIndexForArray(arrClassId), shallowSize);
                        break;
                    }
                    case SUB_GC_PRIM_ARRAY_DUMP: {
                        long objId = readId(in, idSize);
                        in.readInt();                    // stack trace serial
                        int num = in.readInt();
                        if (num < 0) throw new IOException("corrupt HPROF: negative primitive-array length " + num);
                        int elemType = in.readUnsignedByte();
                        int elemSize = typeSize(elemType, idSize);
                        long dataSize = (long) num * elemSize;
                        skipBytes(in, dataSize);
                        remaining -= idSize + 4L + 4 + 1 + dataSize;
                        long shallowSize = (long) idSize + 4 + 4 + 1 + dataSize;
                        addObject(objId, primClassIndex(elemType), shallowSize);
                        break;
                    }
                    case SUB_GC_ROOT_JNI_GLOBAL: {
                        long id = readId(in, idSize);
                        readId(in, idSize);
                        remaining -= idSize * 2L;
                        roots.add(id);
                        break;
                    }
                    case SUB_GC_ROOT_JNI_LOCAL:
                    case SUB_GC_ROOT_JAVA_FRAME:
                    case SUB_GC_ROOT_THREAD_BLOCK: {
                        long id = readId(in, idSize);
                        in.readInt();
                        in.readInt();
                        remaining -= idSize + 8;
                        roots.add(id);
                        break;
                    }
                    case SUB_GC_ROOT_NATIVE_STACK:
                    case SUB_GC_ROOT_THREAD_OBJ: {
                        long id = readId(in, idSize);
                        in.readInt();
                        in.readInt();
                        remaining -= idSize + 8;
                        roots.add(id);
                        break;
                    }
                    case SUB_GC_ROOT_UNKNOWN:
                    case SUB_GC_ROOT_STICKY_CLASS:
                    case SUB_GC_ROOT_MONITOR_USED: {
                        long id = readId(in, idSize);
                        remaining -= idSize;
                        roots.add(id);
                        break;
                    }
                    default: {
                        if (remaining > 0) { skipBytes(in, remaining); remaining = 0; }
                        break;
                    }
                }
            }
        }

        // class index for an object-array's component class; name decorated with []
        private final Map<Long, Integer> arrClassIdx = new HashMap<>();
        int classIndexForArray(long arrClassId) {
            Integer ci = arrClassIdx.get(arrClassId);
            if (ci != null) return ci;
            int idx = classNameList.size();
            classNameList.add(resolveClassName(arrClassId) + "[]");
            arrClassIdx.put(arrClassId, idx);
            return idx;
        }

        private int[] primClassIndexCache = new int[12];
        private boolean primInit = false;
        int primClassIndex(int elemType) {
            if (!primInit) { java.util.Arrays.fill(primClassIndexCache, -1); primInit = true; }
            if (elemType >= 0 && elemType < primClassIndexCache.length
                    && primClassIndexCache[elemType] >= 0) {
                return primClassIndexCache[elemType];
            }
            int idx = classNameList.size();
            classNameList.add(primArrayName(elemType));
            if (elemType >= 0 && elemType < primClassIndexCache.length) {
                primClassIndexCache[elemType] = idx;
            }
            return idx;
        }
    }

    // =====================================================================
    // Edge pass — counts (countOut!=null) or writes (edges!=null) references
    // =====================================================================
    private static final class EdgePass {
        final int idSize;
        final PassA a;
        final LongIntMap idToIndex;
        final int[] countOut;   // out-degree accumulator (count mode) or null
        final int[] edges;      // CSR edge array (write mode) or null
        final int[] writeCursor;

        EdgePass(int idSize, PassA a, LongIntMap idToIndex,
                 int[] countOut, int[] edges, int[] writeCursor) {
            this.idSize = idSize;
            this.a = a;
            this.idToIndex = idToIndex;
            this.countOut = countOut;
            this.edges = edges;
            this.writeCursor = writeCursor;
        }

        void emit(int fromIdx, long targetId) {
            if (targetId == 0) return;
            int t = idToIndex.get(targetId);
            if (t < 0) return; // reference to an object not in the dump (e.g. class obj)
            if (countOut != null) {
                countOut[fromIdx]++;
            } else {
                edges[writeCursor[fromIdx]++] = t;
            }
        }

        void run(DataInputStream in) throws IOException {
            while (true) {
                int tag;
                try { tag = in.readUnsignedByte(); }
                catch (EOFException e) { break; }
                in.readInt();
                long length = Integer.toUnsignedLong(in.readInt());
                switch (tag) {
                    case TAG_STRING:
                    case TAG_LOAD_CLASS:
                        if (length > 0) skipBytes(in, length);
                        break;
                    case TAG_HEAP_DUMP:
                    case TAG_HEAP_DUMP_SEG:
                        runHeapDump(in, length);
                        break;
                    case TAG_HEAP_DUMP_END:
                        break;
                    default:
                        if (length > 0) skipBytes(in, length);
                        break;
                }
            }
        }

        private void runHeapDump(DataInputStream in, long length) throws IOException {
            long remaining = length;
            while (remaining > 0) {
                int sub = in.readUnsignedByte();
                remaining--;
                switch (sub) {
                    case SUB_GC_CLASS_DUMP: {
                        readId(in, idSize);              // class obj id
                        in.readInt();                    // stack trace serial
                        readId(in, idSize);              // super
                        readId(in, idSize);              // loader
                        readId(in, idSize);              // signers
                        readId(in, idSize);              // protection domain
                        readId(in, idSize);              // reserved1
                        readId(in, idSize);              // reserved2
                        in.readInt();                    // instance size
                        remaining -= idSize * 7L + 4 + 4;
                        int cpSize = in.readUnsignedShort();
                        remaining -= 2;
                        for (int i = 0; i < cpSize; i++) {
                            in.readUnsignedShort();
                            int t = in.readUnsignedByte();
                            remaining -= 3;
                            int sz = typeSize(t, idSize);
                            skipBytes(in, sz);
                            remaining -= sz;
                        }
                        int sfCount = in.readUnsignedShort();
                        remaining -= 2;
                        for (int i = 0; i < sfCount; i++) {
                            readId(in, idSize);
                            int t = in.readUnsignedByte();
                            remaining -= idSize + 1;
                            int sz = typeSize(t, idSize);
                            skipBytes(in, sz);
                            remaining -= sz;
                        }
                        int ifCount = in.readUnsignedShort();
                        remaining -= 2;
                        for (int i = 0; i < ifCount; i++) {
                            readId(in, idSize);
                            in.readUnsignedByte();
                            remaining -= idSize + 1;
                        }
                        break;
                    }
                    case SUB_GC_INSTANCE_DUMP: {
                        long objId = readId(in, idSize);
                        in.readInt();
                        long classObjId = readId(in, idSize);
                        int dataSize = in.readInt();
                        remaining -= idSize * 2L + 4 + 4 + dataSize;
                        int fromIdx = idToIndex.get(objId);
                        ClassDef def = a.classFor(classObjId);
                        if (fromIdx >= 0 && def != null) {
                            readInstanceRefs(in, fromIdx, def, dataSize);
                        } else {
                            skipBytes(in, dataSize);
                        }
                        break;
                    }
                    case SUB_GC_OBJ_ARRAY_DUMP: {
                        long objId = readId(in, idSize);
                        in.readInt();
                        int num = in.readInt();
                        if (num < 0) throw new IOException("corrupt HPROF: negative object-array length " + num);
                        readId(in, idSize);              // array class id
                        remaining -= idSize * 2L + 4 + 4 + (long) num * idSize;
                        int fromIdx = idToIndex.get(objId);
                        for (int i = 0; i < num; i++) {
                            long elem = readId(in, idSize);
                            if (fromIdx >= 0) emit(fromIdx, elem);
                        }
                        break;
                    }
                    case SUB_GC_PRIM_ARRAY_DUMP: {
                        readId(in, idSize);
                        in.readInt();
                        int num = in.readInt();
                        if (num < 0) throw new IOException("corrupt HPROF: negative primitive-array length " + num);
                        int elemType = in.readUnsignedByte();
                        int elemSize = typeSize(elemType, idSize);
                        long dataSize = (long) num * elemSize;
                        skipBytes(in, dataSize);
                        remaining -= idSize + 4L + 4 + 1 + dataSize;
                        break;
                    }
                    case SUB_GC_ROOT_JNI_GLOBAL:
                        skipBytes(in, idSize * 2L); remaining -= idSize * 2L; break;
                    case SUB_GC_ROOT_JNI_LOCAL:
                    case SUB_GC_ROOT_JAVA_FRAME:
                    case SUB_GC_ROOT_THREAD_BLOCK:
                    case SUB_GC_ROOT_NATIVE_STACK:
                    case SUB_GC_ROOT_THREAD_OBJ:
                        skipBytes(in, idSize + 8L); remaining -= idSize + 8L; break;
                    case SUB_GC_ROOT_UNKNOWN:
                    case SUB_GC_ROOT_STICKY_CLASS:
                    case SUB_GC_ROOT_MONITOR_USED:
                        skipBytes(in, idSize); remaining -= idSize; break;
                    default:
                        if (remaining > 0) { skipBytes(in, remaining); remaining = 0; }
                        break;
                }
            }
        }

        private void readInstanceRefs(DataInputStream in, int fromIdx, ClassDef def,
                                      int dataSize) throws IOException {
            int consumed = 0;
            byte[] types = def.fieldTypes;
            for (byte tb : types) {
                int t = tb & 0xFF;
                int sz = typeSize(t, idSize);
                // Never read past the record's declared dataSize. A mismatched/duplicate
                // LOAD_CLASS id (or malformed dump) could make the resolved ClassDef's
                // declared fields total MORE than dataSize; reading them unconditionally
                // would overrun this INSTANCE_DUMP into the next record and silently
                // corrupt every subsequent record. Stop at the boundary instead.
                if (consumed + sz > dataSize) {
                    break;
                }
                if (t == 2) { // object reference
                    long target = readId(in, idSize);
                    emit(fromIdx, target);
                } else {
                    skipBytes(in, sz);
                }
                consumed += sz;
            }
            // A subclass instance carries superclass fields too; the parser only
            // knows the most-derived class's fields. Skip any trailing bytes so
            // the stream stays aligned (super-field refs are not followed here).
            if (consumed < dataSize) skipBytes(in, dataSize - consumed);
        }
    }

    // ---- shared low-level helpers ----

    private static String readCString(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int b;
        while ((b = in.readUnsignedByte()) != 0) sb.append((char) b);
        return sb.toString();
    }

    private static long readId(DataInputStream in, int idSize) throws IOException {
        return idSize == 4 ? Integer.toUnsignedLong(in.readInt()) : in.readLong();
    }

    private static void skipBytes(DataInputStream in, long count) throws IOException {
        long skipped = 0;
        while (skipped < count) {
            long nn = in.skip(count - skipped);
            if (nn <= 0) { in.readByte(); skipped++; }
            else skipped += nn;
        }
    }

    private static int typeSize(int type, int idSize) {
        switch (type) {
            case 2: return idSize; // object
            case 4: return 1;      // boolean
            case 5: return 2;      // char
            case 6: return 4;      // float
            case 7: return 8;      // double
            case 8: return 1;      // byte
            case 9: return 2;      // short
            case 10: return 4;     // int
            case 11: return 8;     // long
            default: return idSize;
        }
    }

    private static String primArrayName(int type) {
        switch (type) {
            case 4: return "boolean[]";
            case 5: return "char[]";
            case 6: return "float[]";
            case 7: return "double[]";
            case 8: return "byte[]";
            case 9: return "short[]";
            case 10: return "int[]";
            case 11: return "long[]";
            default: return "unknown[]";
        }
    }

    /** Instance-field type descriptors for one class (most-derived only). */
    private static final class ClassDef {
        final byte[] fieldTypes;
        ClassDef(byte[] fieldTypes) { this.fieldTypes = fieldTypes; }
    }
}
