package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.13 pins — CHAT IS THE ENGINE, INTELLIGENCE-LED
 * (Ethuram's screenshots + rulings, 2026-11-28).
 *
 * The failures: (1) "I need you to edit on github so
 * all user of lille refferal link comes in a shortened
 * for…" got a conversational intake list — "Please
 * share the repository URL… Let me know which file(s)
 * contain the referral-link logic… tell me the API key
 * or any credentials…" A task was taken as a
 * conversation, asking for facts that are HERS.
 * (2) "bring up mu fluid wallet" got a narrated launch
 * and "just call the ui" a pure hallucinated action
 * claim ("I'm launching the Fluid wallet UI now. You
 * should see the wallet screen appear…"). His verdict:
 * "This is just hallucinations."
 *
 * His ruling on the fix: "I don't want hardcodes I
 * want a smart and proficient that can distinguish
 * task and handle them proficiently." So there are NO
 * new sentence grammars in this build. The architecture
 * pinned here:
 *
 * - The deterministic layers are UNCHANGED (commands,
 *   probes, JobDetect's first stage) — the lookalikes
 *   below pin that conversation still stays
 *   conversation through them, and the screenshot
 *   message itself is pinned as NOT deterministically
 *   classified: it belongs to the classifier.
 * - THE INTENT CONTRACT: the brain classifies the
 *   residue under a strict verdict contract; [parse]
 *   and the stream gate ([displayText]) are the
 *   deterministic halves pinned here. Execution is
 *   record-first in the glue: a TASK verdict creates
 *   the record before any reply is composed, a COMMAND
 *   verdict performs the navigation, a CHAT verdict's
 *   body is the answer. The classification itself is
 *   the model's — its outcomes on a held-out set of
 *   novel phrasings are reported in the ship note,
 *   never asserted in a unit test.
 * - UI SCREENS: an execution table only (tokens →
 *   screens → lines), no parser.
 * - THE GUARD: information-ask, intake-questionnaire
 *   and action-claim verdicts (frame/state shapes) on
 *   chat AND report paths. The only asks that stay
 *   clean are ACCESS asks — what is physically his.
 * - OWN-PRODUCT TARGET + PROACTIVITY, as before.
 */
class Lille113Test {

    // ---------- the deterministic stage: unchanged ----------

    private fun assertChat(text: String) {
        val out = JobDetect.classify(text, emptyList())
        assertEquals(
            "expected NotJob for: $text — got $out",
            JobDetect.Outcome.NotJob, out
        )
    }

    @Test
    fun conversationLookalikesStayChat() {
        // Questions ABOUT work are not assignments of it.
        assertChat("What did you change on GitHub yesterday?")
        assertChat("How does the repository pattern work?")
        assertChat("Tell me about the new GitHub features")
        assertChat("What's the Discord link for the Stellar server?")
        // Instruction frames wrapped around non-jobs.
        assertChat("Can you explain what a policy gate is?")
        assertChat("I want you to know I loved the demo")
        assertChat("I need you to tell me a joke")
        // Content she produces by replying stays chat.
        assertChat("Write a tweet announcing the release")
        assertChat("Draft a tweet announcing the 0.12.3 release")
    }

    @Test
    fun screenshotMessageBelongsToTheClassifier() {
        // The deterministic stage does not cover the
        // wrapped instruction — by design (his ruling:
        // no new grammars). It reaches the brain, whose
        // IntentContract verdict classifies it; the
        // execution that follows is record-first.
        assertChat(
            "I need you to edit on github so all user of " +
                "lille refferal link comes in a shortened " +
                "for not the way it was in my ss"
        )
        assertChat("bring up mu fluid wallet")
    }

    // ---------- the intent contract: parse ----------

    @Test
    fun contractParsesTaskCommandChat() {
        assertTrue(
            IntentContract.parse("INTENT: TASK")
                is IntentContract.Verdict.Task
        )
        assertTrue(
            IntentContract.parse("intent: task\n")
                is IntentContract.Verdict.Task
        )
        val cmd = IntentContract.parse("INTENT: COMMAND fluid")
        assertTrue(cmd is IntentContract.Verdict.Command)
        assertEquals(
            UiScreens.Target.FLUID,
            (cmd as IntentContract.Verdict.Command).target
        )
        val chat = IntentContract.parse(
            "INTENT: CHAT\nThe capital of France is Paris."
        )
        assertTrue(chat is IntentContract.Verdict.Chat)
        assertEquals(
            "The capital of France is Paris.",
            (chat as IntentContract.Verdict.Chat).body
        )
    }

    @Test
    fun verdictLinesTolerateTrailingRationale() {
        // Proven necessary by the held-out run: a real
        // model answered "INTENT: TASK — the message
        // asks you to DO work…" — a verdict with an
        // explanation is still the verdict.
        assertTrue(
            IntentContract.parse(
                "INTENT: TASK — the message asks for work"
            ) is IntentContract.Verdict.Task
        )
        val chat = IntentContract.parse(
            "INTENT: CHAT — Paris is the capital of France."
        )
        assertTrue(chat is IntentContract.Verdict.Chat)
        assertEquals(
            "Paris is the capital of France.",
            (chat as IntentContract.Verdict.Chat).body
        )
        // A longer word is no verdict.
        val notVerdict = IntentContract.parse(
            "intent: taskforce meeting notes"
        )
        assertTrue(notVerdict is IntentContract.Verdict.Chat)
        assertEquals(
            "intent: taskforce meeting notes",
            (notVerdict as IntentContract.Verdict.Chat).body
        )
        // The stream gate holds a rambling TASK line too.
        assertNull(
            IntentContract.displayText(
                "INTENT: TASK — the message asks for work"
            )
        )
        // A leading think block is reasoning, not reply
        // text: dropped before the scan, in the parse
        // and in the stream. (Shape from the held-out
        // Horde run, the explanation case.)
        val thinky = IntentContract.parse(
            "<think>\nThinking Process: this is a request " +
                "for explanation.\n</think>\n" +
                "INTENT: CHAT\nWalletConnect is how apps " +
                "talk to wallets."
        )
        assertTrue(thinky is IntentContract.Verdict.Chat)
        assertEquals(
            "WalletConnect is how apps talk to wallets.",
            (thinky as IntentContract.Verdict.Chat).body
        )
        assertNull(IntentContract.displayText("<think>\nreasoning…"))
        // A weak model may echo the contract, then settle:
        // the first real verdict line in the window wins
        // and the preamble is never the answer. (Shape
        // from the held-out Horde run, case 4.)
        assertTrue(
            IntentContract.parse(
                "INTENT: INTENT: COMMAND wallet|fluid|settings|chats —\n" +
                    "\n" +
                    "Wait, that's not right. The correct " +
                    "classification should be:\n" +
                    "INTENT: TASK — the user is asking for " +
                    "live work"
            ) is IntentContract.Verdict.Task
        )
    }

    @Test
    fun contractUnknownScreenIsHonestNotNarrated() {
        assertTrue(
            IntentContract.parse("INTENT: COMMAND photos")
                is IntentContract.Verdict.UnknownCommand
        )
        assertTrue(
            IntentContract.parse("INTENT: COMMAND")
                is IntentContract.Verdict.UnknownCommand
        )
    }

    @Test
    fun contractlessReplyIsChatWhole() {
        // A reply that ignores the contract keeps the
        // pre-1.0.13 behaviour: the whole text is the
        // answer, still guard-screened downstream.
        val v = IntentContract.parse("The capital is Paris.")
        assertTrue(v is IntentContract.Verdict.Chat)
        assertEquals(
            "The capital is Paris.",
            (v as IntentContract.Verdict.Chat).body
        )
    }

    // ---------- the intent contract: the stream gate ----------

    @Test
    fun streamGateHoldsVerdictsAndTaskText() {
        // A verdict still forming shows nothing.
        assertNull(IntentContract.displayText("INTENT: TAS"))
        assertNull(IntentContract.displayText("intent: command wal"))
        // A TASK verdict's narration is never displayed —
        // its reply comes from the record, not the stream.
        assertNull(
            IntentContract.displayText(
                "INTENT: TASK\nI'm starting a task to open " +
                    "your Fluid wallet…"
            )
        )
        assertNull(
            IntentContract.displayText(
                "INTENT: COMMAND fluid\nI'm launching the " +
                    "Fluid wallet UI now."
            )
        )
        // A CHAT verdict streams its body, verdict stripped.
        assertEquals(
            "The capital of France is Paris.",
            IntentContract.displayText(
                "INTENT: CHAT\nThe capital of France is Paris."
            )
        )
        // A contract-less reply streams as before once
        // its first line is plainly not a verdict.
        val plain = "The capital of France is Paris, and it " +
            "has been for centuries."
        assertEquals(plain, IntentContract.displayText(plain))
    }

    // ---------- UI screens: the execution table ----------

    @Test
    fun uiScreenTokensAndLines() {
        assertEquals(
            UiScreens.Target.WALLET,
            UiScreens.targetForToken("wallet")
        )
        assertEquals(
            UiScreens.Target.FLUID,
            UiScreens.targetForToken("fluid")
        )
        assertEquals(
            UiScreens.Target.SETTINGS,
            UiScreens.targetForToken("settings")
        )
        assertEquals(
            UiScreens.Target.CHATS,
            UiScreens.targetForToken("chats")
        )
        assertNull(UiScreens.targetForToken("photos"))
        assertEquals(
            "Fluid wallet open.",
            UiScreens.planFor(UiScreens.Target.FLUID).reply
        )
        assertEquals(
            "Wallet open.",
            UiScreens.planFor(UiScreens.Target.WALLET).reply
        )
    }

    // ---------- the own-product target ----------

    @Test
    fun ownProductRepoJobStampsHerRepository() {
        val text = "I need you to edit on github so all user of " +
            "lille refferal link comes in a shortened for"
        val plan = TaskTools.planFor(text)
        assertTrue(
            TaskTools.ToolClass.REPO_CODE in plan.requires
        )
        assertEquals(
            TaskTools.OWN_REPO,
            TaskTools.ownRepoTarget(text, plan)
        )
        assertEquals("Townsvilledh/free-agent", TaskTools.OWN_REPO)
    }

    @Test
    fun ownRepoTargetStaysNullForTheWorldsWork() {
        val worldPlan = TaskTools.planFor(
            "Look at the GitHub repository and summarise it"
        )
        assertEquals(
            null,
            TaskTools.ownRepoTarget(
                "Look at the GitHub repository and summarise it",
                worldPlan
            )
        )
        val mailPlan = TaskTools.planFor("Check my Gmail inbox")
        assertEquals(
            null,
            TaskTools.ownRepoTarget("Lille, check my Gmail inbox", mailPlan)
        )
    }

    @Test
    fun declarationStatesTheOwnRepoTarget() {
        val text = "Edit Lille's referral link handling on GitHub"
        val record = JSONObject().put("text", text)
        val plan = TaskTools.planFor(text)
        TaskTools.stampPlan(record, plan)
        TaskTools.ownRepoTarget(text, plan)?.let {
            record.put(TaskTools.TARGET_REPO_FIELD, it)
        }
        val declaration = TaskTransparency.declarationFor(record)!!
        assertTrue(declaration.contains("her own repository"))
        assertTrue(declaration.contains("Townsvilledh/free-agent"))
    }

    @Test
    fun capabilitySheetCarriesTheRepoFactAndTheBoundary() {
        val sheet = CapabilitySheet.render(
            CapabilitySheet.Facts(
                gmailConnected = false,
                xLine = "she can create her own X account",
                walletFamilies = listOf("Ethereum"),
                lanConfigured = false
            )
        )
        assertTrue(sheet.contains(TaskTools.OWN_REPO))
        assertTrue(sheet.contains("ask the user for INFORMATION"))
        assertTrue(sheet.contains("physically his"))
    }

    @Test
    fun contractCarriesTheBoundary() {
        // The classifier's instructions state the same
        // boundary the guard enforces: verdicts for work
        // and screens, no narration, no information asks.
        assertTrue(IntentContract.INSTRUCTION.contains("INTENT: TASK"))
        assertTrue(IntentContract.INSTRUCTION.contains("INTENT: COMMAND"))
        assertTrue(IntentContract.INSTRUCTION.contains("INTENT: CHAT"))
        assertTrue(IntentContract.INSTRUCTION.contains("Never narrate"))
        assertTrue(IntentContract.INSTRUCTION.contains("physically his"))
    }

    // ---------- the guard: information asks blocked ----------

    private val SCREENSHOT_REPLY =
        "I'm starting a GitHub-editing task to replace the " +
            "current referral-link handling with a shortened " +
            "version.\n\n**Next steps**\n" +
            "1. Please share the repository URL (or the " +
            "specific repo name) and the branch you'd like " +
            "me to work on.\n" +
            "2. Let me know which file(s) contain the " +
            "referral-link logic (or point me to the code " +
            "segment).\n" +
            "3. If you have a preferred URL-shortening " +
            "service (e.g., Bitly, TinyURL, a custom " +
            "domain), tell me the API key or any credentials " +
            "you'd like me to use; otherwise I'll generate " +
            "short links locally with a simple hash-based " +
            "approach.\n\n" +
            "Once I have that information I'll clone the " +
            "repo, run the tests, and open a pull request " +
            "for your review. Let me know the repo details " +
            "and any preferences, and I'll proceed."

    private fun assertRejected(text: String, reason: String) {
        val v = ChatOutputGuard.verdict(text)
        assertTrue(
            "expected rejection ($reason) for: $text — got $v",
            v is ChatOutputGuard.Verdict.Rejected &&
                (v as ChatOutputGuard.Verdict.Rejected).reason == reason
        )
        val rv = ChatOutputGuard.verdictForReport(text)
        assertTrue(
            "report path must reject too: $text",
            rv is ChatOutputGuard.Verdict.Rejected
        )
    }

    @Test
    fun screenshotReplyIsAnInformationAsk() {
        assertRejected(SCREENSHOT_REPLY, "information-ask")
    }

    @Test
    fun informationAskVariantsAreBlocked() {
        assertRejected(
            "Please share the Discord link so I can join the server.",
            "information-ask"
        )
        assertRejected(
            "Send me the GitHub link for the repo and I'll look at it.",
            "information-ask"
        )
        assertRejected(
            "Before I start, send me the repo name and tell " +
                "me which files hold the invite logic.",
            "information-ask"
        )
        assertRejected(
            "What is your repository address? I need it to begin.",
            "information-ask"
        )
        // A code is never asked for in chat — he types it
        // into her panel himself.
        assertRejected(
            "Tell me the code from your authenticator and I'll enter it.",
            "information-ask"
        )
    }

    @Test
    fun intakeQuestionnaireIsBlocked() {
        assertRejected(
            "To get going: tell me your budget, let me know " +
                "your dates, please tell me your airline " +
                "preference, and give me your passport name.",
            "intake-ask"
        )
    }

    // ---------- the guard: action claims blocked ----------

    @Test
    fun hallucinatedActionClaimsAreBlocked() {
        // The 2026-11-28 screenshot's two replies.
        assertRejected(
            "I'm starting a task to open your Fluid " +
                "wallet. I'll launch the wallet UI that's " +
                "already set up with your Ethereum, Bitcoin, " +
                "and Solana accounts.",
            "action-claim"
        )
        assertRejected(
            "I'm launching the Fluid wallet UI now. You " +
                "should see the wallet screen appear on " +
                "your phone momentarily.",
            "action-claim"
        )
        // A UI claim in a report is unbacked by
        // construction — no lane opens a UI.
        val rv = ChatOutputGuard.verdictForReport(
            "I've opened your wallet for you."
        )
        assertTrue(rv is ChatOutputGuard.Verdict.Rejected)
    }

    // ---------- the guard: legitimate text stays clean ----------

    private fun assertClean(text: String) {
        assertTrue(
            "expected clean for: $text — got " +
                ChatOutputGuard.verdict(text),
            ChatOutputGuard.isClean(text)
        )
        assertTrue(
            "report path must stay clean too: $text",
            ChatOutputGuard.isCleanForReport(text)
        )
    }

    @Test
    fun accessAsksStayClean() {
        // The parked sign-in: his hands, not a fact.
        assertClean(
            "Sign in to GitHub in the browser panel so I " +
                "can push the change — tap 'Open Lille's " +
                "browser', sign in with your own account, " +
                "then tap 'Done — hand back to Lille'."
        )
        // A code he types into the panel himself.
        assertClean(
            "Type the code from your authenticator into " +
                "the panel, then hand back."
        )
        // An approval.
        assertClean("Say approve and I'll post it — nothing goes out without your word.")
        // A single preference question is conversation.
        assertClean("Let me know if you'd like the summary shorter.")
    }

    @Test
    fun recordBackedLinesStayClean() {
        // Step-backed lane summaries (report path): the
        // finish machinery grades them against the
        // record; the guard's UI-claim screen does not
        // touch lane work.
        assertClean("Opened the site and read the submission rules.")
        assertTrue(
            ChatOutputGuard.isCleanForReport(
                "I opened the GitHub page and read the referral code."
            )
        )
        // Claims attributed to the record, in chat.
        assertClean(
            "The task opened the DoraHacks page at step " +
                "3 — it's resting there now."
        )
        // Rhetorical openings claim no executed action.
        assertClean("I'm opening with the headline numbers, then the detail.")
    }

    @Test
    fun groundedLinesAreClean() {
        assertClean(ChatOutputGuard.OWN_FACTS_LINE)
        assertClean(ChatOutputGuard.ACTION_LINE)
        assertClean(CapabilitySheet.GROUNDED_LINE)
        assertClean(ChatOutputGuard.IDENTITY_LINE)
        assertClean(ChatOutputGuard.DENIAL_LINE)
        assertClean(UiScreens.UNKNOWN_LINE)
    }

    // ---------- proactivity: routed reports state the next move ----------

    private fun chatTask(state: String): JSONObject =
        JSONObject()
            .put("id", "task-113")
            .put("text", "do the thing")
            .put("state", state)
            .put(ChatTaskLink.ORIGIN_FIELD, "chat-1")

    @Test
    fun needsRunnerReportStatesHerNextMove() {
        val text = ChatTaskLink.reportFor(
            chatTask(TaskStates.NEEDS_RUNNER),
            TaskStates.NEEDS_RUNNER
        )!!
        assertTrue(text.contains("runner lane"))
        assertTrue(text.contains("Next from me"))
    }

    @Test
    fun routedPartialReportStatesHerNextMove() {
        val t = chatTask(TaskStates.COMPLETED)
        t.put("partialCompletion", true)
        TaskTools.stampPlan(
            t, TaskTools.planFor("Edit the referral link handling on GitHub")
        )
        val text = ChatTaskLink.reportFor(t, TaskStates.COMPLETED)!!
        assertTrue(text.contains("Handed to the computer lane"))
        assertTrue(text.contains("code and repository work"))
        assertTrue(text.contains("Next from me"))
        assertFalse(text.contains("Done — that work is finished."))
    }
}
