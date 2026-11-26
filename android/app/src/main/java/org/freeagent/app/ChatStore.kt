package org.freeagent.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistent chat store (v0.11): one JSON file in the app's
 * private storage (filesDir/lille_chats.json), rewritten
 * atomically (tmp + rename) on every change — the TaskStore
 * discipline. All shape decisions live in the pure [ChatData]
 * component; this class owns only the file.
 *
 * Before v0.11 the chat screen kept its single thread in
 * memory: process death lost the conversation. Chats are now
 * durable, plural, and part of the backup payload.
 */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "lille_chats.json")
    private val legacyFile = File(context.filesDir, "lille_chat_legacy.json")

    @Synchronized
    fun root(): JSONObject {
        if (file.exists()) {
            return ChatData.parseStore(
                try { file.readText() } catch (e: Exception) { null }
            )
        }
        // One-time legacy import: v0.10 never persisted a
        // thread, so this file should not exist — but if a
        // legacy single-thread document is ever present, it
        // becomes the first saved chat instead of being lost.
        if (legacyFile.exists()) {
            val migrated = ChatData.migrateLegacy(
                try { legacyFile.readText() } catch (e: Exception) { null },
                newId(), System.currentTimeMillis()
            )
            if (migrated != null) {
                writeRoot(migrated)
                legacyFile.delete()
                return migrated
            }
        }
        return ChatData.emptyStore()
    }

    @Synchronized
    private fun writeRoot(root: JSONObject) {
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    /** Create a chat, make it active, persist, return its id. */
    @Synchronized
    fun createChat(): String {
        val root = root()
        val id = newId()
        ChatData.createChat(root, id, System.currentTimeMillis())
        writeRoot(root)
        return id
    }

    /** The active chat id, creating a first chat when the
     *  store has none (fresh install, or all chats deleted). */
    @Synchronized
    fun ensureActiveChat(): String {
        val root = root()
        val existing = ChatData.activeId(root)
        if (existing.isNotEmpty() && ChatData.findChat(root, existing) != null) {
            return existing
        }
        val mostRecent = ChatData.chatsByRecency(root).firstOrNull()
        if (mostRecent != null) {
            ChatData.setActive(root, mostRecent.optString("id"))
            writeRoot(root)
            return mostRecent.optString("id")
        }
        val id = newId()
        ChatData.createChat(root, id, System.currentTimeMillis())
        writeRoot(root)
        return id
    }

    @Synchronized
    fun setActive(id: String) {
        val root = root()
        if (ChatData.findChat(root, id) != null) {
            ChatData.setActive(root, id)
            writeRoot(root)
        }
    }

    @Synchronized
    fun deleteChat(id: String): Boolean {
        val root = root()
        val removed = ChatData.deleteChat(root, id)
        if (removed) writeRoot(root)
        return removed
    }

    @Synchronized
    fun appendMessage(chatId: String, role: String, content: String, display: String?): Boolean {
        val root = root()
        val ok = ChatData.appendMessage(
            root, chatId, role, content, display, System.currentTimeMillis()
        )
        if (ok) writeRoot(root)
        return ok
    }

    @Synchronized
    fun chatsByRecency(): List<JSONObject> = ChatData.chatsByRecency(root())

    @Synchronized
    fun chatCount(): Int = ChatData.chats(root()).size

    @Synchronized
    fun isEmpty(): Boolean {
        val root = root()
        return ChatData.chats(root).all {
            ChatData.messageCount(root, it.optString("id")) == 0
        }
    }

    /** Backup hooks: the raw store document, out and in. */
    @Synchronized
    /** The store file's mtime — MainActivity compares it
     *  across onResume to notice writes that landed while
     *  its screen was dead (an orphaned flight's reply). */
    fun lastModified(): Long = if (file.exists()) file.lastModified() else 0L

    fun rawText(): String? =
        if (file.exists()) try { file.readText() } catch (e: Exception) { null } else null

    @Synchronized
    fun replaceRaw(text: String) {
        writeRoot(ChatData.parseStore(text))
    }

    private fun newId(): String =
        "chat-" + UUID.randomUUID().toString().substring(0, 8)
}
