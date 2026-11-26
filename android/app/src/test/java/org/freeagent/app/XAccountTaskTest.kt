package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packaged X task's graded report (v0.9): PASS is decided by
 * recorded EVIDENCE (verified handle + first-post URL in the
 * record), never by the run's narration — the same
 * verify-by-state rule as the core's spec grader (W23/W8).
 */
class XAccountTaskTest {

    private fun task(
        state: String, handle: String = "", postUrl: String = ""
    ): JSONObject {
        val t = JSONObject()
            .put("state", state)
            .put("text", XAccountTask.TASK_TEXT)
            .put("title", XAccountTask.TITLE)
            .put("summary", "")
            .put("note", "")
            .put("steps", 3)
        val criteria = JSONArray()
        XAccountTask.CRITERIA.forEach { criteria.put(it) }
        t.put("criteria", criteria)
        if (handle.isNotEmpty()) t.put("xHandle", handle)
        if (postUrl.isNotEmpty()) t.put("firstPostUrl", postUrl)
        return t
    }

    @Test
    fun passRequiresEvidence() {
        val report = XAccountTask.reportFor(
            task(TaskStates.COMPLETED, "LilleAgent",
                "https://x.com/LilleAgent/status/123")
        )
        assertTrue(report.contains("STATUS: PASS"))
        assertTrue(report.contains("@LilleAgent"))
        assertTrue(report.contains("https://x.com/LilleAgent/status/123"))
    }

    @Test
    fun completedWithoutEvidenceIsNotAPass() {
        val report = XAccountTask.reportFor(task(TaskStates.COMPLETED))
        assertFalse(report.contains("STATUS: PASS"))
        assertTrue(report.contains("NOT graded PASS"))
    }

    @Test
    fun parkedReportNamesTheWallAndTheStep() {
        val t = task(TaskStates.PARKED_AT_WALL)
            .put("wallKind", "captcha")
            .put("wallDomain", "x.com")
            .put("wallDetail", "human-verification iframe: arkose")
            .put("wallStep", "Complete the human verification on x.com")
        val report = XAccountTask.reportFor(t)
        assertTrue(report.contains("NEEDS ETHURAM"))
        assertTrue(report.contains("not passed, not failed"))
        assertTrue(report.contains("Complete the human verification"))
    }

    @Test
    fun identityKitIsEmbedded() {
        assertTrue(XAccountTask.TASK_TEXT.contains("LilleAgent"))
        assertTrue(XAccountTask.TASK_TEXT.contains("asset:lille-mascot-jacket.webp"))
        assertTrue(XAccountTask.TASK_TEXT.contains("vault_gen_secret"))
        assertTrue(XAccountTask.TASK_TEXT.contains("₿itcoin or nothing"))
        assertTrue(XAccountTask.DOMAINS.contains("x.com"))
        // The packaged text must not trip the runner's code-task
        // detector (it is a browser task, run on-device).
        assertFalse(TaskRunner.looksLikeCodeTask(XAccountTask.TASK_TEXT))
    }
}
