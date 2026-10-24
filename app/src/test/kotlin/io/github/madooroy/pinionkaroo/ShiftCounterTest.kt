package io.github.madooroy.pinionkaroo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftCounterTest {
    @Test
    fun countsChangesBetweenKnownGears() {
        val counter = ShiftCounter()
        assertFalse(counter.update(5)) // first gear seen
        assertFalse(counter.update(5)) // the same gear again, once a second
        assertTrue(counter.update(4))
        assertTrue(counter.update(9)) // a jump counts once
        assertEquals(2, counter.shifts)
    }

    @Test
    fun aDroppedLinkIsNotAShift() {
        val counter = ShiftCounter()
        counter.update(5)
        assertFalse(counter.update(null))
        assertFalse(counter.update(5))
        assertTrue(counter.update(6)) // shifted while disconnected: counted once on reconnecting
        assertEquals(1, counter.shifts)
    }
}
