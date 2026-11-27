package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for Plan/Act + checkpoints (v1.0.4, steal #11):
 * in plan mode mutating tools are ABSENT from the offered
 * registry (not refused after the fact), the dispatch
 * wall refuses them anyway, approvals are exact shapes,
 * and a checkpoint restores a step byte-for-byte.
 */
class PlanActTest {

    private val allTools = listOf(
        "browser_open", "browser_read", "write_note", "read_note",
        "calendar_create", "calendar_read", "gmail_send", "gmail_read",
        "web_search", "finish"
    )

    @Test
    fun planModeRegistryOmitsEveryMutatingTool() {
        val offered = PlanAct.offeredTools(PlanAct.MODE_PLAN, allTools)
        for (tool in offered) {
            assertFalse(
                "$tool must not be offered in plan mode",
                TaskTools.isMutating(tool)
            )
        }
        // The read-only kit + the planning acts remain.
        assertTrue("browser_read" in offered)
        assertTrue("calendar_read" in offered)
        assertTrue("gmail_read" in offered)
        assertTrue("propose_plan" in PlanAct.PLAN_TOOLS)
        assertTrue("plan_note" in PlanAct.PLAN_TOOLS)
    }

    @Test
    fun actModeOffersEverything() {
        assertEquals(
            allTools,
            PlanAct.offeredTools(PlanAct.MODE_ACT, allTools)
        )
    }

    @Test
    fun dispatchWallRefusesMutationsInPlanModeOnly() {
        assertTrue(PlanAct.refuses(PlanAct.MODE_PLAN, "write_note"))
        assertTrue(PlanAct.refuses(PlanAct.MODE_PLAN, "gmail_send"))
        assertTrue(PlanAct.refuses(PlanAct.MODE_PLAN, "calendar_create"))
        assertFalse(PlanAct.refuses(PlanAct.MODE_PLAN, "gmail_read"))
        assertFalse(PlanAct.refuses(PlanAct.MODE_ACT, "write_note"))
    }

    @Test
    fun consequentialJobsStartInPlanMode() {
        val send = JSONObject()
        PlanAct.stampInitialMode(send, "send an email to Ada with the invoice")
        assertEquals(PlanAct.MODE_PLAN, PlanAct.mode(send))

        val poem = JSONObject()
        PlanAct.stampInitialMode(poem, "write a poem about rain")
        assertEquals(PlanAct.MODE_ACT, PlanAct.mode(poem))

        // An existing mode is never re-stamped.
        val approved = JSONObject().put(PlanAct.MODE_FIELD, PlanAct.MODE_ACT)
        PlanAct.stampInitialMode(approved, "send an email to Ada with the invoice")
        assertEquals(PlanAct.MODE_ACT, PlanAct.mode(approved))
    }

    @Test
    fun approvalsAreExactShapes() {
        assertTrue(PlanAct.isApprovalMessage("approve"))
        assertTrue(PlanAct.isApprovalMessage("Approve the plan."))
        assertTrue(PlanAct.isApprovalMessage("go ahead"))
        assertTrue(PlanAct.isApprovalMessage("approve send"))
        assertTrue(PlanAct.isApprovalMessage("send it"))
        // Buried in a sentence is conversation, not approval.
        assertFalse(PlanAct.isApprovalMessage("yes but check the total first"))
        assertFalse(PlanAct.isApprovalMessage("I approve of this weather"))
        assertFalse(PlanAct.isApprovalMessage(""))
    }

    @Test
    fun checkpointsRestoreByteForByte() {
        val task = JSONObject()
        Checkpoints.append(
            task, Checkpoints.noteEntry(3, "a.txt", "old content")
        )
        Checkpoints.append(
            task, Checkpoints.noteEntry(4, "b.txt", null)
        )
        assertEquals(2, Checkpoints.count(task))
        // Newest first: b.txt did not exist — undo deletes it.
        val (pathB, contentB) = Checkpoints.restoreTarget(
            Checkpoints.pop(task)!!
        )
        assertEquals("b.txt", pathB)
        assertNull(contentB)
        val (pathA, contentA) = Checkpoints.restoreTarget(
            Checkpoints.pop(task)!!
        )
        assertEquals("a.txt", pathA)
        assertEquals("old content", contentA)
        assertNull(Checkpoints.pop(task))
    }

    @Test
    fun checkpointsAreBounded() {
        val task = JSONObject()
        repeat(Checkpoints.MAX_KEPT + 5) {
            Checkpoints.append(
                task, Checkpoints.noteEntry(it, "n$it.txt", "x")
            )
        }
        assertEquals(Checkpoints.MAX_KEPT, Checkpoints.count(task))
    }
}
