package io.github.madooroy.pinionkaroo

/**
 * Decides when to warn about a low Smart.Shift battery: once per connection at or below [low] percent, once more
 * at or below [veryLow], and once more at or below [critical]. The levels are the ones at which the Smart.Shift box
 * itself reacts after a shift (user manual p. 33): LED yellow at 20 %, red at 10 %, and a beep at 5 %.
 * Pure Kotlin so it can be unit tested; the service shows the alert.
 */
class LowBatteryWarning(
    private val low: Double = LOW_PERCENT,
    private val veryLow: Double = VERY_LOW_PERCENT,
    private val critical: Double = CRITICAL_PERCENT,
) {
    /** In rising order of urgency. */
    enum class Level { LOW, VERY_LOW, CRITICAL }

    private var warned: Level? = null

    /** Feed every state; returns the level to warn about now, or null. */
    fun update(state: PinionState): Level? {
        if (state.link != PinionLink.CONNECTED) {
            warned = null
            return null
        }
        val percent = state.batteryPercent ?: return null
        val level = when {
            percent <= critical -> Level.CRITICAL
            percent <= veryLow -> Level.VERY_LOW
            percent <= low -> Level.LOW
            else -> return null
        }
        val previous = warned
        if (previous != null && level <= previous) return null
        warned = level
        return level
    }

    companion object {
        const val LOW_PERCENT = 20.0
        const val VERY_LOW_PERCENT = 10.0
        const val CRITICAL_PERCENT = 5.0
    }
}
