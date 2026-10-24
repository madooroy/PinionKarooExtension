package io.github.madooroy.pinionkaroo

/**
 * Zone colours for the power and heart rate halves of the split fields, as Karoo paints its own fields in
 * "zone colour" mode. The table is Barberfish's measurement of the Karoo palette
 * (github.com/jpweytjens/Barberfish, ZoneColoring.kt, Apache 2.0); it may have been taken on a Karoo 3, so it is
 * to be checked against native fields on a Karoo 2. Pure Kotlin (colours as ARGB ints) so it can be unit tested.
 */
object ZoneColors {
    /** Power zones 1-7, low to high */
    val POWER = intArrayOf(
        0xFF1A8C3A.toInt(), // 1 active recovery, dark green
        0xFF40D078.toInt(), // 2 endurance, mint green
        0xFFF0D800.toInt(), // 3 tempo, yellow
        0xFFF08868.toInt(), // 4 threshold, salmon
        0xFFF06020.toInt(), // 5 VO2 max, orange
        0xFFD01020.toInt(), // 6 anaerobic, red
        0xFF9020A0.toInt(), // 7 neuromuscular, purple
    )

    /** Heart rate zones 1-5: Karoo uses power zones 1, 2, 3, 4 and 6 */
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
