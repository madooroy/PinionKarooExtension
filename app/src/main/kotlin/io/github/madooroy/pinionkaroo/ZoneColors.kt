package io.github.madooroy.pinionkaroo

/**
 * Zone colours for the power and heart rate halves of the split fields, as Karoo paints its own fields in
 * "zone colour" mode. The table is karoo-powerbar's (github.com/timklge/karoo-powerbar, res/values/colors.xml,
 * Apache 2.0), which holds exactly the two colours a Karoo 2 was seen to paint its own fields with in the profile
 * editor (2026-10-23): #60EEB2 for a power field in zone 2 and #00B988 for a heart rate field. Barberfish's table,
 * used before, has neither. Zones 3-7 are not yet checked against a native field. Pure Kotlin (colours as ARGB
 * ints) so it can be unit tested.
 */
object ZoneColors {
    /** Power zones 1-7, low to high */
    val POWER = intArrayOf(
        0xFF00B988.toInt(), // 1 active recovery, green
        0xFF60EEB2.toInt(), // 2 endurance, mint
        0xFFFFF500.toInt(), // 3 tempo, yellow
        0xFFFB8C65.toInt(), // 4 threshold, salmon
        0xFFFE581F.toInt(), // 5 VO2 max, orange
        0xFFD60404.toInt(), // 6 anaerobic, red
        0xFFB700A2.toInt(), // 7 neuromuscular, purple
    )

    /** Heart rate zones 1-5: power zones 1, 2, 3, 4 and 6 (karoo-powerbar's five-zone mapping) */
    val HEART_RATE = intArrayOf(POWER[0], POWER[1], POWER[2], POWER[3], POWER[5])

    /**
     * 1-based zone of [value] given the rider's zones as (min, max) pairs from the Karoo user profile, or null
     * if the profile has none. Above the top zone's max counts as the top zone.
     */
    fun zoneOf(value: Double, zones: List<Pair<Int, Int>>): Int? {
        if (zones.isEmpty()) return null
        val index = zones.indexOfFirst { value <= it.second }
        return if (index < 0) zones.size else index + 1
    }

    /** The fill for a 1-based [zone] from [palette], or null if out of range */
    fun fill(zone: Int?, palette: IntArray): Int? = zone?.let { palette.getOrNull(it - 1) }

    /** Black or white, whichever reads better on [fill] (relative luminance, as the Karoo does black on mint) */
    fun textOn(fill: Int): Int {
        fun channel(shift: Int): Double {
            val c = ((fill shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val luminance = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        // Contrast against white (1.05 / (L + 0.05)) vs against black ((L + 0.05) / 0.05): equal at L ~ 0.18
        return if (luminance > 0.18) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
}
