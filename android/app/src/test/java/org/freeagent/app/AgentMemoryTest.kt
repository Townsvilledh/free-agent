package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tiered memory: the consolidation law (ADD / UPDATE /
 * DELETE / NOOP), the per-block character cap, the frozen
 * session snapshot, and token-scored search over the
 * journal and the core.
 */
class AgentMemoryTest {

    private class MemoryFakeIO(var stored: String? = null) : TextIO {
        override fun read(): String? = stored
        override fun write(text: String) {
            stored = text
        }
    }

    // ---------- consolidation: decide() ----------

    @Test
    fun decideAddsWhenNothingOverlaps() {
        val (op, index) = MemoryConsolidation.decide(
            listOf("Likes tea in the morning"),
            "Prefers black coffee"
        )
        assertEquals(MemoryConsolidation.Op.ADD, op)
        assertEquals(-1, index)
    }

    @Test
    fun decideAddsAgainstEmptyMemory() {
        val (op, index) = MemoryConsolidation.decide(
            emptyList(), "Lives in Lagos"
        )
        assertEquals(MemoryConsolidation.Op.ADD, op)
        assertEquals(-1, index)
    }

    @Test
    fun decideUpdatesWhenOverlapIsStrongButFactSaysMore() {
        // Tokens: existing {user, prefers, tea, morning},
        // fact {user, prefers, coffee, morning} — Jaccard
        // 3/5 = 0.6, and "coffee" is new, so UPDATE.
        val (op, index) = MemoryConsolidation.decide(
            listOf("User prefers tea in morning"),
            "User prefers coffee in morning"
        )
        assertEquals(MemoryConsolidation.Op.UPDATE, op)
        assertEquals(0, index)
    }

    @Test
    fun decideNoopsWhenFactIsSubsetOfExistingLine() {
        // Fact tokens {prefers, black, coffee} ⊆ existing
        // {prefers, black, coffee, morning}; Jaccard 3/4.
        val (op, index) = MemoryConsolidation.decide(
            listOf("Prefers black coffee morning"),
            "Prefers black coffee"
        )
        assertEquals(MemoryConsolidation.Op.NOOP, op)
        assertEquals(0, index)
    }

    @Test
    fun decideDeletesOnForgetMatchingALine() {
        val (op, index) = MemoryConsolidation.decide(
            listOf("Prefers black coffee morning", "Lives in Lagos"),
            "forget prefers black coffee"
        )
        assertEquals(MemoryConsolidation.Op.DELETE, op)
        assertEquals(0, index)
    }

    @Test
    fun decideDeletesOnRemoveCaseInsensitively() {
        val (op, index) = MemoryConsolidation.decide(
            listOf("Prefers black coffee morning"),
            "Remove prefers black coffee"
        )
        assertEquals(MemoryConsolidation.Op.DELETE, op)
        assertEquals(0, index)
    }

    @Test
    fun decideNoopsOnForgetThatMatchesNothing() {
        val (op, index) = MemoryConsolidation.decide(
            listOf("Prefers black coffee morning"),
            "forget quantum physics lectures"
        )
        assertEquals(MemoryConsolidation.Op.NOOP, op)
        assertEquals(-1, index)
    }

    // ---------- writes through the store ----------

    @Test
    fun writeAddUpdateDeleteFlowAndJournal() {
        val io = MemoryFakeIO()
        val memory = AgentMemory(io)

        val added = memory.write("user", "User prefers tea in morning", 100L)
        assertEquals(MemoryConsolidation.Op.ADD, added.op)
        assertTrue(added.applied)
        assertEquals(
            listOf("User prefers tea in morning"), memory.coreLines("user")
        )

        val updated = memory.write("user", "User prefers coffee in morning", 200L)
        assertEquals(MemoryConsolidation.Op.UPDATE, updated.op)
        assertTrue(updated.applied)
        assertEquals(
            listOf("User prefers coffee in morning"), memory.coreLines("user")
        )

        val deleted = memory.write("user", "forget user prefers coffee morning", 300L)
        assertEquals(MemoryConsolidation.Op.DELETE, deleted.op)
        assertTrue(deleted.applied)
        assertTrue(memory.coreLines("user").isEmpty())

        // Every applied write journalled itself, the delete
        // under the removed line's text.
        val doc = JSONObject(io.stored!!)
        val journal = doc.getJSONArray("journal")
        assertEquals(3, journal.length())
        assertEquals("core:user", journal.getJSONObject(0).getString("kind"))
        assertEquals(
            "User prefers coffee in morning",
            journal.getJSONObject(2).getString("text")
        )
    }

    @Test
    fun writeNoopAppliesNothing() {
        val memory = AgentMemory(MemoryFakeIO())
        memory.write("user", "Prefers black coffee morning", 100L)
        val noop = memory.write("user", "Prefers black coffee", 200L)
        assertEquals(MemoryConsolidation.Op.NOOP, noop.op)
        assertFalse(noop.applied)
        assertEquals(
            listOf("Prefers black coffee morning"), memory.coreLines("user")
        )
    }

    @Test
    fun unknownBlockIsRefusedAndReadsEmpty() {
        val memory = AgentMemory(MemoryFakeIO())
        val result = memory.write("secrets", "anything at all", 100L)
        assertEquals(MemoryConsolidation.Op.NOOP, result.op)
        assertFalse(result.applied)
        assertTrue(memory.coreLines("secrets").isEmpty())
        assertTrue(memory.coreLines("user").isEmpty())
    }

    @Test
    fun addRefusedWhenItWouldPassTheCap() {
        // Seed a block holding 1190 characters in one line.
        val filler = "x".repeat(1190)
        val core = JSONObject().put(
            "user",
            JSONArray().put(filler)
        ).put("persona", JSONArray()).put("current_goals", JSONArray())
        val doc = JSONObject().put("core", core).put("journal", JSONArray())
        val io = MemoryFakeIO(doc.toString())
        val memory = AgentMemory(io)

        val result = memory.write("user", "Prefers black coffee", 100L)
        assertEquals(MemoryConsolidation.Op.ADD, result.op)
        assertFalse(result.applied)
        assertTrue(result.note.contains(AgentMemory.CAP_CHARS.toString()))
        assertEquals(listOf(filler), memory.coreLines("user"))

        // An UPDATE against a nearly-full block is still
        // allowed — only ADD is refused by the cap.
        val doc2 = JSONObject(io.stored!!)
        assertEquals(1, doc2.getJSONObject("core").getJSONArray("user").length())
    }

    @Test
    fun journalIsCappedAtFiveHundredEntries() {
        val io = MemoryFakeIO()
        val memory = AgentMemory(io)
        for (i in 0 until 510) {
            memory.journal("note", "entry number $i", i.toLong())
        }
        val journal = JSONObject(io.stored!!).getJSONArray("journal")
        assertEquals(500, journal.length())
        // The oldest fell off.
        assertEquals(
            "entry number 10", journal.getJSONObject(0).getString("text")
        )
    }

    // ---------- snapshot ----------

    @Test
    fun emptySnapshotSaysSheHasNoStoredMemoryYet() {
        val memory = AgentMemory(MemoryFakeIO())
        val snapshot = memory.snapshot()
        assertTrue(snapshot.startsWith("What Lille remembers"))
        assertTrue(snapshot.contains("does not change mid-session"))
        assertTrue(snapshot.contains("no stored memory yet"))
    }

    @Test
    fun snapshotRendersBlocksAndIsFrozen() {
        val memory = AgentMemory(MemoryFakeIO())
        memory.write("user", "Lives in Lagos Nigeria", 100L)
        memory.write("current_goals", "Ship the Stellar submission", 110L)
        val frozen = memory.snapshot()
        assertTrue(frozen.contains("[user]"))
        assertTrue(frozen.contains("- Lives in Lagos Nigeria"))
        assertTrue(frozen.contains("[current_goals]"))
        assertTrue(frozen.contains("- Ship the Stellar submission"))
        // The persona block is empty and so absent.
        assertFalse(frozen.contains("[persona]"))

        // A later write does not reach back into the
        // snapshot already taken — it is a String copy.
        memory.write("user", "Plays piano daily", 200L)
        assertFalse(frozen.contains("Plays piano daily"))
        assertTrue(memory.snapshot().contains("Plays piano daily"))
    }

    // ---------- search ----------

    @Test
    fun searchRanksByScoreThenMostRecent() {
        val memory = AgentMemory(MemoryFakeIO())
        memory.journal("note", "alpha beta gamma delta", 100L)
        memory.journal("note", "alpha beta epsilon zeta", 200L)
        memory.journal("note", "alpha only here", 300L)
        memory.journal("note", "completely unrelated words", 400L)

        val results = memory.search("alpha beta")
        // Both two-token hits first, the more recent of
        // them ahead; then the one-token hit; the
        // zero-score entry is excluded entirely.
        assertEquals(
            listOf(
                "alpha beta epsilon zeta",
                "alpha beta gamma delta",
                "alpha only here"
            ),
            results
        )
    }

    @Test
    fun searchCoversCoreLinesAndHonoursLimit() {
        val memory = AgentMemory(MemoryFakeIO())
        memory.write("user", "Lives in Lagos Nigeria", 100L)
        memory.journal("note", "Lagos traffic notes", 200L)
        val all = memory.search("lagos", limit = 5)
        assertEquals(2, all.size)
        assertTrue(all.contains("Lives in Lagos Nigeria"))
        val one = memory.search("lagos", limit = 1)
        assertEquals(1, one.size)
        // The journal entry is the more recent hit.
        assertEquals("Lagos traffic notes", one[0])
        assertTrue(memory.search("zzzz").isEmpty())
    }
}
