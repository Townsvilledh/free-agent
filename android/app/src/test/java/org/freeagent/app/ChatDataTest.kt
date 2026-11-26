package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multiple chats: the store document's logic — creation,
 *  auto-titles, ordering, deletion/active fallback, history
 *  windows, and the legacy migration. */
class ChatDataTest {

    private fun storeWithTwo(): JSONObject {
        val root = ChatData.emptyStore()
        ChatData.createChat(root, "c1", 100L)
        ChatData.appendMessage(root, "c1", "user", "First topic", null, 110L)
        ChatData.appendMessage(root, "c1", "assistant", "Answer one", null, 120L)
        ChatData.createChat(root, "c2", 200L)
        ChatData.appendMessage(root, "c2", "user", "Second topic", null, 210L)
        return root
    }

    @Test
    fun parseToleratesGarbage() {
        assertEquals(0, ChatData.chats(ChatData.parseStore(null)).size)
        assertEquals(0, ChatData.chats(ChatData.parseStore("")).size)
        assertEquals(0, ChatData.chats(ChatData.parseStore("{nope")).size)
        assertEquals(0, ChatData.chats(ChatData.parseStore("{}")).size)
    }

    @Test
    fun createMakesActiveAndAppendsInOrder() {
        val root = storeWithTwo()
        assertEquals("c2", ChatData.activeId(root))
        assertEquals(2, ChatData.chats(root).size)
        val msgs = ChatData.messages(root, "c1")
        assertEquals(2, msgs.size)
        assertEquals("user", msgs[0].optString("role"))
        assertEquals("Answer one", msgs[1].optString("content"))
    }

    @Test
    fun autoTitleFromFirstUserMessageOnly() {
        val root = storeWithTwo()
        assertEquals("First topic", ChatData.findChat(root, "c1")!!.optString("title"))
        // An assistant message never titles a chat.
        val root2 = ChatData.emptyStore()
        ChatData.createChat(root2, "x", 1L)
        ChatData.appendMessage(root2, "x", "assistant", "I speak first", null, 2L)
        assertEquals(ChatData.DEFAULT_TITLE, ChatData.findChat(root2, "x")!!.optString("title"))
        // Later user messages don't retitle.
        ChatData.appendMessage(root, "c1", "user", "A later message", null, 300L)
        assertEquals("First topic", ChatData.findChat(root, "c1")!!.optString("title"))
    }

    @Test
    fun autoTitleSanitisesAndCaps() {
        assertEquals("Hello world", ChatData.autoTitle("  Hello\n   world  "))
        assertEquals(ChatData.DEFAULT_TITLE, ChatData.autoTitle("   "))
        assertEquals(
            "Question about the file",
            ChatData.autoTitle("[Attached file: x.pdf (application/pdf). Note: stuff] Question about the file")
        )
        val long = ChatData.autoTitle("a".repeat(200))
        assertTrue(long.length <= ChatData.TITLE_MAX_CHARS)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun displayRidesAlongOnlyWhenDifferent() {
        val root = ChatData.emptyStore()
        ChatData.createChat(root, "c", 1L)
        ChatData.appendMessage(root, "c", "user", "full content", "short", 2L)
        ChatData.appendMessage(root, "c", "user", "same", "same", 3L)
        val msgs = ChatData.messages(root, "c")
        assertEquals("short", msgs[0].optString("display"))
        assertFalse(msgs[1].has("display"))
    }

    @Test
    fun recencyOrdersByUpdatedAt() {
        val root = storeWithTwo()
        ChatData.appendMessage(root, "c1", "user", "bump", null, 999L)
        assertEquals("c1", ChatData.chatsByRecency(root)[0].optString("id"))
        assertEquals("c2", ChatData.chatsByRecency(root)[1].optString("id"))
    }

    @Test
    fun deleteActiveFallsBackToMostRecent() {
        val root = storeWithTwo()
        assertEquals("c2", ChatData.activeId(root))
        assertTrue(ChatData.deleteChat(root, "c2"))
        assertEquals("c1", ChatData.activeId(root))
        assertTrue(ChatData.deleteChat(root, "c1"))
        assertEquals("", ChatData.activeId(root))
        assertFalse(ChatData.deleteChat(root, "c1"))
    }

    @Test
    fun appendToMissingChatFails() {
        val root = ChatData.emptyStore()
        assertFalse(ChatData.appendMessage(root, "ghost", "user", "hi", null, 1L))
    }

    @Test
    fun historyPairsTakeTheTail() {
        val root = ChatData.emptyStore()
        ChatData.createChat(root, "c", 0L)
        for (i in 1..6) {
            ChatData.appendMessage(root, "c", if (i % 2 == 1) "user" else "assistant", "m$i", null, i.toLong())
        }
        val pairs = ChatData.historyPairs(root, "c", 4)
        assertEquals(4, pairs.size)
        assertEquals("m3", pairs[0].optString("content"))
        assertEquals("m6", pairs[3].optString("content"))
        assertEquals(6, ChatData.historyPairs(root, "c", 20).size)
        assertTrue(ChatData.historyPairs(root, "ghost", 5).isEmpty())
    }

    @Test
    fun roundTripsThroughText() {
        val root = storeWithTwo()
        val parsed = ChatData.parseStore(root.toString())
        assertEquals(2, ChatData.chats(parsed).size)
        assertEquals("c2", ChatData.activeId(parsed))
        assertEquals("Second topic", ChatData.findChat(parsed, "c2")!!.optString("title"))
    }

    @Test
    fun legacyMigrationImportsSingleThread() {
        val legacy = """{"messages":[{"role":"user","content":"Old question"},
            {"role":"assistant","content":"Old answer"},
            {"role":"system","content":"dropped"}]}"""
        val root = ChatData.migrateLegacy(legacy, "m1", 500L)
        assertTrue(root != null)
        val msgs = ChatData.messages(root!!, "m1")
        assertEquals(2, msgs.size)
        assertEquals("Old question", ChatData.findChat(root, "m1")!!.optString("title"))
        assertEquals("m1", ChatData.activeId(root))
    }

    @Test
    fun legacyMigrationAcceptsBareArrayAndRejectsJunk() {
        val root = ChatData.migrateLegacy(
            """[{"role":"user","content":"hi"}]""", "m2", 1L
        )
        assertTrue(root != null)
        assertNull(ChatData.migrateLegacy(null, "m", 1L))
        assertNull(ChatData.migrateLegacy("not json", "m", 1L))
        assertNull(ChatData.migrateLegacy("""{"nope":1}""", "m", 1L))
        assertNull(ChatData.migrateLegacy("""{"messages":[]}""", "m", 1L))
    }
}
