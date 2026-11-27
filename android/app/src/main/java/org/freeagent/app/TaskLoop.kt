package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE LOOP DISCIPLINE LAYER (v1.0.4) — three mechanisms
 * stolen as PATTERNS from the open-source agent field
 * (research: live-tests/../research steal list, §3) and
 * reimplemented for Lille's runner:
 *
 * - PER-ACTION REFLECTION (steal #3, Mobile-Agent): after
 *   each step, the outcome is classified deterministically
 *   — worked / wrong target / no change / unexpected —
 *   from the observation's shape and, for browser steps,
 *   from whether the page actually moved. The model's own
 *   sense of how a step went is never the record.
 * - STUCK DETECTION (steal #14, OpenHands): the same
 *   action+observation repeating, or a run of bad
 *   reflections, declares the run stuck — first with a
 *   forced strategy change, then with an honest park.
 *   One mechanism consumes both signals (the brief's
 *   unification rule): reflection streaks and repetition
 *   feed the SAME tracker.
 * - THE EVENT LOG (steal #14): the task record carries an
 *   append-only events array — run starts, steps, steers,
 *   plan notes, checkpoints, condensations, finishes —
 *   the audit trail the graded report and the condenser
 *   both read. When the message context outgrows its
 *   bound, old tool exchanges CONDENSE into a tombstone
 *   message that says what was folded — never a silent
 *   truncation.
 *
 * Pure + org.json; pinned by TaskLoopTest.
 */
object StepReflection {

    enum class Outcome { WORKED, WRONG_TARGET, NO_CHANGE, UNEXPECTED }

    /** The record field the classifications accumulate in. */
    const val FIELD = "reflections"

    /** How many classifications the record keeps (the tail
     *  is the evidence that matters; the event log keeps
     *  the full count). */
    const val MAX_KEPT = 60

    /**
     * Classify one step's outcome. Deterministic first:
     * the observation's own shape decides wherever it can.
     * [pageSigBefore]/[pageSigAfter] are the caller's page
     * signatures (url|title|text-shape) around a browser
     * step; equality after an action that should move the
     * page is a NO_CHANGE, whatever the observation claims.
     */
    fun classify(
        tool: String, observation: String,
        pageSigBefore: String? = null, pageSigAfter: String? = null
    ): Outcome {
        if (observation.startsWith("CHALLENGE")) {
            // Finding the wall IS the step working — the
            // wall machinery owns what happens next, and
            // the reflection must not grade it a failure.
            return Outcome.WORKED
        }
        if (observation.startsWith("error:")) {
            val low = observation.lowercase()
            return when {
                "allowlist" in low || "domain" in low ||
                    "secret_field" in low || "not_a_secret_field" in low ||
                    "unknown tool" in low || "plan mode" in low ->
                    Outcome.WRONG_TARGET
                "no such note" in low || "not found" in low ||
                    "needs a" in low || "needs an" in low ->
                    Outcome.NO_CHANGE
                else -> Outcome.UNEXPECTED
            }
        }
        if (tool.startsWith("browser_") &&
            pageSigBefore != null && pageSigBefore.isNotEmpty() &&
            pageSigBefore == pageSigAfter &&
            tool in setOf(
                "browser_click", "browser_click_mark",
                "browser_type", "browser_open"
            )
        ) {
            return Outcome.NO_CHANGE
        }
        return Outcome.WORKED
    }

    /** A page's signature for before/after comparison:
     *  where it is + what it is titled + the shape of its
     *  text and element tree. Two reads of the same page
     *  in the same state sign identically. */
    fun pageSignature(state: BrowserPerception.PageState): String =
        state.url + "|" + state.title + "|" +
            state.text.length + "|" + state.elements.size

    /** Record one classification on the task (the update
     *  lambda's receiver). Bounded; each entry carries the
     *  step number so the transparency layer can align it
     *  with the step list. */
    fun record(task: JSONObject, step: Int, tool: String, outcome: Outcome) {
        val arr = task.optJSONArray(FIELD) ?: JSONArray()
        arr.put(
            JSONObject()
                .put("step", step)
                .put("tool", tool)
                .put("outcome", outcome.name)
        )
        while (arr.length() > MAX_KEPT) arr.remove(0)
        task.put(FIELD, arr)
    }

    /** Bad = the step did not move the work (wrong target
     *  or no change). UNEXPECTED is information, not a
     *  stall signal on its own. */
    fun isBad(outcome: Outcome): Boolean =
        outcome == Outcome.WRONG_TARGET || outcome == Outcome.NO_CHANGE
}

/**
 * One stall mechanism, two feeds (v1.0.4): consecutive bad
 * reflections AND repeated action+observation signatures
 * both count toward the same verdict. First trigger: the
 * run is told to CHANGE STRATEGY (a recorded instruction,
 * counters reset). Second trigger: PARK — grinding is
 * over; the user hears a named reason, in work terms.
 *
 * In-run state (a resumed run re-earns its verdicts; the
 * record keeps the reflections and events as evidence).
 */
class StallTracker {

    enum class Verdict { OK, CHANGE_STRATEGY, PARK }

    private var consecutiveBad = 0
    private var lastSignature: String = ""
    private var signatureRepeats = 0
    private var strategyChanges = 0

    /** A stable signature for one step: the tool, its
     *  canonical args, and the observation's head. */
    fun signature(tool: String, args: JSONObject, observation: String): String {
        val keys = mutableListOf<String>()
        val it = args.keys()
        while (it.hasNext()) keys.add(it.next())
        keys.sort()
        val canon = keys.joinToString(",") { k -> "$k=${args.opt(k)}" }
        return tool + "|" + canon + "|" + observation.take(120)
    }

    fun record(sig: String, outcome: StepReflection.Outcome): Verdict {
        if (StepReflection.isBad(outcome)) {
            consecutiveBad += 1
        } else {
            consecutiveBad = 0
        }
        if (sig == lastSignature) {
            signatureRepeats += 1
        } else {
            lastSignature = sig
            signatureRepeats = 1
        }
        val stuck = consecutiveBad >= 3 || signatureRepeats >= 3
        if (!stuck) return Verdict.OK
        consecutiveBad = 0
        signatureRepeats = 0
        strategyChanges += 1
        return if (strategyChanges >= 2) Verdict.PARK else Verdict.CHANGE_STRATEGY
    }

    /** The work-terms reason a park names. */
    fun parkReason(): String =
        "She kept hitting the same wall — the last steps were " +
            "not changing anything, so the work is parked instead " +
            "of grinding. Say the word here and she picks it " +
            "back up."

    /** The instruction injected when strategy must change. */
    fun strategyNote(): String =
        "Your last steps are repeating without changing anything. " +
            "STOP and change strategy: re-read the current state, " +
            "name what is actually blocking you, and take a " +
            "materially different next step — a different page, a " +
            "different tool, or a different decomposition of the job."
}

/**
 * The append-only event log on the task record (v1.0.4).
 * Events are evidence: kind + timestamp + a small data
 * object. Bounded — when the log outgrows [MAX_EVENTS]
 * the oldest middle folds into one "condensed" event
 * (the same tombstone discipline as the message
 * condenser; nothing vanishes unmarked).
 */
object EventLog {

    const val FIELD = "events"
    const val MAX_EVENTS = 400

    fun append(task: JSONObject, kind: String, at: Long, data: JSONObject = JSONObject()) {
        val arr = task.optJSONArray(FIELD) ?: JSONArray()
        arr.put(JSONObject().put("at", at).put("kind", kind).put("data", data))
        if (arr.length() > MAX_EVENTS) {
            val keepTail = MAX_EVENTS / 4
            val folded = arr.length() - keepTail - 1
            val next = JSONArray()
            next.put(
                JSONObject().put("at", at).put("kind", "condensed")
                    .put("data", JSONObject().put("folded", folded))
            )
            for (i in arr.length() - keepTail until arr.length()) {
                next.put(arr.getJSONObject(i))
            }
            task.put(FIELD, next)
        } else {
            task.put(FIELD, arr)
        }
    }

    fun kinds(task: JSONObject): List<String> {
        val arr = task.optJSONArray(FIELD) ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it).optString("kind") }
    }

    /**
     * Condense a run's message context: when the exchange
     * outgrows [keepTail] tool turns beyond the system +
     * task head, the oldest middle folds into ONE tombstone
     * message stating how many steps were folded and their
     * outcome counts — the model keeps the thread's shape,
     * the record keeps the truth. Returns the new messages
     * and the number folded (0 = untouched).
     */
    fun condenseMessages(
        messages: JSONArray, keepTail: Int = 12
    ): Pair<JSONArray, Int> {
        // Head = system + the original task message; both
        // are load-bearing and never fold.
        val head = 2
        if (messages.length() <= head + keepTail) return messages to 0
        val foldEnd = messages.length() - keepTail
        var foldedSteps = 0
        var foldedOk = 0
        var foldedFailed = 0
        for (i in head until foldEnd) {
            val content = messages.getJSONObject(i).optString("content")
            if (content.startsWith("Tool result (")) {
                foldedSteps += 1
                if (content.contains("): error:")) foldedFailed += 1 else foldedOk += 1
            }
        }
        val out = JSONArray()
        for (i in 0 until head) out.put(messages.getJSONObject(i))
        out.put(
            JSONObject().put("role", "user").put(
                "content",
                "[Earlier steps condensed: $foldedSteps tool steps were " +
                    "folded out of this context to keep the run going — " +
                    "$foldedOk worked, $foldedFailed did not. The full " +
                    "record is in the task's event log; work from the " +
                    "current state, and re-check anything you are " +
                    "unsure was done.]"
            )
        )
        for (i in foldEnd until messages.length()) {
            out.put(messages.getJSONObject(i))
        }
        return out to foldedSteps
    }
}
