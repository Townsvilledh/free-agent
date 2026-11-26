package org.freeagent.app

/**
 * User handoff (v0.10.1) — the pure pieces of the
 * `browser_handoff` tool, in the BrainPacing/FluidGate style:
 * no Android, no I/O, pinned by JVM tests. TaskRunner owns the
 * wiring (tool dispatch, parking, resume).
 *
 * What a handoff is: a PLANNED human step, named in advance by
 * a packaged task — the counterpart to PARKED_AT_WALL. A wall
 * park is entered only from an OBSERVED challenge (evidence
 * grading, v0.9) and this does not change that: a handoff is
 * not a wall claim, it is the task's own design — e.g.
 * "Connect my X account" opens the login flow and then hands
 * the browser to the user, who types THEIR OWN credentials
 * into the session themselves. Lille never sees, types, or
 * stores the user's password; the session persists in her
 * WebView storage like any lane session.
 *
 * The gate: the tool is honoured only on tasks whose record
 * carries `userHandoff = true`, set at creation by the
 * packaged task itself — never by model request alone. On any
 * other task the tool answers an error and nothing parks.
 */
object UserHandoff {

    const val TOOL = "browser_handoff"

    /** The wall-kind recorded when a task parks for a planned
     *  user step — distinct from every challenge kind, so
     *  reports never dress a handoff up as a wall. */
    const val WALL_KIND = "user_step"

    /** The observation prefix the runner recognises. */
    const val OBSERVATION_PREFIX = "HANDOFF_REQUESTED"

    /** May this task hand the browser to the user? Only when
     *  its own packaged record says so. */
    fun mayHandoff(taskUserHandoffFlag: Boolean): Boolean = taskUserHandoffFlag

    fun isHandoffObservation(observation: String): Boolean =
        observation.startsWith(OBSERVATION_PREFIX)

    /** The step text the human will read: the model's wording,
     *  tidied and capped; a plain default when it said nothing
     *  usable. The default names the shape every handoff takes
     *  — the user acts in Lille's browser, then hands back. */
    fun cleanStep(raw: String, domain: String): String {
        val cleaned = raw.trim().replace(Regex("\\s+"), " ").take(300)
        return cleaned.ifEmpty {
            "Take the step on $domain in Lille's browser: tap " +
                "'Open Lille's browser', do it there, then tap " +
                "'Done — hand back to Lille'. She resumes by herself."
        }
    }

    /** The note injected into the conversation when the user
     *  hands the session back after a handoff park (the wall
     *  resume note's counterpart): what happened, and what to
     *  do next — verify from the page, never from assumption. */
    fun resumeNote(domain: String): String =
        "The user has taken the step you handed over, in your " +
            "browser session on $domain — they acted themselves; " +
            "you never saw any password they typed. Continue the " +
            "task now: browser_read the page and verify the " +
            "result from what the page shows (for an X sign-in, " +
            "call x_session_status), then carry on or finish " +
            "with the verified result."
}
