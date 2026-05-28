package io.argus.instrument;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOptionsTest {

    private static AgentOptions sample() {
        return AgentOptions.of(
                InstrumentMode.WATCH,
                MethodSpec.parse("com.acme.Foo#bar"),
                51234,
                "ab12cd34",
                true,
                CaptureCaps.defaults());
    }

    // --- of() validation ---

    @Test
    void of_buildsWithGetters() {
        AgentOptions o = sample();
        assertEquals(InstrumentMode.WATCH, o.mode());
        assertEquals("com.acme.Foo#bar", o.spec().toString());
        assertEquals(51234, o.port());
        assertEquals("ab12cd34", o.nonce());
        assertTrue(o.enabled());
        assertEquals(100, o.caps().maxHits());
    }

    @Test
    void of_nullModeThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.of(
                null, MethodSpec.parse("com.acme.Foo#bar"), 1, "n", true, CaptureCaps.defaults()));
    }

    @Test
    void of_nullSpecThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.of(
                InstrumentMode.WATCH, null, 1, "n", true, CaptureCaps.defaults()));
    }

    @Test
    void of_nullNonceThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.of(
                InstrumentMode.WATCH, MethodSpec.parse("com.acme.Foo#bar"), 1, null, true, CaptureCaps.defaults()));
    }

    @Test
    void of_nullCapsThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.of(
                InstrumentMode.WATCH, MethodSpec.parse("com.acme.Foo#bar"), 1, "n", true, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, 70000, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void of_portOutOfRangeThrows(int port) {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.of(
                InstrumentMode.WATCH, MethodSpec.parse("com.acme.Foo#bar"), port, "n", true, CaptureCaps.defaults()));
    }

    @Test
    void of_portBoundariesAccepted() {
        AgentOptions lo = AgentOptions.of(InstrumentMode.WATCH, MethodSpec.parse("com.acme.Foo#bar"),
                1, "n", true, CaptureCaps.defaults());
        AgentOptions hi = AgentOptions.of(InstrumentMode.WATCH, MethodSpec.parse("com.acme.Foo#bar"),
                65535, "n", true, CaptureCaps.defaults());
        assertEquals(1, lo.port());
        assertEquals(65535, hi.port());
    }

    // --- encode / parse round-trip ---

    @Test
    void roundTrip_reproducesAllFields() {
        CaptureCaps caps = new CaptureCaps.Builder()
                .maxHits(42)
                .timeoutMs(12345)
                .maxValueLen(128)
                .maxArgs(8)
                .maxDepth(10)
                .maxEventsPerSecond(250)
                .build();
        AgentOptions original = AgentOptions.of(
                InstrumentMode.TRACE,
                MethodSpec.parse("com.acme.Foo$Inner#<init>"),
                65535,
                "deadbeefcafef00d",
                true,
                caps);

        AgentOptions decoded = AgentOptions.parse(original.encode());

        assertEquals(original.mode(), decoded.mode());
        assertEquals(original.spec().toString(), decoded.spec().toString());
        assertEquals(original.port(), decoded.port());
        assertEquals(original.nonce(), decoded.nonce());
        assertEquals(original.enabled(), decoded.enabled());
        assertEquals(original.caps().maxHits(), decoded.caps().maxHits());
        assertEquals(original.caps().timeoutMs(), decoded.caps().timeoutMs());
        assertEquals(original.caps().maxValueLen(), decoded.caps().maxValueLen());
        assertEquals(original.caps().maxArgs(), decoded.caps().maxArgs());
        assertEquals(original.caps().maxDepth(), decoded.caps().maxDepth());
        assertEquals(original.caps().maxEventsPerSecond(), decoded.caps().maxEventsPerSecond());
    }

    @Test
    void roundTrip_disabledFlagSurvives() {
        AgentOptions original = AgentOptions.of(InstrumentMode.MONITOR,
                MethodSpec.parse("com.acme.Foo#*"), 5000, "nonce", false, CaptureCaps.defaults());
        AgentOptions decoded = AgentOptions.parse(original.encode());
        assertEquals(false, decoded.enabled());
        assertEquals(InstrumentMode.MONITOR, decoded.mode());
        assertTrue(decoded.spec().matchesAllMethods());
    }

    @Test
    void encode_isKeyValueSemicolonShape() {
        String wire = sample().encode();
        assertTrue(wire.startsWith("mode=watch;"), wire);
        assertTrue(wire.contains(";spec=com.acme.Foo#bar;"), wire);
        assertTrue(wire.contains(";port=51234;"), wire);
        assertTrue(wire.contains(";nonce=ab12cd34;"), wire);
        assertTrue(wire.contains(";enabled=true;"), wire);
    }

    // --- encode rejects reserved chars in a value ---

    @Test
    void encode_nonceWithSemicolonThrows() {
        AgentOptions o = AgentOptions.of(InstrumentMode.WATCH, MethodSpec.parse("com.acme.Foo#bar"),
                51234, "bad;nonce", true, CaptureCaps.defaults());
        assertThrows(IllegalArgumentException.class, o::encode);
    }

    @Test
    void encode_nonceWithEqualsThrows() {
        AgentOptions o = AgentOptions.of(InstrumentMode.WATCH, MethodSpec.parse("com.acme.Foo#bar"),
                51234, "bad=nonce", true, CaptureCaps.defaults());
        assertThrows(IllegalArgumentException.class, o::encode);
    }

    // --- parse() malformed-input throws ---

    @Test
    void parse_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void parse_emptyThrows(String raw) {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(raw));
    }

    @Test
    void parse_missingModeThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                "spec=com.acme.Foo#bar;port=5000;nonce=n;enabled=true"));
    }

    @Test
    void parse_missingSpecThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                "mode=watch;port=5000;nonce=n;enabled=true"));
    }

    @Test
    void parse_missingPortThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                "mode=watch;spec=com.acme.Foo#bar;nonce=n;enabled=true"));
    }

    @Test
    void parse_missingNonceThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                "mode=watch;spec=com.acme.Foo#bar;port=5000;enabled=true"));
    }

    @Test
    void parse_nonIntegerPortThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                "mode=watch;spec=com.acme.Foo#bar;port=notanumber;nonce=n;enabled=true"));
    }

    @Test
    void parse_portOutOfRangeThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                "mode=watch;spec=com.acme.Foo#bar;port=99999;nonce=n;enabled=true"));
    }

    @Test
    void parse_unknownModeThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                "mode=profile;spec=com.acme.Foo#bar;port=5000;nonce=n;enabled=true"));
    }

    @Test
    void parse_malformedSpecThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                "mode=watch;spec=no-hash-here;port=5000;nonce=n;enabled=true"));
    }

    @Test
    void parse_malformedPairThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                "mode=watch;=bogus;spec=com.acme.Foo#bar;port=5000;nonce=n;enabled=true"));
    }

    @Test
    void parse_nonIntegerCapFieldThrows() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                "mode=watch;spec=com.acme.Foo#bar;port=5000;nonce=n;enabled=true;maxHits=lots"));
    }

    @Test
    void parse_appliesCapsClampOnDecode() {
        // maxHits beyond the cap is clamped through CaptureCaps.Builder during parse.
        AgentOptions o = AgentOptions.parse(
                "mode=watch;spec=com.acme.Foo#bar;port=5000;nonce=n;enabled=true;maxHits=99999999");
        assertEquals(1_000_000, o.caps().maxHits());
    }

    @Test
    void parse_missingEnabledDefaultsToFalse() {
        AgentOptions o = AgentOptions.parse(
                "mode=watch;spec=com.acme.Foo#bar;port=5000;nonce=n");
        assertEquals(false, o.enabled());
    }
}
