package org.freeagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistent local task store (v0.4): one JSON file in the app's
 * private storage (filesDir/lille_tasks.json), rewritten atomically
 * (tmp + rename) on every checkpoint — after EVERY model/tool step —
 * so process death, reboot, or the user closing the app loses at
 * most the in-flight model call. Mirrors the Python core's
 * taskstore.py semantics and state names.
 */
class TaskStore(context: Context) {
    private val file = File(context.filesDir, "lille_tasks.json")

    @Synchronized
    fun all(): MutableList<JSONObject> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { arr.getJSONObject(it) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun saveAll(tasks: List<JSONObject>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(it) }
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun create(text: String): JSONObject {
        val task = JSONObject()
            .put("id", "task-" + UUID.randomUUID().toString().substring(0, 8))
            .put("text", text)
            .put("state", TaskStates.QUEUED)
            .put("steps", 0)
            .put("messages", JSONArray())
            .put("summary", "")
            .put("note", "")
            .put("nextRetryAt", 0L)
            .put("createdAt", System.currentTimeMillis())
            .put("updatedAt", System.currentTimeMillis())
        val tasks = all()
        tasks.add(task)
        saveAll(tasks)
        return task
    }

    @Synchronized
    fun get(id: String): JSONObject? = all().firstOrNull { it.optString("id") == id }

    /** Transition + field updates, validated against the state machine. */
    @Synchronized
    fun update(id: String, newState: String? = null, mutate: (JSONObject) -> Unit = {}): JSONObject? {
        val tasks = all()
        val task = tasks.firstOrNull { it.optString("id") == id } ?: return null
        if (newState != null) {
            val from = task.optString("state", TaskStates.QUEUED)
            require(TaskStates.canTransition(from, newState) || from == newState) {
                "illegal task transition $from -> $newState"
            }
            task.put("state", newState)
        }
        mutate(task)
        task.put("updatedAt", System.currentTimeMillis())
        saveAll(tasks)
        return task
    }
}
