package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packaged "Connect my X account" task (v0.10.1): its
 * report grades CONNECTED from recorded evidence alone —
 * the xHandle the runner's x_session_status wrote, and the
 * userHandoffDone flag a user-step park wrote — never from
 * the run's narration.
 */
class XConnectTaskTest {

    private fun task(
        state: String, handle: String = ""
    ): JSONObject {
        val t = JSONObject()
            .put("state", state)
            .put("text", XConnectTask.TASK_TEXT)
            .put("title", XConnectTask.TITLE)
            .put("summary", "")
            .put("note", "")
            .put("steps", 2)
            .put("userHandoff", true)
            .put("xPostMode", "user")
            .put("xPostApproved", false)
        val criteria = JSONArray()
        XConnectTask.CRITERIA.forEach { criteria.put(it) }
        t.put("criteria", criteria)
        if (handle.isNotEmpty()) t.put("xHandle", handle)
        return t
    }

    @Test
    fun connectedRequiresAVerifiedHandle() {
        val report = XConnectTask.reportFor(
            task(TaskStates.COMPLETED, "someuser")
        )
        assertTrue(report.contains("CONNECTED"))
        assertTrue(report.contains("Connected as @someuser"))
    }

    @Test
    fun completedWithoutAHandleIsNotConnected() {
        val report = XConnectTask.reportFor(task(TaskStates.COMPLETED))
        assertTrue(report.contains("NOT CONNECTED"))
        assertFalse(report.contains("STATUS: CONNECTED"))
    }

    @Test
    fun parkedForTheUsersOwnStepSaysNeedsYou() {
        val t = task(TaskStates.PARKED_AT_WALL)
            .put("wallKind", UserHandoff.WALL_KIND)
            .put("wallDomain", "x.com")
            .put("wallStep", "Log in to X in Lille's browser")
        val report = XConnectTask.reportFor(t)
        assertTrue(report.contains("NEEDS YOU"))
        assertTrue(report.contains("waiting in her browser"))
        assertTrue(report.contains("Log in to X in Lille's browser"))
    }

    @Test
    fun handoffEvidenceGradesTheUsersOwnSignIn() {
        val t = task(TaskStates.COMPLETED, "someuser")
            .put("userHandoffDone", true)
        val report = XConnectTask.reportFor(t)
        assertTrue(report.contains("[x] The sign-in was the user's own"))
    }

    @Test
    fun taskTextCarriesTheHandoffAndThePasswordRule() {
        assertTrue(XConnectTask.TASK_TEXT.contains("browser_handoff"))
        assertTrue(XConnectTask.TASK_TEXT.contains("never"))
        assertTrue(XConnectTask.TASK_TEXT.contains("Connected as @"))
        assertTrue(XConnectTask.DOMAINS.contains("x.com"))
        // A browser task: must not trip the code-task detector.
        assertFalse(TaskRunner.looksLikeCodeTask(XConnectTask.TASK_TEXT))
    }

    @Test
    fun plainCopyPromisesSheNeverSeesThePassword() {
        assertTrue(XConnectTask.PLAIN_COPY.contains("never sees your password"))
        assertTrue(XConnectTask.PLAIN_COPY.contains("you log in yourself"))
    }
}
