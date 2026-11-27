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
 * Self-written skills: the distillation gate (completed +
 * at least five tool calls), the distilled shape (counted
 * steps, pitfalls from error results), trigger matching,
 * and patch-instead-of-duplicate merging.
 */
class SkillStoreTest {

    private class SkillFakeIO(var stored: String? = null) : TextIO {
        override fun read(): String? = stored
        override fun write(text: String) {
            stored = text
        }
    }

    private fun assistantCall(tool: String): JSONObject =
        JSONObject().put("role", "assistant").put(
            "content",
            "```json\n{\"tool\": \"$tool\", \"args\": {}}\n```"
        )

    private fun toolResult(tool: String, body: String): JSONObject =
        JSONObject().put("role", "user").put(
            "content", "Tool result ($tool): $body"
        )

    /** A completed task record in the runner's shapes. */
    private fun task(
        text: String,
        tools: List<String>,
        results: List<String> = emptyList(),
        state: String = "completed"
    ): JSONObject {
        val messages = JSONArray()
        for (tool in tools) messages.put(assistantCall(tool))
        for ((i, body) in results.withIndex()) {
            messages.put(toolResult(tools.getOrElse(i) { "browser_open" }, body))
        }
        return JSONObject()
            .put("text", text)
            .put("state", state)
            .put("messages", messages)
    }

    private fun stringList(arr: JSONArray): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    // ---------- the gate ----------

    @Test
    fun shouldDistilNeedsFiveToolCalls() {
        val store = SkillStore(SkillFakeIO())
        val four = task(
            "Register for the event on the website",
            listOf("browser_open", "browser_read", "browser_click", "browser_type")
        )
        assertFalse(store.shouldDistil(four))
        val five = task(
            "Register for the event on the website",
            listOf(
                "browser_open", "browser_read", "browser_click",
                "browser_type", "browser_open"
            )
        )
        assertTrue(store.shouldDistil(five))
    }

    @Test
    fun shouldDistilNeedsCompletedState() {
        val store = SkillStore(SkillFakeIO())
        val tools = listOf(
            "browser_open", "browser_read", "browser_click",
            "browser_type", "browser_open", "browser_read"
        )
        assertFalse(
            store.shouldDistil(
                task("Register for the event", tools, state = "paused")
            )
        )
        assertTrue(store.shouldDistil(task("Register for the event", tools)))
    }

    @Test
    fun shouldDistilIgnoresProseAndMalformedAssistantMessages() {
        val store = SkillStore(SkillFakeIO())
        val messages = JSONArray()
            .put(JSONObject().put("role", "assistant").put("content", "Thinking…"))
            .put(
                JSONObject().put("role", "assistant")
                    .put("content", "{\"note\": \"no tool field\"}")
            )
        repeat(4) { messages.put(assistantCall("browser_open")) }
        val record = JSONObject()
            .put("text", "A job")
            .put("state", "completed")
            .put("messages", messages)
        assertFalse(store.shouldDistil(record))
        messages.put(assistantCall("browser_open"))
        assertTrue(store.shouldDistil(record))
    }

    @Test
    fun distilReturnsNullBelowTheGate() {
        val store = SkillStore(SkillFakeIO())
        val short = task(
            "Register for the event",
            listOf("browser_open", "browser_read")
        )
        assertNull(store.distil(short, 100L))
    }

    // ---------- the distilled shape ----------

    @Test
    fun distilBuildsCountedStepsTriggerAndPitfalls() {
        val io = SkillFakeIO()
        val store = SkillStore(io)
        val record = task(
            "Register for the Agentware event on lu.ma website",
            listOf(
                "browser_open", "browser_read", "browser_open",
                "browser_click", "browser_open", "browser_type"
            ),
            results = listOf(
                "opened the page",
                "error: element 4 was not clickable",
                "CHALLENGE — a human check blocks this page"
            )
        )
        val skill = store.distil(record, 500L)
        assertNotNull(skill)
        skill!!
        assertEquals(
            "Register for the Agentware event on lu.ma website",
            skill.getString("title")
        )
        assertTrue(skill.getString("slug").startsWith("register-for-the-agentware"))
        // Steps: ordered, distinct, counted.
        assertEquals(
            listOf(
                "browser_open ×3", "browser_read ×1",
                "browser_click ×1", "browser_type ×1"
            ),
            stringList(skill.getJSONArray("steps"))
        )
        // Trigger: distinctive keywords of the job text.
        val trigger = skill.getString("trigger")
        assertTrue(trigger.contains("register"))
        assertTrue(trigger.contains("agentware"))
        // Pitfalls: only the error / CHALLENGE results.
        val pitfalls = stringList(skill.getJSONArray("pitfalls"))
        assertEquals(2, pitfalls.size)
        assertTrue(pitfalls[0].contains("error:"))
        assertTrue(pitfalls[1].contains("CHALLENGE"))
        assertEquals(1, skill.getInt("version"))
        assertEquals(1, skill.getInt("successes"))
        assertEquals(500L, skill.getLong("updatedAt"))
        // And it persisted as the store's one skill.
        val doc = JSONObject(io.stored!!)
        assertEquals(1, doc.getJSONArray("skills").length())
    }

    @Test
    fun distilTruncatesLongTitlesAtAWordBoundary() {
        val store = SkillStore(SkillFakeIO())
        val longText = "Register for the very large international " +
            "developers conference happening this winter season"
        val record = task(
            longText,
            listOf(
                "browser_open", "browser_read", "browser_click",
                "browser_type", "browser_open"
            )
        )
        val skill = store.distil(record, 100L)!!
        assertTrue(skill.getString("title").length <= 60)
        assertFalse(skill.getString("title").endsWith(" "))
        assertTrue(longText.startsWith(skill.getString("title")))
    }

    @Test
    fun secondDistilOfSameJobPatchesInsteadOfDuplicating() {
        val io = SkillFakeIO()
        val store = SkillStore(io)
        val tools = listOf(
            "browser_open", "browser_read", "browser_click",
            "browser_type", "browser_open"
        )
        val first = store.distil(
            task("Register for the Agentware event", tools), 100L
        )!!
        val second = store.distil(
            task("Register for the Agentware event", tools), 200L
        )!!
        assertEquals(first.getString("slug"), second.getString("slug"))
        assertEquals(2, second.getInt("version"))
        assertEquals(2, second.getInt("successes"))
        assertEquals(200L, second.getLong("updatedAt"))
        val doc = JSONObject(io.stored!!)
        assertEquals(1, doc.getJSONArray("skills").length())
    }

    // ---------- matching & rendering ----------

    @Test
    fun matchOrdersByTriggerOverlap() {
        val store = SkillStore(SkillFakeIO())
        val browserTools = listOf(
            "browser_open", "browser_read", "browser_click",
            "browser_type", "browser_open"
        )
        store.distil(
            task("Register for the Agentware event website", browserTools), 100L
        )
        store.distil(
            task("Register for the cooking workshop tickets", browserTools), 200L
        )
        // Both triggers share "register"; only the first
        // shares the event words — it must come first, and
        // a job with no overlap matches nothing.
        val matches = store.match("Register for the Agentware event please")
        assertEquals(2, matches.size)
        assertTrue(matches[0].getString("title").contains("Agentware"))
        assertTrue(matches[1].getString("title").contains("cooking"))
        assertTrue(store.match("Water the plants quietly").isEmpty())
        assertEquals(
            1, store.match("Register for the Agentware event please", limit = 1).size
        )
    }

    @Test
    fun renderProducesSkillMarkdown() {
        val store = SkillStore(SkillFakeIO())
        val skill = store.distil(
            task(
                "Register for the Agentware event",
                listOf(
                    "browser_open", "browser_read", "browser_click",
                    "browser_type", "browser_open"
                ),
                results = listOf("error: the form rejected the email")
            ),
            100L
        )!!
        val rendered = store.render(skill)
        assertTrue(rendered.startsWith("# Register for the Agentware event"))
        assertTrue(rendered.contains("Trigger: "))
        assertTrue(rendered.contains("## Steps"))
        assertTrue(rendered.contains("- browser_open ×2"))
        assertTrue(rendered.contains("## Pitfalls"))
        assertTrue(rendered.contains("error:"))
        assertTrue(rendered.contains("Version: 1"))
    }

    // ---------- patch ----------

    @Test
    fun patchMergesNewStepsAndPitfallsAndBumpsCounters() {
        val io = SkillFakeIO()
        val store = SkillStore(io)
        val skill = store.distil(
            task(
                "Register for the Agentware event",
                listOf(
                    "browser_open", "browser_read", "browser_click",
                    "browser_type", "browser_open"
                )
            ),
            100L
        )!!
        val slug = skill.getString("slug")
        val followUp = task(
            "Register for the Agentware event again",
            listOf(
                "browser_open", "browser_read", "browser_upload",
                "browser_click", "browser_wait"
            ),
            results = listOf("error: upload timed out twice")
        )
        assertTrue(store.patch(slug, followUp, 300L))
        val patched = JSONObject(io.stored!!)
            .getJSONArray("skills").getJSONObject(0)
        val steps = stringList(patched.getJSONArray("steps"))
        // Old steps stay, genuinely new ones append.
        assertTrue(steps.contains("browser_open ×2"))
        assertTrue(steps.any { it.startsWith("browser_upload") })
        assertTrue(steps.any { it.startsWith("browser_wait") })
        assertEquals(6, steps.size)
        val pitfalls = stringList(patched.getJSONArray("pitfalls"))
        assertEquals(1, pitfalls.size)
        assertTrue(pitfalls[0].contains("error:"))
        assertEquals(2, patched.getInt("successes"))
        assertEquals(2, patched.getInt("version"))
        assertEquals(300L, patched.getLong("updatedAt"))
    }

    @Test
    fun patchUnknownSlugIsFalse() {
        val store = SkillStore(SkillFakeIO())
        val record = task(
            "Register for the Agentware event",
            listOf(
                "browser_open", "browser_read", "browser_click",
                "browser_type", "browser_open"
            )
        )
        assertFalse(store.patch("no-such-skill", record, 100L))
    }
}
