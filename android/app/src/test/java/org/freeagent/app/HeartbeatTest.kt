package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heartbeat pass: checklist parsing, the skip/silent/
 * surface decision, and the law that a need is never
 * swallowed by an unchanged signature.
 */
class HeartbeatTest {

    // 2026-11-27 00:00:00 UTC in epoch ms.
    private val nov27Utc = 1795737600000L

    private fun task(
        id: String, state: String, text: String = "a job",
        updatedAt: Long = 1000L
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("state", state)
        .put("text", text)
        .put("updatedAt", updatedAt)

    // ---------- checklist parsing ----------

    @Test
    fun parsesDashAndStarCheckboxLines() {
        val items = Heartbeat.items(
            "- [ ] water the plants\n" +
                "* [ ] check the drip pot\n" +
                "  - [ ] indented item"
        )
        assertEquals(3, items.size)
        assertEquals("water the plants", items[0].text)
        assertEquals("check the drip pot", items[1].text)
        assertEquals("indented item", items[2].text)
    }

    @Test
    fun ignoresNonCheckboxAndCheckedLines() {
        val items = Heartbeat.items(
            "# HEARTBEAT\n" +
                "some prose about the day\n" +
                "- [x] already done\n" +
                "- a plain bullet, no checkbox\n" +
                "\n" +
                "- [ ] the one real item"
        )
        assertEquals(1, items.size)
        assertEquals("the one real item", items[0].text)
    }

    @Test
    fun nullAndBlankChecklistsHaveNoItems() {
        assertTrue(Heartbeat.items(null).isEmpty())
        assertTrue(Heartbeat.items("").isEmpty())
        assertTrue(Heartbeat.items("   \n  ").isEmpty())
    }

    @Test
    fun dueDateParsesAsUtcMidnight() {
        val items = Heartbeat.items("- [ ] pay rent due: 2026-11-27")
        assertEquals(1, items.size)
        assertEquals(nov27Utc, items[0].dueMs)
    }

    @Test
    fun dueDateAnywhereInTheTextParses() {
        val items = Heartbeat.items(
            "- [ ] renew the domain (due: 2026-11-27) soon"
        )
        assertEquals(nov27Utc, items[0].dueMs)
    }

    @Test
    fun unparseableDueDateMeansNoDueDate() {
        val items = Heartbeat.items(
            "- [ ] pay rent due: 2026-13-40\n" +
                "- [ ] no date at all"
        )
        assertEquals(2, items.size)
        assertEquals(null, items[0].dueMs)
        assertEquals(null, items[1].dueMs)
    }

    // ---------- signature ----------

    @Test
    fun signatureIsStableAndChangeSensitive() {
        val tasks = listOf(task("t1", TaskStates.RUNNING))
        val a = Heartbeat.signature("- [ ] x", tasks)
        val b = Heartbeat.signature("- [ ] x", tasks)
        assertEquals(a, b)
        // A state change changes the signature…
        val c = Heartbeat.signature(
            "- [ ] x", listOf(task("t1", TaskStates.COMPLETED))
        )
        assertFalse(a == c)
        // …and so does a checklist change.
        val d = Heartbeat.signature("- [ ] y", tasks)
        assertFalse(a == d)
    }

    // ---------- skip / silent ----------

    @Test
    fun skipOnEmptyChecklistAndUnchangedSignature() {
        val tasks = listOf(task("t1", TaskStates.RUNNING))
        val sig = Heartbeat.signature("", tasks)
        val (outcome, newSig) = Heartbeat.evaluate("", tasks, sig, nov27Utc)
        assertEquals(sig, newSig)
        assertTrue(outcome is Heartbeat.Outcome.Skip)
        assertTrue(Heartbeat.isQuiet(outcome))
    }

    @Test
    fun silentOnUnchangedWithItemsButNoNeeds() {
        val checklist = "- [ ] future item due: 2999-01-01"
        val tasks = listOf(task("t1", TaskStates.RUNNING))
        val sig = Heartbeat.signature(checklist, tasks)
        val (outcome, _) = Heartbeat.evaluate(checklist, tasks, sig, nov27Utc)
        assertTrue(outcome is Heartbeat.Outcome.Silent)
        assertTrue(Heartbeat.isQuiet(outcome))
    }

    @Test
    fun silentNotSkipWhenSignatureChanged() {
        // Empty checklist, but the signature moved (a task
        // changed): the pass looked, so Silent — not Skip.
        val tasks = listOf(task("t1", TaskStates.RUNNING))
        val (outcome, _) = Heartbeat.evaluate("", tasks, "stale-sig", nov27Utc)
        assertTrue(outcome is Heartbeat.Outcome.Silent)
    }

    // ---------- surface: each need kind ----------

    @Test
    fun parkedTaskSurfaces() {
        val tasks = listOf(
            task("t1", TaskStates.PARKED_AT_WALL, "Sign up for the event")
        )
        val (outcome, _) = Heartbeat.evaluate("", tasks, "any", nov27Utc)
        assertTrue(outcome is Heartbeat.Outcome.Surface)
        assertFalse(Heartbeat.isQuiet(outcome))
        val needs = (outcome as Heartbeat.Outcome.Surface).needs
        assertEquals(1, needs.size)
        assertEquals("parked", needs[0].kind)
        assertTrue(
            needs[0].text.startsWith("One job is waiting on a step from you: ")
        )
        assertTrue(needs[0].text.contains("Sign up for the event"))
    }

    @Test
    fun parkedGistIsTruncatedAtSixtyChars() {
        val longText = "x".repeat(100)
        val tasks = listOf(task("t1", TaskStates.PARKED_AT_WALL, longText))
        val (outcome, _) = Heartbeat.evaluate("", tasks, "any", nov27Utc)
        val needs = (outcome as Heartbeat.Outcome.Surface).needs
        val gist = needs[0].text.removePrefix(
            "One job is waiting on a step from you: "
        )
        assertEquals(60, gist.length)
    }

    @Test
    fun unreportedChatFinishSurfaces() {
        val chatTask = task("t1", TaskStates.COMPLETED, "Write the brief")
            .put("originChatId", "chat-1")
            .put("chatReportState", TaskStates.RUNNING)
        val (outcome, _) = Heartbeat.evaluate("", listOf(chatTask), "any", nov27Utc)
        val needs = (outcome as Heartbeat.Outcome.Surface).needs
        assertEquals(1, needs.size)
        assertEquals("unreported", needs[0].kind)
        assertTrue(needs[0].text.startsWith("Finished work to report: "))
        assertTrue(needs[0].text.contains("Write the brief"))
    }

    @Test
    fun reportedChatFinishIsSilent() {
        val chatTask = task("t1", TaskStates.COMPLETED)
            .put("originChatId", "chat-1")
            .put("chatReportState", TaskStates.COMPLETED)
        val sig = Heartbeat.signature("- [ ] standing item", listOf(chatTask))
        val (outcome, _) = Heartbeat.evaluate(
            "- [ ] standing item", listOf(chatTask), sig, nov27Utc
        )
        assertTrue(outcome is Heartbeat.Outcome.Silent)
    }

    @Test
    fun unreportedNonChatFinishSurfacesUnlessFlagged() {
        val unflagged = task("t1", TaskStates.FAILED, "Sync the thing")
        val (outcome1, _) = Heartbeat.evaluate("", listOf(unflagged), "any", nov27Utc)
        val needs1 = (outcome1 as Heartbeat.Outcome.Surface).needs
        assertEquals("unreported", needs1[0].kind)

        val flagged = task("t1", TaskStates.FAILED)
            .put("heartbeatReported", true)
        val sig = Heartbeat.signature("- [ ] standing item", listOf(flagged))
        val (outcome2, _) = Heartbeat.evaluate(
            "- [ ] standing item", listOf(flagged), sig, nov27Utc
        )
        assertTrue(outcome2 is Heartbeat.Outcome.Silent)
    }

    @Test
    fun unfinishedTasksAreNotUnreported() {
        val tasks = listOf(
            task("t1", TaskStates.RUNNING),
            task("t2", TaskStates.QUEUED)
        )
        val sig = Heartbeat.signature("- [ ] standing item", tasks)
        val (outcome, _) = Heartbeat.evaluate(
            "- [ ] standing item", tasks, sig, nov27Utc
        )
        assertTrue(outcome is Heartbeat.Outcome.Silent)
    }

    @Test
    fun dueChecklistItemSurfaces() {
        val checklist = "- [ ] pay rent due: 2026-11-27"
        val (outcome, _) = Heartbeat.evaluate(checklist, emptyList(), "any", nov27Utc)
        val needs = (outcome as Heartbeat.Outcome.Surface).needs
        assertEquals(1, needs.size)
        assertEquals("due", needs[0].kind)
        assertEquals("pay rent due: 2026-11-27", needs[0].text)
    }

    @Test
    fun futureChecklistItemDoesNotSurface() {
        val checklist = "- [ ] pay rent due: 2026-11-28"
        val sig = Heartbeat.signature(checklist, emptyList())
        val (outcome, _) = Heartbeat.evaluate(checklist, emptyList(), sig, nov27Utc)
        assertTrue(outcome is Heartbeat.Outcome.Silent)
    }

    // ---------- the law: needs are never swallowed ----------

    @Test
    fun needIsNeverSwallowedByAnUnchangedSignature() {
        // Same inputs as the "last" pass — signature
        // identical — but a parked job exists: Surface,
        // never Skip.
        val tasks = listOf(task("t1", TaskStates.PARKED_AT_WALL, "A parked job"))
        val sig = Heartbeat.signature("", tasks)
        val (outcome, _) = Heartbeat.evaluate("", tasks, sig, nov27Utc)
        assertTrue(outcome is Heartbeat.Outcome.Surface)
    }

    @Test
    fun multipleNeedsSurfaceTogetherInOrder() {
        val tasks = listOf(
            task("t1", TaskStates.PARKED_AT_WALL, "Parked job"),
            task("t2", TaskStates.COMPLETED, "Finished job")
                .put("originChatId", "chat-9")
        )
        val checklist = "- [ ] overdue thing due: 2026-11-01"
        val (outcome, _) = Heartbeat.evaluate(checklist, tasks, "any", nov27Utc)
        val needs = (outcome as Heartbeat.Outcome.Surface).needs
        assertEquals(listOf("parked", "unreported", "due"), needs.map { it.kind })
    }
}
