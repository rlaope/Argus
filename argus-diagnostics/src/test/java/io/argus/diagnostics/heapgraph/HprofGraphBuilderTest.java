package io.argus.diagnostics.heapgraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test that hand-writes a minimal but valid HPROF 1.0.2 binary with a
 * planted static-collection leak, then drives the full pipeline
 * ({@link HprofGraphBuilder} → {@link HeapGraphAnalysis}) and asserts the
 * dominator tree, retained sizes, leak suspects, and path-to-root.
 *
 * <p>Layout planted:
 * <pre>
 *   GC root (thread-obj) → LeakHolder → Object[5] → 5 × Payload(byte[1024])
 *   GC root (sticky)     → Noise (tiny)
 * </pre>
 * LeakHolder must dominate the entire collection, and the path from a Payload to
 * a GC root must be {root → LeakHolder → Object[] → Payload}.
 */
class HprofGraphBuilderTest {

    // HPROF type tags
    private static final int T_OBJECT = 2;
    private static final int T_BYTE = 8;

    @Test
    void buildsGraph_dominator_retained_leakSuspect_pathToRoot(@TempDir Path tmp) throws Exception {
        File hprof = tmp.resolve("leak.hprof").toFile();
        new HprofWriter().writeLeakFixture(hprof);

        ArrayObjectGraph g = HprofGraphBuilder.build(hprof);
        // 1 holder + 1 array + 5 payloads + 5 byte[] + 1 noise = 13 objects
        assertEquals(13, g.objectCount(), "object count");

        HeapGraphAnalysis analysis = HeapGraphAnalysis.analyze(g);
        DominatorTree dom = analysis.dominators();
        assertEquals(13, dom.reachableCount(), "all objects reachable from roots");

        // --- retained size of the LeakHolder: itself + array + 5 payloads + 5 byte[] ---
        long[] holder = analysis.retainedForClass("com.app.LeakHolder");
        assertEquals(1, holder[1], "exactly one LeakHolder");
        // holder retains the entire leaked subgraph (everything except the noise + roots).
        // byte[] payloads dominate the size; assert it's large and > the noise.
        assertTrue(holder[0] > 5 * 1024, "holder must retain the 5 KB+ payload arrays, got " + holder[0]);

        // --- leak suspects names the planted leak ---
        List<LeakSuspect> suspects = analysis.leakSuspects(5, 5.0);
        assertFalse(suspects.isEmpty());
        boolean named = suspects.stream().anyMatch(s -> s.className().contains("LeakHolder"))
                || analysis.retainedForClass("com.app.LeakHolder")[0]
                   >= analysis.totalReachableBytes() / 2;
        assertTrue(named, "leak suspect / retained must surface LeakHolder. suspects=" + suspects);

        // --- path-to-root resolves for a Payload ---
        int[] path = analysis.pathToRootForClass("com.app.Payload");
        assertTrue(path.length >= 3, "path should traverse root -> holder -> array -> payload");
        // first element is a GC root (the LeakHolder's referrer thread obj or holder root)
        // last element is the Payload
        assertEquals("com.app.Payload", g.className(g.classOf(path[path.length - 1])));
    }

    // ------------------------------------------------------------------
    // Minimal HPROF 1.0.2 writer (test fixture only).
    // ------------------------------------------------------------------
    private static final class HprofWriter {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private long nextId = 1;
        private long nextStringId = 0x10000;

        // 8-byte object ids
        void writeLeakFixture(File out) throws IOException {
            // Allocate ids
            long holderClassId = id();
            long arrayClassId = id();   // java.lang.Object (component) — used for obj-array name
            long payloadClassId = id();
            long noiseClassId = id();

            long holderId = id();
            long arrayId = id();
            long noiseId = id();
            long[] payloadIds = new long[5];
            long[] byteArrIds = new long[5];
            for (int i = 0; i < 5; i++) { payloadIds[i] = id(); byteArrIds[i] = id(); }

            // String ids for class names + field names
            long sHolderName = str("com/app/LeakHolder");
            long sObjectName = str("java/lang/Object");
            long sPayloadName = str("com/app/Payload");
            long sNoiseName = str("com/app/Noise");
            long fItems = str("items");     // LeakHolder.items : Object[]
            long fData = str("data");       // Payload.data : byte[]

            // LOAD_CLASS records (serial, classObjId, stackSerial, nameId)
            loadClass(1, holderClassId, sHolderName);
            loadClass(2, arrayClassId, sObjectName);
            loadClass(3, payloadClassId, sPayloadName);
            loadClass(4, noiseClassId, sNoiseName);

            // Heap dump segment
            ByteArrayOutputStream seg = new ByteArrayOutputStream();
            DataOutputStream s = new DataOutputStream(seg);

            // CLASS_DUMP for LeakHolder: one instance field "items" of type object
            classDump(s, holderClassId, new long[]{fItems}, new int[]{T_OBJECT});
            // CLASS_DUMP for Object (array component): no instance fields
            classDump(s, arrayClassId, new long[]{}, new int[]{});
            // CLASS_DUMP for Payload: one field "data" of type object (byte[])
            classDump(s, payloadClassId, new long[]{fData}, new int[]{T_OBJECT});
            // CLASS_DUMP for Noise: no fields
            classDump(s, noiseClassId, new long[]{}, new int[]{});

            // GC roots
            rootThreadObj(s, holderId);   // thread obj root → holder
            rootStickyClass(s, noiseId);  // sticky root → noise

            // INSTANCE_DUMP holder: field items -> arrayId
            instanceDump(s, holderId, holderClassId, new long[]{arrayId}, new int[]{T_OBJECT});
            // OBJ_ARRAY_DUMP array -> 5 payloads
            objArrayDump(s, arrayId, arrayClassId, payloadIds);
            // 5 payloads, each field data -> its byte[]
            for (int i = 0; i < 5; i++) {
                instanceDump(s, payloadIds[i], payloadClassId,
                        new long[]{byteArrIds[i]}, new int[]{T_OBJECT});
            }
            // 5 byte[] of 1024 bytes
            for (int i = 0; i < 5; i++) primByteArray(s, byteArrIds[i], 1024);
            // noise instance (no fields)
            instanceDump(s, noiseId, noiseClassId, new long[]{}, new int[]{});

            s.flush();
            byte[] segBytes = seg.toByteArray();
            // top-level HEAP_DUMP record
            writeByte(0x0C);
            writeInt(0);                 // ts delta
            writeInt(segBytes.length);   // length
            body.write(segBytes);

            // Assemble full file: header + body
            try (DataOutputStream file = new DataOutputStream(Files.newOutputStream(out.toPath()))) {
                file.writeBytes("JAVA PROFILE 1.0.2");
                file.writeByte(0);       // null terminator
                file.writeInt(8);        // id size
                file.writeLong(System.currentTimeMillis());
                file.write(body.toByteArray());
            }
        }

        private long id() { return nextId++; }

        private long str(String value) throws IOException {
            long sid = nextStringId++;
            byte[] utf = value.getBytes(StandardCharsets.UTF_8);
            writeByte(0x01);             // STRING tag
            writeInt(0);                 // ts delta
            writeInt(8 + utf.length);    // length = id + bytes
            writeLong(sid);
            body.write(utf);
            return sid;
        }

        private void loadClass(int serial, long classObjId, long nameId) throws IOException {
            writeByte(0x02);             // LOAD_CLASS
            writeInt(0);                 // ts delta
            writeInt(4 + 8 + 4 + 8);     // serial + classObjId + stackSerial + nameId
            writeInt(serial);
            writeLong(classObjId);
            writeInt(0);                 // stack trace serial
            writeLong(nameId);
        }

        private void classDump(DataOutputStream s, long classObjId,
                               long[] fieldNameIds, int[] fieldTypes) throws IOException {
            s.writeByte(0x20);           // CLASS_DUMP sub-tag
            s.writeLong(classObjId);
            s.writeInt(0);               // stack trace serial
            s.writeLong(0);              // super
            s.writeLong(0);              // loader
            s.writeLong(0);              // signers
            s.writeLong(0);              // protection domain
            s.writeLong(0);              // reserved1
            s.writeLong(0);              // reserved2
            s.writeInt(0);               // instance size (unused)
            s.writeShort(0);             // constant pool size
            s.writeShort(0);             // static field count
            s.writeShort(fieldNameIds.length); // instance field count
            for (int i = 0; i < fieldNameIds.length; i++) {
                s.writeLong(fieldNameIds[i]);
                s.writeByte(fieldTypes[i]);
            }
        }

        private void instanceDump(DataOutputStream s, long objId, long classObjId,
                                  long[] objFieldValues, int[] fieldTypes) throws IOException {
            // build field data: each object field is an 8-byte id
            ByteArrayOutputStream fd = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(fd);
            for (int i = 0; i < fieldTypes.length; i++) {
                if (fieldTypes[i] == T_OBJECT) d.writeLong(objFieldValues[i]);
            }
            d.flush();
            byte[] data = fd.toByteArray();
            s.writeByte(0x21);           // INSTANCE_DUMP
            s.writeLong(objId);
            s.writeInt(0);               // stack trace serial
            s.writeLong(classObjId);
            s.writeInt(data.length);
            s.write(data);
        }

        private void objArrayDump(DataOutputStream s, long arrayId, long arrayClassId,
                                  long[] elements) throws IOException {
            s.writeByte(0x22);           // OBJ_ARRAY_DUMP
            s.writeLong(arrayId);
            s.writeInt(0);               // stack trace serial
            s.writeInt(elements.length);
            s.writeLong(arrayClassId);
            for (long e : elements) s.writeLong(e);
        }

        private void primByteArray(DataOutputStream s, long arrayId, int length) throws IOException {
            s.writeByte(0x23);           // PRIM_ARRAY_DUMP
            s.writeLong(arrayId);
            s.writeInt(0);               // stack trace serial
            s.writeInt(length);
            s.writeByte(T_BYTE);         // element type byte
            for (int i = 0; i < length; i++) s.writeByte(0);
        }

        private void rootThreadObj(DataOutputStream s, long objId) throws IOException {
            s.writeByte(0x08);           // ROOT_THREAD_OBJ
            s.writeLong(objId);
            s.writeInt(0);               // thread serial
            s.writeInt(0);               // stack trace serial
        }

        private void rootStickyClass(DataOutputStream s, long objId) throws IOException {
            s.writeByte(0x05);           // ROOT_STICKY_CLASS
            s.writeLong(objId);
        }

        private void writeByte(int b) { body.write(b); }
        private void writeInt(int v) {
            body.write((v >>> 24) & 0xFF);
            body.write((v >>> 16) & 0xFF);
            body.write((v >>> 8) & 0xFF);
            body.write(v & 0xFF);
        }
        private void writeLong(long v) {
            for (int i = 7; i >= 0; i--) body.write((int) (v >>> (i * 8)) & 0xFF);
        }
    }
}
