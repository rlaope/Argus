package io.argus.cli.heapanalyze;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Streaming HPROF binary parser. Memory-efficient — processes records
 * sequentially without loading the entire dump into memory.
 *
 * <p>Supports HPROF 1.0.1 and 1.0.2 format (Java 6+).
 * Extracts: class histogram, string table, instance counts, shallow sizes.
 *
 * @see <a href="https://hg.openjdk.org/jdk/jdk/file/tip/src/hotspot/share/services/heapDumper.cpp">HPROF format spec</a>
 */
public final class HprofParser {

    // Top-level record tags
    private static final int TAG_STRING        = 0x01;
    private static final int TAG_LOAD_CLASS    = 0x02;
    private static final int TAG_HEAP_DUMP     = 0x0C;
    private static final int TAG_HEAP_DUMP_SEG = 0x1C;
    private static final int TAG_HEAP_DUMP_END = 0x2C;

    // Heap dump sub-record tags
    private static final int SUB_GC_ROOT_UNKNOWN     = 0xFF;
    private static final int SUB_GC_ROOT_THREAD_OBJ  = 0x08;
    private static final int SUB_GC_ROOT_JNI_GLOBAL  = 0x01;
    private static final int SUB_GC_ROOT_JNI_LOCAL   = 0x02;
    private static final int SUB_GC_ROOT_JAVA_FRAME  = 0x03;
    private static final int SUB_GC_ROOT_NATIVE_STACK = 0x04;
    private static final int SUB_GC_ROOT_STICKY_CLASS = 0x05;
    private static final int SUB_GC_ROOT_THREAD_BLOCK = 0x06;
    private static final int SUB_GC_ROOT_MONITOR_USED = 0x07;
    private static final int SUB_GC_CLASS_DUMP       = 0x20;
    private static final int SUB_GC_INSTANCE_DUMP    = 0x21;
    private static final int SUB_GC_OBJ_ARRAY_DUMP   = 0x22;
    private static final int SUB_GC_PRIM_ARRAY_DUMP  = 0x23;

    private HprofParser() {}

    /**
     * Parses an HPROF file and returns a summary.
     *
     * @param file path to .hprof file
     * @return analysis summary
     */
    public static HprofSummary parse(File file) throws IOException {
        try (var fis = new FileInputStream(file);
             var bis = new BufferedInputStream(fis, 1 << 16)) {
            DataInputStream in = new DataInputStream(bis);

            // Header: "JAVA PROFILE 1.0.2\0"
            String header = readCString(in);
            if (!header.startsWith("JAVA PROFILE")) {
                throw new IOException("Not a valid HPROF file: " + header);
            }

            int idSize = in.readInt(); // 4 or 8 bytes
            in.readLong(); // timestamp (ignored)

            // State
            Map<Long, String> strings = new HashMap<>();      // string ID → UTF-8
            Map<Long, Long> classSerialToId = new HashMap<>(); // serial → class obj ID
            Map<Long, Long> classIdToNameId = new HashMap<>(); // class obj ID → string ID
            Map<Long, ClassInfo> classes = new HashMap<>();     // class obj ID → ClassInfo

            // Aggregation
            Map<String, long[]> histogram = new HashMap<>();   // className → [count, shallowBytes]
            Map<String, Integer> dupStrings = new HashMap<>();  // string value → count
            long totalInstances = 0;
            long totalBytes = 0;
            long totalArrays = 0;
            long totalArrayBytes = 0;
            long stringCount = 0;

            // Parse records
            while (true) {
                int tag;
                try {
                    tag = in.readUnsignedByte();
                } catch (EOFException e) {
                    break;
                }

                in.readInt(); // timestamp delta (ignored)
                long length = Integer.toUnsignedLong(in.readInt());

                switch (tag) {
                    case TAG_STRING -> {
                        long id = readId(in, idSize);
                        int strLen = (int) (length - idSize);
                        byte[] buf = new byte[strLen];
                        in.readFully(buf);
                        strings.put(id, new String(buf, StandardCharsets.UTF_8));
                    }
                    case TAG_LOAD_CLASS -> {
                        int serial = in.readInt();
                        long classObjId = readId(in, idSize);
                        in.readInt(); // stack trace serial
                        long nameId = readId(in, idSize);
                        classSerialToId.put((long) serial, classObjId);
                        classIdToNameId.put(classObjId, nameId);
                    }
                    case TAG_HEAP_DUMP, TAG_HEAP_DUMP_SEG -> {
                        // Parse sub-records within the heap dump segment
                        long remaining = length;
                        while (remaining > 0) {
                            int subTag = in.readUnsignedByte();
                            remaining--;

                            switch (subTag) {
                                case SUB_GC_CLASS_DUMP -> {
                                    long classObjId = readId(in, idSize);
                                    in.readInt(); // stack trace serial
                                    long superClassId = readId(in, idSize);
                                    readId(in, idSize); // class loader
                                    readId(in, idSize); // signers
                                    readId(in, idSize); // protection domain
                                    readId(in, idSize); // reserved1
                                    readId(in, idSize); // reserved2
                                    int instanceSize = in.readInt();
                                    remaining -= idSize * 7 + 4 + 4;

                                    // Constant pool
                                    int cpSize = in.readUnsignedShort();
                                    remaining -= 2;
                                    for (int i = 0; i < cpSize; i++) {
                                        in.readUnsignedShort(); // index
                                        int cpType = in.readUnsignedByte();
                                        remaining -= 3;
                                        int sz = typeSize(cpType, idSize);
                                        skipBytes(in, sz);
                                        remaining -= sz;
                                    }

                                    // Static fields
                                    int sfCount = in.readUnsignedShort();
                                    remaining -= 2;
                                    for (int i = 0; i < sfCount; i++) {
                                        readId(in, idSize); // name ID
                                        int sfType = in.readUnsignedByte();
                                        remaining -= idSize + 1;
                                        int sz = typeSize(sfType, idSize);
                                        skipBytes(in, sz);
                                        remaining -= sz;
                                    }

                                    // Instance fields (descriptors only)
                                    int ifCount = in.readUnsignedShort();
                                    remaining -= 2;
                                    int fieldsDataSize = 0;
                                    for (int i = 0; i < ifCount; i++) {
                                        readId(in, idSize); // name ID
                                        int ifType = in.readUnsignedByte();
                                        remaining -= idSize + 1;
                                        fieldsDataSize += typeSize(ifType, idSize);
                                    }

                                    classes.put(classObjId, new ClassInfo(instanceSize, superClassId, fieldsDataSize));
                                }
                                case SUB_GC_INSTANCE_DUMP -> {
                                    readId(in, idSize); // object ID
                                    in.readInt(); // stack trace serial
                                    long classId = readId(in, idSize);
                                    int dataSize = in.readInt();
                                    skipBytes(in, dataSize);
                                    remaining -= idSize * 2 + 4 + 4 + dataSize;

                                    String className = resolveClassName(classId, classIdToNameId, strings);
                                    long shallowSize = idSize + 4 + idSize + 4 + dataSize; // header + fields
                                    histogram.computeIfAbsent(className, k -> new long[2]);
                                    histogram.get(className)[0]++;
                                    histogram.get(className)[1] += shallowSize;
                                    totalInstances++;
                                    totalBytes += shallowSize;

                                    // Track duplicate strings
                                    if ("java.lang.String".equals(className)) {
                                        stringCount++;
                                    }
                                }
                                case SUB_GC_OBJ_ARRAY_DUMP -> {
                                    readId(in, idSize); // array object ID
                                    in.readInt(); // stack trace serial
                                    int numElements = in.readInt();
                                    long arrayClassId = readId(in, idSize);
                                    long dataSize = (long) numElements * idSize;
                                    skipBytes(in, dataSize);
                                    remaining -= idSize * 2 + 4 + 4 + dataSize;

                                    String className = resolveClassName(arrayClassId, classIdToNameId, strings) + "[]";
                                    long shallowSize = idSize + 4 + 4 + idSize + dataSize;
                                    histogram.computeIfAbsent(className, k -> new long[2]);
                                    histogram.get(className)[0]++;
                                    histogram.get(className)[1] += shallowSize;
                                    totalArrays++;
                                    totalArrayBytes += shallowSize;
                                }
                                case SUB_GC_PRIM_ARRAY_DUMP -> {
                                    readId(in, idSize); // array object ID
                                    in.readInt(); // stack trace serial
                                    int numElements = in.readInt();
                                    int elemType = in.readUnsignedByte();
                                    int elemSize = typeSize(elemType, idSize);
                                    long dataSize = (long) numElements * elemSize;
                                    skipBytes(in, dataSize);
                                    remaining -= idSize + 4 + 4 + 1 + dataSize;

                                    String className = primArrayName(elemType);
                                    long shallowSize = idSize + 4 + 4 + 1 + dataSize;
                                    histogram.computeIfAbsent(className, k -> new long[2]);
                                    histogram.get(className)[0]++;
                                    histogram.get(className)[1] += shallowSize;
                                    totalArrays++;
                                    totalArrayBytes += shallowSize;

                                    // Track char[]/byte[] for duplicate string analysis
                                    if (elemType == 5 /* char */ || elemType == 8 /* byte */) {
                                        // We can't easily extract string values in streaming mode
                                        // without building a full reference graph
                                    }
                                }
                                // GC roots — skip
                                case SUB_GC_ROOT_UNKNOWN -> {
                                    readId(in, idSize);
                                    remaining -= idSize;
                                }
                                case SUB_GC_ROOT_JNI_GLOBAL -> {
                                    readId(in, idSize);
                                    readId(in, idSize);
                                    remaining -= idSize * 2;
                                }
                                case SUB_GC_ROOT_JNI_LOCAL, SUB_GC_ROOT_JAVA_FRAME,
                                     SUB_GC_ROOT_THREAD_BLOCK -> {
                                    readId(in, idSize);
                                    in.readInt();
                                    in.readInt();
                                    remaining -= idSize + 8;
                                }
                                case SUB_GC_ROOT_NATIVE_STACK, SUB_GC_ROOT_THREAD_OBJ -> {
                                    readId(in, idSize);
                                    in.readInt();
                                    in.readInt();
                                    remaining -= idSize + 8;
                                }
                                case SUB_GC_ROOT_STICKY_CLASS -> {
                                    readId(in, idSize);
                                    remaining -= idSize;
                                }
                                case SUB_GC_ROOT_MONITOR_USED -> {
                                    readId(in, idSize);
                                    remaining -= idSize;
                                }
                                default -> {
                                    // Unknown sub-record, skip remaining
                                    if (remaining > 0) {
                                        skipBytes(in, remaining);
                                        remaining = 0;
                                    }
                                }
                            }
                        }
                    }
                    case TAG_HEAP_DUMP_END -> {
                        // No body
                    }
                    default -> {
                        // Skip unknown records
                        if (length > 0) {
                            skipBytes(in, length);
                        }
                    }
                }
            }

            return new HprofSummary(
                    file.getName(),
                    file.length(),
                    idSize,
                    histogram,
                    totalInstances,
                    totalBytes,
                    totalArrays,
                    totalArrayBytes,
                    stringCount,
                    classes.size()
            );
        }
    }

    private static String readCString(DataInputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.readUnsignedByte()) != 0) {
            baos.write(b);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static long readId(DataInputStream in, int idSize) throws IOException {
        if (idSize == 4) return Integer.toUnsignedLong(in.readInt());
        return in.readLong();
    }

    private static void skipBytes(DataInputStream in, long count) throws IOException {
        long skipped = 0;
        while (skipped < count) {
            long n = in.skip(count - skipped);
            if (n <= 0) {
                // skip() can return 0; fallback to read
                in.readByte();
                skipped++;
            } else {
                skipped += n;
            }
        }
    }

    private static int typeSize(int type, int idSize) {
        return switch (type) {
            case 2 -> idSize;  // object
            case 4 -> 1;       // boolean
            case 5 -> 2;       // char
            case 6 -> 4;       // float
            case 7 -> 8;       // double
            case 8 -> 1;       // byte
            case 9 -> 2;       // short
            case 10 -> 4;      // int
            case 11 -> 8;      // long
            default -> idSize; // unknown, assume ID
        };
    }

    private static String primArrayName(int type) {
        return switch (type) {
            case 4 -> "boolean[]";
            case 5 -> "char[]";
            case 6 -> "float[]";
            case 7 -> "double[]";
            case 8 -> "byte[]";
            case 9 -> "short[]";
            case 10 -> "int[]";
            case 11 -> "long[]";
            default -> "unknown[]";
        };
    }

    private static String resolveClassName(long classId, Map<Long, Long> classIdToNameId,
                                           Map<Long, String> strings) {
        Long nameId = classIdToNameId.get(classId);
        if (nameId == null) return "unknown_class_0x" + Long.toHexString(classId);
        String name = strings.get(nameId);
        if (name == null) return "unknown_name_0x" + Long.toHexString(nameId);
        return name.replace('/', '.');
    }

    private record ClassInfo(int instanceSize, long superClassId, int fieldsDataSize) {}
}
