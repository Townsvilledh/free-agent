package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.7 — no text selection in chat at all, and the
 * identity-ask net widened to explainer framing, plus
 * the deterministic needs/blockers probe.
 *
 * Born of Ethuram's 2026-11-27 11:18 paste: a Lille
 * reply (from the pre-1.0.6 build still on his phone)
 * that explained X-account creation's blockers in a
 * table and closed by claiming the flow was "paused at
 * step 35" because it was "waiting for the specific
 * details you want to use (handle, email, password,
 * phone)" — the identity-ask in declarative mood, which
 * the 1.0.6 request-frame detector could miss. And his
 * question over it — "how can we help her over all
 * this" — is itself the needs question this build makes
 * deterministic.
 */
class Lille107Test {

    // ---------- 1. the explainer identity-ask ----------

    /** The pasted reply, verbatim (blockers table +
     *  closing paragraph). */
    private val explainerAsk = """
        The main things that tend to slow down or block an X‑account creation are:

        | Step | Why it can be difficult |
        |------|--------------------------|
        | Choosing an available handle | Many short or common names are already taken, so the system may reject the handle you suggest and ask you to try another one. |
        | Email verification | X sends a confirmation link to the address you provide. If the mailbox isn't instantly reachable (e.g., Gmail sync delay, spam filtering, two‑step verification) the link may not arrive quickly, causing the flow to pause. |
        | CAPTCHA / Bot detection | X frequently presents a visual or audio CAPTCHA to confirm you're human. That requires a manual tap or solve, which the automated flow can't complete on its own. |
        | Phone‑number verification | X often asks for a phone number after the email step. If you don't have a number ready, or if the number has been used on another X account, the verification will fail or be delayed. |
        | Rate‑limit / “Suspicious activity” blocks | Creating an account from a fresh IP or from a device that hasn't logged into X before can trigger additional security checks (e.g., “We've detected unusual activity”). This forces a manual review or extra verification steps. |
        | Terms‑of‑service prompts | Occasionally X shows pop‑ups about age, location, or policy acceptance that need a user click before the next screen appears. |

        Because I'm waiting for the specific details you want to use (handle, email, password, phone), the flow has been paused at step 35. Once I have those inputs I can move past the form‑filling stage; the remaining hurdles (CAPTCHA, phone verification) will still require a manual tap or a code you retrieve from the verification SMS/email. Let me know the missing details and we can continue.
    """.trimIndent()

    @Test
    fun theExplainerAskFailsTheChatScreen() {
        val v = ChatOutputGuard.verdict(explainerAsk)
        assertTrue(v is ChatOutputGuard.Verdict.Rejected)
        assertEquals(
            "identity-ask",
            (v as ChatOutputGuard.Verdict.Rejected).reason
        )
    }

    @Test
    fun theExplainerAskFailsTheReportScreen() {
        val v = ChatOutputGuard.verdictForReport(explainerAsk)
        assertTrue(v is ChatOutputGuard.Verdict.Rejected)
        assertEquals(
            "identity-ask",
            (v as ChatOutputGuard.Verdict.Rejected).reason
        )
    }

    @Test
    fun explainerDependenceCatchesOtherWordings() {
        val variants = listOf(
            // continues-once-given, single item
            "Once I have your email and password, I can " +
                "finish the sign-up.",
            // paused-until-given, single item
            "I'm paused until you give me the phone " +
                "number for the account.",
            // waiting-for-you-to-provide
            "It's waiting for you to provide the password " +
                "before it can go on.",
            // let-me-know-the-missing-details set framing
            "Let me know the missing details — the email " +
                "and the phone — and I'll continue."
        )
        for (v in variants) {
            val verdict = ChatOutputGuard.verdict(v)
            assertTrue(
                "explainer variant must be rejected: $v",
                verdict is ChatOutputGuard.Verdict.Rejected &&
                    verdict.reason == "identity-ask"
            )
        }
    }

    @Test
    fun waitingOnTheWorldIsNotAnAsk() {
        // Waiting for a thing to ARRIVE — no details-set,
        // no giving clause — is her own work narrative,
        // not a request for her identity items.
        assertTrue(
            ChatOutputGuard.isClean(
                "I'm waiting for the confirmation email to " +
                    "land in her mailbox; once it arrives " +
                    "she continues on her own."
            )
        )
        // The details-set shapes need identity items in
        // the prose: an innocent "details" passes.
        assertTrue(
            ChatOutputGuard.isClean(
                "I'm waiting for the details of the " +
                    "shipment before I file the summary."
            )
        )
    }

    // ---------- 2. the needs / blockers probe ----------

    @Test
    fun needsShapesAreRecognised() {
        for (probe in listOf(
            "how can I help",
            "How can I help?",
            "how can i help you",
            "can I help you",
            "what do you need",
            "What do you need?",
            "what do you need from me",
            "do you need anything",
            "is there anything you need",
            "what's blocking you",
            "what is stopping you",
            "anything blocking you",
            "is something stopping you"
        )) {
            assertTrue(
                "needs probe: $probe",
                StatusProbe.isNeedsProbe(probe)
            )
        }
    }

    @Test
    fun needsLookalikesAreNotIntercepted() {
        for (notProbe in listOf(
            // The pinned boundary: a job's inputs are an
            // open-class object — a question for the
            // brain, not a control question.
            "what do you need for the report",
            "what do you need for the report?",
            "what do you need to finish the form",
            // The USER's need, not hers.
            "i need help",
            "help me",
            "i need you",
            // Other deterministic families / plain chat.
            "why did you stop",
            "how are you",
            "update",
            ""
        )) {
            assertFalse(
                "not a needs probe: $notProbe",
                StatusProbe.isNeedsProbe(notProbe)
            )
        }
    }

    private fun task(
        state: String,
        chatId: String = "chat-1",
        steps: Int = 0,
        note: String = "",
        wallStep: String = "",
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
        nextRetryAt: Long = 0L,
        approval: Boolean = false,
        takeover: Boolean = false
    ): JSONObject {
        val t = JSONObject()
            .put("id", "task-x")
            .put("title", "Create Lille's X account")
            .put("text", "Create Lille's X account")
            .put("state", state)
            .put("steps", steps)
            .put("note", note)
            .put("nextRetryAt", nextRetryAt)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
        if (chatId.isNotEmpty()) t.put(ChatTaskLink.ORIGIN_FIELD, chatId)
        if (wallStep.isNotEmpty()) t.put("wallStep", wallStep)
        if (approval) t.put(PlanAct.APPROVAL_KIND_FIELD, "plan")
        if (takeover) t.put("browserTakeover", true)
        return t
    }

    @Test
    fun needsGateFollowsTheNewestRecordsState() {
        val parked = task(TaskStates.PARKED_AT_WALL, updatedAt = 500L)
        assertTrue(
            StatusProbe.needsTaskFor(listOf(parked), "chat-1") === parked
        )
        // Finished or failed newest work: no interception.
        assertNull(
            StatusProbe.needsTaskFor(
                listOf(task(TaskStates.COMPLETED)), "chat-1"
            )
        )
        assertNull(
            StatusProbe.needsTaskFor(
                listOf(task(TaskStates.FAILED)), "chat-1"
            )
        )
        // Another chat's parked work is not this chat's.
        assertNull(
            StatusProbe.needsTaskFor(
                listOf(task(TaskStates.PARKED_AT_WALL, chatId = "chat-2")),
                "chat-1"
            )
        )
    }

    @Test
    fun parkedNeedsAnswerNamesThePanelStepAndNothingElse() {
        val answer = StatusProbe.needsAnswerFor(
            task(
                TaskStates.PARKED_AT_WALL, steps = 35,
                wallStep = "Complete the human check on x.com."
            ),
            now = 61_000L
        )
        assertTrue(answer.contains("Create Lille's X account"))
        assertTrue(answer.contains("Complete the human check on x.com."))
        assertTrue(answer.contains("no details or inputs from you"))
    }

    @Test
    fun needsAnswersPerStateComeFromTheRecord() {
        val now = 61_000L
        val running = StatusProbe.needsAnswerFor(
            task(TaskStates.RUNNING, steps = 3), now
        )
        assertTrue(running.startsWith("Nothing —"))
        assertTrue(running.contains("working on"))

        val resting = StatusProbe.needsAnswerFor(
            task(
                TaskStates.WAITING_ROUTE, steps = 35,
                nextRetryAt = 181_000L
            ),
            now
        )
        assertTrue(resting.startsWith("Nothing —"))
        assertTrue(resting.contains("continues on her own"))

        val paused = StatusProbe.needsAnswerFor(
            task(TaskStates.PAUSED, steps = 35), now
        )
        assertTrue(paused.startsWith("Nothing —"))
        assertTrue(paused.contains("resume"))

        val approval = StatusProbe.needsAnswerFor(
            task(TaskStates.PAUSED, steps = 2, approval = true), now
        )
        assertTrue(approval.contains("approve"))

        val takeover = StatusProbe.needsAnswerFor(
            task(TaskStates.PAUSED, steps = 2, takeover = true), now
        )
        assertTrue(takeover.contains("hand it back"))

        val queued = StatusProbe.needsAnswerFor(
            task(TaskStates.QUEUED), now
        )
        assertTrue(queued.contains("starts on its own"))

        val runner = StatusProbe.needsAnswerFor(
            task(TaskStates.NEEDS_RUNNER), now
        )
        assertTrue(runner.contains("computer lane"))
    }

    // ---------- 3. the self-denial verdict ----------

    /** The 2026-11-27 screenshot's answer, after he had
     *  corrected her twice: a flat denial of her own
     *  identity kit. */
    private val selfDenial = "I don't have a personal " +
        "email address or password of my own to use for " +
        "an X account. To create the profile I would " +
        "need you to provide:\n" +
        "1. An email address to sign up with\n" +
        "2. A password for the account"

    @Test
    fun theSelfDenialFailsChatAndReportScreens() {
        val v = ChatOutputGuard.verdict(selfDenial)
        assertTrue(v is ChatOutputGuard.Verdict.Rejected)
        assertEquals(
            "identity-denial",
            (v as ChatOutputGuard.Verdict.Rejected).reason
        )
        val rv = ChatOutputGuard.verdictForReport(selfDenial)
        assertTrue(rv is ChatOutputGuard.Verdict.Rejected)
        assertEquals(
            "identity-denial",
            (rv as ChatOutputGuard.Verdict.Rejected).reason
        )
    }

    @Test
    fun selfDenialCatchesOtherWordings() {
        val variants = listOf(
            "I have no email address of my own to sign " +
                "up with.",
            "I don't have my own password for X.",
            "There's no email of my own on this phone.",
            "I will need you to provide an email address " +
                "for the account."
        )
        for (v in variants) {
            val verdict = ChatOutputGuard.verdict(v)
            assertTrue(
                "denial variant must be rejected: $v",
                verdict is ChatOutputGuard.Verdict.Rejected &&
                    verdict.reason == "identity-denial"
            )
        }
    }

    @Test
    fun nonDenialsAboutPossessionPass() {
        // Denying possession of the USER's data is not a
        // denial of her kit — no own-marker, no hit.
        assertTrue(
            ChatOutputGuard.isClean(
                "I don't have your email address saved " +
                    "on this phone."
            )
        )
        // The grounded fallback lines are themselves
        // clean — the guard can never reject its own
        // landing text.
        assertTrue(ChatOutputGuard.isClean(ChatOutputGuard.DENIAL_LINE))
        assertTrue(ChatOutputGuard.isClean(ChatOutputGuard.IDENTITY_LINE))
    }

    // ---------- 4. steering a packaged lane ----------

    private fun xTask(
        state: String, steps: Int = 35
    ): JSONObject = task(state, steps = steps)

    @Test
    fun packagedLanesAreRecognisedByTitle() {
        assertTrue(TaskSteer.isPackaged(xTask(TaskStates.RUNNING)))
        val general = JSONObject()
            .put("title", "")
            .put("text", "Summarise the group chat")
            .put("state", TaskStates.RUNNING)
        assertFalse(TaskSteer.isPackaged(general))
    }

    @Test
    fun conductMessagesAreRecognised() {
        // The screenshot exchange, verbatim shapes.
        assertTrue(
            TaskSteer.isConductMessage("for your username try lilleetbtc")
        )
        assertTrue(
            TaskSteer.isConductMessage(
                "email and password should be handled by you end to end"
            )
        )
        assertTrue(
            TaskSteer.isConductMessage(
                "this is your personal x account you should " +
                    "sort your own email and password"
            )
        )
        assertTrue(
            TaskSteer.isConductMessage("create an email for yourself")
        )
        // No conduct reference: flows on as before.
        assertFalse(TaskSteer.isConductMessage("thanks, that worked!"))
        assertFalse(
            TaskSteer.isConductMessage("post this announcement for me")
        )
    }

    @Test
    fun steerAnswersComeFromTheRecordAndStayClean() {
        val running = TaskSteer.steerAnswerFor(
            xTask(TaskStates.RUNNING), identityReferenced = true
        )
        // The fact the screenshot's replies denied by
        // omission: the lane was ALREADY running.
        assertTrue(running.contains("already running"))
        assertTrue(running.contains("Create Lille's X account"))
        assertTrue(running.contains("vault"))
        assertTrue(ChatOutputGuard.isClean(running))
        assertFalse(NarrationGuard.containsNarration(running))

        val paused = TaskSteer.steerAnswerFor(
            xTask(TaskStates.PAUSED), identityReferenced = false
        )
        assertTrue(paused.contains("paused"))
        assertTrue(ChatOutputGuard.isClean(paused))

        val parked = TaskSteer.steerAnswerFor(
            xTask(TaskStates.PARKED_AT_WALL),
            identityReferenced = true
        )
        assertTrue(parked.contains("browser panel"))
        assertTrue(ChatOutputGuard.isClean(parked))
    }

    // ---------- 5. no confirmation question ----------

    @Test
    fun theConfirmationQuestionIsRejectedAsAReply() {
        val v = ChatOutputGuard.verdict(JobDetect.QUESTION)
        assertTrue(v is ChatOutputGuard.Verdict.Rejected)
        assertEquals(
            "confirmation-question",
            (v as ChatOutputGuard.Verdict.Rejected).reason
        )
        // Merely quoting it inside a real answer passes.
        assertTrue(
            ChatOutputGuard.isClean(
                "You asked whether to take that on as a " +
                    "task, so here is where it stands."
            )
        )
    }

    @Test
    fun needsAnswersAreThemselvesGuardClean() {
        // The deterministic path can never produce the
        // failure it replaces: every needs answer passes
        // the chat guard and the narration screen, and
        // none of them asks him for anything in chat.
        val now = 61_000L
        val answers = listOf(
            StatusProbe.needsAnswerFor(
                task(
                    TaskStates.PARKED_AT_WALL, steps = 35,
                    wallStep = "Complete the human check on x.com."
                ), now
            ),
            StatusProbe.needsAnswerFor(
                task(TaskStates.RUNNING, steps = 3), now
            ),
            StatusProbe.needsAnswerFor(
                task(
                    TaskStates.WAITING_ROUTE, steps = 35,
                    nextRetryAt = 181_000L
                ), now
            ),
            StatusProbe.needsAnswerFor(
                task(TaskStates.WAITING_NETWORK, steps = 1), now
            ),
            StatusProbe.needsAnswerFor(
                task(TaskStates.PAUSED, steps = 35), now
            ),
            // The budget-paused X record, note and all —
            // the screenshot's exact situation.
            StatusProbe.needsAnswerFor(
                task(
                    TaskStates.PAUSED, steps = 35,
                    note = "Step budget (40) reached — " +
                        "resume to continue."
                ), now
            ),
            StatusProbe.needsAnswerFor(
                task(TaskStates.PAUSED, steps = 2, approval = true), now
            ),
            StatusProbe.needsAnswerFor(
                task(TaskStates.PAUSED, steps = 2, takeover = true), now
            ),
            StatusProbe.needsAnswerFor(task(TaskStates.QUEUED), now),
            StatusProbe.needsAnswerFor(task(TaskStates.NEEDS_RUNNER), now)
        )
        for (answer in answers) {
            assertTrue(
                "guard-clean: $answer",
                ChatOutputGuard.isClean(answer)
            )
            assertFalse(
                "narration-clean: $answer",
                NarrationGuard.containsNarration(answer)
            )
            assertFalse(
                "no chat ask: $answer",
                answer.lowercase().contains("let me know")
            )
        }
    }
}
