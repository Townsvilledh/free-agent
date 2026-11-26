package org.freeagent.app

import org.json.JSONObject

/**
 * Chat→task job detection (v0.13) — the chat is the command
 * surface (Ethuram's law, 2026-11-26: "keep it in the fucking
 * chat"). When the user gives Lille a job in chat, she takes
 * it as a task herself, and the work lives in that same
 * thread (CHAT IS THE ENGINE) — never a second workplace.
 *
 * Detection is deterministic-plus-model, in that order:
 *
 * 1. THIS component runs first, in the submit path (the
 *    WalletPrompt precedent). Strong, checkable signals —
 *    an action aimed at an external system, a watch/recurrence
 *    ask, an explicit delegation, or a long pasted brief —
 *    make a task immediately. A message whose satisfaction
 *    plausibly needs the tool lane but carries no checkable
 *    target is AMBIGUOUS: she asks ONE short question
 *    ([QUESTION]) instead of guessing. Everything else is
 *    ordinary chat and goes to the brain untouched.
 * 2. The model path: the chat system prompt instructs the
 *    brain, when IT judges a message to be a job this layer
 *    missed, to reply with exactly [QUESTION]. The user's
 *    "yes" is then resolved HERE, deterministically, from
 *    the stored thread: [pendingInstruction] finds the ask
 *    that preceded the question, whichever layer asked it.
 *
 * Conservative by design: a missed job stays a chat answer
 * (the user can rephrase, or say "take that on as a task"),
 * while a false task would spend the durable engine — and
 * the user's trust — on conversation. Content she can fully
 * produce by replying (drafts, translations, explanations,
 * summaries of pasted text) is CHAT, never a task: a task is
 * for work that needs tools, the world, or time.
 *
 * FLUENCY (Ethuram's standard, 2026-11-26): input does not
 * have to arrive as a tidy instruction sentence. A bare link,
 * a link with two words, a file sent with "fix this" or with
 * nothing at all — each is job input: the task carries what
 * he sent (the URL, the attachment reference) plus the
 * inferred instruction, and the task text carries the
 * standing gather-it-yourself direction (ChatTaskLink's
 * INTAKE_NOTE) so missing pieces are fetched, read or
 * searched for during execution — never begged for one by
 * one. The only asks are single steps that are genuinely
 * his alone (a sign-in tap, a human check), and those
 * surface as parked steps, not questions in chat.
 *
 * Pure Kotlin + org.json (the house pattern): every rule is
 * pinned by JobDetectTest over a fixture set of job-shaped,
 * ambiguous and chat-shaped messages.
 */
object JobDetect {

    /** The one clarifying question. Wording is contractual:
     *  the system prompt tells the model to ask it verbatim,
     *  and [pendingInstruction] recognises a thread by it. */
    const val QUESTION = "Want me to take that on as a task?"

    sealed class Outcome {
        /** Take [instruction] on as a durable task now. */
        data class StartTask(val instruction: String) : Outcome()
        /** Ask [QUESTION]; [instruction] is what a "yes" starts. */
        data class AskQuestion(val instruction: String) : Outcome()
        /** Ordinary chat — the brain answers. */
        object NotJob : Outcome()
    }

    // ---------- signal vocabularies ----------

    private val PLATFORMS = listOf(
        "x.com", "twitter", "github", "gmail", "google calendar",
        "discord", "telegram", "whatsapp", "instagram", "tiktok",
        "youtube", "devpost", "dorahacks", "facebook", "linkedin",
        "reddit", "product hunt", "hackathon raptors", "agentware"
    )

    private val POSSESSION_TARGETS = listOf(
        "my account", "my email", "my gmail", "my inbox",
        "my calendar", "my x account", "my twitter", "my github",
        "my discord", "my profile", "my page", "my wallet"
    )

    /** Verbs that act on the world rather than produce text. */
    private val EXTERNAL_VERBS = listOf(
        "post", "publish", "sign up", "signup", "register", "join",
        "log in", "login", "apply", "submit", "follow", "unfollow",
        "subscribe", "download", "upload", "book", "reserve",
        "create an account", "open an account", "set up an account",
        "check my", "fetch my", "read my", "send an email",
        "send a message", "message him", "message her", "message them"
    )

    private val MONITOR_PHRASES = listOf(
        "let me know when", "tell me when", "notify me when",
        "alert me", "keep an eye on", "keep checking", "watch for",
        "monitor", "every day", "every morning", "every evening",
        "every hour", "every week", "daily", "when it lands",
        "when the results are out", "when it opens", "when it drops",
        "as soon as it", "once it is out", "once it's out"
    )

    private val DELEGATION_PHRASES = listOf(
        "as a task", "take this on", "take that on", "handle this for me",
        "handle it for me", "get this done", "do this for me",
        "do it for me", "take care of this", "take care of it"
    )

    /** Verbs whose object lives in the live world (a price, a
     *  flight, a listing) — she cannot answer those from chat
     *  knowledge, but without a target the shape is genuinely
     *  ambiguous, so they ask rather than spawn. */
    private val LOOKUP_VERBS = listOf(
        "find", "check", "look up", "lookup", "research", "search for",
        "get me", "book", "create an account", "open an account",
        "set up"
    )

    private val WORK_VERBS = listOf(
        "build", "create", "write", "set up", "setup", "prepare",
        "develop", "implement", "make", "design", "run", "fix",
        "deploy", "draft", "organise", "organize", "plan"
    )

    private val QUESTION_OPENERS = listOf(
        "what", "why", "how", "when", "where", "who", "which",
        "whose", "is it", "is there", "are there", "do you",
        "does it", "did you", "should i", "would it", "could it",
        "can it", "explain", "tell me about"
    )

    /** Openers whose whole satisfaction is a text reply. */
    private val CONTENT_OPENERS = listOf(
        "write a", "write me", "draft a", "draft me", "compose",
        "translate", "rewrite", "rephrase", "summarise this",
        "summarize this", "explain", "tell me a", "give me a poem",
        "give me an example", "list the", "name the"
    )

    private val AFFIRMATIVE_EXACT = setOf(
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "yes please",
        "go ahead", "go for it", "do it", "please do", "yes do it",
        "yes please do", "sounds good", "alright", "fine"
    )

    private val NEGATIVE_EXACT = setOf(
        "no", "nope", "no thanks", "not now", "never mind", "nevermind",
        "don't", "dont", "cancel", "forget it", "leave it"
    )

    // ---------- the classifier ----------

    /** Appended to a link sent with little or no explanation:
     *  the inferred instruction the task executes. */
    const val LINK_NOTE =
        "The user sent this link with little or no explanation. " +
            "Fetch it, read what it is, and report back what it " +
            "is and anything about it that needs doing."

    /**
     * Classify the message the user just sent. [history] is
     * the chat's stored messages INCLUDING the one just sent
     * (submitMessage stores before it detects, the v0.11
     * discipline) — the pending-question resolution reads the
     * messages behind it. [hasAttachment] is the fluency
     * flag: a message carrying a file is judged by the words
     * that came with it plus the fact of the file itself.
     */
    fun classify(
        text: String,
        history: List<JSONObject>,
        hasAttachment: Boolean = false
    ): Outcome {
        val raw = text.trim()
        val low = raw.lowercase()

        // 0. A reply to the clarifying question resolves first:
        //    "yes" starts the ask that was on the table; "no"
        //    drops it and the message is ordinary chat.
        if (raw.isNotEmpty()) {
            val pending = pendingInstruction(history)
            if (pending != null) {
                if (isAffirmative(raw)) return Outcome.StartTask(pending)
                if (isNegative(raw)) return Outcome.NotJob
            }
        }

        // 0.5 Fluency intake: what he sent can BE the job.
        if (hasAttachment) {
            // A file with no words, or with a fragment ("fix
            // this", "this one"), is a job handed over, not a
            // conversation opener. A file with a real question
            // falls through and is answered in chat.
            if (raw.isEmpty()) return Outcome.StartTask(raw)
            if (isFragment(low)) return Outcome.StartTask(raw)
        }
        if (raw.isEmpty()) return Outcome.NotJob
        linkInstruction(raw, low)?.let { return Outcome.StartTask(it) }

        // 1. Strong signals — a task, no question asked.
        if (hasStrongSignal(raw, low)) return Outcome.StartTask(raw)

        // 2. Chat-shaped: questions and text-only asks are
        //    answered by replying. (A question that carried a
        //    strong signal already became a task above —
        //    "can you post this to X?" is a job in a question's
        //    clothing.)
        if (isQuestionShaped(low) || isContentAsk(low)) {
            return Outcome.NotJob
        }

        // 3. Ambiguous band: a work-shaped imperative whose
        //    satisfaction plausibly needs tools or the live
        //    world, but with no checkable target — ask once.
        if (isAmbiguous(raw, low)) return Outcome.AskQuestion(raw)

        return Outcome.NotJob
    }

    private fun hasStrongSignal(raw: String, low: String): Boolean {
        if (DELEGATION_PHRASES.any { low.contains(it) }) return true
        if (low.startsWith("task:")) return true
        if (MONITOR_PHRASES.any { low.contains(it) }) return true
        // An outward send is a world action by itself — the
        // recipient is the target, no platform need be named.
        // (Wallet sends never reach here: WalletPrompt's exact
        // parser runs first in the submit path.)
        if (containsPhrase(low, "send an email") ||
            containsPhrase(low, "send a message to") ||
            containsPhrase(low, "send an e-mail")
        ) return true

        val hasTarget = PLATFORMS.any { low.contains(it) } ||
            POSSESSION_TARGETS.any { low.contains(it) } ||
            // The bare platform name: "post this to X", "on X" —
            // preposition-bound so "3 x 4" is not a target.
            Regex("(^|[^a-z])(to|on|via|my) x([^a-z]|$)")
                .containsMatchIn(low) ||
            low.contains("http://") || low.contains("https://") ||
            low.contains("www.")
        if (hasTarget && EXTERNAL_VERBS.any { containsPhrase(low, it) }) {
            return true
        }
        // An errand pointed at a place: "go to <site> and …".
        if (hasTarget && (low.startsWith("go to ") ||
                low.contains(" go to ") ||
                Regex("\\bopen\\s+\\S+\\.\\S+").containsMatchIn(low))
        ) return true
        // Account creation aimed at a named place is a world
        // action. UNaimed ("create an account for me") names
        // no target at all — that is the ambiguous band's
        // question, not a spawn.
        if ((low.contains("create my") || low.contains("create an account") ||
                low.contains("open an account")) && hasTarget
        ) return true

        // A long pasted brief: multi-clause, work-verbed, not a
        // question — a spec the user wants executed, not read.
        if (raw.length >= LONG_BRIEF_CHARS &&
            WORK_VERBS.any { containsPhrase(low, it) } &&
            !low.endsWith("?")
        ) return true

        return false
    }

    private fun isQuestionShaped(low: String): Boolean {
        if (low.endsWith("?")) return true
        val first = low.trimStart()
        return QUESTION_OPENERS.any { first.startsWith(it) }
    }

    private fun isContentAsk(low: String): Boolean =
        CONTENT_OPENERS.any { low.contains(it) }

    private fun isAmbiguous(raw: String, low: String): Boolean {
        val words = low.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 3) return false
        val startsWithLookup = LOOKUP_VERBS.any { low.startsWith(it) }
        val startsWithWork = WORK_VERBS.any { low.startsWith(it) } ||
            low.startsWith("please ")
        return when {
            // Live-world lookup with no target named.
            startsWithLookup -> true
            // A medium-length work brief: long enough to be a
            // spec, short enough that intent is not obvious.
            raw.length in MEDIUM_BRIEF_CHARS..(LONG_BRIEF_CHARS - 1) &&
                WORK_VERBS.any { containsPhrase(low, it) } -> true
            // A plain work imperative of some substance.
            startsWithWork && words.size >= 5 -> true
            else -> false
        }
    }

    /** Word-boundary phrase match, so "post" does not fire
     *  inside "poster" and "join" not inside "joint". */
    private fun containsPhrase(low: String, phrase: String): Boolean =
        Regex("(^|[^a-z])" + Regex.escape(phrase) + "([^a-z]|$)")
            .containsMatchIn(low)

    private val URL_RE = Regex("https?://\\S+|www\\.\\S+")

    /**
     * A link sent bare, or with only a few words around it,
     * is a job: fetch it and report. Returns the task text —
     * what he sent, plus the inferred instruction — or null
     * when the message is not link-shaped. A link carrying a
     * question is NOT this: questions fall through to the
     * chat guards below.
     */
    private fun linkInstruction(raw: String, low: String): String? {
        if (!URL_RE.containsMatchIn(low)) return null
        if (isQuestionShaped(low)) return null
        val words = low.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size > LINK_MAX_WORDS) return null
        return raw + "\n\n" + LINK_NOTE
    }

    private val FRAGMENT_EXACT = setOf(
        "this", "this one", "here", "it", "do it", "fix this",
        "handle this", "sort this", "check this", "post this",
        "send this", "deal with this", "look at this"
    )

    /** A fragment: a handful of words, not a question, that
     *  either is a known hand-over phrase or opens with a
     *  work verb. "thank you" with a photo is conversation;
     *  "fix this" with a photo is a job. */
    private fun isFragment(low: String): Boolean {
        if (low.endsWith("?")) return false
        val words = low.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 4) return false
        if (low in FRAGMENT_EXACT) return true
        return WORK_VERBS.any { low.startsWith(it) }
    }

    // ---------- the pending question ----------

    /**
     * The instruction on the table, if the thread's shape is
     * […, user instruction, assistant QUESTION, user reply]:
     * the nearest user message before a stored assistant
     * message that ends with [QUESTION]. Works whichever layer
     * asked — this component (the question is appended as an
     * assistant message) or the model (its reply is stored the
     * same way). Null when no question is pending.
     */
    fun pendingInstruction(history: List<JSONObject>): String? {
        if (history.size < 3) return null
        // The last message is the reply being classified; the
        // one before it must be the question.
        val questionMsg = history[history.size - 2]
        if (questionMsg.optString("role") != "assistant") return null
        if (!questionMsg.optString("content").trim().endsWith(QUESTION)) {
            return null
        }
        for (i in history.size - 3 downTo 0) {
            val m = history[i]
            if (m.optString("role") == "user") {
                val text = m.optString("content").trim()
                return if (text.isEmpty()) null else text
            }
        }
        return null
    }

    /** A short, clearly-affirmative reply. Long messages are
     *  never read as a bare "yes" — they carry their own
     *  content and get classified on their own shape. */
    fun isAffirmative(text: String): Boolean {
        val low = text.trim().lowercase().trimEnd('.', '!')
        if (low.length > MAX_REPLY_CHARS) return false
        if (low in AFFIRMATIVE_EXACT) return true
        return low.startsWith("yes ") || low.startsWith("yes,") ||
            low.startsWith("yeah ") || low.startsWith("go ahead")
    }

    fun isNegative(text: String): Boolean {
        val low = text.trim().lowercase().trimEnd('.', '!')
        if (low.length > MAX_REPLY_CHARS) return false
        if (low in NEGATIVE_EXACT) return true
        return low.startsWith("no ") || low.startsWith("no,") ||
            low.startsWith("don't ") || low.startsWith("do not")
    }

    private const val LONG_BRIEF_CHARS = 600
    private const val MEDIUM_BRIEF_CHARS = 200
    private const val MAX_REPLY_CHARS = 40
    private const val LINK_MAX_WORDS = 6
}
