package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.6 — the composer never locks, the targeted kill,
 * and reports screened like chat. Born of Ethuram's
 * 2026-11-27 screenshot of 1.0.5: the Agentware task's
 * report was a homework pack (a numbered menu of
 * deliverables for HIM to execute), his "not this task,
 * kill this completely" sat at "…thinking" (no targeted
 * kill existed), and his typed next message sat over a
 * STOP button — unsendable while she was thinking.
 */
class Lille106Test {

    // ---------- 1. the composer state machine ----------

    @Test
    fun composerFacesFollowContentAndFlight() {
        // text + idle → Send (starts now)
        assertEquals(
            ComposerAction.Action.SEND,
            ComposerAction.actionFor(hasContent = true, busy = false)
        )
        // text + busy → Send (queued in order — never Stop
        // over sendable text: the screenshot's defect)
        assertEquals(
            ComposerAction.Action.SEND_QUEUE,
            ComposerAction.actionFor(hasContent = true, busy = true)
        )
        // empty + busy → Stop (its only face)
        assertEquals(
            ComposerAction.Action.STOP,
            ComposerAction.actionFor(hasContent = false, busy = true)
        )
        // empty + idle → Send (inert, as before)
        assertEquals(
            ComposerAction.Action.SEND,
            ComposerAction.actionFor(hasContent = false, busy = false)
        )
    }

    @Test
    fun composerIconsAndDescriptions() {
        assertEquals(
            "ic_stop",
            ComposerAction.iconName(ComposerAction.Action.STOP)
        )
        assertEquals(
            "ic_send",
            ComposerAction.iconName(ComposerAction.Action.SEND)
        )
        assertEquals(
            "ic_send",
            ComposerAction.iconName(ComposerAction.Action.SEND_QUEUE)
        )
        assertEquals(
            "Stop",
            ComposerAction.description(ComposerAction.Action.STOP)
        )
        assertEquals(
            "Send message",
            ComposerAction.description(ComposerAction.Action.SEND_QUEUE)
        )
    }

    @Test
    fun twoQueuedMessagesProcessInOrderWithBadgeLifecycle() {
        val q = ChatQueue()
        assertEquals(0, q.offer("first"))
        q.beginFlight()
        assertEquals(1, q.offer("second"))
        assertEquals(2, q.offer("third"))
        // Badges are positions in the live queue: the head
        // wears "sends next", the rest count down as the
        // queue drains — never stale, never reordered.
        assertEquals("queued — sends next", ChatQueue.badge(1))
        assertEquals("queued — 2 in line", ChatQueue.badge(2))
        q.endFlight()
        assertEquals("second", q.startNext())
        assertEquals(listOf("third"), q.queuedTexts())
        // "third" is the head now: its badge is position 1.
        assertEquals(
            "queued — sends next",
            ChatQueue.badge(q.queuedTexts().indexOf("third") + 1)
        )
        q.endFlight()
        assertEquals("third", q.startNext())
        q.endFlight()
        assertNull(q.startNext())
    }

    // ---------- 2. targeted kill: parsing ----------

    @Test
    fun killAllShapesStillParseAsAll() {
        assertEquals(
            TaskControl.KillCommand.All,
            TaskControl.parseKill("kill all task")
        )
        assertEquals(
            TaskControl.KillCommand.All,
            TaskControl.parseKill("Kill all tasks!")
        )
    }

    @Test
    fun killThisShapesParseTargeted() {
        assertEquals(
            TaskControl.KillCommand.Targeted(null, false),
            TaskControl.parseKill("kill this")
        )
        assertEquals(
            TaskControl.KillCommand.Targeted(null, false),
            TaskControl.parseKill("stop this task")
        )
        assertEquals(
            TaskControl.KillCommand.Targeted(null, false),
            TaskControl.parseKill("cancel this")
        )
        assertEquals(
            TaskControl.KillCommand.Targeted(null, true),
            TaskControl.parseKill("kill this completely")
        )
    }

    @Test
    fun theScreenshotsKillParsesTargetedCompletely() {
        // His exact words, both comma spellings: the
        // Agentware pack was on screen; "not this task"
        // names what NOT to kill, the command follows it.
        assertEquals(
            TaskControl.KillCommand.Targeted(null, true),
            TaskControl.parseKill("not this task,kill this completely")
        )
        assertEquals(
            TaskControl.KillCommand.Targeted(null, true),
            TaskControl.parseKill("not this task, kill this completely")
        )
        assertEquals(
            TaskControl.KillCommand.Targeted(null, false),
            TaskControl.parseKill("not this task, kill this")
        )
    }

    @Test
    fun namedKillParsesItsHint() {
        assertEquals(
            TaskControl.KillCommand.Targeted("x", false),
            TaskControl.parseKill("kill the X task")
        )
        assertEquals(
            TaskControl.KillCommand.Targeted("agentware", false),
            TaskControl.parseKill("kill the agentware task")
        )
        assertEquals(
            TaskControl.KillCommand.Targeted("x account", true),
            TaskControl.parseKill("kill the x account task completely")
        )
        assertEquals(
            TaskControl.KillCommand.Targeted("agentware", false),
            TaskControl.parseKill("stop agentware task")
        )
    }

    @Test
    fun nonCommandsDoNotParseAsKills() {
        // No task noun: names no work, stays conversation.
        assertNull(TaskControl.parseKill("kill the lights"))
        // Not whole-message shapes: discussion, not command.
        // (v1.0.11, G4: "please kill this task now" IS a
        // command now — edge politeness is stripped; the
        // discussion shape that stays rejected is the one
        // whose verb still does not lead after the strip.)
        assertNull(TaskControl.parseKill("tell me please kill this task"))
        assertNull(TaskControl.parseKill("kill this idea, it is not working"))
        assertNull(TaskControl.parseKill("why did you stop"))
        assertNull(TaskControl.parseKill("kill time"))
    }

    // ---------- 2. targeted kill: resolution ----------

    private fun rec(
        id: String,
        title: String = "",
        kind: String = "",
        text: String = "",
        state: String,
        created: Long,
        updated: Long,
        chat: String = "chat-1",
        archived: Boolean = false
    ): JSONObject {
        val t = JSONObject()
            .put("id", id)
            .put("state", state)
            .put("createdAt", created)
            .put("updatedAt", updated)
            .put(ChatTaskLink.ORIGIN_FIELD, chat)
        if (title.isNotEmpty()) t.put("title", title)
        if (kind.isNotEmpty()) t.put("kind", kind)
        if (text.isNotEmpty()) t.put("text", text)
        if (archived) t.put(TaskControl.ARCHIVED_FIELD, true)
        return t
    }

    private fun xTask(updated: Long = 500L) = rec(
        "task-x",
        title = "Create Lille's X account",
        kind = "browser",
        text = "Create Lille's X account",
        state = TaskStates.PAUSED,
        created = 100L, updated = updated
    )

    private fun agentwareTask(
        state: String = TaskStates.COMPLETED,
        updated: Long = 400L,
        archived: Boolean = false
    ) = rec(
        "task-a",
        text = "Lille, this is a standing order, not a " +
            "discussion. Execute the Agentware 2026 entry " +
            "end to end. First join the Discord server " +
            "and note the perk codes.",
        state = state,
        created = 200L, updated = updated,
        archived = archived
    )

    @Test
    fun hintResolvesByTitleAndText() {
        val tasks = listOf(xTask(), agentwareTask())
        assertEquals(
            "task-x",
            TaskControl.resolveTarget(tasks, "chat-1", "x", null)
                ?.optString("id")
        )
        assertEquals(
            "task-a",
            TaskControl.resolveTarget(tasks, "chat-1", "agentware", null)
                ?.optString("id")
        )
        assertEquals(
            "task-a",
            TaskControl.resolveTarget(tasks, "chat-1", "discord", null)
                ?.optString("id")
        )
    }

    @Test
    fun unmatchedHintResolvesToNothing() {
        // A name that matches no record must NOT fall back
        // to the nearest task — killing the wrong work is
        // worse than the no-referent line.
        val tasks = listOf(xTask(), agentwareTask())
        assertNull(
            TaskControl.resolveTarget(tasks, "chat-1", "billing", null)
        )
    }

    @Test
    fun noHintResolvesOnScreenThenRecency() {
        val tasks = listOf(xTask(), agentwareTask())
        // The card on screen wins over the newer record.
        assertEquals(
            "task-a",
            TaskControl.resolveTarget(tasks, "chat-1", null, "task-a")
                ?.optString("id")
        )
        // No card: the most recently active work here.
        assertEquals(
            "task-x",
            TaskControl.resolveTarget(tasks, "chat-1", null, null)
                ?.optString("id")
        )
        // A card from another context (not this chat's
        // pool) falls through to recency, never across.
        assertEquals(
            "task-x",
            TaskControl.resolveTarget(tasks, "chat-1", null, "task-zzz")
                ?.optString("id")
        )
    }

    @Test
    fun liveMatchBeatsFinishedMatch() {
        val done = rec(
            "task-x-old",
            title = "Create Lille's X account",
            kind = "browser",
            text = "Create Lille's X account",
            state = TaskStates.COMPLETED,
            created = 50L, updated = 900L
        )
        val tasks = listOf(done, xTask())
        assertEquals(
            "task-x",
            TaskControl.resolveTarget(tasks, "chat-1", "x", null)
                ?.optString("id")
        )
    }

    @Test
    fun otherChatsWorkIsNeverTheTarget() {
        val foreign = rec(
            "task-f", title = "Foreign work", text = "foreign",
            state = TaskStates.RUNNING,
            created = 1L, updated = 999L, chat = "chat-2"
        )
        assertNull(
            TaskControl.resolveTarget(listOf(foreign), "chat-1", null, null)
        )
        assertNull(
            TaskControl.resolveTarget(listOf(foreign), "chat-1", "foreign", null)
        )
    }

    @Test
    fun lastStatusTaskIdReadsTheLowestCard() {
        val root = ChatData.emptyStore()
        ChatData.createChat(root, "c1", 100L)
        ChatData.upsertTaskStatus(root, "c1", "task-a", "status a", 10L, 110L)
        ChatData.appendMessage(root, "c1", "user", "hello", null, 120L)
        ChatData.upsertTaskStatus(root, "c1", "task-x", "status x", 20L, 130L)
        assertEquals("task-x", ChatData.lastStatusTaskId(root, "c1"))
        ChatData.createChat(root, "c2", 200L)
        assertNull(ChatData.lastStatusTaskId(root, "c2"))
    }

    // ---------- 2. archive: closed away means gone ----------

    @Test
    fun archivedRecordsVanishFromProbeAndHeartbeat() {
        val archived = agentwareTask(
            state = TaskStates.PARKED_AT_WALL,
            updated = 900L, archived = true
        )
        val live = xTask()
        // The probe's "latest work here" skips the archive
        // even though it is newer.
        assertEquals(
            "task-x",
            StatusProbe.latestTaskForChat(
                listOf(archived, live), "chat-1"
            )?.optString("id")
        )
        assertNull(
            StatusProbe.latestTaskForChat(listOf(archived), "chat-1")
        )
        // The heartbeat never surfaces a closed record —
        // while the same record unarchived DOES surface.
        val (quietOutcome, _) = Heartbeat.evaluate(
            null, listOf(archived), "", nowMs = 1_700_000_100_000L
        )
        assertTrue(Heartbeat.isQuiet(quietOutcome))
        val (loudOutcome, _) = Heartbeat.evaluate(
            null,
            listOf(agentwareTask(state = TaskStates.PARKED_AT_WALL)),
            "", nowMs = 1_700_000_100_000L
        )
        assertFalse(Heartbeat.isQuiet(loudOutcome))
    }

    @Test
    fun stampArchiveMarksAndRecords() {
        val t = agentwareTask()
        TaskControl.stampArchive(t, 1_700_000_000_000L)
        assertTrue(TaskControl.isArchived(t))
        assertFalse(TaskControl.isArchived(xTask()))
    }

    // ---------- 3. reports screened like chat ----------

    /** The Agentware pack from the screenshot, its
     *  distinctive phrases verbatim. */
    private val pack = "Here is what I can prepare:\n" +
        "1. A step-by-step checklist (including exactly " +
        "which pages to open, what buttons to click, and " +
        "what data to copy) so you can execute the " +
        "remaining pre-kickoff steps on your phone or " +
        "computer.\n" +
        "2. Generate all the code assets (the repo " +
        "skeleton, safety modules, sandbox fixtures, " +
        "audit-trace logic, README, AGENT.md, SAFETY.md, " +
        ".env.example) ready for you to push at kickoff.\n" +
        "3. Draft the Discord messages you'll need to " +
        "post to capture the perk codes.\n" +
        "4. Create a script that, once the repo is live, " +
        "will run the full unattended demo and capture " +
        "the video (you'll just need to run it locally).\n\n" +
        "If you'd like me to proceed with any (or all) of " +
        "those deliverables, just let me know which " +
        "you'd like first, and I'll generate them " +
        "immediately."

    @Test
    fun theHomeworkPackFailsTheReportScreen() {
        val v = ChatOutputGuard.verdictForReport(pack)
        assertTrue(v is ChatOutputGuard.Verdict.Rejected)
        assertEquals(
            "homework",
            (v as ChatOutputGuard.Verdict.Rejected).reason
        )
        // It is not a chat-verdict offence — the report
        // screen is the stricter one, by design.
        assertTrue(ChatOutputGuard.isClean(pack))
    }

    private fun reportTask(
        state: String,
        summary: String = "",
        note: String = "",
        wallStep: String = ""
    ): JSONObject {
        val t = JSONObject()
            .put("id", "task-test")
            .put("text", "do the thing")
            .put("state", state)
            .put("summary", summary)
            .put("note", note)
            .put(ChatTaskLink.ORIGIN_FIELD, "chat-1")
        if (wallStep.isNotEmpty()) t.put("wallStep", wallStep)
        return t
    }

    @Test
    fun completedReportWithholdsThePackAndStandsOnTheRecord() {
        val text = ChatTaskLink.reportFor(
            reportTask(TaskStates.COMPLETED, summary = pack),
            TaskStates.COMPLETED
        )!!
        assertTrue(text.contains("Done — that work is finished."))
        assertTrue(text.contains(ChatTaskLink.COST_LINE))
        assertFalse(text.contains("so you can execute"))
        assertFalse(text.contains("ready for you to push"))
        assertFalse(text.contains("let me know which"))
        assertFalse(text.contains("just need to run it locally"))
    }

    @Test
    fun completedReportWithholdsCapabilityDenialSummaries() {
        val denial = "I stopped because the required " +
            "browser, Gmail, Discord, GitHub, and video " +
            "tools are not available in this environment."
        val text = ChatTaskLink.reportFor(
            reportTask(TaskStates.COMPLETED, summary = denial),
            TaskStates.COMPLETED
        )!!
        assertTrue(text.contains("Done — that work is finished."))
        assertFalse(text.contains("not available in this environment"))
    }

    @Test
    fun honestSummaryStillReachesTheReport() {
        val text = ChatTaskLink.reportFor(
            reportTask(
                TaskStates.COMPLETED,
                summary = "Joined the Discord server and " +
                    "saved the perk codes to notes."
            ),
            TaskStates.COMPLETED
        )!!
        assertTrue(
            text.contains(
                "Joined the Discord server and saved the perk codes to notes."
            )
        )
    }

    @Test
    fun parkedLineNamingHerWaitPassesTheScreen() {
        // The pinned positive: a parked line names the one
        // step that is genuinely his — it is not homework.
        val step = "parked at the human check on x.com — " +
            "tap the browser panel to pass it"
        assertTrue(ChatOutputGuard.isCleanForReport(step))
        val text = ChatTaskLink.reportFor(
            reportTask(TaskStates.PARKED_AT_WALL, wallStep = step),
            TaskStates.PARKED_AT_WALL
        )!!
        assertTrue(text.contains(step))
    }

    // ---------- 3b. the detectors are patterns, not phrases ----------

    @Test
    fun killGrammarResolvesOtherWordingsIdentically() {
        // The no-hardcoding constraint: the screenshot's
        // sentences are fixtures, not the rule. Other
        // wordings with the same structure resolve the
        // same way.
        assertEquals(
            TaskControl.KillCommand.Targeted("discord", true),
            TaskControl.parseKill("stop the discord job entirely")
        )
        assertEquals(
            TaskControl.KillCommand.Targeted(null, false),
            TaskControl.parseKill("cancel that one")
        )
        assertEquals(
            TaskControl.KillCommand.All,
            TaskControl.parseKill("kill all the work")
        )
        assertEquals(
            TaskControl.KillCommand.All,
            TaskControl.parseKill("kill all tasks completely")
        )
        // And structure-less wordings still parse as
        // nothing: no verb in front, no work noun.
        assertNull(TaskControl.parseKill("stop the music"))
        assertNull(TaskControl.parseKill("kill this idea"))
    }

    @Test
    fun homeworkPatternCatchesOtherWordings() {
        val variants = listOf(
            // "I can … for you" offer + you-execute + picker
            "I can put together the full pack for you — " +
                "the migration script, the test data, and " +
                "a walkthrough you can follow to apply it " +
                "on your machine. Tell me which one you " +
                "want first and I'll draft it right away.",
            // possession frame handing him the infinitive
            "The remaining steps are yours to run: export " +
                "the keys, then push the branch when ready.",
            // readiness handed to his infinitive
            "Everything is staged and ready for you " +
                "whenever you are — the commands are listed " +
                "below for you to run through."
        )
        for (v in variants) {
            val verdict = ChatOutputGuard.verdictForReport(v)
            assertTrue(
                "homework variant must be rejected: $v",
                verdict is ChatOutputGuard.Verdict.Rejected &&
                    verdict.reason == "homework"
            )
        }
    }

    @Test
    fun probeGrammarResolvesOtherWordingsIdentically() {
        // New phrasings of the probe shapes — never in
        // any phrase table — resolve under the grammar.
        assertTrue(StatusProbe.isReasonProbe("why is the task stopped"))
        assertTrue(StatusProbe.isReasonProbe("why isn't it running"))
        assertTrue(StatusProbe.isProbe("is the work finished yet"))
        assertTrue(StatusProbe.isProbe("how far along are you"))
        // Open-class objects still break the parse.
        assertFalse(StatusProbe.isProbe("how are you"))
        assertFalse(StatusProbe.isReasonProbe("why not"))
        assertFalse(StatusProbe.isProbe("update the work email"))
    }

    // ---------- 4. she never asks for her own identity ----------

    /** The 2026-11-27 "resume" answer, its intake list
     *  reconstructed from Ethuram's screenshot. */
    private val identityAskList = "To continue I need a " +
        "few things:\n" +
        "1. Desired X handle\n" +
        "2. Email address to use for the account\n" +
        "3. A password for the account\n" +
        "4. Your phone number for verification"

    @Test
    fun theIdentityAskListFailsTheChatScreen() {
        val v = ChatOutputGuard.verdict(identityAskList)
        assertTrue(v is ChatOutputGuard.Verdict.Rejected)
        assertEquals(
            "identity-ask",
            (v as ChatOutputGuard.Verdict.Rejected).reason
        )
        // And the report screen inherits it.
        val rv = ChatOutputGuard.verdictForReport(identityAskList)
        assertTrue(rv is ChatOutputGuard.Verdict.Rejected)
        assertEquals(
            "identity-ask",
            (rv as ChatOutputGuard.Verdict.Rejected).reason
        )
    }

    @Test
    fun identityAskPatternCatchesOtherWordings() {
        val variants = listOf(
            "Before I go on, I'll need the username to " +
                "use, an email address, and a password " +
                "for the account.",
            "What is your email address?",
            "Send me the password and I'll finish the " +
                "sign-up."
        )
        for (v in variants) {
            val verdict = ChatOutputGuard.verdict(v)
            assertTrue(
                "identity-ask variant must be rejected: $v",
                verdict is ChatOutputGuard.Verdict.Rejected &&
                    verdict.reason == "identity-ask"
            )
        }
    }

    @Test
    fun identityFactsAreNotAsks() {
        // Facts about identity carry no request frame and
        // pass — the guard hunts asks, not mentions.
        assertTrue(
            ChatOutputGuard.isClean(
                "Your password lives only in your " +
                    "encrypted vault on this phone."
            )
        )
        assertTrue(
            ChatOutputGuard.isClean(
                "Her X handle is hers — it was set up " +
                    "when her account was created."
            )
        )
    }

    // ---------- 5. resume is a command, not a brain call ----------

    @Test
    fun resumeShapesParse() {
        assertEquals(
            TaskControl.ResumeCommand(null),
            TaskControl.parseResume("resume")
        )
        assertEquals(
            TaskControl.ResumeCommand(null),
            TaskControl.parseResume("Resume!")
        )
        assertEquals(
            TaskControl.ResumeCommand(null),
            TaskControl.parseResume("go on")
        )
        assertEquals(
            TaskControl.ResumeCommand(null),
            TaskControl.parseResume("continue")
        )
        assertEquals(
            TaskControl.ResumeCommand(null),
            TaskControl.parseResume("resume this")
        )
        assertEquals(
            TaskControl.ResumeCommand("x"),
            TaskControl.parseResume("resume the x task")
        )
        assertEquals(
            TaskControl.ResumeCommand("x"),
            TaskControl.parseResume("continue the x task now")
        )
    }

    @Test
    fun resumeLookalikesDoNotParse() {
        // A referent with no work noun is not her work.
        assertNull(TaskControl.parseResume("resume my download"))
        assertNull(
            TaskControl.parseResume("continue reading this article to me")
        )
        // The verb must lead, like every chat command —
        // edge politeness aside (v1.0.11, G4: "please
        // resume the task" parses now, pinned in
        // Lille111Test; a verb buried mid-sentence does
        // not).
        assertNull(TaskControl.parseResume("tell me to resume the task"))
        assertNull(TaskControl.parseResume("why did you stop"))
    }

    @Test
    fun resumeResolvesTheBudgetPausedXTask() {
        // The screenshot's flow, at the pure level: the X
        // task sits PAUSED at its step budget; his
        // "resume" resolves to exactly that record.
        val cmd = TaskControl.parseResume("resume")!!
        val target = TaskControl.resolveResumeTarget(
            listOf(xTask(), agentwareTask()), "chat-1",
            cmd.hint, null
        )
        assertEquals("task-x", target?.optString("id"))
        TaskControl.stampResume(target!!, "resume", 1_700_000_000_000L)
        assertTrue(target.optBoolean(TaskControl.RESUMED_FIELD))
    }

    @Test
    fun resumeResolvesParkedAndPrefersWorkOverQueued() {
        val parked = rec(
            "task-p", title = "Create Lille's X account",
            kind = "browser", text = "Create Lille's X account",
            state = TaskStates.PARKED_AT_WALL,
            created = 100L, updated = 300L
        )
        val queued = rec(
            "task-q", text = "queued work",
            state = TaskStates.QUEUED,
            created = 100L, updated = 900L
        )
        // The parked record resolves — the glue reopens
        // the panel at its step rather than enqueueing.
        assertEquals(
            "task-p",
            TaskControl.resolveResumeTarget(
                listOf(parked, queued), "chat-1", null, null
            )?.optString("id")
        )
        // A hint that matches nothing resolves to nothing
        // — never a guess at the nearest work.
        assertNull(
            TaskControl.resolveResumeTarget(
                listOf(parked), "chat-1", "billing", null
            )
        )
        // Finished work is not resumable at all.
        assertNull(
            TaskControl.resolveResumeTarget(
                listOf(agentwareTask()), "chat-1", null, null
            )
        )
    }

    // ---------- 6. the message menu can never silently shrink ----------

    @Test
    fun theMenuContractIsPinnedForBothRolesAndTheLiveBubble() {
        // One source of truth serves stored bubbles of
        // both roles and the live bubble alike: the exact
        // reactions row and the items, in order.
        // v1.0.7: the items are THREE — Select was
        // removed by Ethuram's standing order (chat text
        // is never selectable); the pin now guards the
        // smaller contract against silent change either
        // way.
        assertEquals(
            listOf("👍", "❤️", "😂", "😮", "😢", "🙏"),
            MessageActions.REACTIONS
        )
        assertEquals(
            listOf("Reply", "Copy", "Share"),
            MessageActions.ITEMS.map { it.title }
        )
        assertEquals(
            listOf(
                MessageActions.Item.REPLY,
                MessageActions.Item.COPY,
                MessageActions.Item.SHARE
            ),
            MessageActions.ITEMS
        )
    }
}
