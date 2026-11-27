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
 * - IDENTITY_ASK (v1.0.6): the reply asks the user for
 *   the AGENT'S OWN identity items — her handle, her
 *   mailbox, a password, a phone number — as an intake
 *   list or a request. Those items are hers, already set
 *   up on the phone; her password is self-generated into
 *   her vault and no password is ever requested in chat.
 *   A phone-verification demand is a park at the wall,
 *   never a chat question. Structural (request frame +
 *   identity item), like the homework detector below.
 *
 * On a hit the glue silently regenerates ONCE on the next
 * route; on a second hit it shows [PLAIN_LINE] and logs
 * the rejected text to the trace only. The verdict's
 * reason is a trace tag, never user copy.
 *
 * REPORTS (v1.0.6): [verdictForReport] adds the HOMEWORK
 * detector for task-originated text (graded reports —
 * ChatTaskLink screens every model-written field through
 * it). Born of the Agentware report Ethuram screenshot-
 * ted on 2026-11-27: a numbered menu of deliverables for
 * HIM to execute — "so you can execute the remaining
 * steps on your phone or computer", "ready for you to
 * push at kickoff", "you'll just need to run it locally",
 * "just let me know which you'd like first". Lille does
 * the work or names the one step that is genuinely his;
 * a report that hands him a worklist is a failed report,
 * whatever its state line says. The detector hunts the
 * OFFER shapes — work framed as his to execute — not the
 * honest parked line ("tap the browser panel to pass
 * it"), which names her wait, not his homework.
 *
 * Pure, pinned by ChatOutputGuardTest against fixtures
 * reproducing the failure's shapes and against healthy
 * replies.
 */
object ChatOutputGuard {

    /** The one plain line shown when a regenerated reply
     *  also fails the screen. No mechanics, no blame. */
    const val PLAIN_LINE = "That reply didn't come out right — ask me again."

    /** The grounded line for a second identity-ask hit:
     *  a true sentence in her own voice — her details are
     *  hers and already set up — instead of the generic
     *  shrug. */
    const val IDENTITY_LINE =
        "I already have my own details set up on this " +
            "phone — I don't need those from you."

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
        if (identityAsk(prose)) {
            return Verdict.Rejected("identity-ask")
        }
        return Verdict.Clean
    }

    /**
     * IDENTITY ASK (v1.0.6, born of the 2026-11-27
     * screenshot): the brain answered his "resume" with
     * an intake list asking for HER desired handle, the
     * email to use, a password, and his phone number —
     * every item already hers (handle and mailbox from
     * her own setup, her password self-generated into
     * her vault), and a password must never be requested
     * in chat at all. The detector is structural — a
     * REQUEST frame bound to an identity item, in
     * whatever wording:
     *  - a chosen-for-her framing ("desired handle",
     *    "preferred username");
     *  - a request verb bound to an identity item within
     *    the same sentence ("I need your email", "send
     *    me the password", "what is your phone number");
     *  - the intake list: a need frame ("to continue I
     *    need", "I'll need") over a text naming two or
     *    more identity items.
     * Facts ABOUT identity pass: "your password lives
     * only in the vault" requests nothing and carries no
     * request frame.
     */
    private val IDENTITY_ITEM =
        "(handle|username|e-?mail|password|passcode|phone( number)?)"

    private val IDENTITY_ASK_RES = listOf(
        Regex(
            "\\b(desired|preferred|chosen)\\b[^.!?\\n]{0,30}" +
                "\\b(handle|username)\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\b(i need|i'll need|i will need|send me|give me|" +
                "tell me|please (provide|send|give|share|enter|" +
                "type))\\b[^.!?\\n]{0,40}\\b$IDENTITY_ITEM\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\bwhat('s| is) your\\b[^.!?\\n]{0,30}" +
                "\\b$IDENTITY_ITEM\\b",
            RegexOption.IGNORE_CASE
        )
    )

    private val IDENTITY_ITEM_RE =
        Regex("\\b$IDENTITY_ITEM\\b", RegexOption.IGNORE_CASE)

    private val NEED_FRAME_RE = Regex(
        "\\b(i need|i'll need|i will need|need from you|" +
            "to continue|required|requirements)\\b",
        RegexOption.IGNORE_CASE
    )

    private fun identityAsk(prose: String): Boolean {
        if (IDENTITY_ASK_RES.any { it.containsMatchIn(prose) }) {
            return true
        }
        if (!NEED_FRAME_RE.containsMatchIn(prose)) return false
        val items = IDENTITY_ITEM_RE.findAll(prose)
            .map {
                it.value.lowercase()
                    .replace("e-mail", "email")
                    .replace("phone number", "phone")
            }
            .toSet()
        return items.size >= 2
    }

    fun isClean(text: String): Boolean = verdict(text) is Verdict.Clean

    /** Homework PATTERNS (see the class doc; v1.0.6
     *  structural form — Ethuram: "No hardcoding"). The
     *  detector hunts the PATTERN — work framed as the
     *  USER's to execute — as subject/frame + verb-class
     *  shapes, never the screenshot's literal sentences:
     *  "you" bound to an execution verb; a readiness or
     *  possession frame handing him an infinitive
     *  ("ready for you to…", "yours to run"); the
     *  minimised imposition ("you just need to…"); the
     *  choice-among-deliverables close ("let me know
     *  which…", "which one you want"); and the offer of
     *  work-products framed for him ("I can generate …
     *  for you"). Her own work statements ("I ran the
     *  checks") and the honest parked line carry none of
     *  these frames and pass. */
    private val HOMEWORK_RES = listOf(
        Regex(
            "\\byou\\b[^.!?\\n]{0,40}" +
                "\\b(execute|run|push|post|send|upload|deploy|" +
                "submit|perform|apply|follow)\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\bready for you to\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\b(for you|yours) to " +
                "(execute|run|push|post|send|upload|deploy|" +
                "submit|perform|do)\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\byou('ll| will)? just need to\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\blet me know (which|what)\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\bwhich (one |ones )?you('d| would| do)? ?" +
                "(like|want|prefer)\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\b(tell me|say) which\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\bI can (prepare|generate|draft|create|build|" +
                "write|put together)\\b[^.!?\\n]{0,50}" +
                "\\bfor you\\b",
            RegexOption.IGNORE_CASE
        )
    )

    /** The verdict for TASK-ORIGINATED text (a graded
     *  report's model-written fields): every chat verdict,
     *  plus homework. A hit means the field is withheld
     *  and the report recomposes from the record alone —
     *  reports have no regeneration route, and a withheld
     *  summary is always more honest than a worklist. */
    fun verdictForReport(text: String): Verdict {
        val base = verdict(text)
        if (base is Verdict.Rejected) return base
        val prose = proseOnly(text)
        if (HOMEWORK_RES.any { it.containsMatchIn(prose) }) {
            return Verdict.Rejected("homework")
        }
        return Verdict.Clean
    }

    fun isCleanForReport(text: String): Boolean =
        verdictForReport(text) is Verdict.Clean
}
