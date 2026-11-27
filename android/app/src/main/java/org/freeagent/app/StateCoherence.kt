package org.freeagent.app

import org.json.JSONObject

/**
 * STATE COHERENCE (v1.0.8, born of the 2026-11-27
 * screenshot): one record, one story. The falsely
 * finished X report printed "Done — that work is
 * finished." and then, three lines later, "Waiting —
 * continues automatically (task is waiting, not
 * failed)." — the WAITING note the record had picked
 * up in an earlier state, echoed by the COMPLETED
 * report composer as if it were current fact. A note
 * belongs to the state that wrote it: a system note
 * describing a waiting state is not a pause reason, not
 * a failure reason, and never a trailer on a terminal
 * report.
 *
 * The notes governed here are the system's OWN state
 * notes (named constants — structure, not phrase
 * hunting): BrainPacing's waiting note, StepBudget's
 * rest note, the runner's network-waiting note. A
 * model's or a lane's substantive note is untouched:
 * it passes whenever the narration guard passes it.
 *
 * Pure; pinned by Lille108Test's coherence sweep.
 */
object StateCoherence {

    /** The network-waiting note the runner writes (a
     *  literal in TaskRunner's wait path). */
    private const val NETWORK_WAITING_NOTE =
        "Network unreachable — task is waiting, not failed."

    /** System notes that describe a NON-terminal,
     *  still-continuing state. Echoed only while the
     *  record actually stands in a waiting state. */
    private val WAITING_NOTES = setOf(
        BrainPacing.WAITING_NOTE,
        StepBudget.REST_NOTE,
        NETWORK_WAITING_NOTE
    )

    /** True when [note] is one of the system's own
     *  waiting-state notes. */
    fun isWaitingNote(note: String): Boolean =
        note.trim() in WAITING_NOTES

    /**
     * The record's note as [forState] may echo it: the
     * narration-vetted note, withheld when it is a
     * waiting-state note and the state it would be
     * echoed under is not a waiting state. Every surface
     * that echoes a note beside a state (report
     * composer, transparency detail, probe answers)
     * reads through here, so a record can never tell
     * two states' stories at once.
     */
    fun coherentNote(task: JSONObject, forState: String): String? {
        val note = NarrationGuard.cleanOrNull(task.optString("note"))
            ?: return null
        if (!TaskStates.isWaiting(forState) && isWaitingNote(note)) {
            return null
        }
        return note
    }
}
