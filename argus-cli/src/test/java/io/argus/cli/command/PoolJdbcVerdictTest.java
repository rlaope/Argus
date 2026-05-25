package io.argus.cli.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PoolJdbcVerdictTest {

    @Test
    void critWhenAnyThreadIsWaiting() {
        assertEquals(PoolJdbcHandler.Verdict.CRIT,
                PoolJdbcHandler.computeVerdict(5, 50, 1));
    }

    @Test
    void warnWhenUtilizationAtOrAboveThreshold() {
        assertEquals(PoolJdbcHandler.Verdict.WARN,
                PoolJdbcHandler.computeVerdict(85, 100, 0));
        assertEquals(PoolJdbcHandler.Verdict.WARN,
                PoolJdbcHandler.computeVerdict(90, 100, 0));
    }

    @Test
    void okWhenUnderThresholdAndNoWaiters() {
        assertEquals(PoolJdbcHandler.Verdict.OK,
                PoolJdbcHandler.computeVerdict(20, 50, 0));
        assertEquals(PoolJdbcHandler.Verdict.OK,
                PoolJdbcHandler.computeVerdict(84, 100, 0));
    }

    @Test
    void okWhenMaxIsUnknown() {
        assertEquals(PoolJdbcHandler.Verdict.OK,
                PoolJdbcHandler.computeVerdict(50, 0, 0));
    }
}
