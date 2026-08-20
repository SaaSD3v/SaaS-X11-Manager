package com.saas.x11manager.util

/**
 * One Manager-owned X11 monitor slot.
 *
 * Slot numbers are zero based at the X11 protocol level (`:0`, `:1`, ...),
 * while the UI presents them as human-friendly monitor numbers (1, 2, ...).
 * A slot is runtime state, not a permanent property of a container.
 */
data class X11DisplaySlot(val number: Int) {
    init {
        require(number >= 0) { "X11 display number must be non-negative" }
    }

    val monitorNumber: Int
        get() = number + 1

    val displayName: String
        get() = ":$number"

    val processName: String
        get() = "saas-x11-$number"

    val runtimeDir: String
        get() = "${Constants.INTEGRATED_X11_RUNTIME_DIR}/display-$number"

    val socketDir: String
        get() = "$runtimeDir/.X11-unix"

    val socketFile: String
        get() = "$socketDir/X$number"

    val lockFile: String
        get() = "$runtimeDir/.X${number}-lock"

    val logFile: String
        get() = "$runtimeDir/server.log"

    fun describe(): String = "Monitor $monitorNumber ($displayName)"
}

/**
 * Stateless slot-selection policy.
 *
 * Active sessions keep their current slot. Once a slot is released, the next
 * graphical session receives the lowest available display number instead of
 * being sent back to a historical/per-container monitor assignment.
 */
object X11DisplayAllocator {
    fun firstFree(occupiedDisplayNumbers: Collection<Int>): X11DisplaySlot {
        val occupied = occupiedDisplayNumbers
            .asSequence()
            .filter { it >= 0 }
            .toHashSet()

        var candidate = 0
        while (candidate in occupied) candidate++
        return X11DisplaySlot(candidate)
    }
}
