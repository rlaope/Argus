package io.argus.cli.heapanalyze;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the HPROF binary parser.
 * Generates minimal valid HPROF binary data programmatically.
 */
class HprofParserTest {

    private static final int ID_SIZE = 4;

    @Test
    void parseMinimalHprof_producesValidSummary(@TempDir Path tempDir) throws Exception {
        File hprof = tempDir.resolve("test.hprof").toFile();
        writeMinimalHprof(hprof);

        HprofSummary summary = HprofParser.parse(hprof);

        assertNotNull(summary);
        assertEquals("test.hprof", summary.fileName());
        assertTrue(summary.fileSize() > 0);
        assertEquals(ID_SIZE, summary.idSize());
    }

    @Test
    void parseWithInstances_countsCorrectly(@TempDir Path tempDir) throws Exception {
        File hprof = tempDir.resolve("instances.hprof").toFile();
        writeHprofWithInstances(hprof, 5);

        HprofSummary summary = HprofParser.parse(hprof);

        assertEquals(5, summary.totalInstances());
        assertTrue(summary.totalBytes() > 0);
        assertFalse(summary.histogram().isEmpty());
    }

    @Test
    void parseWithArrays_countsCorrectly(@TempDir Path tempDir) throws Exception {
        File hprof = tempDir.resolve("arrays.hprof").toFile();
        writeHprofWithPrimArrays(hprof, 3);

        HprofSummary summary = HprofParser.parse(hprof);

        assertEquals(3, summary.totalArrays());
        assertTrue(summary.totalArrayBytes() > 0);
        assertTrue(summary.histogram().containsKey("byte[]"));
    }

    @Test
    void topBySize_returnsDescendingOrder(@TempDir Path tempDir) throws Exception {
        File hprof = tempDir.resolve("top.hprof").toFile();
        writeHprofWithInstances(hprof, 10);

        HprofSummary summary = HprofParser.parse(hprof);
        var top = summary.topBySize(5);

        assertTrue(top.size() <= 5);
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i - 1).getValue()[1] >= top.get(i).getValue()[1],
                    "topBySize should be descending by shallow bytes");
        }
    }

    @Test
    void topByCount_returnsDescendingOrder(@TempDir Path tempDir) throws Exception {
        File hprof = tempDir.resolve("topcount.hprof").toFile();
        writeHprofWithInstances(hprof, 10);

        HprofSummary summary = HprofParser.parse(hprof);
        var top = summary.topByCount(5);

        assertTrue(top.size() <= 5);
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i - 1).getValue()[0] >= top.get(i).getValue()[0],
                    "topByCount should be descending by instance count");
        }
    }

    @Test
    void invalidFile_throwsIOException(@TempDir Path tempDir) throws Exception {
        File bad = tempDir.resolve("bad.hprof").toFile();
        try (var fos = new FileOutputStream(bad)) {
            fos.write("NOT A HPROF FILE".getBytes(StandardCharsets.UTF_8));
        }

        assertThrows(IOException.class, () -> HprofParser.parse(bad));
    }

    @Test
    void emptyHeapDump_returnsZeroCounts(@TempDir Path tempDir) throws Exception {
        File hprof = tempDir.resolve("empty.hprof").toFile();
        writeMinimalHprof(hprof);

        HprofSummary summary = HprofParser.parse(hprof);

        assertEquals(0, summary.totalInstances());
        assertEquals(0, summary.totalArrays());
        assertEquals(0, summary.totalObjects());
    }

    @Test
    void totalObjects_equalsInstancesPlusArrays(@TempDir Path tempDir) throws Exception {
        File hprof = tempDir.resolve("mixed.hprof").toFile();
        writeHprofWithMixed(hprof, 4, 3);

        HprofSummary summary = HprofParser.parse(hprof);

        assertEquals(4, summary.totalInstances());
        assertEquals(3, summary.totalArrays());
        assertEquals(7, summary.totalObjects());
        assertEquals(summary.totalBytes() + summary.totalArrayBytes(), summary.totalShallowBytes());
    }

    // --- HPROF binary writers ---

    private void writeMinimalHprof(File file) throws IOException {
        try (var dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            writeHeader(dos);
        }
    }

    private void writeHprofWithInstances(File file, int count) throws IOException {
        try (var dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            writeHeader(dos);

            // STRING record for class name
            long stringId = 1L;
            byte[] nameBytes = "com.example.TestClass".getBytes(StandardCharsets.UTF_8);
            writeRecord(dos, 0x01, ID_SIZE + nameBytes.length); // TAG_STRING
            writeId(dos, stringId);
            dos.write(nameBytes);

            // LOAD_CLASS record
            long classObjId = 100L;
            writeRecord(dos, 0x02, 4 + ID_SIZE + 4 + ID_SIZE); // TAG_LOAD_CLASS
            dos.writeInt(1); // serial
            writeId(dos, classObjId);
            dos.writeInt(0); // stack trace serial
            writeId(dos, stringId);

            // HEAP_DUMP_SEG with instances
            ByteArrayOutputStream heapBuf = new ByteArrayOutputStream();
            DataOutputStream heap = new DataOutputStream(heapBuf);

            // CLASS_DUMP sub-record
            heap.writeByte(0x20); // SUB_GC_CLASS_DUMP
            writeId(heap, classObjId);
            heap.writeInt(0); // stack trace serial
            writeId(heap, 0); // super
            writeId(heap, 0); // class loader
            writeId(heap, 0); // signers
            writeId(heap, 0); // protection domain
            writeId(heap, 0); // reserved1
            writeId(heap, 0); // reserved2
            heap.writeInt(16); // instance size
            heap.writeShort(0); // constant pool size
            heap.writeShort(0); // static fields
            heap.writeShort(0); // instance fields

            // INSTANCE_DUMP sub-records
            for (int i = 0; i < count; i++) {
                heap.writeByte(0x21); // SUB_GC_INSTANCE_DUMP
                writeId(heap, 200 + i); // object ID
                heap.writeInt(0); // stack trace serial
                writeId(heap, classObjId); // class ID
                heap.writeInt(16); // data size
                heap.write(new byte[16]); // field data
            }

            byte[] heapData = heapBuf.toByteArray();
            writeRecord(dos, 0x1C, heapData.length); // TAG_HEAP_DUMP_SEG
            dos.write(heapData);
        }
    }

    private void writeHprofWithPrimArrays(File file, int count) throws IOException {
        try (var dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            writeHeader(dos);

            ByteArrayOutputStream heapBuf = new ByteArrayOutputStream();
            DataOutputStream heap = new DataOutputStream(heapBuf);

            for (int i = 0; i < count; i++) {
                heap.writeByte(0x23); // SUB_GC_PRIM_ARRAY_DUMP
                writeId(heap, 300 + i); // array object ID
                heap.writeInt(0); // stack trace serial
                heap.writeInt(32); // num elements
                heap.writeByte(8); // byte type
                heap.write(new byte[32]); // data
            }

            byte[] heapData = heapBuf.toByteArray();
            writeRecord(dos, 0x1C, heapData.length);
            dos.write(heapData);
        }
    }

    private void writeHprofWithMixed(File file, int instances, int arrays) throws IOException {
        try (var dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            writeHeader(dos);

            // String + LoadClass for instances
            long stringId = 1L;
            byte[] nameBytes = "com.example.Mixed".getBytes(StandardCharsets.UTF_8);
            writeRecord(dos, 0x01, ID_SIZE + nameBytes.length);
            writeId(dos, stringId);
            dos.write(nameBytes);

            long classObjId = 100L;
            writeRecord(dos, 0x02, 4 + ID_SIZE + 4 + ID_SIZE);
            dos.writeInt(1);
            writeId(dos, classObjId);
            dos.writeInt(0);
            writeId(dos, stringId);

            ByteArrayOutputStream heapBuf = new ByteArrayOutputStream();
            DataOutputStream heap = new DataOutputStream(heapBuf);

            // Class dump
            heap.writeByte(0x20);
            writeId(heap, classObjId);
            heap.writeInt(0);
            for (int j = 0; j < 6; j++) writeId(heap, 0);
            heap.writeInt(8);
            heap.writeShort(0);
            heap.writeShort(0);
            heap.writeShort(0);

            // Instances
            for (int i = 0; i < instances; i++) {
                heap.writeByte(0x21);
                writeId(heap, 200 + i);
                heap.writeInt(0);
                writeId(heap, classObjId);
                heap.writeInt(8);
                heap.write(new byte[8]);
            }

            // Prim arrays
            for (int i = 0; i < arrays; i++) {
                heap.writeByte(0x23);
                writeId(heap, 400 + i);
                heap.writeInt(0);
                heap.writeInt(16);
                heap.writeByte(8); // byte
                heap.write(new byte[16]);
            }

            byte[] heapData = heapBuf.toByteArray();
            writeRecord(dos, 0x1C, heapData.length);
            dos.write(heapData);
        }
    }

    private void writeHeader(DataOutputStream dos) throws IOException {
        // "JAVA PROFILE 1.0.2\0"
        dos.write("JAVA PROFILE 1.0.2".getBytes(StandardCharsets.UTF_8));
        dos.writeByte(0);
        dos.writeInt(ID_SIZE);
        dos.writeLong(System.currentTimeMillis());
    }

    private void writeRecord(DataOutputStream dos, int tag, int length) throws IOException {
        dos.writeByte(tag);
        dos.writeInt(0); // timestamp delta
        dos.writeInt(length);
    }

    private void writeId(DataOutputStream dos, long id) throws IOException {
        dos.writeInt((int) id);
    }
}
