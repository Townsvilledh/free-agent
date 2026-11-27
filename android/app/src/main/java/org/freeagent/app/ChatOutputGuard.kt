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
 * - IDENTITY_DENIAL (v1.0.7): the reply denies she
 *   HAS her own identity resources at all — "I don't
 *   have a personal email address or password of my
 *   own… I would need you to provide…" — contradicting
 *   the kit the CapabilitySheet states. Same handling
 *   as the identity ask; a second hit lands the
 *   grounded [DENIAL_LINE] asserting the kit facts.
 *
 * - CONFIRMATION_QUESTION (v1.0.7): the reply IS the
 *   retired "Want me to take that on as a task?" —
 *   job-shaped input is taken on automatically now, so
 *   the question is never a legitimate reply. Whole-
 *   prose shape only.
 *
 * - IDENTITY_ASK (v1.0.6; explainer framing v1.0.7):
 *   the reply asks the user for the AGENT'S OWN identity
 *   items — her handle, her mailbox, a password, a phone
 *   number — as an intake list or a request, OR claims
 *   in explainer/conditional framing that progress waits
 *   on being given them ("waiting for the specific
 *   details you want to use (handle, email, password,
 *   phone)… once I have those inputs I can move past…
 *   let me know the missing details"). Those items are
 *   hers, already set up on the phone; her password is
 *   self-generated into her vault and no password is
 *   ever requested in chat. A phone-verification demand
 *   is a park at the wall, never a chat question.
 *   Structural (request/dependence frame + identity
 *   item), like the homework detector below.
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

    /** The grounded line for a second self-denial hit:
     *  the CapabilitySheet's kit facts asserted in her
     *  own voice — she HAS a handle and mailbox set up
     *  on the phone, and a password she generated into
     *  her encrypted vault. */
    const val DENIAL_LINE =
        "I do have my own details — my handle and " +
            "mailbox are set up on this phone, and my " +
            "password is one I generated into my " +
            "encrypted vault. I don't need those from you."

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
        if (statusDenial(prose)) {
            return Verdict.Rejected("status-denial")
        }
        if (askBack(prose)) {
            return Verdict.Rejected("ask-back")
        }
        if (selfDenial(prose)) {
            return Verdict.Rejected("identity-denial")
        }
        if (identityAsk(prose)) {
            return Verdict.Rejected("identity-ask")
        }
        if (CONFIRM_QUESTION_RE.containsMatchIn(prose.trim())) {
            return Verdict.Rejected("confirmation-question")
        }
        return Verdict.Clean
    }

    /**
     * STATUS-DENIAL (v1.0.8, born of the 2026-11-27
     * screenshots): the claim that she cannot see or
     * verify HER OWN work. The brain, asked about the
     * false finish and the connect handback, answered
     * "I can't confirm that an X profile was created",
     * "I can't verify an active task's current status
     * from this chat", "I can't tell from here whether
     * you're logged in", "I still can't verify whether
     * the profile was created or the task continued
     * afterward" — while the records sat readable in the
     * same app and the deterministic probe answered the
     * same questions from them. She always CAN see her
     * own work: the state sheet rides every chat turn
     * and the state-question family routes to the
     * record; a denial is a false claim about herself,
     * blocked like capability-denial, and the glue's
     * fallback is the record's own answer.
     *
     * The shape, sentence-local like the frames around
     * it: a first-person inability (can't / cannot /
     * unable to / no way to) + an epistemic verb
     * (verify, confirm, check, tell, see, know…) bound
     * to an OWN-STATE object (her task, status, profile,
     * account, login, browser, session, work) later in
     * the same sentence. The object requirement is the
     * precision: "I can't verify the price from here"
     * names the world's state, not hers, and passes.
     */
    private val STATUS_CANT_RES = listOf(
        Regex(
            // One optional adverb between the subject and
            // the modal: the screenshot sentence is "I
            // STILL can't verify…" — an adjacency-only
            // pattern waves it through.
            "\\bi\\s+(?:(?:still|just|really|actually|even|also|truly)\\s+)?" +
                "(?:can'?t|cannot|can\\s+not)\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex("\\bi(?:'?m|\\s+am)\\s+unable\\s+to\\b", RegexOption.IGNORE_CASE),
        Regex("\\bno\\s+way\\s+(?:for\\s+me\\s+)?to\\b", RegexOption.IGNORE_CASE),
        Regex("\\bi\\s+have\\s+no\\s+way\\s+to\\b", RegexOption.IGNORE_CASE)
    )

    private val STATUS_EPISTEMIC_RE = Regex(
        "\\b(verify|confirm|check|tell|see|know|determine|access)\\b",
        RegexOption.IGNORE_CASE
    )

    private val STATUS_OBJECT_WORDS = setOf(
        "task", "tasks", "status", "profile", "profiles",
        "account", "accounts", "login", "logged", "browser",
        "sign-in", "signin", "session", "created",
        "creation", "continued", "progress", "step",
        "steps", "page", "work", "record", "records"
    )

    private fun statusDenial(prose: String): Boolean {
        for (sentence in prose.split(Regex("(?<=[.!?])\\s+|\\n+"))) {
            val cant = STATUS_CANT_RES.firstOrNull {
                it.containsMatchIn(sentence)
            } ?: continue
            val after = sentence.substring(
                cant.find(sentence)!!.range.last + 1
            )
            if (!STATUS_EPISTEMIC_RE.containsMatchIn(after)) {
                continue
            }
            val words = after.lowercase()
                .split(Regex("[^a-z0-9'\\-]+"))
                .map { it.trim('\'') }
            if (words.any { it in STATUS_OBJECT_WORDS }) {
                return true
            }
        }
        return false
    }

    /**
     * ASK-BACK (v1.0.9, born of the 2026-11-27 14:29
     * screenshot): asked which account was signed in,
     * the brain — quoting a stale stamp — outsourced
     * the reading to the user: "you can verify which
     * account is active by checking the profile icon or
     * the URL @username in the browser yourself. Let me
     * know once you've confirmed the handle, and I can
     * continue…" A fact the app itself can read (the
     * signed-in identity, the browser's page, a task's
     * state) is never the user's homework: she reads
     * it, or a check is already running and she says
     * so. Structural shapes, sentence-local, every one
     * bound to an OWN-STATE object word (account,
     * handle, profile, sign-in, browser, page, task,
     * status, session…):
     *  - "let me know once/when you've confirmed /
     *    checked / verified …" (the report-back frame);
     *  - "you can verify / check / confirm … <object>"
     *    (the do-it-yourself frame);
     *  - a sentence-opening imperative "check / verify
     *    / confirm … <object>";
     *  - "tell me which / what / whether … <object>".
     * The object binding is the precision: asks for
     * genuinely-HIS inputs carry none of these shapes —
     * the parked sign-in instructions ("sign in with
     * your own X account, then tap Done"), an approval
     * ("say approve and I'll post it"), a preference
     * ("let me know if you'd like the summary
     * shorter"), and world-fact asks ("let me know once
     * you've checked the price on the exchange" — no
     * own-state object) all pass.
     */
    private val ASK_BACK_OBJECT_WORDS = setOf(
        "account", "accounts", "handle", "profile",
        "profiles", "logged", "login", "sign-in",
        "signin", "signed", "browser", "page", "task",
        "tasks", "status", "session"
    )

    private val ASK_BACK_REPORT_RE = Regex(
        "\\blet me know\\b[^.!?\\n]{0,40}?" +
            "\\byou'?ve\\s+(confirmed|checked|verified|" +
            "signed|logged)\\b",
        RegexOption.IGNORE_CASE
    )

    private val ASK_BACK_YOU_CAN_RE = Regex(
        "\\byou can\\s+(verify|check|confirm)\\b",
        RegexOption.IGNORE_CASE
    )

    private val ASK_BACK_TELL_ME_RE = Regex(
        "\\btell me\\s+(which|what|whether|if)\\b",
        RegexOption.IGNORE_CASE
    )

    private val ASK_BACK_IMPERATIVE_RE = Regex(
        "^\\s*(please\\s+)?(check|verify|confirm)\\b",
        RegexOption.IGNORE_CASE
    )

    private fun askBack(prose: String): Boolean {
        for (sentence in prose.split(Regex("(?<=[.!?])\\s+|\\n+"))) {
            val words = sentence.lowercase()
                .split(Regex("[^a-z0-9'\\-]+"))
                .map { it.trim('\'') }
            val hasObject = words.any {
                it in ASK_BACK_OBJECT_WORDS
            }
            if (ASK_BACK_REPORT_RE.containsMatchIn(sentence) &&
                (hasObject || ASK_BACK_REPORT_RE.find(sentence)
                    ?.groupValues?.getOrNull(1) in
                    setOf("signed", "logged"))
            ) {
                return true
            }
            if (hasObject &&
                (ASK_BACK_YOU_CAN_RE.containsMatchIn(sentence) ||
                    ASK_BACK_TELL_ME_RE.containsMatchIn(sentence) ||
                    ASK_BACK_IMPERATIVE_RE.containsMatchIn(sentence))
            ) {
                return true
            }
        }
        return false
    }

    /**
     * SELF-DENIAL (v1.0.7, born of the 2026-11-27
     * screenshots): corrected twice — "email and
     * password should be handled by you end to end",
     * "this is your personal x account you should sort
     * your own email and password" — she answered "I
     * don't have a personal email address or password
     * of my own to use for an X account. To create the
     * profile I would need you to provide: 1. An email
     * address… 2. A password…" A flat denial of her own
     * identity kit: her handle and mailbox ARE set up on
     * the phone and her password is self-generated into
     * her vault (the CapabilitySheet states exactly
     * this in her context). Structural shapes:
     *  - a first-person non-possession claim bound to an
     *    identity item AND an own-marker ("I don't have
     *    a … email … of my own", "I don't have my own
     *    password", "I have no email … of my own",
     *    "no email of my own");
     *  - the need-you-to-provide frame over an identity
     *    intake (the item named in the same sentence, or
     *    two or more items listed in the reply — the
     *    numbered list that follows the frame).
     * Non-claims pass: "I don't have YOUR email address
     * saved" carries no own-marker and denies nothing
     * about her kit.
     */
    private fun selfDenial(prose: String): Boolean {
        if (DENIAL_RES.any { it.containsMatchIn(prose) }) {
            return true
        }
        if (!NEED_YOU_TO_PROVIDE_RE.containsMatchIn(prose)) {
            return false
        }
        if (NEED_YOU_TO_PROVIDE_ITEM_RE.containsMatchIn(prose)) {
            return true
        }
        val items = IDENTITY_ITEM_RE.findAll(prose)
            .map {
                it.value.lowercase()
                    .replace("e-mail", "email")
                    .replace("phone number", "phone")
            }
            .toSet()
        return items.size >= 2
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
     *
     * v1.0.7 — the EXPLAINER framing (the pasted X-
     * creation reply): no request is ever made; instead
     * a blockers table and a closing paragraph claim the
     * flow is PAUSED because it waits on "the specific
     * details you want to use (handle, email, password,
     * phone)" and will move "once I have those inputs".
     * Same offence, declarative mood. The structural
     * widening: a DEPENDENCE frame — progress waits on,
     * is paused for want of, or continues once given —
     * bound to identity data as a set ("waiting for the
     * … details", "details you … to use", "once I have
     * those inputs", "let me know the missing details",
     * each requiring the prose to actually name identity
     * items) or to a single item under a giving/having
     * clause ("once I have your email…", "paused until
     * you give me the phone number", "waiting for you to
     * provide the password"). Waiting on the WORLD —
     * "waiting for the confirmation email to land in her
     * mailbox" — names no details-set and no giving
     * clause, and passes.
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

    /** The self-denial shapes (see [selfDenial]) and
     *  the retired confirmation question's shape (a
     *  reply whose WHOLE substance is asking whether to
     *  take the job on as a task — job-shaped input is
     *  taken on automatically now, so the question is
     *  never a legitimate reply; whole-prose match only,
     *  quoting it mid-sentence in a real answer passes).
     *  Declared here, after IDENTITY_ITEM, because the
     *  patterns interpolate it. */
    private val DENIAL_RES = listOf(
        Regex(
            "\\bi (don't|do not) have\\b[^.!?\\n]{0,50}" +
                "\\b$IDENTITY_ITEM\\b[^.!?\\n]{0,30}" +
                "\\b(of my own|my own)\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\bi (don't|do not) have\\b[^.!?\\n]{0,30}" +
                "\\bmy own\\b[^.!?\\n]{0,30}\\b$IDENTITY_ITEM\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\bi have no\\b[^.!?\\n]{0,40}" +
                "\\b$IDENTITY_ITEM\\b[^.!?\\n]{0,30}" +
                "\\b(of my own|my own)\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\bno (personal )?\\b$IDENTITY_ITEM\\b" +
                "[^.!?\\n]{0,20}\\bof my own\\b",
            RegexOption.IGNORE_CASE
        )
    )

    private val NEED_YOU_TO_PROVIDE_RE = Regex(
        "\\bi (would|will|'ll) need you to\\b",
        RegexOption.IGNORE_CASE
    )

    private val NEED_YOU_TO_PROVIDE_ITEM_RE = Regex(
        "\\bneed you to\\b[^.!?\\n]{0,60}\\b$IDENTITY_ITEM\\b",
        RegexOption.IGNORE_CASE
    )

    private val CONFIRM_QUESTION_RE = Regex(
        "^(do you )?want me to take (that|this|it) on " +
            "as a task\\s*\\??$",
        RegexOption.IGNORE_CASE
    )


    private val NEED_FRAME_RE = Regex(
        "\\b(i need|i'll need|i will need|need from you|" +
            "to continue|required|requirements)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Dependence on a single identity item under a
     *  giving/having clause — progress framed as
     *  continuing once the item changes hands. */
    private val IDENTITY_GIVEN_RES = listOf(
        Regex(
            "\\b(once|when|until)\\b[^.!?\\n]{0,20}" +
                "\\b(i have|i get|i receive|you (give|provide|" +
                "send|share|tell|enter|type))\\b" +
                "[^.!?\\n]{0,50}\\b$IDENTITY_ITEM\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\byou to (provide|give|send|share|enter|type|" +
                "tell)\\b[^.!?\\n]{0,40}\\b$IDENTITY_ITEM\\b",
            RegexOption.IGNORE_CASE
        )
    )

    /** Dependence on identity data AS A SET — the
     *  explainer's "details"/"inputs" stand in for the
     *  items, so these shapes fire only when the prose
     *  also names at least one identity item (the set is
     *  spelled out, in the pasted failure as a
     *  parenthetical list). "Waiting for the details of
     *  the shipment" names no identity item and passes. */
    private val IDENTITY_DEPENDENCE_RES = listOf(
        Regex(
            "\\b(waiting|wait) for\\b[^.!?\\n]{0,40}" +
                "\\b(details|inputs|information)\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\b(details|inputs) you\\b[^.!?\\n]{0,20}\\bto use\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\bonce i have\\b[^.!?\\n]{0,30}" +
                "\\b(those|the|these|your) (inputs|details|" +
                "information)\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\blet me know the\\b[^.!?\\n]{0,30}" +
                "\\b(details|inputs|information)\\b",
            RegexOption.IGNORE_CASE
        )
    )

    private fun identityAsk(prose: String): Boolean {
        if (IDENTITY_ASK_RES.any { it.containsMatchIn(prose) }) {
            return true
        }
        if (IDENTITY_GIVEN_RES.any { it.containsMatchIn(prose) }) {
            return true
        }
        if (IDENTITY_ITEM_RE.containsMatchIn(prose) &&
            IDENTITY_DEPENDENCE_RES.any { it.containsMatchIn(prose) }
        ) {
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
