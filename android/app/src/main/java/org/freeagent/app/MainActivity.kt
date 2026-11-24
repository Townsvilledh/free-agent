package org.freeagent.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Lille (project: FreeAgent) — single-activity chat UI (v0.2).
 *
 * Chat is wired to the KEYLESS free brain: POST
 * https://text.pollinations.ai/openai (OpenAI-compatible, no API key,
 * no signup — see KEYLESS.md). POST is used deliberately: prompts in a
 * GET URL get logged and cached by intermediaries.
 *
 * Anonymous keyless limits are real (~1 request at a time, roughly 45s
 * between requests per IP): a 402/429 cools the route down and the same
 * task auto-retries after the wait (fallback law, ROUTES.md) — never a
 * crash, never silent loss, never a raw provider error shown.
 *
 * Attachments (v0.2): the picker lets the user attach an image or file;
 * its name/type ride along as a text note and images get a thumbnail
 * preview. Full multimodal understanding of attachments is a documented
 * next step — the app does NOT pretend the model saw the file.
 *
 * Keys: NONE are needed. A user's OWN optional keys (AgentRouter/Groq)
 * belong in the Keystore-backed encrypted vault (Settings, planned) and
 * are never shipped in the app, never shared, never collected.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TEXT_ENDPOINT = "https://text.pollinations.ai/openai"
        private const val MODEL = "openai" // default from GET /models
        private const val SYSTEM_PROMPT =
            "You are Lille, a helpful personal AI assistant running in an " +
            "open-source app on the user's own phone. You run on free public " +
            "models with no account and no key. Be concise, practical and " +
            "honest about your limits. Never invent features, facts, or " +
            "steps the user did not give you; if something is unknown, say " +
            "so or label it clearly as an assumption. Give only safe, " +
            "standard-practice advice."
        private const val RATE_LIMIT_WAIT_SECONDS = 45L
        private const val MAX_HISTORY = 20
        private const val MAX_AUTO_RETRIES = 3
        // v0.2.1 smart recovery: stream continuations + one refusal reframe.
        private const val MAX_CONTINUATIONS = 3
        private const val REFUSAL_NOTE =
            "The free model refused that phrasing, so I re-asked it as a concrete task:"
        private const val REFUSAL_STILL =
            "\n\n(The free model refused this one even after I re-asked it as a " +
            "concrete task. Try a more specific wording yourself — for example " +
            "\"Write a Python script that …\" naming exactly what it should do.)"
    }

    private lateinit var transcript: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var attachButton: Button
    private lateinit var attachmentBar: View
    private lateinit var attachmentThumb: ImageView
    private lateinit var attachmentName: TextView

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Conversation history sent with each request (role/content pairs). */
    private val history = mutableListOf<JSONObject>()

    /** Currently picked attachment (uri + display name + mime), or null. */
    private var pendingAttachment: Triple<Uri, String, String?>? = null

    /** True while a request is in flight — the keyless tier is 1-at-a-time. */
    private var busy = false

    /** Fallback law: when the keyless route is cooling down after a
     *  rate limit, this holds the wall-clock time it recovers. */
    private var cooldownUntilMs = 0L

    private val pickAttachment =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onAttachmentPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        transcript = findViewById(R.id.transcript)
        scrollView = findViewById(R.id.scrollView)
        input = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        attachButton = findViewById(R.id.attachButton)
        attachmentBar = findViewById(R.id.attachmentBar)
        attachmentThumb = findViewById(R.id.attachmentThumb)
        attachmentName = findViewById(R.id.attachmentName)

        findViewById<Button>(R.id.tasksButton).setOnClickListener {
            // v0.4: durable background tasks (OFFLINE.md) — create a
            // task there, run it, then close the app / go offline.
            startActivity(android.content.Intent(this, TasksActivity::class.java))
        }
        sendButton.setOnClickListener { send() }
        attachButton.setOnClickListener {
            // Images AND files; the note in the outgoing message says what
            // was attached. */* keeps the picker open to any document.
            pickAttachment.launch(arrayOf("image/*", "*/*"))
        }
        findViewById<Button>(R.id.clearAttachmentButton).setOnClickListener { clearAttachment() }

        appendLine(
            "Lille",
            "Ready — free keyless brain, no account, no API key needed. " +
                "Optional: your OWN AgentRouter/Groq key can be added later in Settings " +
                "(encrypted vault) — never required, never shared."
        )
    }

    // ---------- attachments ----------

    private fun onAttachmentPicked(uri: Uri) {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "attachment"
        val mime = contentResolver.getType(uri)
        pendingAttachment = Triple(uri, name, mime)
        attachmentName.text = name
        attachmentThumb.setImageBitmap(null)
        attachmentThumb.visibility = View.GONE
        if (mime?.startsWith("image/") == true) {
            try {
                // API 29+ thumbnail of the user's own picked image.
                val bmp: Bitmap = contentResolver.loadThumbnail(uri, Size(144, 144), null)
                attachmentThumb.setImageBitmap(bmp)
                attachmentThumb.visibility = View.VISIBLE
            } catch (e: Exception) {
                attachmentThumb.visibility = View.GONE
            }
        }
        attachmentBar.visibility = View.VISIBLE
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun clearAttachment() {
        pendingAttachment = null
        attachmentBar.visibility = View.GONE
        attachmentThumb.setImageBitmap(null)
    }

    // ---------- chat ----------

    private fun send() {
        val raw = input.text.toString().trim()
        if (busy) {
            appendLine("Lille", "(Still waiting on the free brain — one request at a time. Your message will be sendable in a moment.)")
            return
        }
        if (raw.isEmpty() && pendingAttachment == null) return

        var outgoing = raw
        pendingAttachment?.let { (_, name, mime) ->
            val note = "[Attached file: $name (${mime ?: "unknown type"}). " +
                "Note: in this version the attachment is recorded by name only — " +
                "full file/image understanding is a next step, so do not claim to have seen its contents.]"
            outgoing = if (outgoing.isEmpty()) note else "$outgoing\n$note"
            appendLine("You", (if (raw.isEmpty()) "" else "$raw ") + "[attachment: $name]")
        } ?: appendLine("You", raw)

        input.text.clear()
        clearAttachment()
        askBrain(outgoing, attempt = 0)
    }

    /**
     * Fallback-law mirror (see ROUTES.md): on a rate limit the route
     * cools down and the SAME task auto-continues after the wait —
     * the user never re-sends. After MAX_AUTO_RETRIES the app says so
     * plainly instead of dying silently.
     */
    private fun askBrain(userText: String, attempt: Int) {
        askBrain(userText, attempt, reframed = false, carry = "", continuations = 0,
                 historyText = userText)
    }

    /**
     * v0.2.1 smart recovery, mirroring the Python router:
     * - STREAMING (stream=true): if the connection drops mid-answer,
     *   the partial text is kept and the SAME task auto-continues
     *   ("continue from where you stopped", max MAX_CONTINUATIONS),
     *   stitching the parts into one answer.
     * - REFUSAL RECOVERY: if the free model refuses the phrasing of a
     *   vague outcome request, Lille reframes it ONCE as a concrete
     *   task and retries, telling the user it did so. Refusals of
     *   harmful requests are never reframed; second refusals are
     *   delivered plainly with a suggested manual rephrase.
     */
    private fun askBrain(userText: String, attempt: Int, reframed: Boolean,
                         carry: String, continuations: Int, historyText: String) {
        busy = true
        setInputEnabled(false)
        if (carry.isEmpty()) appendLine("Lille", "…thinking")

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (m in history.takeLast(MAX_HISTORY)) messages.put(m)
        messages.put(JSONObject().put("role", "user").put("content", userText))
        if (carry.isNotEmpty()) {
            messages.put(JSONObject().put("role", "assistant").put("content", carry))
            messages.put(JSONObject().put("role", "user")
                .put("content", "Continue exactly from where you stopped. Do not repeat any text you have already given."))
        }
        val bodyJson = JSONObject().put("model", MODEL).put("messages", messages)
            .put("stream", true)

        val request = Request.Builder()
            .url(TEXT_ENDPOINT)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    if (carry.isNotEmpty() && continuations < MAX_CONTINUATIONS) {
                        // Dropped before/while streaming with a partial
                        // answer preserved: auto-continue, don't lose it.
                        replaceCurrentReply(carry + "\n\n(Connection dropped — continuing automatically…)")
                        askBrain(userText, attempt, reframed, carry, continuations + 1, historyText)
                    } else {
                        busy = false
                        setInputEnabled(true)
                        replaceCurrentReply("Couldn't reach the free brain — check your connection and try again. Nothing was sent anywhere else.")
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                var chunk = ""
                var dropped = false
                if (code == 200) {
                    try {
                        val source = response.body?.source()
                        if (source != null) {
                            var sseMode: Boolean? = null
                            while (true) {
                                val line = try {
                                    source.readUtf8Line()
                                } catch (e: Exception) {
                                    dropped = chunk.isNotEmpty()
                                    break
                                } ?: break
                                if (line.isBlank()) continue
                                if (sseMode == null) sseMode = line.startsWith("data:")
                                if (sseMode == true) {
                                    if (!line.startsWith("data:")) continue
                                    val payload = line.substring(5).trim()
                                    if (payload == "[DONE]") break
                                    try {
                                        chunk += JSONObject(payload).getJSONArray("choices")
                                            .getJSONObject(0).getJSONObject("delta")
                                            .optString("content", "")
                                    } catch (e: Exception) {
                                        // keep-alive / non-delta line: ignore
                                    }
                                } else {
                                    // Provider ignored stream=true and sent
                                    // one plain JSON body instead.
                                    val rest = try { source.readUtf8() } catch (e: Exception) { "" }
                                    chunk = try {
                                        JSONObject(line + rest).getJSONArray("choices")
                                            .getJSONObject(0).getJSONObject("message")
                                            .getString("content").trim()
                                    } catch (e: Exception) {
                                        ""
                                    }
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (chunk.isNotEmpty()) dropped = true
                    } finally {
                        response.close()
                    }
                } else {
                    response.close()
                }
                val full = stitch(carry, chunk)
                mainHandler.post {
                    when {
                        code == 200 && dropped && full.isNotEmpty() &&
                            continuations < MAX_CONTINUATIONS -> {
                            replaceCurrentReply(full + "\n\n(Connection dropped mid-answer — continuing automatically…)")
                            askBrain(userText, attempt, reframed, full, continuations + 1, historyText)
                        }
                        code == 200 && full.isNotEmpty() && isRefusalReply(full) &&
                            !reframed && !looksHarmful(userText) -> {
                            // Refusal recovery: reframe ONCE, transparently.
                            replaceCurrentReply(REFUSAL_NOTE)
                            askBrain(reframeRequest(userText), attempt, reframed = true,
                                     carry = "", continuations = 0, historyText = historyText)
                        }
                        code == 200 && full.isNotEmpty() -> {
                            busy = false
                            setInputEnabled(true)
                            history.add(JSONObject().put("role", "user").put("content", historyText))
                            history.add(JSONObject().put("role", "assistant").put("content", full))
                            replaceCurrentReply(
                                when {
                                    reframed && isRefusalReply(full) -> full + REFUSAL_STILL
                                    reframed -> "$REFUSAL_NOTE\n\n$full"
                                    else -> full
                                }
                            )
                        }
                        (code == 402 || code == 429) && attempt < MAX_AUTO_RETRIES -> {
                            val wait = retryAfter ?: RATE_LIMIT_WAIT_SECONDS
                            cooldownUntilMs = System.currentTimeMillis() + wait * 1000
                            replaceCurrentReply("The free brain is rate-limited right now (anonymous tier). Route cooling down — your task is queued and continues automatically in about ${wait}s. No need to re-send.")
                            mainHandler.postDelayed({
                                // Auto-continue the SAME task after cooldown;
                                // askBrain re-enters with busy still true.
                                askBrain(userText, attempt + 1, reframed, "", 0, historyText)
                            }, wait * 1000)
                        }
                        else -> {
                            busy = false
                            setInputEnabled(true)
                            replaceCurrentReply(
                                if (code == 402 || code == 429)
                                    "All free routes are still cooling down after several automatic retries. Your message is safe — wait a few minutes and send it again; nothing was routed anywhere else."
                                else
                                    "The free brain returned an error (HTTP $code). It's a best-effort free service — try again shortly."
                            )
                        }
                    }
                }
            }
        })
    }

    // ---------- smart recovery helpers (mirror free_agent/recovery.py) ----------

    private fun isRefusalReply(text: String): Boolean {
        if (text.length > 300) return false
        // Normalise curly apostrophes — the model emits both forms,
        // and a missed match silently disables refusal recovery.
        val low = text.lowercase().replace('\u2019', '\'').replace('\u2018', '\'')
        return low.contains("can't help with that") || low.contains("cannot help") ||
            low.contains("can't assist with that") || low.contains("cannot assist with that") ||
            (low.startsWith("i'm sorry") && low.contains("can't"))
    }

    private fun looksHarmful(text: String): Boolean {
        val low = text.lowercase()
        return listOf(
            "bomb", "explosive", "build a weapon", "make a weapon", "murder",
            "steal ", "fraud", "phishing", "malware", "ransomware", "hack into",
            "counterfeit", "forge "
        ).any { low.contains(it) }
    }

    private fun reframeRequest(original: String): String {
        var core = original.replace(Regex("\\[attachment:[^\\]]*\\]"), "")
        core = core.replace(Regex("\\[Attached file:.*?\\]", RegexOption.DOT_MATCHES_ALL), "").trim()
        if (core.isEmpty()) core = original.trim()
        val low = core.lowercase()
        // Mirror recovery.py: wrapping the trigger wording fails — the
        // reframe must REPLACE vague outcome wording with a concrete
        // spec (the proven shape for agent+money asks).
        if (low.contains("agent") &&
            listOf("profit", "trading", "trade", "btc", "bitcoin", "crypto", "money").any { low.contains(it) }
        ) {
            return "Write a Python script for an autonomous agent that watches the BTC " +
                "price and paper-trades a simple moving-average strategy, no real " +
                "money. Include a short explanation of how it works and any risks."
        }
        val moneyish = listOf("profit", "trading", "trade", "money", "invest", "crypto", "btc", "bitcoin")
            .any { low.contains(it) }
        val sanitised = core.replace(Regex("(?i)\\bprofitable\\b"), "")
            .replace(Regex("(?i)\\bmake money\\b"), "run a simulated strategy")
            .replace(Regex("\\s+"), " ").trim()
        val bounds = if (moneyish)
            "as a simulation / paper-only version with no real money, no real accounts and no real-world transactions"
        else
            "as a self-contained example that acts on no real accounts or real-world systems"
        return "Write a complete Python script that implements the following request " +
            "$bounds. Include a short explanation of how it works and any risks. Request: $sanitised"
    }

    /** Join a continuation onto the partial answer, trimming overlap. */
    private fun stitch(carry: String, chunk: String): String {
        if (carry.isEmpty()) return chunk
        if (chunk.isEmpty()) return carry
        var overlap = 0
        val maxK = minOf(64, carry.length, chunk.length)
        for (k in maxK downTo 1) {
            if (carry.endsWith(chunk.substring(0, k))) {
                overlap = k
                break
            }
        }
        return carry + chunk.substring(overlap)
    }

    // ---------- UI helpers ----------

    private fun setInputEnabled(enabled: Boolean) {
        sendButton.isEnabled = enabled
        attachButton.isEnabled = enabled
    }

    /** Replace the current (last) Lille reply — the "…thinking" line,
     *  or a preserved partial answer — with the given text. */
    private fun replaceCurrentReply(text: String) {
        val current = transcript.text.toString()
        val marker = "\nLille: "
        val idx = current.lastIndexOf(marker)
        transcript.text = if (idx >= 0) {
            current.substring(0, idx + marker.length) + text + "\n"
        } else {
            current + "\nLille: $text\n"
        }
        scrollToBottom()
    }

    private fun appendLine(who: String, text: String) {
        transcript.append("\n$who: $text\n")
        scrollToBottom()
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }
}
