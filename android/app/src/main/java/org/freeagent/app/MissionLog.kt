package org.freeagent.app

/**
 * Mission-log formatting for the proactive debrief
 * (design fold-in, Direction 2): a running task reads as a checklist
 * of what is DONE (from its real checkpointed trace), what it
 * is doing NOW (its note, in plain words), and how long it has
 * been at it — never a fake percentage. Pure functions over
 * primitives so JVM tests pin them without org.json.
 */
object MissionLog {

    /** A bare duration ("under a minute", "3 min", "1 h 5
     *  min") — the caller phrases it ("Elapsed:" for a live
     *  task, "Took:" for a finished one). Empty when the start
     *  time is unknown (tasks created before createdAt was
     *  recorded). */
    fun elapsedLabel(createdAt: Long, end: Long): String {
        if (createdAt <= 0L) return ""
        val seconds = ((end - createdAt) / 1000).coerceAtLeast(0)
        return when {
            seconds < 45 -> "under a minute"
            seconds < 3600 -> "${maxOf(1, seconds / 60)} min"
            else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        }
    }

    /** One line per completed tool step, from the task's own
     *  recorded tool results; then the current step while the
     *  task is live. Pending steps are NOT invented — agent
     *  work is indeterminate, so the list ends at "now". */
    fun checklist(doneTools: List<String>, running: Boolean, steps: Int): List<String> {
        val lines = doneTools.map { "✓ $it" }.toMutableList()
        if (running) {
            lines.add("● step ${steps + 1} — working…")
        }
        return lines
    }

    /** The current-action line: the task's own note when it
     *  has one AND the note is clean (NarrationGuard — a
     *  legacy note carrying route narration is withheld),
     *  else a state-true plain fallback. */
    fun currentAction(state: String, note: String): String {
        NarrationGuard.cleanOrNull(note)?.let { return it }
        return when (state) {
            TaskStates.RUNNING -> "Working on it…"
            TaskStates.WAITING_ROUTE -> "Waiting — continues automatically…"
            TaskStates.WAITING_NETWORK -> "Waiting for the network…"
            TaskStates.PARKED_AT_WALL -> "Parked at a step only you can do."
            TaskStates.QUEUED -> "Queued — starts when the worker picks it up."
            TaskStates.PAUSED -> "Paused by you."
            else -> ""
        }
    }
}

/**
 * "While you were away" ordering (Direction 4): the proactive
 * debrief surfaces as Needs you → Failed → Finished, as
 * distinct sections — never one undifferentiated list.
 */
object DebriefOrder {

    const val NEEDS_YOU = 0
    const val FAILED = 1
    const val FINISHED = 2

    fun rank(status: String): Int {
        val s = status.uppercase()
        return when {
            s.contains("NEEDS") || s.contains("BLOCKED") ||
                s.contains("PARKED") -> NEEDS_YOU
            s.contains("FAIL") || s.contains("ERROR") -> FAILED
            else -> FINISHED
        }
    }

    fun sectionTitle(rank: Int): String = when (rank) {
        NEEDS_YOU -> "Needs you"
        FAILED -> "Failed"
        else -> "Finished"
    }
}
