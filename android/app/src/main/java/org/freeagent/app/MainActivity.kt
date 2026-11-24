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
 * FreeAgent — single-activity chat UI (v0.2).
 *
 * Chat is wired to the KEYLESS free brain: POST
 * https://text.pollinations.ai/openai (OpenAI-compatible, no API key,
 * no signup — see KEYLESS.md). POST is used deliberately: prompts in a
 * GET URL get logged and cached by intermediaries.
 *
 * Anonymous keyless limits are real (~1 request at a time, roughly 45s
 * between requests per IP): a 402/429 is handled by telling the user and
 * retrying once after the wait — never a crash, never silent loss.
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
            "You are FreeAgent, a helpful personal AI assistant running in an " +
            "open-source app on the user's own phone. You run on free public " +
            "models with no account and no key. Be concise, practical and " +
            "honest about your limits."
        private const val RATE_LIMIT_WAIT_SECONDS = 45L
        private const val MAX_HISTORY = 20
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

        sendButton.setOnClickListener { send() }
        attachButton.setOnClickListener {
            // Images AND files; the note in the outgoing message says what
            // was attached. */* keeps the picker open to any document.
            pickAttachment.launch(arrayOf("image/*", "*/*"))
        }
        findViewById<Button>(R.id.clearAttachmentButton).setOnClickListener { clearAttachment() }

        appendLine(
            "FreeAgent",
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
            appendLine("FreeAgent", "(Still waiting on the free brain — one request at a time. Your message will be sendable in a moment.)")
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
        askBrain(outgoing, isRetry = false)
    }

    private fun askBrain(userText: String, isRetry: Boolean) {
        busy = true
        setInputEnabled(false)
        appendLine("FreeAgent", "…thinking")

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (m in history.takeLast(MAX_HISTORY)) messages.put(m)
        messages.put(JSONObject().put("role", "user").put("content", userText))
        val bodyJson = JSONObject().put("model", MODEL).put("messages", messages)

        val request = Request.Builder()
            .url(TEXT_ENDPOINT)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    busy = false
                    setInputEnabled(true)
                    replaceThinking("Couldn't reach the free brain — check your connection and try again. Nothing was sent anywhere else.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                val text = try {
                    if (code == 200) {
                        val parsed = JSONObject(response.body?.string() ?: "")
                        parsed.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim()
                    } else ""
                } catch (e: Exception) {
                    ""
                } finally {
                    response.close()
                }
                mainHandler.post {
                    when {
                        code == 200 && text.isNotEmpty() -> {
                            busy = false
                            setInputEnabled(true)
                            history.add(JSONObject().put("role", "user").put("content", userText))
                            history.add(JSONObject().put("role", "assistant").put("content", text))
                            replaceThinking(text)
                        }
                        (code == 402 || code == 429) && !isRetry -> {
                            val wait = retryAfter ?: RATE_LIMIT_WAIT_SECONDS
                            replaceThinking("The free brain is rate-limited right now (anonymous tier). Queued — retrying automatically in about ${wait}s…")
                            mainHandler.postDelayed({
                                // Retry once; askBrain re-enters with busy still true.
                                askBrain(userText, isRetry = true)
                            }, wait * 1000)
                        }
                        else -> {
                            busy = false
                            setInputEnabled(true)
                            replaceThinking(
                                if (code == 402 || code == 429)
                                    "Still rate-limited by the free tier. Give it a minute and send again — your message wasn't lost, it just wasn't answered yet."
                                else
                                    "The free brain returned an error (HTTP $code). It's a best-effort free service — try again shortly."
                            )
                        }
                    }
                }
            }
        })
    }

    // ---------- UI helpers ----------

    private fun setInputEnabled(enabled: Boolean) {
        sendButton.isEnabled = enabled
        attachButton.isEnabled = enabled
    }

    /** Replace the trailing "…thinking" line with the real reply. */
    private fun replaceThinking(text: String) {
        val current = transcript.text.toString()
        val marker = "\nFreeAgent: …thinking\n"
        transcript.text = if (current.endsWith(marker)) {
            current.removeSuffix(marker) + "\nFreeAgent: $text\n"
        } else {
            current + "\nFreeAgent: $text\n"
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
