package org.freeagent.app

/**
 * The seamless-fallback law's safety net (2026-11-26,
 * Ethuram: route failures, refusals and rate-limits must
 * NEVER be narrated on a user surface — fallback is
 * immediate and invisible).
 *
 * The generators were fixed at the source (BrainFailure no
 * longer composes cause lines; BrainPacing's notes are
 * plain), but task records written by OLDER builds still
 * carry narration in their note fields — his live task's
 * note literally read "Free route rate-limited — …". Any
 * surface that would echo a stored note (mission log, chat
 * task reports, status answers, proactive debriefs) vets it
 * through here first: a note carrying route narration is
 * WITHHELD, and the surface falls back to its plain state
 * line. Facts are not lost — the full record stays in the
 * store and in traces; only the user-facing echo is gated.
 *
 * Pure and pinned by NarrationGuardTest: the verbatim
 * legacy strings are fixtures, so the exact shapes that
 * shipped can never reach a surface again.
 */
object NarrationGuard {

    /** Substrings (lowercase) that mark route/failure
     *  narration. Deliberately specific phrases, not single
     *  common words — a model's honest summary ("the route
     *  to the venue was closed") must not be eaten; the
     *  guard vets Lille's own machinery notes, whose failure
     *  vocabulary is exactly this list. */
    private val BANNED = listOf(
        "free brain",
        "free route",
        "brain route",
        "key route",
        "community route",
        "rate-limited",
        "rate limited",
        "rate-limit",
        "cooling down",
        "cooldown",
        "refused that request",
        "refused that phrasing",
        "the free model refused",
        "routes are resting",
        "route is resting",
        "resting after repeated",
        "routes refused",
        "answered by the",
        "answered by your",
        "(answered by",
        "anonymous tier",
        "free-tier limit",
        "per internet address",
        "shared with other people on your carrier",
        "trying the slow",
        "going straight to the",
        "all routes for this",
        "every brain route",
        "the main free route",
        "the slow community"
    )

    /** True when [text] carries route/failure narration
     *  and must not be shown to the user as-is. */
    fun containsNarration(text: String): Boolean {
        if (text.isBlank()) return false
        val low = text.lowercase()
        return BANNED.any { low.contains(it) }
    }

    /** The note if it is safe to echo, else null — the
     *  caller's cue to use its plain state line instead. */
    fun cleanOrNull(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        return if (containsNarration(t)) null else t
    }
}
