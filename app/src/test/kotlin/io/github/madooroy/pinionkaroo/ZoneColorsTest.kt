package io.github.madooroy.pinionkaroo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoneColorsTest {
    private val hrZones = listOf(0 to 120, 121 to 140, 141 to 160, 161 to 175, 176 to 220)

    @Test
    fun zoneOfFindsTheFirstZoneWhoseMaxIsNotExceeded() {
        assertEquals(1, ZoneColors.zoneOf(96.0, hrZones))
        assertEquals(1, ZoneColors.zoneOf(120.0, hrZones))
        assertEquals(2, ZoneColors.zoneOf(120.4, hrZones))
        assertEquals(5, ZoneColors.zoneOf(190.0, hrZones))
        assertEquals(5, ZoneColors.zoneOf(240.0, hrZones)) // above the top zone
        assertNull(ZoneColors.zoneOf(150.0, emptyList()))
    }

    @Test
    fun fillPicksFromThePaletteAndToleratesOddZones() {
        assertEquals(ZoneColors.POWER[2], ZoneColors.fill(3, ZoneColors.POWER))
        assertEquals(ZoneColors.POWER[5], ZoneColors.fill(5, ZoneColors.HEART_RATE))
        assertNull(ZoneColors.fill(8, ZoneColors.POWER))
        assertNull(ZoneColors.fill(null, ZoneColors.POWER))
    }

    @Test
    fun paletteHoldsTheColoursTheKaroo2PaintsItsOwnFieldsWith() {
        assertEquals(0xFF00B988.toInt(), ZoneColors.HEART_RATE[0])
        assertEquals(0xFF60EEB2.toInt(), ZoneColors.POWER[1])
    }

    @Test
    fun textIsBlackOnLightFillsAndWhiteOnDarkOnes() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(black, ZoneColors.textOn(0xFF60EEB2.toInt())) // as on the Karoo 2's own mint power field
        assertEquals(black, ZoneColors.textOn(0xFF00B988.toInt())) // and its green HR field
        assertEquals(black, ZoneColors.textOn(ZoneColors.POWER[2])) // yellow
        assertEquals(white, ZoneColors.textOn(ZoneColors.POWER[6])) // purple
        assertEquals(white, ZoneColors.textOn(ZoneColors.POWER[5])) // red
    }
}
