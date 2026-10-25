package io.github.madooroy.pinionkaroo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideGearStatsTest {
    @Test
    fun nothingRecordedYet() {
        val summary = RideGearStats().summary
        assertEquals(0, summary.shifts)
        assertNull(summary.averageGear)
    }

    @Test
    fun averageIsOverRecordedSecondsWithAGear() {
        val stats = RideGearStats()
        repeat(30) { stats.onSecond(4) }
        repeat(10) { stats.onSecond(8) }
        repeat(20) { stats.onSecond(null) } // gearbox not connected: left out
        assertEquals(5.0, stats.summary.averageGear!!, 1e-9)
    }

    @Test
    fun shiftsCountChanges() {
        val stats = RideGearStats()
        listOf(5, 4, 3, 3, null, 3, 9).forEach { stats.onGear(it) }
        assertEquals(3, stats.summary.shifts) // 5-4, 4-3, 3-9
    }
}
