package io.github.madooroy.pinionkaroo

/**
 * Decides when to warn about a low Smart.Shift battery: once per connection at or below [low] percent, and
 * once more if it then reaches [veryLow]. Pure Kotlin so it can be unit tested; the service shows the alert.
 */
class LowBatteryWarning(
    private val low: Double = LOW_PERCENT,
    private val veryLow: Double = VERY_LOW_PERCENT,
) {
    enum class Level { LOW, VERY_LOW }

    private var warned = 0 // 0 nothing yet, 1 low, 2 very low

    /** Feed every state; returns the level to warn about now, or null. */
    fun update(state: PinionState): Level? {
        if (state.link != PinionLink.CONNECTED) {
            warned = 0
            return null
        }
        val percent = state.batteryPercent ?: return null
        val level = when {
            percent <= veryLow -> 2
            percent <= low -> 1
            else -> 0
        }
        if (level <= warned) return null
        warned = level
        return if (level == 2) Level.VERY_LOW else Level.LOW
    }

    companion object {
        const val LOW_PERCENT = 20.0
        const val VERY_LOW_PERCENT = 10.0
    }
}
