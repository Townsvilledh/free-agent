package org.freeagent.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
import java.io.File
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
 * in this thread (ChatTaskLink / ChatTaskReporter).
 *
 * v1.0.1 — CHAT IS THE ENGINE + SEAMLESS FALLBACK: the
 * conversation is the workspace. There is no Tasks screen
 * (removed entirely): a chat-born job's state is the
 * thread's own state — a living status line in the thread,
 * her messages for the parked step and the graded result,
 * the live browser panel when the work is in a browser
 * step. Route failures, refusals and rate limits are
 * NEVER narrated: fallback is immediate and invisible
 * (BrainFailure discipline + NarrationGuard on stored
 * notes), attribution footers are gone (traces only), a
 * bare status probe is answered from the record with no
 * brain call (StatusProbe), and every reply passes the
 * deterministic ChatOutputGuard before display. Packaged
 * jobs (her X account — owner-gated and state-driven,
 * LilleXState; the user's own X connect) live in this
 * chat's menu (MainChatActions).
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
            "answer — and no task has been started for it, answer in " +
            "chat as far as chat reaches. Never ask " +
            "\"Want me to take that on as a task?\" — jobs are " +
            "started by the app, never by asking."
        private const val MAX_HISTORY = 20
        // v0.2.1 smart recovery: stream continuations + one refusal reframe.
        private const val MAX_CONTINUATIONS = 3
        // (The refusal-recovery ANNOUNCEMENTS that used to
        // live here are gone — seamless law, v1.0.1: the
        // reframe still happens, invisibly.)
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
        /** Chat-output guard (v1.0.1): this message's reply
         *  already used its one silent regeneration. Reset
         *  per fresh ask. */
        var guardRetried = false
        /** Capability grounding (v1.0.5): the pending
         *  regeneration follows a capability-denial
         *  rejection, so its system prompt carries the
         *  capability sheet's re-assert line. Reset per
         *  fresh ask and on delivery, like [guardRetried]. */
        var guardReassert = false
        /** Live-bubble menu (v1.0.6): a reaction picked on
         *  the LIVE reply bubble — the message has no
         *  record yet, so the toggled result waits here
         *  and lands on the record the moment the reply
         *  settles. Cleared in finishFlight as a leak
         *  guard: a reaction never jumps to a later reply. */
        var pendingLiveReaction: String? = null
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
    private lateinit var quoteBar: View
    private lateinit var quoteBarText: TextView
    private lateinit var walletCardText: TextView
    private lateinit var attachmentThumb: ImageView
    private lateinit var attachmentName: TextView

    /** The in-chat live browser panel (v1.0.1): her real
     *  WebView session, shown in the conversation while
     *  this chat's work is in a browser step (BrowserPanel
     *  state machine). Refreshed on every render and on a
     *  slow tick while the screen is alive — work state
     *  changes in the background (worker steps, handbacks)
     *  and the panel follows the record. */
    private lateinit var browserPanel: LinearLayout
    private lateinit var browserPanelLabel: TextView
    private lateinit var browserPanelContainer: FrameLayout
    private lateinit var browserPanelWaiting: TextView
    private lateinit var browserPanelExpand: ImageButton
    private lateinit var browserPanelAction: ImageButton
    private lateinit var browserPanelClose: ImageButton
    private lateinit var browserChip: TextView
    private var panelState: BrowserPanel.State = BrowserPanel.State.HIDDEN
    private var panelTaskId: String? = null
    private var panelExpanded = false

    /** The panel dismissal (v1.0.3): the task id whose
     *  panel the user closed. A VIEW fact only — it lives
     *  here, never on the task record; closing the panel
     *  does not touch the work. A different task becoming
     *  the chat's latest clears it; a park overrides it
     *  (BrowserPanel.panelVisible). */
    private var panelDismissedTaskId: String? = null
    private val panelTick = object : Runnable {
        override fun run() {
            if (!uiAlive) return
            refreshBrowserPanel()
            mainHandler.postDelayed(this, 3_000)
        }
    }

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

    /** v1.0.2: task ids whose living status message is
     *  currently expanded to the full named step list in
     *  the rendered thread (tap the status line to toggle).
     *  View state only — the record is the truth, this set
     *  is just which lists are open on screen. */
    private val expandedTaskIds = mutableSetOf<String>()

    /** Bubbles of the RENDERED chat's queued user messages,
     *  FIFO-parallel to that chat flight's queue, so a badge
     *  can be stripped when a message starts. Rebuilt by
     *  every render. */
    private val queuedBubbles = ArrayDeque<Pair<TextView, String>>()

    /** Currently picked attachment (uri + display name + mime), or null. */
    private var pendingAttachment: Triple<Uri, String, String?>? = null
    // v1.0.1 reply-to-message: the pending quote — (quoted
    // role, snippet) set from the long-press menu's Reply.
    private var pendingQuote: Pair<String, String>? = null

    private var defaultHint: CharSequence = ""

    @Volatile
    private var uiAlive = false

    /** Fallback law: when the keyless route is cooling down after a
     *  rate limit, this holds the wall-clock time it recovers. */
    private var cooldownUntilMs = 0L

    /** BrainPacing stamps, PER ROUTE (v1.0.1 seamless
     *  law): pacing governs repeat calls to the SAME route
     *  only — a fresh call that starts on a different route
     *  fires at once, and a fall-through NEVER re-enters
     *  the gate. Global per route on purpose: the anonymous
     *  tier limits per IP, not per chat. Chat is a live
     *  session, so the stamps live in memory — the durable
     *  task runner persists its own copies. */
    private val lastBrainCallAtByRoute =
        mutableMapOf<BrainFailure.Route, Long>()
    private var consecutiveRateLimits = 0

    /** Stamp a brain call on [route]: pacing record +
     *  the BrainLane chat mark (chat never waits; tasks
     *  yield around it). */
    private fun stampBrainCall(route: BrainFailure.Route) {
        lastBrainCallAtByRoute[route] = System.currentTimeMillis()
        BrainLane.shared.chatStarted()
    }

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
            OwnKeyRoutes.presentRoutes(
                { name -> vault.hasSecret(name) },
                lanEnabled = LanRoute.isEnabled(this)
            )
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

        // Referrals (REFERRALS.md): an invite link that
        // reached the app names its inviter until sign-in
        // (captured only while no identity stands — an
        // invite is for a new user), and a standing identity
        // settles any pending inviter against its first
        // sign-in. Both are no-ops in the ordinary case.
        captureInviteLink(intent)
        fluidIdentity?.let {
            ReferralStore(this).settleOnLogin(it.username, null)
        }
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
        quoteBar = findViewById(R.id.quoteBar)
        quoteBarText = findViewById(R.id.quoteBarText)

        chatStore = ChatStore(this)
        BackupWorker.schedule(this)

        // v1.0.1 UI organisation: the header is quiet —
        // mascot + name, Chats, New, and one menu holding
        // Slim icon chrome (v1.0.1): chats list, new chat
        // and settings are small symbol buttons in the
        // header; the ⋯ menu holds only the packaged jobs
        // (MainChatActions). The Tasks destination is GONE:
        // work lives in chats now, nowhere else.
        findViewById<View>(R.id.chatsButton).setOnClickListener {
            startActivity(android.content.Intent(this, ChatListActivity::class.java))
        }
        findViewById<View>(R.id.newChatButton).setOnClickListener {
            renderChat(chatStore.createChat())
        }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        val menuButton = findViewById<View>(R.id.menuButton)
        menuButton.setOnClickListener { showMainMenu(menuButton) }
        walletCardText = findViewById(R.id.walletCardText)
        findViewById<View>(R.id.walletCard).setOnClickListener {
            startActivity(android.content.Intent(this, WalletActivity::class.java))
        }
        refreshWalletCard()

        browserPanel = findViewById(R.id.browserPanel)
        browserPanelLabel = findViewById(R.id.browserPanelLabel)
        browserPanelContainer = findViewById(R.id.browserPanelContainer)
        browserPanelWaiting = findViewById(R.id.browserPanelWaiting)
        browserPanelExpand = findViewById(R.id.browserPanelExpand)
        browserPanelAction = findViewById(R.id.browserPanelAction)
        browserPanelClose = findViewById(R.id.browserPanelClose)
        browserChip = findViewById(R.id.browserChip)
        browserPanelExpand.setOnClickListener {
            setPanelExpanded(!panelExpanded)
        }
        browserPanelAction.setOnClickListener { onPanelAction() }
        // Close (v1.0.3): hide the panel; the work keeps
        // running untouched — dismissal is recorded HERE,
        // never on the task. The chip reopens it; a park
        // reopens it on its own.
        browserPanelClose.setOnClickListener {
            panelDismissedTaskId = panelTaskId
            refreshBrowserPanel()
        }
        browserChip.setOnClickListener {
            panelDismissedTaskId = null
            refreshBrowserPanel()
        }
        // Chat parity (v1.0.6, ComposerAction): the button's
        // face is a function of the composer's content AND
        // the flight — while a reply is in flight it is the
        // stop button ONLY when the composer is empty; with
        // text typed it is SEND, and the message queues in
        // order (see submitMessage). The shipped 1.0.5 face
        // was busy-only, so a typed message sat over a stop
        // square, unsendable (2026-11-27 screenshot: "I
        // can't send when she's thinking").
        sendButton.setOnClickListener {
            val action = ComposerAction.actionFor(
                composerHasContent(), renderedBusy()
            )
            if (action == ComposerAction.Action.STOP) {
                stopReply()
            } else {
                submitMessage()
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                updateComposerState()
            }

            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) = Unit
        })
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
        findViewById<Button>(R.id.clearQuoteButton).setOnClickListener { clearPendingQuote() }

        uiAlive = true
        activeChatId = chatStore.ensureActiveChat()
        renderChat(activeChatId)
        mainHandler.postDelayed(panelTick, 3_000)
    }

    /** Referrals: remember an invite link's inviter as
     *  pending — only while no verified identity stands on
     *  this device (an invite is for a new user; a signed-in
     *  user opening a link changes nothing). */
    private fun captureInviteLink(incoming: android.content.Intent?) {
        val inviter = Referrals.referrerFromUri(incoming?.data?.toString())
            ?: return
        if (FluidIdentityStore(this).load() == null) {
            ReferralStore(this).setPendingFromLink(inviter)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureInviteLink(intent)
        // Returning from the chat list (CLEAR_TOP reuses this
        // instance): the active chat may have changed.
        if (::chatStore.isInitialized) {
            val current = ChatData.activeId(chatStore.root())
            if (current.isNotEmpty()) renderChat(current)
        }
    }

    /** FINISH-GATE AUDIT (v1.0.8): a packaged record
     *  found in Finished with its gate criteria false is
     *  reopened to WAITING — the false finish heals
     *  itself on open instead of sitting there as fact.
     *  The audit runs before the watchdog (a reopened
     *  record is waiting work the watchdog should see)
     *  and at render (a run can finish while he sits in
     *  the app — the 12:44 false finish happened in
     *  session). The reopen stamps the discrepancy and
     *  the audit event on the record, retires the false
     *  summary into that event, and force-enqueues the
     *  work to continue from its steps. */
    private fun auditFinishGate() {
        val store = TaskStore(this)
        var changed = false
        for (task in store.all()) {
            if (!FinishGate.shouldReopen(task)) continue
            val id = task.optString("id")
            store.update(id, TaskStates.WAITING_ROUTE) {
                FinishGate.stampReopen(
                    it, System.currentTimeMillis()
                )
            }
            TaskWorker.enqueueForced(this, id)
            changed = true
        }
        if (changed) BackupManager.requestBackup(this)
    }

    override fun onResume() {
        super.onResume()
        uiAlive = true
        auditFinishGate()
        // Watchdog app-open kick (v1.0.2): opening the app
        // never leaves an expired wait sitting — expired
        // waits get an immediate pass, stale waits (>10 min
        // unmoved) a forced retry that ignores their stored
        // wait state. Cheap record scan; enqueues are
        // REPLACE-idempotent.
        TaskWorker.watchdogPass(this)
        if (::walletCardText.isInitialized) refreshWalletCard()
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
        mainHandler.removeCallbacks(panelTick)
        if (::browserPanelContainer.isInitialized &&
            browserPanelContainer.childCount > 0
        ) {
            BrowserSession.detach()
        }
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
        // The finish-gate audit runs at render too: a run
        // can finish (falsely) while he sits in the app,
        // and the status refresh below must read the
        // corrected record, not the false finish.
        auditFinishGate()
        activeChatId = chatId
        renderedChatId = chatId
        transcript.removeAllViews()
        liveBubble = null
        queuedBubbles.clear()

        // v1.0.2 (TRANSPARENCY): refresh the chat's living
        // status messages from the task records BEFORE
        // reading the thread — each task's ONE status
        // message is rewritten in place when the record has
        // moved since it last reflected it, so what renders
        // below is always the current truth and never a
        // stack of twins. A terminal task with a status
        // message gets its final rewrite the same way; a
        // terminal task without one is left to its graded
        // report (no line is created for it here).
        val allTasks = TaskStore(this).all()
        val tasksById = allTasks.associateBy { it.optString("id") }
        val nowMs = System.currentTimeMillis()
        for (task in allTasks) {
            if (task.optString(ChatTaskLink.ORIGIN_FIELD) != chatId) continue
            val taskId = task.optString("id")
            val reflected = chatStore.taskStatusReflectedAt(chatId, taskId)
            if (reflected == null &&
                TaskStates.isTerminal(task.optString("state"))
            ) continue
            if (reflected != task.optLong("updatedAt", 0L)) {
                chatStore.upsertTaskStatus(
                    chatId, taskId,
                    TaskTransparency.statusText(task, nowMs),
                    task.optLong("updatedAt", 0L)
                )
            }
        }

        val root = chatStore.root()
        val messages = ChatData.messages(root, chatId)
        val flight = flights[chatId]
        val waiting = flight?.queue?.waitingCount ?: 0
        val userBubbles = mutableListOf<Pair<TextView, String>>()
        for ((index, m) in messages.withIndex()) {
            val role = m.optString("role")
            val display = if (m.has("display")) m.optString("display")
                else m.optString("content")
            // v1.0.1: the stored reaction shows under the
            // bubble's text; the long-press menu (reactions,
            // Reply / Copy / Share) hangs off every stored
            // bubble, his and hers alike.
            val reaction = ReplyQuote.reaction(m)
            var shown = if (reaction != null) display + "\n" + reaction
                else display
            // v1.0.2: the task's ONE living status message —
            // tapping it expands the full named step list
            // in-thread (rendered fresh from the record, so
            // the expansion is as current as the line), and
            // tapping again collapses it. The hint is render
            // chrome only; the stored text stays clean.
            val statusTaskId = m.optString(ChatData.TASK_STATUS_FIELD)
            val statusTask = if (statusTaskId.isNotEmpty()) {
                tasksById[statusTaskId]
            } else null
            if (statusTask != null) {
                shown += if (expandedTaskIds.contains(statusTaskId)) {
                    "\n\n" + TaskTransparency.expandedText(
                        statusTask, System.currentTimeMillis()
                    ) + "\n(Tap to close)"
                } else {
                    "\n(Tap for the step list)"
                }
            }
            val bubble = appendLine(if (role == "user") "You" else "Lille", shown)
            bubble.setOnLongClickListener {
                showMessageMenu(chatId, index, role, display, bubble)
                true
            }
            if (statusTask != null) {
                // Selectable text swallows taps; the status
                // line's tap-to-expand wins (its text is
                // generated, and the long-press menu still
                // offers Copy).
                bubble.setTextIsSelectable(false)
                bubble.setOnClickListener {
                    if (expandedTaskIds.contains(statusTaskId)) {
                        expandedTaskIds.remove(statusTaskId)
                    } else {
                        expandedTaskIds.add(statusTaskId)
                    }
                    renderChat(chatId)
                }
                // RUN NOW (v1.0.2 watchdog): in the EXPANDED
                // status view, a waiting task carries the
                // user's own wake button — a forced pass
                // that ignores the stored wait state. Slim
                // shared-menu row styling, like every other
                // action surface.
                if (expandedTaskIds.contains(statusTaskId) &&
                    TaskWatchdog.runNowOffered(statusTask.optString("state"))
                ) {
                    transcript.addView(
                        LilleMenu.actionRow(this, "Run now") {
                            TaskWorker.enqueueForced(this, statusTaskId)
                            Toast.makeText(
                                this@MainActivity, "Trying again now",
                                Toast.LENGTH_SHORT
                            ).show()
                            renderChat(chatId)
                        }
                    )
                }
            }
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
        // CHAT IS THE ENGINE + TRANSPARENCY (v1.0.2): the
        // thread's work state is visible IN the thread as
        // each task's ONE living status message — refreshed
        // from the record at the top of this render and
        // rendered above as part of the stored thread. (The
        // v1.0.1 transient line is gone: it duplicated the
        // stored probe answers and read as stacking.)
        if (flight != null && flight.queue.inFlight) {
            val liveText = when {
                flight.partial.isNotEmpty() || flight.carry.isNotEmpty() ->
                    stitch(flight.carry, flight.partial)
                flight.statusText != null -> flight.statusText!!
                flight.replyOpen -> "…thinking"
                else -> null
            }
            if (liveText != null) {
                val bubble = appendLine("Lille", liveText)
                liveBubble = bubble
                // v1.0.6: the live bubble carries the same
                // long-press menu as every stored bubble.
                attachLiveMenu(chatId, bubble)
            }
        }
        if (messages.isEmpty() && (flight == null || !flight.queue.inFlight)) {
            appendGreeting()
        }
        renderedMtime = chatStore.lastModified()
        updateComposerState()
        refreshBrowserPanel()
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
        // An attachment is composer content (v1.0.6): the
        // button face must know the composer can send.
        updateComposerState()
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
        if (::sendButton.isInitialized) updateComposerState()
    }

    // ---------- message menu: reactions + reply (v1.0.1) ----

    private fun clearPendingQuote() {
        pendingQuote = null
        quoteBar.visibility = View.GONE
    }

    private fun setPendingQuote(role: String, quotedText: String) {
        val snip = ReplyQuote.snippet(quotedText)
        pendingQuote = role to snip
        quoteBarText.text = ReplyQuote.quoteBlock(role, snip)
        quoteBar.visibility = View.VISIBLE
        input.requestFocus()
    }

    /**
     * The ONE long-press message menu card (v1.0.2's shared
     * LilleMenu component; v1.0.6: shared by stored AND
     * live bubbles, so the two can never drift): a
     * reactions row (with "+" for more) over Reply / Copy /
     * Share — the items and reactions come from
     * MessageActions alone, for both roles and both bubble
     * kinds. Only the reaction SINK differs: a stored
     * bubble toggles on its record at once; the live
     * bubble queues onto the record it settles into.
     * Chat text is NOT selectable at all (v1.0.7): no menu
     * item and no code path here enables selection on a
     * bubble — the long-press reaches this menu, never
     * the system selection bar.
     */
    private fun showMessageMenuCard(
        role: String, display: String, bubble: TextView,
        onReaction: (String) -> Unit,
        onMoreReactions: () -> Unit
    ) {
        val card = LilleMenu.card(this)
        var dialog: AlertDialog? = null
        card.addView(
            LilleMenu.reactionRow(
                this, MessageActions.REACTIONS,
                onPick = { emoji ->
                    dialog?.dismiss()
                    onReaction(emoji)
                },
                onMore = {
                    dialog?.dismiss()
                    onMoreReactions()
                }
            )
        )
        card.addView(LilleMenu.divider(this))
        MessageActions.ITEMS.forEachIndexed { i, item ->
            if (i > 0) card.addView(LilleMenu.divider(this))
            card.addView(LilleMenu.actionRow(this, item.title) {
                dialog?.dismiss()
                when (item) {
                    MessageActions.Item.REPLY ->
                        setPendingQuote(role, display)
                    MessageActions.Item.COPY -> {
                        val cm = getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText("Lille message", display)
                        )
                        Toast.makeText(
                            this@MainActivity, "Copied",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    MessageActions.Item.SHARE -> {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, display)
                        }
                        startActivity(
                            Intent.createChooser(send, "Share message")
                        )
                    }
                }
            })
        }
        dialog = LilleMenu.show(this, card)
    }

    /** The stored bubble's menu: reactions toggle on the
     *  record (MessageActions' rule) and persist via the
     *  store — restart- and backup-safe. */
    private fun showMessageMenu(
        chatId: String, index: Int, role: String,
        display: String, bubble: TextView
    ) {
        showMessageMenuCard(
            role, display, bubble,
            onReaction = { emoji ->
                applyReaction(chatId, index, emoji, bubble, display)
            },
            onMoreReactions = {
                showMoreReactions(chatId, index, bubble, display)
            }
        )
    }

    private fun showMoreReactionsMenu(onPick: (String) -> Unit) {
        val card = LilleMenu.card(this)
        var dialog: AlertDialog? = null
        MessageActions.MORE_REACTIONS.chunked(4).forEach { chunk ->
            card.addView(
                LilleMenu.reactionRow(
                    this, chunk,
                    onPick = { emoji ->
                        dialog?.dismiss()
                        onPick(emoji)
                    }
                )
            )
        }
        dialog = LilleMenu.show(this, card)
    }

    private fun showMoreReactions(
        chatId: String, index: Int, bubble: TextView, display: String
    ) {
        showMoreReactionsMenu { emoji ->
            applyReaction(chatId, index, emoji, bubble, display)
        }
    }

    // ---------- the live bubble's menu (v1.0.6) ----------

    /** The live reply's current text, composed exactly as
     *  renderChat composes it — the menu acts on what the
     *  user sees. */
    private fun liveReplyText(chatId: String): String {
        val flight = flights[chatId] ?: return ""
        return when {
            flight.partial.isNotEmpty() || flight.carry.isNotEmpty() ->
                stitch(flight.carry, flight.partial)
            flight.statusText != null -> flight.statusText!!
            flight.replyOpen -> "…thinking"
            else -> ""
        }
    }

    /** Hang the long-press menu off a live reply bubble.
     *  Called at both creation sites (renderChat's tail
     *  and replaceCurrentReply's re-creation), so the
     *  streaming bubble is never a dead surface. */
    private fun attachLiveMenu(chatId: String, bubble: TextView) {
        bubble.setOnLongClickListener {
            showLiveMessageMenu(chatId, bubble)
            true
        }
    }

    /** The live bubble's menu: Copy / Share / Reply act
     *  on the text as it stands; a reaction queues onto
     *  the record the reply settles into
     *  ([queueLiveReaction] + [applyPendingLiveReaction]). */
    private fun showLiveMessageMenu(chatId: String, bubble: TextView) {
        val display = liveReplyText(chatId)
        if (display.isEmpty()) return
        showMessageMenuCard(
            "assistant", display, bubble,
            onReaction = { emoji -> queueLiveReaction(chatId, emoji) },
            onMoreReactions = {
                showMoreReactionsMenu { emoji ->
                    queueLiveReaction(chatId, emoji)
                }
            }
        )
    }

    /** Toggle a reaction against the live flight's pending
     *  one (MessageActions' rule) and show it on the
     *  bubble for now — the durable copy lands on the
     *  record at settle. */
    private fun queueLiveReaction(chatId: String, emoji: String) {
        val flight = flights[chatId] ?: return
        val next = MessageActions.toggleReaction(
            flight.pendingLiveReaction, emoji
        )
        flight.pendingLiveReaction = next
        val bubble = liveBubble ?: return
        if (chatId != renderedChatId) return
        val display = liveReplyText(chatId)
        bubble.text = styledText(
            if (next != null) display + "\n" + next else display
        )
    }

    /** Land a queued live reaction on the settled record:
     *  called right after each settle path appends the
     *  reply. Applies only when the chat's LAST message is
     *  that reply (an assistant message at the tail) —
     *  anything else and the reaction is dropped rather
     *  than guessed onto the wrong message. */
    private fun applyPendingLiveReaction(chatId: String) {
        val flight = flights[chatId] ?: return
        val emoji = flight.pendingLiveReaction ?: return
        flight.pendingLiveReaction = null
        val messages = ChatData.messages(chatStore.root(), chatId)
        val lastIndex = messages.indexOfLast {
            it.optString("role") == "assistant"
        }
        if (lastIndex < 0 || lastIndex != messages.lastIndex) return
        if (chatStore.setReaction(chatId, lastIndex, emoji)) {
            renderedMtime = chatStore.lastModified()
        }
    }

    private fun applyReaction(
        chatId: String, index: Int, emoji: String,
        bubble: TextView, display: String
    ) {
        val current = ChatData.messages(chatStore.root(), chatId)
            .getOrNull(index)?.let { ReplyQuote.reaction(it) }
        val next = MessageActions.toggleReaction(current, emoji)
        if (chatStore.setReaction(chatId, index, next)) {
            renderedMtime = chatStore.lastModified()
            bubble.text = styledText(
                if (next != null) display + "\n" + next else display
            )
        }
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
        // v1.0.1 reply-to-message: the pending quote rides
        // this send — visibly on the bubble, in the store,
        // and as marked context in what the model (or a task
        // born from this reply) receives.
        val quote = pendingQuote

        var outgoing = raw
        var display = pendingAttachment?.let { (_, name, mime) ->
            val note = "[Attached file: $name (${mime ?: "unknown type"}). " +
                "Note: in this version the attachment is recorded by name only — " +
                "full file/image understanding is a next step, so do not claim to have seen its contents.]"
            outgoing = if (outgoing.isEmpty()) note else "$outgoing\n$note"
            (if (raw.isEmpty()) "" else "$raw ") + "[attachment: $name]"
        } ?: raw
        if (quote != null) {
            display = ReplyQuote.displayText(quote.first, quote.second, display)
            outgoing = ReplyQuote.markedContent(quote.first, quote.second, outgoing)
        }
        val bubble = appendLine("You", display)
        chatStore.appendMessage(
            chatId, "user", outgoing, display,
            quoteRole = quote?.first, quoteText = quote?.second
        )
        renderedMtime = chatStore.lastModified()
        BackupManager.requestBackup(this)

        input.text.clear()
        clearAttachment()
        clearPendingQuote()

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

        // KILL ALL (v1.0.5): "kill all task" is a COMMAND,
        // handled deterministically — never a brain
        // utterance. Before this existed, the message fell
        // through to the brain, which announced "all tasks
        // stopped" with no machinery behind the claim
        // (2026-11-27 screenshot). The kill stops every
        // non-terminal record — general and packaged alike
        // (one store) — stamps each as killed by his hand
        // with his command on it, cancels its scheduled
        // work, and replies from what it actually stopped.
        // CANCELLED is terminal: no watchdog, budget rest
        // or boot pass can resurrect a killed task.
        if (!hadAttachment && TaskControl.isKillAll(raw)) {
            killAllTasks(chatId, raw.trim())
            return
        }

        // TARGETED KILL (v1.0.6): "kill this completely" —
        // one task, named or on screen — is a command too.
        // In 1.0.5 only the all-shapes were deterministic,
        // so Ethuram's "not this task, kill this
        // completely" fell to the brain and sat at
        // "…thinking" (2026-11-27 screenshot). Resolution
        // is TaskControl's fixed order — the name in the
        // message, the status card on screen, the newest
        // work here — and an unidentifiable referent gets
        // one deterministic line, never a brain call.
        if (!hadAttachment) {
            val cmd = TaskControl.parseKill(raw)
            if (cmd is TaskControl.KillCommand.Targeted) {
                killTargetedTask(chatId, raw.trim(), cmd)
                return
            }
        }

        // RESUME BY WORD (v1.0.6): "resume" in a chat with
        // paused or resting work is a COMMAND, handled
        // deterministically like the kill — never a brain
        // call. In 1.0.5 his "resume" fell to the brain,
        // which answered the X task's budget pause with an
        // intake list asking for HER handle, email,
        // password and phone number — items that are
        // already hers (2026-11-27 screenshot). The resume
        // restarts the record's own lane directly; a task
        // parked at his step reopens the browser panel at
        // that step instead of asking anything.
        if (!hadAttachment) {
            val resumeCmd = TaskControl.parseResume(raw)
            if (resumeCmd != null) {
                resumeTaskDeterministic(chatId, raw.trim(), resumeCmd)
                return
            }
        }

        // REASON FROM THE RECORD (v1.0.5): "why paused" —
        // and its kin — ask for the CAUSE the record
        // carries, and get it deterministically: killed by
        // his own command (named), a budget rest, a stall
        // park, a takeover, a wall. Before this, the shape
        // fell through to the brain, which invented a cause
        // (the same screenshot). Checked before the plain
        // status probe: "what happened" is both, and the
        // reason answer is the fuller truth. A chat with
        // no work of its own is not intercepted.
        if (!hadAttachment && StatusProbe.isReasonProbe(raw)) {
            val task = StatusProbe.latestTaskForChat(
                TaskStore(this).all(), chatId
            )
            if (task != null) {
                appendAssistantMessage(
                    chatId,
                    StatusProbe.reasonAnswerFor(
                        task, System.currentTimeMillis()
                    )
                )
                return
            }
        }

        // NEEDS FROM THE RECORD (v1.0.7): "how can I
        // help", "what do you need", "what's blocking
        // you" — in a chat with live work these ask what
        // SHE needs, and the record's answer is narrow:
        // she works on her own; the only thing that can
        // ever need him is a human step in the browser
        // panel. In 1.0.6 the question fell to the brain,
        // which answered with a blockers table claiming
        // her X creation was paused for want of HER OWN
        // handle, email, password and phone from him
        // (2026-11-27 paste). Checked after the reason
        // probe (its shapes are disjoint — "stopping" is
        // not probe vocabulary) and gated on the chat's
        // newest work standing in a needs state; job-
        // shaped lookalikes ("what do you need for the
        // report") carry an open-class object and are
        // never intercepted.
        if (!hadAttachment && StatusProbe.isNeedsProbe(raw)) {
            val task = StatusProbe.needsTaskFor(
                TaskStore(this).all(), chatId
            )
            if (task != null) {
                appendAssistantMessage(
                    chatId,
                    StatusProbe.needsAnswerFor(
                        task, System.currentTimeMillis()
                    )
                )
                return
            }
        }

        // LOGIN STATE FROM THE STAMP (v1.0.8): "already
        // logged in", "am I logged in", "is she signed
        // in" — answered from the record's identity
        // stamp (the handle the lane read off the page,
        // and when), never from the messages and never
        // from the brain. On 2026-11-27 he signed in
        // himself in her panel, handed back, said
        // "already logged in" — and the brain answered
        // "I can't tell from here whether you're logged
        // in." The stamp is the page's own testimony;
        // this family reads it. Checked before the
        // state-question family: the login assertion is
        // the more specific shape.
        if (!hadAttachment) {
            val loginTask = StatusProbe.loginTaskFor(
                raw, TaskStore(this).all(), chatId
            )
            if (loginTask != null) {
                appendAssistantMessage(
                    chatId,
                    StatusProbe.loginAnswerFor(
                        loginTask, System.currentTimeMillis()
                    )
                )
                return
            }
        }

        // ACCOUNTS FROM THE REGISTRY (v1.0.8): "which
        // accounts do you have", "what accounts are
        // connected" — answered from the accounts
        // registry (every verified sign-in, whose it is,
        // which is active), never from the brain.
        if (!hadAttachment && StatusProbe.isAccountsQuestion(raw)) {
            appendAssistantMessage(
                chatId,
                AccountsRegistry.answerFor(
                    AccountRegistryStore(this).root()
                )
            )
            return
        }

        // STATE QUESTIONS FROM THE RECORD (v1.0.8): the
        // whole family of questions about her work — a
        // task's state, outcome, or completion (incl.
        // yes/no forms: "so the x profile is created?",
        // "did it finish?"), the browser's current page,
        // and accountability for a claim she made ("why
        // did you claim its complete") — answered
        // deterministically from the record, zero brain
        // call, whenever this chat has a relevant record.
        // A finished packaged record is CROSS-CHECKED
        // against its finish gate in the answer itself:
        // a finish whose criteria fail is reported as
        // not standing. On 1.0.7 every one of these fell
        // to the brain, which disowned the record
        // ("I can't verify an active task's current
        // status from this chat") and invented a
        // confession — while the probe above answered
        // the same record correctly. Job-shaped
        // lookalikes keep their non-interception: the
        // grammar binds only to referents of records in
        // THIS chat.
        if (!hadAttachment) {
            val allTasks = TaskStore(this).all()
            val stateQ = StatusProbe.stateQuestionFor(
                raw, allTasks, chatId
            )
            if (stateQ != null) {
                appendAssistantMessage(
                    chatId,
                    StatusProbe.stateAnswerFor(
                        stateQ.first,
                        System.currentTimeMillis(),
                        stateQ.second,
                        BrowserSession.lastState?.url ?: "",
                        BrowserSession.lastState?.title ?: ""
                    )
                )
                return
            }
            // A browser question in a chat with no record:
            // the session's resting page still answers it.
            if (StatusProbe.isBrowserQuestion(raw)) {
                appendAssistantMessage(
                    chatId,
                    StatusProbe.browserAnswer(
                        null,
                        BrowserSession.lastState?.url ?: "",
                        BrowserSession.lastState?.title ?: ""
                    )
                )
                return
            }
        }

        // STATUS FROM STATE (v1.0.1): a bare status probe
        // in a chat with work of its own is answered from
        // the record — state, steps, the parked step — with
        // NO brain call. A status question must never reach
        // a model (the live failure: a fallback model
        // confabulated progress). Probes in a chat with no
        // work, and lookalikes carrying an object ("update
        // my bio"), are not intercepted: StatusProbe's
        // whole-message shapes decide, and the flow below
        // handles the rest exactly as before.
        if (!hadAttachment && StatusProbe.isProbe(raw)) {
            val task = StatusProbe.latestTaskForChat(
                TaskStore(this).all(), chatId
            )
            if (task != null) {
                answerStatusProbe(chatId, task)
                return
            }
        }

        // APPROVALS (v1.0.4, Plan/Act + the send gate): a
        // task parked for the user's word — a plan awaiting
        // approval, an email send awaiting its word — is
        // released by the deterministic approval shapes and
        // nothing else. The check runs before detection: an
        // approval is not a new job and not conversation.
        if (!hadAttachment && PlanAct.isApprovalMessage(raw)) {
            val pending = TaskStore(this).all()
                .filter {
                    it.optString(ChatTaskLink.ORIGIN_FIELD) == chatId &&
                        it.optString("state") == TaskStates.PAUSED &&
                        it.optString(PlanAct.APPROVAL_KIND_FIELD)
                            .isNotEmpty()
                }
                .maxByOrNull { it.optLong("updatedAt", 0L) }
            if (pending != null) {
                applyApproval(chatId, pending)
                return
            }
        }

        // UNDO (v1.0.4, checkpoints): the deterministic
        // command restores the newest checkpointed step of
        // this chat's latest task that has one.
        val undoNorm = raw.trim().lowercase().trimEnd('.', '!')
        if (!hadAttachment &&
            (undoNorm == "undo last step" || undoNorm == "undo the last step")
        ) {
            val task = StatusProbe.latestTaskForChat(
                TaskStore(this).all(), chatId
            )
            if (task != null && Checkpoints.count(task) > 0) {
                appendAssistantMessage(
                    chatId,
                    TaskRunner(this).undoLastCheckpoint(
                        task.optString("id")
                    )
                )
                return
            }
        }

        // RECIPES (v1.0.4): "run recipe <name>" runs a
        // recipe from her library in this chat — the
        // deterministic door to the recipe runner.
        if (!hadAttachment &&
            raw.trim().lowercase().startsWith("run recipe ")
        ) {
            val name = raw.trim().substringAfter(" ")
                .substringAfter(" ").trim().lowercase()
            startRecipeInChat(chatId, name)
            return
        }

        // STEERING A PACKAGED LANE (v1.0.7): in a chat
        // whose newest live work is a packaged lane (the
        // X lanes), a message about that work's conduct —
        // a correction ("email and password should be
        // handled by you end to end"), a suggestion ("for
        // your username try …"), a direction — is a STEER,
        // not conversation and not a new job. It is
        // recorded on the task record (the pendingSteers
        // queue the step-boundary machinery consumes,
        // plus a steer event) and answered from the
        // record: what the lane is doing — already
        // running, when it is — and what it uses. In
        // 1.0.6 the v1.0.4 steer recorded the words but
        // the BRAIN still answered them, with an intake
        // list for her own identity items and then a flat
        // denial that she has any (2026-11-27
        // screenshots). Probes (status / reason / needs)
        // run earlier and keep their answers; this net is
        // for the conduct messages they don't shape.
        if (!hadAttachment) {
            val task = StatusProbe.latestTaskForChat(
                TaskStore(this).all(), chatId
            )
            if (task != null &&
                !TaskStates.isTerminal(task.optString("state")) &&
                TaskSteer.isPackaged(task) &&
                TaskSteer.isConductMessage(raw)
            ) {
                val identityRef = TaskSteer.referencesIdentity(raw)
                val taskId = task.optString("id")
                TaskStore(this).update(taskId) {
                    val steers = it.optJSONArray("pendingSteers")
                        ?: JSONArray()
                    steers.put(raw)
                    it.put("pendingSteers", steers)
                    EventLog.append(
                        it, "steer",
                        System.currentTimeMillis(),
                        JSONObject().put("text", raw.take(120))
                    )
                }
                appendAssistantMessage(
                    chatId,
                    TaskSteer.steerAnswerFor(task, identityRef)
                )
                return
            }
        }

        // v0.13 — CHAT→TASK (the chat is the command surface):
        // a job given here is taken on by Lille herself, as
        // work in this thread. Detection is the
        // pure JobDetect (deterministic first; the model's
        // own clarifying question resolves through the same
        // pending-instruction path). A detected job creates
        // the task, acknowledges it in this chat in the
        // present tense — truthfully, because the record now
        // exists — and the engine's graded reports land back
        // in this thread (ChatTaskReporter). An ambiguous ask
        // gets ONE short question, never a guess.
        // A quoted reply is classified on its full marked
        // form: fragment + quote = the job input (the quote
        // is part of what the user is asking about). Unquoted
        // messages classify exactly as before, on raw.
        val jdText = if (quote != null) outgoing else raw
        when (val jd = JobDetect.classify(
            jdText, ChatData.messages(chatStore.root(), chatId),
            hasAttachment = hadAttachment
        )) {
            is JobDetect.Outcome.StartTask -> {
                // A fresh detection starts THIS message (its
                // full stored form, attachment note included);
                // a resolved "yes" starts the earlier ask the
                // question was about, recovered from the thread.
                startChatTask(
                    chatId,
                    if (jd.instruction == jdText) outgoing else jd.instruction
                )
                return
            }
            is JobDetect.Outcome.AskQuestion -> {
                appendAssistantMessage(chatId, JobDetect.QUESTION)
                return
            }
            JobDetect.Outcome.NotJob -> Unit
        }

        // STEERS (v1.0.4, interruption-as-event): a plain
        // message in a chat whose task is RUNNING also
        // steers that task — recorded on its record as an
        // event, applied at its next step boundary. The
        // chat conversation below proceeds exactly as
        // before; the steer never kills or restarts the run.
        run {
            val active = TaskStore(this).all()
                .filter {
                    it.optString(ChatTaskLink.ORIGIN_FIELD) == chatId &&
                        TaskStates.isResumable(it.optString("state"))
                }
                .maxByOrNull { it.optLong("updatedAt", 0L) }
            if (active != null) {
                val taskId = active.optString("id")
                TaskStore(this).update(taskId) {
                    val steers = it.optJSONArray("pendingSteers")
                        ?: JSONArray()
                    steers.put(raw)
                    it.put("pendingSteers", steers)
                }
            }
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

    /** The kill-all command (v1.0.5): stop EVERY
     *  non-terminal task on the store — general and
     *  packaged (the X lanes) alike — stamp each record
     *  killed-by-user with his command on it, cancel its
     *  WorkManager work, and refresh this chat's living
     *  status card when its own work was among the
     *  stopped, so the card reads "Stopped — you stopped
     *  this." in the same breath as the reply. The reply
     *  is composed by TaskControl from the titles actually
     *  stopped — the claim is the deed, counted. */
    private fun killAllTasks(chatId: String, command: String) {
        val store = TaskStore(this)
        val now = System.currentTimeMillis()
        val stoppedTitles = mutableListOf<String>()
        val stoppedIds = mutableSetOf<String>()
        for (task in store.all()) {
            if (!TaskControl.isStoppable(task)) continue
            val id = task.optString("id")
            if (id.isEmpty()) continue
            TaskWorker.cancel(this, id)
            store.update(id, TaskStates.CANCELLED) {
                TaskControl.stampKill(it, command, now)
            }
            stoppedTitles.add(TaskTransparency.titleFor(task))
            stoppedIds.add(id)
        }
        // This chat's own status card follows the kill in
        // the same turn — no stale "under way" left on
        // screen above the reply.
        val own = StatusProbe.latestTaskForChat(store.all(), chatId)
        if (own != null && own.optString("id") in stoppedIds) {
            val text = StatusProbe.answerFor(own, now)
            chatStore.upsertTaskStatus(
                chatId, own.optString("id"), text,
                own.optLong("updatedAt", 0L)
            )
            if (chatId == renderedChatId && uiAlive) renderChat(chatId)
        }
        appendAssistantMessage(chatId, TaskControl.replyFor(stoppedTitles))
        BackupManager.requestBackup(this)
    }

    /** The targeted kill (v1.0.6): stop the ONE task the
     *  command resolved to — TaskControl's order: the name
     *  in the message, this chat's on-screen status card,
     *  its newest work. Live work is cancelled and stamped
     *  killed-by-hand exactly as a kill-all kill (terminal
     *  under the watchdog); "completely" also archives the
     *  record so it never resurfaces. A record that already
     *  finished can only be closed away (with "completely")
     *  or is answered honestly — a finished task is never
     *  stamped as stopped. The chat's living status card
     *  for the killed task is refreshed in the same turn,
     *  like the kill-all path. */
    private fun killTargetedTask(
        chatId: String,
        command: String,
        cmd: TaskControl.KillCommand.Targeted
    ) {
        val store = TaskStore(this)
        val now = System.currentTimeMillis()
        val target = TaskControl.resolveTarget(
            store.all(), chatId, cmd.hint,
            chatStore.lastStatusTaskId(chatId)
        )
        if (target == null) {
            appendAssistantMessage(chatId, TaskControl.NO_REFERENT_LINE)
            return
        }
        val id = target.optString("id")
        val title = TaskTransparency.titleFor(target)
        when {
            TaskControl.isStoppable(target) -> {
                TaskWorker.cancel(this, id)
                store.update(id, TaskStates.CANCELLED) {
                    TaskControl.stampKill(it, command, now)
                    if (cmd.completely) {
                        TaskControl.stampArchive(it, now)
                    }
                }
                val fresh = store.get(id)
                if (fresh != null &&
                    chatStore.hasTaskStatus(chatId, id)
                ) {
                    chatStore.upsertTaskStatus(
                        chatId, id,
                        StatusProbe.answerFor(fresh, now),
                        fresh.optLong("updatedAt", 0L)
                    )
                    if (chatId == renderedChatId && uiAlive) {
                        renderChat(chatId)
                    }
                }
                appendAssistantMessage(
                    chatId, TaskControl.replyForTarget(title)
                )
            }
            cmd.completely -> {
                store.update(id) { TaskControl.stampArchive(it, now) }
                appendAssistantMessage(
                    chatId, TaskControl.replyForClosed(title)
                )
            }
            else -> appendAssistantMessage(
                chatId, TaskControl.replyForAlreadyFinished(title)
            )
        }
        BackupManager.requestBackup(this)
    }

    /** The deterministic resume (v1.0.6): restart the ONE
     *  record the command resolved to, in its own lane —
     *  a waiting record gets a forced pass (its stored
     *  wait cleared, a fresh all-routes attempt), a paused
     *  record is enqueued (the runner reads a spent
     *  budget window entering a run as deliberately
     *  resumed and grants a fresh one), a queued record
     *  is already going and is answered as such. A record
     *  PARKED at the user's own step is never enqueued:
     *  resume reopens the browser panel at that step
     *  (dismissal cleared; the panel follows the record)
     *  and the handback there is what resumes her. The
     *  resume is stamped on the record with his command. */
    private fun resumeTaskDeterministic(
        chatId: String,
        command: String,
        cmd: TaskControl.ResumeCommand
    ) {
        val store = TaskStore(this)
        val now = System.currentTimeMillis()
        val target = TaskControl.resolveResumeTarget(
            store.all(), chatId, cmd.hint,
            chatStore.lastStatusTaskId(chatId)
        )
        if (target == null) {
            val latest = StatusProbe.latestTaskForChat(
                store.all(), chatId
            )
            if (latest != null &&
                latest.optString("state") == TaskStates.RUNNING
            ) {
                appendAssistantMessage(
                    chatId,
                    TaskControl.replyForAlreadyGoing(
                        TaskTransparency.titleFor(latest)
                    )
                )
            } else {
                appendAssistantMessage(
                    chatId, TaskControl.NO_RESUMABLE_LINE
                )
            }
            return
        }
        val id = target.optString("id")
        val title = TaskTransparency.titleFor(target)
        when (target.optString("state")) {
            TaskStates.PARKED_AT_WALL -> {
                store.update(id) {
                    TaskControl.stampResume(it, command, now)
                }
                if (panelDismissedTaskId == id) {
                    panelDismissedTaskId = null
                }
                refreshBrowserPanel()
                appendAssistantMessage(
                    chatId,
                    TaskControl.replyForParkedResume(
                        title, target.optString("wallStep")
                    )
                )
            }
            TaskStates.QUEUED -> appendAssistantMessage(
                chatId, TaskControl.replyForAlreadyGoing(title)
            )
            TaskStates.WAITING_ROUTE,
            TaskStates.WAITING_NETWORK -> {
                store.update(id) {
                    TaskControl.stampResume(it, command, now)
                }
                TaskWorker.enqueueForced(this, id)
                appendAssistantMessage(
                    chatId, TaskControl.replyForResumed(title)
                )
            }
            else -> {
                store.update(id) {
                    TaskControl.stampResume(it, command, now)
                }
                TaskWorker.enqueue(this, id)
                appendAssistantMessage(
                    chatId, TaskControl.replyForResumed(title)
                )
            }
        }
        BackupManager.requestBackup(this)
    }

    /** v1.0.2 (TRANSPARENCY): a status probe is answered
     *  from the record by REWRITING the task's ONE living
     *  status message in place — never by appending another
     *  near-identical bubble (the stacking in Ethuram's
     *  1.0.1 screenshots). When the record has not moved
     *  since that message last reflected it, the rewrite
     *  carries one short no-change line instead of dressing
     *  the same block as news. */
    private fun answerStatusProbe(chatId: String, task: JSONObject) {
        val taskId = task.optString("id")
        val updatedAt = task.optLong("updatedAt", 0L)
        val reflected = chatStore.taskStatusReflectedAt(chatId, taskId)
        val noChangeSince =
            if (reflected != null && reflected == updatedAt && updatedAt > 0L) {
                updatedAt
            } else null
        val text = StatusProbe.answerFor(
            task, System.currentTimeMillis(), noChangeSince
        )
        chatStore.upsertTaskStatus(chatId, taskId, text, updatedAt)
        if (chatId == renderedChatId && uiAlive) renderChat(chatId)
        BackupManager.requestBackup(this)
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

    /** A re-tap that re-adopts a paused or parked run is
     *  a RESTART by his hand (v1.0.5): the pickup is
     *  stamped on the record (TaskControl.stampRestart —
     *  restartedAt, resumedAtStep, restartCount, the
     *  event) so the reason probe can say "you restarted
     *  it — it picked up at step N" instead of the
     *  inheritance being silent state. A run still in
     *  motion is merely re-linked: no stamp, nothing
     *  restarted. A KILLED record is terminal and never
     *  reaches this path — the callers' live search
     *  excludes it, and a fresh record is created instead:
     *  a restart never resurrects a killed task's
     *  identity. */
    private fun stampRestartIfPickup(task: JSONObject) {
        val state = task.optString("state")
        if (state == TaskStates.PAUSED ||
            state == TaskStates.PARKED_AT_WALL
        ) {
            TaskStore(this).update(task.optString("id")) {
                TaskControl.stampRestart(it, System.currentTimeMillis())
            }
        }
    }

    /** Link a task record to the chat it lives in and
     *  stamp its current state as already reported — the
     *  acknowledgement in the thread IS that report, so the
     *  reporter's first pass doesn't repeat it. */
    private fun linkTaskToChat(taskId: String, chatId: String) {
        TaskStore(this).update(taskId) {
            it.put(ChatTaskLink.ORIGIN_FIELD, chatId)
            it.put(ChatTaskLink.REPORTED_FIELD, TaskStates.RUNNING)
        }
    }

    /** Take a chat-given job on (CHAT IS THE ENGINE): the
     *  work runs as part of THIS thread — the record is
     *  linked to the chat, the acknowledgement reads as
     *  starting work here, and the engine (checkpoints,
     *  background survival, waiting states, resume) is the
     *  thread's invisible backbone. There is no hand-off
     *  and no other screen where the work lives. */
    private fun startChatTask(chatId: String, instruction: String) {
        val taskStore = TaskStore(this)
        // The task carries what the user sent, in full, plus
        // the standing fluency direction: gather missing
        // pieces herself during execution (ChatTaskLink).
        val task = taskStore.create(
            instruction + "\n\n" + ChatTaskLink.INTAKE_NOTE
        )
        val taskId = task.optString("id")
        // TOOL AVAILABILITY (v1.0.3, TaskTools): the plan
        // is stamped at creation — from the user's own
        // words, before the intake direction is added — so
        // the job's FIRST status in this chat already
        // declares what she can and cannot do here, and a
        // browser job holds the browser lane from its
        // first run. The runner re-stamps the same plan at
        // run start (idempotent).
        val plan = TaskTools.planFor(
            instruction,
            TaskTools.DeviceCaps(
                gmailConnected = GmailConnection.isConnected(this)
            )
        )
        taskStore.update(taskId) {
            TaskTools.stampPlan(it, plan)
            // PLAN/ACT (v1.0.4): a consequential job is
            // stamped PLAN at creation too, so its first
            // status already says she is planning first.
            PlanAct.stampInitialMode(it, instruction)
        }
        linkTaskToChat(taskId, chatId)
        TaskWorker.enqueue(this, taskId)
        appendAssistantMessage(chatId, ChatTaskLink.START_ACK)
    }

    /** Release a parked approval (v1.0.4): the word landed
     *  in the chat — the gate opens on the record and the
     *  engine resumes the work in this thread. A plan
     *  approval flips the run to ACT mode (the acting
     *  tools exist from the next run); a send approval
     *  arms the one pending send. */
    private fun applyApproval(chatId: String, task: JSONObject) {
        val taskId = task.optString("id")
        val kind = task.optString(PlanAct.APPROVAL_KIND_FIELD)
        TaskStore(this).update(taskId) {
            it.remove(PlanAct.APPROVAL_KIND_FIELD)
            it.remove(PlanAct.APPROVAL_DETAIL_FIELD)
            it.remove("note")
            if (kind == "gmail_send") {
                it.put("gmailSendApproved", true)
            } else {
                it.put(PlanAct.MODE_FIELD, PlanAct.MODE_ACT)
                it.put("planApproved", true)
            }
            EventLog.append(
                it, "approved", System.currentTimeMillis(),
                JSONObject().put("kind", kind)
            )
        }
        TaskWorker.enqueue(this, taskId)
        appendAssistantMessage(
            chatId,
            if (kind == "gmail_send") {
                "Approved — she's sending it now."
            } else {
                "Approved — she's carrying out the plan now."
            }
        )
    }

    /** Her recipe library (v1.0.4): name → recipe JSON text,
     *  in the agent-state store (rides the backup). The two
     *  built-ins are seeded on first read. */
    private fun recipeLibrary(): JSONObject {
        val store = AgentStateStore(this)
        val raw = store.readSection(AgentStateStore.SECTION_RECIPES)
        if (raw != null) {
            return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
        }
        val library = JSONObject()
        for (name in Recipes.builtinNames()) {
            Recipes.builtin(name)?.let { library.put(name, it.toString()) }
        }
        store.writeSection(
            AgentStateStore.SECTION_RECIPES, library.toString()
        )
        return library
    }

    /** Run a recipe in this chat (v1.0.4): the recipe's
     *  instructions (parameters substituted) become a task
     *  carrying the recipe itself — its tool allowlist is
     *  enforced at dispatch and its success check gates
     *  the finish. */
    private fun startRecipeInChat(chatId: String, name: String) {
        val recipeText = recipeLibrary().optString(name)
        val recipe = if (recipeText.isEmpty()) {
            null
        } else {
            try { JSONObject(recipeText) } catch (e: Exception) { null }
        }
        if (recipe == null) {
            appendAssistantMessage(
                chatId,
                "There's no recipe called \"$name\" in her library " +
                    "yet — the built-ins are " +
                    Recipes.builtinNames().joinToString(", ") + "."
            )
            return
        }
        val instruction = Recipes.substitute(recipe, emptyMap())
        val taskStore = TaskStore(this)
        val task = taskStore.create(
            instruction + "\n\n" + ChatTaskLink.INTAKE_NOTE
        )
        val taskId = task.optString("id")
        taskStore.update(taskId) {
            it.put("kind", "recipe")
            it.put("recipeName", name)
            it.put("recipeJson", recipe.toString())
        }
        linkTaskToChat(taskId, chatId)
        TaskWorker.enqueue(this, taskId)
        appendAssistantMessage(
            chatId,
            "Running the recipe \"$name\" here — she'll report " +
                "back in this chat when it's done."
        )
    }

    // ---------- the main chat's menu + packaged jobs ----------

    /** The quiet header menu: packaged jobs (state-driven,
     *  MainChatActions) + Wallet + Settings. The list is
     *  computed at tap time from the two live verdicts —
     *  OwnerGate on the verified login, LilleXState on her
     *  creation record — so it can never show a stale
     *  action. */
    private fun showMainMenu(anchor: View) {
        val isOwner = OwnerGate.canCreateXAccountTask(fluidIdentity?.username)
        val lilleVerified = LilleXState.hasVerifiedAccount(TaskStore(this).all())
        val actions = MainChatActions.menuActions(isOwner, lilleVerified)
        // v1.0.2: the header menu renders on the ONE shared
        // menu surface (LilleMenu) like every other popup —
        // same actions, same order, same handlers.
        LilleMenu.simpleMenu(this, actions.map { it.menuTitle }) { picked ->
            when (actions[picked]) {
                MainChatActions.Action.CREATE_X_ACCOUNT ->
                    startXCreationInChat()
                MainChatActions.Action.CONNECT_LILLE_X_ACCOUNT ->
                    startLilleXConnectInChat()
                MainChatActions.Action.CONNECT_X_ACCOUNT ->
                    startXConnectInChat()
            }
        }
    }

    /**
     * The main-page wallet card (v1.0.1): her balances at a
     * glance across the three families, read through the
     * same engines the Wallet screen uses. Each family
     * lands independently; WalletSummary keeps every state
     * honest (real value, true zero, couldn't load — never
     * a fake zero). The EVM glance reads Ethereum, the
     * default chain; the full screen has every chain.
     */
    private fun refreshWalletCard() {
        if (!::walletCardText.isInitialized) return
        val wallet = AgentWallet(this)
        if (!wallet.hasWallet() && !wallet.hasBtcWallet() &&
            !wallet.hasSolWallet()
        ) {
            walletCardText.text = "Not set up yet — tap to create her wallet"
            return
        }
        val families = linkedMapOf<String, WalletSummary.Family>(
            "Ethereum" to WalletSummary.Family.Loading,
            "Bitcoin" to WalletSummary.Family.Loading,
            "Solana" to WalletSummary.Family.Loading
        )
        fun render() {
            if (isFinishing || isDestroyed) return
            walletCardText.text = WalletSummary.cardText(families.toList())
        }
        if (wallet.hasWallet()) {
            WalletEngine(this).loadBalances(WalletGate.ETHEREUM) { balances ->
                families["Ethereum"] = WalletSummary.Family.Loaded(balances)
                render()
            }
        } else families["Ethereum"] = WalletSummary.Family.NotSetUp
        if (wallet.hasBtcWallet()) {
            BtcEngine(this).loadBalances { balances ->
                families["Bitcoin"] = WalletSummary.Family.Loaded(balances)
                render()
            }
        } else families["Bitcoin"] = WalletSummary.Family.NotSetUp
        if (wallet.hasSolWallet()) {
            SolEngine(this).loadBalances { balances ->
                families["Solana"] = WalletSummary.Family.Loaded(balances)
                render()
            }
        } else families["Solana"] = WalletSummary.Family.NotSetUp
        render()
    }

    /** "Create Lille's X account" (owner only): her own
     *  account, her identity kit — started here, living in
     *  this thread (state line, parked step on the browser
     *  panel, graded report). A live creation record is
     *  re-adopted into this chat instead of duplicated; a
     *  hollow build says so plainly and starts nothing. */
    private fun startXCreationInChat() {
        val chatId = activeChatId
        val store = TaskStore(this)
        val tasks = store.all()
        val live = tasks.firstOrNull {
            LilleXState.isCreationTask(it) &&
                !TaskStates.isTerminal(it.optString("state"))
        }
        val unavailableLine =
            "Her X account can't be created from this build — it " +
                "carries none of her owner configuration. Nothing " +
                "was started."
        when {
            live != null -> {
                val id = live.optString("id")
                linkTaskToChat(id, chatId)
                if (live.optString("state") == TaskStates.QUEUED) {
                    TaskWorker.enqueue(this, id)
                }
                stampRestartIfPickup(live)
                appendAssistantMessage(
                    chatId,
                    StatusProbe.liveAckFor("Creating her X account", live)
                )
            }
            tasks.any { XAccountTask.isUnavailableRecord(it) } ->
                appendAssistantMessage(chatId, unavailableLine)
            else -> {
                val task = XAccountTask.create(this)
                val id = task.optString("id")
                if (XAccountTask.isUnavailableRecord(task)) {
                    appendAssistantMessage(chatId, unavailableLine)
                } else {
                    linkTaskToChat(id, chatId)
                    TaskWorker.enqueue(this, id)
                    appendAssistantMessage(chatId, ChatTaskLink.START_ACK)
                }
            }
        }
    }

    /** "Connect Lille's X account" (owner only, shown only
     *  once her account is verified on record): re-establish
     *  HER session — she signs back in from the vault
     *  credential if the session is gone, parking only for
     *  X's own human checks. */
    private fun startLilleXConnectInChat() {
        val chatId = activeChatId
        val store = TaskStore(this)
        val handle = LilleXState.verifiedHandle(store.all())
        if (handle == null) {
            appendAssistantMessage(
                chatId,
                "Her X account isn't on record yet — creation comes first."
            )
            return
        }
        val live = store.all().firstOrNull {
            it.optString("title") == XAccountTask.CONNECT_TITLE &&
                !TaskStates.isTerminal(it.optString("state"))
        }
        if (live != null) {
            val id = live.optString("id")
            linkTaskToChat(id, chatId)
            if (live.optString("state") == TaskStates.QUEUED) {
                TaskWorker.enqueue(this, id)
            }
            stampRestartIfPickup(live)
            appendAssistantMessage(
                chatId,
                StatusProbe.liveAckFor("Reconnecting her X session", live)
            )
            return
        }
        val task = XAccountTask.createConnect(this, handle)
        val id = task.optString("id")
        linkTaskToChat(id, chatId)
        TaskWorker.enqueue(this, id)
        appendAssistantMessage(chatId, ChatTaskLink.START_ACK)
    }

    /** "Connect my X account" (every user): the USER's own
     *  account — they sign in themselves in her parked
     *  session; she never sees their password (the plain
     *  copy says so at the start, in the thread). */
    private fun startXConnectInChat() {
        val chatId = activeChatId
        val store = TaskStore(this)
        val live = store.all().firstOrNull {
            it.optString("title") == XConnectTask.TITLE &&
                !TaskStates.isTerminal(it.optString("state"))
        }
        if (live != null) {
            val id = live.optString("id")
            linkTaskToChat(id, chatId)
            if (live.optString("state") == TaskStates.QUEUED) {
                TaskWorker.enqueue(this, id)
            }
            stampRestartIfPickup(live)
            appendAssistantMessage(
                chatId,
                StatusProbe.liveAckFor("Connecting your X account", live)
            )
            return
        }
        val task = XConnectTask.create(this)
        val id = task.optString("id")
        linkTaskToChat(id, chatId)
        TaskWorker.enqueue(this, id)
        appendAssistantMessage(
            chatId,
            ChatTaskLink.START_ACK + "\n\n" + XConnectTask.PLAIN_COPY
        )
    }

    // ---------- the in-chat live browser panel ----------

    /** Recompute the panel from the record: this chat's
     *  newest work + the browser lane's owner (TaskGate) +
     *  the user's hold. Cheap facts, read fresh — the
     *  panel follows the work, including work that moved
     *  while this screen was closed. */
    private fun refreshBrowserPanel() {
        if (!::browserPanel.isInitialized || renderedChatId.isEmpty()) return
        val task = StatusProbe.latestTaskForChat(
            TaskStore(this).all(), renderedChatId
        )
        val ownerId = TaskGate.shared.browserOwnerTask()
        val taskId = task?.optString("id") ?: ""
        val state = BrowserPanel.stateFor(
            task?.optString("state"),
            task != null && TaskRunner.isBrowserTask(task),
            ownerId != null && ownerId == taskId,
            ownerId != null && ownerId != taskId,
            task?.optBoolean("browserTakeover", false) == true
        )
        // A dismissal belongs to ONE task; a different
        // task becoming this chat's latest clears it.
        if (panelDismissedTaskId != null &&
            panelDismissedTaskId != taskId
        ) {
            panelDismissedTaskId = null
        }
        val dismissed = panelDismissedTaskId != null &&
            panelDismissedTaskId == taskId
        applyPanelState(state, task, dismissed)
    }

    private fun applyPanelState(
        state: BrowserPanel.State,
        task: JSONObject?,
        dismissed: Boolean = false
    ) {
        panelState = state
        panelTaskId = task?.optString("id")?.takeIf { it.isNotEmpty() }
        // The indicator chip (v1.0.3): shown exactly while
        // the panel is dismissed and the work is still
        // browsing — one tap reopens the panel.
        if (BrowserPanel.indicatorShown(state, dismissed)) {
            browserChip.text = BrowserPanel.INDICATOR_LINE
            browserChip.visibility = View.VISIBLE
        } else {
            browserChip.visibility = View.GONE
        }
        if (!BrowserPanel.panelVisible(state, dismissed)) {
            // Hidden — no work, or dismissed by the user.
            // Either way the session detaches (it lives on
            // in BrowserSession; her steps keep driving it)
            // and the task itself is never touched here.
            browserPanel.visibility = View.GONE
            if (panelExpanded) setPanelExpanded(false)
            if (browserPanelContainer.childCount > 0) {
                BrowserSession.detach()
            }
            return
        }
        when (state) {
            BrowserPanel.State.WAITING_OTHER -> {
                browserPanel.visibility = View.VISIBLE
                browserPanelLabel.text = "Lille's browser"
                browserPanelWaiting.text = BrowserPanel.WAITING_OTHER_LINE
                browserPanelWaiting.visibility = View.VISIBLE
                browserPanelContainer.visibility = View.GONE
                browserPanelAction.visibility = View.GONE
                browserPanelExpand.visibility = View.GONE
                browserPanelClose.visibility = View.VISIBLE
                if (browserPanelContainer.childCount > 0) {
                    BrowserSession.detach()
                }
            }
            else -> {
                browserPanel.visibility = View.VISIBLE
                browserPanelWaiting.visibility = View.GONE
                browserPanelContainer.visibility = View.VISIBLE
                browserPanelExpand.visibility = View.VISIBLE
                browserPanelClose.visibility = View.VISIBLE
                browserPanelLabel.text = when (state) {
                    BrowserPanel.State.PARKED -> BrowserPanel.PARKED_LABEL
                    BrowserPanel.State.USER_HOLD -> BrowserPanel.USER_HOLD_LABEL
                    else -> BrowserPanel.WATCH_LABEL
                }
                // The action is an icon button now (v1.0.3):
                // the wheel takes over, the return arrow
                // hands back; the words live in the content
                // description (BrowserPanel's pinned labels).
                browserPanelAction.visibility = View.VISIBLE
                if (state == BrowserPanel.State.WATCH) {
                    browserPanelAction.setImageResource(R.drawable.ic_takeover)
                    browserPanelAction.contentDescription =
                        BrowserPanel.TAKE_OVER_LABEL
                } else {
                    browserPanelAction.setImageResource(R.drawable.ic_handback)
                    browserPanelAction.contentDescription =
                        BrowserPanel.HAND_BACK_LABEL
                }
                if (browserPanelContainer.childCount == 0) {
                    BrowserSession.attachTo(this, browserPanelContainer)
                }
            }
        }
    }

    /** Expand = the panel takes the conversation's space
     *  (the transcript steps aside); shrink restores it.
     *  Same session throughout — only the frame changes. */
    private fun setPanelExpanded(expanded: Boolean) {
        panelExpanded = expanded
        browserPanelExpand.setImageResource(
            if (expanded) R.drawable.ic_shrink else R.drawable.ic_expand
        )
        browserPanelExpand.contentDescription = if (expanded) {
            BrowserPanel.SHRINK_LABEL
        } else {
            BrowserPanel.EXPAND_LABEL
        }
        scrollView.visibility = if (expanded) View.GONE else View.VISIBLE
        val plp = browserPanel.layoutParams as LinearLayout.LayoutParams
        if (expanded) {
            plp.height = 0
            plp.weight = 1f
        } else {
            plp.height = LinearLayout.LayoutParams.WRAP_CONTENT
            plp.weight = 0f
        }
        browserPanel.layoutParams = plp
        val clp = browserPanelContainer.layoutParams
        clp.height = if (expanded) {
            LinearLayout.LayoutParams.MATCH_PARENT
        } else {
            dp(300)
        }
        browserPanelContainer.layoutParams = clp
    }

    /** The panel's one action button, by state:
     *  - WATCH → "Take over": her run pauses (the record
     *    says he is driving); he works in the live session.
     *  - PARKED → "Done — hand back": the shared handback
     *    (WallHandback) — the human check was passed right
     *    here, in the chat where it was announced.
     *  - USER_HOLD → "Done — hand back": her run resumes
     *    from its checkpoint. */
    private fun onPanelAction() {
        val id = panelTaskId ?: return
        val store = TaskStore(this)
        when (panelState) {
            BrowserPanel.State.WATCH -> {
                val state = store.get(id)?.optString("state") ?: ""
                if (state == TaskStates.RUNNING &&
                    TaskStates.canTransition(state, TaskStates.PAUSED)
                ) {
                    store.update(id, TaskStates.PAUSED) {
                        it.put("browserTakeover", true)
                        it.put(
                            "note",
                            "You took the browser over in the chat — " +
                                "Lille is paused. Tap Done — hand back " +
                                "when you're finished."
                        )
                    }
                }
            }
            BrowserPanel.State.PARKED -> {
                if (WallHandback.handBack(this, id)) {
                    appendAssistantMessage(
                        activeChatId, "Handed back — she's resuming."
                    )
                }
            }
            BrowserPanel.State.USER_HOLD -> {
                val state = store.get(id)?.optString("state") ?: ""
                if (state == TaskStates.PAUSED &&
                    TaskStates.canTransition(state, TaskStates.RUNNING)
                ) {
                    store.update(id, TaskStates.RUNNING) {
                        it.remove("browserTakeover")
                        it.put("handedBackAt", System.currentTimeMillis())
                    }
                    TaskWorker.enqueue(this, id)
                    appendAssistantMessage(
                        activeChatId, "Handed back — she's resuming."
                    )
                }
            }
            else -> Unit
        }
        refreshBrowserPanel()
    }

    private val walletEngine by lazy { WalletEngine(this) }
    private val btcEngine by lazy { BtcEngine(this) }
    private val solEngine by lazy { SolEngine(this) }

    /** A parsed send prompt (v0.12): prepare from live state,
     *  then the gate decides — autonomous-within-caps sends
     *  and reports; anything needing approval shows the
     *  approval sheet HERE, in the chat the user is looking
     *  at ("sign where you stand"); if this chat isn't on
     *  screen, nothing is signed and the message says how to
     *  approve. The family engines share this one flow: the
     *  intent's family picks the engine, everything after
     *  preparation is identical. */
    private fun handleWalletPrompt(
        chatId: String,
        intent: WalletPrompt.SendIntent
    ) {
        val wallet = AgentWallet(this)
        val has = when (intent.family) {
            WalletGate.FAMILY_BITCOIN -> wallet.hasBtcWallet()
            WalletGate.FAMILY_SOLANA -> wallet.hasSolWallet()
            else -> wallet.hasWallet()
        }
        if (!has) {
            val familyName = when (intent.family) {
                WalletGate.FAMILY_BITCOIN -> "Bitcoin "
                WalletGate.FAMILY_SOLANA -> "Solana "
                else -> ""
            }
            appendAssistantMessage(
                chatId,
                "I don't have my own ${familyName}wallet yet, so there's " +
                    "nothing for me to send from. Create it in Settings → " +
                    "Lille's wallet (one tap sets up every family), fund " +
                    "it with what I may spend, then ask me again in " +
                    "exactly that phrasing. Nothing was sent."
            )
            return
        }
        val fromAddress = when (intent.family) {
            WalletGate.FAMILY_BITCOIN -> wallet.btcAddress() ?: ""
            WalletGate.FAMILY_SOLANA -> wallet.solAddress() ?: ""
            else -> wallet.address() ?: ""
        }
        appendAssistantMessage(chatId, "Preparing that send from my own wallet…")
        when (intent.family) {
            WalletGate.FAMILY_BITCOIN ->
                btcEngine.prepareFromPrompt(intent) { outcome ->
                    onWalletPrepared(chatId, outcome, fromAddress) { p, a, r, d ->
                        btcEngine.execute(p, a, r, d)
                    }
                }
            WalletGate.FAMILY_SOLANA ->
                solEngine.prepareFromPrompt(intent) { outcome ->
                    onWalletPrepared(chatId, outcome, fromAddress) { p, a, r, d ->
                        solEngine.execute(p, a, r, d)
                    }
                }
            else ->
                walletEngine.prepareFromPrompt(intent) { outcome ->
                    onWalletPrepared(chatId, outcome, fromAddress) { p, a, r, d ->
                        walletEngine.execute(p, a, r, d)
                    }
                }
        }
    }

    /** The shared post-preparation flow for every family:
     *  the gate decides (via the family's gated signer);
     *  approval happens on the sheet, in this chat, or not
     *  at all. [execute] is the preparing family's signer. */
    private fun onWalletPrepared(
        chatId: String,
        outcome: WalletEngine.PrepareOutcome,
        fromAddress: String,
        execute: (
            WalletGate.PreparedTx, Boolean, Boolean,
            (WalletEngine.ExecOutcome) -> Unit
        ) -> Unit
    ) {
        when (outcome) {
            is WalletEngine.PrepareOutcome.Failed ->
                appendAssistantMessage(chatId, outcome.message)
            is WalletEngine.PrepareOutcome.Ready -> {
                val prepared = outcome.prepared
                execute(
                    prepared, false, false
                ) { exec ->
                    when (exec) {
                        WalletEngine.ExecOutcome.NeedsApproval -> {
                            if (chatId == renderedChatId && uiAlive &&
                                !isFinishing
                            ) {
                                WalletApprovalUi.show(
                                    this, prepared, fromAddress,
                                    onApprove = { rawConfirmed ->
                                        appendAssistantMessage(
                                            chatId,
                                            "Approved — signing and sending " +
                                                "from my wallet…"
                                        )
                                        execute(
                                            prepared, true, rawConfirmed
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
                                        WalletGate.networkLabel(prepared.chainKey) +
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
        BrainLane.shared.chatFinished(System.currentTimeMillis())
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
            applyPendingLiveReaction(chatId)
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
        // Leak guard: an unapplied live reaction dies with
        // its flight — it never lands on a later reply.
        flight.pendingLiveReaction = null
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

    /** Does the composer hold anything submitMessage
     *  would send — text beyond whitespace, or a pending
     *  attachment? The ComposerAction input: a stop face
     *  over sendable content is the 1.0.5 defect. */
    private fun composerHasContent(): Boolean =
        input.text.toString().trim().isNotEmpty() ||
            pendingAttachment != null

    /** Send button + header mascot follow the RENDERED chat's
     *  flight state AND the composer's content (v1.0.6,
     *  ComposerAction): Stop only when busy with an empty
     *  composer; Send whenever tapping would send — now, or
     *  queued in order behind the flight. The mascot carries
     *  the state — WAITING while BrainPacing holds the reply,
     *  WORKING while a reply is actually being produced, IDLE
     *  at rest. */
    private fun updateComposerState() {
        val flight = flights[activeChatId]
        val busy = flight?.queue?.inFlight == true
        val action = ComposerAction.actionFor(composerHasContent(), busy)
        sendButton.setImageResource(
            resources.getIdentifier(
                ComposerAction.iconName(action), "drawable", packageName
            )
        )
        sendButton.contentDescription = ComposerAction.description(action)
        input.hint = if (busy) {
            "Type another message — the send key queues it"
        } else {
            defaultHint
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
     * keys join the chain — BrainFailure.chain over
     * OwnKeyRoutes. v1.0.2 (OWN KEYS LEAD): saved own keys
     * walk FIRST and the keyless pair is the fallback.
     * Pacing + fall-through + per-route health below are
     * the chain's resilience — pacing and health are
     * per-route, so the keyless pair's values never
     * throttle an own-key route.)
     */
    private fun askBrain(chatId: String, userText: String, attempt: Int) {
        askBrain(chatId, userText, attempt, reframed = false, carry = "", continuations = 0,
                 historyText = userText)
    }

    /** Fire one route's call. Fall-through lands here
     *  directly (brainRouteFailed) — immediate, no pacing
     *  gate, no narration: the bubble simply keeps its
     *  thinking state until an answer lands. */
    private fun dispatchBrainRoute(
        chatId: String, userText: String, attempt: Int, reframed: Boolean,
        historyText: String, route: BrainFailure.Route
    ) {
        when (route) {
            BrainFailure.Route.POLLINATIONS ->
                askBrainNow(chatId, userText, attempt, reframed, "", 0, historyText)
            BrainFailure.Route.HORDE -> {
                showThinking(chatId)
                tryHorde(chatId, userText, historyText)
            }
            else -> {
                showThinking(chatId)
                tryOwnKeyRoute(chatId, userText, historyText, route)
            }
        }
    }

    /** Put the flight's bubble into its ordinary thinking
     *  state (used by the non-streaming routes, which have
     *  no bubble of their own to open). */
    private fun showThinking(chatId: String) {
        val flight = flightFor(chatId)
        if (!flight.replyOpen) {
            flight.replyOpen = true
            replaceCurrentReply(chatId, "…thinking")
        }
    }

    /**
     * A fresh ask (v1.0.1 seamless discipline): rebuild the
     * chain, start at the first healthy route — a cooling
     * route is SKIPPED while another exists, never waited
     * out in front of the user. Pacing applies only to a
     * repeat call to the SAME route the fresh call starts
     * on. When the whole chain is resting, the flight waits
     * in its ordinary thinking state for the earliest
     * recovery. Nothing about routes is ever said.
     * Continuations and reframes of an in-flight answer go
     * straight to the primary route's call, as before.
     */
    private fun askBrain(chatId: String, userText: String, attempt: Int, reframed: Boolean,
                         carry: String, continuations: Int, historyText: String) {
        val flight = flightFor(chatId)
        if (carry.isEmpty() && continuations == 0) {
            flight.guardRetried = false
            flight.guardReassert = false
            // v0.12.1: the chain is rebuilt per fresh ask —
            // keyless pair first, then the user's own-key
            // routes for keys actually saved (OwnKeyRoutes).
            refreshBrainChain()
            val now = System.currentTimeMillis()
            val first = BrainFailure.firstHealthy(brainChain, routeCoolingUntil, now)
            if (first == null) {
                val earliest = routeCoolingUntil.values.minOrNull() ?: now
                val waitMs = maxOf(earliest - now, 30_000L)
                flight.waiting = true
                if (chatId == activeChatId) updateComposerState()
                showThinking(chatId)
                schedule(flight, waitMs) {
                    askBrain(chatId, userText, attempt + 1, reframed,
                             "", 0, historyText)
                }
                return
            }
            val waitMs = BrainPacing.remainingIntervalMs(
                lastBrainCallAtByRoute[first] ?: 0L, now
            )
            if (waitMs > 0) {
                flight.waiting = true
                if (chatId == activeChatId) updateComposerState()
                showThinking(chatId)
                schedule(flight, waitMs) {
                    dispatchBrainRoute(chatId, userText, attempt, reframed,
                                       historyText, first)
                }
                return
            }
            dispatchBrainRoute(chatId, userText, attempt, reframed, historyText, first)
            return
        }
        askBrainNow(chatId, userText, attempt, reframed, carry, continuations, historyText)
    }

    /** The chat brain's system prompt for one ask: the
     *  standing prompt + the CAPABILITY SHEET (v1.0.5) —
     *  her real capabilities as a fact block, generated
     *  from the live connection state read at this moment
     *  (Gmail connected or not, her X account's standing,
     *  the wallet families that exist, the user's own
     *  computer configured or not). A reply that denies a
     *  capability she has is a contradiction of this
     *  sheet; the output guard's capability-denial rule is
     *  the net, and a regeneration after such a rejection
     *  carries the sheet's re-assert line (the flight's
     *  guardReassert flag). */
    private fun chatSystemPrompt(chatId: String): String {
        val tasks = TaskStore(this).all()
        val wallet = AgentWallet(this)
        val facts = CapabilitySheet.Facts(
            gmailConnected = GmailConnection.isConnected(this),
            xLine = CapabilitySheet.xLineFor(
                LilleXState.verifiedHandle(tasks),
                tasks.any {
                    LilleXState.isCreationTask(it) &&
                        !TaskStates.isTerminal(it.optString("state"))
                }
            ),
            walletFamilies = listOfNotNull(
                if (wallet.hasWallet()) "Ethereum" else null,
                if (wallet.hasBtcWallet()) "Bitcoin" else null,
                if (wallet.hasSolWallet()) "Solana" else null
            ),
            lanConfigured = LanRoute.isEnabled(this) &&
                vault.hasSecret(OwnKeyRoutes.LAN_URL_VAULT_KEY)
        )
        // THE STATE SHEET (v1.0.8): what she IS doing —
        // every task's standing, the browser's resting
        // page, the identity stamps, the accounts
        // registry — composed fresh for THIS turn from
        // the live stores. Whatever the brain is asked
        // about her work, the facts are in its context;
        // the deterministic probe family answers the
        // shaped questions before the brain is reached,
        // and this sheet grounds everything else.
        val stateSheet = StateSheet.render(
            tasks,
            StateSheet.Facts(
                browserUrl = BrowserSession.lastState?.url ?: "",
                browserTitle = BrowserSession.lastState?.title ?: "",
                xVerifiedHandle = LilleXState.verifiedHandle(tasks),
                walletFamilies = facts.walletFamilies,
                accountsLines = AccountsRegistry.summaryLines(
                    AccountRegistryStore(this).root()
                )
            ),
            System.currentTimeMillis()
        )
        val base = SYSTEM_PROMPT + "\n\n" +
            CapabilitySheet.render(facts) + "\n\n" + stateSheet
        return if (flightFor(chatId).guardReassert) {
            base + "\n\n" + CapabilitySheet.REASSERT_LINE
        } else {
            base
        }
    }

    /** Deliver a finished chat answer: history, store,
     *  bubble, backup, flight end — the one path every
     *  route's answer lands through. */
    private fun deliverChatAnswer(chatId: String, historyText: String, shown: String) {
        val flight = flightFor(chatId)
        flight.guardRetried = false
        flight.guardReassert = false
        flight.history.add(JSONObject().put("role", "user").put("content", historyText))
        flight.history.add(JSONObject().put("role", "assistant").put("content", shown))
        chatStore.appendMessage(chatId, "assistant", shown)
        applyPendingLiveReaction(chatId)
        if (chatId == renderedChatId) renderedMtime = chatStore.lastModified()
        replaceCurrentReply(chatId, shown)
        BackupManager.requestBackup(this)
        finishFlight(chatId)
    }

    /** The chat-output guard's glue (ChatOutputGuard): a
     *  rejected reply is logged to the trace only, then
     *  silently regenerated ONCE on the next route. A
     *  second rejection — or no next route — lands the one
     *  plain line. The user never sees the rejected text,
     *  and never hears about the machinery.
     *
     *  v1.0.5 (capability grounding): a capability-denial
     *  rejection regenerates with the capability sheet
     *  RE-ASSERTED in the system prompt (the flight's
     *  guardReassert flag), and a second denial lands the
     *  sheet's one grounded line instead of the generic
     *  plain line — the user hears a true sentence about
     *  what she can do, never the denial and never a
     *  shrug. */
    private fun guardRegenerateOrPlain(
        chatId: String, userText: String, historyText: String,
        fromRoute: BrainFailure.Route, rejectedText: String
    ) {
        val flight = flightFor(chatId)
        logGuardRejection(rejectedText)
        val reason = (ChatOutputGuard.verdict(rejectedText)
            as? ChatOutputGuard.Verdict.Rejected)?.reason
        val fallback = when (reason) {
            "capability-denial" -> CapabilitySheet.GROUNDED_LINE
            "identity-ask" -> ChatOutputGuard.IDENTITY_LINE
            "identity-denial" -> ChatOutputGuard.DENIAL_LINE
            // Status-denial (v1.0.8): the fallback is not a
            // shrug — it is the record's own answer to the
            // question about her work, discrepancy and
            // identity stamp included.
            "status-denial" -> statusDenialFallback(chatId)
            else -> ChatOutputGuard.PLAIN_LINE
        }
        if (!flight.guardRetried) {
            val next = BrainFailure.pickNext(
                fromRoute, brainChain, routeCoolingUntil,
                System.currentTimeMillis()
            )
            if (next != null) {
                flight.guardRetried = true
                if (reason == "capability-denial" ||
                    reason == "identity-denial" ||
                    reason == "status-denial"
                ) {
                    flight.guardReassert = true
                }
                dispatchBrainRoute(chatId, userText, 0, false, historyText, next)
                return
            }
        }
        deliverChatAnswer(chatId, historyText, fallback)
    }

    /** The status-denial fallback (v1.0.8): this chat's
     *  newest record answers for itself — the standing,
     *  the discrepancy when a finish doesn't hold, the
     *  identity stamp. No record in the chat → the plain
     *  line (the denial is still never shown). */
    private fun statusDenialFallback(chatId: String): String {
        val task = StatusProbe.latestTaskForChat(
            TaskStore(this).all(), chatId
        ) ?: return ChatOutputGuard.PLAIN_LINE
        return StatusProbe.recordAnswerFor(
            task, System.currentTimeMillis()
        )
    }

    /** Rejected reply text goes to a bounded trace file —
     *  evidence for debugging, never a user surface. */
    private fun logGuardRejection(rejectedText: String) {
        try {
            val file = File(filesDir, "chat_guard_trace.txt")
            val entry = "\n=== ${System.currentTimeMillis()} ===\n" + rejectedText
            val prior = if (file.exists()) file.readText() else ""
            val merged = prior + entry
            file.writeText(
                if (merged.length > 100_000) {
                    merged.substring(merged.length - 50_000)
                } else {
                    merged
                }
            )
        } catch (e: Exception) {
            // The trace is best-effort; the guard's verdict stands.
        }
    }

    /**
     * v0.2.1 smart recovery, mirroring the Python router:
     * - STREAMING (stream=true): if the connection drops mid-answer,
     *   the partial text is kept and the SAME task auto-continues
     *   ("continue from where you stopped", max MAX_CONTINUATIONS),
     *   stitching the parts into one answer.
     * - REFUSAL RECOVERY: if the free model refuses the phrasing of a
     *   vague outcome request, Lille reframes it ONCE as a concrete
     *   task and retries — invisibly (seamless law: the recovery is
     *   never announced, and a reframed answer is shown bare).
     *   Refusals of harmful requests are never reframed; a second
     *   refusal is delivered as the model's own words, unadorned.
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
        messages.put(JSONObject().put("role", "system").put("content", chatSystemPrompt(chatId)))
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

        stampBrainCall(BrainFailure.Route.POLLINATIONS)
        val call = client.newCall(request)
        flight.call = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                postIfCurrent(flight, gen) {
                    BrainLane.shared.chatFinished(System.currentTimeMillis())
                    if (carry.isNotEmpty() && continuations < MAX_CONTINUATIONS) {
                        // Dropped before/while streaming with a partial
                        // answer preserved: auto-continue, don't lose it.
                        // The bubble just keeps the partial — the
                        // continuation is never announced.
                        replaceCurrentReply(chatId, carry)
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
                    BrainLane.shared.chatFinished(System.currentTimeMillis())
                    if (code == 200) {
                        // First success ends any rate-limit streak.
                        consecutiveRateLimits =
                            BrainPacing.nextCounter(consecutiveRateLimits, code)
                    }
                    when {
                        code == 200 && dropped && full.isNotEmpty() &&
                            continuations < MAX_CONTINUATIONS -> {
                            replaceCurrentReply(chatId, full)
                            askBrain(chatId, userText, attempt, reframed, full, continuations + 1, historyText)
                        }
                        code == 200 && full.isNotEmpty() && isRefusalReply(full) &&
                            !reframed && !looksHarmful(userText) -> {
                            // Refusal recovery: reframe ONCE, silently.
                            askBrain(chatId, reframeRequest(userText), attempt, reframed = true,
                                     carry = "", continuations = 0, historyText = historyText)
                        }
                        code == 200 && full.isNotEmpty() -> {
                            flight.brainStreak = 0
                            noteRouteSuccess(BrainFailure.Route.POLLINATIONS)
                            // The output guard screens EVERY reply
                            // before display; a reframed answer is
                            // shown bare, exactly as the model
                            // wrote it.
                            if (!ChatOutputGuard.isClean(full)) {
                                guardRegenerateOrPlain(
                                    chatId, userText, historyText,
                                    BrainFailure.Route.POLLINATIONS, full
                                )
                            } else {
                                deliverChatAnswer(chatId, historyText, full)
                            }
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

    /** One brain route failed (SEAMLESS LAW): fall through
     *  to the next route AT ONCE while one remains — no
     *  pacing gate, no narration; the bubble keeps its
     *  thinking state. When the chain is exhausted, either
     *  end with the ONE plain line (terminal failure) or
     *  hold the flight in its ordinary waiting/thinking
     *  state and retry the whole chain after an escalating
     *  wait. Route names, causes and retry plans live in
     *  the failure classification only — never on screen.
     *  The user's message is never erased and never
     *  terminally failed for a retryable cause. */
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
        BrainLane.shared.chatFinished(System.currentTimeMillis())
        noteRouteFailure(route)
        val next = BrainFailure.pickNext(
            route, brainChain, routeCoolingUntil, System.currentTimeMillis()
        )
        if (next != null) {
            dispatchBrainRoute(chatId, userText, attempt, reframed, historyText, next)
            return
        }
        if (BrainFailure.attemptEndsTerminally(failure)) {
            val shown = BrainFailure.EXHAUSTED_LINE
            replaceCurrentReply(chatId, shown)
            chatStore.appendMessage(chatId, "assistant", shown)
            applyPendingLiveReaction(chatId)
            if (chatId == renderedChatId) renderedMtime = chatStore.lastModified()
            finishFlight(chatId)
            return
        }
        val waitMs = BrainFailure.waitMs(failure, retryAfterSec, flight.brainStreak)
        flight.brainStreak++
        flight.waiting = true
        if (chatId == activeChatId) updateComposerState()
        schedule(flight, waitMs) {
            askBrain(chatId, userText, attempt + 1, reframed, "", 0, historyText)
        }
    }

    /** Route 2 — AI Horde (live-verified keyless
     *  2026-11-26; ROUTES.md).
     *  Raw-completion API: the conversation is flattened
     *  into one prompt, the job is submitted with the
     *  anonymous key, and the status endpoint is polled.
     *  No refusal/continuation machinery — those belong to
     *  the primary route's chat format. */
    private fun tryHorde(chatId: String, userText: String, historyText: String) {
        val flight = flightFor(chatId)
        val gen = flight.generation
        val prompt = buildString {
            append(chatSystemPrompt(chatId)).append("\n\n")
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
        stampBrainCall(BrainFailure.Route.HORDE)
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
                                deliverHordeAnswer(chatId, userText, historyText, answer)
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
     *  path as every answer — BARE. Attribution lives in
     *  traces only (seamless law): no route footer is ever
     *  appended to a chat reply. The output guard screens
     *  it like every other route's reply. */
    private fun deliverHordeAnswer(
        chatId: String,
        userText: String,
        historyText: String,
        rawAnswer: String
    ) {
        val flight = flightFor(chatId)
        flight.brainStreak = 0
        noteRouteSuccess(BrainFailure.Route.HORDE)
        BrainLane.shared.chatFinished(System.currentTimeMillis())
        val answer = rawAnswer.trim()
        if (!ChatOutputGuard.isClean(answer)) {
            guardRegenerateOrPlain(
                chatId, userText, historyText,
                BrainFailure.Route.HORDE, answer
            )
            return
        }
        deliverChatAnswer(chatId, historyText, answer)
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
        // v1.0.4: the LAN route ("your computer") walks this
        // same path — its spec is built on demand, it carries
        // NO key (a local server on the user's own network),
        // and its address/model come from the vault. An
        // unreachable computer simply fails the call and the
        // chain falls through, seamlessly.
        val isLan = route == BrainFailure.Route.LAN
        val spec = if (isLan) {
            OwnKeyRoutes.lanSpec()
        } else {
            OwnKeyRoutes.spec(route)
        }
        val key = spec?.let {
            if (isLan) "" else vault.getSecret(it.vaultKeyName)
        }
        if (spec == null || (!isLan && key.isNullOrEmpty())) {
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
        val customBase = when {
            route == BrainFailure.Route.CUSTOM ->
                vault.getSecret(OwnKeyRoutes.CUSTOM_URL_VAULT_KEY)
            isLan -> vault.getSecret(OwnKeyRoutes.LAN_URL_VAULT_KEY)
            else -> null
        }
        val customModel = when {
            route == BrainFailure.Route.CUSTOM ->
                vault.getSecret(OwnKeyRoutes.CUSTOM_MODEL_VAULT_KEY)
            isLan -> vault.getSecret(OwnKeyRoutes.LAN_MODEL_VAULT_KEY)
            else -> null
        }
        if (isLan && customBase.isNullOrEmpty()) {
            brainRouteFailed(
                chatId, userText, 0, false, historyText,
                route, BrainFailure.Class.NETWORK, null
            )
            return
        }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", chatSystemPrompt(chatId)))
        for (m in flight.history.takeLast(MAX_HISTORY)) messages.put(m)
        messages.put(JSONObject().put("role", "user").put("content", userText))
        val bodyJson = JSONObject()
            .put("model", OwnKeyRoutes.chatModel(spec, customModel))
            .put("messages", messages)
        val requestBuilder = Request.Builder()
            .url(OwnKeyRoutes.chatUrl(spec, customBase))
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
        if (!key.isNullOrEmpty()) {
            requestBuilder.header("Authorization", "Bearer $key")
        }
        val request = requestBuilder.build()
        stampBrainCall(route)
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
                            deliverOwnKeyAnswer(chatId, userText, historyText, answer, route)
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
     *  store/history path as every answer — BARE, like all
     *  routes (attribution lives in traces only), and
     *  screened by the output guard. */
    private fun deliverOwnKeyAnswer(
        chatId: String,
        userText: String,
        historyText: String,
        rawAnswer: String,
        route: BrainFailure.Route
    ) {
        val flight = flightFor(chatId)
        flight.brainStreak = 0
        noteRouteSuccess(route)
        BrainLane.shared.chatFinished(System.currentTimeMillis())
        val answer = rawAnswer.trim()
        if (!ChatOutputGuard.isClean(answer)) {
            guardRegenerateOrPlain(chatId, userText, historyText, route, answer)
            return
        }
        deliverChatAnswer(chatId, historyText, answer)
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
            val created = appendLine("Lille", text)
            liveBubble = created
            attachLiveMenu(chatId, created)
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
        // v1.0.6 (supersedes the v0.12.2 selectable default):
        // bubbles are NOT selectable. On-device proof
        // (2026-11-27): a selectable TextView swallows the
        // long-press into text selection — the user got
        // selection handles and the system Copy/Share bar,
        // and the LilleMenu (reactions, Reply/Copy/Share)
        // never opened. v1.0.7 completes the order: chat
        // text is not selectable AT ALL — no menu item, no
        // code path in this screen enables selection on a
        // bubble (ChatBubbleSelectionTest pins the source).
        // Copy in the menu is how text leaves a bubble.
        bubble.setTextIsSelectable(false)
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
