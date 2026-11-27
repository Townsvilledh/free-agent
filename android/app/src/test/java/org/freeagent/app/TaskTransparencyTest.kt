package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TRANSPARENCY (v1.0.2): the in-chat status is rendered
 * from the task record at WORK level — steps named from
 * the recorded tool calls, the wait reason in work terms,
 * the parked step named exactly, the last-movement time —
 * and there is only ever ONE living status message per
 * task in its chat. Negative-pinned against the seamless
 * law: no route/provider/rate-limit vocabulary may appear
 * in ANY rendered status, expansion, or report.
 */
class TaskTransparencyTest {

    private val t0 = 1_700_000_000_000L
    private val now = t0 + 60_000L

    // ---------- record fixtures (TaskRunner's real shapes) ----------

    private fun call(tool: String, args: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("role", "assistant").put(
            "content",
            "```json\n{\"tool\": \"$tool\", \"args\": $args}\n```"
        )

    private fun result(name: String, observation: String): JSONObject =
        JSONObject().put("role", "user")
            .put("content", "Tool result ($name): $observation")

    private fun plain(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun task(
        state: String,
        messages: List<JSONObject> = emptyList(),
        text: String = "Do the thing",
        summary: String = "",
        note: String = "",
        wallStep: String = "",
        nextRetryAt: Long = 0L,
        updatedAt: Long = t0,
        title: String = "",
        chatId: String = "chat-1"
    ): JSONObject {
        val t = JSONObject()
            .put("id", "task-1")
            .put("text", text)
            .put("state", state)
            .put("steps", 0)
            .put("summary", summary)
            .put("note", note)
            .put("nextRetryAt", nextRetryAt)
            .put("createdAt", t0 - 600_000L)
            .put("updatedAt", updatedAt)
        if (title.isNotEmpty()) t.put("title", title)
        if (wallStep.isNotEmpty()) t.put("wallStep", wallStep)
        if (chatId.isNotEmpty()) t.put(ChatTaskLink.ORIGIN_FIELD, chatId)
        val arr = JSONArray()
        messages.forEach { arr.put(it) }
        t.put("messages", arr)
        return t
    }

    private fun searchedTask(state: String): JSONObject = task(
        state,
        messages = listOf(
            plain("system", "sys"),
            plain("user", "Do the thing"),
            call("web_search", JSONObject().put("query", "agentware hackathon discord")),
            result("web_search", "[search route: duckduckgo-instant]\nSome answer"),
            call("write_note", JSONObject().put("path", "digest.txt").put("content", "x")),
            result("write_note", "wrote digest.txt (1 chars)")
        )
    )

    // ---------- step-name derivation ----------

    @Test
    fun stepNamesComeFromRecordedToolCalls() {
        val steps = TaskTransparency.stepsFor(searchedTask(TaskStates.RUNNING))
        assertEquals(
            listOf(
                "Searched the web: agentware hackathon discord",
                "Wrote digest.txt"
            ),
            steps.map { it.name }
        )
        assertTrue(steps.all { it.ok })
    }

    @Test
    fun browserStepsNameTheSiteInSequence() {
        val t = task(
            TaskStates.RUNNING,
            messages = listOf(
                plain("user", "Join the Discord"),
                call("browser_open", JSONObject().put("url", "https://www.maildrop.cc/")),
                result(
                    "browser_open",
                    "BROWSER_OK opened https://www.maildrop.cc/\n" +
                        "URL: https://www.maildrop.cc/\nTitle: Maildrop"
                ),
                call("browser_click", JSONObject().put("text", "Get my inbox")),
                result(
                    "browser_click",
                    "CLICK_OK\nURL: https://maildrop.cc/inbox\nTitle: Inbox"
                ),
                call(
                    "browser_type_secret",
                    JSONObject().put("ref", 3).put("vault_key", "x_password")
                ),
                result("browser_type_secret", "TYPE_SECRET_OK vault_key=x_password")
            )
        )
        assertEquals(
            listOf(
                "Opened maildrop.cc",
                "Tapped “Get my inbox” on maildrop.cc",
                "Entered a saved secret on maildrop.cc"
            ),
            TaskTransparency.stepsFor(t).map { it.name }
        )
    }

    @Test
    fun erroredStepIsMarkedDidntWork() {
        val t = task(
            TaskStates.RUNNING,
            messages = listOf(
                plain("user", "Do the thing"),
                call("write_note", JSONObject().put("path", "../evil.txt")),
                result("write_note", "error: path escapes the task sandbox")
            )
        )
        val steps = TaskTransparency.stepsFor(t)
        assertEquals(1, steps.size)
        assertFalse(steps[0].ok)
        assertTrue(
            TaskTransparency.statusText(t, now).contains("didn't work")
        )
        assertTrue(
            TaskTransparency.expandedText(t, now)
                .contains("✗ Wrote ../evil.txt — didn't work")
        )
    }

    @Test
    fun challengeStepNamesTheWallNotAFailure() {
        val t = task(
            TaskStates.PARKED_AT_WALL,
            messages = listOf(
                plain("user", "Sign up"),
                call("browser_open", JSONObject().put("url", "https://guerrillamail.com/")),
                result(
                    "browser_open",
                    "CHALLENGE kind=arkose detail=Verify you are human — STOP"
                )
            ),
            wallStep = "Complete the human check on guerrillamail.com."
        )
        val steps = TaskTransparency.stepsFor(t)
        assertEquals(
            listOf("Reached a human check on guerrillamail.com"),
            steps.map { it.name }
        )
        assertTrue(steps[0].ok)
    }

    @Test
    fun nudgesAndRejectedFinishesAreNotSteps() {
        val t = task(
            TaskStates.RUNNING,
            messages = listOf(
                plain("user", "Do the thing"),
                plain("assistant", "Let me think about this first."),
                plain("user", "Reply with exactly one fenced JSON tool block, or finish."),
                call("finish", JSONObject().put("summary", "done")),
                plain(
                    "user",
                    "finish rejected: claimed outputs do not exist: [x.txt]. Create them first."
                ),
                call("list_notes"),
                result("list_notes", "(empty)")
            )
        )
        assertEquals(
            listOf("Listed the work files"),
            TaskTransparency.stepsFor(t).map { it.name }
        )
    }

    @Test
    fun finishIsBookkeepingUnlessTheTaskCompleted() {
        val parked = task(
            TaskStates.PARKED_AT_WALL,
            messages = listOf(
                plain("user", "Sign up"),
                call("browser_read"),
                result("browser_read", "PAGE\nURL: https://x.com/signup\nTitle: Sign up"),
                call("finish", JSONObject().put("summary", "walled"))
            ),
            wallStep = "Complete the human check on x.com."
        )
        assertEquals(
            listOf("Read the page on x.com"),
            TaskTransparency.stepsFor(parked).map { it.name }
        )
        val done = task(
            TaskStates.COMPLETED,
            messages = listOf(
                plain("user", "Do the thing"),
                call("list_notes"),
                result("list_notes", "digest.txt"),
                call("finish", JSONObject().put("summary", "Wrote the digest."))
            ),
            summary = "Wrote the digest."
        )
        val names = TaskTransparency.stepsFor(done).map { it.name }
        assertEquals("Listed the work files", names.first())
        assertTrue(names.last().startsWith("Finished — Wrote the digest."))
    }

    // ---------- the rich status text ----------

    @Test
    fun statusNamesLastStepStateAndMovement() {
        val text = TaskTransparency.statusText(searchedTask(TaskStates.RUNNING), now)
        assertTrue(text.contains("Working on it here"))
        assertTrue(text.contains("Last step: Wrote digest.txt"))
        assertTrue(text.contains("Now: working on the next step."))
        assertTrue(
            text.contains("Last movement " + TaskTransparency.clockLabel(t0) + ".")
        )
    }

    @Test
    fun waitReasonsAreWorkTermsPerState() {
        val retry = now + 120_000L
        val waitingRoute = TaskTransparency.statusText(
            searchedTask(TaskStates.WAITING_ROUTE).put("nextRetryAt", retry), now
        )
        assertTrue(waitingRoute.contains("Taking a short pause before the next step"))
        assertTrue(waitingRoute.contains("she continues on her own"))
        assertTrue(
            waitingRoute.contains("Next try around " + TaskTransparency.clockLabel(retry) + ".")
        )
        val waitingNet = TaskTransparency.statusText(
            searchedTask(TaskStates.WAITING_NETWORK), now
        )
        assertTrue(waitingNet.contains("Waiting for the network to come back"))
        assertTrue(waitingNet.contains("she continues on her own"))
        val queued = TaskTransparency.statusText(
            searchedTask(TaskStates.QUEUED), now
        )
        assertTrue(queued.contains("Queued here"))
        assertTrue(queued.contains("Starts on its own"))
    }

    @Test
    fun parkedStatusNamesTheUsersExactStep() {
        val step = "Complete the human check on x.com, then tap Done — hand back."
        val text = TaskTransparency.statusText(
            task(TaskStates.PARKED_AT_WALL, wallStep = step), now
        )
        assertTrue(text.contains("Waiting on one step from you"))
        assertTrue(text.contains("Your step: $step"))
    }

    @Test
    fun titleComesFromTheRecord() {
        // Packaged tasks carry their own title.
        assertEquals(
            "Create Lille's X account",
            TaskTransparency.titleFor(
                task(TaskStates.RUNNING, title = "Create Lille's X account")
            )
        )
        // Chat tasks: the ask itself, intake direction stripped.
        val chatTask = task(
            TaskStates.RUNNING,
            text = "Find the Discord invite in my email\n\n" +
                ChatTaskLink.INTAKE_NOTE
        )
        assertEquals(
            "Find the Discord invite in my email",
            TaskTransparency.titleFor(chatTask)
        )
    }

    @Test
    fun clockLabelIsAPlainClockTime() {
        val expected = SimpleDateFormat("h:mm a", Locale.US).format(Date(t0))
        assertEquals(expected, TaskTransparency.clockLabel(t0))
    }

    // ---------- no stacking: ONE living status message ----------

    @Test
    fun probeTwiceLeavesOneStatusMessageRefreshed() {
        val root = ChatData.emptyStore()
        ChatData.createChat(root, "chat-1", t0)
        val t = searchedTask(TaskStates.WAITING_ROUTE)

        // First probe: no status message yet — the answer
        // is created as the ONE living status message.
        val first = StatusProbe.answerFor(t, now)
        assertTrue(
            ChatData.upsertTaskStatus(
                root, "chat-1", "task-1", first, t0, now
            )
        )
        assertEquals(1, ChatData.messageCount(root, "chat-1"))
        assertEquals(
            t0, ChatData.taskStatusReflectedAt(root, "chat-1", "task-1")
        )

        // Second probe, record unmoved: the reflected-at
        // stamp matches the record, so the answer carries
        // the one short no-change line — and the upsert
        // rewrites the SAME message instead of stacking.
        val reflected = ChatData.taskStatusReflectedAt(root, "chat-1", "task-1")
        assertEquals(t.optLong("updatedAt"), reflected)
        val second = StatusProbe.answerFor(t, now, noChangeSince = reflected)
        assertTrue(
            second.contains("No change since " + TaskTransparency.clockLabel(t0) + ".")
        )
        assertTrue(
            ChatData.upsertTaskStatus(
                root, "chat-1", "task-1", second, t0, now
            )
        )
        assertEquals(1, ChatData.messageCount(root, "chat-1"))
        val msgs = ChatData.messages(root, "chat-1")
        assertEquals(second, msgs[0].optString("content"))
        assertEquals(
            1,
            msgs.count { it.optString(ChatData.TASK_STATUS_FIELD) == "task-1" }
        )

        // The record moves (a step lands): the next refresh
        // rewrites the same message with the new truth.
        val moved = searchedTask(TaskStates.RUNNING)
            .put("updatedAt", t0 + 300_000L)
        val third = StatusProbe.answerFor(moved, now)
        assertFalse(third.contains("No change since"))
        assertTrue(
            ChatData.upsertTaskStatus(
                root, "chat-1", "task-1", third, t0 + 300_000L, now
            )
        )
        assertEquals(1, ChatData.messageCount(root, "chat-1"))
        assertEquals(
            third,
            ChatData.messages(root, "chat-1")[0].optString("content")
        )
    }

    // ---------- expansion ----------

    @Test
    fun expansionListsEveryStepAndTheNowLine() {
        val expanded = TaskTransparency.expandedText(
            searchedTask(TaskStates.RUNNING), now
        )
        assertTrue(expanded.contains("Steps so far (2):"))
        assertTrue(expanded.contains("✓ Searched the web: agentware hackathon discord"))
        assertTrue(expanded.contains("✓ Wrote digest.txt"))
        assertTrue(expanded.contains("● Working on the next step"))
        val waiting = TaskTransparency.expandedText(
            searchedTask(TaskStates.WAITING_ROUTE), now
        )
        assertTrue(waiting.contains("● Pausing briefly — continues on her own"))
    }

    // ---------- reports carry the step list ----------

    @Test
    fun completedReportCarriesStepsAboveTheOutcome() {
        val done = task(
            TaskStates.COMPLETED,
            messages = listOf(
                plain("user", "Do the thing"),
                call("web_search", JSONObject().put("query", "agentware hackathon discord")),
                result("web_search", "answer"),
                call("write_note", JSONObject().put("path", "digest.txt")),
                result("write_note", "wrote digest.txt (1 chars)"),
                call("finish", JSONObject().put("summary", "Wrote the digest."))
            ),
            summary = "Wrote the digest."
        )
        val report = ChatTaskLink.reportFor(done, TaskStates.COMPLETED)!!
        assertTrue(report.contains("What was done:"))
        assertTrue(report.contains("✓ Searched the web: agentware hackathon discord"))
        assertTrue(report.contains("✓ Wrote digest.txt"))
        assertTrue(
            report.indexOf("What was done:") <
                report.indexOf("Wrote the digest.")
        )
        assertTrue(report.contains(ChatTaskLink.COST_LINE))
        // The finish gist is not stuttered above the summary.
        assertFalse(report.contains("✓ Finished —"))
    }

    @Test
    fun parkedReportCarriesStepsAndTheAsk() {
        val step = "Complete the human check on x.com."
        val parked = task(
            TaskStates.PARKED_AT_WALL,
            messages = listOf(
                plain("user", "Sign up"),
                call("browser_open", JSONObject().put("url", "https://x.com/signup")),
                result("browser_open", "BROWSER_OK\nURL: https://x.com/signup\nTitle: Sign up")
            ),
            wallStep = step
        )
        val report = ChatTaskLink.reportFor(parked, TaskStates.PARKED_AT_WALL)!!
        assertTrue(report.contains("What she's done so far:"))
        assertTrue(report.contains("✓ Opened x.com"))
        assertTrue(report.contains(step))
    }

    @Test
    fun reportStepListIsCappedWithAnHonestTail() {
        val msgs = mutableListOf<JSONObject>(plain("user", "Do the thing"))
        repeat(14) { i ->
            msgs.add(call("list_notes"))
            msgs.add(result("list_notes", "f$i"))
        }
        val t = task(TaskStates.FAILED, messages = msgs, note = "Stopped.")
        val block = TaskTransparency.reportSteps(t)!!
        assertTrue(block.contains("…and 4 more steps."))
        assertEquals(
            TaskTransparency.MAX_REPORT_STEPS,
            block.lines().count { it.startsWith("✓ ") }
        )
    }

    // ---------- the seamless-law negative pin ----------

    @Test
    fun noMechanicsVocabularyInAnyRenderedSurface() {
        val legacyNote =
            "Free route rate-limited — retrying on the community route."
        val surfaces = mutableListOf<String>()
        for (state in TaskStates.ALL) {
            val t = searchedTask(state)
                .put("note", legacyNote)
                .put("nextRetryAt", now + 60_000L)
            surfaces.add(TaskTransparency.statusText(t, now))
            surfaces.add(TaskTransparency.expandedText(t, now))
            surfaces.add(StatusProbe.answerFor(t, now))
            surfaces.add(StatusProbe.statusLineFor(t))
            ChatTaskLink.reportFor(t, state)?.let { surfaces.add(it) }
            TaskTransparency.reportSteps(t)?.let { surfaces.add(it) }
        }
        val banned = listOf(
            "pollinations", "horde", "groq", "provider",
            "rate-limit", "rate limited", "cooldown",
            "429", "402", "community route", "free route"
        )
        for (surface in surfaces) {
            assertFalse(
                "narration leaked: $surface",
                NarrationGuard.containsNarration(surface)
            )
            for (word in banned) {
                assertFalse(
                    "'$word' leaked into: $surface",
                    surface.lowercase().contains(word)
                )
            }
        }
    }
}
