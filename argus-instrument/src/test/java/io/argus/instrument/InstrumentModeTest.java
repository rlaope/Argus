package io.argus.instrument;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstrumentModeTest {

    @ParameterizedTest
    @CsvSource({
            "watch,WATCH",
            "trace,TRACE",
            "monitor,MONITOR",
            "WATCH,WATCH",
            "Trace,TRACE",
            "MONITOR,MONITOR",
            "  watch  ,WATCH",
    })
    void fromString_parsesCaseInsensitivelyAndTrims(String token, InstrumentMode expected) {
        assertEquals(expected, InstrumentMode.fromString(token));
    }

    @Test
    void fromString_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> InstrumentMode.fromString(null));
    }

    @Test
    void fromString_unknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> InstrumentMode.fromString("profile"));
    }

    @Test
    void fromString_emptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> InstrumentMode.fromString(""));
    }

    @ParameterizedTest
    @CsvSource({
            "WATCH,watch",
            "TRACE,trace",
            "MONITOR,monitor",
    })
    void token_isLowerCaseName(InstrumentMode mode, String expected) {
        assertEquals(expected, mode.token());
    }

    @Test
    void token_roundTripsThroughFromString() {
        for (InstrumentMode mode : InstrumentMode.values()) {
            assertEquals(mode, InstrumentMode.fromString(mode.token()));
        }
    }
}
