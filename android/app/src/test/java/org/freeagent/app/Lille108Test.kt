package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.8 — she answers about her own work from her
 * records, always; the X lane cannot falsely finish.
 *
 * Born of Ethuram's 2026-11-27 screenshots (1.0.7):
 * the X creation record read "Finished — Done" after
 * three mailbox steps, its own expansion also printed
 * the stale "Waiting — continues automatically"
 * trailer, and his follow-ups ("so the x profile is
 * created?", "why did you claim its complete", "so
 * where does the task stand now", "already logged
 * in") were answered by the BRAIN with status-denial
 * and an invented confession — while the record sat
 * readable in the same app. Plus the 12:55 connect
 * evidence (she couldn't tell his account from hers)
 * and the 13:06 phase-machine ruling (the mailbox
 * circles) and the 13:11 accounts registry.
 */
class Lille108Test {

    private val chat = "chat-108"
    private val now = 1_700_000_000_000L

    // ---------- fixtures ----------

    private fun toolMsg(tool: String, argsJson: String): JSONObject =
        JSONObject().put("role", "assistant").put(
            "content",
            "```json\n{\"tool\": \"$tool\", \"args\": $argsJson}\n```"
        )

    private fun resultMsg(tool: String, body: String): JSONObject =
        JSONObject().put("role", "user")
            .put("content", "Tool result ($tool): $body")

    private fun record(
        title: String, state: String,
        messages: JSONArray = JSONArray(),
        extra: (JSONObject) -> Unit = {}
    ): JSONObject {
        val t = JSONObject()
            .put("id", "task-" + title.hashCode())
            .put("title", title)
            .put("text", title)
            .put("state", state)
            .put(ChatTaskLink.ORIGIN_FIELD, chat)
            .put("createdAt", now - 3_600_000L)
            .put("updatedAt", now - 60_000L)
            .put("messages", messages)
            .put("steps", 3)
        extra(t)
        return t
    }

    /** The screenshot record, faithfully: three mailbox
     *  steps, a "Done." summary, and the stale waiting
     *  note picked up in an earlier state. */
    private fun falseFinish(): JSONObject {
        val messages = JSONArray()
            .put(toolMsg("browser_open", "{\"url\": \"https://www.emailnator.com/\"}"))
            .put(resultMsg("browser_open", "BROWSER_OK opened https://www.emailnator.com/"))
            .put(toolMsg("browser_click", "{\"ref\": 4}"))
            .put(resultMsg("browser_click", "CLICK_OK"))
            .put(toolMsg("browser_open", "{\"url\": \"https://www.guerrillamail.com/\"}"))
            .put(resultMsg("browser_open", "BROWSER_OK opened https://www.guerrillamail.com/"))
        return record(
            "Create Lille's X account", TaskStates.COMPLETED, messages
        ) {
            it.put("kind", "browser")
            it.put("summary", "Done.")
            it.put("note", BrainPacing.WAITING_NOTE)
        }
    }

    // ---------- 1. the state sheet ----------

    @Test
    fun stateSheetStatesTheFalseFinishAsNotStanding() {
        val sheet = StateSheet.render(
            listOf(falseFinish()),
            StateSheet.Facts(
                browserUrl = "https://www.guerrillamail.com/",
                browserTitle = "Guerrilla Mail",
                xVerifiedHandle = null,
                walletFamilies = emptyList()
            ),
            now
        )
        assertTrue(sheet.contains("does not stand"))
        assertTrue(sheet.contains("NOT created"))
        assertTrue(sheet.contains("guerrillamail.com"))
        // The phase machine's standing rides the line.
        assertTrue(sheet.contains("Creation phase: MAILBOX"))
        assertTrue(sheet.contains("no address committed yet"))
    }

    @Test
    fun stateSheetCarriesTheAccountsRegistry() {
        val root = AccountsRegistry.parseRoot(null)
        AccountsRegistry.register(
            root, "x", "lilleetbtc", AccountsRegistry.OWNER_LILLE, now
        )
        AccountsRegistry.register(
            root, "x", "jeetethuram", AccountsRegistry.OWNER_USER, now
        )
        AccountsRegistry.markActive(root, "x", "jeetethuram", now)
        val sheet = StateSheet.render(
            emptyList(),
            StateSheet.Facts(
                browserUrl = "", browserTitle = "",
                xVerifiedHandle = null, walletFamilies = emptyList(),
                accountsLines = AccountsRegistry.summaryLines(root)
            ),
            now
        )
        assertTrue(sheet.contains("@jeetethuram — yours · active now"))
        assertTrue(sheet.contains("@lilleetbtc — hers"))
        assertTrue(sheet.contains("she asks before acting"))
    }

    // ---------- 2. the state-question family ----------

    @Test
    fun completionQuestionResolvesAndCrossChecks() {
        val tasks = listOf(falseFinish())
        val q = StatusProbe.stateQuestionFor(
            "so the x profile is created?", tasks, chat
        )
        assertNotNull(q)
        assertEquals(
            StatusProbe.StateKind.COMPLETION, q!!.second
        )
        val answer = StatusProbe.stateAnswerFor(
            q.first, now, q.second
        )
        assertTrue(answer.startsWith("No"))
        assertTrue(answer.contains("doesn't stand"))
        assertTrue(answer.contains("no X session has been verified as hers"))
        // The last REAL step is named — the mailbox, not
        // the synthetic finish bookkeeping.
        assertTrue(answer.contains("guerrillamail"))
    }

    @Test
    fun accountabilityResolvesAndTellsTheRecordStory() {
        val tasks = listOf(falseFinish())
        val q = StatusProbe.stateQuestionFor(
            "why did you claim its complete", tasks, chat
        )
        assertNotNull(q)
        assertEquals(
            StatusProbe.StateKind.ACCOUNTABILITY, q!!.second
        )
        val answer = StatusProbe.stateAnswerFor(
            q.first, now, q.second
        )
        assertTrue(answer.contains("the record was wrong"))
        assertTrue(answer.contains("reopened"))
        // Never the brain's invented confession.
        assertFalse(answer.contains("I incorrectly treated"))
    }

    @Test
    fun standQuestionResolvesToTheCrossCheckedStatus() {
        val tasks = listOf(falseFinish())
        val q = StatusProbe.stateQuestionFor(
            "so where does the task stand now", tasks, chat
        )
        assertNotNull(q)
        assertEquals(StatusProbe.StateKind.STAND, q!!.second)
        val answer = StatusProbe.stateAnswerFor(
            q.first, now, q.second
        )
        assertTrue(answer.contains("doesn't stand"))
    }

    @Test
    fun browserQuestionResolvesToTheRestingPage() {
        val tasks = listOf(falseFinish())
        val q = StatusProbe.stateQuestionFor(
            "what page is she on?", tasks, chat
        )
        assertNotNull(q)
        assertEquals(StatusProbe.StateKind.BROWSER, q!!.second)
        val answer = StatusProbe.stateAnswerFor(
            q.first, now, q.second,
            "https://www.guerrillamail.com/", "Guerrilla Mail"
        )
        assertTrue(answer.contains("guerrillamail.com"))
    }

    @Test
    fun didItFinishResolvesAsCompletion() {
        val q = StatusProbe.stateQuestionFor(
            "did it finish?", listOf(falseFinish()), chat
        )
        assertNotNull(q)
        assertEquals(
            StatusProbe.StateKind.COMPLETION, q!!.second
        )
    }

    @Test
    fun jobShapedLookalikesAreNotIntercepted() {
        val tasks = listOf(falseFinish())
        assertNull(
            StatusProbe.stateQuestionFor("update my bio", tasks, chat)
        )
        assertNull(
            StatusProbe.stateQuestionFor(
                "is the report ready", tasks, chat
            )
        )
        assertNull(
            StatusProbe.stateQuestionFor(
                "create a post about the launch", tasks, chat
            )
        )
    }

    // ---------- the login family ----------

    private fun connectRecord(): JSONObject =
        record("Connect my X account", TaskStates.PARKED_AT_WALL) {
            it.put("kind", "browser")
            it.put("wallKind", UserHandoff.WALL_KIND)
        }

    @Test
    fun loginAssertionResolvesToTheStamp() {
        val connect = connectRecord()
        val tasks = listOf(connect)
        assertNotNull(
            StatusProbe.loginTaskFor("already logged in", tasks, chat)
        )
        // No stamp yet: the answer says the check hasn't run.
        val before = StatusProbe.loginAnswerFor(connect, now)
        assertTrue(before.contains("hasn't read"))
        // The handback verification stamps the handle.
        SessionIdentity.stamp(connect, "jeetethuram", now)
        val after = StatusProbe.loginAnswerFor(connect, now)
        assertTrue(after.contains("signed in as @jeetethuram"))
        assertTrue(after.contains("approval-gated"))
    }

    @Test
    fun loginAnswerNamesHisAccountOnHerCreationLane() {
        val creation = record(
            "Create Lille's X account", TaskStates.RUNNING
        ) {
            it.put("kind", "browser")
            it.put(FinishGate.HER_HANDLE_FIELD, "lilleetbtc")
        }
        SessionIdentity.stamp(creation, "jeetethuram", now)
        val answer = StatusProbe.loginAnswerFor(creation, now)
        assertTrue(answer.contains("your account, not hers"))
    }

    @Test
    fun loginJobsAreNotIntercepted() {
        val tasks = listOf(connectRecord())
        assertNull(
            StatusProbe.loginTaskFor(
                "log in to the site and post the update", tasks, chat
            )
        )
    }

    // ---------- 3. the finish gate + audit ----------

    @Test
    fun finishGateCriteriaJudgeFromState() {
        val bare = falseFinish()
        assertTrue(FinishGate.isGated(bare))
        assertFalse(FinishGate.holds(bare))
        assertEquals(2, FinishGate.unmet(bare).size)
        assertTrue(
            FinishGate.discrepancyFor(bare)
                .contains("no X session has been verified as hers")
        )
        // Session + profile evidence: the gate holds.
        val proven = falseFinish()
        proven.put("xHandle", "lilleetbtc")
        proven.put(FinishGate.PROFILE_EVIDENCE_FIELD, "https://x.com/lilleetbtc")
        assertTrue(FinishGate.holds(proven))
        // A session stamp naming someone else, fresher
        // than the handle stamp, contradicts the session.
        val foreign = falseFinish()
        foreign.put("xHandle", "lilleetbtc")
        foreign.put(FinishGate.X_HANDLE_AT_FIELD, now - 10_000L)
        SessionIdentity.stamp(foreign, "jeetethuram", now)
        assertNull(FinishGate.sessionHandle(foreign))
        assertFalse(FinishGate.holds(foreign))
    }

    @Test
    fun auditReopenStampsTheCorrection() {
        val task = falseFinish()
        assertTrue(FinishGate.shouldReopen(task))
        assertTrue(
            TaskStates.canTransition(
                TaskStates.COMPLETED, TaskStates.WAITING_ROUTE
            )
        )
        FinishGate.stampReopen(task, now)
        // The audit's state transition (MainActivity)
        // rides with the stamp.
        task.put("state", TaskStates.WAITING_ROUTE)
        assertTrue(
            task.optString(FinishGate.DISCREPANCY_FIELD)
                .contains("no X session")
        )
        assertEquals("", task.optString("summary"))
        assertTrue(task.optString("note").contains("open again"))
        // The probe answers carry the corrected story.
        assertTrue(StatusProbe.hasDiscrepancy(task))
        val answer = StatusProbe.discrepancyAnswer(task, now)
        assertTrue(answer.contains("already been corrected"))
    }

    @Test
    fun profileUrlMatchingIsExact() {
        assertTrue(
            FinishGate.isProfileUrl(
                "https://x.com/LilleEtBtc", "lilleetbtc"
            )
        )
        assertFalse(
            FinishGate.isProfileUrl(
                "https://x.com/lilleetbtc/status/123", "lilleetbtc"
            )
        )
        assertFalse(
            FinishGate.isProfileUrl(
                "https://x.com/home", "lilleetbtc"
            )
        )
    }

    // ---------- 4. state coherence ----------

    @Test
    fun coherenceSweepAcrossAllStates() {
        for (state in TaskStates.ALL) {
            val task = record("Some work", state) {
                it.put("note", BrainPacing.WAITING_NOTE)
                it.put("summary", "Done.")
            }
            val status = TaskTransparency.statusText(task, now)
            val expanded = TaskTransparency.expandedText(task, now)
            if (!TaskStates.isWaiting(state)) {
                assertFalse(
                    "statusText leaked the waiting note into $state",
                    status.contains(BrainPacing.WAITING_NOTE)
                )
                assertFalse(
                    "expandedText leaked the waiting note into $state",
                    expanded.contains(BrainPacing.WAITING_NOTE)
                )
            }
            if (state != TaskStates.COMPLETED &&
                state != TaskStates.CANCELLED
            ) {
                assertFalse(
                    "waiting record for $state claimed Finished",
                    status.startsWith("Finished")
                )
            }
        }
    }

    @Test
    fun theFalseFinishReportCarriesNoWaitingTrailer() {
        val report = ChatTaskLink.reportFor(
            falseFinish(), TaskStates.COMPLETED
        )
        assertNotNull(report)
        assertFalse(report!!.contains(BrainPacing.WAITING_NOTE))
        assertFalse(report.contains("continues automatically"))
    }

    // ---------- 5. status-denial guard ----------

    @Test
    fun statusDenialFixturesAreBlocked() {
        val fixtures = listOf(
            "I can't confirm that an X profile was created.",
            "I can't verify an active task's current status from this chat.",
            "I can't tell from here whether you're logged in.",
            "I still can't verify whether the profile was created or the task continued afterward.",
            // Reworded variants.
            "I cannot verify the task status from here.",
            "I'm unable to check whether the account was created.",
            "There is no way for me to tell if the session continued.",
            "I can't see what page the browser is on right now."
        )
        for (text in fixtures) {
            val v = ChatOutputGuard.verdict(text)
            assertTrue(
                "expected status-denial rejection for: $text",
                v is ChatOutputGuard.Verdict.Rejected &&
                    (v as ChatOutputGuard.Verdict.Rejected).reason ==
                    "status-denial"
            )
        }
    }

    @Test
    fun worldStateDenialsPass() {
        assertTrue(
            ChatOutputGuard.verdict(
                "I can't verify the price from here — the feed is down."
            ) is ChatOutputGuard.Verdict.Clean
        )
        assertTrue(
            ChatOutputGuard.verdict(
                "I can't confirm the meeting time yet; the calendar has not synced."
            ) is ChatOutputGuard.Verdict.Clean
        )
    }

    // ---------- the phase machine ----------

    @Test
    fun phasesAdvanceOnlyForwardAndResumeAtTheFirstGap() {
        val task = record("Create Lille's X account", TaskStates.RUNNING) {
            it.put("kind", "browser")
        }
        assertEquals(
            XCreationPhases.Phase.MAILBOX,
            XCreationPhases.currentPhase(task)
        )
        assertTrue(
            XCreationPhases.commitAddress(
                task, "lille@guerrillamail.com", now
            )
        )
        assertEquals(
            XCreationPhases.Phase.SIGNUP_FORM,
            XCreationPhases.currentPhase(task)
        )
        // A second commitment is refused — the address stands.
        assertFalse(
            XCreationPhases.commitAddress(
                task, "other@emailnator.com", now
            )
        )
        assertEquals(
            "lille@guerrillamail.com",
            XCreationPhases.committedAddress(task)
        )
        XCreationPhases.stamp(
            task, XCreationPhases.Phase.SIGNUP_FORM,
            "form submitted", now
        )
        // A resume derives EMAIL_VERIFY — never MAILBOX again.
        assertEquals(
            XCreationPhases.Phase.EMAIL_VERIFY,
            XCreationPhases.currentPhase(task)
        )
        val line = XCreationPhases.summaryLine(task)
        assertTrue(line.contains("lille@guerrillamail.com"))
        assertTrue(line.contains("signup form ✓"))
    }

    @Test
    fun mailboxSwitchNeedsRecordedEvidenceAndWorksOnce() {
        val task = record("Create Lille's X account", TaskStates.RUNNING) {
            it.put("kind", "browser")
        }
        XCreationPhases.commitAddress(task, "lille@guerrillamail.com", now)
        // Code-never-arrived without the poll evidence: refused.
        assertNotNull(
            XCreationPhases.switchVerdict(
                task, XCreationPhases.REASON_CODE_NEVER_ARRIVED
            )
        )
        // X-refused before the form was submitted: refused.
        assertNotNull(
            XCreationPhases.switchVerdict(
                task, XCreationPhases.REASON_X_REFUSED
            )
        )
        // The runner's poll exhaustion IS the evidence.
        task.put(XCreationPhases.POLLS_EXHAUSTED_FIELD, true)
        assertNull(
            XCreationPhases.switchVerdict(
                task, XCreationPhases.REASON_CODE_NEVER_ARRIVED
            )
        )
        XCreationPhases.applySwitch(
            task, XCreationPhases.REASON_CODE_NEVER_ARRIVED, now
        )
        assertEquals("", XCreationPhases.committedAddress(task))
        assertEquals(
            XCreationPhases.Phase.MAILBOX,
            XCreationPhases.currentPhase(task)
        )
        assertEquals(
            "lille@guerrillamail.com",
            task.optString(XCreationPhases.PREVIOUS_FIELD)
        )
        // One switch per run: a second is refused outright.
        XCreationPhases.commitAddress(task, "lille@emailnator.com", now)
        task.put(XCreationPhases.POLLS_EXHAUSTED_FIELD, true)
        assertNotNull(
            XCreationPhases.switchVerdict(
                task, XCreationPhases.REASON_CODE_NEVER_ARRIVED
            )
        )
    }

    // ---------- the loop breaker ----------

    @Test
    fun loopBreakerTripsOnTheMailboxCircle() {
        val task = record("Create Lille's X account", TaskStates.RUNNING) {
            it.put("kind", "browser")
        }
        // The 37-step circle's shape: provider after
        // provider, no checkpoint ever advancing.
        var trippedAt = -1
        for (step in 1..37) {
            val v = LoopGuard.noteStep(
                task, "phase:MAILBOX", advanced = false
            )
            if (v.tripped) {
                trippedAt = step
                break
            }
        }
        assertTrue("the circle must trip", trippedAt > 0)
        assertTrue(
            "the circle trips early, not at 37",
            trippedAt <= LoopGuard.TRIP_TOTAL
        )
        // The 3-step screenshot record alone does not trip.
        val small = record("Create Lille's X account", TaskStates.RUNNING) {
            it.put("kind", "browser")
        }
        repeat(3) {
            assertFalse(
                LoopGuard.noteStep(
                    small, "phase:MAILBOX", advanced = false
                ).tripped
            )
        }
        // An advancing step clears the streak.
        LoopGuard.noteStep(small, "phase:MAILBOX", advanced = true)
        assertEquals(0, LoopGuard.streak(small).total)
    }

    @Test
    fun loopClustersNameTheGround() {
        assertEquals(
            "guerrillamail.com/",
            LoopGuard.clusterFor("https://www.guerrillamail.com/")
        )
        assertEquals(
            "x.com/i",
            LoopGuard.clusterFor("https://x.com/i/flow/signup")
        )
    }

    // ---------- the accounts registry ----------

    @Test
    fun registrySeparatesAccountsAndTracksTheActiveOne() {
        val root = AccountsRegistry.parseRoot(null)
        AccountsRegistry.register(
            root, "x", "lilleetbtc", AccountsRegistry.OWNER_LILLE, now
        )
        AccountsRegistry.register(
            root, "x", "jeetethuram", AccountsRegistry.OWNER_USER, now
        )
        AccountsRegistry.register(
            root, "x", "Bitcoinornatin", AccountsRegistry.OWNER_USER, now
        )
        AccountsRegistry.markActive(root, "x", "jeetethuram", now)
        assertEquals(
            AccountsRegistry.Precondition.MATCH,
            AccountsRegistry.precondition(root, "x", "jeetethuram")
        )
        assertEquals(
            AccountsRegistry.Precondition.NEEDS_SWITCH,
            AccountsRegistry.precondition(root, "x", "lilleetbtc")
        )
        assertEquals(
            AccountsRegistry.Precondition.NOT_REGISTERED,
            AccountsRegistry.precondition(root, "x", "nobody")
        )
        // A switch is a registry fact: the previous
        // active is returned and lastActiveAt moves.
        val previous = AccountsRegistry.markActive(
            root, "x", "lilleetbtc", now + 1000L
        )
        assertEquals("jeetethuram", previous)
        assertEquals(
            "lilleetbtc",
            AccountsRegistry.activeEntry(root, "x")?.optString("handle")
        )
        // Signed out: registered, but nothing is active.
        AccountsRegistry.markSignedOut(root, "x")
        assertEquals(
            AccountsRegistry.Precondition.NO_ACTIVE,
            AccountsRegistry.precondition(root, "x", "lilleetbtc")
        )
        val answer = AccountsRegistry.answerFor(root)
        assertTrue(answer.contains("@lilleetbtc"))
        assertTrue(answer.contains("@Bitcoinornatin"))
        assertTrue(answer.contains("No account is active"))
    }

    @Test
    fun registryMergeKeepsTheLocalActiveTruth() {
        val local = AccountsRegistry.parseRoot(null)
        AccountsRegistry.register(
            local, "x", "jeetethuram", AccountsRegistry.OWNER_USER, now
        )
        AccountsRegistry.markActive(local, "x", "jeetethuram", now)
        val backup = AccountsRegistry.parseRoot(null)
        AccountsRegistry.register(
            backup, "x", "lilleetbtc",
            AccountsRegistry.OWNER_LILLE, now - 5000L
        )
        AccountsRegistry.markActive(backup, "x", "lilleetbtc", now - 5000L)
        val added = AccountsRegistry.mergeRoots(local, backup.toString())
        assertEquals(1, added)
        // Her account joined; HIS stays the active one here.
        assertEquals(
            "jeetethuram",
            AccountsRegistry.activeEntry(local, "x")?.optString("handle")
        )
        assertFalse(
            AccountsRegistry.find(local, "x", "lilleetbtc")!!
                .optBoolean("active")
        )
    }

    @Test
    fun accountsQuestionsResolveAndJobsDoNot() {
        assertTrue(
            StatusProbe.isAccountsQuestion("which accounts do you have?")
        )
        assertTrue(
            StatusProbe.isAccountsQuestion("list her accounts")
        )
        assertFalse(
            StatusProbe.isAccountsQuestion("connect my bank account")
        )
        assertFalse(
            StatusProbe.isAccountsQuestion(
                "open an account at the new exchange for me"
            )
        )
    }

    // ---------- session identity ----------

    @Test
    fun handbackAnnouncementsComeFromThePage() {
        val connect = connectRecord()
        assertTrue(
            SessionIdentity.handbackAnnouncement(
                connect, "jeetethuram", challengePresent = false
            ).contains("Signed in as @jeetethuram")
        )
        assertTrue(
            SessionIdentity.handbackAnnouncement(
                connect, null, challengePresent = false
            ).contains("Still signed out")
        )
        val creation = record(
            "Create Lille's X account", TaskStates.PARKED_AT_WALL
        ) {
            it.put("wallKind", "arkose")
        }
        assertTrue(
            SessionIdentity.handbackAnnouncement(
                creation, null, challengePresent = true
            ).contains("human check is still on screen")
        )
    }

    @Test
    fun ownerForSeparatesHersHisAndUnknown() {
        val creation = record(
            "Create Lille's X account", TaskStates.RUNNING
        ) {
            it.put(FinishGate.HER_HANDLE_FIELD, "lilleetbtc")
        }
        SessionIdentity.stamp(creation, "lilleetbtc", now)
        assertEquals(
            SessionIdentity.Owner.SELF,
            SessionIdentity.ownerFor(creation, "jeetethuram")
        )
        SessionIdentity.stamp(creation, "jeetethuram", now + 1)
        assertEquals(
            SessionIdentity.Owner.OTHER,
            SessionIdentity.ownerFor(creation, "jeetethuram")
        )
        SessionIdentity.stamp(creation, null, now + 2)
        assertEquals(
            SessionIdentity.Owner.SIGNED_OUT,
            SessionIdentity.ownerFor(creation, "jeetethuram")
        )
    }
}
