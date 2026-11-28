package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * PENDING FINISH (v1.0.14) — a finish is a state write,
 * not a model call.
 *
 * The 1.0.13 battery's dominant failure (13 of 24 tasks):
 * the work was done and she had said so in plain text,
 * then the route dropped before a finish CALL could
 * land, and the record parked in waiting_route with
 * finished work inside. The core loop had the same
 * shape; this is the device mirror (free_agent.loop's
 * pending finish).
 *
 * The mechanism: when a run's model replies in plain
 * text AFTER real tool work, that reply is recorded on
 * the task record as a pending finish — her summary in
 * her own words, outputs the files this task's steps
 * actually produced (stamped by the writers themselves:
 * write_note, browser_screenshot). When the run would
 * settle into a waiting state, or when a later run
 * resumes the record, the pending finish is APPLIED
 * deterministically — outputs re-verified on disk,
 * grounding checked (FinishChecks) — and the record
 * closes COMPLETED with no model call spent re-deciding
 * what she already decided.
 *
 * Pure + org.json record shaping; the runner performs
 * the file checks and the state write. Pinned by
 * Lille114Test.
 */
object PendingFinish {

    const val FIELD = "pendingFinish"
    const val PRODUCED_FIELD = "producedOutputs"
    const val APPLIED_NOTE =
        "Finished from her own completion statement when " +
            "the route dropped — the finish is a state " +
            "write, not a model call (v1.0.14)."

    /** Record a file this task's steps produced. Writers
     *  call this at the moment of the write, so the set
     *  is evidence, not recollection. */
    fun addProduced(task: JSONObject, rel: String) {
        if (rel.isEmpty()) return
        val arr = task.optJSONArray(PRODUCED_FIELD) ?: JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == rel) return
        }
        arr.put(rel)
        task.put(PRODUCED_FIELD, arr)
    }

    /** The files this task's steps have produced, in
     *  first-production order. */
    fun produced(task: JSONObject): List<String> {
        val arr = task.optJSONArray(PRODUCED_FIELD) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val rel = arr.optString(i)
            if (rel.isNotEmpty() && rel !in out) out.add(rel)
        }
        return out
    }

    /** A pending finish: her summary, the outputs it
     *  claims, and the record's step count when she
     *  stated it. The step count is the staleness guard:
     *  if the task did MORE tool work after the statement
     *  (steps advanced past [atStep]), the statement no
     *  longer describes the work and must not be applied. */
    data class Pending(
        val summary: String,
        val outputs: List<String>,
        val atStep: Int
    )

    /** Stamp her plain-text completion statement as the
     *  record's pending finish. */
    fun stamp(
        task: JSONObject, summary: String,
        outputs: List<String>, atStep: Int
    ) {
        val arr = JSONArray()
        outputs.forEach { arr.put(it) }
        task.put(FIELD, JSONObject()
            .put("summary", summary)
            .put("outputs", arr)
            .put("atStep", atStep))
    }

    /** The pending finish, or null. */
    fun read(task: JSONObject): Pending? {
        val pf = task.optJSONObject(FIELD) ?: return null
        val summary = pf.optString("summary")
        if (summary.isEmpty()) return null
        val arr = pf.optJSONArray("outputs")
        val outputs = mutableListOf<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val rel = arr.optString(i)
                if (rel.isNotEmpty()) outputs.add(rel)
            }
        }
        return Pending(summary, outputs, pf.optInt("atStep", 0))
    }

    fun clear(task: JSONObject) {
        task.remove(FIELD)
    }
}
