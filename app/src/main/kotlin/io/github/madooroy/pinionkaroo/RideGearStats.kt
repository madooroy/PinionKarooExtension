package io.github.madooroy.pinionkaroo

/**
 * Shifts and average gear for one ride, for the "Shifts + Avg Gear" field and the ride file. Pure Kotlin.
 *
 * - Shifts: every gear change while the ride is running or paused (a shift at a stop light is a shift), counted by
 *   [ShiftCounter], so a run of upshifts reported as one jump counts once.
 * - Average gear: over the seconds the ride is recording with the gearbox connected, so auto-pause stops and
 *   dropouts do not pull it either way. Seconds coasting do count, as the gear is still engaged.
 */
class RideGearStats {
    data class Summary(val shifts: Int, val averageGear: Double?)

    private val counter = ShiftCounter()
    private var gearSum = 0L
    private var seconds = 0L

    /** Every gear the gearbox reports while the ride is running or paused (null while disconnected) */
    fun onGear(gear: Int?) {
        counter.update(gear)
    }

    /** Once a second while the ride is recording */
    fun onSecond(gear: Int?) {
        if (gear == null) return
        gearSum += gear
        seconds++
    }

    val summary: Summary
        get() = Summary(counter.shifts, if (seconds > 0) gearSum.toDouble() / seconds else null)
}
