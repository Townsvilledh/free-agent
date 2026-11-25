package org.freeagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * On-device proactivity data (v0.7): the commitments register and
 * the "while you were away" outbox, persisted as one JSON file in
 * the app's private storage (filesDir/lille_proactive.json),
 * rewritten atomically (tmp + rename) like the TaskStore. Mirrors
 * the Python core's commitments.py semantics and state names.
 *
 * A commitment is a promise with a machine-checkable landing
 * condition (on-device: URL checks) — a result Lille must surface
 * when it lands, without being asked (the Proactivity Law).
 */
interface ProactiveData {
    fun commitments(): MutableList<JSONObject>
    fun saveCommitments(list: List<JSONObject>)
    fun reports(): MutableList<JSONObject>
    fun saveReports(list: List<JSONObject>)
    /** taskId -> title for tasks a pass picked up but that have not
     *  reached a terminal state yet (settled by a later pass). */
    fun pickups(): MutableMap<String, String>
    fun savePickups(map: Map<String, String>)
}

class ProactiveStore(context: Context) : ProactiveData {
    private val file = File(context.filesDir, "lille_proactive.json")

    @Synchronized
    private fun root(): JSONObject {
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    private fun write(root: JSONObject) {
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private fun arrToList(arr: JSONArray?): MutableList<JSONObject> {
        if (arr == null) return mutableListOf()
        return MutableList(arr.length()) { arr.getJSONObject(it) }
    }

    @Synchronized
    override fun commitments(): MutableList<JSONObject> =
        arrToList(root().optJSONArray("commitments"))

    @Synchronized
    override fun saveCommitments(list: List<JSONObject>) {
        val root = root()
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        root.put("commitments", arr)
        write(root)
    }

    @Synchronized
    override fun reports(): MutableList<JSONObject> =
        arrToList(root().optJSONArray("reports"))

    @Synchronized
    override fun saveReports(list: List<JSONObject>) {
        val root = root()
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        root.put("reports", arr)
        write(root)
    }

    @Synchronized
    override fun pickups(): MutableMap<String, String> {
        val obj = root().optJSONObject("pickups") ?: return mutableMapOf()
        val out = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optString(k)
        }
        return out
    }

    @Synchronized
    override fun savePickups(map: Map<String, String>) {
        val root = root()
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        root.put("pickups", obj)
        write(root)
    }

    /** Register a commitment. Check specs on-device: url:<URL> or
     *  url-contains:<URL>::<text> (the core supports more kinds). */
    @Synchronized
    fun addCommitment(title: String, check: String, everySeconds: Long): JSONObject {
        val now = System.currentTimeMillis()
        val cmt = JSONObject()
            .put("id", "cmt-" + UUID.randomUUID().toString().substring(0, 8))
            .put("kind", "watch")
            .put("title", title)
            .put("check", check)
            .put("everySeconds", everySeconds)
            .put("nextCheckAt", now)
            .put("deadline", 0L)
            .put("state", "open")
            .put("createdAt", now)
            .put("checkCount", 0)
        val list = commitments()
        list.add(cmt)
        saveCommitments(list)
        return cmt
    }
}
