package io.github.augztn.pocketlink.ssdbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemTelemetryTest {
    @Test
    fun calculatesAverageDeviceCpuUtilizationFromCounterDeltas() {
        val usage = calculateCpuUsagePercent(
            previousTotalTicks = 1_000,
            previousIdleTicks = 700,
            currentTotalTicks = 1_100,
            currentIdleTicks = 760,
        )

        assertEquals(40.0, usage!!, 0.001)
    }

    @Test
    fun rejectsInvalidOrUnavailableCpuCounterDeltas() {
        assertNull(calculateCpuUsagePercent(100, 70, 100, 70))
        assertNull(calculateCpuUsagePercent(100, 70, 90, 65))
        assertNull(calculateCpuUsagePercent(100, 70, 110, 125))
    }
}
