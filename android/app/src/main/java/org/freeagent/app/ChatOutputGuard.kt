package org.freeagent.app

/**
 * The chat-output guard (2026-11-26, Ethuram: "I don't
 * want whatever this is to repeat itself").
 *
 * A deterministic screen applied to every chat reply
 * BEFORE display. It exists because of one live reply —
 * her answer to "update" — which stacked three defects in
 * a single bubble: an invented placeholder token
 * (<DISCORD_INVITE_LINK>) presented as a fetched fact,
 * confabulated SELF-DIALOGUE (Answer: / Update: / Lille:
 * speaker turns inside one reply, a fake Q&A with
 * herself), and an ask-list for input the work itself
 * should fetch. Status-from-state stops a status question
 * reaching a model at all; this guard is the net for
 * every other reply, from any route.
 *
 * Detectors (all over the PROSE only — fenced code blocks
 * and inline code are stripped first, so HTML, generics
 * and angle brackets inside code never false-positive):
 *
 * - PLACEHOLDER: angle-bracket SCREAMING tokens
 *   (<DISCORD_INVITE_LINK>), <insert …> forms, bare
 *   *_LINK / *_URL / *_TOKEN words presented as content,
 *   TODO:/FIXME: markers standing in for content.
 * - SELF_DIALOGUE: two or more speaker-turn markers at
 *   line starts (Answer:, Update:, Question:, Response:,
 *   Reply:, Assistant:, User:, Lille:, Me:) — one reply
 *   staging a conversation with itself. A single marker
 *   (a heading "Update: …") is ordinary prose and passes.
 *
 * - CAPABILITY_DENIAL (v1.0.5): the reply claims a
 *   capability she HAS is unavailable "in this
 *   environment", or offers the user instructions to run
 *   locally / a wait for "a future environment" in place
 *   of doing the work. Born of the 2026-11-27 "why
 *   paused" answer, which denied her browser/Gmail/
 *   video tools wholesale on a build that has them all.
 *   The capability sheet in her context states the facts;
 *   this detector is the net when a route contradicts
 *   them anyway. Shapes are narrow — a sentence-local
 *   negation bound to availability, the local-instructions
 *   offer, the future-environment wait — so legitimate
 *   hand-offs ("that part needs the computer lane — I've
 *   sent it there") and honest partial states ("Gmail
 *   isn't connected yet — one step in Settings") pass.
 *
 * On a hit the glue silently regenerates ONCE on the next
 * route; on a second hit it shows [PLAIN_LINE] and logs
 * the rejected text to the trace only. The verdict's
 * reason is a trace tag, never user copy.
 *
 * Pure, pinned by ChatOutputGuardTest against fixtures
 * reproducing the failure's shapes and against healthy
 * replies.
 */
object ChatOutputGuard {

    /** The one plain line shown when a regenerated reply
     *  also fails the screen. No mechanics, no blame. */
    const val PLAIN_LINE = "That reply didn't come out right — ask me again."

    sealed class Verdict {
        object Clean : Verdict()
        data class Rejected(val reason: String) : Verdict()
    }

    private val FENCE_RE = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_CODE_RE = Regex("`[^`\\n]*`")

    private val ANGLE_PLACEHOLDER_RE =
        Regex("<[A-Z][A-Z0-9_]{2,}>")
    private val ANGLE_INSERT_RE =
        Regex("<\\s*(insert|placeholder|your)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val BARE_TOKEN_RE =
        Regex("\\b[A-Z][A-Z0-9_]{2,}_(LINK|URL|TOKEN|HERE)\\b")
    private val TODO_MARKER_RE =
        Regex("(^|\\n)\\s*(TODO|FIXME|XXX)\\s*:", RegexOption.IGNORE_CASE)
    private val BRACKET_INSERT_RE =
        Regex("\\[\\s*(insert|placeholder)\\b[^\\]]*\\]", RegexOption.IGNORE_CASE)

    /** Speaker-turn labels at a line start, optionally
     *  wrapped in markdown bold. */
    private val SPEAKER_TURN_RE = Regex(
        "^\\s{0,3}(\\*\\*|__)?(Answer|Update|Question|Response|Reply|" +
            "Assistant|User|Lille|Me)(\\*\\*|__)?:",
        RegexOption.MULTILINE
    )

    /** The prose view of a reply: code fenced off, because
     *  code legitimately contains the shapes the detectors
     *  hunt (HTML tags, generics, TODO comments in code
     *  samples are content the user asked for). */
    fun proseOnly(text: String): String =
        INLINE_CODE_RE.replace(FENCE_RE.replace(text, " "), " ")

    /** Capability-denial shapes (see the class doc):
     *  a negation bound to availability in the same
     *  sentence — "tools are not available", "aren't
     *  available in this environment" — the offer of
     *  instructions the user can run locally, and the
     *  wait for a future environment. */
    private val ENV_DENIAL_RES = listOf(
        Regex(
            "\\b(aren't|isn't|are not|is not|not|no|never)\\b" +
                "[^.!?\\n]{0,60}\\bavailable in this environment\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\b(tools?|capabilit\\w+|features?)\\b" +
                "[^.!?\\n]{0,50}\\b(aren't|isn't|are not|is not|not)" +
                " available\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\b(tools?|capabilit\\w+|features?)\\b" +
                "[^.!?\\n]{0,50}\\bunavailable\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\bunavailable in this environment\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\byou can run( \\w+)? locally\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\b(instructions|steps|guide|commands)\\b" +
                "[^.!?\\n]{0,50}\\brun locally\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\b(wait|waiting) for a future environment\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\ba future environment where\\b",
            RegexOption.IGNORE_CASE
        )
    )

    fun verdict(text: String): Verdict {
        val prose = proseOnly(text)
        if (ANGLE_PLACEHOLDER_RE.containsMatchIn(prose) ||
            ANGLE_INSERT_RE.containsMatchIn(prose) ||
            BARE_TOKEN_RE.containsMatchIn(prose) ||
            TODO_MARKER_RE.containsMatchIn(prose) ||
            BRACKET_INSERT_RE.containsMatchIn(prose)
        ) {
            return Verdict.Rejected("placeholder")
        }
        val turns = SPEAKER_TURN_RE.findAll(prose).count()
        if (turns >= 2) {
            return Verdict.Rejected("self-dialogue")
        }
        if (ENV_DENIAL_RES.any { it.containsMatchIn(prose) }) {
            return Verdict.Rejected("capability-denial")
        }
        return Verdict.Clean
    }

    fun isClean(text: String): Boolean = verdict(text) is Verdict.Clean
}
