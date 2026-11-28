package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.14 pins — THE RETURN BUILD (battery #1 graded
 * 1.0.13 NOT READY: 1 domain held of 12, 8/24 clean
 * completions, six graded failure classes).
 *
 * This file pins the PURE halves of the device-side
 * fixes (the runner wiring is compile-checked by CI;
 * the core halves carry the behavioural battery in
 * tests/test_finish_landing.py):
 *
 * - FINISH DIALECT (fix 3): the battery's exact
 *   schedule-a and format-a replies, emitted as literal
 *   text, parse back into finish calls; narration that
 *   merely mentions finish(...) does not.
 * - PENDING FINISH (fix 1): stamp/read/clear round-trip,
 *   the produced set accumulating from the writers, and
 *   the atStep staleness stamp riding the record.
 * - VERIFY-BY-STATE (fix 2): a claimed output with no
 *   production evidence is flagged; a produced or
 *   observation-evidenced claim passes.
 * - TOOL OUTPUT OUTRANKS NARRATION (fix 6): the
 *   schedule-b shape — a summary date contradicted by
 *   the record's own observations is detected; a
 *   matching date is not.
 * - HER OWN FACTS (fix 4): the canonical sources are on
 *   the state-sheet line, verbatim.
 */
class Lille114Test {

    // ---- fix 3: finish dialect ----

    @Test
    fun dialectScheduleAShape() {
        // The battery's schedule-a reply, verbatim.
        val fin = FinishDialect.parse(
            "finish(\"Workshop time conversion: Lagos " +
                "17:00-18:00 (UTC+1) equals London 16:00-17:00 " +
                "(UTC+0) and New York 11:00-12:00 (UTC-5) on " +
                "28 November 2026.\", [])"
        )
        assertTrue(fin != null)
        assertTrue(fin!!.optString("summary")
            .startsWith("Workshop time conversion"))
        assertEquals(0, fin.optJSONArray("outputs")!!.length())
    }

    @Test
    fun dialectFormatAShape() {
        // The battery's format-a reply, verbatim: the JSON
        // deliverable itself as the first argument.
        val fin = FinishDialect.parse(
            "finish({\"name\": \"Adaeze\", \"chain\": " +
                "\"Solana\", \"amount\": 2.5}, [])"
        )
        assertTrue(fin != null)
        val summary = JSONObject(fin!!.optString("summary"))
        assertEquals("Adaeze", summary.optString("name"))
        assertEquals("Solana", summary.optString("chain"))
    }

    @Test
    fun dialectKwargsAndOutputs() {
        val fin = FinishDialect.parse(
            "finish(summary=\"Done.\", outputs=[\"a.txt\", \"b.txt\"])"
        )
        assertTrue(fin != null)
        assertEquals("Done.", fin!!.optString("summary"))
        assertEquals(2, fin.optJSONArray("outputs")!!.length())
        assertEquals("b.txt",
            fin.optJSONArray("outputs")!!.optString(1))
    }

    @Test
    fun dialectRejectsNarration() {
        assertNull(FinishDialect.parse(
            "I will finish(\"the report\", []) once it is ready."))
        assertNull(FinishDialect.parse(
            "cat > fees.py << 'EOF'\nfinish(x)\nEOF"))
        assertNull(FinishDialect.parse("Just a plain answer."))
    }

    // ---- fix 1: pending finish ----

    @Test
    fun pendingFinishRoundTrip() {
        val task = JSONObject()
        PendingFinish.stamp(task, "All done.", listOf("a.txt"), 4)
        val p = PendingFinish.read(task)
        assertTrue(p != null)
        assertEquals("All done.", p!!.summary)
        assertEquals(listOf("a.txt"), p.outputs)
        assertEquals(4, p.atStep)
        PendingFinish.clear(task)
        assertNull(PendingFinish.read(task))
    }

    @Test
    fun producedSetAccumulatesWithoutDuplicates() {
        val task = JSONObject()
        PendingFinish.addProduced(task, "a.txt")
        PendingFinish.addProduced(task, "b.txt")
        PendingFinish.addProduced(task, "a.txt")
        assertEquals(listOf("a.txt", "b.txt"),
            PendingFinish.produced(task))
    }

    // ---- fix 2: verify-by-state ----

    @Test
    fun unproducedClaimIsFlagged() {
        // The code-b shape: a claim over a file no step
        // of the task ever wrote.
        val bad = FinishChecks.unproducedClaims(
            listOf("fees.py"), emptyList(),
            listOf("Tool result (read_note): <fixture text>"))
        assertEquals(listOf("fees.py"), bad)
    }

    @Test
    fun producedAndEvidencedClaimsPass() {
        assertTrue(FinishChecks.unproducedClaims(
            listOf("a.txt"), listOf("a.txt"), emptyList()
        ).isEmpty())
        assertTrue(FinishChecks.unproducedClaims(
            listOf("note.txt"), emptyList(),
            listOf("Tool result (write_note): wrote note.txt " +
                "(120 chars) — durable in the task sandbox"))
        .isEmpty())
        assertTrue(FinishChecks.unproducedClaims(
            listOf("shot.png"), emptyList(),
            listOf("Tool result (browser_screenshot): " +
                "SCREENSHOT_OK path=shot.png bytes=1234 url=x"))
        .isEmpty())
    }

    // ---- fix 6: tool output outranks narration ----

    @Test
    fun dateContradictionScheduleBShape() {
        val contra = FinishChecks.dateContradiction(
            "That is Friday, 14 January 2027.",
            listOf("Tool result (read_note): due 2026-12-11"))
        assertTrue(contra != null)
        assertTrue(contra!!.contains("2026-12-11"))
    }

    @Test
    fun matchingOrAbsentDatesDoNotContradict() {
        assertNull(FinishChecks.dateContradiction(
            "Due 2026-12-11 as computed.",
            listOf("Tool result (read_note): due 2026-12-11")))
        assertNull(FinishChecks.dateContradiction(
            "No dates here.",
            listOf("Tool result (read_note): due 2026-12-11")))
        assertNull(FinishChecks.dateContradiction(
            "Friday, 14 January 2027.",
            listOf("Tool result (read_note): no dates either")))
    }

    // ---- fix 4: her own facts ----

    @Test
    fun selfFactsCarryTheCanonicalSources() {
        val line = SelfFacts.sheetLine()
        assertTrue(line.contains(
            "https://raw.githubusercontent.com/Townsvilledh/" +
                "free-agent/apk/version.json"))
        assertTrue(line.contains(
            "https://fluidwallet.dpdns.org/names.json"))
        assertTrue(line.contains(
            "https://github.com/Townsvilledh/free-agent"))
        assertFalse(line.contains("lille.dev"))
        assertFalse(line.contains("fluidnames"))
        val prompt = SelfFacts.promptLine()
        assertTrue(prompt.contains(SelfFacts.APK_VERSION_URL))
    }

    @Test
    fun observationsOfReadsToolResultsOnly() {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "user")
            .put("content", "the task text"))
        messages.put(JSONObject().put("role", "user")
            .put("content", "Tool result (read_note): x"))
        assertEquals(1,
            FinishChecks.observationsOf(messages).size)
    }
}
