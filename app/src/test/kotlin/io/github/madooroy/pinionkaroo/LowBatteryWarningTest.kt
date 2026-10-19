package io.github.madooroy.pinionkaroo

import io.github.madooroy.pinionkaroo.LowBatteryWarning.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LowBatteryWarningTest {
    private fun connected(percent: Double?) = PinionState(link = PinionLink.CONNECTED, batteryPercent = percent)
    private fun searching(percent: Double?) = PinionState(link = PinionLink.SEARCHING, batteryPercent = percent)

    @Test
    fun noWarningAboveLow() {
        val w = LowBatteryWarning()
        assertNull(w.update(connected(null)))
        assertNull(w.update(connected(88.55)))
        assertNull(w.update(connected(20.01)))
    }

    @Test
    fun warnsOnceAtLowThenOnceAtVeryLow() {
        val w = LowBatteryWarning()
        assertEquals(Level.LOW, w.update(connected(20.0)))
        assertNull(w.update(connected(19.5)))
        assertNull(w.update(connected(20.3))) // wobbling back over the line does not warn again
        assertNull(w.update(connected(19.9)))
        assertEquals(Level.VERY_LOW, w.update(connected(10.0)))
        assertNull(w.update(connected(9.0)))
        assertNull(w.update(connected(5.01)))
        assertEquals(Level.CRITICAL, w.update(connected(5.0)))
        assertNull(w.update(connected(4.0)))
    }

    @Test
    fun veryLowAtConnectionSkipsLow() {
        val w = LowBatteryWarning()
        assertEquals(Level.VERY_LOW, w.update(connected(8.0)))
        assertNull(w.update(connected(7.0)))
    }

    @Test
    fun criticalAtConnectionSkipsTheOthers() {
        val w = LowBatteryWarning()
        assertEquals(Level.CRITICAL, w.update(connected(3.0)))
        assertNull(w.update(connected(8.0)))
        assertNull(w.update(connected(15.0)))
    }

    @Test
    fun newConnectionWarnsAgain() {
        val w = LowBatteryWarning()
        assertEquals(Level.LOW, w.update(connected(15.0)))
        assertNull(w.update(searching(15.0)))
        assertEquals(Level.LOW, w.update(connected(15.0)))
    }
}
