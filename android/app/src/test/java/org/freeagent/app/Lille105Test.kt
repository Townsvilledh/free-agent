package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.5 — the 2026-11-27 screenshot exchange, pinned
 * end to end at the pure level (the intake glue is
 * Android-coupled; its decision order is: wallet →
 * kill → reason probe → status probe → … → brain, and
 * every branch it can take is one of the pure functions
 * exercised here):
 *
 *   "kill all task"            → TaskControl (was: the
 *                                brain CLAIMING a stop)
 *   card above the reply       → the killed rendering on
 *                                every surface
 *   his re-tap of the X action → the restart stamp
 *   "why paused"               → StatusProbe's reason
 *                                answer, from the record
 *   the brain's old paragraph  → ChatOutputGuard's
 *                                capability-denial rule
 *
 * The kill's coverage of PACKAGED tasks is proven here,
 * not assumed: the X record is a packaged record on the
 * same store, and the kill loop's predicate
 * (TaskControl.isStoppable) is lane-blind.
 */
class Lille105Test {

    private val now = 1_700_000_000_000L

    /** The X creation record as it stood in the
     *  screenshot: packaged (kind + title), PAUSED at 35
     *  steps, its pause reason recorded as the note. */
    private fun xTask(state: String = TaskStates.PAUSED): JSONObject {
        val messages = JSONArray()
        messages.put(
            JSONObject().put("role", "assistant")
                .put("content", "{\"tool\":\"browser_open\",\"args\":{\"url\":\"https://x.com\"}}")
        )
        messages.put(
            JSONObject().put("role", "user")
                .put("content", "Tool result (browser_open): URL: https://x.com — page loaded")
        )
        return JSONObject()
            .put("id", "task-x")
            .put("kind", "browser")
            // XAccountTask.TITLE, as a literal so this suite
            // stays in the pure harness (XAccountTask itself
            // is Context-coupled).
            .put("title", "Create Lille's X account")
            .put("text", "Create Lille's X account")
            .put("state", state)
            .put("steps", 35)
            .put("messages", messages)
            .put("summary", "")
            .put(
                "note",
                "Step budget (40) reached — resume to continue."
            )
            .put("createdAt", 1_000L)
            .put("updatedAt", 2_000L)
            .put(ChatTaskLink.ORIGIN_FIELD, "chat-1")
    }

    private fun generalTask(
        state: String, id: String = "task-g"
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("text", "Join the Discord and read my Gmail")
        .put("state", state)
        .put("steps", 5)
        .put("messages", JSONArray())
        .put("summary", "")
        .put("note", "")
        .put("createdAt", 1_000L)
        .put("updatedAt", 2_000L)
        .put(ChatTaskLink.ORIGIN_FIELD, "chat-1")

    /** The pure half of MainActivity.killAllTasks: every
     *  stoppable record transitions to CANCELLED with the
     *  kill stamped on it. */
    private fun applyKill(
        tasks: List<JSONObject>, command: String
    ): List<String> {
        val stopped = mutableListOf<String>()
        for (task in tasks) {
            if (!TaskControl.isStoppable(task)) continue
            task.put("state", TaskStates.CANCELLED)
            TaskControl.stampKill(task, command, now)
            stopped.add(TaskTransparency.titleFor(task))
        }
        return stopped
    }

    // ---------- the kill ----------

    @Test
    fun killAllShapesAreCommands() {
        for (shape in listOf(
            "kill all task", "Kill all tasks!", "stop all tasks",
            "cancel all task", "kill all", "stop everything",
            "cancel everything", "kill all work"
        )) {
            assertTrue("kill shape: $shape", TaskControl.isKillAll(shape))
        }
    }

    @Test
    fun killLookalikesAreNotCommands() {
        for (notCommand in listOf(
            "kill the lights", "kill time", "killing it",
            "stop the task for a moment and think it over",
            // v1.0.11 (G4): edge politeness is stripped,
            // so the lookalike that stays rejected is the
            // one whose verb does not lead even after the
            // strip. ("please kill all tasks now" itself
            // is a command now — pinned in Lille111Test.)
            "tell me, please kill all tasks",
            "kill all task lists",
            "stop", "cancel", ""
        )) {
            assertFalse("not a command: $notCommand", TaskControl.isKillAll(notCommand))
        }
    }

    @Test
    fun theScreenshotKillStopsEverythingIncludingPackaged() {
        val x = xTask()
        val running = generalTask(TaskStates.RUNNING)
        val resting = generalTask(TaskStates.WAITING_ROUTE, "task-r")
            .put(StepBudget.REST_FLAG, true)
            .put("nextRetryAt", now - 1_000L)
        val done = generalTask(TaskStates.COMPLETED, "task-d")

        val stopped = applyKill(
            listOf(x, running, resting, done), "kill all task"
        )

        // All three live records stopped — the packaged X
        // record among them; the finished one untouched.
        assertEquals(3, stopped.size)
        for (task in listOf(x, running, resting)) {
            assertEquals(TaskStates.CANCELLED, task.optString("state"))
            assertTrue(TaskControl.wasKilled(task))
            assertEquals("kill all task", TaskControl.killCommand(task))
            assertTrue(EventLog.kinds(task).contains("killed"))
            assertFalse(TaskControl.isStoppable(task))
        }
        assertEquals(TaskStates.COMPLETED, done.optString("state"))
        assertFalse(TaskControl.wasKilled(done))

        // Killed is terminal under every resume machine:
        // not resumable, not waiting, invisible to the
        // watchdog's expired/stale selectors even with a
        // due retry time and a stale record.
        val all = listOf(x, running, resting, done)
        for (task in listOf(x, running, resting)) {
            assertFalse(TaskStates.isResumable(task.optString("state")))
            assertFalse(TaskStates.isWaiting(task.optString("state")))
        }
        assertTrue(TaskWatchdog.expiredWaitIds(all, now).isEmpty())
        assertTrue(
            TaskWatchdog.staleWaitIds(all, now + 3_600_000L, true)
                .isEmpty()
        )

        // The reply is composed from the deed.
        assertEquals(
            "All active tasks have been stopped.",
            TaskControl.replyFor(stopped)
        )
        assertEquals(
            "No work was running — nothing to stop.",
            TaskControl.replyFor(emptyList())
        )
        assertEquals(
            "Stopped — Create Lille's X account.",
            TaskControl.replyFor(listOf("Create Lille's X account"))
        )
    }

    @Test
    fun killedRendersStoppedOnEverySurface() {
        val x = xTask()
        applyKill(listOf(x), "kill all task")

        assertEquals(
            "Stopped — you stopped this.",
            StatusProbe.statusLineFor(x)
        )
        val card = TaskTransparency.statusText(x, now)
        assertTrue(card.startsWith("Stopped — "))
        assertTrue(card.contains("You stopped this."))
        assertFalse(card.contains("under way"))
        assertFalse(card.contains("Working on it"))
        assertFalse(card.contains("Paused"))
        assertEquals("· stopped", ChatWorkMark.markFor(x))

        // The reason probe names his own command back.
        val why = StatusProbe.reasonAnswerFor(x, now)
        assertTrue(why.contains("Create Lille's X account"))
        assertTrue(why.contains("kill all task"))
        assertTrue(why.contains("You stopped"))
        assertTrue(ChatOutputGuard.isClean(why))
        assertFalse(NarrationGuard.containsNarration(why))
    }

    // ---------- the restart ----------

    @Test
    fun aRestartStampsTheInheritanceOnTheRecord() {
        // His re-tap re-adopts the still-paused X run:
        // the pickup is explicit — when, and at which
        // step the earlier run left off.
        val x = xTask()
        TaskControl.stampRestart(x, now)
        assertEquals(35, TaskControl.resumedAtStep(x))
        assertEquals(1, x.optInt(TaskControl.RESTART_COUNT_FIELD, 0))
        assertEquals(now, x.optLong(TaskControl.RESTARTED_AT_FIELD, 0L))
        assertTrue(EventLog.kinds(x).contains("restarted"))

        // And a killed record is never the one restarted:
        // it is terminal, so the live search that feeds
        // the re-adopt path excludes it — a fresh record
        // (new id, no kill fields, no steps) is what a
        // post-kill tap creates.
        val killed = xTask()
        applyKill(listOf(killed), "kill all task")
        assertTrue(TaskStates.isTerminal(killed.optString("state")))
        val fresh = xTask(state = TaskStates.QUEUED)
            .put("id", "task-x2").put("steps", 0)
        assertNotEquals(killed.optString("id"), fresh.optString("id"))
        assertFalse(TaskControl.wasKilled(fresh))
        assertNull(TaskControl.resumedAtStep(fresh))
    }

    // ---------- "why paused" ----------

    @Test
    fun whyPausedAfterRestartAnswersFromTheRecord() {
        val x = xTask()
        TaskControl.stampRestart(x, now)

        assertTrue(StatusProbe.isReasonProbe("why paused"))
        assertTrue(StatusProbe.isReasonProbe("why paused?"))
        val answer = StatusProbe.reasonAnswerFor(x, now)
        // The task is named, the restart is confessed, and
        // the recorded pause reason is reported — the
        // three facts the screenshot's answer missed.
        assertTrue(answer.contains("Create Lille's X account"))
        assertTrue(answer.contains("restarted"))
        assertTrue(answer.contains("step 35"))
        assertTrue(answer.contains("Step budget (40) reached"))
        // And none of the confabulation's shapes.
        assertFalse(answer.contains("aren't available"))
        assertFalse(answer.contains("in this environment"))
        assertFalse(answer.contains("run locally"))
        assertTrue(ChatOutputGuard.isClean(answer))
        assertFalse(NarrationGuard.containsNarration(answer))
    }

    @Test
    fun reasonAnswersPerState() {
        // Budget rest: not paused at all — resting, and
        // it continues on its own.
        val resting = generalTask(TaskStates.WAITING_ROUTE)
            .put(StepBudget.REST_FLAG, true)
            .put("nextRetryAt", now + 120_000L)
        val restAnswer = StatusProbe.reasonAnswerFor(resting, now)
        assertTrue(restAnswer.contains("isn't paused"))
        assertTrue(restAnswer.contains("resting between work sessions"))
        assertTrue(restAnswer.contains("continues on its own"))

        // Parked at a wall: the user's exact step.
        val parked = generalTask(TaskStates.PARKED_AT_WALL)
            .put("wallStep", "Complete the human check on x.com.")
        val parkedAnswer = StatusProbe.reasonAnswerFor(parked, now)
        assertTrue(parkedAnswer.contains("waiting on one step from you"))
        assertTrue(parkedAnswer.contains("Complete the human check on x.com."))

        // Stall park: the recorded reason, verbatim shape.
        val stalled = generalTask(TaskStates.PAUSED)
            .put("note", StallTracker().parkReason())
        val stallAnswer = StatusProbe.reasonAnswerFor(stalled, now)
        assertTrue(stallAnswer.contains("is paused"))
        assertTrue(stallAnswer.contains("same wall"))

        // Browser takeover: his hand is the reason.
        val takeover = generalTask(TaskStates.PAUSED)
            .put("browserTakeover", true)
        val takeoverAnswer = StatusProbe.reasonAnswerFor(takeover, now)
        assertTrue(takeoverAnswer.contains("took the browser over"))

        // Approval wait: a record fact, not a note.
        val approval = generalTask(TaskStates.PAUSED)
            .put(PlanAct.APPROVAL_KIND_FIELD, "plan")
        val approvalAnswer = StatusProbe.reasonAnswerFor(approval, now)
        assertTrue(approvalAnswer.contains("waiting on your word"))

        // No reason recorded: the answer says so — a
        // cause is never invented.
        val silent = generalTask(TaskStates.PAUSED)
        val silentAnswer = StatusProbe.reasonAnswerFor(silent, now)
        assertTrue(silentAnswer.contains("doesn't say why"))

        // Running: it isn't paused.
        val running = generalTask(TaskStates.RUNNING)
        val runningAnswer = StatusProbe.reasonAnswerFor(running, now)
        assertTrue(runningAnswer.contains("isn't paused"))
        assertTrue(runningAnswer.contains("working on it now"))

        // Every one of these answers is itself clean
        // under both guards — the deterministic path can
        // never produce the failure it replaces.
        for (answer in listOf(
            restAnswer, parkedAnswer, stallAnswer,
            takeoverAnswer, approvalAnswer, silentAnswer,
            runningAnswer
        )) {
            assertTrue("guard-clean: $answer", ChatOutputGuard.isClean(answer))
            assertFalse(
                "narration-clean: $answer",
                NarrationGuard.containsNarration(answer)
            )
        }
    }

    @Test
    fun reasonProbeLookalikesAreNotIntercepted() {
        for (notProbe in listOf(
            "explain why paused trading is risky",
            "why is the sky blue",
            "why did you stop the download yesterday",
            "why paused my card was declined at the shop",
            "why is my phone battery draining so fast",
            "what happened at the meeting yesterday",
            "hello", ""
        )) {
            assertFalse(
                "not a reason probe: $notProbe",
                StatusProbe.isReasonProbe(notProbe)
            )
        }
        for (probe in listOf(
            "why", "Why?", "why paused", "why did you stop",
            "why is it stopped", "why isn't it running",
            "why isn't it working", "what happened",
            "why did you stop it", "why is it stuck"
        )) {
            assertTrue(
                "reason probe: $probe",
                StatusProbe.isReasonProbe(probe)
            )
        }
    }

    // ---------- label exclusivity ----------

    @Test
    fun stateLabelsNeverContradictThemselves() {
        val active = setOf(
            TaskStates.QUEUED, TaskStates.RUNNING,
            TaskStates.WAITING_ROUTE, TaskStates.WAITING_NETWORK
        )
        for (state in TaskStates.ALL) {
            val task = generalTask(state)
            val ack = StatusProbe.liveAckFor("Creating her X account", task)
            if (state in active) {
                assertTrue(
                    "active state may claim under way: $state",
                    ack.contains("already under way")
                )
            } else {
                assertFalse(
                    "non-active state must not claim under way: $state → $ack",
                    ack.contains("under way")
                )
            }
            // The composed card never pairs a working
            // headline with a paused one.
            val card = TaskTransparency.statusText(task, now)
            assertFalse(
                "card contradiction in $state",
                card.contains("Working on it here") &&
                    card.contains("Paused here")
            )
        }
        // The screenshot's exact pairing, pinned out.
        val pausedAck = StatusProbe.liveAckFor(
            "Creating her X account", xTask()
        )
        assertEquals(
            "Creating her X account — paused here · 35 steps done.",
            pausedAck
        )
    }

    // ---------- the confabulation, as a guard fixture ----------

    /** The screenshot's "why paused" answer, verbatim in
     *  its distinctive clauses. It must never reach the
     *  chat again. */
    private val confabulation =
        "I paused because the actions you asked for (joining " +
            "Discord, reading your Gmail, creating a GitHub " +
            "repo, recording a video, etc.) require interactive " +
            "browser-based tools and access to external services " +
            "that aren't available in this environment. Since I " +
            "can't perform those steps here, I stopped the task " +
            "rather than produce incomplete or misleading " +
            "results. Let me know how you'd like to proceed — " +
            "e.g., I can give you detailed instructions you can " +
            "run locally, or wait for a future environment " +
            "where the required tools are available."

    @Test
    fun theConfabulationIsRejectedAsCapabilityDenial() {
        assertEquals(
            ChatOutputGuard.Verdict.Rejected("capability-denial"),
            ChatOutputGuard.verdict(confabulation)
        )
        // Its distinctive phrases, one by one.
        for (phrase in listOf(
            "The tools are not available in this environment.",
            "Those features aren't available here in this build.",
            "I can give you instructions you can run locally.",
            "We can wait for a future environment where this works.",
            "Her capabilities are unavailable in this environment."
        )) {
            assertEquals(
                "phrase rejected: $phrase",
                ChatOutputGuard.Verdict.Rejected("capability-denial"),
                ChatOutputGuard.verdict(phrase)
            )
        }
    }

    @Test
    fun legitimateHandOffsAndPartialStatesPassTheGuard() {
        for (fine in listOf(
            "That part needs the computer lane — I've sent it " +
                "there with a written brief, and I'll finish " +
                "the rest here.",
            "Gmail isn't connected yet — one step in Settings " +
                "connects it, and the rest of the job is " +
                "already under way.",
            "Sending email is switched off in Settings, so I " +
                "can prepare the message but not send it yet.",
            "That shop isn't available here in Nigeria without " +
                "a local card.",
            "The 8am slot is unavailable — the next opening " +
                "is at 10am.",
            CapabilitySheet.GROUNDED_LINE
        )) {
            assertTrue(
                "must pass: $fine",
                ChatOutputGuard.isClean(fine)
            )
        }
        assertFalse(
            NarrationGuard.containsNarration(CapabilitySheet.GROUNDED_LINE)
        )
    }

    // ---------- the capability sheet ----------

    @Test
    fun capabilitySheetStatesTheLiveFacts() {
        val connected = CapabilitySheet.render(
            CapabilitySheet.Facts(
                gmailConnected = true,
                xLine = CapabilitySheet.xLineFor("lilleagent", false),
                walletFamilies = listOf("Ethereum", "Bitcoin", "Solana"),
                lanConfigured = false
            )
        )
        assertTrue(connected.contains("Gmail is connected"))
        assertTrue(connected.contains("@lilleagent"))
        assertTrue(connected.contains("Ethereum, Bitcoin, Solana"))
        assertTrue(connected.contains("computer lane"))
        assertFalse(connected.contains("extra brain"))

        val notConnected = CapabilitySheet.render(
            CapabilitySheet.Facts(
                gmailConnected = false,
                xLine = CapabilitySheet.xLineFor(null, true),
                walletFamilies = emptyList(),
                lanConfigured = true
            )
        )
        assertTrue(notConnected.contains("connect it in Settings"))
        assertTrue(notConnected.contains("being created by a task"))
        assertTrue(notConnected.contains("extra brain"))
        // The sheet itself never trips the narration net.
        assertFalse(NarrationGuard.containsNarration(connected))
        assertFalse(NarrationGuard.containsNarration(notConnected))
    }
}
