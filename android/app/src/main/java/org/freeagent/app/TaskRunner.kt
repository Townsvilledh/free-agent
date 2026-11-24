package org.freeagent.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * On-device task runner (v0.4). A small plan-act-observe loop over
 * the keyless brain, checkpointing to the TaskStore after EVERY
 * step, using the same state names as the Python core.
 *
 * Honest on-device tool scope: model calls, web_search (keyless
 * DuckDuckGo Instant Answers, the core search chain's first route),
 * and note files inside the app sandbox. Tasks that need CODE
 * EXECUTION are never faked on-device: they are marked
 * needs_runner and exported as a task spec JSON (filesDir/specs/)
 * for the GitHub Actions runner lane (OFFLINE.md mode C).
 *
 * Network loss and rate limits are WAITING states, not failures:
 * the task keeps its checkpoint and WorkManager retries it.
 */
class TaskRunner(private val context: Context) {

    companion object {
        private const val TEXT_ENDPOINT = "https://text.pollinations.ai/openai"
        private const val MODEL = "openai"
        private const val MAX_STEPS = 8
        private const val SYSTEM_PROMPT =
            "You are Lille, an autonomous agent completing a task on the user's phone by calling tools. " +
            "Reply with exactly ONE fenced JSON block per step, like:\n" +
            "```json\n{\"tool\": \"write_note\", \"args\": {\"path\": \"note.txt\", \"content\": \"...\"}}\n```\n" +
            "Tools: write_note{path,content}, read_note{path}, list_notes{}, web_search{query}, " +
            "finish{summary, outputs}. All files are notes inside the app sandbox; use relative paths. " +
            "When fully done, call finish and list produced notes in outputs. Never invent tool results."
        private val CODE_HINTS = listOf(
            "python", "run code", "execute code", "compile", "build an app",
            "build the app", "create an apk", "make an apk", "script that runs",
            "run a script", "run the script"
        )

        fun looksLikeCodeTask(text: String): Boolean {
            val low = text.lowercase()
            return CODE_HINTS.any { low.contains(it) }
        }
    }

    private val store = TaskStore(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun taskDir(id: String): File =
        File(context.filesDir, "tasks/$id").apply { mkdirs() }

    /** Run/resume one task until it completes, waits, pauses, or fails.
     *  Returns the final state. Safe to call again after process death:
     *  the checkpoint (messages + step count) is restored from the store. */
    fun runTask(id: String): String {
        var task = store.get(id) ?: return TaskStates.FAILED
        val text = task.optString("text")

        if (looksLikeCodeTask(text)) {
            exportSpec(task)
            store.update(id, TaskStates.NEEDS_RUNNER) {
                it.put("note", "Needs code execution — exported as a task spec for the " +
                    "GitHub Actions runner lane. Not faked on-device.")
            }
            return TaskStates.NEEDS_RUNNER
        }

        store.update(id, TaskStates.RUNNING)
        var messages = task.optJSONArray("messages") ?: JSONArray()
        if (messages.length() == 0) {
            messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            messages.put(JSONObject().put("role", "user").put("content", text))
        }
        var stepsDone = task.optInt("steps", 0)

        while (stepsDone < MAX_STEPS) {
            // Honour pause/cancel that landed while we were working.
            val fresh = store.get(id) ?: return TaskStates.FAILED
            when (fresh.optString("state")) {
                TaskStates.CANCELLED -> return TaskStates.CANCELLED
                TaskStates.PAUSED -> return TaskStates.PAUSED
            }

            val reply: JSONObject
            try {
                reply = callModel(messages)
            } catch (e: IOException) {
                return wait(id, TaskStates.WAITING_NETWORK,
                    "Network unreachable — task is waiting, not failed.", 60_000L)
            }
            val httpCode = reply.optInt("http", 200)
            if (httpCode == 402 || httpCode == 429) {
                val waitMs = (reply.optLong("retryAfterSec", 45L)) * 1000L
                return wait(id, TaskStates.WAITING_ROUTE,
                    "Free route rate-limited — task is waiting, not failed.", waitMs)
            }
            if (httpCode != 200) {
                store.update(id, TaskStates.FAILED) {
                    it.put("note", "Free brain returned HTTP $httpCode.")
                }
                return TaskStates.FAILED
            }

            val content = reply.optString("content", "")
            messages.put(JSONObject().put("role", "assistant").put("content", content))
            val call = parseToolCall(content)
            stepsDone += 1
            if (call == null) {
                messages.put(JSONObject().put("role", "user").put("content",
                    "Reply with exactly one fenced JSON tool block, or finish."))
                checkpoint(id, messages, stepsDone)
                continue
            }
            val (name, args) = call
            if (name == "finish") {
                val outputs = args.optJSONArray("outputs")
                val missing = mutableListOf<String>()
                if (outputs != null) {
                    for (i in 0 until outputs.length()) {
                        if (!File(taskDir(id), outputs.optString(i)).isFile) {
                            missing.add(outputs.optString(i))
                        }
                    }
                }
                if (missing.isNotEmpty()) {
                    messages.put(JSONObject().put("role", "user").put("content",
                        "finish rejected: claimed outputs do not exist: $missing. Create them first."))
                    checkpoint(id, messages, stepsDone)
                    continue
                }
                store.update(id, TaskStates.COMPLETED) {
                    it.put("summary", args.optString("summary", "Done."))
                    it.put("steps", stepsDone)
                    it.put("messages", messages)
                }
                return TaskStates.COMPLETED
            }
            val observation = executeTool(id, name, args)
            messages.put(JSONObject().put("role", "user")
                .put("content", "Tool result ($name): $observation"))
            checkpoint(id, messages, stepsDone)
            task = store.get(id) ?: return TaskStates.FAILED
        }

        store.update(id, TaskStates.PAUSED) {
            it.put("note", "Step budget ($MAX_STEPS) reached — resume to continue.")
            it.put("steps", stepsDone)
            it.put("messages", messages)
        }
        return TaskStates.PAUSED
    }

    private fun wait(id: String, state: String, note: String, waitMs: Long): String {
        store.update(id, state) {
            it.put("note", note)
            it.put("nextRetryAt", System.currentTimeMillis() + waitMs)
        }
        return state
    }

    private fun checkpoint(id: String, messages: JSONArray, steps: Int) {
        store.update(id) {
            it.put("messages", messages)
            it.put("steps", steps)
        }
    }

    private fun exportSpec(task: JSONObject) {
        val id = task.optString("id")
        val dir = File(context.filesDir, "specs").apply { mkdirs() }
        val spec = JSONObject()
            .put("task_id", id)
            .put("task_text", task.optString("text"))
            .put("private", false)
            .put("needs_runner", true)
            .put("runner", "github-actions:lille-task.yml")
        File(dir, "$id.json").writeText(spec.toString(2))
    }

    // ---------- model + tools ----------

    /** One model call. Returns {http, content, retryAfterSec?};
     *  throws IOException on transport failure (-> waiting_network). */
    private fun callModel(messages: JSONArray): JSONObject {
        val body = JSONObject().put("model", MODEL).put("messages", messages)
        val request = Request.Builder()
            .url(TEXT_ENDPOINT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val out = JSONObject().put("http", response.code)
            response.header("Retry-After")?.toLongOrNull()?.let {
                out.put("retryAfterSec", it)
            }
            if (response.code == 200) {
                val payload = JSONObject(response.body?.string() ?: "{}")
                val content = payload.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "")
                out.put("content", content)
            }
            return out
        }
    }

    private fun parseToolCall(content: String): Pair<String, JSONObject>? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(content.substring(start, end + 1))
            if (!obj.has("tool")) return null
            obj.optString("tool") to (obj.optJSONObject("args") ?: JSONObject())
        } catch (e: Exception) {
            null
        }
    }

    private fun executeTool(id: String, name: String, args: JSONObject): String {
        val dir = taskDir(id)
        return try {
            when (name) {
                "write_note" -> {
                    val rel = args.optString("path")
                    val target = File(dir, rel).canonicalFile
                    if (!target.path.startsWith(dir.canonicalFile.path)) {
                        "error: path escapes the task sandbox"
                    } else {
                        target.parentFile?.mkdirs()
                        target.writeText(args.optString("content"))
                        "wrote $rel (${args.optString("content").length} chars)"
                    }
                }
                "read_note" -> {
                    val target = File(dir, args.optString("path"))
                    if (target.isFile) target.readText().take(4000)
                    else "error: no such note: ${args.optString("path")}"
                }
                "list_notes" -> {
                    val files = dir.walkTopDown().filter { it.isFile }
                        .map { it.relativeTo(dir).path }.sorted().toList()
                    if (files.isEmpty()) "(empty)" else files.joinToString("\n")
                }
                "web_search" -> webSearch(args.optString("query"))
                else -> "error: unknown tool: $name"
            }
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Keyless search, first route of the core chain (DDG Instant Answers). */
    private fun webSearch(query: String): String {
        val url = "https://api.duckduckgo.com/?q=" +
            URLEncoder.encode(query, "UTF-8") + "&format=json&no_html=1"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code != 200) return "No result (HTTP ${response.code})."
                val data = JSONObject(response.body?.string() ?: "{}")
                val abstract = data.optString("AbstractText", "")
                if (abstract.isNotEmpty()) return abstract.take(1500)
                val topics = data.optJSONArray("RelatedTopics")
                if (topics != null && topics.length() > 0) {
                    val first = topics.getJSONObject(0).optString("Text", "")
                    if (first.isNotEmpty()) return first.take(1500)
                }
                "No instant answer found for that query."
            }
        } catch (e: IOException) {
            "error: search route unreachable (${e.message})"
        }
    }
}
