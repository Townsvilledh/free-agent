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
 *
 * v0.10.1: the identity kit is injected at build time, never
 * in source — so these tests run on a FIXTURE kit (test-double
 * values, deliberately unlike Lille's real kit) injected the
 * same way, through XIdentityKit.fromRaw.
 */
class XAccountTaskTest {

    companion object {
        private val FIXTURE_KIT = XIdentityKit.fromRaw(
            "FixtureOne, FixtureTwo",
            "Fixture bio — a test double, not the real bio.",
            "1 January 1990",
            "fixture-mail.example, fixture-mail-2.example"
        )
    }

    private fun task(
        state: String, handle: String = "", postUrl: String = ""
    ): JSONObject {
        val t = JSONObject()
            .put("state", state)
            .put("text", XAccountTask.taskText(FIXTURE_KIT))
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
            task(TaskStates.COMPLETED, "FixtureOne",
                "https://x.com/FixtureOne/status/123")
        )
        assertTrue(report.contains("STATUS: PASS"))
        assertTrue(report.contains("@FixtureOne"))
        assertTrue(report.contains("https://x.com/FixtureOne/status/123"))
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
    fun taskTextCarriesTheInjectedKit() {
        val text = XAccountTask.taskText(FIXTURE_KIT)
        assertTrue(text.contains("FixtureOne"))
        assertTrue(text.contains("FixtureTwo"))
        assertTrue(text.contains("Fixture bio"))
        assertTrue(text.contains("1 January 1990"))
        assertTrue(text.contains("fixture-mail.example"))
        assertTrue(text.contains("asset:lille-mascot-jacket.webp"))
        assertTrue(text.contains("vault_gen_secret"))
        assertTrue(text.contains("₿itcoin or nothing"))
        val domains = XAccountTask.domains(FIXTURE_KIT)
        assertTrue(domains.contains("x.com"))
        assertTrue(domains.contains("fixture-mail.example"))
        assertTrue(domains.contains("fixture-mail-2.example"))
        // The packaged text must not trip the runner's code-task
        // detector (it is a browser task, run on-device).
        assertFalse(TaskRunner.looksLikeCodeTask(text))
    }

    @Test
    fun hollowBuildRecordReportsUnavailable() {
        val t = task(TaskStates.FAILED)
            .put("note", XAccountTask.UNAVAILABLE_NOTE)
            .put("summary", XAccountTask.UNAVAILABLE_NOTE)
        assertTrue(XAccountTask.isUnavailableRecord(t))
        val report = XAccountTask.reportFor(t)
        assertTrue(report.contains("UNAVAILABLE"))
        assertTrue(report.contains("Owner configuration missing"))
        assertFalse(XAccountTask.isUnavailableRecord(task(TaskStates.COMPLETED)))
    }
}
