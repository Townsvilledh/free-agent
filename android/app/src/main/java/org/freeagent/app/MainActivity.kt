package org.freeagent.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
        private const val MAX_HISTORY = 20
        // v0.2.1 smart recovery: stream continuations + one refusal reframe.
        private const val MAX_CONTINUATIONS = 3
        private const val REFUSAL_NOTE =
            "The free model refused that phrasing, so I re-asked it as a concrete task:"
        private const val REFUSAL_STILL =
            "\n\n(The free model refused this one even after I re-asked it as a " +
            "concrete task. Try a more specific wording yourself — for example " +
            "\"Write a Python script that …\" naming exactly what it should do.)"
    }

    private lateinit var transcript: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var attachButton: ImageButton
    private lateinit var headerMascot: ImageView

    /** v0.10 UI polish: messages are bubbles now. This is the
     *  most recent Lille bubble — the one replaceCurrentReply
     *  rewrites as an answer streams in (the "…thinking" bubble
     *  first, then continuations and the final text). */
    private var lastReplyBubble: TextView? = null
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

    /** Chat parity (v0.10 fold-in): the visible send queue.
     *  While a reply is in flight the composer stays live; a
     *  message sent then is queued (bubble shown at once with
     *  a "queued" badge) and sent in order when the flight
     *  ends or is stopped. ChatQueue owns the order; the
     *  bubbles for waiting messages live in [queuedBubbles],
     *  FIFO-parallel to it, so the badge can be stripped when
     *  a message starts. */
    private val chatQueue = ChatQueue()
    private val queuedBubbles = ArrayDeque<Pair<TextView, String>>()

    /** The in-flight OkHttp call (so Stop can cancel it), a
     *  generation counter (so a cancelled call's late callback
     *  is ignored), the partial answer so far (so Stop keeps
     *  it, honestly marked), and the tracked delayed actions
     *  (pacing gate / cooldown retries Stop must cancel). */
    private var currentCall: Call? = null
    private var generation = 0
    private var currentPartial = ""
    private var currentCarry = ""
    private var replyOpen = false
    private var chatWaiting = false
    private val pendingActions = mutableListOf<Runnable>()
    private var defaultHint: CharSequence = ""

    /** Fallback law: when the keyless route is cooling down after a
     *  rate limit, this holds the wall-clock time it recovers. */
    private var cooldownUntilMs = 0L

    /** v0.10 rate-limit adjustment (Fallback Law, chat path):
     *  BrainPacing arithmetic, same as the task engine (v0.9.1).
     *  Chat is a live session, so the stamps live in memory —
     *  the durable task runner persists its own copies. */
    private var lastBrainCallAtMs = 0L
    private var consecutiveRateLimits = 0

    /** The verified Fluid identity behind this session (v0.10
     *  gate), for the greeting line. Null only if the gate let
     *  the app open with an uncheckable session state. */
    private var fluidIdentity: FluidIdentityStore.Identity? = null

    private val pickAttachment =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onAttachmentPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v0.10 Fluid login gate (LOGIN.md) — the gate guards
        // the UI only: background workers (TaskWorker,
        // ProactiveWorker) never pass through here and never
        // consult login state, so a logged-out app still runs
        // its durable tasks. An approval that landed while no
        // screen was alive is reconciled first.
        val identityStore = FluidIdentityStore(this)
        FluidLogin.init(this)
        FluidLogin.reconcilePending(identityStore)
        fluidIdentity = identityStore.load()
        val decision = FluidGate.decide(
            fluidIdentity?.username, FluidLogin.sessionStateFor(identityStore)
        )
        if (decision != FluidGate.Decision.OPEN) {
            startActivity(
                android.content.Intent(this, LoginActivity::class.java)
            )
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        transcript = findViewById(R.id.transcript)
        scrollView = findViewById(R.id.scrollView)
        input = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        attachButton = findViewById(R.id.attachButton)
        headerMascot = findViewById(R.id.headerMascot)
        defaultHint = input.hint ?: ""
        attachmentBar = findViewById(R.id.attachmentBar)
        attachmentThumb = findViewById(R.id.attachmentThumb)
        attachmentName = findViewById(R.id.attachmentName)

        findViewById<Button>(R.id.tasksButton).setOnClickListener {
            // v0.4: durable background tasks (OFFLINE.md) — create a
            // task there, run it, then close the app / go offline.
            startActivity(android.content.Intent(this, TasksActivity::class.java))
        }
        // Chat parity: while a reply is in flight the send
        // button IS the stop button (same slot); the composer
        // stays live and the keyboard's send key queues the
        // next message (see submitMessage).
        sendButton.setOnClickListener {
            if (busy) stopReply() else submitMessage()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitMessage()
                true
            } else {
                false
            }
        }
        attachButton.setOnClickListener {
            // Images AND files; the note in the outgoing message says what
            // was attached. */* keeps the picker open to any document.
            pickAttachment.launch(arrayOf("image/*", "*/*"))
        }
        findViewById<Button>(R.id.clearAttachmentButton).setOnClickListener { clearAttachment() }

        appendLine(
            "Lille",
            "Ready — free keyless brain, no account, no API key needed. " +
                (fluidIdentity?.let {
                    "Connected with your Fluid name @${it.username}. "
                } ?: "") +
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

    /**
     * Submit whatever is in the composer. Before v0.10, sending
     * during a reply was refused with a "still waiting" line and
     * the composer locked. Now (chat parity): the message joins
     * the transcript IMMEDIATELY — sent at once when Lille is
     * free, or badged "queued" and sent in order when the
     * current reply finishes or is stopped. A user's message is
     * never erased by a busy route or a failed send.
     */
    private fun submitMessage() {
        val raw = input.text.toString().trim()
        if (raw.isEmpty() && pendingAttachment == null) return

        var outgoing = raw
        val display = pendingAttachment?.let { (_, name, mime) ->
            val note = "[Attached file: $name (${mime ?: "unknown type"}). " +
                "Note: in this version the attachment is recorded by name only — " +
                "full file/image understanding is a next step, so do not claim to have seen its contents.]"
            outgoing = if (outgoing.isEmpty()) note else "$outgoing\n$note"
            (if (raw.isEmpty()) "" else "$raw ") + "[attachment: $name]"
        } ?: raw
        val bubble = appendLine("You", display)

        input.text.clear()
        clearAttachment()

        val position = chatQueue.offer(outgoing)
        if (position == 0) {
            chatQueue.beginFlight()
            busy = true
            updateComposerState()
            askBrain(outgoing, attempt = 0)
        } else {
            queuedBubbles.addLast(bubble to display)
            bubble.text = display + "\n· " + ChatQueue.badge(position)
        }
    }

    /** Stop the in-flight reply (the send button's other face).
     *  The partial answer stays in the transcript, honestly
     *  marked as stopped — never silently completed — and the
     *  next queued message, if any, goes next. */
    private fun stopReply() {
        generation++
        currentCall?.cancel()
        currentCall = null
        pendingActions.forEach { mainHandler.removeCallbacks(it) }
        pendingActions.clear()
        val partial = stitch(currentCarry, currentPartial)
        if (replyOpen) {
            replaceCurrentReply(
                if (partial.isNotEmpty()) {
                    partial + "\n\n(Stopped by you — this reply is incomplete.)"
                } else {
                    "(Stopped by you.)"
                }
            )
        }
        currentPartial = ""
        currentCarry = ""
        replyOpen = false
        finishFlight()
    }

    /** A reply cycle ended (delivered, errored, or stopped):
     *  release the flight and start the next queued message,
     *  if one is waiting — in order, badge stripped. */
    private fun finishFlight() {
        busy = false
        chatWaiting = false
        chatQueue.endFlight()
        currentCall = null
        replyOpen = false
        val next = chatQueue.startNext()
        if (next == null) {
            updateComposerState()
            return
        }
        val entry = if (queuedBubbles.isEmpty()) null else queuedBubbles.removeFirst()
        entry?.let { (bubble, display) -> bubble.text = display }
        busy = true
        updateComposerState()
        askBrain(next, attempt = 0)
    }

    /** Send button + header mascot follow the flight state:
     *  the button morphs Send → Stop while busy (composer and
     *  attach stay live), and the mascot carries the state —
     *  WAITING while BrainPacing holds the call, WORKING while
     *  a reply is actually being produced, IDLE at rest. */
    private fun updateComposerState() {
        if (busy) {
            sendButton.setImageResource(R.drawable.ic_stop)
            sendButton.contentDescription = "Stop"
            input.hint = "Type another message — the send key queues it"
        } else {
            sendButton.setImageResource(R.drawable.ic_send)
            sendButton.contentDescription = "Send message"
            input.hint = defaultHint
        }
        val mascot = MascotStates.forChat(busy, chatWaiting)
        headerMascot.setImageResource(
            resources.getIdentifier(
                MascotStates.drawableName(mascot), "drawable", packageName
            )
        )
    }

    /** Delayed work Stop must be able to cancel (pacing gate,
     *  cooldown retries). */
    private fun schedule(delayMs: Long, block: () -> Unit) {
        lateinit var r: Runnable
        r = Runnable {
            pendingActions.remove(r)
            block()
        }
        pendingActions.add(r)
        mainHandler.postDelayed(r, delayMs)
    }

    /** Main-thread work from a network callback, dropped if
     *  Stop (or a newer flight) has superseded its generation. */
    private fun postIfCurrent(gen: Int, block: () -> Unit) {
        mainHandler.post { if (gen == generation) block() }
    }

    /**
     * Fallback-law mirror (see ROUTES.md): on a rate limit the route
     * cools down and the SAME task auto-continues after the wait —
     * the user never re-sends.
     *
     * v0.10 rate-limit adjustment: EVERY brain call from chat —
     * first asks, continuations, reframes, retries — passes the
     * BrainPacing gate first (the same arithmetic the task
     * engine got in v0.9.1): a minimum interval between calls,
     * Retry-After honoured, escalating cooldowns on consecutive
     * 402/429. While the one keyless text route is merely busy,
     * the behaviour is "she thinks a little longer" — the call
     * waits and fires when the route allows; there is no retry
     * cap and no error wall for a busy route. (Honest count:
     * exactly ONE keyless text route exists today — see
     * ROUTES.md — so pacing + queueing is its resilience.)
     */
    private fun askBrain(userText: String, attempt: Int) {
        askBrain(userText, attempt, reframed = false, carry = "", continuations = 0,
                 historyText = userText)
    }

    /** The pacing gate: hold the call until the free route's
     *  minimum interval has passed, then hand over to the call
     *  itself. The wait is silent thinking time, never an error. */
    private fun askBrain(userText: String, attempt: Int, reframed: Boolean,
                         carry: String, continuations: Int, historyText: String) {
        val remaining = BrainPacing.remainingIntervalMs(
            lastBrainCallAtMs, System.currentTimeMillis()
        )
        if (remaining > 0) {
            busy = true
            chatWaiting = true
            updateComposerState()
            schedule(remaining) {
                askBrainNow(userText, attempt, reframed, carry, continuations, historyText)
            }
            return
        }
        askBrainNow(userText, attempt, reframed, carry, continuations, historyText)
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
    private fun askBrainNow(userText: String, attempt: Int, reframed: Boolean,
                            carry: String, continuations: Int, historyText: String) {
        busy = true
        chatWaiting = false
        updateComposerState()
        val gen = generation
        currentCarry = carry
        currentPartial = ""
        if (carry.isEmpty()) {
            appendLine("Lille", "…thinking")
            replyOpen = true
        }

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

        lastBrainCallAtMs = System.currentTimeMillis()
        val call = client.newCall(request)
        currentCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                postIfCurrent(gen) {
                    if (carry.isNotEmpty() && continuations < MAX_CONTINUATIONS) {
                        // Dropped before/while streaming with a partial
                        // answer preserved: auto-continue, don't lose it.
                        replaceCurrentReply(carry + "\n\n(Connection dropped — continuing automatically…)")
                        askBrain(userText, attempt, reframed, carry, continuations + 1, historyText)
                    } else {
                        replaceCurrentReply("Couldn't reach the free brain — check your connection and try again. Nothing was sent anywhere else.")
                        finishFlight()
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
                                        // Mirror the partial so Stop can
                                        // keep it, honestly marked.
                                        currentPartial = chunk
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
                                    currentPartial = chunk
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
                postIfCurrent(gen) {
                    if (code == 200) {
                        // First success ends any rate-limit streak.
                        consecutiveRateLimits =
                            BrainPacing.nextCounter(consecutiveRateLimits, code)
                    }
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
                            history.add(JSONObject().put("role", "user").put("content", historyText))
                            history.add(JSONObject().put("role", "assistant").put("content", full))
                            replaceCurrentReply(
                                when {
                                    reframed && isRefusalReply(full) -> full + REFUSAL_STILL
                                    reframed -> "$REFUSAL_NOTE\n\n$full"
                                    else -> full
                                }
                            )
                            finishFlight()
                        }
                        (code == 402 || code == 429) -> {
                            // Busy route, not a failure: escalate the
                            // cooldown (Retry-After wins when larger),
                            // keep the SAME message queued, and fire
                            // again when the route allows. No cap on
                            // attempts — she thinks a little longer,
                            // she never hits an error wall for this.
                            val waitMs = BrainPacing.cooldownMs(
                                retryAfter, consecutiveRateLimits
                            )
                            consecutiveRateLimits = BrainPacing.nextCounter(
                                consecutiveRateLimits, code
                            )
                            cooldownUntilMs = System.currentTimeMillis() + waitMs
                            // Honest waiting (Direction 8): the
                            // status lives IN the conversation, the
                            // mascot shows WAITING, the retry time
                            // is named, and the message stays
                            // queued — never a full-screen error.
                            chatWaiting = true
                            updateComposerState()
                            replaceCurrentReply(
                                "The free brain is busy right now — its anonymous " +
                                    "tier rate-limits. Your message is queued and " +
                                    "Lille continues automatically in about " +
                                    "${BrainPacing.describeWait(waitMs)}. No need " +
                                    "to re-send; nothing is sent anywhere else."
                            )
                            schedule(waitMs) {
                                // Auto-continue the SAME task after cooldown;
                                // askBrain re-enters with busy still true.
                                askBrain(userText, attempt + 1, reframed, "", 0, historyText)
                            }
                        }
                        else -> {
                            replaceCurrentReply(
                                "The free brain returned an error (HTTP $code). It's a best-effort free service — try again shortly."
                            )
                            finishFlight()
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

    /** Replace the current (last) Lille reply — the "…thinking"
     *  bubble, or a preserved partial answer — with the given
     *  text. Falls back to a fresh bubble if none is live. */
    private fun replaceCurrentReply(text: String) {
        val bubble = lastReplyBubble
        if (bubble != null && bubble.parent === transcript) {
            bubble.text = text
            scrollToBottom()
        } else {
            appendLine("Lille", text)
        }
    }

    /** One message, one bubble (v0.10 UI polish): Lille's are
     *  dark warm surfaces hugging the left; the user's are
     *  glossy Bitcoin-orange hugging the right, dark text on
     *  the orange for contrast. Same call contract as the old
     *  plain-text transcript — every display path in this
     *  activity goes through here or replaceCurrentReply. */
    private fun appendLine(who: String, text: String): TextView {
        val bubble = TextView(this)
        bubble.text = text
        bubble.textSize = 15f
        bubble.setPadding(dp(14), dp(9), dp(14), dp(9))
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(3)
        params.bottomMargin = dp(3)
        if (who == "You") {
            bubble.setBackgroundResource(R.drawable.bg_bubble_user)
            bubble.setTextColor(Color.parseColor("#1A1206"))
            params.gravity = Gravity.END
            params.marginStart = dp(48)
        } else {
            bubble.setBackgroundResource(R.drawable.bg_bubble_lille)
            bubble.setTextColor(Color.parseColor("#F5EFE7"))
            params.gravity = Gravity.START
            params.marginEnd = dp(48)
            lastReplyBubble = bubble
        }
        bubble.layoutParams = params
        transcript.addView(bubble)
        scrollToBottom()
        return bubble
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }
}
