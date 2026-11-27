package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for the loop steals (v1.0.4): the reflection pass
 * (#3), the ONE stall mechanism (#14's stuck detection
 * unified with #3's feed), and the event log + condenser
 * (#14). Deterministic-first is the point: these verdicts
 * never need a model.
 */
class TaskLoopTest {

    // ---------- reflection ----------

    @Test
    fun reflectionGradesErrorsByShape() {
        assertEquals(
            StepReflection.Outcome.WRONG_TARGET,
            StepReflection.classify(
                "browser_open",
                "error: domain x.com is not in this task's browser allowlist"
            )
        )
        assertEquals(
            StepReflection.Outcome.WRONG_TARGET,
            StepReflection.classify(
                "write_note", TaskTools.PLAN_REFUSAL
            )
        )
        assertEquals(
            StepReflection.Outcome.NO_CHANGE,
            StepReflection.classify("read_note", "error: no such note: a.txt")
        )
        assertEquals(
            StepReflection.Outcome.UNEXPECTED,
            StepReflection.classify("web_search", "error: HTTP 503 from route")
        )
        assertEquals(
            StepReflection.Outcome.WORKED,
            StepReflection.classify("write_note", "wrote a.txt (12 chars)")
        )
    }

    @Test
    fun reflectionGradesAnUnmovedPageAsNoChange() {
        val sig = "https://x.com/home|Home|400|12"
        assertEquals(
            StepReflection.Outcome.NO_CHANGE,
            StepReflection.classify(
                "browser_click", "CLICK_OK …", sig, sig
            )
        )
        // A moved page worked, whatever the prose says.
        assertEquals(
            StepReflection.Outcome.WORKED,
            StepReflection.classify(
                "browser_click", "CLICK_OK …", sig, "https://x.com/next|Next|10|2"
            )
        )
        // A challenge found is the step WORKING (the wall
        // machinery owns what happens next).
        assertEquals(
            StepReflection.Outcome.WORKED,
            StepReflection.classify(
                "browser_open", "CHALLENGE arkose: verify you are human"
            )
        )
    }

    @Test
    fun reflectionsAccumulateBoundedOnTheRecord() {
        val task = JSONObject()
        repeat(80) {
            StepReflection.record(
                task, it, "web_search", StepReflection.Outcome.WORKED
            )
        }
        val arr = task.getJSONArray(StepReflection.FIELD)
        assertEquals(StepReflection.MAX_KEPT, arr.length())
        // The tail survives — the newest evidence.
        assertEquals(79, arr.getJSONObject(arr.length() - 1).getInt("step"))
    }

    // ---------- the stall mechanism ----------

    @Test
    fun threeBadStepsChangeStrategyThenPark() {
        val tracker = StallTracker()
        val args = JSONObject().put("q", "x")
        assertEquals(
            StallTracker.Verdict.OK,
            tracker.record(
                tracker.signature("web_search", args, "error: no such note"),
                StepReflection.Outcome.NO_CHANGE
            )
        )
        assertEquals(
            StallTracker.Verdict.OK,
            tracker.record(
                tracker.signature("read_note", args, "error: no such note"),
                StepReflection.Outcome.NO_CHANGE
            )
        )
        assertEquals(
            StallTracker.Verdict.CHANGE_STRATEGY,
            tracker.record(
                tracker.signature("list_notes", args, "error: unknown tool"),
                StepReflection.Outcome.WRONG_TARGET
            )
        )
        // After the strategy change, three more bad steps park.
        tracker.record(
            tracker.signature("a", args, "error: x"),
            StepReflection.Outcome.NO_CHANGE
        )
        tracker.record(
            tracker.signature("b", args, "error: x"),
            StepReflection.Outcome.NO_CHANGE
        )
        assertEquals(
            StallTracker.Verdict.PARK,
            tracker.record(
                tracker.signature("c", args, "error: x"),
                StepReflection.Outcome.NO_CHANGE
            )
        )
        assertTrue(tracker.parkReason().contains("parked"))
    }

    @Test
    fun aRepeatedIdenticalStepIsStuckEvenWhenItWorks() {
        val tracker = StallTracker()
        val args = JSONObject().put("url", "https://x.com")
        val sig = tracker.signature("browser_open", args, "PAGE same")
        assertEquals(
            StallTracker.Verdict.OK,
            tracker.record(sig, StepReflection.Outcome.WORKED)
        )
        assertEquals(
            StallTracker.Verdict.OK,
            tracker.record(sig, StepReflection.Outcome.WORKED)
        )
        assertEquals(
            StallTracker.Verdict.CHANGE_STRATEGY,
            tracker.record(sig, StepReflection.Outcome.WORKED)
        )
    }

    @Test
    fun workedStepsResetTheBadStreak() {
        val tracker = StallTracker()
        val args = JSONObject()
        tracker.record(
            tracker.signature("a", args, "e1"),
            StepReflection.Outcome.NO_CHANGE
        )
        tracker.record(
            tracker.signature("b", args, "e2"),
            StepReflection.Outcome.NO_CHANGE
        )
        tracker.record(
            tracker.signature("c", args, "ok"),
            StepReflection.Outcome.WORKED
        )
        tracker.record(
            tracker.signature("d", args, "e3"),
            StepReflection.Outcome.NO_CHANGE
        )
        assertEquals(
            StallTracker.Verdict.OK,
            tracker.record(
                tracker.signature("e", args, "e4"),
                StepReflection.Outcome.NO_CHANGE
            )
        )
    }

    // ---------- event log + condenser ----------

    @Test
    fun eventLogAppendsAndCondensesWithATombstone() {
        val task = JSONObject()
        repeat(EventLog.MAX_EVENTS + 50) {
            EventLog.append(task, "step", it.toLong())
        }
        val kinds = EventLog.kinds(task)
        assertTrue(kinds.size <= EventLog.MAX_EVENTS)
        assertEquals("condensed", kinds.first())
    }

    @Test
    fun condenserFoldsTheMiddleAndSaysSo() {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", "sys"))
        messages.put(JSONObject().put("role", "user").put("content", "the job"))
        repeat(20) { i ->
            messages.put(
                JSONObject().put("role", "assistant")
                    .put("content", "call $i")
            )
            messages.put(
                JSONObject().put("role", "user")
                    .put(
                        "content",
                        if (i % 4 == 0) {
                            "Tool result (x): error: nope"
                        } else {
                            "Tool result (x): fine"
                        }
                    )
            )
        }
        val (out, folded) = EventLog.condenseMessages(messages, keepTail = 6)
        assertTrue(folded > 0)
        assertTrue(out.length() < messages.length())
        // Head survives; the tombstone names the fold.
        assertEquals("sys", out.getJSONObject(0).optString("content"))
        assertEquals("the job", out.getJSONObject(1).optString("content"))
        assertTrue(
            out.getJSONObject(2).optString("content")
                .contains("condensed")
        )
    }

    @Test
    fun condenserLeavesShortContextsAlone() {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", "sys"))
        messages.put(JSONObject().put("role", "user").put("content", "job"))
        messages.put(JSONObject().put("role", "user").put("content", "res"))
        val (out, folded) = EventLog.condenseMessages(messages, keepTail = 12)
        assertEquals(0, folded)
        assertEquals(3, out.length())
    }
}
