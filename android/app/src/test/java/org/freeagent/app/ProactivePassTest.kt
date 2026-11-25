package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the on-device proactive pass (v0.7). */
class ProactivePassTest {

    private class MemData : ProactiveData {
        val cmts = mutableListOf<JSONObject>()
        val rpts = mutableListOf<JSONObject>()
        val pcks = mutableMapOf<String, String>()
        // Like the real file-backed store, reads return a fresh list
        // and saves copy the input first — the pass hands back the
        // very list it read, and clear()+addAll(sameList) would
        // erase it (that aliasing bug failed this suite in CI).
        override fun commitments() = cmts.toMutableList()
        override fun saveCommitments(list: List<JSONObject>) {
            val copy = list.toList()
            cmts.clear(); cmts.addAll(copy)
        }
        override fun reports() = rpts.toMutableList()
        override fun saveReports(list: List<JSONObject>) {
            val copy = list.toList()
            rpts.clear(); rpts.addAll(copy)
        }
        override fun pickups() = pcks.toMutableMap()
        override fun savePickups(map: Map<String, String>) {
            val copy = map.toMap()
            pcks.clear(); pcks.putAll(copy)
        }
    }

    private var now = 1_700_000_000_000L

    private fun watch(check: String, everySeconds: Long = 60): JSONObject =
        JSONObject()
            .put("id", "cmt-1").put("kind", "watch").put("title", "result")
            .put("check", check).put("everySeconds", everySeconds)
            .put("nextCheckAt", now).put("deadline", 0L)
            .put("state", "open").put("checkCount", 0)

    @Test
    fun dueUrlCheckLandsAndReportsExactlyOnce() {
        val data = MemData()
        data.cmts.add(watch("url-contains:https://x.test/r::Winner"))
        val pass = ProactivePass(
            data, tasks = { emptyList() },
            fetch = { 200 to "the Winner is you" }, now = { now }
        )
        val s1 = pass.run()
        assertEquals(1, s1.checked)
        assertEquals(listOf("cmt-1"), s1.landed)
        assertEquals(1, data.rpts.size)
        assertEquals("LANDED", data.rpts[0].optString("status"))
        // Second pass: landed commitments are never re-checked
        // or re-reported.
        val s2 = pass.run()
        assertEquals(0, s2.checked)
        assertEquals(1, data.rpts.size)
    }

    @Test
    fun notYetLandedPushesNextCheckOut() {
        val data = MemData()
        data.cmts.add(watch("url:https://x.test/pending", everySeconds = 60))
        val pass = ProactivePass(
            data, tasks = { emptyList() },
            fetch = { 404 to "not yet" }, now = { now }
        )
        val s1 = pass.run()
        assertEquals(1, s1.checked)
        assertTrue(s1.landed.isEmpty())
        assertEquals(0, data.rpts.size)
        assertEquals(now + 60_000L, data.cmts[0].optLong("nextCheckAt"))
        // Not due again at the same instant.
        assertEquals(0, pass.run().checked)
    }

    @Test
    fun deadlinePassingUnlandedReportsBlocked() {
        val data = MemData()
        val cmt = watch("url:https://x.test/never")
        cmt.put("deadline", now - 1)
        data.cmts.add(cmt)
        val pass = ProactivePass(
            data, tasks = { emptyList() },
            fetch = { 404 to "" }, now = { now }
        )
        val s = pass.run()
        assertEquals(listOf("cmt-1"), s.expired)
        assertEquals("BLOCKED", data.rpts[0].optString("status"))
        assertEquals("expired", data.cmts[0].optString("state"))
    }

    @Test
    fun queuedTaskPickedUpOnceAndSettledWhenDone() {
        val data = MemData()
        var taskState = TaskStates.QUEUED
        val task = JSONObject()
            .put("id", "task-9").put("text", "do the thing")
            .put("summary", "").put("note", "")
        val pass = ProactivePass(
            data,
            tasks = {
                task.put("state", taskState)
                listOf(task)
            },
            fetch = { 200 to "" }, now = { now }
        )
        val s1 = pass.run()
        assertEquals(listOf("task-9"), s1.pickupTaskIds)
        // Still queued (worker has not started it): not picked twice.
        assertTrue(pass.run().pickupTaskIds.isEmpty())
        // It finishes in the background; the next pass settles it
        // into the "while you were away" reports.
        taskState = TaskStates.COMPLETED
        task.put("summary", "the thing is done")
        val s3 = pass.run()
        assertEquals(1, s3.reportsWritten)
        assertEquals("DONE", data.rpts[0].optString("status"))
        assertTrue(data.rpts[0].optString("body").contains("the thing is done"))
        // Settled pickups are forgotten: no duplicate report.
        assertEquals(0, pass.run().reportsWritten)
    }

    @Test
    fun fetchErrorLeavesCommitmentOpen() {
        val data = MemData()
        data.cmts.add(watch("url:https://x.test/down"))
        val pass = ProactivePass(
            data, tasks = { emptyList() },
            fetch = { throw RuntimeException("network down") },
            now = { now }
        )
        val s = pass.run()
        assertEquals(0, s.checked) // an errored check is not a check
        assertEquals("open", data.cmts[0].optString("state"))
        assertEquals(0, data.rpts.size)
    }
}
