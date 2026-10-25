package io.github.madooroy.pinionkaroo

/**
 * Counts gear changes for the ride file. Feed it the gear seen while the ride is recording (null while the gearbox
 * is not connected); a change between two known gears counts as one shift, so a run of upshifts that the gearbox
 * reports as one jump (see CLAUDE.md) counts once, and losing and regaining the link does not count. Pure Kotlin.
 */
class ShiftCounter {
    private var last: Int? = null

    var shifts = 0
        private set

    /** Returns true if this counted a shift */
    fun update(gear: Int?): Boolean {
        if (gear == null) return false
        val previous = last
        last = gear
        if (previous == null || previous == gear) return false
        shifts++
        return true
    }
}
