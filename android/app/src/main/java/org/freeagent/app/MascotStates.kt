package org.freeagent.app

/**
 * The mascot IS the status system (design fold-in, Direction 1
 * of the 2026-11-26 UI/UX research): six pre-rendered states,
 * swapped as static drawables — no animation loops, no Lottie,
 * nothing that costs frames on a low-end phone. The antenna
 * dot carries the colour: orange = alive (thinking/working),
 * amber = waiting, green = done, red = stuck/needs-you,
 * grey = idle.
 *
 * Pure mapping, JVM-pinned: app/task state in, mascot out.
 * The UI layer resolves [drawableName] to the layer-list
 * drawable of the same name.
 */
object MascotStates {

    enum class Mascot { IDLE, THINKING, WORKING, WAITING, DONE, STUCK }

    fun forTaskState(state: String): Mascot = when (state) {
        TaskStates.PARKED_AT_WALL, TaskStates.NEEDS_RUNNER,
        TaskStates.FAILED -> Mascot.STUCK
        TaskStates.WAITING_ROUTE, TaskStates.WAITING_NETWORK,
        TaskStates.PAUSED -> Mascot.WAITING
        TaskStates.RUNNING -> Mascot.WORKING
        TaskStates.COMPLETED -> Mascot.DONE
        else -> Mascot.IDLE // queued, cancelled, unknown
    }

    /** Chat header: idle at rest, WAITING while a BrainPacing
     *  gate/cooldown holds the reply, WORKING while a reply is
     *  actually being produced. */
    fun forChat(busy: Boolean, waiting: Boolean): Mascot = when {
        !busy -> Mascot.IDLE
        waiting -> Mascot.WAITING
        else -> Mascot.WORKING
    }

    fun drawableName(mascot: Mascot): String =
        "mascot_" + mascot.name.lowercase()
}
