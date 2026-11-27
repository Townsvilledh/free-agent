package org.freeagent.app

import org.json.JSONObject

/**
 * THE LOOP BREAKER (v1.0.8) — for the packaged browser
 * lanes (the general lanes have the stall tracker; the
 * packaged lanes had nothing, and the X creation lane
 * circled mailbox providers for 37 steps).
 *
 * The guard watches one thing: browser steps that land
 * on the same cluster WITHOUT the work advancing. A
 * streak is keyed (for the creation lane, the phase —
 * provider-hopping inside MAILBOX is one streak,
 * because the phase is the cluster that isn't
 * advancing; for other packaged lanes, the URL
 * cluster) and counted ON THE RECORD, so circling
 * across budget rests and resumes still accumulates —
 * the circle that spanned four runs is one circle.
 * An advancing step (a phase checkpoint stamped, a
 * different kind of progress) clears the streak.
 *
 * On a trip the runner acts, never silently: the
 * creation lane takes its phase script's alternate
 * branch when one exists (next provider, the sanctioned
 * switch), and otherwise the lane PARKS with the loop
 * evidence named — a named stop the user can resume,
 * instead of silent circling that burns the day.
 *
 * Pure over the record; pinned by Lille108Test against
 * the 37-step circle and the 3-step mailbox record.
 */
object LoopGuard {

    const val FIELD = "loopGuard"

    /** Same-cluster steps without an advance. */
    const val TRIP_SAME_KEY = 8

    /** Total no-advance browser steps under any key —
     *  the provider-hopping circle changes keys too
     *  slowly to hide under this. */
    const val TRIP_TOTAL = 12

    data class Verdict(
        val tripped: Boolean,
        val key: String,
        val count: Int,
        val total: Int
    )

    /** The URL cluster of a page: domain + first path
     *  segment ("guerrillamail.com/", "x.com/i") — fine
     *  enough to tell wandering from working. */
    fun clusterFor(url: String): String {
        if (url.isEmpty()) return ""
        // www. is the same ground: guerrillamail.com and
        // www.guerrillamail.com are one cluster, or the
        // breaker counts them as two and the circle the
        // breaker exists to catch slips under it.
        val host = BrowserPerception.domainOf(url)
            .removePrefix("www.")
        val afterScheme = url.substringAfter("://", "")
        val first = afterScheme.substringAfter("/", "")
            .substringBefore("/").substringBefore("?")
            .substringBefore("#")
        return if (first.isEmpty()) "$host/" else "$host/$first"
    }

    private fun state(task: JSONObject): JSONObject =
        task.optJSONObject(FIELD) ?: JSONObject()

    /** The current streak, for surfaces/tests. */
    fun streak(task: JSONObject): Verdict {
        val s = state(task)
        return Verdict(
            false, s.optString("key"),
            s.optInt("count", 0), s.optInt("total", 0)
        )
    }

    /**
     * Record one browser step under [key]. [advanced]
     * — the step moved the work (a checkpoint stamped,
     * a progress tool landed) — clears the streak.
     * Otherwise the streak grows: same key increments
     * the cluster count, a new key starts its count at
     * 1, and the no-advance total always grows. The
     * verdict says whether the breaker has tripped.
     */
    fun noteStep(
        task: JSONObject, key: String, advanced: Boolean
    ): Verdict {
        if (advanced) {
            task.put(
                FIELD,
                JSONObject().put("key", "").put("count", 0)
                    .put("total", 0)
            )
            return Verdict(false, key, 0, 0)
        }
        val s = state(task)
        val count = if (s.optString("key") == key) {
            s.optInt("count", 0) + 1
        } else {
            1
        }
        val total = s.optInt("total", 0) + 1
        task.put(
            FIELD,
            JSONObject().put("key", key).put("count", count)
                .put("total", total)
        )
        return Verdict(
            count >= TRIP_SAME_KEY || total >= TRIP_TOTAL,
            key, count, total
        )
    }

    /** Clear the streak (the alternate branch was taken,
     *  or the lane parked and the user resumed it — a
     *  fresh approach gets a fresh streak). */
    fun reset(task: JSONObject) {
        task.put(
            FIELD,
            JSONObject().put("key", "").put("count", 0)
                .put("total", 0)
        )
    }

    /** Stamp the trip as evidence: the event, and the
     *  fields the park note and the report read. */
    fun stampTrip(task: JSONObject, verdict: Verdict, now: Long) {
        task.put("loopTrippedAt", now)
        task.put("loopCluster", verdict.key)
        task.put("loopCount", verdict.total)
        EventLog.append(
            task, "loop_trip", now,
            JSONObject().put("cluster", verdict.key)
                .put("count", verdict.count)
                .put("total", verdict.total)
        )
    }

    /** The park note for a tripped lane with no
     *  alternate branch: the loop, named, and the way
     *  back — resume is a fresh approach, not a
     *  continuation of the circle. */
    fun parkNote(verdict: Verdict): String =
        "Loop breaker: the lane went over the same " +
            "ground (${verdict.key}) ${verdict.total} " +
            "times without the work advancing, so she " +
            "parked instead of circling. Say “resume” " +
            "and she goes back with a fresh approach."
}
