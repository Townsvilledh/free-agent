package org.freeagent.app

import org.json.JSONObject
import java.util.UUID

/**
 * One on-device proactive pass (v0.7) — the Proactivity Law in
 * machinery, mirroring the Python core's proactive.py:
 *
 * 1. DUE CHECKS — open commitments whose nextCheckAt has arrived
 *    get their landing condition checked (on-device check kinds:
 *    url: and url-contains: — the core supports more). Landed ->
 *    state landed + a "while you were away" report. Past deadline
 *    unlanded -> expired + a BLOCKED report: a missed result is
 *    surfaced, never silently dropped.
 * 2. QUEUED-TASK PICKUP — tasks sitting in QUEUED are returned to
 *    the caller (the worker enqueues them with TaskWorker) and
 *    remembered; a later pass settles each pickup when the task
 *    reaches a terminal state, writing its DONE/BLOCKED report.
 *
 * A report is written only in the same moment a persisted state
 * transition happens, so re-running a pass never duplicates one.
 * Pure logic over injected data/fetch/clock — JVM-unit-testable.
 */
/** The heartbeat's data door (v1.0.4): the checklist text
 *  + the last pass's signature. Separate from
 *  [ProactiveData] so existing pass constructions (and
 *  their test fakes) are untouched — a pass without a
 *  heartbeat door simply has no heartbeat phase. */
interface HeartbeatData {
    fun heartbeatText(): String?
    fun heartbeatSignature(): String
    fun saveHeartbeatSignature(signature: String)
}

class ProactivePass(
    private val data: ProactiveData,
    private val tasks: () -> List<JSONObject>,
    private val fetch: (String) -> Pair<Int, String>,
    private val now: () -> Long,
    private val heartbeat: HeartbeatData? = null
) {
    data class Summary(
        var checked: Int = 0,
        val landed: MutableList<String> = mutableListOf(),
        val expired: MutableList<String> = mutableListOf(),
        var reportsWritten: Int = 0,
        val pickupTaskIds: MutableList<String> = mutableListOf(),
        var heartbeatNeeds: Int = 0
    )

    fun run(): Summary {
        val summary = Summary()
        val t = now()
        runDueChecks(t, summary)
        runHeartbeat(t, summary)
        settlePickups(t, summary)
        pickUpQueued(summary)
        return summary
    }

    /** THE HEARTBEAT (v1.0.4, steal #1 — OpenClaw): a light
     *  pass over the HEARTBEAT.md checklist + standing task
     *  states, SEPARATE from the cron lane's isolated runs.
     *  Discipline: skipped entirely — no evaluation, no
     *  report — when the checklist is empty and nothing
     *  changed; silent (HEARTBEAT_OK) when it looked and
     *  nothing needs the user; ONE report when something
     *  does (a parked job, unreported finished work, a due
     *  checklist item). Needs are never swallowed by the
     *  skip: a changed world re-opens the pass. */
    private fun runHeartbeat(t: Long, summary: Summary) {
        val hb = heartbeat ?: return
        val (outcome, newSignature) = Heartbeat.evaluate(
            hb.heartbeatText(), tasks(), hb.heartbeatSignature(), t
        )
        when (outcome) {
            Heartbeat.Outcome.Skip -> Unit
            Heartbeat.Outcome.Silent ->
                hb.saveHeartbeatSignature(newSignature)
            is Heartbeat.Outcome.Surface -> {
                hb.saveHeartbeatSignature(newSignature)
                writeReport(
                    "NEEDS YOU", "While you were away",
                    outcome.needs.joinToString("\n") { "• ${it.text}" },
                    t
                )
                summary.reportsWritten++
                summary.heartbeatNeeds = outcome.needs.size
            }
        }
    }

    private fun writeReport(
        status: String, title: String, body: String, at: Long
    ): JSONObject {
        val report = JSONObject()
            .put("id", "rpt-" + UUID.randomUUID().toString().substring(0, 8))
            .put("status", status)
            .put("title", title)
            .put("body", body)
            .put("at", at)
        val reports = data.reports()
        reports.add(report)
        data.saveReports(reports)
        return report
    }

    /** Returns landed=true/false, or null when the check errored
     *  (network down etc. — stays open, retried next interval). */
    private fun runCheck(check: String): Boolean? {
        return try {
            when {
                check.startsWith("url-contains:") -> {
                    val rest = check.removePrefix("url-contains:")
                    val idx = rest.indexOf("::")
                    if (idx < 0) return null
                    val (status, body) = fetch(rest.substring(0, idx))
                    status == 200 && body.contains(rest.substring(idx + 2))
                }
                check.startsWith("url:") -> {
                    val (status, _) = fetch(check.removePrefix("url:"))
                    status == 200
                }
                else -> null // kind not checkable on-device; core-only
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun runDueChecks(t: Long, summary: Summary) {
        val cmts = data.commitments()
        var dirty = false
        for (cmt in cmts) {
            if (cmt.optString("state") != "open") continue
            val due = cmt.optLong("nextCheckAt") <= t
            val deadline = cmt.optLong("deadline")
            val deadlineHit = deadline > 0 && deadline <= t
            if (!due && !deadlineHit) continue
            val check = cmt.optString("check")
            val landed = if (check.isNotEmpty()) runCheck(check) else null
            if (landed != null) {
                cmt.put("checkCount", cmt.optInt("checkCount") + 1)
                summary.checked++
            }
            when {
                landed == true -> {
                    cmt.put("state", "landed")
                    writeReport(
                        "LANDED", cmt.optString("title"),
                        "Lille checked while you were away and the " +
                            "result has landed.\nCheck: $check\n" +
                            "Checks executed: ${cmt.optInt("checkCount")}",
                        t
                    )
                    summary.landed.add(cmt.optString("id"))
                    summary.reportsWritten++
                }
                deadlineHit -> {
                    cmt.put("state", "expired")
                    writeReport(
                        "BLOCKED", cmt.optString("title"),
                        "The deadline passed and the watched result " +
                            "still has not landed. Lille is surfacing " +
                            "this instead of letting it sit silently.",
                        t
                    )
                    summary.expired.add(cmt.optString("id"))
                    summary.reportsWritten++
                }
                landed == false -> {
                    cmt.put(
                        "nextCheckAt",
                        t + cmt.optLong("everySeconds", 1800) * 1000
                    )
                }
                else -> {
                    // check errored or not device-checkable: retry
                    // at the next interval without counting a check
                    cmt.put(
                        "nextCheckAt",
                        t + cmt.optLong("everySeconds", 1800) * 1000
                    )
                }
            }
            dirty = true
        }
        if (dirty) data.saveCommitments(cmts)
    }

    private fun settlePickups(t: Long, summary: Summary) {
        val pickups = data.pickups()
        if (pickups.isEmpty()) return
        val byId = tasks().associateBy { it.optString("id") }
        val settled = mutableListOf<String>()
        for ((taskId, title) in pickups) {
            val task = byId[taskId] ?: continue
            val state = task.optString("state")
            if (state != TaskStates.COMPLETED && state != TaskStates.FAILED) {
                continue
            }
            if (ChatTaskLink.isChatTask(task)) {
                // v0.13: a chat-born task's report channel is
                // its chat (ChatTaskReporter, at the worker's
                // settle). Settling it here too would report
                // the same finish twice — retire the pickup
                // silently instead.
                settled.add(taskId)
                continue
            }
            val done = state == TaskStates.COMPLETED
            val body = buildString {
                append("Task $taskId finished while you were away.\n")
                append("Final state: $state\n")
                val s = task.optString("summary")
                if (s.isNotEmpty()) append("Summary: $s\n")
                // Seamless law: a stored note is echoed only
                // when clean — route narration never reaches
                // the debrief.
                val n = NarrationGuard.cleanOrNull(task.optString("note"))
                if (n != null) append("Note: $n")
            }
            writeReport(if (done) "DONE" else "BLOCKED", title, body, t)
            summary.reportsWritten++
            settled.add(taskId)
        }
        if (settled.isNotEmpty()) {
            settled.forEach { pickups.remove(it) }
            data.savePickups(pickups)
        }
    }

    private fun pickUpQueued(summary: Summary) {
        val pickups = data.pickups()
        var dirty = false
        for (task in tasks()) {
            val id = task.optString("id")
            if (task.optString("state") == TaskStates.QUEUED &&
                !pickups.containsKey(id)
            ) {
                pickups[id] = task.optString("text")
                summary.pickupTaskIds.add(id)
                dirty = true
            }
        }
        if (dirty) data.savePickups(pickups)
    }
}
