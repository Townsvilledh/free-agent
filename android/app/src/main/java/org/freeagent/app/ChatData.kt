package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Multiple chats (v0.11) — the pure data component behind the
 * on-device chat store, in the same style as BrainPacing /
 * FluidGate: no Android, no I/O, pinned by JVM tests. ChatStore
 * only owns the file; every decision about the JSON lives here.
 *
 * Store shape (one JSON document, rewritten atomically):
 * {
 *   "version": 1,
 *   "activeId": "<chat id>",
 *   "chats": [
 *     {"id": "...", "title": "...", "createdAt": ms, "updatedAt": ms,
 *      "messages": [{"role": "user"|"assistant",
 *                    "content": "<text sent to / received from the brain>",
 *                    "display": "<text shown in the bubble, if different>"}]}
 *   ]
 * }
 *
 * `content` is what the brain sees (history is rebuilt from it);
 * `display` is what the user saw (e.g. a user bubble shows the
 * short "[attachment: name]" form while `content` carries the
 * full attachment note). When `display` is absent the bubble
 * shows `content`.
 *
 * Migration: v0.10 kept the single chat thread in memory only —
 * nothing was ever written to disk — so in practice there is
 * nothing to migrate. [migrateLegacy] still honours a legacy
 * single-thread document ({"messages":[...]} or a bare array)
 * if one is ever found, importing it as the first saved chat.
 */
object ChatData {

    const val STORE_VERSION = 1
    const val DEFAULT_TITLE = "New chat"
    const val TITLE_MAX_CHARS = 48

    fun emptyStore(): JSONObject = JSONObject()
        .put("version", STORE_VERSION)
        .put("activeId", "")
        .put("chats", JSONArray())

    /** Tolerant parse: anything unusable becomes an empty store
     *  rather than a crash — a corrupt chat file must never lock
     *  the user out of the app. */
    fun parseStore(text: String?): JSONObject {
        if (text.isNullOrBlank()) return emptyStore()
        return try {
            val root = JSONObject(text)
            if (!root.has("chats")) return emptyStore()
            if (!root.has("activeId")) root.put("activeId", "")
            root
        } catch (e: Exception) {
            emptyStore()
        }
    }

    fun chats(root: JSONObject): MutableList<JSONObject> {
        val arr = root.optJSONArray("chats") ?: return mutableListOf()
        return MutableList(arr.length()) { arr.getJSONObject(it) }
    }

    /** Newest activity first — the order the chat list shows. */
    fun chatsByRecency(root: JSONObject): List<JSONObject> =
        chats(root).sortedByDescending { it.optLong("updatedAt", 0L) }

    fun findChat(root: JSONObject, id: String): JSONObject? =
        chats(root).firstOrNull { it.optString("id") == id }

    fun activeId(root: JSONObject): String = root.optString("activeId", "")

    fun setActive(root: JSONObject, id: String) {
        root.put("activeId", id)
    }

    /** Create a chat and make it active. Returns the chat. */
    fun createChat(root: JSONObject, id: String, now: Long): JSONObject {
        val chat = JSONObject()
            .put("id", id)
            .put("title", DEFAULT_TITLE)
            .put("createdAt", now)
            .put("updatedAt", now)
            .put("messages", JSONArray())
        val arr = root.optJSONArray("chats") ?: JSONArray().also { root.put("chats", it) }
        arr.put(chat)
        root.put("activeId", id)
        return chat
    }

    /** Delete a chat. If it was the active one, the most
     *  recently updated remaining chat becomes active (or
     *  none). Returns true when a chat was removed. */
    fun deleteChat(root: JSONObject, id: String): Boolean {
        val list = chats(root)
        val idx = list.indexOfFirst { it.optString("id") == id }
        if (idx < 0) return false
        list.removeAt(idx)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        root.put("chats", arr)
        if (activeId(root) == id) {
            val next = list.maxByOrNull { it.optLong("updatedAt", 0L) }
            root.put("activeId", next?.optString("id") ?: "")
        }
        return true
    }

    /**
     * Append a message to a chat, bump its updatedAt, and — if
     * the chat still wears the default title and this is a user
     * message — auto-title it from that message. Returns false
     * when the chat does not exist.
     */
    fun appendMessage(
        root: JSONObject,
        chatId: String,
        role: String,
        content: String,
        display: String?,
        now: Long,
        quoteRole: String? = null,
        quoteText: String? = null
    ): Boolean {
        val chat = findChat(root, chatId) ?: return false
        val messages = chat.optJSONArray("messages") ?: JSONArray().also {
            chat.put("messages", it)
        }
        val msg = JSONObject()
            .put("role", role)
            .put("content", content)
        if (display != null && display != content) msg.put("display", display)
        // v1.0.1 reply-to-message: the quote rides the record.
        if (quoteRole != null && !quoteText.isNullOrEmpty()) {
            ReplyQuote.attach(msg, quoteRole, quoteText)
        }
        messages.put(msg)
        chat.put("updatedAt", now)
        if (role == "user" && chat.optString("title") == DEFAULT_TITLE) {
            chat.put("title", autoTitle(content))
        }
        return true
    }

    // ---------- the ONE living status message (v1.0.2) ----------

    /** Message-record field marking the chat's living
     *  status message for a task; the value is the task id.
     *  A task has exactly ONE such message in its chat:
     *  status refreshes and probe answers rewrite it in
     *  place instead of appending near-identical twins
     *  (the 1.0.1 stacking Ethuram screenshotted). */
    const val TASK_STATUS_FIELD = "taskStatus"

    /** Message-record field: the task updatedAt the living
     *  status message currently reflects — the probe path
     *  compares it against the record to say honestly
     *  whether anything has moved. */
    const val TASK_STATUS_AT_FIELD = "taskStatusAt"

    /** The living status message for [taskId] in a chat,
     *  or null when the chat carries none yet. */
    fun taskStatusMessage(
        root: JSONObject, chatId: String, taskId: String
    ): JSONObject? {
        val chat = findChat(root, chatId) ?: return null
        val arr = chat.optJSONArray("messages") ?: return null
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            if (m.optString(TASK_STATUS_FIELD) == taskId) return m
        }
        return null
    }

    /** The task updatedAt the living status message for
     *  [taskId] reflects, or null when there is no status
     *  message (or it predates the field). */
    fun taskStatusReflectedAt(
        root: JSONObject, chatId: String, taskId: String
    ): Long? {
        val msg = taskStatusMessage(root, chatId, taskId) ?: return null
        if (!msg.has(TASK_STATUS_AT_FIELD)) return null
        return msg.optLong(TASK_STATUS_AT_FIELD)
    }

    /** The task id on the LAST living status message in a
     *  chat — the card sitting lowest in the thread, the
     *  one on screen above the user's next message. The
     *  targeted kill (v1.0.6) resolves "this" to it before
     *  falling back to recency. Null when the chat carries
     *  no status message. */
    fun lastStatusTaskId(root: JSONObject, chatId: String): String? {
        val chat = findChat(root, chatId) ?: return null
        val arr = chat.optJSONArray("messages") ?: return null
        for (i in arr.length() - 1 downTo 0) {
            val id = arr.getJSONObject(i).optString(TASK_STATUS_FIELD)
            if (id.isNotEmpty()) return id
        }
        return null
    }

    /**
     * Create-or-rewrite the ONE living status message for
     * [taskId] in a chat: an existing marked message is
     * edited in place (content replaced, any stale display
     * override dropped, the reflected-at stamp moved);
     * otherwise a fresh assistant message carrying the
     * marker is appended. Returns false when the chat does
     * not exist.
     */
    fun upsertTaskStatus(
        root: JSONObject,
        chatId: String,
        taskId: String,
        text: String,
        reflectedAt: Long,
        now: Long
    ): Boolean {
        val chat = findChat(root, chatId) ?: return false
        val existing = taskStatusMessage(root, chatId, taskId)
        if (existing != null) {
            existing.put("content", text)
            existing.remove("display")
            existing.put(TASK_STATUS_AT_FIELD, reflectedAt)
        } else {
            val messages = chat.optJSONArray("messages")
                ?: JSONArray().also { chat.put("messages", it) }
            messages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", text)
                    .put(TASK_STATUS_FIELD, taskId)
                    .put(TASK_STATUS_AT_FIELD, reflectedAt)
            )
        }
        chat.put("updatedAt", now)
        return true
    }

    /**
     * Set or clear (emoji == null) the reaction on the message
     * at [index] in a chat. False when the chat or the index
     * does not exist. v1.0.1 long-press menu.
     */
    fun setReaction(
        root: JSONObject, chatId: String, index: Int, emoji: String?
    ): Boolean {
        val chat = findChat(root, chatId) ?: return false
        val messages = chat.optJSONArray("messages") ?: return false
        if (index < 0 || index >= messages.length()) return false
        val msg = messages.getJSONObject(index)
        if (emoji.isNullOrEmpty()) msg.remove(ReplyQuote.REACTION_FIELD)
        else msg.put(ReplyQuote.REACTION_FIELD, emoji)
        return true
    }

    fun messages(root: JSONObject, chatId: String): List<JSONObject> {
        val chat = findChat(root, chatId) ?: return emptyList()
        val arr = chat.optJSONArray("messages") ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }
    }

    fun messageCount(root: JSONObject, chatId: String): Int =
        findChat(root, chatId)?.optJSONArray("messages")?.length() ?: 0

    /** The role/content pairs the brain is given for a chat:
     *  the last [max] messages, in order. (The caller appends
     *  the new user turn itself, exactly as the v0.10 path did.) */
    fun historyPairs(root: JSONObject, chatId: String, max: Int): List<JSONObject> {
        val all = messages(root, chatId)
        val tail = if (all.size > max) all.subList(all.size - max, all.size) else all
        return tail.map {
            JSONObject()
                .put("role", it.optString("role"))
                .put("content", it.optString("content"))
        }
    }

    /** A chat title from the first user message: whitespace
     *  collapsed, attachment-note scaffolding stripped, capped
     *  with an ellipsis. Never empty. */
    fun autoTitle(firstUserText: String): String {
        var text = firstUserText
            .replace(Regex("\\[Attached file:.*?\\]", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("\\[attachment:[^\\]]*\\]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isEmpty()) return DEFAULT_TITLE
        if (text.length > TITLE_MAX_CHARS) {
            text = text.substring(0, TITLE_MAX_CHARS - 1).trimEnd() + "…"
        }
        return text
    }

    /**
     * Import a legacy single-thread document as the first
     * saved chat. Accepted shapes: {"messages":[{role,content}]}
     * or a bare [{role,content}] array. Returns the new store,
     * or null when the text is not a recognisable legacy thread.
     */
    fun migrateLegacy(legacyText: String?, id: String, now: Long): JSONObject? {
        if (legacyText.isNullOrBlank()) return null
        val messages: JSONArray = try {
            val trimmed = legacyText.trim()
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray("messages") ?: return null
            }
        } catch (e: Exception) {
            return null
        }
        if (messages.length() == 0) return null
        val root = emptyStore()
        val chat = createChat(root, id, now)
        val copied = JSONArray()
        var firstUser: String? = null
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role")
            val content = m.optString("content")
            if (role != "user" && role != "assistant") continue
            if (content.isEmpty()) continue
            copied.put(JSONObject().put("role", role).put("content", content))
            if (role == "user" && firstUser == null) firstUser = content
        }
        if (copied.length() == 0) return null
        chat.put("messages", copied)
        chat.put("title", autoTitle(firstUser ?: DEFAULT_TITLE))
        return root
    }
}
