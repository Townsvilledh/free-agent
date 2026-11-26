package org.freeagent.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
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
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Lille (project: FreeAgent) — chat UI.
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
 * v0.11 — MULTIPLE CHATS: conversations live in the durable
 * ChatStore (lille_chats.json), not in memory. The screen
 * renders the ACTIVE chat from the store; each chat owns a
 * [Flight] (send queue, in-flight call, partial reply, brain
 * history), so a reply keeps belonging to the chat it was
 * sent in even while the user reads another chat, and a
 * message is written to the store BEFORE the brain is asked
 * — process death no longer loses a conversation. The
 * brain-call machinery (BrainPacing gate, queue, stop,
 * refusal recovery, stream continuations) is v0.10.1's,
 * parameterised by chat; the pacing stamps stay global
 * because the free route limits per IP, not per chat.
 *
 * Attachments: the picker lets the user attach an image or file;
 * its name/type ride along as a text note and images get a thumbnail
 * preview. Full multimodal understanding of attachments is a documented
 * next step — the app does NOT pretend the model saw the file.
 *
 * v0.13 — CHAT→TASK: the chat is the command surface. A job
 * given in chat is detected by the pure JobDetect component
 * (deterministic signals first, one clarifying question when
 * genuinely ambiguous, the model's own question resolving
 * through the same path), taken on as a durable task in the
 * shared TaskStore, and its graded state reports land back
 * in this thread (ChatTaskLink / ChatTaskReporter). The
 * Tasks screen is a window into the same tasks, never a
 * second workplace.
 *
 * Keys: NONE are needed. A user's OWN optional free keys
 * (Groq, Gemini, Cerebras, OpenRouter, AgentRouter, or a
 * custom endpoint) can be added in Settings → "Your API
 * keys" (v0.12.1): they live in the Keystore-backed
 * encrypted vault, are never shipped in the app, never
 * shared, never collected — and when present they join the
 * brain chain behind the keyless routes (OwnKeyRoutes).
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
            "standard-practice advice. In chat you cannot perform " +
            "actions: nothing you say here does anything by itself. " +
            "Never claim to have done, created, fetched, submitted or " +
            "completed anything you did not actually do through a tool " +
            "in a task. When the user gives you a job in chat, say you " +
            "are starting it and what happens next, in the present " +
            "tense — never narrate a finished result. If the user is " +
            "giving you a job to do in the world — not a question to " +
            "answer — and no task has been started for it, ask exactly " +
            "this and nothing more about doing it: " +
            "\"Want me to take that on as a task?\""
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

    /**
     * One chat's live reply machinery — v0.10.1's single set
     * of flight fields, per chat. Created on the chat's first
     * sent message and kept for the process's life: switch
     * chats and the flight keeps its queue, its partial, and
     * its own brain history. [history] is seeded from the
     * store at creation (every stored message before the
     * first in-flight one) and extended on each delivery —
     * the store is the durable record, this is the brain's
     * working context.
     */
    private class Flight {
        val queue = ChatQueue()
        val history = mutableListOf<JSONObject>()
        var seeded = false
        var call: Call? = null
        var generation = 0
        var partial = ""
        var carry = ""
        var replyOpen = false
        var waiting = false
        /** The text the live reply bubble currently carries
         *  (thinking / busy / dropped-note / partial), kept
         *  so a re-render can re-attach the bubble exactly. */
        var statusText: String? = null
        val pendingActions = mutableListOf<Runnable>()
        /** v0.12 brain-chain retry streak (BrainFailure):
         *  consecutive full-chain failures for the in-flight
         *  message, driving the escalating waits. Reset on
         *  any delivered answer. */
        var brainStreak = 0
    }

    private lateinit var transcript: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var attachButton: ImageButton
    private lateinit var headerMascot: ImageView

    /** The RENDERED chat's live reply bubble — the one
     *  replaceCurrentReply rewrites as an answer streams in.
     *  Null whenever no flight bubble is on screen; stored
     *  (replayed) bubbles never become it. */
    private var liveBubble: TextView? = null
    private lateinit var attachmentBar: View
    private lateinit var attachmentThumb: ImageView
    private lateinit var attachmentName: TextView

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var chatStore: ChatStore

    /** The chat on screen; the chat the transcript was last
     *  rendered from; and the store mtime at that render —
     *  compared on resume so a reply that landed in the
     *  store while this screen was dead still appears. */
    private var activeChatId: String = ""
    private var renderedChatId: String = ""
    private var renderedMtime: Long = -1L

    private val flights = mutableMapOf<String, Flight>()

    /** Bubbles of the RENDERED chat's queued user messages,
     *  FIFO-parallel to that chat flight's queue, so a badge
     *  can be stripped when a message starts. Rebuilt by
     *  every render. */
    private val queuedBubbles = ArrayDeque<Pair<TextView, String>>()

    /** Currently picked attachment (uri + display name + mime), or null. */
    private var pendingAttachment: Triple<Uri, String, String?>? = null

    private var defaultHint: CharSequence = ""

    @Volatile
    private var uiAlive = false

    /** Fallback law: when the keyless route is cooling down after a
     *  rate limit, this holds the wall-clock time it recovers. */
    private var cooldownUntilMs = 0L

    /** v0.10 rate-limit adjustment (Fallback Law, chat path):
     *  BrainPacing arithmetic, same as the task engine (v0.9.1).
     *  Global on purpose: the anonymous tier limits per IP,
     *  not per chat. Chat is a live session, so the stamps
     *  live in memory — the durable task runner persists its
     *  own copies. */
    private var lastBrainCallAtMs = 0L
    private var consecutiveRateLimits = 0

    /** v0.12 per-route health (BrainFailure): consecutive
     *  failure counts + cooldown deadlines per brain route.
     *  A route that keeps failing is demoted (skipped while
     *  it rests); recovery is automatic when the deadline
     *  passes, and a success clears the record. In-memory on
     *  purpose — a process restart simply re-probes every
     *  route fresh. */
    private val routeFailures = mutableMapOf<BrainFailure.Route, Int>()
    private val routeCoolingUntil = mutableMapOf<BrainFailure.Route, Long>()

    /** v0.12.1 own-key routes: the vault (key NAMES are read
     *  for presence; a key VALUE is resolved only inside
     *  tryOwnKeyRoute, at the moment of its call) and the
     *  chain the current conversation walks — the keyless
     *  pair plus whichever own-key routes have a saved key.
     *  Refreshed at the start of every fresh ask, so a key
     *  added in Settings joins the very next message. */
    private val vault by lazy { DeviceVault(this) }
    private var brainChain: List<BrainFailure.Route> = BrainFailure.KEYLESS_CHAIN

    private fun refreshBrainChain() {
        brainChain = BrainFailure.chain(
            OwnKeyRoutes.presentRoutes { name -> vault.hasSecret(name) }
        )
    }

    private fun noteRouteFailure(route: BrainFailure.Route) {
        val n = (routeFailures[route] ?: 0) + 1
        routeFailures[route] = n
        routeCoolingUntil[route] =
            System.currentTimeMillis() + BrainFailure.routeCooldownMs(n)
    }

    private fun noteRouteSuccess(route: BrainFailure.Route) {
        routeFailures.remove(route)
        routeCoolingUntil.remove(route)
    }

    /** The verified Fluid identity behind this session (v0.10
     *  gate), for the greeting line. Null only if the gate let
     *  the app open with an uncheckable session state. */
    private var fluidIdentity: FluidIdentityStore.Identity? = null

    private val pickAttachment =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onAttachmentPicked(uri)
        }

    private fun flightFor(chatId: String): Flight =
        flights.getOrPut(chatId) { Flight() }

    /** The rendered chat's flight state drives the composer:
     *  Send morphs to Stop while ITS reply is in flight. */
    private fun renderedBusy(): Boolean =
        flights[activeChatId]?.queue?.inFlight == true

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

        chatStore = ChatStore(this)
        BackupWorker.schedule(this)

        findViewById<Button>(R.id.tasksButton).setOnClickListener {
            // v0.4: durable background tasks (OFFLINE.md) — create a
            // task there, run it, then close the app / go offline.
            startActivity(android.content.Intent(this, TasksActivity::class.java))
        }
        findViewById<Button>(R.id.chatsButton).setOnClickListener {
            startActivity(android.content.Intent(this, ChatListActivity::class.java))
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.newChatButton).setOnClickListener {
            renderChat(chatStore.createChat())
        }
        // Chat parity: while a reply is in flight the send
        // button IS the stop button (same slot); the composer
        // stays live and the keyboard's send key queues the
        // next message (see submitMessage).
        sendButton.setOnClickListener {
            if (renderedBusy()) stopReply() else submitMessage()
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

        uiAlive = true
        activeChatId = chatStore.ensureActiveChat()
        renderChat(activeChatId)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Returning from the chat list (CLEAR_TOP reuses this
        // instance): the active chat may have changed.
        if (::chatStore.isInitialized) {
            val current = ChatData.activeId(chatStore.root())
            if (current.isNotEmpty()) renderChat(current)
        }
    }

    override fun onResume() {
        super.onResume()
        uiAlive = true
        if (::chatStore.isInitialized && renderedChatId.isNotEmpty()) {
            val current = ChatData.activeId(chatStore.root())
            if ((current.isNotEmpty() && current != renderedChatId) ||
                chatStore.lastModified() != renderedMtime
            ) {
                renderChat(if (current.isNotEmpty()) current else renderedChatId)
            }
        }
        checkForUpdateBanner()
    }

    /** v0.12.1 in-app update check: on app open (the
     *  checker throttles itself to once per 6 h), show the
     *  dismissible banner when the distribution branch
     *  carries a newer version that wasn't dismissed.
     *  Tapping the banner text opens the one-tap APK link —
     *  the stable signing line installs it in place. */
    private fun checkForUpdateBanner() {
        val banner = findViewById<LinearLayout>(R.id.updateBanner)
        val bannerText = findViewById<TextView>(R.id.updateBannerText)
        val dismiss = findViewById<Button>(R.id.updateBannerDismiss)
        UpdateChecker.maybeCheck(this, notify = true) { remote ->
            if (!uiAlive) return@maybeCheck
            if (remote != null &&
                UpdateCheck.shouldShowBanner(
                    BuildConfig.VERSION_CODE,
                    UpdateChecker.dismissedCode(this),
                    remote
                )
            ) {
                bannerText.text = UpdateCheck.bannerText(remote)
                banner.visibility = View.VISIBLE
                bannerText.setOnClickListener {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(UpdateCheck.APK_URL)
                        )
                    )
                }
                dismiss.setOnClickListener {
                    UpdateChecker.dismiss(this, remote.versionCode)
                    banner.visibility = View.GONE
                }
            } else {
                banner.visibility = View.GONE
            }
        }
    }

    override fun onPause() {
        // Leaving the screen is a backup trigger: the manager
        // flushes whatever the held key allows.
        if (::chatStore.isInitialized) BackupManager.requestBackup(this)
        super.onPause()
    }

    override fun onDestroy() {
        uiAlive = false
        super.onDestroy()
    }

    // ---------- rendering ----------

    /**
     * Rebuild the transcript from the store for [chatId]:
     * every saved message as a bubble, the flight's queued
     * user messages badged, and — when a reply is in flight —
     * its live bubble re-attached so the flight continues
     * seamlessly on screen.
     */
    private fun renderChat(chatId: String) {
        activeChatId = chatId
        renderedChatId = chatId
        transcript.removeAllViews()
        liveBubble = null
        queuedBubbles.clear()

        val root = chatStore.root()
        val messages = ChatData.messages(root, chatId)
        val flight = flights[chatId]
        val waiting = flight?.queue?.waitingCount ?: 0
        val userBubbles = mutableListOf<Pair<TextView, String>>()
        for (m in messages) {
            val role = m.optString("role")
            val display = if (m.has("display")) m.optString("display")
                else m.optString("content")
            val bubble = appendLine(if (role == "user") "You" else "Lille", display)
            if (role == "user") userBubbles.add(bubble to display)
        }
        if (waiting > 0 && userBubbles.size >= waiting) {
            val start = userBubbles.size - waiting
            for (i in 0 until waiting) {
                val (bubble, display) = userBubbles[start + i]
                bubble.text = styledText(display + "\n· " + ChatQueue.badge(i + 1))
                queuedBubbles.addLast(bubble to display)
            }
        }
        if (flight != null && flight.queue.inFlight) {
            val liveText = when {
                flight.partial.isNotEmpty() || flight.carry.isNotEmpty() ->
                    stitch(flight.carry, flight.partial)
                flight.statusText != null -> flight.statusText!!
                flight.replyOpen -> "…thinking"
                else -> null
            }
            if (liveText != null) liveBubble = appendLine("Lille", liveText)
        }
        if (messages.isEmpty() && (flight == null || !flight.queue.inFlight)) {
            appendGreeting()
        }
        renderedMtime = chatStore.lastModified()
        updateComposerState()
    }

    /** The first-run greeting. Transient — never stored, so
     *  a chat's saved history is purely what the user and
     *  Lille actually said. */
    private fun appendGreeting() {
        val freshWorld = chatStore.chatCount() <= 1 && chatStore.isEmpty()
        appendLine(
            "Lille",
            "Ready — free keyless brain, no account, no API key needed. " +
                (fluidIdentity?.let {
                    "Connected with your Fluid name @${it.username}. "
                } ?: "") +
                "Optional: your OWN free API keys (Groq, Gemini, Cerebras, " +
                "OpenRouter, AgentRouter) can be added in Settings → Your API " +
                "keys (encrypted vault) — never required, never shared." +
                if (freshWorld) {
                    "\n\nReinstalling, or on a new phone? Settings → Backup & " +
                        "restore can bring your previous chats and tasks back."
                } else {
                    ""
                }
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
     *
     * v0.11: the message is written to the chat's store FIRST —
     * before the brain is asked — so it survives the app
     * closing mid-reply, and a backup taken tonight contains
     * it even if the reply lands tomorrow.
     */
    private fun submitMessage() {
        val raw = input.text.toString().trim()
        if (raw.isEmpty() && pendingAttachment == null) return
        val chatId = activeChatId
        // Fluency intake (v0.13): captured now — the picker
        // state is cleared below, but whether this message
        // carried a file is part of what it means.
        val hadAttachment = pendingAttachment != null

        var outgoing = raw
        val display = pendingAttachment?.let { (_, name, mime) ->
            val note = "[Attached file: $name (${mime ?: "unknown type"}). " +
                "Note: in this version the attachment is recorded by name only — " +
                "full file/image understanding is a next step, so do not claim to have seen its contents.]"
            outgoing = if (outgoing.isEmpty()) note else "$outgoing\n$note"
            (if (raw.isEmpty()) "" else "$raw ") + "[attachment: $name]"
        } ?: raw
        val bubble = appendLine("You", display)
        chatStore.appendMessage(chatId, "user", outgoing, display)
        renderedMtime = chatStore.lastModified()
        BackupManager.requestBackup(this)

        input.text.clear()
        clearAttachment()

        // v0.12 — WALLET PROMPTS (WALLET.md): money moves
        // only through the deterministic parser, never
        // through the brain. An exact supported phrasing is
        // prepared from live chain state and gated by the
        // signer; a send-shaped message that doesn't parse
        // exactly gets a question back, not a guess. Anything
        // else falls through to the brain as ordinary chat.
        when (val w = WalletPrompt.parse(raw)) {
            is WalletPrompt.Result.Parsed -> {
                // v0.13.1: the trailing "from fluid" clause
                // chooses the source — the user's main Fluid
                // wallet (FluidTx flow) instead of her own.
                if (w.intent.source == WalletPrompt.Source.FLUID) {
                    handleFluidWalletPrompt(chatId, w.intent)
                } else {
                    handleWalletPrompt(chatId, w.intent)
                }
                return
            }
            is WalletPrompt.Result.Ambiguous -> {
                appendAssistantMessage(chatId, w.question)
                return
            }
            WalletPrompt.Result.NotWallet -> Unit
        }

        // v0.13 — CHAT→TASK (the chat is the command surface):
        // a job given here is taken on as a durable task by
        // Lille herself — the user never has to visit the
        // Tasks screen to make her work. Detection is the
        // pure JobDetect (deterministic first; the model's
        // own clarifying question resolves through the same
        // pending-instruction path). A detected job creates
        // the task, acknowledges it in this chat in the
        // present tense — truthfully, because the record now
        // exists — and the engine's graded reports land back
        // in this thread (ChatTaskReporter). An ambiguous ask
        // gets ONE short question, never a guess.
        when (val jd = JobDetect.classify(
            raw, ChatData.messages(chatStore.root(), chatId),
            hasAttachment = hadAttachment
        )) {
            is JobDetect.Outcome.StartTask -> {
                // A fresh detection starts THIS message (its
                // full stored form, attachment note included);
                // a resolved "yes" starts the earlier ask the
                // question was about, recovered from the thread.
                startChatTask(
                    chatId,
                    if (jd.instruction == raw) outgoing else jd.instruction
                )
                return
            }
            is JobDetect.Outcome.AskQuestion -> {
                appendAssistantMessage(chatId, JobDetect.QUESTION)
                return
            }
            JobDetect.Outcome.NotJob -> Unit
        }

        val flight = flightFor(chatId)
        if (!flight.seeded) {
            // Seed the brain history from the store: every
            // message before the one just appended.
            val prior = ChatData.messages(chatStore.root(), chatId).dropLast(1)
            for (m in prior) {
                flight.history.add(
                    JSONObject()
                        .put("role", m.optString("role"))
                        .put("content", m.optString("content"))
                )
            }
            flight.seeded = true
        }
        val position = flight.queue.offer(outgoing)
        if (position == 0) {
            flight.queue.beginFlight()
            askBrain(chatId, outgoing, attempt = 0)
        } else {
            queuedBubbles.addLast(bubble to display)
            bubble.text = styledText(display + "\n· " + ChatQueue.badge(position))
        }
        updateComposerState()
    }

    /** A Lille message outside the brain flow (wallet
     *  replies, parser questions): shown in the rendered chat
     *  and stored like any other assistant message. */
    private fun appendAssistantMessage(chatId: String, text: String) {
        if (chatId == renderedChatId && uiAlive) appendLine("Lille", text)
        chatStore.appendMessage(chatId, "assistant", text)
        if (chatId == renderedChatId) renderedMtime = chatStore.lastModified()
        BackupManager.requestBackup(this)
    }

    /** Take a chat-given job on as a durable task (v0.13):
     *  create the record in the SAME store the Tasks screen
     *  reads (one task list, two windows), link it to this
     *  chat, stamp the started state as already reported —
     *  the acknowledgement below IS that report — and hand
     *  it to the existing engine via TaskWorker. Nothing
     *  about the engine changes: checkpoints, waiting
     *  states, BrainPacing and the verified finish all apply
     *  exactly as for a task created on the Tasks screen. */
    private fun startChatTask(chatId: String, instruction: String) {
        val taskStore = TaskStore(this)
        // The task carries what the user sent, in full, plus
        // the standing fluency direction: gather missing
        // pieces herself during execution (ChatTaskLink).
        val task = taskStore.create(
            instruction + "\n\n" + ChatTaskLink.INTAKE_NOTE
        )
        val taskId = task.optString("id")
        taskStore.update(taskId) {
            it.put(ChatTaskLink.ORIGIN_FIELD, chatId)
            it.put(ChatTaskLink.REPORTED_FIELD, TaskStates.RUNNING)
        }
        TaskWorker.enqueue(this, taskId)
        appendAssistantMessage(
            chatId,
            "Starting that now — I've taken it on as a task, and " +
                "I'll report back here as it lands. You can watch " +
                "it any time in the Tasks screen."
        )
    }

    private val walletEngine by lazy { WalletEngine(this) }

    /** A parsed send prompt (v0.12): prepare from live state,
     *  then the gate decides — autonomous-within-caps sends
     *  and reports; anything needing approval shows the
     *  approval sheet HERE, in the chat the user is looking
     *  at ("sign where you stand"); if this chat isn't on
     *  screen, nothing is signed and the message says how to
     *  approve. */
    private fun handleWalletPrompt(
        chatId: String,
        intent: WalletPrompt.SendIntent
    ) {
        val wallet = AgentWallet(this)
        if (!wallet.hasWallet()) {
            appendAssistantMessage(
                chatId,
                "I don't have my own wallet yet, so there's nothing for " +
                    "me to send from. Create it in Settings → Lille's " +
                    "wallet, fund it with what I may spend, then ask me " +
                    "again in exactly that phrasing. Nothing was sent."
            )
            return
        }
        appendAssistantMessage(chatId, "Preparing that send from my own wallet…")
        walletEngine.prepareFromPrompt(intent) { outcome ->
            when (outcome) {
                is WalletEngine.PrepareOutcome.Failed ->
                    appendAssistantMessage(chatId, outcome.message)
                is WalletEngine.PrepareOutcome.Ready -> {
                    val prepared = outcome.prepared
                    walletEngine.execute(
                        prepared, userApproved = false, rawAmountConfirmed = false
                    ) { exec ->
                        when (exec) {
                            WalletEngine.ExecOutcome.NeedsApproval -> {
                                if (chatId == renderedChatId && uiAlive &&
                                    !isFinishing
                                ) {
                                    WalletApprovalUi.show(
                                        this, prepared,
                                        wallet.address() ?: "",
                                        onApprove = { rawConfirmed ->
                                            appendAssistantMessage(
                                                chatId,
                                                "Approved — signing and sending " +
                                                    "from my wallet…"
                                            )
                                            walletEngine.execute(
                                                prepared,
                                                userApproved = true,
                                                rawAmountConfirmed = rawConfirmed
                                            ) { done ->
                                                appendAssistantMessage(
                                                    chatId, walletExecText(done)
                                                )
                                            }
                                        },
                                        onCancel = {
                                            appendAssistantMessage(
                                                chatId,
                                                "Cancelled — nothing was sent."
                                            )
                                        }
                                    )
                                } else {
                                    appendAssistantMessage(
                                        chatId,
                                        "I've prepared it: " +
                                            "${prepared.amountDisplay} " +
                                            "${prepared.tokenSymbol} to " +
                                            "${prepared.recipientLabel} on " +
                                            (WalletGate.chainByKey(prepared.chainKey)?.label
                                                ?: prepared.chainKey) +
                                            ". Approving only happens on the " +
                                            "approval sheet while you're looking " +
                                            "at this chat — ask me again here " +
                                            "and I'll show it. Nothing was sent."
                                    )
                                }
                            }
                            else -> appendAssistantMessage(
                                chatId, walletExecText(exec)
                            )
                        }
                    }
                }
            }
        }
    }

    /** A parsed send prompt with "from fluid" (v0.13.1):
     *  a MAIN-WALLET send. Lille prepares it, checks the
     *  session actually granted transaction requests (a
     *  sign-in-only session degrades honestly, here, before
     *  anything is prepared), then sends eth_sendTransaction
     *  over the session with Fluid's own sheet — opened
     *  inside Lille — as the only approval. The result
     *  reported in chat is Fluid's answer: the hash it
     *  broadcast, or its error verbatim. Her caps, her
     *  signer and her wallet are never involved. */
    private fun handleFluidWalletPrompt(
        chatId: String,
        intent: WalletPrompt.SendIntent
    ) {
        val identity = FluidIdentityStore(this).load()
        if (identity == null) {
            appendAssistantMessage(
                chatId,
                "That send is from your Fluid wallet, but no Fluid name " +
                    "is connected here — connect it first, then ask me " +
                    "again. Nothing was sent."
            )
            return
        }
        FluidLogin.init(this)
        val granted = FluidLogin.grantedFor(identity.address)
        if (granted == null) {
            appendAssistantMessage(
                chatId,
                "Your Fluid session isn't live right now, so Lille can't " +
                    "ask it for anything. Reconnect your Fluid name, then " +
                    "ask me again. Nothing was sent."
            )
            return
        }
        FluidTx.txUnavailableReason(granted, intent.chainKey)?.let { reason ->
            appendAssistantMessage(chatId, reason)
            return
        }
        appendAssistantMessage(chatId, "Preparing that send from your Fluid wallet…")
        walletEngine.prepareFluidSend(intent) { outcome ->
            when (outcome) {
                is WalletEngine.FluidSendOutcome.Failed ->
                    appendAssistantMessage(chatId, outcome.message)
                is WalletEngine.FluidSendOutcome.Ready -> {
                    val send = outcome.send
                    // The payload gate once more, at the last
                    // moment before the request exists.
                    val refusal = FluidTx.validateCall(send.to, send.dataHex)
                    if (refusal != null) {
                        appendAssistantMessage(chatId, refusal)
                        return@prepareFluidSend
                    }
                    appendAssistantMessage(chatId, FluidTx.summaryText(send))
                    // Fluid's approval must be on screen when
                    // the request lands: open the sheet first,
                    // then send the request over the session.
                    if (chatId == renderedChatId && uiAlive && !isFinishing) {
                        startActivity(Intent(this, FluidSheetActivity::class.java))
                    }
                    FluidLogin.requestSendTransaction(
                        identity.address, send.chainId,
                        FluidTx.txParamsJson(send)
                    ) { txHash, error ->
                        runOnUiThread {
                            if (txHash != null) {
                                WalletStore(this).recordActivity(
                                    "SENT-FLUID",
                                    "Sent from your Fluid wallet: " +
                                        "${send.amountDisplay} " +
                                        "${send.tokenSymbol} → " +
                                        "${send.recipient} on " +
                                        (WalletGate.chainByKey(send.chainKey)?.label
                                            ?: send.chainKey),
                                    txHash
                                )
                                appendAssistantMessage(
                                    chatId, FluidTx.resultText(send, txHash)
                                )
                            } else {
                                appendAssistantMessage(
                                    chatId,
                                    FluidTx.errorText(
                                        error ?: "The request was refused."
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun walletExecText(outcome: WalletEngine.ExecOutcome): String =
        when (outcome) {
            is WalletEngine.ExecOutcome.Sent -> outcome.message
            is WalletEngine.ExecOutcome.Refused -> outcome.reason
            is WalletEngine.ExecOutcome.Failed -> outcome.message
            WalletEngine.ExecOutcome.NeedsApproval ->
                "That still needs your approval on the sheet — nothing was sent."
        }

    /** Stop the rendered chat's in-flight reply (the send
     *  button's other face). The partial answer stays in the
     *  transcript — and now in the store — honestly marked as
     *  stopped, never silently completed; the next queued
     *  message, if any, goes next. */
    private fun stopReply() {
        val chatId = activeChatId
        val flight = flights[chatId] ?: return
        if (!flight.queue.inFlight) return
        flight.generation++
        flight.call?.cancel()
        flight.call = null
        flight.pendingActions.forEach { mainHandler.removeCallbacks(it) }
        flight.pendingActions.clear()
        val partial = stitch(flight.carry, flight.partial)
        if (flight.replyOpen) {
            val shown = if (partial.isNotEmpty()) {
                partial + "\n\n(Stopped by you — this reply is incomplete.)"
            } else {
                "(Stopped by you.)"
            }
            replaceCurrentReply(chatId, shown)
            chatStore.appendMessage(chatId, "assistant", shown)
            renderedMtime = chatStore.lastModified()
        }
        flight.partial = ""
        flight.carry = ""
        flight.replyOpen = false
        finishFlight(chatId)
    }

    /** A reply cycle ended (delivered, errored, or stopped):
     *  release the flight and start the next queued message
     *  for THIS chat, if one is waiting — in order, badge
     *  stripped (on screen only when this chat is rendered). */
    private fun finishFlight(chatId: String) {
        val flight = flightFor(chatId)
        flight.queue.endFlight()
        flight.waiting = false
        flight.call = null
        flight.replyOpen = false
        if (chatId == renderedChatId) liveBubble = null
        val next = flight.queue.startNext()
        if (next == null) {
            if (chatId == activeChatId) updateComposerState()
            return
        }
        if (chatId == renderedChatId) {
            val entry = if (queuedBubbles.isEmpty()) null else queuedBubbles.removeFirst()
            entry?.let { (bubble, display) -> bubble.text = styledText(display) }
        }
        askBrain(chatId, next, attempt = 0)
        if (chatId == activeChatId) updateComposerState()
    }

    /** Send button + header mascot follow the RENDERED chat's
     *  flight state: the button morphs Send → Stop while busy
     *  (composer and attach stay live), and the mascot carries
     *  the state — WAITING while BrainPacing holds the reply,
     *  WORKING while a reply is actually being produced, IDLE
     *  at rest. */
    private fun updateComposerState() {
        val flight = flights[activeChatId]
        val busy = flight?.queue?.inFlight == true
        if (busy) {
            sendButton.setImageResource(R.drawable.ic_stop)
            sendButton.contentDescription = "Stop"
            input.hint = "Type another message — the send key queues it"
        } else {
            sendButton.setImageResource(R.drawable.ic_send)
            sendButton.contentDescription = "Send message"
            input.hint = defaultHint
        }
        val mascot = MascotStates.forChat(busy, flight?.waiting == true)
        headerMascot.setImageResource(
            resources.getIdentifier(
                MascotStates.drawableName(mascot), "drawable", packageName
            )
        )
    }

    /** Delayed work Stop must be able to cancel (pacing gate,
     *  cooldown retries) — tracked on the owning flight. */
    private fun schedule(flight: Flight, delayMs: Long, block: () -> Unit) {
        lateinit var r: Runnable
        r = Runnable {
            flight.pendingActions.remove(r)
            block()
        }
        flight.pendingActions.add(r)
        mainHandler.postDelayed(r, delayMs)
    }

    /** Main-thread work from a network callback, dropped if
     *  Stop (or a newer flight) has superseded its generation.
     *  Store writes inside survive a dead screen on purpose:
     *  the block runs on the main handler either way, and the
     *  UI helpers inside it no-op when their chat isn't
     *  rendered — the reply still lands in the store. */
    private fun postIfCurrent(flight: Flight, gen: Int, block: () -> Unit) {
        mainHandler.post { if (gen == flight.generation) block() }
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
     * cap and no error wall for a busy route. (v0.12: TWO
     * keyless text routes are live-verified — Pollinations +
     * AI Horde — out of ~45 candidates tested; ROUTES.md
     * carries every verdict. v0.12.1: the user's OWN saved
     * keys join the chain behind them — BrainFailure.chain
     * over OwnKeyRoutes. Pacing + fall-through + per-route
     * health below are the chain's resilience.)
     */
    private fun askBrain(chatId: String, userText: String, attempt: Int) {
        askBrain(chatId, userText, attempt, reframed = false, carry = "", continuations = 0,
                 historyText = userText)
    }

    /** The pacing gate: hold the call until the free route's
     *  minimum interval has passed, then hand over to the call
     *  itself. The wait is silent thinking time, never an error. */
    private fun askBrain(chatId: String, userText: String, attempt: Int, reframed: Boolean,
                         carry: String, continuations: Int, historyText: String) {
        val flight = flightFor(chatId)
        // v0.12 route health: a fresh call starts at the
        // first route that isn't resting after repeated
        // failures (BrainFailure). If the main route is
        // demoted, go straight to the community route; if
        // the whole chain is resting, wait for the earliest
        // recovery instead of firing into a wall. Recovery
        // is automatic — a rested route is simply eligible
        // again, and its next success clears its record.
        if (carry.isEmpty() && continuations == 0) {
            // v0.12.1: the chain is rebuilt per fresh ask —
            // keyless pair first, then the user's own-key
            // routes for keys actually saved (OwnKeyRoutes).
            refreshBrainChain()
            val now = System.currentTimeMillis()
            when (val first = BrainFailure.firstHealthy(brainChain, routeCoolingUntil, now)) {
                BrainFailure.Route.POLLINATIONS -> Unit // main route healthy: normal path
                BrainFailure.Route.HORDE -> {
                    replaceCurrentReply(
                        chatId,
                        "The main free route is resting after repeated " +
                            "failures — going straight to the slow " +
                            "community route."
                    )
                    tryHorde(chatId, userText, historyText)
                    return
                }
                null -> {
                    val earliest = routeCoolingUntil.values.minOrNull() ?: now
                    val waitMs = maxOf(earliest - now, 30_000L)
                    flight.waiting = true
                    if (chatId == activeChatId) updateComposerState()
                    replaceCurrentReply(
                        chatId,
                        "Every brain route is resting after repeated " +
                            "failures. Your message is queued — Lille " +
                            "retries on her own in about " +
                            BrainPacing.describeWait(waitMs) + "."
                    )
                    schedule(flight, waitMs) {
                        askBrain(chatId, userText, attempt + 1, reframed,
                                 "", 0, historyText)
                    }
                    return
                }
                else -> {
                    // The keyless pair is resting but one of
                    // the user's own key routes is healthy:
                    // start there directly.
                    val spec = OwnKeyRoutes.spec(first)
                    replaceCurrentReply(
                        chatId,
                        "The free routes are resting after repeated " +
                            "failures — using your " +
                            (spec?.displayName ?: "own") + " key route."
                    )
                    tryOwnKeyRoute(chatId, userText, historyText, first)
                    return
                }
            }
        }
        val remaining = BrainPacing.remainingIntervalMs(
            lastBrainCallAtMs, System.currentTimeMillis()
        )
        if (remaining > 0) {
            flight.waiting = true
            if (chatId == activeChatId) updateComposerState()
            schedule(flight, remaining) {
                askBrainNow(chatId, userText, attempt, reframed, carry, continuations, historyText)
            }
            return
        }
        askBrainNow(chatId, userText, attempt, reframed, carry, continuations, historyText)
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
    private fun askBrainNow(chatId: String, userText: String, attempt: Int, reframed: Boolean,
                            carry: String, continuations: Int, historyText: String) {
        val flight = flightFor(chatId)
        flight.waiting = false
        if (chatId == activeChatId) updateComposerState()
        val gen = flight.generation
        flight.carry = carry
        flight.partial = ""
        if (carry.isEmpty()) {
            flight.replyOpen = true
            flight.statusText = "…thinking"
            if (chatId == renderedChatId && uiAlive) {
                liveBubble = appendLine("Lille", "…thinking")
            }
        }

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (m in flight.history.takeLast(MAX_HISTORY)) messages.put(m)
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
        flight.call = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                postIfCurrent(flight, gen) {
                    if (carry.isNotEmpty() && continuations < MAX_CONTINUATIONS) {
                        // Dropped before/while streaming with a partial
                        // answer preserved: auto-continue, don't lose it.
                        replaceCurrentReply(chatId, carry + "\n\n(Connection dropped — continuing automatically…)")
                        askBrain(chatId, userText, attempt, reframed, carry, continuations + 1, historyText)
                    } else {
                        // v0.12 brain resilience: a network
                        // failure is classified, never
                        // dead-ended here — the chain falls
                        // through to the next route, or waits
                        // and retries on its own (BrainFailure).
                        // The old terminal "check your
                        // connection" copy is GONE: offline is
                        // only claimed when the device really is.
                        brainRouteFailed(
                            chatId, userText, attempt, reframed, historyText,
                            BrainFailure.Route.POLLINATIONS,
                            BrainFailure.classify(
                                null,
                                if (e is SocketTimeoutException) {
                                    BrainFailure.IoKind.TIMEOUT
                                } else {
                                    BrainFailure.IoKind.OTHER
                                },
                                deviceOnline()
                            ),
                            null
                        )
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
                                        flight.partial = chunk
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
                                    flight.partial = chunk
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
                postIfCurrent(flight, gen) {
                    if (code == 200) {
                        // First success ends any rate-limit streak.
                        consecutiveRateLimits =
                            BrainPacing.nextCounter(consecutiveRateLimits, code)
                    }
                    when {
                        code == 200 && dropped && full.isNotEmpty() &&
                            continuations < MAX_CONTINUATIONS -> {
                            replaceCurrentReply(chatId, full + "\n\n(Connection dropped mid-answer — continuing automatically…)")
                            askBrain(chatId, userText, attempt, reframed, full, continuations + 1, historyText)
                        }
                        code == 200 && full.isNotEmpty() && isRefusalReply(full) &&
                            !reframed && !looksHarmful(userText) -> {
                            // Refusal recovery: reframe ONCE, transparently.
                            replaceCurrentReply(chatId, REFUSAL_NOTE)
                            askBrain(chatId, reframeRequest(userText), attempt, reframed = true,
                                     carry = "", continuations = 0, historyText = historyText)
                        }
                        code == 200 && full.isNotEmpty() -> {
                            flight.brainStreak = 0
                            noteRouteSuccess(BrainFailure.Route.POLLINATIONS)
                            val shown = when {
                                reframed && isRefusalReply(full) -> full + REFUSAL_STILL
                                reframed -> "$REFUSAL_NOTE\n\n$full"
                                else -> full
                            }
                            flight.history.add(JSONObject().put("role", "user").put("content", historyText))
                            flight.history.add(JSONObject().put("role", "assistant").put("content", shown))
                            chatStore.appendMessage(chatId, "assistant", shown)
                            if (chatId == renderedChatId) renderedMtime = chatStore.lastModified()
                            replaceCurrentReply(chatId, shown)
                            BackupManager.requestBackup(this@MainActivity)
                            finishFlight(chatId)
                        }
                        (code == 402 || code == 429) -> {
                            // Busy route (v0.12): the chain
                            // falls through to the next route
                            // first; only when every route is
                            // busy does it wait and retry with
                            // escalating waits — BrainFailure
                            // owns the discipline now.
                            brainRouteFailed(
                                chatId, userText, attempt, reframed, historyText,
                                BrainFailure.Route.POLLINATIONS,
                                BrainFailure.Class.RATE_LIMITED, retryAfter
                            )
                        }
                        code == 200 -> {
                            // A 200 with no usable text is a
                            // sick route, not an answer.
                            brainRouteFailed(
                                chatId, userText, attempt, reframed, historyText,
                                BrainFailure.Route.POLLINATIONS,
                                BrainFailure.Class.NETWORK, null
                            )
                        }
                        code in 500..599 -> {
                            brainRouteFailed(
                                chatId, userText, attempt, reframed, historyText,
                                BrainFailure.Route.POLLINATIONS,
                                BrainFailure.Class.SERVER, retryAfter
                            )
                        }
                        else -> {
                            brainRouteFailed(
                                chatId, userText, attempt, reframed, historyText,
                                BrainFailure.Route.POLLINATIONS,
                                BrainFailure.classify(code, null, deviceOnline()),
                                retryAfter
                            )
                        }
                    }
                }
            }
        })
    }

    // ---------- v0.12 brain chain (BrainFailure discipline) ----------

    /** The device's REAL connectivity state — the only
     *  authority allowed to produce "you're offline" copy. */
    private fun deviceOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** One brain route failed. Fall through to the next
     *  route while one remains; when the chain is exhausted,
     *  either end terminally (permanent failure everywhere)
     *  or hold the flight in its waiting state and retry the
     *  whole chain after an escalating wait. The user's
     *  message is never erased and never terminally failed
     *  for a retryable cause. */
    private fun brainRouteFailed(
        chatId: String,
        userText: String,
        attempt: Int,
        reframed: Boolean,
        historyText: String,
        route: BrainFailure.Route,
        failure: BrainFailure.Class,
        retryAfterSec: Long?
    ) {
        val flight = flightFor(chatId)
        noteRouteFailure(route)
        // The cause copy names the route that actually
        // failed: own-key routes get their own wording
        // (BrainFailure.statusLine's ownKeyName), never the
        // anonymous tier's shared-IP explanation.
        val failedOwnKeyName = if (OwnKeyRoutes.isOwnKeyRoute(route)) {
            OwnKeyRoutes.spec(route)?.displayName
        } else {
            null
        }
        val next = BrainFailure.pickNext(
            route, brainChain, routeCoolingUntil, System.currentTimeMillis()
        )
        if (next != null) {
            replaceCurrentReply(
                chatId,
                BrainFailure.statusLine(failure, next.label, "", failedOwnKeyName)
            )
            when (next) {
                BrainFailure.Route.HORDE ->
                    tryHorde(chatId, userText, historyText)
                BrainFailure.Route.POLLINATIONS ->
                    askBrain(chatId, userText, attempt, reframed, "", 0, historyText)
                else ->
                    tryOwnKeyRoute(chatId, userText, historyText, next)
            }
            return
        }
        if (BrainFailure.attemptEndsTerminally(failure)) {
            val shown = "The brain routes refused that request's shape — " +
                "it can't be retried as-is. Your message stays above; " +
                "rephrase it and I'll try again."
            replaceCurrentReply(chatId, shown)
            chatStore.appendMessage(chatId, "assistant", shown)
            if (chatId == renderedChatId) renderedMtime = chatStore.lastModified()
            finishFlight(chatId)
            return
        }
        val waitMs = BrainFailure.waitMs(failure, retryAfterSec, flight.brainStreak)
        flight.brainStreak++
        flight.waiting = true
        if (chatId == activeChatId) updateComposerState()
        replaceCurrentReply(
            chatId,
            BrainFailure.statusLine(
                failure, null, BrainPacing.describeWait(waitMs), failedOwnKeyName
            )
        )
        schedule(flight, waitMs) {
            askBrain(chatId, userText, attempt + 1, reframed, "", 0, historyText)
        }
    }

    /** Route 2 — AI Horde, the slow community route
     *  (live-verified keyless 2026-11-26; ROUTES.md).
     *  Raw-completion API: the conversation is flattened
     *  into one prompt, the job is submitted with the
     *  anonymous key, and the status endpoint is polled.
     *  No refusal/continuation machinery — those belong to
     *  the primary route's chat format. */
    private fun tryHorde(chatId: String, userText: String, historyText: String) {
        val flight = flightFor(chatId)
        val gen = flight.generation
        val prompt = buildString {
            append(SYSTEM_PROMPT).append("\n\n")
            for (m in flight.history.takeLast(MAX_HISTORY)) {
                append(
                    if (m.optString("role") == "user") "User: " else "Lille: "
                )
                append(m.optString("content")).append("\n")
            }
            append("User: ").append(userText).append("\nLille:")
        }
        val params = JSONObject()
            .put("max_length", 512)
            .put("temperature", 0.7)
            .put("stop_sequence", JSONArray().put("\nUser:"))
        val bodyJson = JSONObject()
            .put("prompt", prompt)
            .put("params", params)
            .put("models", JSONArray())
            .put("slow_workers", true)
        val request = Request.Builder()
            .url("https://stablehorde.net/api/v2/generate/text/async")
            .header("apikey", "0000000000")
            .header("Client-Agent", "lille-android:0.12.1:free-agent")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        lastBrainCallAtMs = System.currentTimeMillis()
        val call = client.newCall(request)
        flight.call = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                postIfCurrent(flight, gen) {
                    brainRouteFailed(
                        chatId, userText, 0, false, historyText,
                        BrainFailure.Route.HORDE,
                        BrainFailure.classify(
                            null,
                            if (e is SocketTimeoutException) {
                                BrainFailure.IoKind.TIMEOUT
                            } else {
                                BrainFailure.IoKind.OTHER
                            },
                            deviceOnline()
                        ),
                        null
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                val text = try {
                    response.body?.string()
                } catch (e: Exception) {
                    null
                }
                response.close()
                val jobId = try {
                    text?.let { JSONObject(it).optString("id", "") } ?: ""
                } catch (e: Exception) {
                    ""
                }
                postIfCurrent(flight, gen) {
                    if (code in 200..299 && jobId.isNotEmpty()) {
                        pollHorde(chatId, userText, historyText, jobId, 0)
                    } else {
                        brainRouteFailed(
                            chatId, userText, 0, false, historyText,
                            BrainFailure.Route.HORDE,
                            BrainFailure.classify(code, null, deviceOnline()),
                            retryAfter
                        )
                    }
                }
            }
        })
    }

    /** Poll a Horde job every ~15 s, up to ~6 minutes of
     *  queue. A dropped poll is retried, not treated as a
     *  dead job; a faulted job or an expired wait hands back
     *  to the chain discipline. */
    private fun pollHorde(
        chatId: String,
        userText: String,
        historyText: String,
        jobId: String,
        polls: Int
    ) {
        val flight = flightFor(chatId)
        val gen = flight.generation
        if (polls >= 24) {
            brainRouteFailed(
                chatId, userText, 0, false, historyText,
                BrainFailure.Route.HORDE, BrainFailure.Class.NETWORK, null
            )
            return
        }
        schedule(flight, 15_000) {
            val request = Request.Builder()
                .url("https://stablehorde.net/api/v2/generate/text/status/$jobId")
                .header("apikey", "0000000000")
                .header("Client-Agent", "lille-android:0.12.1:free-agent")
                .build()
            val call = client.newCall(request)
            flight.call = call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    postIfCurrent(flight, gen) {
                        pollHorde(chatId, userText, historyText, jobId, polls + 1)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val text = try {
                        response.body?.string()
                    } catch (e: Exception) {
                        null
                    }
                    response.close()
                    var done = false
                    var faulted = false
                    var answer = ""
                    try {
                        val json = JSONObject(text ?: "")
                        done = json.optBoolean("done", false)
                        faulted = json.optBoolean("faulted", false)
                        val gens = json.optJSONArray("generations")
                        if (gens != null && gens.length() > 0) {
                            answer = gens.getJSONObject(0).optString("text", "")
                        }
                    } catch (e: Exception) {
                        // Unparseable status: keep polling.
                    }
                    postIfCurrent(flight, gen) {
                        when {
                            faulted -> brainRouteFailed(
                                chatId, userText, 0, false, historyText,
                                BrainFailure.Route.HORDE,
                                BrainFailure.Class.NETWORK, null
                            )
                            done && answer.isNotBlank() ->
                                deliverHordeAnswer(chatId, historyText, answer)
                            done -> brainRouteFailed(
                                chatId, userText, 0, false, historyText,
                                BrainFailure.Route.HORDE,
                                BrainFailure.Class.NETWORK, null
                            )
                            else -> pollHorde(
                                chatId, userText, historyText, jobId, polls + 1
                            )
                        }
                    }
                }
            })
        }
    }

    /** A Horde answer lands through the same store/history
     *  path as a primary-route answer, with the route named
     *  — the user always knows which brain answered. */
    private fun deliverHordeAnswer(
        chatId: String,
        historyText: String,
        rawAnswer: String
    ) {
        val flight = flightFor(chatId)
        flight.brainStreak = 0
        noteRouteSuccess(BrainFailure.Route.HORDE)
        val shown = rawAnswer.trim() +
            "\n\n(Answered by the slow community route — the main free " +
            "route is busy. Community models can be rougher than usual.)"
        flight.history.add(
            JSONObject().put("role", "user").put("content", historyText)
        )
        flight.history.add(
            JSONObject().put("role", "assistant").put("content", shown)
        )
        chatStore.appendMessage(chatId, "assistant", shown)
        if (chatId == renderedChatId) renderedMtime = chatStore.lastModified()
        replaceCurrentReply(chatId, shown)
        BackupManager.requestBackup(this)
        finishFlight(chatId)
    }

    /** v0.12.1 — an own-key route call (Groq / Gemini /
     *  Cerebras / OpenRouter / AgentRouter / custom; the
     *  spec lives in OwnKeyRoutes). Plain non-streaming
     *  OpenAI-compatible POST: the key is resolved from the
     *  vault at this moment and crosses only into the
     *  Authorization header — never into the message body,
     *  the chat, a log, or the task context. No refusal /
     *  continuation machinery (those belong to the primary
     *  route's stream); failures hand back to the same
     *  BrainFailure chain discipline as every route. */
    private fun tryOwnKeyRoute(
        chatId: String,
        userText: String,
        historyText: String,
        route: BrainFailure.Route
    ) {
        val flight = flightFor(chatId)
        val gen = flight.generation
        val spec = OwnKeyRoutes.spec(route)
        val key = spec?.let { vault.getSecret(it.vaultKeyName) }
        if (spec == null || key.isNullOrEmpty()) {
            // The key was removed between the chain refresh
            // and this call: the route is absent, not broken.
            // Fall through as a network-class failure; the
            // next fresh ask rebuilds the chain without it.
            brainRouteFailed(
                chatId, userText, 0, false, historyText,
                route, BrainFailure.Class.NETWORK, null
            )
            return
        }
        val customBase = if (route == BrainFailure.Route.CUSTOM) {
            vault.getSecret(OwnKeyRoutes.CUSTOM_URL_VAULT_KEY)
        } else {
            null
        }
        val customModel = if (route == BrainFailure.Route.CUSTOM) {
            vault.getSecret(OwnKeyRoutes.CUSTOM_MODEL_VAULT_KEY)
        } else {
            null
        }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        for (m in flight.history.takeLast(MAX_HISTORY)) messages.put(m)
        messages.put(JSONObject().put("role", "user").put("content", userText))
        val bodyJson = JSONObject()
            .put("model", OwnKeyRoutes.chatModel(spec, customModel))
            .put("messages", messages)
        val request = Request.Builder()
            .url(OwnKeyRoutes.chatUrl(spec, customBase))
            .header("Authorization", "Bearer $key")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        lastBrainCallAtMs = System.currentTimeMillis()
        val call = client.newCall(request)
        flight.call = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                postIfCurrent(flight, gen) {
                    brainRouteFailed(
                        chatId, userText, 0, false, historyText,
                        route,
                        BrainFailure.classify(
                            null,
                            if (e is SocketTimeoutException) {
                                BrainFailure.IoKind.TIMEOUT
                            } else {
                                BrainFailure.IoKind.OTHER
                            },
                            deviceOnline()
                        ),
                        null
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                val text = try {
                    response.body?.string()
                } catch (e: Exception) {
                    null
                }
                response.close()
                val answer = if (code == 200 && text != null) {
                    try {
                        JSONObject(text).getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message")
                            .optString("content", "").trim()
                    } catch (e: Exception) {
                        ""
                    }
                } else {
                    ""
                }
                postIfCurrent(flight, gen) {
                    when {
                        code == 200 && answer.isNotEmpty() ->
                            deliverOwnKeyAnswer(chatId, historyText, answer, route)
                        code == 200 ->
                            // A 200 with no usable text is a
                            // sick route, not an answer.
                            brainRouteFailed(
                                chatId, userText, 0, false, historyText,
                                route, BrainFailure.Class.NETWORK, null
                            )
                        else ->
                            brainRouteFailed(
                                chatId, userText, 0, false, historyText,
                                route,
                                BrainFailure.classify(code, null, deviceOnline()),
                                retryAfter
                            )
                    }
                }
            }
        })
    }

    /** An own-key answer lands through the same
     *  store/history path as every answer, with the route
     *  named — the user always knows which brain answered,
     *  and that this one ran on their own key. */
    private fun deliverOwnKeyAnswer(
        chatId: String,
        historyText: String,
        rawAnswer: String,
        route: BrainFailure.Route
    ) {
        val flight = flightFor(chatId)
        flight.brainStreak = 0
        noteRouteSuccess(route)
        val name = OwnKeyRoutes.spec(route)?.displayName ?: "your own key"
        val shown = rawAnswer.trim() +
            "\n\n(Answered by your $name key route.)"
        flight.history.add(
            JSONObject().put("role", "user").put("content", historyText)
        )
        flight.history.add(
            JSONObject().put("role", "assistant").put("content", shown)
        )
        chatStore.appendMessage(chatId, "assistant", shown)
        if (chatId == renderedChatId) renderedMtime = chatStore.lastModified()
        replaceCurrentReply(chatId, shown)
        BackupManager.requestBackup(this)
        finishFlight(chatId)
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

    /** Replace [chatId]'s live reply bubble text — the
     *  "…thinking" bubble, a busy/cooldown note, or the final
     *  answer. The text is always recorded on the flight (so
     *  a later render re-attaches it exactly); the view is
     *  touched only when that chat is on screen. */
    private fun replaceCurrentReply(chatId: String, text: String) {
        val flight = flightFor(chatId)
        flight.statusText = text
        if (!uiAlive || chatId != renderedChatId) return
        val bubble = liveBubble
        if (bubble != null && bubble.parent === transcript) {
            bubble.text = styledText(text)
            scrollToBottom()
        } else {
            liveBubble = appendLine("Lille", text)
        }
    }

    /** Chat markdown (v0.12.3): the pure [ChatMarkdown]
     *  renderer strips the model's raw markers (`**`, `##`,
     *  backticks) and this turns its spans into Android styles.
     *  The rendered text stays the full readable text, so the
     *  v0.12.2 long-press selection copies clean text. A
     *  message without markdown comes back byte-identical. */
    private fun styledText(raw: String): CharSequence {
        val rendered = ChatMarkdown.render(raw)
        if (rendered.spans.isEmpty()) return rendered.text
        val sb = SpannableStringBuilder(rendered.text)
        for (span in rendered.spans) {
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            when (span.style) {
                ChatMarkdown.Style.BOLD ->
                    sb.setSpan(StyleSpan(Typeface.BOLD), span.start, span.end, flags)
                ChatMarkdown.Style.ITALIC ->
                    sb.setSpan(StyleSpan(Typeface.ITALIC), span.start, span.end, flags)
                ChatMarkdown.Style.CODE, ChatMarkdown.Style.CODE_BLOCK -> {
                    sb.setSpan(TypefaceSpan("monospace"), span.start, span.end, flags)
                    sb.setSpan(
                        BackgroundColorSpan(Color.argb(45, 128, 128, 128)),
                        span.start, span.end, flags
                    )
                }
                ChatMarkdown.Style.HEADING -> {
                    sb.setSpan(StyleSpan(Typeface.BOLD), span.start, span.end, flags)
                    val scale = when (span.level) {
                        1 -> 1.30f
                        2 -> 1.18f
                        3 -> 1.10f
                        else -> 1.05f
                    }
                    sb.setSpan(RelativeSizeSpan(scale), span.start, span.end, flags)
                }
            }
        }
        return sb
    }

    /** One message, one bubble (v0.10 UI polish): Lille's are
     *  dark warm surfaces hugging the left; the user's are
     *  glossy Bitcoin-orange hugging the right, dark text on
     *  the orange for contrast. */
    private fun appendLine(who: String, text: String): TextView {
        val bubble = TextView(this)
        bubble.text = styledText(text)
        // v0.12.2: chat text must be copyable — a programmatic
        // TextView is NOT selectable by default (the XML ones
        // carry android:textIsSelectable), so without this line
        // neither the user's messages nor Lille's replies could
        // be long-pressed, selected or copied. Selection lives
        // inside the bubble; it does not change layout, the
        // queue badges appended to the text, or scrolling.
        bubble.setTextIsSelectable(true)
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
