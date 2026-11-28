package org.freeagent.app

/**
 * THE INTENT CONTRACT (v1.0.13) — intelligence-led
 * classification, executed record-first.
 *
 * Ethuram's ruling (2026-11-28): "I don't want
 * hardcodes I want a smart and proficient that can
 * distinguish task and handle them proficiently."
 * The deterministic layers keep what they already
 * cover — the command grammars (kill, resume, browser
 * open/close, log out/switch), the probe family,
 * JobDetect's first stage. Everything they do NOT
 * cover used to fall to an uncontracted brain reply,
 * which is how a stated task got answered as a
 * conversation (the 2026-11-28 intake list) and how a
 * UI demand got a narrated launch that never happened
 * ("I'm launching the Fluid wallet UI now…").
 *
 * Now the residue is classified by the brain under a
 * STRICT OUTPUT CONTRACT: every chat reply must open
 * with one verdict line — INTENT: TASK, INTENT:
 * COMMAND <target>, or INTENT: CHAT. The verdict is
 * parsed deterministically ([parse]) and EXECUTED by
 * the glue BEFORE any reply text is composed:
 *
 * - TASK → the task record is created from the user's
 *   own message and its lane started; the user-visible
 *   reply is the record's start acknowledgement. The
 *   model's text is discarded entire. Narration
 *   physically cannot precede the action, because the
 *   action is what produces the reply.
 * - COMMAND → the navigation is performed (UiScreens),
 *   and the reply is the performed fact's line.
 * - CHAT → the verdict line is stripped and the rest
 *   is the answer, screened by the output guard like
 *   every reply.
 *
 * A reply that ignores the contract (no verdict line)
 * is treated as CHAT whole — the pre-1.0.13 behaviour,
 * still guard-screened; the contract is the discipline,
 * the guard is the net. While a reply streams, the
 * live bubble shows nothing of it until the verdict is
 * known ([displayText]): a TASK/COMMAND verdict's text
 * is never displayed at all.
 *
 * The contract is domain-general by construction —
 * repo, browser, mail, wallet-adjacent, writing,
 * research, scheduling, social, UI — because it asks
 * for INTENT, not keywords. Its proof is a held-out
 * set of novel phrasings (the ship note carries it):
 * a novel phrasing that needs a new rule is a failure
 * of this design, named as such, never patched over.
 *
 * Pure; the parse and the stream gate are pinned by
 * Lille113Test. The classification itself is the
 * model's — its outcomes on the held-out set are
 * reported in the ship note, not asserted here.
 */
object IntentContract {

    sealed class Verdict {
        /** A stated job: create the record, start the
         *  lane, answer from the record. */
        object Task : Verdict()

        /** A UI command for a screen she has. */
        data class Command(val target: UiScreens.Target) : Verdict()

        /** A UI command naming a screen she does not
         *  have: the honest line, never a narration. */
        object UnknownCommand : Verdict()

        /** Ordinary conversation: [body] is the answer
         *  (the reply with any verdict line stripped). */
        data class Chat(val body: String) : Verdict()
    }

    /** The contract block, appended to every chat system
     *  prompt after the capability and state sheets. */
    const val INSTRUCTION =
        "INTENT FIRST — before anything else, classify " +
            "the user's latest message and begin your " +
            "reply with exactly one verdict line:\n" +
            "INTENT: TASK — the message asks you to DO " +
            "work that needs tools, the live world, or " +
            "time, in any domain (web, mail, code and " +
            "repos, research, scheduling, accounts, " +
            "files, media). Write NOTHING after this " +
            "line: the app creates the task and starts " +
            "it before any reply is composed, and " +
            "answers from the record.\n" +
            "INTENT: COMMAND wallet|fluid|settings|chats " +
            "— the message asks you to bring up, open or " +
            "show one of your screens (wallet = your own " +
            "wallet view; fluid = the Fluid wallet; " +
            "settings; chats = the chats list). Write " +
            "NOTHING after this line: the app performs " +
            "the navigation and answers from the " +
            "performed fact.\n" +
            "INTENT: CHAT — everything else: questions, " +
            "discussion, opinions, and content you can " +
            "fully produce by replying (drafts, " +
            "translations, explanations, summaries). " +
            "Then answer normally from the next line.\n" +
            "Never narrate an action instead of issuing " +
            "its verdict — \"I'm launching…\", \"I'll " +
            "open…\", \"you should see… appear\" are " +
            "hallucinations when no verdict performed " +
            "them. Never ask the user for information " +
            "(a link, a file location, which repo or " +
            "file): a fact you do not hold, you fetch " +
            "yourself. The only asks are steps that are " +
            "physically his — a sign-in only he can " +
            "perform in your browser panel, a code he " +
            "types there, an approval."

    private const val TASK_LINE = "intent: task"
    private const val CHAT_LINE = "intent: chat"
    private const val COMMAND_PREFIX = "intent: command"

    /** Parse a finished reply's verdict. The verdict
     *  line is the first non-empty line; matching is
     *  case-insensitive and tolerant of surrounding
     *  whitespace only — the contract is strict because
     *  execution hangs on it. No verdict line → the
     *  whole reply is CHAT, exactly as before. */
    fun parse(reply: String): Verdict {
        val lines = reply.lines()
        val firstIdx = lines.indexOfFirst { it.isNotBlank() }
        if (firstIdx < 0) return Verdict.Chat(reply)
        val first = lines[firstIdx].trim().lowercase()
        val rest = lines.drop(firstIdx + 1)
            .joinToString("\n").trim()
        return when {
            first == TASK_LINE -> Verdict.Task
            first == CHAT_LINE -> Verdict.Chat(rest)
            first == COMMAND_PREFIX ||
                first.startsWith("$COMMAND_PREFIX ") -> {
                val token = first.removePrefix(COMMAND_PREFIX)
                    .trim().split(Regex("\\s+"))
                    .firstOrNull { it.isNotEmpty() } ?: ""
                val target = UiScreens.targetForToken(token)
                if (target != null) {
                    Verdict.Command(target)
                } else {
                    Verdict.UnknownCommand
                }
            }
            else -> Verdict.Chat(reply)
        }
    }

    /**
     * What the live bubble may show while [partial] is
     * still streaming — null means show nothing yet (the
     * "…thinking" state stands). A TASK or COMMAND
     * verdict's text is NEVER shown: its reply comes
     * from the execution, not the stream. A CHAT verdict
     * streams its body (verdict line stripped). A reply
     * that has plainly not opened with a verdict (its
     * first line is complete and is no verdict) streams
     * as before. A partial that could still BECOME a
     * verdict line shows nothing — narration cannot
     * precede the action on screen either.
     */
    fun displayText(partial: String): String? {
        val trimmed = partial.trimStart()
        if (trimmed.isEmpty()) return null
        val nl = trimmed.indexOf('\n')
        val firstLine = if (nl >= 0) {
            trimmed.substring(0, nl).trim().lowercase()
        } else {
            trimmed.trim().lowercase()
        }
        if (nl < 0) {
            // The first line is still arriving: hold the
            // display while it could be a verdict line
            // (a prefix of one, or a finished verdict
            // awaiting its newline).
            val couldBeVerdict = TASK_LINE.startsWith(firstLine) ||
                CHAT_LINE.startsWith(firstLine) ||
                COMMAND_PREFIX.startsWith(firstLine) ||
                firstLine == TASK_LINE || firstLine == CHAT_LINE ||
                firstLine.startsWith("$COMMAND_PREFIX ")
            if (couldBeVerdict) return null
            // A long first line is plainly not a verdict.
            return if (firstLine.length > COMMAND_PREFIX.length + 12) {
                partial
            } else {
                null
            }
        }
        return when {
            firstLine == TASK_LINE -> null
            firstLine == CHAT_LINE ->
                trimmed.substring(nl + 1).trimStart()
                    .ifEmpty { null }
            firstLine == COMMAND_PREFIX ||
                firstLine.startsWith("$COMMAND_PREFIX ") -> null
            else -> partial
        }
    }
}
