package io.argus.server.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PinningTaxonomyTest {

    @Test
    void nativeFrame_isClassifiedAsNativeFrame() {
        String stack = """
                at java.base/java.net.SocketInputStream.socketRead0(Native Method)
                at com.example.LegacyIo.read(LegacyIo.java:88)
                at com.example.Handler.handle(Handler.java:30)
                """;
        assertEquals(PinningTaxonomy.NATIVE_FRAME, PinningTaxonomy.classify(stack));
    }

    @Test
    void foreignDowncall_isClassifiedAsForeignCall() {
        String stack = """
                at java.base/jdk.internal.foreign.abi.DowncallStub.invoke(DowncallStub.java:1)
                at com.example.native.Sqlite.exec(Sqlite.java:120)
                at com.example.Repo.query(Repo.java:42)
                """;
        assertEquals(PinningTaxonomy.FOREIGN_CALL, PinningTaxonomy.classify(stack));
    }

    @Test
    void publicForeignApi_isClassifiedAsForeignCall() {
        String stack = """
                at java.base/java.lang.foreign.Linker$downcallHandle.invoke(Linker.java:1)
                at com.example.Panama.call(Panama.java:9)
                """;
        assertEquals(PinningTaxonomy.FOREIGN_CALL, PinningTaxonomy.classify(stack));
    }

    @Test
    void monitorEnterWithNativeFrame_isClassifiedAsObjectMonitorInNative() {
        String stack = """
                at java.base/java.lang.Object.wait0(Native Method)
                at java.base/java.lang.Object.wait(Object.java:366)
                at com.example.Pool.borrow(Pool.java:55)
                """;
        assertEquals(PinningTaxonomy.OBJECT_MONITOR_IN_NATIVE, PinningTaxonomy.classify(stack));
    }

    @Test
    void monitorEnterPlusSeparateNativeFrame_isObjectMonitorInNative() {
        String stack = """
                at com.example.Jni.lock(Native Method)
                at java.base/java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:1)
                at com.example.Service.run(Service.java:12)
                """;
        assertEquals(PinningTaxonomy.OBJECT_MONITOR_IN_NATIVE, PinningTaxonomy.classify(stack));
    }

    @Test
    void pureJavaStack_isUnclassified() {
        String stack = """
                at com.example.Foo.bar(Foo.java:10)
                at com.example.Baz.qux(Baz.java:20)
                """;
        assertEquals(PinningTaxonomy.UNCLASSIFIED, PinningTaxonomy.classify(stack));
    }

    @Test
    void nullOrBlank_isUnclassified() {
        assertEquals(PinningTaxonomy.UNCLASSIFIED, PinningTaxonomy.classify(null));
        assertEquals(PinningTaxonomy.UNCLASSIFIED, PinningTaxonomy.classify("   \n  "));
    }

    @Test
    void labelsAreStable() {
        assertEquals("native-frame", PinningTaxonomy.NATIVE_FRAME.label());
        assertEquals("foreign-call", PinningTaxonomy.FOREIGN_CALL.label());
        assertEquals("object-monitor-in-native", PinningTaxonomy.OBJECT_MONITOR_IN_NATIVE.label());
        assertEquals("unclassified", PinningTaxonomy.UNCLASSIFIED.label());
    }
}
