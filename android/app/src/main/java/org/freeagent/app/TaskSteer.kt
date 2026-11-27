package org.freeagent.app

import org.json.JSONObject

/**
 * STEERING, NOT INTERVIEWS (v1.0.7, born of the
 * 2026-11-27 screenshots): in the chat where her X
 * creation lane was RUNNING — the browser chip on
 * screen — Ethuram steered it in chat ("for your
 * username try lilleetbtc"; "email and password should
 * be handled by you end to end"; "this is your personal
 * x account you should sort your own email and
 * password"). The v1.0.4 steer machinery recorded the
 * words, but the chat BRAIN still answered them as if
 * nothing had started: an intake list for her own
 * handle/email/password/phone, then a flat denial that
 * she has an email or password of her own.
 *
 * For a PACKAGED lane (the X lanes — scripted flows
 * with their own steps, their own kit) a message about
 * the work's CONDUCT is not conversation and not a new
 * job: it is a STEER. The glue records it on the task
 * record through the same pendingSteers queue the
 * v1.0.4 machinery consumes at step boundaries, and
 * the reply is composed from the record — what the
 * lane is doing (already running, when it is) and what
 * it uses (her own mailbox setup, her self-generated
 * vault password) — never an intake interview.
 *
 * Detection is structural, in the house style: the
 * chat's newest live record is a packaged lane, and
 * the message carries a conduct reference — an
 * identity item, or a conduct phrase ("end to end",
 * "your own", "you should", "for yourself"…).
 * Messages with no conduct reference flow on exactly
 * as before (new jobs, plain chat, the brain).
 *
 * Pure + org.json; pinned by Lille107Test.
 */
object TaskSteer {

    /** The packaged lanes' titles — the contractual
     *  strings of XAccountTask.TITLE / LEGACY_TITLE /
     *  CONNECT_TITLE and XConnectTask.TITLE. A packaged
     *  lane is recognised by the title it was created
     *  with; general chat-born tasks carry none of
     *  these. */
    private val PACKAGED_TITLES = setOf(
        "Create Lille's X account",
        "Lille: create my X account",
        "Connect Lille's X account",
        "Connect my X account"
    )

    fun isPackaged(task: JSONObject): Boolean =
        task.optString("title") in PACKAGED_TITLES

    /** Words that make a message about the lane's own
     *  identity conduct. */
    private val IDENTITY_WORDS = setOf(
        "email", "mailbox", "password", "passcode",
        "handle", "username", "phone"
    )

    /** Phrases that mark a message as steering the
     *  work's conduct — corrections and directions
     *  about how SHE should be doing it. */
    private val CONDUCT_PHRASES = listOf(
        "end to end", "your own", "by yourself",
        "for yourself", "you should", "handled by you",
        "handle it yourself", "sort your", "sort it out",
        "do it yourself", "your personal"
    )

    private val WORD_RE = Regex("[a-z']+")

    private fun words(text: String): Set<String> =
        WORD_RE.findAll(text.lowercase())
            .map { it.value }
            .toSet()

    /** True when the message references her identity
     *  items — the steer answer then states what the
     *  lane uses for them. */
    fun referencesIdentity(text: String): Boolean =
        words(text).any { it in IDENTITY_WORDS }

    /** True when the message speaks to the packaged
     *  lane's conduct: an identity reference or a
     *  conduct phrase. (Progress and needs questions
     *  are answered by the probes earlier in the submit
     *  path; this net is for the rest — corrections,
     *  suggestions, directions.) */
    fun isConductMessage(text: String): Boolean {
        val low = text.lowercase()
        if (CONDUCT_PHRASES.any { low.contains(it) }) return true
        return referencesIdentity(text)
    }

    /**
     * The deterministic reply to a steer, composed ONLY
     * from the record: the words are on the run's
     * record (the glue stamps them), the lane's state
     * in work terms — ALREADY RUNNING when it is, the
     * fact the screenshot's replies denied by omission —
     * and, when the steer touched her identity items,
     * what the lane uses for them: her mailbox from her
     * own setup, her password self-generated into her
     * vault. Never a question, never an intake. The
     * text is itself clean under the chat guard
     * (pinned).
     */
    fun steerAnswerFor(
        task: JSONObject, identityReferenced: Boolean
    ): String {
        val title = TaskTransparency.titleFor(task)
        val state = task.optString("state")
        val steps = task.optInt("steps", 0)
        val stepBit = when {
            steps <= 0 -> ""
            steps == 1 -> " · 1 step done"
            else -> " · $steps steps done"
        }
        val last = TaskTransparency.stepsFor(task).lastOrNull()
        val lastBit = if (last != null) {
            " (last step: " + last.name + ")"
        } else {
            ""
        }
        val stateBit = when (state) {
            TaskStates.RUNNING ->
                "“$title” is already running$stepBit" +
                    lastBit + " — the lane is working " +
                    "now, not waiting to start."
            TaskStates.QUEUED ->
                "“$title” is queued and starts on its own."
            TaskStates.WAITING_ROUTE,
            TaskStates.WAITING_NETWORK ->
                "“$title” is resting$stepBit and " +
                    "continues on its own — she'll take " +
                    "your words up as she goes."
            TaskStates.PAUSED ->
                "“$title” is paused$stepBit — she'll " +
                    "take your words up when she resumes " +
                    "(say “resume” to restart her now)."
            TaskStates.PARKED_AT_WALL ->
                "“$title” is parked at the step in the " +
                    "browser panel — pass it there and " +
                    "she continues, with your words on " +
                    "the record."
            else ->
                "“$title” — " +
                    StatusProbe.statusLineFor(task)
        }
        val identityBit = if (identityReferenced) {
            " Her email and password are hers to sort " +
                "end to end: her mailbox comes from her " +
                "own setup, and her password is one she " +
                "generates into her vault — she doesn't " +
                "need them from you."
        } else {
            ""
        }
        return "Noted — your words are on the run's " +
            "record. " + stateBit + identityBit
    }
}
