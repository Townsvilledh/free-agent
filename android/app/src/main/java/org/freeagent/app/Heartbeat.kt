package org.freeagent.app

import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * THE HEARTBEAT PASS (the OpenClaw pattern, reimplemented).
 *
 * Why this exists, and why it is NOT a cron job: a cron job
 * (see ScheduledJobs) fires at a precise time whether or
 * not anything changed, and runs in isolation. A heartbeat
 * is the opposite shape — a periodic, light, cheap pass
 * over two things the user already maintains:
 *
 *   1. an editable checklist (conventionally HEARTBEAT.md)
 *      of small standing items, some carrying a due date;
 *   2. the standing task states (the durable task records,
 *      whose states live in [TaskStates]).
 *
 * The law of the heartbeat is SILENCE BY DEFAULT. Most
 * passes should do nothing at all: write nothing, call no
 * model, and surface nothing. A pass only earns the user's
 * attention when it finds a NEED — something that is
 * genuinely waiting on the user, or finished work the user
 * has not yet been told about, or a checklist item whose
 * due date has arrived.
 *
 * The skip rule is what makes the pass cheap enough to run
 * often: the caller remembers the [signature] of the last
 * pass, and when the checklist is empty AND the signature
 * is unchanged, the outcome is [Outcome.Skip] — the pass
 * is skipped entirely, as if it never ran. The signature
 * is a stable hash over the checklist text and each task's
 * id|state|updatedAt, so any real change (a new checklist
 * line, a task moving state, a task record being touched)
 * changes it.
 *
 * The one hard rule, pinned by tests: A NEED IS NEVER
 * SWALLOWED BY THE SKIP. If any need exists, the outcome
 * is [Outcome.Surface] regardless of whether the signature
 * matches the last one. A parked job must not sit silent
 * just because nothing else changed since the last pass —
 * "nothing changed" is exactly the situation in which a
 * job parked at a human wall needs saying again through
 * the proper surface, and the heartbeat's job is to notice
 * it, not to optimise it away.
 *
 * What counts as a need:
 *
 * - PARKED: a task in [TaskStates.PARKED_AT_WALL] is
 *   waiting on a step only the user can take (a human
 *   check in the browser, a sign-in, a tap). That is the
 *   user's business immediately.
 * - UNREPORTED: a task in COMPLETED or FAILED whose finish
 *   has not yet been reported through its own channel. For
 *   a chat-born task (a non-empty "originChatId") the
 *   report channel is its chat, and the record's
 *   "chatReportState" field holds the last state reported
 *   there — so a finish is unreported exactly when the
 *   task's state differs from that value. For a non-chat
 *   task there is no chat to compare against, so the
 *   record carries an explicit "heartbeatReported" flag;
 *   a missing flag means false (never reported), which is
 *   the honest default for legacy records.
 * - DUE: a checklist item whose parsed due date is at or
 *   before now.
 *
 * Honest limits: this component is pure decision logic.
 * It does not schedule itself, does not persist the
 * signature, does not write any report, and does not call
 * any model — the caller does those things, and only when
 * [isQuiet] is false. The checklist parser is deliberately
 * small: checkbox lines only, one due-date shape only
 * ("due: YYYY-MM-DD"). Anything fancier belongs in the
 * checklist author's hands, not in a parser guessing at
 * prose.
 *
 * Pure Kotlin + org.json; pinned by HeartbeatTest.
 */
object Heartbeat {

    /** One parsed checklist item: its text (the line's
     *  words after the checkbox, trimmed) and its due
     *  instant in epoch milliseconds (UTC midnight of the
     *  "due: YYYY-MM-DD" date), or null when the item
     *  carries no parseable due date. */
    data class Item(val text: String, val dueMs: Long?)

    /** One thing that needs the user. [kind] is one of
     *  "parked", "unreported" or "due"; [text] is the
     *  user-facing line, in plain work terms. */
    data class Need(val kind: String, val text: String)

    /** The verdict of one pass. [Skip] = nothing to look
     *  at and nothing changed; [Silent] = looked, nothing
     *  needs the user; [Surface] = these needs are the
     *  user's business now. */
    sealed class Outcome {
        object Skip : Outcome()
        object Silent : Outcome()
        data class Surface(val needs: List<Need>) : Outcome()
    }

    private val CHECKBOX_RE = Regex("^\\s*[-*]\\s*\\[\\s*]\\s*(.*)$")
    private val DUE_RE = Regex("due:\\s*(\\d{4}-\\d{2}-\\d{2})")

    /** Parse the checklist's checkbox items, in file
     *  order. Lines like "- [ ] text" or "* [ ] text" are
     *  items; every other line (headings, prose, checked
     *  "- [x]" lines, blanks) is ignored — a checked item
     *  is done, and done is not a heartbeat concern.
     *  A null or blank checklist has no items. */
    fun items(checklistText: String?): List<Item> {
        if (checklistText.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Item>()
        for (line in checklistText.lines()) {
            val m = CHECKBOX_RE.find(line) ?: continue
            val text = m.groupValues[1].trim()
            if (text.isEmpty()) continue
            out.add(Item(text = text, dueMs = parseDueMs(text)))
        }
        return out
    }

    /** The due instant for [text], or null when it carries
     *  no "due: YYYY-MM-DD" or the date does not parse
     *  (an unparseable date is no date — never a guessed
     *  one). Dates parse as UTC midnight, so a due item
     *  means the same instant on every device. */
    private fun parseDueMs(text: String): Long? {
        val m = DUE_RE.find(text) ?: return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.isLenient = false
            fmt.parse(m.groupValues[1])?.time
        } catch (e: Exception) {
            null
        }
    }

    /** A stable signature over everything a pass reads:
     *  the checklist text plus each task's
     *  id|state|updatedAt, in list order. Same inputs →
     *  same signature, on every run and every device
     *  (SHA-256 hex over a fixed encoding). */
    fun signature(checklistText: String?, tasks: List<JSONObject>): String {
        val sb = StringBuilder()
        sb.append(checklistText ?: "")
        sb.append('\n')
        for (task in tasks) {
            sb.append(task.optString("id"))
            sb.append('|')
            sb.append(task.optString("state"))
            sb.append('|')
            // updatedAt may be stored as a number or a
            // string in legacy records; read it as a long
            // when numeric and fall back to its raw text,
            // so either shape feeds the hash stably.
            if (task.has("updatedAt") && !task.isNull("updatedAt")) {
                sb.append(task.opt("updatedAt").toString())
            }
            sb.append('\n')
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** The gist of a task for a need line: the first 60
     *  characters of its text — enough to recognise the
     *  job, never a dump of the whole record. */
    private fun gist(task: JSONObject): String =
        task.optString("text").take(60)

    /** Whether a finished (completed/failed) task's finish
     *  has not yet been reported — see the class doc for
     *  the chat vs non-chat rule. */
    private fun isUnreported(task: JSONObject): Boolean {
        val state = task.optString("state")
        if (state != TaskStates.COMPLETED && state != TaskStates.FAILED) {
            return false
        }
        return if (task.optString("originChatId").isNotEmpty()) {
            state != task.optString("chatReportState")
        } else {
            !task.optBoolean("heartbeatReported", false)
        }
    }

    /**
     * Evaluate one pass. Returns the outcome and the NEW
     * signature (the caller persists it as the next pass's
     * [lastSignature]).
     *
     * Order of needs is stable and deliberate: parked
     * first (a human step is blocking work), then
     * unreported finishes, then due checklist items —
     * each group in input order.
     */
    fun evaluate(
        checklistText: String?,
        tasks: List<JSONObject>,
        lastSignature: String,
        nowMs: Long
    ): Pair<Outcome, String> {
        val newSignature = signature(checklistText, tasks)
        val parsed = items(checklistText)
        val needs = mutableListOf<Need>()

        for (task in tasks) {
            if (task.optString("state") == TaskStates.PARKED_AT_WALL) {
                needs.add(
                    Need(
                        "parked",
                        "One job is waiting on a step from you: " + gist(task)
                    )
                )
            }
        }
        for (task in tasks) {
            if (isUnreported(task)) {
                needs.add(
                    Need(
                        "unreported",
                        "Finished work to report: " + gist(task)
                    )
                )
            }
        }
        for (item in parsed) {
            val due = item.dueMs
            if (due != null && due <= nowMs) {
                needs.add(Need("due", item.text))
            }
        }

        val outcome: Outcome = when {
            needs.isNotEmpty() -> Outcome.Surface(needs)
            parsed.isEmpty() && newSignature == lastSignature -> Outcome.Skip
            else -> Outcome.Silent
        }
        return outcome to newSignature
    }

    /** True when the pass writes nothing and calls no
     *  model: both [Outcome.Skip] and [Outcome.Silent] are
     *  quiet. Only a [Outcome.Surface] speaks. */
    fun isQuiet(outcome: Outcome): Boolean = outcome !is Outcome.Surface
}
