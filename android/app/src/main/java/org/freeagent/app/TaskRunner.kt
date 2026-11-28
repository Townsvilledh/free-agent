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
 * On-device task runner (v0.4; browser lane v0.9). A small
 * plan-act-observe loop over the brain chain — keyless
 * Pollinations → AI Horde → the user's own saved keys
 * (v0.12.1, OwnKeyRoutes) — checkpointing to the TaskStore
 * after EVERY step, using the same state names as the
 * Python core.
 *
 * Tool scope by task kind:
 * - Note tasks (default): model calls, web_search (keyless
 *   DuckDuckGo Instant Answers), note files in the app sandbox.
 *   Tasks that need CODE EXECUTION are never faked on-device:
 *   they are marked needs_runner and exported as a task spec
 *   JSON for the GitHub Actions runner lane (OFFLINE.md mode C).
 * - General tasks whose text requires the browser (v1.0.3,
 *   TaskTools): the SAME device browser toolset as the
 *   packaged lane, under an allowlist derived from the job's
 *   own words and stamped on the record, plus notes/search.
 *   The X lane tools stay packaged-only (Sentinel refusal).
 *   Classes this phone does not have (mail, repo/code,
 *   video) are declared up front on the record and in the
 *   task's first status — never discovered at the end, and
 *   a finish with them outstanding is labelled partial.
 * - Browser tasks (kind="browser", v0.9): the device browser
 *   lane — BrowserSession's WebView driven through the SAME tool
 *   contract as the host lane (browser_open/read/click/type/
 *   type_secret/upload/screenshot/wait, vault_gen_secret,
 *   vault_record_account, and the X lane x_session_status/
 *   x_read_feed/x_search/x_post), under the same Sentinel rules
 *   (per-task domain allowlist, SECRET-field refusals, secrets
 *   typed only by vault key name from DeviceVault, copy rules on
 *   posts). A task whose record names lane="host" is NOT run
 *   here — it belongs to the host core and is exported like a
 *   runner task rather than faked on the wrong lane.
 *
 * PARKED_AT_WALL (v0.9): when a browser run stops at a human-only
 * wall — the model's finish (or the step budget) arrives while
 * the last browser observation was an OBSERVED CHALLENGE — the
 * task parks with the wall recorded (domain, kind, detail, the
 * ONE named step, a screenshot). Ethuram passes the check in
 * BrowserActivity and hands back; the run then resumes from its
 * checkpoint with a note that the check was completed.
 *
 * Network loss and rate limits are WAITING states, not failures:
 * the task keeps its checkpoint and WorkManager retries it.
 */
class TaskRunner(private val context: Context) {

    companion object {
        private const val TEXT_ENDPOINT = "https://text.pollinations.ai/openai"
        private const val MODEL = "openai"
        private const val MAX_STEPS = 8
        private const val BROWSER_MAX_STEPS = 40

        /** The observation executeMailTool returns when a
         *  send parks for the user's approval — the run loop
         *  stops on it before any further step. Never
         *  model-visible: the loop returns first. */
        const val GMAIL_PARK_OBSERVATION = "PARKED_FOR_APPROVAL"

        // LAN route health cache (v1.0.4): a 60-second
        // verdict per address, so a dead computer costs one
        // short probe a minute — never a full request
        // timeout per step.
        @Volatile private var lanHealthBase: String = ""
        @Volatile private var lanHealthAt: Long = 0L
        @Volatile private var lanHealthOk: Boolean = false

        /** Is the user's computer answering? A short GET on
         *  its /models — the OpenAI-compatible shape's
         *  cheapest liveness probe. Cached briefly; any
         *  failure is just "not now", and the chain walks
         *  on seamlessly. */
        fun lanHealthy(baseUrl: String): Boolean {
            val now = System.currentTimeMillis()
            if (baseUrl == lanHealthBase && now - lanHealthAt < 60_000L) {
                return lanHealthOk
            }
            val ok = try {
                val probe = OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build()
                val base = baseUrl.trimEnd('/')
                val url = if (base.endsWith("/v1")) {
                    "$base/models"
                } else {
                    "$base/v1/models"
                }
                probe.newCall(Request.Builder().url(url).get().build())
                    .execute().use { it.code in 200..499 }
            } catch (e: Exception) {
                false
            }
            lanHealthBase = baseUrl
            lanHealthAt = now
            lanHealthOk = ok
            return ok
        }
        private val SYSTEM_PROMPT =
            "You are Lille, an autonomous agent completing a task on the user's phone by calling tools. " +
            "Reply with exactly ONE fenced JSON block per step, like:\n" +
            "```json\n{\"tool\": \"write_note\", \"args\": {\"path\": \"note.txt\", \"content\": \"...\"}}\n```\n" +
            "Tools: write_note{path,content}, read_note{path}, list_notes{}, web_search{query}, " +
            "memory_read{}, memory_write{block,fact}, memory_search{query}, " +
            "plan_note{facts,subgoals,done,dropped}, finish{summary, outputs}. " +
            "All files are notes inside the app sandbox; use relative paths. " +
            "When fully done, call finish and list produced notes in outputs. Never invent tool results. " +
            SelfFacts.promptLine()
        private const val BROWSER_SYSTEM_PROMPT =
            "You are Lille, an autonomous agent completing a task in your OWN browser on the user's phone by calling tools. " +
            "Reply with exactly ONE fenced JSON block per step, like:\n" +
            "```json\n{\"tool\": \"browser_open\", \"args\": {\"url\": \"https://example.com\"}}\n```\n" +
            "Tools: browser_open{url}, browser_read{}, browser_click{ref or text}, browser_type{ref,text}, " +
            "browser_type_secret{ref,vault_key}, browser_upload{ref,path}, browser_screenshot{name}, " +
            "browser_wait{seconds or text,timeout}, browser_handoff{step}, vault_gen_secret{key}, " +
            "vault_record_account{site,username,vault_key}, mailbox_claim{address}, " +
            "mailbox_switch{reason}, x_session_status{}, x_read_feed{count}, " +
            "x_search{query,count}, x_post{text}, write_note{path,content}, read_note{path}, " +
            "finish{summary}. Work from browser_read's element refs — refs are reassigned on every read, " +
            "so re-read after each navigation. Passwords are NEVER typed by you and NEVER written into any " +
            "file or message: create them with vault_gen_secret (you receive only a key name) and type them " +
            "with browser_type_secret. If any tool result starts with CHALLENGE, the page is a human-only " +
            "wall (CAPTCHA, an emailed code you cannot read, a phone demand): do NOT try to solve, bypass, " +
            "or route around that wall itself — if the task names another legitimate route, take it; " +
            "otherwise call finish naming that exact wall. browser_handoff{step} hands your browser to " +
            "the user for a step the TASK ITSELF names (e.g. signing in with their OWN account): it is " +
            "honoured only on tasks that carry a user step — the task text will say so. Call it, then " +
            "finish, and the task parks until the user hands back. Never use it for work you can do " +
            "yourself, and never ask the user for a password: passwords are typed by the user in the " +
            "parked session, never by you. Never invent tool results."
        private val GENERAL_BROWSER_SYSTEM_PROMPT =
            "You are Lille, an autonomous agent completing a task on the user's phone by calling tools. " +
            "Reply with exactly ONE fenced JSON block per step, like:\n" +
            "```json\n{\"tool\": \"browser_open\", \"args\": {\"url\": \"https://example.com\"}}\n```\n" +
            "Tools: browser_open{url}, browser_read{}, browser_click{ref or text}, browser_type{ref,text}, " +
            "browser_type_secret{ref,vault_key}, browser_upload{ref,path}, browser_screenshot{name}, " +
            "browser_wait{seconds or text,timeout}, browser_handoff{step}, vault_gen_secret{key}, " +
            "vault_record_account{site,username,vault_key}, web_search{query}, write_note{path,content}, " +
            "read_note{path}, list_notes{}, memory_read{}, memory_write{block,fact}, " +
            "memory_search{query}, plan_note{facts,subgoals,done,dropped}, finish{summary}. " +
            "The browser is bound by this task's domain " +
            "allowlist (named in the environment note): browser_open only those domains, and work from " +
            "browser_read's element refs — refs are reassigned on every read, so re-read after each " +
            "navigation. Passwords are NEVER typed by you and NEVER written into any file or message: " +
            "create them with vault_gen_secret (you receive only a key name) and type them with " +
            "browser_type_secret. If any tool result starts with CHALLENGE, the page is a human-only " +
            "wall (CAPTCHA, an emailed code you cannot read, a phone demand): do NOT try to solve, " +
            "bypass, or route around that wall itself — if the task names another legitimate route, " +
            "take it; otherwise call finish naming that exact wall. browser_handoff{step} hands your " +
            "browser to the user for a step the TASK ITSELF names (e.g. signing in with their OWN " +
            "account): it is honoured only on tasks that carry a user step — the task text will say " +
            "so. Never invent tool results. " +
            SelfFacts.promptLine()
        private val CHALLENGE_RE =
            Regex("^CHALLENGE kind=(\\S+) detail=(.*?) — ", RegexOption.DOT_MATCHES_ALL)
        private const val X_POST_MIN_INTERVAL_MS = 90_000L

        /** X lane rate limit (≥90 s between posts), process-wide. */
        @Volatile
        private var lastPostTs = 0L

        /** The v0.4 code-execution predicate — since v1.0.3
         *  the hints live in TaskTools (one home for the
         *  detection the plan and the export gate share). */
        fun looksLikeCodeTask(text: String): Boolean =
            TaskTools.looksLikeCodeTask(text)

        /** True when the task drives the device browser:
         *  a packaged browser record (kind or a packaged
         *  allowlist), or a general record the tool plan
         *  stamped with browser tools (v1.0.3). TaskGate
         *  admission and the in-chat browser panel both key
         *  on this — a general browser task serialises on
         *  the one WebView and shows in the panel exactly
         *  like a packaged one. */
        fun isBrowserTask(task: JSONObject): Boolean =
            task.optString("kind") == "browser" ||
                task.optBoolean(TaskTools.BROWSER_TOOLS_FIELD) ||
                task.optJSONArray("browserDomains")?.let { it.length() > 0 } == true
    }

    private val store = TaskStore(context)
    private val vault = DeviceVault(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun taskDir(id: String): File =
        File(context.filesDir, "tasks/$id").apply { mkdirs() }

    /** Run/resume one task until it completes, waits, pauses,
     *  parks, or fails. Returns the final state. Safe to call
     *  again after process death: the checkpoint (messages + step
     *  count) is restored from the store. */
    fun runTask(id: String): String {
        var task = store.get(id) ?: return TaskStates.FAILED
        // A terminal record never re-enters (v1.0.5): a
        // kill that landed before this pass is final — the
        // run returns the state untouched instead of
        // forcing an illegal transition (or an export)
        // onto a record the user already stopped.
        val entryState = task.optString("state")
        if (TaskStates.isTerminal(entryState)) return entryState
        val text = task.optString("text")
        val browserTask = isBrowserTask(task)
        // TOOL AVAILABILITY (v1.0.3, TaskTools): a general
        // task is PLANNED from its text — which tool classes
        // it requires, which of those this phone lacks, and
        // the browser allowlist its own words authorise.
        // Packaged tasks (kind set) keep their package's
        // toolset and are never planned.
        val packaged = TaskTools.isPackaged(task)
        // v1.0.4: the plan is computed against the phone's
        // LIVE capabilities — Gmail connected or not — so
        // mail is device kit when the connection exists and
        // an up-front declaration when it does not.
        val caps = TaskTools.DeviceCaps(
            gmailConnected = GmailConnection.isConnected(context)
        )
        val plan = if (packaged) null else TaskTools.planFor(text, caps)
        val browserToolsActive = browserTask ||
            (plan?.requiresBrowser == true)
        // RECIPES (v1.0.4): a recipe record carries its
        // package as JSON; its tool allowlist is its
        // contract and its success check is its finish gate.
        val recipe = if (task.optString("kind") == "recipe") {
            try {
                JSONObject(task.optString("recipeJson"))
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        if (task.optString("lane") == "host") {
            // Lane discipline: a host-lane task belongs to the
            // host core's browser. Export it like a runner task —
            // never fake it on the device lane.
            exportSpec(task, "lane=host — runs on the host core's " +
                "browser lane, not on the phone. Exported, not faked.")
            store.update(id, TaskStates.NEEDS_RUNNER) {
                it.put("note", "Lane is 'host' — this task runs on the host " +
                    "core, not the device lane. Exported as a task spec.")
            }
            return TaskStates.NEEDS_RUNNER
        }

        if (!browserToolsActive && TaskTools.looksLikeCodeTask(text)) {
            // The v0.4 export gate, narrowed by exactly one
            // case (v1.0.3): a MIXED job — code work AND
            // browser work — no longer exports whole. It
            // runs here with the browser tools, its plan
            // declaring the code class as computer-lane and
            // its finish labelled partial. A job whose code
            // is the whole job exports exactly as before.
            exportSpec(task)
            store.update(id, TaskStates.NEEDS_RUNNER) {
                it.put("note", "Needs code execution — exported as a task spec for the " +
                    "GitHub Actions runner lane. Not faked on-device.")
            }
            return TaskStates.NEEDS_RUNNER
        }

        // Same guard at the run's own door (v1.0.5): a
        // kill that landed during this pass's setup is
        // honoured here, not overridden by the RUNNING
        // transition below.
        val doorState = store.get(id)?.optString("state")
            ?: return TaskStates.FAILED
        if (TaskStates.isTerminal(doorState)) return doorState
        store.update(id, TaskStates.RUNNING) {
            // The plan is stamped at run start (creation
            // stamps it too, for the chat's first status):
            // requires/unavailable classes + the derived
            // browser allowlist are record evidence from
            // here on. A new run also supersedes the last
            // budget rest's flag.
            if (plan != null) {
                TaskTools.stampPlan(it, plan)
                // PLAN/ACT (v1.0.4): a consequential general
                // task starts in PLAN mode — mutating tools
                // absent — until the user approves the plan.
                // A recipe is exempt: the recipe file IS the
                // user's pre-approved instruction.
                if (recipe == null) PlanAct.stampInitialMode(it, text)
            }
            it.remove(StepBudget.REST_FLAG)
            EventLog.append(it, "run_start", System.currentTimeMillis())
        }
        // Re-read: the update above may have stamped the
        // plan/mode onto the record — the run's decisions
        // (plan mode above all) read the record's truth,
        // not the pre-update snapshot.
        task = store.get(id) ?: task
        // ROUTE RECOVERY RESUMES TO FINISH (v1.0.14): a
        // pending finish left by an interrupted run — her
        // own completion statement over verified work
        // products — closes the record here, before any
        // model call is spent re-deciding it.
        if (applyPendingFinish(id)) return TaskStates.COMPLETED
        task = store.get(id) ?: task
        // LANE ROUTING (v1.0.4): the classes this phone can
        // never carry (repo/code, video) are ROUTED, not
        // dropped — the spec export is written once, and
        // its receipt lives on the record where the status
        // and the graded report read it. No general task
        // ends in a terminal "cannot execute" for a
        // routable class again (the Full-Kit law).
        if (plan != null && plan.routed.isNotEmpty() &&
            !task.optBoolean("laneRouted")
        ) {
            exportSpec(
                task,
                "classes routed to the computer lane: " +
                    TaskTools.labelsFor(plan.routed)
            )
            store.update(id) {
                it.put("laneRouted", true)
                it.put("laneReceipt", "specs/$id.json")
                it.put(
                    "laneRoutedClasses",
                    TaskTools.labelsFor(plan.routed)
                )
                EventLog.append(
                    it, "lane_routed", System.currentTimeMillis(),
                    JSONObject().put(
                        "classes", TaskTools.labelsFor(plan.routed)
                    )
                )
            }
        }
        var messages = task.optJSONArray("messages") ?: JSONArray()
        // PLAN MODE (v1.0.4): a consequential general task
        // that has not been approved yet runs on the PLAN
        // registry — read-only tools + propose_plan; the
        // acting tools do not exist in this run. Read from a
        // fresh record: the run-start update may have just
        // stamped the mode onto it.
        val planMode = plan != null &&
            PlanAct.isPlanMode(store.get(id) ?: task)
        if (messages.length() == 0) {
            messages.put(JSONObject().put("role", "system")
                .put("content", when {
                    planMode -> PlanAct.PLAN_SYSTEM_PROMPT
                    packaged -> BROWSER_SYSTEM_PROMPT
                    browserToolsActive -> GENERAL_BROWSER_SYSTEM_PROMPT
                    else -> SYSTEM_PROMPT
                }))
            messages.put(JSONObject().put("role", "user").put("content", text))
        }
        // PLAN APPROVED (v1.0.4): the user gave the word in
        // the chat — the run that parked on the PLAN prompt
        // resumes on the full registry. The swap happens
        // once, on the record's flag, and the model is told
        // plainly that the acting tools now exist.
        if (plan != null && !planMode &&
            task.optBoolean("planApproved") &&
            !task.optBoolean("planPromptSwapped") &&
            messages.length() > 0
        ) {
            messages.put(
                0,
                JSONObject().put("role", "system").put(
                    "content",
                    if (browserToolsActive) {
                        GENERAL_BROWSER_SYSTEM_PROMPT
                    } else {
                        SYSTEM_PROMPT
                    }
                )
            )
            messages.put(JSONObject().put("role", "user").put(
                "content",
                "The user approved your plan in the chat. The acting " +
                    "tools are now available (the full tool list in " +
                    "your instructions applies, including the tools " +
                    "this job's environment note names). Carry out " +
                    "the plan on the record."
            ))
            store.update(id) {
                it.put("planPromptSwapped", true)
                it.put("messages", messages)
            }
        }
        // GMAIL SEND APPROVED (v1.0.4): a send parked for
        // the user's word; the word landed — tell her once
        // to make the call again (the gate now opens).
        if (task.optBoolean("gmailSendApproved") &&
            task.has("pendingGmailSend") &&
            !task.optBoolean("gmailSendResumeNoted") &&
            messages.length() > 0
        ) {
            val pending = task.optJSONObject("pendingGmailSend")
            messages.put(JSONObject().put("role", "user").put(
                "content",
                "The user approved the email send in the chat. Call " +
                    "gmail_send now with to=${pending?.optString("to")}, " +
                    "subject=${pending?.optString("subject")} and the " +
                    "same body you prepared."
            ))
            store.update(id) {
                it.put("gmailSendResumeNoted", true)
                it.put("messages", messages)
            }
        }
        var stepsDone = task.optInt("steps", 0)
        // v1.0.14 finish-landing trackers: whether this run
        // has landed real tool work (the pending-finish
        // precondition), and whether the grounding bounce
        // has already been spent in this run.
        var sawToolWork = false
        var groundingBounced = false
        val maxSteps = if (browserToolsActive) BROWSER_MAX_STEPS else MAX_STEPS
        // The step budget is spent in WINDOWS (v1.0.3,
        // StepBudget): stepsInWindow persists across runs
        // of the same window; a budget rest zeroes it.
        var windowSteps = StepBudget.windowFor(task, maxSteps)
        // PARKED_AT_WALL resume: the human step was taken in her
        // session (BrowserActivity handback). Tell her once, from
        // the record — the wall fields are evidence, not memory.
        val wall = task.optJSONObject("wall")
        val handedBackAt = task.optLong("handedBackAt", 0L)
        if (browserToolsActive && wall != null && handedBackAt > task.optLong("wallAt", 0L) &&
            task.optLong("wallResumeNoted", 0L) < handedBackAt
        ) {
            // HANDBACK VERIFICATION (v1.0.8): the lane's FIRST
            // act on a handback is verify-by-state — what
            // actually changed while the browser was his:
            // signed in? as whom? check passed? The read is
            // the page's own account UI (no navigation — the
            // parked page is the evidence), stamped on the
            // record, announced in the origin chat from the
            // record, and carried into her resume note. On
            // 2026-11-27 he signed in himself, handed back,
            // said "already logged in" — and she answered
            // from the brain that she couldn't tell. Never
            // again: the handback IS the verification.
            var verifiedHandle: String? = null
            var identityRead = false
            var unreadableReason: String? = null
            if (packaged && isXDomainTask(task)) {
                // THE READ RUNS TO A DEFINITE RESULT
                // (v1.0.9): 1.0.8 read only when the
                // browser happened to rest on x.com — a
                // handback from any other page left the
                // verification unstamped forever, and its
                // xHandle() read stamped signed OUT for a
                // handle hidden behind the account menu.
                // Now the lane positions on x.com/home
                // when it must and runs the concluding
                // read: signed-in-as, signed-out, or an
                // explicit unreadable with the reason.
                val currentDomain = BrowserPerception.domainOf(
                    BrowserSession.currentUrl()
                )
                if (currentDomain != "x.com" &&
                    !currentDomain.endsWith(".x.com")
                ) {
                    try {
                        xOpen(
                            "https://x.com/home",
                            browserDomains(task)
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                val outcome = try {
                    readXIdentityHere(browserDomains(task))
                } catch (e: Exception) {
                    IdentityOutcome.Unreadable(
                        "the sign-in check itself failed"
                    )
                }
                when (outcome) {
                    is IdentityOutcome.SignedIn -> {
                        verifiedHandle = outcome.handle
                        identityRead = true
                    }
                    is IdentityOutcome.SignedOut -> {
                        identityRead = true
                    }
                    is IdentityOutcome.Unreadable -> {
                        unreadableReason = outcome.reason
                    }
                }
            }
            val verifiedBit = when {
                identityRead && verifiedHandle != null ->
                    " Verified from the page itself: the browser " +
                        "is signed in as @$verifiedHandle."
                identityRead ->
                    " Verified from the page itself: the browser " +
                        "is still signed out."
                unreadableReason != null ->
                    " The sign-in check couldn't read the " +
                        "page just now ($unreadableReason) — " +
                        "don't assume either way; the check " +
                        "retries on its own."
                else -> ""
            }
            // A planned user handoff (v0.10.1) resumes with its
            // own note: the user acted themselves in her session
            // — she verifies from the page, never from assumption.
            val resumeContent =
                if (wall.optString("kind") == UserHandoff.WALL_KIND) {
                    UserHandoff.resumeNote(wall.optString("domain")) +
                        verifiedBit
                } else {
                    "Ethuram has completed the human check in your browser session " +
                        "(wall was: ${wall.optString("kind")} on " +
                        "${wall.optString("domain")}). Continue the task now: " +
                        "browser_read the page and carry on from where you stopped." +
                        verifiedBit
                }
            messages.put(JSONObject().put("role", "user").put("content", resumeContent))
            val handbackNow = System.currentTimeMillis()
            store.update(id) {
                it.put("wallResumeNoted", handedBackAt)
                it.remove("wall")
                it.remove("handoffStep")
                if (identityRead) {
                    stampIdentityOn(it, verifiedHandle, handbackNow)
                    it.put(
                        SessionIdentity.HANDBACK_VERIFIED_FIELD,
                        handedBackAt
                    )
                }
                val unreadable = unreadableReason
                if (unreadable != null) {
                    // No verification stamp: the handback
                    // stays pending, the unreadable mark is
                    // the record's latest word, and the
                    // re-check machinery (chat questions,
                    // the watchdog) owes the read a retry.
                    SessionIdentity.stampUnreadable(
                        it, unreadable, handbackNow
                    )
                }
            }
            if (!identityRead && unreadableReason != null) {
                // The unreadable handback still speaks —
                // deterministically, once per handback —
                // so silence never reads as a conclusion.
                val fresh = store.get(id) ?: task
                if (ChatTaskLink.isChatTask(fresh) &&
                    fresh.optLong(
                        SessionIdentity.HANDBACK_ANNOUNCED_FIELD, 0L
                    ) < handedBackAt
                ) {
                    val origin = fresh.optString(ChatTaskLink.ORIGIN_FIELD)
                    if (origin.isNotEmpty()) {
                        ChatStore(context).appendMessage(
                            origin, "lille",
                            SessionIdentity.handbackUnreadableLine(
                                fresh, unreadableReason
                            )
                        )
                        store.update(id) {
                            it.put(
                                SessionIdentity.HANDBACK_ANNOUNCED_FIELD,
                                handedBackAt
                            )
                        }
                    }
                }
            }
            if (identityRead) {
                val fresh = store.get(id) ?: task
                syncAccountRegistry(fresh, verifiedHandle)
                // The announcement: deterministic, from the
                // record, in the originating chat, without
                // being asked — once per handback.
                if (ChatTaskLink.isChatTask(fresh) &&
                    fresh.optLong(
                        SessionIdentity.HANDBACK_ANNOUNCED_FIELD, 0L
                    ) < handedBackAt
                ) {
                    val origin = fresh.optString(ChatTaskLink.ORIGIN_FIELD)
                    if (origin.isNotEmpty()) {
                        ChatStore(context).appendMessage(
                            origin, "lille",
                            SessionIdentity.handbackAnnouncement(
                                fresh, verifiedHandle,
                                // The check reads as still
                                // standing only when the wall
                                // was a challenge AND the page
                                // came back signed out — a
                                // verified sign-in outranks it.
                                challengePresent =
                                    wall.optString("kind") !=
                                        UserHandoff.WALL_KIND &&
                                        verifiedHandle == null
                            )
                        )
                        store.update(id) {
                            it.put(
                                SessionIdentity.HANDBACK_ANNOUNCED_FIELD,
                                handedBackAt
                            )
                        }
                    }
                }
            }
            checkpoint(id, messages, stepsDone, windowSteps)
        }
        // PACKAGED X LANES (v1.0.8): the run-start identity
        // stamp + account separation + the creation lane's
        // phase script — before her first step, every run.
        if (packaged && browserToolsActive && isXDomainTask(task)) {
            startPackagedLane(id, messages)
        }
        // TOOL AVAILABILITY, model-facing (v1.0.3): a
        // general task hears its real toolset — and the
        // classes this phone does NOT have — as a recorded
        // environment note before its first step, exactly
        // once. The user-facing copy of the same facts is
        // the plan stamp on the record (the chat's status
        // renders it); this copy is so the run plans around
        // them from step one instead of discovering them
        // in its final report.
        if (plan != null && !task.optBoolean("envNotePosted")) {
            messages.put(JSONObject().put("role", "user")
                .put("content", TaskTools.environmentNote(plan)))
            // OWN-PRODUCT TARGET (v1.0.13): a job about her
            // own product carries its default repository on
            // the record (stamped at creation) — the run
            // hears it as a fact, so no step of the work
            // ever needs to ask the user for it.
            val targetRepo = task.optString(TaskTools.TARGET_REPO_FIELD)
            if (targetRepo.isNotEmpty()) {
                messages.put(JSONObject().put("role", "user").put(
                    "content",
                    "This job targets your own repository, " +
                        targetRepo + ". Its files and layout " +
                        "are yours to work from — never ask " +
                        "the user for the repository, its " +
                        "files, or any credential."
                ))
            }
            if (plan.requiresCalendar) {
                // Calendar tools take epoch times — give her
                // the anchor instead of letting her guess it.
                val nowMs = System.currentTimeMillis()
                messages.put(JSONObject().put("role", "user").put(
                    "content",
                    "The current time is " +
                        "${CalendarFormat.formatInstant(nowMs)} " +
                        "(epoch ms $nowMs). Compute calendar times from it."
                ))
            }
            store.update(id) { it.put("envNotePosted", true) }
            checkpoint(id, messages, stepsDone, windowSteps)
        }
        // MEMORY + SKILLS + SITE CARDS (v1.0.4): her durable
        // context, posted ONCE per task at its start. The
        // memory core rides as a FROZEN SNAPSHOT (stamped on
        // the record — resumes and later runs of this task
        // read the same snapshot, never a moving target);
        // skills she wrote from earlier runs and the cards
        // for this job's sites join it. A guide, not gospel:
        // memory can be wrong and the live state in front of
        // her cannot.
        if (!task.optBoolean("contextPosted") && messages.length() > 0) {
            val stateStore = AgentStateStore(context)
            val parts = mutableListOf<String>()
            val frozen = task.optString("memorySnapshot")
            val snapshot = if (frozen.isNotEmpty()) {
                frozen
            } else {
                val snap = AgentMemory(
                    stateStore.io(AgentStateStore.SECTION_MEMORY)
                ).snapshot()
                store.update(id) { it.put("memorySnapshot", snap) }
                snap
            }
            if (snapshot.isNotBlank()) {
                parts.add(
                    "What you remember (frozen snapshot from your " +
                        "memory at this task's start):\n$snapshot"
                )
            }
            val skillStore = SkillStore(
                stateStore.io(AgentStateStore.SECTION_SKILLS)
            )
            for (skill in skillStore.match(text)) {
                parts.add(skillStore.render(skill))
            }
            if (plan != null && plan.browserDomains.isNotEmpty()) {
                val cards = SiteCards(
                    stateStore.io(AgentStateStore.SECTION_CARDS)
                ).renderFor(plan.browserDomains)
                if (cards.isNotBlank()) parts.add(cards)
            }
            if (parts.isNotEmpty()) {
                messages.put(JSONObject().put("role", "user").put(
                    "content",
                    "Context from your memory and earlier runs — a " +
                        "guide, not gospel; the live state in front " +
                        "of you wins any disagreement:\n\n" +
                        parts.joinToString("\n\n")
                ))
            }
            store.update(id) { it.put("contextPosted", true) }
            checkpoint(id, messages, stepsDone, windowSteps)
        }
        // Window outcome counters for the progress-aware
        // budget: graded from THIS run's observations (the
        // window's step count itself persists on the
        // record; a resumed window re-earns its verdict).
        var windowOk = 0
        var windowFailed = 0
        var consecErrors = 0
        // OWN KEYS LEAD + NO SLEEPING WHILE A ROUTE LIVES
        // (v1.0.2): callModel walks the user's own-key routes
        // first, and ALL pacing/cooldown discipline is PER
        // ROUTE inside callModel — a route's floor or rest
        // never throttles another route, and the task's rest
        // after an all-routes failure is the shortest
        // per-route cooldown (BrainPacing.restMs), never an
        // escalating task-level backoff.
        var lastChallenge: BrowserPerception.Challenge? = null
        var pendingHandoffStep: String? = null

        // One stall mechanism for the whole run (v1.0.4):
        // reflections feed it, its verdicts steer or park.
        val stallTracker = StallTracker()
        while (windowSteps < maxSteps) {
            // Honour pause/cancel that landed while we were working.
            val fresh = store.get(id) ?: return TaskStates.FAILED
            when (fresh.optString("state")) {
                TaskStates.CANCELLED -> return TaskStates.CANCELLED
                TaskStates.PAUSED -> return TaskStates.PAUSED
            }
            // STEERS (v1.0.4, interruption-as-event): a user
            // message that steered this task from its chat is
            // an EVENT on the record — applied here, at the
            // step boundary, never by killing the run. The
            // work so far stands; the steer joins the
            // conversation and the loop continues.
            val steers = fresh.optJSONArray("pendingSteers")
            if (steers != null && steers.length() > 0) {
                for (i in 0 until steers.length()) {
                    val steer = steers.optString(i)
                    if (steer.isBlank()) continue
                    messages.put(JSONObject().put("role", "user").put(
                        "content",
                        "The user is steering this work: $steer " +
                            "Apply it from this step on; everything " +
                            "done so far stands."
                    ))
                    store.update(id) {
                        EventLog.append(
                            it, "steer",
                            System.currentTimeMillis(),
                            JSONObject().put("text", steer.take(120))
                        )
                    }
                }
                store.update(id) {
                    it.put("pendingSteers", JSONArray())
                    it.put("messages", messages)
                }
            }
            // CONDENSER (v1.0.4): past the bound, old tool
            // exchanges fold into ONE tombstone message that
            // says what it folded — never silent truncation.
            if (messages.length() > 30) {
                val (condensed, folded) =
                    EventLog.condenseMessages(messages, 12)
                if (folded > 0) {
                    messages = condensed
                    store.update(id) {
                        it.put("messages", messages)
                        EventLog.append(
                            it, "condensed",
                            System.currentTimeMillis(),
                            JSONObject().put("folded", folded)
                        )
                    }
                }
            }

            // Brain-call pacing lives inside callModel now
            // (v1.0.2): per route, from per-route timestamps
            // in the record. There is deliberately NO task-
            // level gate here — a step the lead route can
            // serve proceeds at once, whatever another
            // route's floor says. lastBrainCallAt stays as
            // evidence (the last call, any route).
            store.update(id) {
                it.put("lastBrainCallAt", System.currentTimeMillis())
            }

            // Brain lane (v1.0.1 multitasking): chat has
            // priority and only one task brain call flies at
            // a time. Poll in-worker for a bounded stretch —
            // most contention is seconds — and only then
            // take the ordinary waiting state (the run
            // releases its TaskGate slot; the retry resumes
            // from this checkpoint).
            var laneWaitedMs = 0L
            while (true) {
                val laneWait = BrainLane.shared.taskCallWaitMs(
                    id, System.currentTimeMillis()
                )
                if (laneWait <= 0L) break
                if (laneWaitedMs >= 120_000L) {
                    return wait(id, TaskStates.WAITING_ROUTE,
                        BrainPacing.pacingNote(60_000L), 60_000L)
                }
                Thread.sleep(minOf(laneWait, BrainLane.POLL_MS))
                laneWaitedMs += minOf(laneWait, BrainLane.POLL_MS)
                val recheck = store.get(id) ?: return TaskStates.FAILED
                if (recheck.optString("state") == TaskStates.CANCELLED) {
                    return TaskStates.CANCELLED
                }
                if (recheck.optString("state") == TaskStates.PAUSED) {
                    return TaskStates.PAUSED
                }
            }

            val reply: JSONObject
            BrainLane.shared.taskStarted(id)
            try {
                reply = callModel(id, messages)
            } catch (e: IOException) {
                return wait(id, TaskStates.WAITING_NETWORK,
                    "Network unreachable — task is waiting, not failed.", 60_000L)
            } finally {
                BrainLane.shared.taskFinished(id)
            }
            val httpCode = reply.optInt("http", 200)
            if (httpCode == 402 || httpCode == 429) {
                // NO SLEEPING WHILE A ROUTE LIVES (v1.0.2):
                // the rest is the SHORTEST per-route cooldown
                // among configured routes, computed by
                // callModel from the per-route state it just
                // updated (BrainPacing.restMs — capped at
                // ~60 s, never escalating at task level; the
                // escalation lives on each route's own streak
                // inside callModel). A rest of 0 means a route
                // is available NOW — loop straight into the
                // fresh attempt instead of waiting at all.
                val waitMs = if (reply.has("restMs")) {
                    reply.optLong("restMs")
                        .coerceIn(0L, BrainPacing.MAX_REST_MS)
                } else {
                    BrainPacing.MAX_REST_MS
                }
                if (waitMs <= 0L) continue
                return wait(id, TaskStates.WAITING_ROUTE,
                    BrainPacing.rateLimitNote(waitMs), waitMs)
            }
            if (httpCode != 200) {
                // Seamless law: the user-visible note is
                // plain — no route names, no HTTP mechanics.
                // The serving facts (route + code) are kept
                // as EVIDENCE fields on the record, where
                // traces and graded reports read them.
                store.update(id, TaskStates.FAILED) {
                    it.put(
                        "note",
                        "Stopped at a step that could not be completed."
                    )
                    it.put("lastBrainRoute", reply.optString("route", ""))
                    it.put("lastBrainHttp", httpCode)
                }
                return TaskStates.FAILED
            }

            val content = reply.optString("content", "")
            messages.put(JSONObject().put("role", "assistant").put("content", content))
            val call = parseToolCall(content)
            stepsDone += 1
            windowSteps += 1
            if (call == null) {
                // PSEUDO-TOOL-CALL TEXT (v1.0.15, class 3
                // mirror): the reply WRITES a tool call
                // instead of making one — nothing was
                // executed. It is never stamped as a
                // pending finish (it states no result) and
                // the steer names the real format, once per
                // reply; the window budget ends a run that
                // keeps narrating.
                val pseudo = FinishChecks.pseudoCallName(
                    content, TaskTools.ALL_TOOL_NAMES)
                if (pseudo != null) {
                    windowFailed += 1
                    messages.put(JSONObject().put("role", "user").put(
                        "content",
                        "You wrote $pseudo(...) as TEXT. Text " +
                            "that looks like a tool call does " +
                            "nothing — nothing was executed. " +
                            "Emit the call for real: exactly " +
                            "one fenced JSON tool block. Do " +
                            "not narrate actions; execute them."))
                    checkpoint(id, messages, stepsDone, windowSteps)
                    continue
                }
                // PENDING FINISH (v1.0.14): a plain-text
                // reply after real tool work is her
                // completion statement in words. Record it
                // on the record — her summary, the outputs
                // this task produced, the step it was
                // stated at — so the finish can land as a
                // state write if the route drops or a later
                // run resumes the record. (A reply before
                // any tool work states nothing about work
                // and is never recorded.)
                if (content.isNotBlank() &&
                    (sawToolWork || stepsDone > 1)
                ) {
                    val producedNow = PendingFinish.produced(
                        store.get(id) ?: task
                    )
                    store.update(id) {
                        PendingFinish.stamp(
                            it, content, producedNow, stepsDone
                        )
                        it.put("messages", messages)
                    }
                }
                // A reply with no tool call spends budget
                // but is not progress (the budget verdict
                // counts it as a failed step, though not as
                // a tool error streak).
                windowFailed += 1
                messages.put(JSONObject().put("role", "user").put("content",
                    "Reply with exactly one fenced JSON tool block, or finish."))
                checkpoint(id, messages, stepsDone, windowSteps)
                continue
            }
            val (name, args) = call
            if (name == "finish") {
                if (planMode) {
                    // PLAN MODE (v1.0.4): there is no finish
                    // here — the run's product is a plan for
                    // the user to approve.
                    messages.put(JSONObject().put("role", "user").put(
                        "content",
                        "You are in PLAN MODE — finish does not exist " +
                            "here. Call propose_plan{plan,subgoals} " +
                            "with the plan you have formed; the user " +
                            "approves it in the chat."
                    ))
                    checkpoint(id, messages, stepsDone, windowSteps)
                    continue
                }
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
                    checkpoint(id, messages, stepsDone, windowSteps)
                    continue
                }
                if (browserToolsActive && lastChallenge != null) {
                    // She stopped AT an observed wall: park, do
                    // not complete (the wall is Ethuram's step).
                    return park(id, lastChallenge, messages, stepsDone)
                }
                if (browserToolsActive && pendingHandoffStep != null) {
                    // She asked to hand the browser to the user
                    // for the task's own named step (v0.10.1):
                    // park as a user step, not a wall.
                    return park(
                        id,
                        BrowserPerception.Challenge(
                            UserHandoff.WALL_KIND, pendingHandoffStep!!
                        ),
                        messages, stepsDone,
                        stepOverride = pendingHandoffStep
                    )
                }
                // FINISH GATE (v1.0.8): a packaged lane with
                // state-verifiable criteria may finish ONLY
                // when they hold. On 2026-11-27 the X
                // creation lane finished from the mailbox
                // phase — three steps, no account — because
                // the generic browser probe below accepts
                // "resting on an allowed page" as proof.
                // The gate judges the LANE's criteria from
                // the record: a live session as her handle
                // and her profile page seen as hers. Unmet →
                // the finish is refused, the record goes to
                // WAITING with the unmet criterion named in
                // work terms, and the work continues.
                val gateRecord = store.get(id) ?: task
                if (FinishGate.isGated(gateRecord) &&
                    !FinishGate.holds(gateRecord)
                ) {
                    messages.put(JSONObject().put("role", "user").put(
                        "content", FinishGate.refusalNote(gateRecord)
                    ))
                    store.update(id, TaskStates.WAITING_ROUTE) {
                        it.put("note", FinishGate.waitNote(gateRecord))
                        it.put(
                            "nextRetryAt",
                            System.currentTimeMillis() + 60_000L
                        )
                        it.put("messages", messages)
                        it.put("steps", stepsDone)
                        it.put(
                            FinishGate.DISCREPANCY_FIELD,
                            FinishGate.discrepancyFor(gateRecord)
                        )
                        EventLog.append(
                            it, "finish_gate",
                            System.currentTimeMillis(),
                            JSONObject().put(
                                "unmet",
                                FinishGate.unmet(gateRecord)
                                    .joinToString(",") { c -> c.name }
                            )
                        )
                    }
                    return TaskStates.WAITING_ROUTE
                }
                // VERIFY-STATE + FINAL-ANSWER CHECKS
                // (v1.0.4, steals #2 + #8): a finish is a
                // CLAIM until the claimed end state is
                // re-read. The probe re-reads it (note /
                // browser page / calendar), the deterministic
                // checks judge the record, and a failing
                // finish bounces back to work ONCE; a second
                // failure finishes honestly with the check
                // named on the record.
                val summaryText = args.optString("summary", "Done.")
                // VERIFY-BY-STATE (v1.0.14, battery fix 2):
                // a claimed output must be this task's own
                // work — in its produced set (stamped by
                // the writers) or evidenced by a recorded
                // write observation. The code-b shape — a
                // completion claim over a file no step ever
                // wrote — is refused here, before any
                // probe runs.
                val finishObservations =
                    FinishChecks.observationsOf(messages)
                val unproduced = FinishChecks.unproducedClaims(
                    claimedOutputs(outputs),
                    PendingFinish.produced(store.get(id) ?: task),
                    finishObservations
                )
                if (unproduced.isNotEmpty()) {
                    messages.put(JSONObject().put("role", "user").put(
                        "content",
                        "finish rejected: claimed outputs were " +
                            "not produced by any step of this " +
                            "task: $unproduced. Work a step of " +
                            "this task did not do cannot be " +
                            "claimed as its product — write the " +
                            "note with a tool, then finish."))
                    checkpoint(id, messages, stepsDone, windowSteps)
                    continue
                }
                // EVIDENCE-PROPORTIONATE FINISH (v1.0.15,
                // class 2 mirror): a finish must rest on
                // steps proportionate to the task's own
                // evidence demand — a plan requiring tool
                // classes, or files in the task sandbox the
                // task's words name. Zero tool observations
                // under such a demand, a demanded file no
                // step read, or a summary value that traces
                // to no evidence: the finish bounces back
                // to work (the summary-b / browser-a shapes
                // from battery round 2).
                val demandedFiles = FinishChecks.demandedFiles(
                    text,
                    File(taskDir(id)).listFiles()
                        ?.filter { it.isFile }
                        ?.map { it.name } ?: emptyList()
                )
                val planDemandsTools =
                    plan?.requires?.isNotEmpty() == true
                if ((planDemandsTools || demandedFiles.isNotEmpty()) &&
                    finishObservations.isEmpty()
                ) {
                    messages.put(JSONObject().put("role", "user").put(
                        "content",
                        "finish rejected: this task requires " +
                            "evidence first — its words name " +
                            "work tools must do (reading, " +
                            "fetching, acting), but no tool has " +
                            "run in this task yet. Do the work " +
                            "with tools, read what they show, " +
                            "then finish."))
                    checkpoint(id, messages, stepsDone, windowSteps)
                    continue
                }
                if (demandedFiles.isNotEmpty()) {
                    val dir = File(taskDir(id))
                    val unreadFiles = mutableListOf<String>()
                    val readContents = mutableListOf<String>()
                    for (rel in demandedFiles) {
                        val content = try {
                            File(dir, rel).readText()
                                .take(65_536)
                        } catch (e: Exception) {
                            ""
                        }
                        if (FinishChecks.readEvidenced(
                                rel, content, finishObservations)
                        ) {
                            readContents.add(content)
                        } else {
                            unreadFiles.add(rel)
                        }
                    }
                    if (unreadFiles.isNotEmpty()) {
                        messages.put(JSONObject().put(
                            "role", "user").put(
                            "content",
                            "finish rejected: your summary " +
                                "speaks about $unreadFiles, " +
                                "but no step of this task read " +
                                "them — claims about a file's " +
                                "contents must trace to a step " +
                                "that read the file. Read them " +
                                "with a tool, then finish from " +
                                "what they actually say."))
                        checkpoint(id, messages, stepsDone, windowSteps)
                        continue
                    }
                    val badValues =
                        FinishChecks.ungroundedContentValues(
                            summaryText, finishObservations,
                            text, readContents)
                    if (badValues.isNotEmpty()) {
                        messages.put(JSONObject().put(
                            "role", "user").put(
                            "content",
                            "finish rejected: your summary " +
                                "states value(s) $badValues " +
                                "that appear in no tool result " +
                                "of this task, in the task " +
                                "text, or in a file this task " +
                                "read. Report only values your " +
                                "evidence shows."))
                        checkpoint(id, messages, stepsDone, windowSteps)
                        continue
                    }
                }
                // TOOL OUTPUT OUTRANKS NARRATION (v1.0.14,
                // battery fix 6): a summary date that the
                // record's own tool observations contradict
                // bounces ONCE with the evidence named.
                val contra = FinishChecks.dateContradiction(
                    summaryText, finishObservations
                )
                if (contra != null && !groundingBounced) {
                    groundingBounced = true
                    messages.put(JSONObject().put("role", "user").put(
                        "content",
                        "finish rejected: your summary's date " +
                            "contradicts what your own tool " +
                            "results in this task show (" +
                            contra.joinToString(", ") +
                            "). Report the value your tools " +
                            "showed, exactly."))
                    checkpoint(id, messages, stepsDone, windowSteps)
                    continue
                }
                var recipeFailed = false
                val verify = if (recipe != null) {
                    val passed = Recipes.checkPasses(
                        recipe, recipeProbeText(recipe, id)
                    )
                    if (!passed) {
                        // Fresh read: the local snapshot does
                        // not see this run's own retry stamps.
                        val retries = (store.get(id) ?: task)
                            .optInt("recipeRetries", 0)
                        val maxRetries =
                            recipe.optInt("maxRetries", 0)
                        if (retries < maxRetries) {
                            // Bounded retry: a fresh attempt
                            // at the same recipe, told why.
                            val freshMessages = JSONArray()
                            freshMessages.put(JSONObject()
                                .put("role", "system")
                                .put(
                                    "content",
                                    if (browserToolsActive) {
                                        GENERAL_BROWSER_SYSTEM_PROMPT
                                    } else {
                                        SYSTEM_PROMPT
                                    }
                                ))
                            freshMessages.put(JSONObject()
                                .put("role", "user").put("content", text))
                            freshMessages.put(JSONObject()
                                .put("role", "user").put(
                                    "content",
                                    "Attempt ${retries + 2}: the recipe's " +
                                        "success check failed on the last " +
                                        "attempt (" +
                                        describeRecipeCheck(recipe) +
                                        "). Do the work so the check " +
                                        "passes this time."
                                ))
                            store.update(id) {
                                it.put("recipeRetries", retries + 1)
                                it.put("messages", freshMessages)
                                it.put("steps", stepsDone)
                            }
                            messages = freshMessages
                            windowSteps = 0
                            continue
                        }
                        recipeFailed = true
                    }
                    FinishVerifier.externalVerdict(
                        passed,
                        if (passed) {
                            "the recipe's success check passed " +
                                "(${describeRecipeCheck(recipe)})"
                        } else {
                            "the recipe's success check did NOT pass " +
                                "(${describeRecipeCheck(recipe)})"
                        }
                    )
                } else {
                    runFinishProbe(id, plan, browserToolsActive)
                }
                val freshRecord = store.get(id) ?: task
                val failures = FinalChecks.evaluate(
                    freshRecord, plan, summaryText, verify
                ).toMutableList()
                if (recipeFailed) {
                    failures.add(
                        FinalChecks.Failure(
                            "recipe_success_check", verify.detail
                        )
                    )
                }
                if (failures.isNotEmpty() &&
                    freshRecord.optInt(TaskPlanning.BOUNCES_FIELD, 0) == 0
                ) {
                    messages.put(JSONObject().put("role", "user").put(
                        "content", FinalChecks.bounceNote(failures)
                    ))
                    store.update(id) {
                        it.put(TaskPlanning.BOUNCES_FIELD, 1)
                        it.put("messages", messages)
                        it.put("steps", stepsDone)
                        FinishVerifier.stamp(it, verify)
                    }
                    checkpoint(id, messages, stepsDone, windowSteps)
                    continue
                }
                store.update(id, TaskStates.COMPLETED) {
                    it.put("summary", summaryText)
                    it.put("steps", stepsDone)
                    it.put("messages", messages)
                    // A finish that HOLDS clears any earlier
                    // gate discrepancy (v1.0.8): the record
                    // must not carry a stale "doesn't stand"
                    // against a finish that now checks out.
                    it.remove(FinishGate.DISCREPANCY_FIELD)
                    // Never silently downgrade (v1.0.3): a
                    // finish with computer-lane classes
                    // outstanding is labelled partial on the
                    // record, and the graded report says so.
                    if (plan != null && plan.partial) {
                        it.put("partialCompletion", true)
                    }
                    // Verify-state + final checks (v1.0.4):
                    // the re-read and any failed check ride
                    // the record; a failed check marks the
                    // finish partial — the report names it.
                    FinishVerifier.stamp(it, verify)
                    if (failures.isNotEmpty()) {
                        it.put("partialCompletion", true)
                        it.put(
                            FinalChecks.FAILED_FIELD,
                            failures.joinToString(",") { f -> f.check }
                        )
                    }
                    EventLog.append(
                        it, "finish", System.currentTimeMillis(),
                        JSONObject()
                            .put("verified", verify.ok)
                            .put(
                                "checks",
                                failures.joinToString(",") { f -> f.check }
                            )
                    )
                }
                onTaskCompleted(id, text, plan)
                return TaskStates.COMPLETED
            }
            // PLAN PROPOSAL (v1.0.4): the plan-mode run's one
            // productive act — the plan lands on the record,
            // the task parks, and the user approves in the
            // chat. The acting tools do not exist until then.
            if (!packaged && name == "propose_plan") {
                val planText = args.optString("plan")
                store.update(id, TaskStates.PAUSED) {
                    it.put("steps", stepsDone)
                    it.put("messages", messages)
                    it.put("planText", planText)
                    it.put(PlanAct.APPROVAL_KIND_FIELD, "plan")
                    it.put(
                        PlanAct.APPROVAL_DETAIL_FIELD,
                        planText.take(120)
                    )
                    TaskPlanning.applyPlanNote(it, args, stepsDone)
                    it.put("note", PlanAct.parkNote("plan", ""))
                    EventLog.append(
                        it, "plan_proposed",
                        System.currentTimeMillis()
                    )
                }
                return TaskStates.PAUSED
            }
            // The page signature BEFORE the step, for the
            // reflection pass (v1.0.4): a browser step's
            // outcome is graded against what actually
            // changed on the page.
            val pageSigBefore = BrowserSession.lastState?.let {
                StepReflection.pageSignature(it)
            }
            val observation = when {
                // PLAN GATE (v1.0.4): the dispatch wall
                // behind the registry wall — a mutating call
                // named in plan mode is refused by Sentinel.
                plan != null &&
                    PlanAct.refuses(PlanAct.mode(task), name) ->
                    TaskTools.PLAN_REFUSAL
                // PLAN NOTE (v1.0.4): the planning tool lands
                // on the record itself.
                !packaged && name == "plan_note" -> {
                    var obs = ""
                    store.update(id) {
                        obs = TaskPlanning.applyPlanNote(it, args, stepsDone)
                        EventLog.append(
                            it, "plan_note",
                            System.currentTimeMillis()
                        )
                    }
                    obs
                }
                // RECIPE CONTRACT (v1.0.4): a recipe's tool
                // allowlist is its contract — a call outside
                // it is refused, never improvised around.
                recipe != null && name != "finish" &&
                    !Recipes.allowedTools(recipe).contains(name) ->
                    "error: SENTINEL — the recipe " +
                        "${recipe.optString("name")} does not allow " +
                        "the tool $name; its tools are its contract. " +
                        "Work with the allowed tools or finish."
                packaged -> executeBrowserTool(id, task, name, args)
                else ->
                    // General task (v1.0.3 + v1.0.4): the
                    // pure dispatch (TaskTools.executorFor)
                    // routes each family to its executor —
                    // browser to the SAME device browser
                    // executor the packaged lane uses,
                    // calendar/mail/memory to the device
                    // lanes, the X tools refused by Sentinel,
                    // notes/search to the note executor.
                    when (TaskTools.executorFor(
                        browserToolsActive, name,
                        mailActive = plan?.mailActive == true,
                        calendarActive = plan?.requiresCalendar == true
                    )) {
                        TaskTools.Executor.BROWSER ->
                            executeBrowserTool(id, task, name, args)
                        TaskTools.Executor.CALENDAR ->
                            executeCalendarTool(id, name, args)
                        TaskTools.Executor.MAIL ->
                            executeMailTool(id, task, name, args, stepsDone)
                        TaskTools.Executor.MEMORY ->
                            executeMemoryTool(name, args)
                        TaskTools.Executor.REFUSE_X -> TaskTools.X_REFUSAL
                        else -> executeTool(id, name, args)
                    }
            }
            // A gmail send parked for the user's word inside
            // executeMailTool: the record already carries the
            // park — stop here, before any further step.
            if (observation == GMAIL_PARK_OBSERVATION) {
                return TaskStates.PAUSED
            }
            // The window's outcome, for the progress-aware
            // budget: an error observation is a step that
            // did not work; anything else is a step that
            // landed.
            if (observation.startsWith("error:")) {
                windowFailed += 1
                consecErrors += 1
            } else {
                windowOk += 1
                consecErrors = 0
                // v1.0.14: real tool work landed in this
                // run — the pending-finish precondition.
                sawToolWork = true
            }
            if (browserToolsActive) {
                lastChallenge = trackChallenge(name, observation, lastChallenge)
                if (UserHandoff.isHandoffObservation(observation)) {
                    // The handler recorded the cleaned step on
                    // the task record; read it back from the
                    // store (the local task copy is stale here).
                    pendingHandoffStep = store.get(id)
                        ?.optString("handoffStep")
                        ?.ifEmpty { null }
                        ?: UserHandoff.cleanStep("", "the site")
                }
            }
            messages.put(JSONObject().put("role", "user")
                .put("content", "Tool result ($name): $observation"))
            checkpoint(id, messages, stepsDone, windowSteps)
            // REFLECTION + STALL (v1.0.4, steals #3 + #14):
            // every general step is classified on the record
            // (worked / wrong target / no change /
            // unexpected) and the ONE stall mechanism judges
            // the stream — change strategy once, then park
            // with a named reason instead of grinding.
            if (!packaged) {
                val pageSigAfter = BrowserSession.lastState?.let {
                    StepReflection.pageSignature(it)
                }
                val outcome = StepReflection.classify(
                    name, observation, pageSigBefore, pageSigAfter
                )
                val nowMs = System.currentTimeMillis()
                store.update(id) {
                    StepReflection.record(it, stepsDone, name, outcome)
                    EventLog.append(
                        it, "step", nowMs,
                        JSONObject()
                            .put("tool", name)
                            .put("outcome", outcome.name)
                    )
                }
                when (stallTracker.record(
                    stallTracker.signature(name, args, observation),
                    outcome
                )) {
                    StallTracker.Verdict.CHANGE_STRATEGY -> {
                        messages.put(JSONObject().put("role", "user")
                            .put("content", stallTracker.strategyNote()))
                        store.update(id) {
                            it.put("messages", messages)
                            EventLog.append(
                                it, "stuck_strategy",
                                System.currentTimeMillis()
                            )
                        }
                    }
                    StallTracker.Verdict.PARK -> {
                        store.update(id, TaskStates.PAUSED) {
                            it.put("note", stallTracker.parkReason())
                            it.put("steps", stepsDone)
                            it.put("messages", messages)
                            it.put(StepBudget.WINDOW_FIELD, windowSteps)
                            EventLog.append(
                                it, "stuck_park",
                                System.currentTimeMillis()
                            )
                        }
                        return TaskStates.PAUSED
                    }
                    StallTracker.Verdict.OK -> {}
                }
                // PLANNING INTERVAL (v1.0.4, steal #8): the
                // run owes a recorded plan note at the
                // cadence — requested on this result, merged
                // by the plan_note tool when it lands.
                if (name != "plan_note" &&
                    TaskPlanning.planDue(windowSteps, browserToolsActive)
                ) {
                    messages.put(JSONObject().put("role", "user")
                        .put("content", TaskPlanning.PLAN_REQUEST))
                    store.update(id) { it.put("messages", messages) }
                }
            }
            // PACKAGED LANES (v1.0.8): the phase machine's
            // checkpoints, the finish gate's profile
            // evidence, and the loop breaker judge every
            // browser step on the record — a lane that is
            // circling parks here with the loop named
            // instead of burning the day.
            if (packaged && browserToolsActive) {
                postPackagedStep(
                    id, name, args, observation,
                    stepsDone, messages, windowSteps
                )?.let { return it }
            }
            task = store.get(id) ?: return TaskStates.FAILED
        }

        if (browserToolsActive && lastChallenge != null) {
            return park(id, lastChallenge, messages, stepsDone)
        }
        if (browserToolsActive && pendingHandoffStep != null) {
            return park(
                id,
                BrowserPerception.Challenge(
                    UserHandoff.WALL_KIND, pendingHandoffStep!!
                ),
                messages, stepsDone,
                stepOverride = pendingHandoffStep
            )
        }
        // PROGRESS-AWARE STEP BUDGET (v1.0.3, StepBudget):
        // the window is spent. A window that was making
        // progress RESTS and auto-resumes on the ordinary
        // waiting machinery (the worker re-enqueues at the
        // record's nextRetryAt; the watchdog's app-open kick
        // covers a lost chain) — up to the daily rest cap.
        // A stalled window, or a task out of rests, parks
        // for the user's word exactly as before.
        val budgetRecord = store.get(id) ?: task
        val today = StepBudget.dayKey(System.currentTimeMillis())
        val rests = StepBudget.restsToday(budgetRecord, today)
        return if (StepBudget.decide(
                windowOk, windowFailed, consecErrors, rests
            ) == StepBudget.Decision.AUTO_REST
        ) {
            store.update(id) {
                it.put("steps", stepsDone)
                it.put("messages", messages)
                it.put(StepBudget.WINDOW_FIELD, 0)
                it.put(StepBudget.RESTS_FIELD, rests + 1)
                it.put(StepBudget.REST_DAY_FIELD, today)
                it.put(StepBudget.REST_FLAG, true)
            }
            wait(
                id, TaskStates.WAITING_ROUTE,
                StepBudget.REST_NOTE, StepBudget.REST_MS
            )
        } else {
            store.update(id, TaskStates.PAUSED) {
                it.put("note", "Step budget ($maxSteps) reached — resume to continue.")
                it.put("steps", stepsDone)
                it.put("messages", messages)
                it.put(StepBudget.WINDOW_FIELD, windowSteps)
            }
            TaskStates.PAUSED
        }
    }

    /** The wall tracker: an OBSERVED CHALLENGE from a browser/X
     *  tool sets the current wall; a later successful browser/X
     *  result (a route that worked) clears it. Errors neither set
     *  nor clear — a wall is graded from observations, and a route
     *  switch is Lille working, not the wall disappearing. */
    private fun trackChallenge(
        tool: String, observation: String,
        current: BrowserPerception.Challenge?
    ): BrowserPerception.Challenge? {
        if (!tool.startsWith("browser_") && !tool.startsWith("x_")) {
            return current
        }
        val m = CHALLENGE_RE.find(observation)
        if (m != null) {
            return BrowserPerception.Challenge(
                m.groupValues[1], m.groupValues[2].take(200)
            )
        }
        return if (observation.startsWith("error:")) current else null
    }

    /** Park the task at the observed wall: record the wall + the
     *  ONE named step + a screenshot, checkpoint, notify. The
     *  WebView session itself stays exactly where it stopped —
     *  BrowserActivity re-parents it for the human step.
     *
     *  With [stepOverride] (v0.10.1) the park is a planned USER
     *  HANDOFF instead: challenge.kind is UserHandoff.WALL_KIND,
     *  the step shown is the task's own named step, and the
     *  record is flagged userHandoffDone so reports can grade
     *  "the user signed in themselves" from evidence. */
    private fun park(
        id: String, challenge: BrowserPerception.Challenge,
        messages: JSONArray, steps: Int, stepOverride: String? = null
    ): String {
        val domain = BrowserPerception.domainOf(BrowserSession.currentUrl())
            .ifEmpty { "the site" }
        var shotName = ""
        try {
            shotName = BrowserSession.screenshot(
                context, taskDir(id), "wall.png"
            ).name
        } catch (e: Exception) {
            // A missing screenshot never blocks the park itself.
        }
        val isUserStep = challenge.kind == UserHandoff.WALL_KIND
        val stepText = stepOverride
            ?: if (isUserStep) {
                UserHandoff.cleanStep("", domain)
            } else {
                "Complete the human verification on $domain in " +
                    "Lille's browser: tap 'Open Lille's browser', pass the check, " +
                    "then tap 'Done — hand back to Lille'. She resumes by herself."
            }
        val noteText = if (isUserStep) {
            "Parked for your step on $domain — Lille needs you (one step)."
        } else {
            "Parked at a human-only wall on $domain — " +
                "Lille needs you (one step)."
        }
        store.update(id, TaskStates.PARKED_AT_WALL) {
            it.put("wall", JSONObject()
                .put("domain", domain)
                .put("kind", challenge.kind)
                .put("detail", challenge.detail))
            it.put("wallDomain", domain)
            it.put("wallKind", challenge.kind)
            it.put("wallDetail", challenge.detail)
            it.put("wallStep", stepText)
            it.put("wallScreenshot", shotName)
            it.put("wallAt", System.currentTimeMillis())
            it.put("messages", messages)
            it.put("steps", steps)
            it.put("note", noteText)
            if (isUserStep) it.put("userHandoffDone", true)
        }
        WallNotify.notifyParked(context, id, stepText)
        return TaskStates.PARKED_AT_WALL
    }

    /** The finish call's claimed outputs as a list. */
    private fun claimedOutputs(outputs: JSONArray?): List<String> {
        if (outputs == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until outputs.length()) {
            val rel = outputs.optString(i)
            if (rel.isNotEmpty()) out.add(rel)
        }
        return out
    }

    /** Apply the record's pending finish (v1.0.14) when
     *  it still verifies: her completion statement is
     *  fresh (no tool work followed it), its outputs are
     *  on disk and were this task's own work, no gate
     *  refuses it (FinishGate, plan mode, an unresolved
     *  wall), and its dates do not contradict the record's
     *  own tool evidence. The close is a state write —
     *  COMPLETED with her summary — and spends no model
     *  call. Returns true when the record was closed. */
    private fun applyPendingFinish(id: String): Boolean {
        val task = store.get(id) ?: return false
        val pending = PendingFinish.read(task) ?: return false
        if (task.optInt("steps", 0) > pending.atStep) return false
        if (FinishGate.isGated(task) && !FinishGate.holds(task)) {
            return false
        }
        if (PlanAct.isPlanMode(task)) return false
        val wall = task.optJSONObject("wall")
        if (wall != null &&
            task.optLong("handedBackAt", 0L) <= task.optLong("wallAt", 0L)
        ) {
            return false
        }
        val dir = taskDir(id)
        if (pending.outputs.any { !File(dir, it).isFile }) return false
        val observations = FinishChecks.observationsOf(
            task.optJSONArray("messages") ?: JSONArray()
        )
        if (FinishChecks.unproducedClaims(
                pending.outputs, PendingFinish.produced(task),
                observations
            ).isNotEmpty()
        ) {
            return false
        }
        if (FinishChecks.dateContradiction(
                pending.summary, observations
            ) != null
        ) {
            return false
        }
        // EVIDENCE-PROPORTIONATE FINISH (v1.0.15, class 2
        // mirror): the deterministic close judges the same
        // evidence demand as the finish path — a statement
        // speaking about a demanded file no step read, or
        // asserting a value no evidence carries, does not
        // close the record; the loop works it instead.
        val pendingDemanded = FinishChecks.demandedFiles(
            task.optString("text"),
            dir.listFiles()?.filter { it.isFile }
                ?.map { it.name } ?: emptyList()
        )
        if (pendingDemanded.isNotEmpty()) {
            val readContents = mutableListOf<String>()
            for (rel in pendingDemanded) {
                val content = try {
                    File(dir, rel).readText().take(65_536)
                } catch (e: Exception) {
                    ""
                }
                if (!FinishChecks.readEvidenced(
                        rel, content, observations)
                ) {
                    return false
                }
                readContents.add(content)
            }
            if (FinishChecks.ungroundedContentValues(
                    pending.summary, observations,
                    task.optString("text"), readContents
                ).isNotEmpty()
            ) {
                return false
            }
        }
        store.update(id, TaskStates.COMPLETED) {
            it.put("summary", pending.summary)
            it.put("note", PendingFinish.APPLIED_NOTE)
            PendingFinish.clear(it)
            EventLog.append(
                it, "pending_finish_applied",
                System.currentTimeMillis()
            )
        }
        return true
    }

    private fun wait(id: String, state: String, note: String, waitMs: Long): String {
        // FINISH MUST LAND (v1.0.14): before the run
        // settles into a wait, a pending finish that still
        // verifies closes the record instead — finished
        // work does not park behind a dropped route.
        if (applyPendingFinish(id)) return TaskStates.COMPLETED
        store.update(id, state) {
            it.put("note", note)
            it.put("nextRetryAt", System.currentTimeMillis() + waitMs)
            // THE RESTING PAGE (v1.0.11, G6): a browser
            // task settling into a wait leaves the
            // shared WebView resting on its page — the
            // stamp is what lets a later identity
            // re-check know the page is spoken for and
            // must not be navigated away.
            if (isBrowserTask(it)) {
                SessionIdentity.stampRestingPage(
                    it, BrowserSession.currentUrl()
                )
            }
        }
        return state
    }

    private fun checkpoint(
        id: String, messages: JSONArray, steps: Int, windowSteps: Int
    ) {
        store.update(id) {
            it.put("messages", messages)
            it.put("steps", steps)
            it.put(StepBudget.WINDOW_FIELD, windowSteps)
        }
    }

    private fun exportSpec(task: JSONObject, reason: String? = null) {
        val id = task.optString("id")
        val dir = File(context.filesDir, "specs").apply { mkdirs() }
        val spec = JSONObject()
            .put("task_id", id)
            .put("task_text", task.optString("text"))
            .put("private", false)
            .put("needs_runner", true)
            .put("runner", "github-actions:lille-task.yml")
        if (reason != null) spec.put("reason", reason)
        File(dir, "$id.json").writeText(spec.toString(2))
    }

    // ---------- model ----------

    /** One OpenAI-compatible chat call. [key] null = the
     *  keyless route. Returns {http, content?, retryAfterSec?};
     *  throws IOException on transport failure. */
    private fun callOpenAi(
        url: String, model: String, key: String?, messages: JSONArray
    ): JSONObject {
        val body = JSONObject().put("model", model).put("messages", messages)
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
        client.newCall(builder.build()).execute().use { response ->
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

    /** Route 2 — AI Horde (keyless, slow community queue):
     *  the conversation flattened into one prompt, submitted
     *  with the service's public anonymous key, polled with
     *  a bounded wait (~3 min — a task step may wait, but
     *  not forever; on expiry the chain falls through).
     *  Same result shape as callOpenAi; http 0 when the job
     *  faulted or the wait expired. */
    private fun callHorde(messages: JSONArray): JSONObject {
        val prompt = buildString {
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                val label = when (m.optString("role")) {
                    "system" -> "System"
                    "assistant" -> "Lille"
                    else -> "User"
                }
                append(label).append(": ").append(m.optString("content")).append("\n")
            }
            append("Lille:")
        }
        val params = JSONObject()
            .put("max_length", 1024)
            .put("temperature", 0.7)
            .put("stop_sequence", JSONArray().put("\nUser:"))
        val body = JSONObject()
            .put("prompt", prompt)
            .put("params", params)
            .put("models", JSONArray())
            .put("slow_workers", true)
        val submit = Request.Builder()
            .url("https://stablehorde.net/api/v2/generate/text/async")
            .header("apikey", "0000000000")
            .header("Client-Agent", "lille-android:0.12.1:free-agent")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val jobId: String
        client.newCall(submit).execute().use { response ->
            if (response.code !in 200..299) {
                val out = JSONObject().put("http", response.code)
                response.header("Retry-After")?.toLongOrNull()?.let {
                    out.put("retryAfterSec", it)
                }
                return out
            }
            jobId = JSONObject(response.body?.string() ?: "{}")
                .optString("id", "")
        }
        if (jobId.isEmpty()) return JSONObject().put("http", 0)
        repeat(18) {
            Thread.sleep(10_000)
            try {
                val poll = Request.Builder()
                    .url("https://stablehorde.net/api/v2/generate/text/status/$jobId")
                    .header("apikey", "0000000000")
                    .header("Client-Agent", "lille-android:0.12.1:free-agent")
                    .build()
                client.newCall(poll).execute().use { response ->
                    if (response.code == 200) {
                        val data = JSONObject(response.body?.string() ?: "{}")
                        if (data.optBoolean("faulted")) {
                            return JSONObject().put("http", 0)
                        }
                        if (data.optBoolean("done")) {
                            val gens = data.optJSONArray("generations")
                            val text = if (gens != null && gens.length() > 0) {
                                gens.getJSONObject(0).optString("text", "").trim()
                            } else {
                                ""
                            }
                            return if (text.isNotEmpty()) {
                                JSONObject().put("http", 200).put("content", text)
                            } else {
                                JSONObject().put("http", 0)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // A dropped poll is not a dead job — keep polling.
            }
        }
        return JSONObject().put("http", 0)
    }

    /** One model call, walking the brain chain. OWN KEYS
     *  LEAD (v1.0.2): the user's own-key routes for keys
     *  actually saved walk FIRST, in BrainFailure.OWN_KEY_ORDER
     *  (Groq → Gemini → Cerebras → OpenRouter → AgentRouter
     *  → custom; OwnKeyRoutes), and the keyless pair
     *  (Pollinations → AI Horde) is the fallback — with no
     *  keys saved the walk is the keyless pair alone, as it
     *  has always been. The first route to answer 200 serves
     *  the step and is named in the result. Failures fall
     *  through WITHIN the call, silently (seamless law);
     *  the aggregate keeps the runner's waiting discipline:
     *  any rate-limit seen (and no success) returns that
     *  rate-limit result (-> waiting_route), else the last
     *  hard HTTP failure is returned (the FAILED path), else
     *  an IOException is thrown (-> waiting_network). Own keys
     *  are resolved from the vault at call time and cross
     *  only into the Authorization header.
     *
     *  NO SLEEPING WHILE A ROUTE LIVES (v1.0.2): every
     *  route's pacing floor, rate-limit streak and rest
     *  deadline are PER ROUTE, persisted in the task record
     *  (routeLastCallAt / routeStreaks / routeRestUntil), so
     *  they survive process death like everything else in
     *  the record. A route that is resting or inside its own
     *  pacing floor is SKIPPED in this walk — the next route
     *  is tried in the same breath, never after a sleep. A
     *  402/429 escalates only THAT route's cooldown
     *  (BrainPacing.cooldownMs on its own streak). When the
     *  walk ends without success, the result carries
     *  "restMs": BrainPacing.restMs over the waits that
     *  actually bind (routes that rate-limited this call +
     *  routes skipped for rest/pacing) — the shortest one,
     *  capped at ~60 s. When EVERY route was skipped (none
     *  attempted), the result is a synthetic 429 carrying
     *  that same restMs, so the runner waits exactly until
     *  the first route frees up and no longer. */
    private fun callModel(id: String, messages: JSONArray): JSONObject {
        var rateLimit: JSONObject? = null
        var lastFailure: JSONObject? = null
        var lastIo: IOException? = null
        var attempted = 0
        val bindingWaits = mutableListOf<Long>()

        val record = store.get(id)
        val restUntil = record?.optJSONObject("routeRestUntil") ?: JSONObject()
        val streaks = record?.optJSONObject("routeStreaks") ?: JSONObject()
        val lastCallAt = record?.optJSONObject("routeLastCallAt") ?: JSONObject()
        fun persistRouteState() {
            store.update(id) {
                it.put("routeRestUntil", restUntil)
                it.put("routeStreaks", streaks)
                it.put("routeLastCallAt", lastCallAt)
            }
        }

        fun attempt(routeName: String, call: () -> JSONObject): JSONObject? {
            val now = System.currentTimeMillis()
            val restWait = (restUntil.optLong(routeName, 0L) - now)
                .coerceAtLeast(0L)
            val paceWait = BrainPacing.routePacingWaitMs(
                routeName, lastCallAt.optLong(routeName, 0L), now
            )
            val blockedWait = maxOf(restWait, paceWait)
            if (blockedWait > 0L) {
                // This route cannot serve now; another may.
                // Skip it in the same breath — its wait only
                // binds the task if EVERY route is blocked.
                bindingWaits.add(blockedWait)
                return null
            }
            attempted += 1
            lastCallAt.put(routeName, now)
            persistRouteState()
            val r = try {
                call()
            } catch (e: IOException) {
                lastIo = e
                return null
            }
            val http = r.optInt("http", 0)
            if (http == 200) {
                streaks.put(routeName, 0)
                restUntil.put(routeName, 0L)
                persistRouteState()
                return r.put("route", routeName)
            }
            if (http == 402 || http == 429) {
                val streak = streaks.optInt(routeName, 0)
                val cooldown = BrainPacing.cooldownMs(
                    if (r.has("retryAfterSec"))
                        r.optLong("retryAfterSec") else null,
                    streak
                )
                streaks.put(
                    routeName, BrainPacing.nextCounter(streak, http)
                )
                restUntil.put(
                    routeName, System.currentTimeMillis() + cooldown
                )
                persistRouteState()
                bindingWaits.add(cooldown)
                if (rateLimit == null) rateLimit = r.put("route", routeName)
            } else if (http != 0) {
                lastFailure = r.put("route", routeName)
            }
            return null
        }

        val present = OwnKeyRoutes.presentRoutes(
            { name -> vault.hasSecret(name) },
            lanEnabled = LanRoute.isEnabled(context)
        )
        for (route in BrainFailure.OWN_KEY_ORDER) {
            if (route !in present) continue
            if (route == BrainFailure.Route.LAN) {
                // YOUR COMPUTER (v1.0.4, steal #13): the LAN
                // route — the user's own model server, no key.
                // Health-checked first: an unreachable
                // computer is skipped in silence and the chain
                // falls back seamlessly (the seamless law —
                // no route talk reaches any user surface).
                val base = vault.getSecret(OwnKeyRoutes.LAN_URL_VAULT_KEY)
                    ?: continue
                if (!lanHealthy(base)) continue
                val model = vault.getSecret(OwnKeyRoutes.LAN_MODEL_VAULT_KEY)
                    ?.ifBlank { null } ?: OwnKeyRoutes.LAN_DEFAULT_MODEL
                attempt(route.key) {
                    callOpenAi(
                        base.trimEnd('/') + "/chat/completions",
                        model, null, messages
                    )
                }?.let { return it }
                continue
            }
            val spec = OwnKeyRoutes.spec(route) ?: continue
            val key = vault.getSecret(spec.vaultKeyName) ?: continue
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
            attempt(spec.route.key) {
                callOpenAi(
                    OwnKeyRoutes.chatUrl(spec, customBase),
                    OwnKeyRoutes.chatModel(spec, customModel),
                    key, messages
                )
            }?.let { return it }
        }
        // The keyless fallback (OWN KEYS LEAD: walked only
        // after every present own-key route has had its
        // attempt — or first, when no key is saved at all).
        attempt("pollinations") { callOpenAi(TEXT_ENDPOINT, MODEL, null, messages) }
            ?.let { return it }
        attempt("horde") { callHorde(messages) }?.let { return it }
        rateLimit?.let {
            return it.put("restMs", BrainPacing.restMs(bindingWaits))
        }
        if (attempted == 0 && bindingWaits.isNotEmpty()) {
            // Every route is resting or inside its pacing
            // floor: wait exactly for the first to free up.
            return JSONObject().put("http", 429)
                .put("restMs", BrainPacing.restMs(bindingWaits))
                .put("pacingOnly", true)
        }
        lastFailure?.let { return it }
        throw lastIo ?: IOException("all brain routes unreachable")
    }

    private fun parseToolCall(content: String): Pair<String, JSONObject>? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val obj = JSONObject(content.substring(start, end + 1))
                if (obj.has("tool")) {
                    return obj.optString("tool") to
                        (obj.optJSONObject("args") ?: JSONObject())
                }
            } catch (e: Exception) {
                // Not the JSON dialect — fall through to the
                // finish-call dialect below.
            }
        }
        // FINISH DIALECT (v1.0.14): finish(...) emitted as
        // literal text is recovered as the call (the
        // schedule-a / format-a shape from the battery).
        val fin = FinishDialect.parse(content)
        if (fin != null) return "finish" to fin
        return null
    }

    // ---------- note tools (v0.4 scope) ----------

    private fun executeTool(id: String, name: String, args: JSONObject): String {
        val dir = taskDir(id)
        return try {
            when (name) {
                "write_note" -> writeNoteCheckpointed(id, args)
                "read_note" -> readNote(dir, args)
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

    private fun writeNote(dir: File, args: JSONObject): String {
        val rel = args.optString("path")
        val target = File(dir, rel).canonicalFile
        return if (!target.path.startsWith(dir.canonicalFile.path)) {
            "error: path escapes the task sandbox"
        } else {
            target.parentFile?.mkdirs()
            target.writeText(args.optString("content"))
            "wrote $rel (${args.optString("content").length} chars)"
        }
    }

    /** write_note with its v1.0.4 bookkeeping: a CHECKPOINT
     *  of the note's prior state lands on the record before
     *  the write (so the step can be undone), and the path
     *  is remembered as the task's last note — the finish
     *  probe's re-read target (verify-state, steal #2). */
    private fun writeNoteCheckpointed(id: String, args: JSONObject): String {
        val rel = args.optString("path")
        val file = File(taskDir(id), rel)
        val before = if (file.isFile) {
            try { file.readText() } catch (e: Exception) { null }
        } else {
            null
        }
        val obs = writeNote(taskDir(id), args)
        if (!obs.startsWith("error:")) {
            store.update(id) {
                Checkpoints.append(
                    it,
                    Checkpoints.noteEntry(
                        it.optInt("steps", 0), rel, before
                    )
                )
                it.put("lastNotePath", rel)
                // PRODUCED EVIDENCE (v1.0.14): the write
                // stamps itself as this task's work — the
                // finish checks judge claims against it.
                PendingFinish.addProduced(it, rel)
            }
        }
        return obs
    }

    /** Undo the newest checkpointed step (v1.0.4): restore
     *  the note the step changed — byte-for-byte, or delete
     *  it when the step created it. Deterministic; driven
     *  from the chat's "undo last step" command. */
    fun undoLastCheckpoint(id: String): String {
        val task = store.get(id) ?: return "No such task."
        val entry = Checkpoints.pop(task) ?: return "Nothing to undo."
        val (path, content) = Checkpoints.restoreTarget(entry)
        val file = File(taskDir(id), path).canonicalFile
        if (!file.path.startsWith(taskDir(id).canonicalFile.path)) {
            return "Undo refused: the recorded path escapes the sandbox."
        }
        if (content == null) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        store.update(id) {
            it.put(Checkpoints.FIELD, task.optJSONArray(Checkpoints.FIELD))
        }
        return "Undid the last step: $path restored."
    }

    /** VERIFY-STATE probe (v1.0.4, steal #2): re-read the
     *  end state a finish claims, per FinishVerifier's
     *  selection. The runner owns the artefacts; the pure
     *  layer grades what the re-read found. */
    private fun runFinishProbe(
        id: String, plan: TaskTools.Plan?, browserActive: Boolean
    ): FinishVerifier.Result {
        val task = store.get(id) ?: return FinishVerifier.noneVerdict()
        return when (FinishVerifier.probeFor(task, browserActive)) {
            FinishVerifier.ProbeKind.NOTE -> {
                val path = task.optString("lastNotePath")
                val file = File(taskDir(id), path)
                FinishVerifier.noteVerdict(
                    path, file.isFile,
                    if (file.isFile) file.length().toInt() else 0
                )
            }
            FinishVerifier.ProbeKind.BROWSER -> {
                val state = BrowserSession.lastState
                if (state == null) {
                    FinishVerifier.browserVerdict("", "", false)
                } else {
                    FinishVerifier.browserVerdict(
                        state.url, state.title,
                        BrowserPerception.domainAllowed(
                            state.url, browserDomains(task)
                        )
                    )
                }
            }
            FinishVerifier.ProbeKind.CALENDAR -> {
                val title = task.optString("calendarCreatedTitle")
                val start = task.optLong("calendarCreatedStart", 0L)
                val found = try {
                    CalendarContractGateway(context)
                        .events(start - 3_600_000L, start + 86_400_000L)
                        .any { it.title == title }
                } catch (e: Exception) {
                    false
                }
                FinishVerifier.calendarVerdict(title, found)
            }
            FinishVerifier.ProbeKind.NONE -> FinishVerifier.noneVerdict()
        }
    }

    /** The text a recipe's success check reads (v1.0.4):
     *  the target note's content, or the browser's resting
     *  page text — the check's own target decides. */
    private fun recipeProbeText(recipe: JSONObject, id: String): String {
        val check = recipe.optJSONObject("successCheck") ?: return ""
        return when (check.optString("kind")) {
            "note_contains" -> {
                val file = File(taskDir(id), check.optString("target"))
                if (file.isFile) {
                    try { file.readText() } catch (e: Exception) { "" }
                } else {
                    ""
                }
            }
            "page_contains" -> BrowserSession.lastState?.text ?: ""
            else -> ""
        }
    }

    /** A recipe's success check, in words (for the retry
     *  note and the verdict detail). */
    private fun describeRecipeCheck(recipe: JSONObject): String {
        val check = recipe.optJSONObject("successCheck")
            ?: return "no success check"
        return when (check.optString("kind")) {
            "note_contains" ->
                "the note ${check.optString("target")} must contain " +
                    "\"${check.optString("pattern")}\""
            "page_contains" ->
                "the page must contain \"${check.optString("pattern")}\""
            else -> "the run must complete"
        }
    }

    /** Post-completion growth (v1.0.4): a successful task
     *  that took ≥5 tool calls distils its SKILL (steal #7);
     *  the sites it worked grow their CARDS (steal #9).
     *  Both ride the agent-state store, and so the backup. */
    private fun onTaskCompleted(
        id: String, text: String, plan: TaskTools.Plan?
    ) {
        try {
            val record = store.get(id) ?: return
            val stateStore = AgentStateStore(context)
            val skillStore = SkillStore(
                stateStore.io(AgentStateStore.SECTION_SKILLS)
            )
            if (skillStore.shouldDistil(record)) {
                skillStore.distil(record, System.currentTimeMillis())
            }
            if (plan != null && plan.browserDomains.isNotEmpty()) {
                val cards = SiteCards(
                    stateStore.io(AgentStateStore.SECTION_CARDS)
                )
                val title = BrowserSession.lastState?.title ?: ""
                for (domain in plan.browserDomains) {
                    cards.grow(domain, System.currentTimeMillis(), keyScreen = title)
                }
            }
        } catch (e: Exception) {
            // Growth is a dividend, never a failure mode:
            // a completed task stays completed.
        }
    }

    /** The calendar executor (v1.0.4): CalendarContract
     *  behind the pure gateway. Creation is a mutating
     *  tool — plan-gated upstream like every mutation, and
     *  its result is recorded for the finish probe. */
    private fun executeCalendarTool(
        id: String, name: String, args: JSONObject
    ): String {
        val gateway = CalendarContractGateway(context)
        return try {
            when (name) {
                "calendar_read" -> {
                    val now = System.currentTimeMillis()
                    val rangeName = args.optString("range")
                        .ifEmpty { "today" }
                    val (from, to) = when (rangeName) {
                        "week" -> now to now + 7 * 86_400_000L
                        "tomorrow" ->
                            CalendarFormat.dayRange(now + 86_400_000L)
                        else -> CalendarFormat.dayRange(now)
                    }
                    CalendarFormat.formatEvents(
                        gateway.events(from, to), rangeName
                    )
                }
                "calendar_create" -> {
                    val title = args.optString("title")
                    val start = args.optLong("startMs", 0L)
                    val end = args.optLong("endMs", 0L)
                    if (title.isBlank() || start <= 0L || end <= start) {
                        "error: calendar_create needs a title and " +
                            "startMs/endMs (epoch ms, end after start) — " +
                            "compute them from the current time given " +
                            "in the environment note"
                    } else {
                        val created = gateway.create(
                            title, start, end, args.optString("location")
                        )
                        store.update(id) {
                            it.put("calendarCreatedTitle", created.title)
                            it.put("calendarCreatedStart", created.startMs)
                        }
                        "CALENDAR_CREATED id=${created.id} " +
                            "title=\"${created.title}\" " +
                            "start=${CalendarFormat.formatInstant(created.startMs)} " +
                            "— on the calendar."
                    }
                }
                else -> "error: unknown tool: $name"
            }
        } catch (e: SecurityException) {
            "error: the calendar permission is not granted on this " +
                "phone — the user can grant it in Android Settings → " +
                "Apps → Lille → Permissions → Calendar. Do not invent " +
                "events; finish honestly if the job needs the calendar."
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** The Gmail executor (v1.0.4): the device lane behind
     *  the pure gateway + redaction layer. Not connected →
     *  the honest NOT_CONNECTED observation (the plan
     *  already declared it at task start; nothing is
     *  faked). SEND walks MailSendGate's triple gate in
     *  order; when the ONLY closed gate is the user's
     *  approval, the send PARKS for that word — the record
     *  carries the pending send, the chat carries the ask,
     *  and an "approve send" in the chat opens the gate. */
    private fun executeMailTool(
        id: String, task: JSONObject, name: String,
        args: JSONObject, stepsDone: Int
    ): String {
        if (!GmailConnection.isConnected(context)) {
            return NOT_CONNECTED
        }
        val gateway = GmailGateway(context)
        return try {
            when (name) {
                "gmail_list" -> GmailFormat.formatList(
                    gateway.list(args.optInt("max", 10))
                )
                "gmail_search" -> GmailFormat.formatList(
                    gateway.search(
                        args.optString("query"),
                        args.optInt("max", 10)
                    )
                )
                "gmail_read" -> gateway.read(args.optString("id"))
                "gmail_send" -> {
                    val refusal = MailSendGate.refusal(
                        GmailConnection.sendEnabled(context),
                        GmailConnection.sendScopeGranted(context),
                        task.optBoolean("gmailSendApproved")
                    )
                    when {
                        refusal == null -> gateway.send(
                            args.optString("to"),
                            args.optString("subject"),
                            args.optString("body")
                        )
                        // The only closed gate is approval:
                        // park for the user's word, with the
                        // send itself stashed on the record.
                        GmailConnection.sendEnabled(context) &&
                            GmailConnection.sendScopeGranted(context) -> {
                            val detail = "to ${args.optString("to")} — " +
                                args.optString("subject").take(60)
                            store.update(id, TaskStates.PAUSED) {
                                it.put("steps", stepsDone)
                                it.put(
                                    PlanAct.APPROVAL_KIND_FIELD,
                                    "gmail_send"
                                )
                                it.put(
                                    PlanAct.APPROVAL_DETAIL_FIELD, detail
                                )
                                it.put(
                                    "pendingGmailSend",
                                    JSONObject()
                                        .put("to", args.optString("to"))
                                        .put(
                                            "subject",
                                            args.optString("subject")
                                        )
                                        .put("body", args.optString("body"))
                                )
                                it.put(
                                    "note",
                                    PlanAct.parkNote("gmail_send", detail)
                                )
                                EventLog.append(
                                    it, "send_parked",
                                    System.currentTimeMillis()
                                )
                            }
                            GMAIL_PARK_OBSERVATION
                        }
                        else -> refusal
                    }
                }
                else -> "error: unknown tool: $name"
            }
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** The memory executor (v1.0.4, steal #5): her core
     *  blocks + journal, through the consolidation write
     *  path — never raw appends. */
    private fun executeMemoryTool(name: String, args: JSONObject): String {
        val memory = AgentMemory(
            AgentStateStore(context).io(AgentStateStore.SECTION_MEMORY)
        )
        return try {
            when (name) {
                "memory_read" -> memory.snapshot()
                    .ifBlank { "(memory is empty)" }
                "memory_search" -> {
                    val hits = memory.search(args.optString("query"))
                    if (hits.isEmpty()) {
                        "(nothing stored matches)"
                    } else {
                        hits.joinToString("\n") { "- $it" }
                    }
                }
                "memory_write" -> {
                    val result = memory.write(
                        args.optString("block"),
                        args.optString("fact"),
                        System.currentTimeMillis()
                    )
                    "MEMORY ${result.op} applied=${result.applied}" +
                        if (result.note.isNotEmpty()) {
                            " — ${result.note}"
                        } else {
                            ""
                        }
                }
                else -> "error: unknown tool: $name"
            }
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun readNote(dir: File, args: JSONObject): String {
        val target = File(dir, args.optString("path"))
        return if (target.isFile) target.readText().take(4000)
        else "error: no such note: ${args.optString("path")}"
    }

    /**
     * Keyless search — the core chain's order (routes.py),
     * walked until a route serves: DuckDuckGo Instant Answers,
     * then Wikipedia, then Open Library (v0.10; before this the
     * device stopped at route 1, and DDG Instant answers many
     * queries with nothing). The result names its route.
     */
    private fun webSearch(query: String): String {
        ddgInstant(query)?.let { return "[search route: duckduckgo-instant]\n$it" }
        wikipediaSearch(query)?.let { return "[search route: wikipedia]\n$it" }
        openLibrarySearch(query)?.let { return "[search route: openlibrary]\n$it" }
        return "No result found for that query on any keyless search route " +
            "(DuckDuckGo Instant, Wikipedia, Open Library)."
    }

    /** Route 1: DDG Instant Answers. Null = no instant answer
     *  (normal for this API) or the route is unreachable — the
     *  chain falls through either way. */
    private fun ddgInstant(query: String): String? {
        val url = "https://api.duckduckgo.com/?q=" +
            URLEncoder.encode(query, "UTF-8") + "&format=json&no_html=1"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code != 200) return null
                val data = JSONObject(response.body?.string() ?: "{}")
                val abstract = data.optString("AbstractText", "")
                if (abstract.isNotEmpty()) return abstract.take(1500)
                val topics = data.optJSONArray("RelatedTopics")
                if (topics != null && topics.length() > 0) {
                    val first = topics.getJSONObject(0).optString("Text", "")
                    if (first.isNotEmpty()) return first.take(1500)
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Route 2: Wikipedia OpenSearch (keyless). */
    private fun wikipediaSearch(query: String): String? {
        val url = "https://en.wikipedia.org/w/api.php?action=opensearch&search=" +
            URLEncoder.encode(query, "UTF-8") + "&limit=3&namespace=0&format=json"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code != 200) return null
                SearchChain.parseWikipediaOpenSearch(response.body?.string() ?: "")
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Route 3: Open Library search (keyless, books-shaped). */
    private fun openLibrarySearch(query: String): String? {
        val url = "https://openlibrary.org/search.json?q=" +
            URLEncoder.encode(query, "UTF-8") + "&limit=3"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code != 200) return null
                SearchChain.parseOpenLibrary(response.body?.string() ?: "")
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- browser tools (v0.9 device lane) ----------
    //
    // The SAME contract as the host lane (XLANE.md): allowlist
    // first, SECRET guards twice (last-read snapshot + live
    // probe), vault values by key name only, CHALLENGE surfaces
    // as the observation itself, post-action domain guard.

    private fun browserDomains(task: JSONObject): List<String> {
        val arr = task.optJSONArray("browserDomains") ?: return emptyList()
        return List(arr.length()) { arr.optString(it) }
    }

    private fun domainGuard(
        state: BrowserPerception.PageState, domains: List<String>
    ): String? {
        if (state.challenge != null) return null
        if (state.url.isNotEmpty() &&
            !BrowserPerception.domainAllowed(state.url, domains)
        ) {
            return "error: the browser is now on ${state.url}, whose domain " +
                "is NOT in this task's allowlist " +
                "(${domains.joinToString(", ").ifEmpty { "none" }}). " +
                "browser_open an allowed URL to continue."
        }
        return null
    }

    private fun preAction(domains: List<String>): String? {
        val url = BrowserSession.currentUrl()
        if (url.isNotEmpty() &&
            !BrowserPerception.domainAllowed(url, domains)
        ) {
            return "error: current page $url is outside this task's domain " +
                "allowlist; browser_open an allowed URL first."
        }
        return null
    }

    private fun executeBrowserTool(
        id: String, task: JSONObject, name: String, args: JSONObject
    ): String {
        val domains = browserDomains(task)
        return try {
            when (name) {
                "write_note" -> writeNoteCheckpointed(id, args)
                "read_note" -> readNote(taskDir(id), args)
                "browser_open" -> {
                    val url = args.optString("url")
                    val phaseRefusal = completedPhaseRefusal(id, url)
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        "error: browser_open needs an http(s) URL: ${url.take(80)}"
                    } else if (!BrowserPerception.domainAllowed(url, domains)) {
                        "error: domain ${BrowserPerception.domainOf(url)} is not " +
                            "in this task's browser allowlist " +
                            "(${domains.joinToString(", ").ifEmpty { "none authorised" }})"
                    } else if (phaseRefusal != null) {
                        phaseRefusal
                    } else {
                        val state = BrowserSession.open(
                            context, url,
                            args.optDouble("settle", 4.0)
                        )
                        domainGuard(state, domains)
                            ?: BrowserPerception.formatState(
                                state, "BROWSER_OK opened $url"
                            )
                    }
                }
                "browser_read" -> {
                    val state = BrowserSession.read(context)
                    BrowserPerception.formatState(state, "PAGE")
                }
                "browser_click" -> {
                    preAction(domains)?.let { return it }
                    val state = when {
                        args.has("ref") ->
                            BrowserSession.clickRef(context, args.optInt("ref"))
                        args.optString("text").isNotEmpty() ->
                            BrowserSession.clickText(context, args.optString("text"))
                        else -> return "error: browser_click needs a ref or a text"
                    }
                    domainGuard(state, domains)
                        ?: BrowserPerception.formatLite(state, "CLICK_OK")
                }
                "browser_click_mark" -> {
                    // Set-of-Marks fallback (v1.0.4): pages
                    // with no element tree carry numbered
                    // marks; the session resolves the number
                    // to a real tap at the region's centre.
                    preAction(domains)?.let { return it }
                    if (!args.has("n")) {
                        return "error: browser_click_mark needs n (a mark " +
                            "number from the page's MARKS block)"
                    }
                    val state = BrowserSession.clickMark(
                        context, args.optInt("n")
                    )
                    domainGuard(state, domains)
                        ?: BrowserPerception.formatLite(state, "CLICK_OK")
                }
                "browser_type" -> {
                    preAction(domains)?.let { return it }
                    if (!args.has("ref")) {
                        return "error: browser_type needs a ref (from browser_read)"
                    }
                    val ref = args.optInt("ref")
                    BrowserPerception.plainTypeRefusal(
                        BrowserSession.lastState?.element(ref)
                    )?.let { return it }
                    val state = BrowserSession.type(
                        context, ref, args.optString("text"), secret = false
                    )
                    domainGuard(state, domains)
                        ?: BrowserPerception.formatLite(state, "TYPE_OK")
                }
                "browser_type_secret" -> {
                    preAction(domains)?.let { return it }
                    if (!args.has("ref")) {
                        return "error: browser_type_secret needs a ref and a vault_key NAME"
                    }
                    val ref = args.optInt("ref")
                    val key = args.optString("vault_key")
                    if (!DeviceVault.validKeyName(key)) {
                        return "error: browser_type_secret needs a ref and a " +
                            "vault_key NAME (never a value)"
                    }
                    BrowserPerception.secretTypeRefusal(
                        BrowserSession.lastState?.element(ref)
                    )?.let { return it }
                    val value = vault.getSecret(key)
                        ?: return "error: no vault entry named $key — create it " +
                            "with vault_gen_secret first"
                    val state = BrowserSession.type(
                        context, ref, value, secret = true
                    )
                    // The value existed only inside the vault call
                    // and the WebView field. Never in this result.
                    domainGuard(state, domains)
                        ?: BrowserPerception.formatLite(
                            state,
                            "TYPE_SECRET_OK vault_key=$key (value typed from " +
                                "the vault; never shown)"
                        )
                }
                "browser_upload" -> {
                    preAction(domains)?.let { return it }
                    if (!args.has("ref")) {
                        return "error: browser_upload needs the file input's ref"
                    }
                    val file = resolveUploadFile(id, args.optString("path"))
                        ?: return "error: browser_upload path must be an app " +
                            "asset (asset:<name>) or a file inside the task sandbox"
                    BrowserSession.stageUpload(file)
                    val state = BrowserSession.clickRef(context, args.optInt("ref"))
                    if (BrowserSession.stagedUploadPending()) {
                        BrowserPerception.formatLite(
                            state,
                            "UPLOAD_STAGED file=${file.name} — the page has " +
                                "not opened its file chooser yet; click the " +
                                "page's upload control once and the staged " +
                                "file will be supplied to it"
                        )
                    } else {
                        domainGuard(state, domains)
                            ?: BrowserPerception.formatLite(
                                state, "UPLOAD_OK file=${file.name}"
                            )
                    }
                }
                "browser_screenshot" -> {
                    var shotName = args.optString("name", "shot.png")
                        .replace(Regex("[^A-Za-z0-9_/.-]"), "_")
                    if (!shotName.endsWith(".png")) shotName += ".png"
                    val f = BrowserSession.screenshot(
                        context, taskDir(id), shotName
                    )
                    // PRODUCED EVIDENCE (v1.0.14): a written
                    // screenshot is this task's work product.
                    if (f.isFile) {
                        store.update(id) {
                            PendingFinish.addProduced(it, shotName)
                        }
                    }
                    val ch = BrowserSession.lastState?.challenge
                    if (ch != null) {
                        BrowserPerception.challengeObservation(ch) +
                            "\n(page: ${BrowserSession.currentUrl()})"
                    } else {
                        "SCREENSHOT_OK path=$shotName bytes=${f.length()} " +
                            "url=${BrowserSession.currentUrl()}"
                    }
                }
                "browser_wait" -> {
                    val state = if (args.optString("text").isNotEmpty()) {
                        BrowserSession.waitText(
                            context, args.optString("text"),
                            args.optDouble("timeout", 30.0)
                        )
                    } else {
                        BrowserSession.waitSeconds(
                            context, args.optDouble("seconds", 2.0)
                        )
                    }
                    BrowserPerception.formatLite(state, "WAIT_OK")
                }
                UserHandoff.TOOL -> {
                    // Planned user step (v0.10.1): honoured ONLY
                    // on tasks whose packaged record carries the
                    // userHandoff flag — never on model request
                    // alone. The cleaned step is recorded on the
                    // task; the run loop sees the observation and
                    // parks at finish.
                    if (!UserHandoff.mayHandoff(task.optBoolean("userHandoff"))) {
                        "error: this task has no user step — the user " +
                            "never types in this task. Continue it yourself."
                    } else {
                        val domain = BrowserPerception.domainOf(
                            BrowserSession.currentUrl()
                        ).ifEmpty { "the site" }
                        val step = UserHandoff.cleanStep(
                            args.optString("step"), domain
                        )
                        store.update(id) { it.put("handoffStep", step) }
                        UserHandoff.OBSERVATION_PREFIX + " — the step " +
                            "is recorded. Call finish now: the task " +
                            "parks, the user takes the step in your " +
                            "browser session, and you resume after " +
                            "handback."
                    }
                }
                "vault_gen_secret" -> {
                    val key = args.optString("key")
                    if (!DeviceVault.validKeyName(key)) {
                        "error: vault key names must match [A-Za-z0-9_.-]{1,64}"
                    } else if (vault.genSecret(
                            key, args.optInt("length", 24)
                        )
                    ) {
                        "VAULT_OK key=$key stored in Lille's encrypted vault " +
                            "(the value is never shown; type it with " +
                            "browser_type_secret using this key name)"
                    } else {
                        "error: vault unavailable on this device"
                    }
                }
                "vault_record_account" -> {
                    val key = args.optString("vault_key")
                    if (!DeviceVault.validKeyName(key)) {
                        "error: vault_key must be a vault key name"
                    } else if (vault.recordAccount(
                            args.optString("site"),
                            args.optString("username"), key
                        )
                    ) {
                        // HER HANDLE (v1.0.8): recording her X
                        // account settles the handle the
                        // finish gate attributes sessions to —
                        // a later session read naming anyone
                        // else is a foreign session, never hers.
                        val site = args.optString("site")
                        val username = args.optString("username")
                        if (site.contains("x.com") &&
                            LilleXState.isCreationTask(task) &&
                            username.isNotEmpty()
                        ) {
                            store.update(id) {
                                it.put(
                                    FinishGate.HER_HANDLE_FIELD, username
                                )
                            }
                        }
                        "ACCOUNT_RECORDED site=${args.optString("site")} " +
                            "username=${args.optString("username")} " +
                            "vault_key=$key (metadata only — no secret " +
                            "stored in this record)"
                    } else {
                        "error: could not record the account metadata"
                    }
                }
                "mailbox_claim" -> mailboxClaim(id, task, args)
                "mailbox_switch" -> mailboxSwitch(id, task, args)
                "x_session_status" -> xSessionStatus(id, domains)
                "x_read_feed" -> xReadFeed(id, domains, args)
                "x_search" -> xSearch(id, domains, args)
                "x_post" -> xPost(id, task, domains, args)
                else -> "error: unknown tool: $name"
            }
        } catch (e: BrowserException) {
            "error: ${e.message}"
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Upload sources: `asset:<name>` (bundled app assets, e.g.
     *  her mascot) copied into the task dir, or a path inside the
     *  task sandbox. Nothing outside those two doors. */
    private fun resolveUploadFile(id: String, path: String): File? {
        val dir = taskDir(id)
        return try {
            if (path.startsWith("asset:")) {
                val name = path.removePrefix("asset:")
                    .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                if (name.isEmpty()) return null
                val out = File(dir, "uploads/$name").apply { parentFile?.mkdirs() }
                context.assets.open(name).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                out
            } else {
                val target = File(dir, path).canonicalFile
                if (target.path.startsWith(dir.canonicalFile.path) &&
                    target.isFile
                ) target else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- X lane (device) — mirrors browsertools.py ----------

    private fun xOpen(url: String, domains: List<String>): BrowserPerception.PageState {
        if (!BrowserPerception.domainAllowed(url, domains)) {
            throw BrowserException(
                "x.com is not in this task's browser allowlist — " +
                    "the task must authorise it"
            )
        }
        return BrowserSession.open(context, url, 4.0)
    }

    private fun xHandle(): String? {
        val v = BrowserSession.evalValue(context, BrowserPerception.X_HANDLE_JS)
        return BrowserPerception.parseHandle(v)
    }

    /** The three ways an identity read can land
     *  (v1.0.9): signed in as a named handle, signed
     *  out, or UNREADABLE — the page said neither, and
     *  the read says so instead of guessing. */
    private sealed class IdentityOutcome {
        data class SignedIn(val handle: String) : IdentityOutcome()
        object SignedOut : IdentityOutcome()
        data class Unreadable(val reason: String) : IdentityOutcome()
    }

    /** Stamp a CONCLUDED read on a record: the identity
     *  stamp plus the creation-attribution guard (a
     *  verified handle lands in xHandle only when it
     *  doesn't contradict a settled creation record, so
     *  someone else's session can never satisfy her
     *  finish gate). Shared by every read site. */
    private fun stampIdentityOn(
        t: JSONObject, handle: String?, now: Long
    ) {
        SessionIdentity.stamp(t, handle, now)
        if (handle != null) {
            val her = t.optString(FinishGate.HER_HANDLE_FIELD)
            if (!LilleXState.isCreationTask(t) ||
                her.isEmpty() || her.equals(handle, true)
            ) {
                t.put("xHandle", handle)
                t.put(FinishGate.X_HANDLE_AT_FIELD, now)
            }
        }
    }

    /** The session driver over the real WebView: the
     *  surface the navigation read (XIdentityRead) and
     *  the account routines (AccountSession) compose
     *  over. Navigation goes through xOpen, so the
     *  task's own allowlist governs every move. */
    private inner class SessionDriver(
        private val domains: List<String>
    ) : AccountSession.Driver {
        override fun currentState(): BrowserPerception.PageState =
            BrowserSession.read(context)

        override fun navigate(
            url: String
        ): BrowserPerception.PageState = xOpen(url, domains)

        override fun revealAccountMenu():
            BrowserPerception.PageState {
            try {
                BrowserSession.evalValue(
                    context,
                    BrowserPerception.X_OPEN_ACCOUNT_MENU_JS
                )
            } catch (e: Exception) {
                null
            }
            Thread.sleep(1_200)
            return BrowserSession.read(context)
        }

        override fun clickRef(
            ref: Int
        ): BrowserPerception.PageState =
            BrowserSession.clickRef(context, ref)

        override fun clickText(
            text: String
        ): BrowserPerception.PageState =
            BrowserSession.clickText(context, text)

        override fun type(
            ref: Int, value: String, secret: Boolean
        ): BrowserPerception.PageState =
            BrowserSession.type(context, ref, value, secret)
    }

    /**
     * THE CONCLUDING IDENTITY READ (v1.0.10 — identity
     * by navigation, [XIdentityRead]). 1.0.9 judged the
     * page's shape and on-device the tree never
     * testified (the assumed testids weren't there), so
     * reads landed unreadable or stale while the
     * browser sat signed in. Now the read NAVIGATES to
     * X's own-profile route and judges the landed URL —
     * X's own redirect is the witness — with the page
     * read kept as the corroborating fallback and as
     * the in-place read where navigation is forbidden
     * ([mayNavigate] false: a parked page is evidence;
     * a mid-signup flow page must survive a resume).
     * Callers position the browser first, as before.
     */
    private fun readXIdentityHere(
        domains: List<String>, mayNavigate: Boolean = true
    ): IdentityOutcome {
        val outcome = XIdentityRead.conclude(
            SessionDriver(domains), mayNavigate
        )
        return when (outcome) {
            is XIdentityRead.Outcome.SignedIn ->
                IdentityOutcome.SignedIn(outcome.handle)
            is XIdentityRead.Outcome.SignedOut ->
                IdentityOutcome.SignedOut
            is XIdentityRead.Outcome.Unreadable ->
                IdentityOutcome.Unreadable(outcome.reason)
        }
    }

    /** A concluding read that APPLIES itself: every
     *  conclusion stamps the record, syncs the
     *  registry and refreshes the session sense; an
     *  unreadable read stamps only the unreadable
     *  mark. The account routines (sign-out, sign-in,
     *  switch) and the acting-identity path read
     *  through this — conclusions are the only thing
     *  that moves the record, the registry or the
     *  sense, so a switch that fails halfway leaves
     *  all three reading the last verified truth. */
    private fun applyingRead(task: JSONObject): IdentityRead {
        val id = task.optString("id")
        val domains = browserDomains(task)
        return { mayNavigate ->
            val outcome = try {
                XIdentityRead.conclude(
                    SessionDriver(domains), mayNavigate
                )
            } catch (e: Exception) {
                XIdentityRead.Outcome.Unreadable(
                    "the sign-in check itself failed"
                )
            }
            val now = System.currentTimeMillis()
            when (outcome) {
                is XIdentityRead.Outcome.SignedIn -> {
                    store.update(id) {
                        stampIdentityOn(it, outcome.handle, now)
                    }
                    store.get(id)?.let {
                        syncAccountRegistry(it, outcome.handle)
                    }
                }
                is XIdentityRead.Outcome.SignedOut -> {
                    store.update(id) {
                        stampIdentityOn(it, null, now)
                    }
                    store.get(id)?.let {
                        syncAccountRegistry(it, null)
                    }
                }
                is XIdentityRead.Outcome.Unreadable ->
                    store.update(id) {
                        SessionIdentity.stampUnreadable(
                            it, outcome.reason, now
                        )
                    }
            }
            outcome
        }
    }

    /** The scripted sign-out (AccountSession) over
     *  this task's record, its conclusions applied as
     *  they land. */
    private fun runScriptedSignOut(
        task: JSONObject
    ): AccountSession.SignOutResult =
        try {
            AccountSession.signOut(
                SessionDriver(browserDomains(task)),
                applyingRead(task)
            )
        } catch (e: Exception) {
            AccountSession.SignOutResult.Failed(
                AccountSession.STEP_VERIFY_LOGOUT,
                "the sign-in check itself failed"
            )
        }

    /**
     * THE IDENTITY RE-CHECK PASS (v1.0.9): a read-only
     * pass over a record, run by TaskWorker's recheck
     * mode — the freshness rule's engine. It never runs
     * the task (no state transitions, no un-parking);
     * it positions the browser if the record's lane may
     * move it (a parked record's page is evidence and is
     * read in place), runs the concluding read, stamps
     * the outcome, syncs the registry, completes any
     * handback verification this read settles, and —
     * when a chat requested the check — lands the
     * verified answer in that chat from the record.
     * Returns true when the read concluded.
     */
    fun runIdentityRecheck(id: String): Boolean {
        val task = store.get(id) ?: return false
        if (TaskControl.isArchived(task) ||
            TaskControl.wasKilled(task) || !isXDomainTask(task)
        ) {
            clearRecheck(id)
            return false
        }
        val chatId = task.optString(SessionIdentity.RECHECK_CHAT_FIELD)
        val requestedAt = task.optLong(
            SessionIdentity.RECHECK_AT_FIELD, 0L
        )
        // A conclusion fresher than the request already
        // answers it — no second read needed.
        if (requestedAt > 0L &&
            !SessionIdentity.recheckRequested(task) &&
            SessionIdentity.checked(task) &&
            SessionIdentity.unreadableReason(task) == null
        ) {
            deliverRecheckAnswer(id, chatId, null)
            clearRecheck(id)
            return true
        }
        val wasHandbackPending = SessionIdentity.handbackPending(task)
        val parked = task.optString("state") ==
            TaskStates.PARKED_AT_WALL
        val currentUrl = BrowserSession.currentUrl()
        val domain = BrowserPerception.domainOf(currentUrl)
        val onX = domain == "x.com" || domain.endsWith(".x.com")
        // THE RESTING PAGE (v1.0.11, G6): the parked/
        // flow in-place protection extends to resting
        // pages — when the shared WebView is resting on
        // ANOTHER live task's page (a filled form, a
        // reading position), this read-only pass may
        // not navigate it away: it reads in place, and
        // where the resting page can't testify it lands
        // Unreadable — the deferred outcome the
        // watchdog retries — with the page untouched.
        val mayNavigate = !parked &&
            SessionIdentity.mayNavigateForRecheck(
                store.all(), id, currentUrl
            )
        val outcome: IdentityOutcome = if (!onX && parked) {
            // A parked page is evidence for a step that's
            // his — the re-check never navigates it away.
            IdentityOutcome.Unreadable(
                "the browser is parked away from X for a step that's yours"
            )
        } else {
            try {
                if (!onX && mayNavigate) {
                    xOpen("https://x.com/home", browserDomains(task))
                }
                // In place when parked (the page is
                // evidence) or when another task rests on
                // it; the navigation proof everywhere
                // else (v1.0.10).
                readXIdentityHere(
                    browserDomains(task), mayNavigate = mayNavigate
                )
            } catch (e: Exception) {
                IdentityOutcome.Unreadable(
                    "the sign-in check itself failed"
                )
            }
        }
        val now = System.currentTimeMillis()
        var handle: String? = null
        when (outcome) {
            is IdentityOutcome.SignedIn -> {
                handle = outcome.handle
                store.update(id) { t ->
                    stampIdentityOn(t, handle, now)
                    if (SessionIdentity.handbackPending(t)) {
                        t.put(
                            SessionIdentity.HANDBACK_VERIFIED_FIELD,
                            t.optLong("handedBackAt", 0L)
                        )
                    }
                }
                syncAccountRegistry(store.get(id) ?: task, handle)
            }
            is IdentityOutcome.SignedOut -> {
                store.update(id) { t ->
                    stampIdentityOn(t, null, now)
                    if (SessionIdentity.handbackPending(t)) {
                        t.put(
                            SessionIdentity.HANDBACK_VERIFIED_FIELD,
                            t.optLong("handedBackAt", 0L)
                        )
                    }
                }
                AccountRegistryStore(context)
                    .recordSignedOut(AccountsRegistry.PLATFORM_X)
            }
            is IdentityOutcome.Unreadable -> {
                store.update(id) { t ->
                    SessionIdentity.stampUnreadable(
                        t, outcome.reason, now
                    )
                }
            }
        }
        // A handback this read just verified gets its
        // announcement (once) in the ORIGIN chat — unless
        // the requesting chat IS the origin, whose answer
        // below is the announcement.
        val fresh = store.get(id) ?: task
        if (wasHandbackPending &&
            outcome is IdentityOutcome.SignedIn &&
            ChatTaskLink.isChatTask(fresh)
        ) {
            val handedBackAt = fresh.optLong("handedBackAt", 0L)
            val origin = fresh.optString(ChatTaskLink.ORIGIN_FIELD)
            if (origin.isNotEmpty() && origin != chatId &&
                fresh.optLong(
                    SessionIdentity.HANDBACK_ANNOUNCED_FIELD, 0L
                ) < handedBackAt
            ) {
                ChatStore(context).appendMessage(
                    origin, "lille",
                    SessionIdentity.handbackAnnouncement(
                        fresh, handle, challengePresent = false
                    )
                )
                store.update(id) { t ->
                    t.put(
                        SessionIdentity.HANDBACK_ANNOUNCED_FIELD,
                        handedBackAt
                    )
                }
            }
        }
        deliverRecheckAnswer(id, chatId, outcome)
        clearRecheck(id)
        return outcome !is IdentityOutcome.Unreadable
    }

    /** Drop a record's re-check request fields. */
    private fun clearRecheck(id: String) {
        store.update(id) { t ->
            t.remove(SessionIdentity.RECHECK_AT_FIELD)
            t.remove(SessionIdentity.RECHECK_CHAT_FIELD)
        }
    }

    /** Land a re-check's answer in the requesting chat:
     *  the record's own login answer for a concluded
     *  read, or the honest still-checking line for an
     *  unreadable one. No chat requested → nothing. */
    private fun deliverRecheckAnswer(
        id: String, chatId: String, outcome: IdentityOutcome?
    ) {
        if (chatId.isEmpty()) return
        val fresh = store.get(id) ?: return
        val text = if (outcome is IdentityOutcome.Unreadable) {
            "She checked just now and the sign-in still " +
                "couldn't be read from the page " +
                "(${outcome.reason}) — the check retries " +
                "on its own, and no older sign-in state " +
                "counts until one lands."
        } else {
            StatusProbe.loginAnswerFor(
                fresh, System.currentTimeMillis()
            )
        }
        ChatStore(context).appendMessage(chatId, "lille", text)
    }

    private fun xArticles(n: Int): List<BrowserPerception.Article> {
        val json = BrowserSession.evalValue(
            context, BrowserPerception.xArticlesJs(n)
        ) ?: return emptyList()
        return BrowserPerception.parseArticles(json)
    }

    private fun xSessionStatus(id: String, domains: List<String>): String {
        val state = xOpen("https://x.com/home", domains)
        state.challenge?.let {
            return BrowserPerception.challengeObservation(it) +
                "\n(page: ${state.url})"
        }
        // The concluding read (v1.0.10): the
        // navigation proof — X's own-profile route
        // names the session in its landed URL.
        val outcome = try {
            readXIdentityHere(domains)
        } catch (e: Exception) {
            IdentityOutcome.Unreadable(
                "the sign-in check itself failed"
            )
        }
        return when (outcome) {
            is IdentityOutcome.SignedIn -> {
                val handle = outcome.handle
                try {
                    File(taskDir(id), "x_session.json").writeText(
                        JSONObject().put("handle", handle)
                            .put("ts", System.currentTimeMillis()).toString(2)
                    )
                } catch (e: Exception) {
                    // The observation is the evidence; the file is a copy.
                }
                // IDENTITY (v1.0.8): the read stamps the
                // session identity on the record AND the
                // accounts registry. Attribution guard: on a
                // creation record whose handle is settled, a
                // session naming anyone else is a FOREIGN
                // session — it stamps signedInAs only, never
                // xHandle, so her gate can't be satisfied by
                // someone else's account.
                val nowMs = System.currentTimeMillis()
                store.update(id) { t ->
                    stampIdentityOn(t, handle, nowMs)
                }
                store.get(id)?.let { syncAccountRegistry(it, handle) }
                "X_SESSION logged_in as @$handle — session is live in " +
                    "Lille's persistent browser profile"
            }
            is IdentityOutcome.SignedOut -> {
                val nowMs = System.currentTimeMillis()
                store.update(id) { t ->
                    stampIdentityOn(t, null, nowMs)
                }
                AccountRegistryStore(context)
                    .recordSignedOut(AccountsRegistry.PLATFORM_X)
                "X_SESSION logged_out — no account is signed in in Lille's " +
                    "browser profile (page: ${state.url.take(80)})"
            }
            is IdentityOutcome.Unreadable -> {
                val nowMs = System.currentTimeMillis()
                store.update(id) { t ->
                    SessionIdentity.stampUnreadable(
                        t, outcome.reason, nowMs
                    )
                }
                "X_SESSION unreadable — ${outcome.reason}. Do " +
                    "not assume the sign-in either way; the " +
                    "check retries on its own."
            }
        }
    }

    /** The handle a task acts AS, when one is settled:
     *  the verified session handle, else her recorded
     *  handle (creation), else the connect lane's target.
     *  Null when the lane has no target yet — nothing to
     *  precondition against. */
    private fun targetHandleFor(task: JSONObject): String? {
        val live = task.optString("xHandle").trim()
        if (live.isNotEmpty()) return live
        val her = task.optString(FinishGate.HER_HANDLE_FIELD).trim()
        if (her.isNotEmpty()) return her
        val connect = task.optString("connectHandle").trim()
        if (connect.isNotEmpty()) return connect
        return null
    }

    /**
     * THE IDENTITY PRECONDITION — NOW IT ACTS
     * (v1.0.10, Fix 3.3). 1.0.8 stopped and named a
     * mismatch because no switching machinery existed;
     * now the machinery exists ([AccountSession]), so
     * the precondition EXECUTES the switch and only
     * stops when the switch itself fails, naming the
     * failed step:
     *
     * - The SENSED session first (the extension's
     *   rule): a fresh sense naming the target means
     *   the session is already the saved credential —
     *   the action proceeds with no read, no login
     *   step, no ask.
     * - A concluding read naming the target: proceed.
     * - A mismatch or a signed-out browser: switch.
     *   Target hers → the full autonomous switch
     *   (sign-out, then her vault sign-in). Target
     *   his → sign out, then the HANDOFF observation
     *   (the existing park machinery takes his login
     *   in the panel; his password is never
     *   attempted) — parked ONCE per expiry: if
     *   another task already waits on that sign-in,
     *   this action waits too instead of parking
     *   again.
     * - A wall mid-switch: the challenge observation
     *   (the loop parks it). An unreadable read or a
     *   failed step: the refusal, naming the step.
     *
     * Returns null to proceed, or the tool result to
     * return instead of acting.
     */
    private fun ensureActingIdentity(
        id: String, task: JSONObject, action: String
    ): String? {
        val target = targetHandleFor(task) ?: return null
        val senseStore = SessionSenseStore(context)
        val fresh = SessionSense.freshConclusion(
            senseStore.root(), AccountsRegistry.PLATFORM_X,
            store.all(), BrowserSession.lastActivityAt
        )
        if (fresh != null &&
            fresh.handle?.equals(target, true) == true
        ) {
            return null
        }
        val read = applyingRead(task)
        when (val o = read(true)) {
            is XIdentityRead.Outcome.SignedIn ->
                if (o.handle.equals(target, true)) return null
            is XIdentityRead.Outcome.Unreadable ->
                return "error: IDENTITY — $action is scoped " +
                    "to @$target, but the browser's sign-in " +
                    "could not be read (${o.reason}). No " +
                    "action runs on an unknown sign-in."
            is XIdentityRead.Outcome.SignedOut -> Unit
        }
        val registry = AccountRegistryStore(context)
        val targetIsHers =
            LilleXState.isCreationTask(task) ||
                task.optString("title") == XAccountTask.CONNECT_TITLE ||
                AccountsRegistry.find(
                    registry.root(),
                    AccountsRegistry.PLATFORM_X, target
                )?.optString("owner") ==
                    AccountsRegistry.OWNER_LILLE
        val password = if (targetIsHers) {
            vault.getSecret(LilleXState.X_PASSWORD_VAULT_KEY)
        } else {
            null
        }
        val result = try {
            AccountSession.switchTo(
                SessionDriver(browserDomains(task)),
                target, targetIsHers, password, read
            )
        } catch (e: Exception) {
            AccountSession.SwitchResult.Failed(
                AccountSession.STEP_VERIFY_LOGIN,
                "the sign-in check itself failed"
            )
        }
        return when (result) {
            is AccountSession.SwitchResult.Switched,
            is AccountSession.SwitchResult.AlreadyActive -> null
            is AccountSession.SwitchResult.NeedsUserLogin -> {
                if (AccountsRegistry.sessionLoginParked(
                        registry.root(),
                        AccountsRegistry.PLATFORM_X, target
                    )
                ) {
                    store.update(id) {
                        it.put(
                            "note",
                            "Waiting on the sign-in for " +
                                "@$target already parked in " +
                                "another task."
                        )
                    }
                    "error: IDENTITY — the browser is " +
                        "signed out, and a sign-in for " +
                        "@$target is already parked waiting " +
                        "on the user in another task. " +
                        "$action waits for that sign-in; " +
                        "do not park a second time."
                } else {
                    val step = BrowserControl.switchParkStep(target)
                    store.update(id) { it.put("handoffStep", step) }
                    registry.recordSessionLoginPark(
                        AccountsRegistry.PLATFORM_X, target,
                        System.currentTimeMillis()
                    )
                    UserHandoff.OBSERVATION_PREFIX +
                        " — $action is scoped to @$target; " +
                        "the browser has been signed out " +
                        "and is ready. $step"
                }
            }
            is AccountSession.SwitchResult.Walled ->
                BrowserPerception.challengeObservation(
                    BrowserPerception.Challenge(
                        result.kind, result.detail
                    )
                ) + "\n(page: ${BrowserSession.currentUrl()})"
            is AccountSession.SwitchResult.Failed ->
                "error: IDENTITY — switching to @$target " +
                    "failed at ${result.step} (${result.reason}). " +
                    "$action did not run under the wrong account."
        }
    }

    /**
     * CREATION-LANE UNBLOCK (v1.0.10, Fix 3.4): signup
     * needs a signed-out browser, and any live session
     * — his Connect session above all — silently
     * blocks it. At SIGNUP_FORM entry (the lane start
     * and the phase machine's step into it), a browser
     * signed in as ANYONE is signed out by the
     * scripted routine as a recorded step BEFORE the
     * signup script posts. Returns null when the way
     * is clear, or the stop-and-name instruction when
     * the sign-out itself failed — the lane never
     * starts a signup under someone else's session.
     */
    private fun ensureSignedOutForSignup(id: String): String? {
        val task = store.get(id) ?: return null
        if (!LilleXState.isCreationTask(task)) return null
        if (XCreationPhases.currentPhase(task) !=
            XCreationPhases.Phase.SIGNUP_FORM
        ) {
            return null
        }
        val read = applyingRead(task)
        val outcome = read(true)
        if (outcome !is XIdentityRead.Outcome.SignedIn) {
            return null
        }
        val who = outcome.handle
        val result = try {
            AccountSession.signOut(
                SessionDriver(browserDomains(task)), read
            )
        } catch (e: Exception) {
            AccountSession.SignOutResult.Failed(
                AccountSession.STEP_VERIFY_LOGOUT,
                "the sign-in check itself failed"
            )
        }
        val now = System.currentTimeMillis()
        return when (result) {
            is AccountSession.SignOutResult.Failed -> {
                store.update(id) {
                    it.put(
                        "note",
                        "The pre-signup sign-out failed at " +
                            "${result.step} (${result.reason}) — " +
                            "signup can't start under " +
                            "@$who's session."
                    )
                    EventLog.append(
                        it, "sign_out_failed", now,
                        JSONObject()
                            .put("step", result.step)
                            .put("reason", result.reason)
                    )
                }
                "SIGN-OUT BEFORE SIGNUP FAILED: the browser " +
                    "was signed in as @$who and the scripted " +
                    "sign-out failed at ${result.step} " +
                    "(${result.reason}). Do NOT start the " +
                    "signup under that session — it is not " +
                    "yours to use. Finish now, naming this " +
                    "failure exactly."
            }
            else -> {
                store.update(id) {
                    EventLog.append(
                        it, "sign_out", now,
                        JSONObject()
                            .put("from", who)
                            .put("reason", "pre_signup")
                    )
                }
                null
            }
        }
    }

    /**
     * THE SESSION PASS (v1.0.10): the spoken session
     * commands' execution — "log out of X", "switch to
     * @…" — run by TaskWorker's account-session mode
     * under the browser gate. The routines are
     * scripted (AccountSession); every outcome lands
     * as ONE deterministic line in the record's origin
     * chat, composed from the verified result
     * (BrowserControl's line composers), never from
     * the taps. A switch to HIS account parks the
     * record on the hand-back step; a wall parks it as
     * a wall. The record ends COMPLETED when it exists
     * for the command, PAUSED (with the outcome as its
     * note) when the command borrowed a lane's record.
     * A record mid-run is never raced: the pass lands
     * the busy line and stops.
     */
    fun runAccountSession(
        id: String, action: String, target: String?
    ) {
        val task = store.get(id) ?: return
        if (TaskControl.isArchived(task) ||
            TaskControl.wasKilled(task)
        ) {
            return
        }
        val chatId = task.optString(ChatTaskLink.ORIGIN_FIELD)
        fun land(text: String) {
            if (chatId.isNotEmpty()) {
                ChatStore(context).appendMessage(chatId, "lille", text)
            }
        }
        val state = task.optString("state")
        if (state == TaskStates.RUNNING ||
            TaskStates.isTerminal(state)
        ) {
            land(BrowserControl.SESSION_BUSY_LINE)
            return
        }
        if (state == TaskStates.QUEUED ||
            state == TaskStates.PAUSED ||
            state == TaskStates.WAITING_NETWORK ||
            state == TaskStates.WAITING_ROUTE
        ) {
            store.update(id, TaskStates.RUNNING) { }
        }
        val fresh = store.get(id) ?: task
        val sessionCommand =
            fresh.optBoolean(BrowserControl.SESSION_COMMAND_FIELD)
        var parked = false
        var outcomeLine: String? = null
        when (action) {
            TaskWorker.ACTION_SIGN_OUT -> {
                val result = runScriptedSignOut(fresh)
                outcomeLine = BrowserControl.signOutLine(result)
            }
            TaskWorker.ACTION_SWITCH_HER -> {
                val handle = target ?: ""
                val result = try {
                    AccountSession.switchTo(
                        SessionDriver(browserDomains(fresh)),
                        handle, true,
                        vault.getSecret(
                            LilleXState.X_PASSWORD_VAULT_KEY
                        ),
                        applyingRead(fresh)
                    )
                } catch (e: Exception) {
                    AccountSession.SwitchResult.Failed(
                        AccountSession.STEP_VERIFY_LOGIN,
                        "the sign-in check itself failed"
                    )
                }
                if (result is AccountSession.SwitchResult.Walled) {
                    park(
                        id,
                        BrowserPerception.Challenge(
                            result.kind, result.detail
                        ),
                        JSONArray(), steps = 1
                    )
                    parked = true
                }
                outcomeLine = BrowserControl.switchLine(result, handle)
            }
            TaskWorker.ACTION_SWITCH_HIS -> {
                val handle = target ?: ""
                val result = try {
                    AccountSession.switchTo(
                        SessionDriver(browserDomains(fresh)),
                        handle, false, null,
                        applyingRead(fresh)
                    )
                } catch (e: Exception) {
                    AccountSession.SwitchResult.Failed(
                        AccountSession.STEP_VERIFY_LOGIN,
                        "the sign-in check itself failed"
                    )
                }
                if (result is AccountSession.SwitchResult
                        .NeedsUserLogin
                ) {
                    val step =
                        BrowserControl.switchParkStep(handle)
                    val now = System.currentTimeMillis()
                    val wall = JSONObject()
                        .put("domain", "x.com")
                        .put("kind", UserHandoff.WALL_KIND)
                        .put("detail", step)
                        .put("step", 1)
                        .put("at", now)
                    store.update(
                        id, TaskStates.PARKED_AT_WALL
                    ) {
                        it.put("wall", wall)
                        it.put("wallStep", step)
                        it.put("userHandoffDone", true)
                        it.put(
                            "note",
                            "Waiting on your X sign-in " +
                                "as @$handle."
                        )
                    }
                    AccountRegistryStore(context)
                        .recordSessionLoginPark(
                            AccountsRegistry.PLATFORM_X,
                            handle, now
                        )
                    WallNotify.notifyParked(context, id, step)
                    parked = true
                }
                outcomeLine = BrowserControl.switchLine(result, handle)
            }
            else -> outcomeLine =
                "That session command isn't one she can run."
        }
        outcomeLine?.let { land(it) }
        if (parked) return
        val endNote = outcomeLine
        if (sessionCommand) {
            store.update(id, TaskStates.COMPLETED) {
                if (endNote != null) it.put("note", endNote)
            }
        } else {
            store.update(id, TaskStates.PAUSED) {
                if (endNote != null) it.put("note", endNote)
            }
        }
    }

    private fun xReadFeed(
        id: String, domains: List<String>, args: JSONObject
    ): String {
        val n = (args.optInt("count", 10)).coerceIn(1, 20)
        // Identity FIRST (v1.0.10): a read-as is an
        // account-scoped action. When this task has a
        // target account, the acting precondition makes
        // the session be it before the feed opens under
        // anyone's name — switching when it can, parking
        // or refusing (naming why) when it can't.
        store.get(id)?.let { t ->
            if (targetHandleFor(t) != null) {
                ensureActingIdentity(id, t, "x_read_feed")
                    ?.let { return it }
            }
        }
        val state = xOpen("https://x.com/home", domains)
        state.challenge?.let {
            return BrowserPerception.challengeObservation(it) +
                "\n(page: ${state.url})"
        }
        val feedHandle = xHandle()
        if (feedHandle == null) {
            return "error: X_FEED unavailable — not logged in. " +
                "x_session_status explains the session state."
        }
        var posts = xArticles(n)
        if (posts.size < n) {
            BrowserSession.evalValue(context, BrowserPerception.SCROLL_JS)
            Thread.sleep(2_000)
            val more = xArticles(n)
            if (more.size > posts.size) posts = more
        }
        // Evidence for the graded report (verify-by-state): a
        // feed criterion is met by this recorded read, not by
        // the model's later summary.
        store.update(id) {
            it.put("feedEvidence", "X_FEED_OK posts=${posts.size}")
        }
        return BrowserPerception.formatPosts("X_FEED_OK posts=${posts.size}", posts)
    }

    private fun xSearch(
        id: String, domains: List<String>, args: JSONObject
    ): String {
        val q = args.optString("query").trim()
        if (q.isEmpty()) return "error: x_search needs a query"
        val n = (args.optInt("count", 10)).coerceIn(1, 20)
        val url = "https://x.com/search?q=" +
            URLEncoder.encode(q, "UTF-8") + "&src=typed_query"
        // Identity FIRST (v1.0.10): search results are
        // read as the signed-in account — when this
        // task has a target, the acting precondition
        // makes the session be it before the search
        // opens under anyone's name.
        store.get(id)?.let { t ->
            if (targetHandleFor(t) != null) {
                ensureActingIdentity(id, t, "x_search")
                    ?.let { return it }
            }
        }
        val state = xOpen(url, domains)
        state.challenge?.let {
            return BrowserPerception.challengeObservation(it) +
                "\n(page: ${state.url})"
        }
        val posts = xArticles(n)
        store.update(id) {
            it.put("searchEvidence", "X_SEARCH_OK results=${posts.size} query='$q'")
        }
        return BrowserPerception.formatPosts(
            "X_SEARCH_OK results=${posts.size} query='$q'", posts
        )
    }

    private fun xPost(
        id: String, task: JSONObject, domains: List<String>, args: JSONObject
    ): String {
        val postText = args.optString("text").trim()
        if (postText.isEmpty()) return "error: x_post needs text"
        if (postText.length > 280) {
            return "error: post is ${postText.length} chars (X limit 280)"
        }
        // Sentinel gates (deterministic, same as the host lane):
        // own account posts within policy; Ethuram's account only
        // with the task's explicit approval; anything else denied.
        val mode = task.optString("xPostMode")
        when (mode) {
            "own" -> Unit
            "user" -> if (!task.optBoolean("xPostApproved", false)) {
                return "error: SENTINEL — posting on Ethuram's account " +
                    "requires the task's explicit approval " +
                    "(post approved: yes). Default is deny."
            }
            else -> return "error: SENTINEL — this task does not authorise " +
                "x_post (no X lane section). Default is deny."
        }
        val low = postText.lowercase()
        for (bad in listOf("giveaway", "giving away", "win $", "prize pool", "airdrop")) {
            if (low.contains(bad)) {
                return "error: COPY_RULE — the post mentions '$bad'; Lille " +
                    "never posts invented offers, prizes or commitments " +
                    "(Ethuram's rule). Rewrite it."
            }
        }
        if (mode == "own" && postText.contains("₿itcoin or nothing")) {
            return "error: COPY_RULE — '₿itcoin or nothing' is " +
                "@Bitcoinornatin's branding; it must not appear on " +
                "Lille's own page."
        }
        val waitMs = X_POST_MIN_INTERVAL_MS -
            (System.currentTimeMillis() - lastPostTs)
        if (waitMs > 0) Thread.sleep(waitMs)

        // Identity FIRST (v1.0.10): a post runs ONLY
        // under the task's own account — the acting
        // precondition makes the session be it before
        // the composer opens under anyone's name.
        ensureActingIdentity(id, task, "x_post")
            ?.let { return it }
        val state = xOpen("https://x.com/home", domains)
        state.challenge?.let {
            return BrowserPerception.challengeObservation(it) +
                "\n(page: ${state.url})"
        }
        val handle = xHandle()
            ?: return "error: X_POST unavailable — not logged in."
        val composer = BrowserSession.lastState?.elements
            ?.firstOrNull { it.testid == "tweetTextarea_0" }
            ?: return "error: X_POST — composer box not found on the page"
        BrowserSession.clickRef(context, composer.ref)
        BrowserSession.type(context, composer.ref, postText, secret = false)
        Thread.sleep(1_000)
        val clicked = BrowserSession.evalValue(
            context, BrowserPerception.X_CLICK_POST_BUTTON_JS
        )
        if (clicked != "clicked") {
            return "error: X_POST — the Post button was not clickable"
        }
        Thread.sleep(3_000)
        // Verify by state: the new post must be findable, with a
        // status URL, on the profile timeline (newest first).
        xOpen("https://x.com/$handle", domains)
        val posts = xArticles(5)
        val probe = postText.take(40).lowercase()
        for (p in posts) {
            if (p.text.lowercase().contains(probe) && p.url.isNotEmpty()) {
                lastPostTs = System.currentTimeMillis()
                store.update(id) { it.put("firstPostUrl", p.url) }
                return "X_POST_OK url=${p.url} — posted as @$handle and " +
                    "verified on the profile timeline"
            }
        }
        return "X_POST_UNVERIFIED — the Post button was clicked but the " +
            "post could not be found on the profile timeline; treating " +
            "as not posted (honesty rule). Check with x_read_feed " +
            "before retrying."
    }

    // ---------- v1.0.8 lane machinery ----------
    // Identity stamps, the accounts registry, account
    // separation, the creation phase machine, and the
    // loop breaker. Everything here judges the RECORD
    // and the PAGE — never the model's narration.

    private val BROWSER_STEP_TOOLS = setOf(
        "browser_open", "browser_read", "browser_click",
        "browser_click_mark", "browser_type",
        "browser_type_secret", "browser_upload",
        "browser_wait", "browser_screenshot"
    )

    /** True when the task's browser allowlist carries
     *  x.com — the X lanes (hers to create/connect, the
     *  user's to connect, and general tasks the job
     *  itself authorised X for). */
    private fun isXDomainTask(task: JSONObject): Boolean =
        browserDomains(task).any {
            it == "x.com" || it.endsWith(".x.com")
        }

    /** The user's own handle as a connect lane verified
     *  it — the creation lane's separation check needs
     *  to know which handle is HIS. */
    private fun newestConnectHandle(): String? =
        store.all()
            .filter { it.optString("title") == XConnectTask.TITLE }
            .maxByOrNull { it.optLong("updatedAt", 0L) }
            ?.optString("xHandle")?.trim()?.ifEmpty { null }

    /** Sync a verified page read into the accounts
     *  registry: register + mark active, or mark the
     *  platform signed out. When the active account
     *  CHANGED, the switch is stamped on this task as a
     *  recorded step (the event log), and the newly
     *  active entry's lastActiveAt has already moved in
     *  the registry itself. */
    private fun syncAccountRegistry(task: JSONObject, handle: String?) {
        // CONTINUOUS SESSION AWARENESS (v1.0.10): a
        // concluding read is a sense update too — the
        // sense, the contradiction pass over the other
        // records and the unprompted announcement all
        // ride this sync.
        recordSensedRead(
            context, handle, BrowserSession.currentUrl()
        )
        val registry = AccountRegistryStore(context)
        if (handle == null) {
            registry.recordSignedOut(AccountsRegistry.PLATFORM_X)
            return
        }
        val owner = if (LilleXState.isCreationTask(task)) {
            AccountsRegistry.OWNER_LILLE
        } else {
            AccountsRegistry.OWNER_USER
        }
        val previous = registry.recordVerifiedSignIn(
            AccountsRegistry.PLATFORM_X, handle, owner,
            System.currentTimeMillis()
        )
        if (previous != null) {
            val id = task.optString("id")
            if (id.isNotEmpty()) {
                store.update(id) {
                    EventLog.append(
                        it, "account_switch",
                        System.currentTimeMillis(),
                        JSONObject().put("from", previous)
                            .put("to", handle)
                    )
                }
            }
        }
    }

    /**
     * RUN START for a packaged X lane: (1) the identity
     * stamp — the lane reads the signed-in handle off
     * x.com/home and stamps it (or explicit signed-out)
     * before her first step; (2) ACCOUNT SEPARATION on
     * the creation lane — a foreign session is named on
     * the record and the sign-out branch is instructed,
     * once per run; an unattributed session gets the
     * settle-it instruction; (3) the creation lane's
     * PHASE SCRIPT for its current phase, posted once
     * per phase entry. The registry syncs from the same
     * read.
     */
    private fun startPackagedLane(id: String, messages: JSONArray) {
        val task = store.get(id) ?: return
        val domains = browserDomains(task)
        // The identity read: in place when the browser
        // already rests on x.com (a mid-signup flow page
        // must survive a resume untouched), else via
        // x.com/home — and CONCLUDING (v1.0.9): the
        // 1.0.8 read stamped signed-out whenever the
        // handle wasn't in the tree, including on a
        // signed-in page whose handle sits behind the
        // account menu. An unconcluded read now stamps
        // unreadable and instructs accordingly — the
        // lane never separates accounts on a guess.
        // CREATION-LANE UNBLOCK (v1.0.10): at
        // SIGNUP_FORM entry, a browser signed in as
        // anyone (his Connect session above all) is
        // signed out by the scripted routine BEFORE
        // the phase script posts; a failed sign-out
        // replaces the script with the stop-and-name
        // instruction below.
        val signupBlock = ensureSignedOutForSignup(id)
        val outcome = try {
            val currentUrl = BrowserSession.currentUrl()
            val currentDomain = BrowserPerception.domainOf(
                currentUrl
            )
            if (currentDomain != "x.com" &&
                !currentDomain.endsWith(".x.com")
            ) {
                xOpen("https://x.com/home", domains)
            }
            // A flow page (signup, login) is a lane in
            // motion: read it in place so the flow
            // survives; every other position takes the
            // navigation proof (v1.0.10).
            readXIdentityHere(
                domains,
                mayNavigate = !currentUrl.contains("/i/flow/")
            )
        } catch (e: Exception) {
            IdentityOutcome.Unreadable(
                "the sign-in check itself failed"
            )
        }
        val handle = (outcome as? IdentityOutcome.SignedIn)?.handle
        val now = System.currentTimeMillis()
        var instruction: String? = null
        store.update(id) { t ->
            when (outcome) {
                is IdentityOutcome.Unreadable -> {
                    SessionIdentity.stampUnreadable(
                        t, outcome.reason, now
                    )
                    instruction =
                        SessionIdentity.unreadableInstruction(
                            outcome.reason
                        )
                }
                else -> {
                    stampIdentityOn(t, handle, now)
                    // A concluded lane-start read also
                    // settles a pending handback (the
                    // panel's user-hold handback has no
                    // wall block to verify it): the read
                    // IS the verification.
                    if (SessionIdentity.handbackPending(t)) {
                        t.put(
                            SessionIdentity.HANDBACK_VERIFIED_FIELD,
                            t.optLong("handedBackAt", 0L)
                        )
                    }
                }
            }
            if (LilleXState.isCreationTask(t)) {
                // Account separation judges the FRESH
                // stamp — only a concluded read may feed
                // it (an unreadable read left the old
                // stamp in place, and separating on it
                // would be separating on a guess).
                val owner = if (
                    outcome !is IdentityOutcome.Unreadable
                ) {
                    SessionIdentity.ownerFor(
                        t, newestConnectHandle()
                    )
                } else {
                    SessionIdentity.Owner.UNCHECKED
                }
                val signed = if (
                    outcome !is IdentityOutcome.Unreadable
                ) {
                    SessionIdentity.signedInAs(t)
                } else {
                    null
                }
                if (signed != null &&
                    t.optLong("identityNoteAt", 0L) < now
                ) {
                    when (owner) {
                        SessionIdentity.Owner.OTHER -> {
                            t.put("identityNoteAt", now)
                            t.put(
                                "note",
                                SessionIdentity.foreignSessionLine(signed)
                            )
                            EventLog.append(
                                t, "identity_mismatch", now,
                                JSONObject().put("signedInAs", signed)
                            )
                            instruction =
                                SessionIdentity.foreignSessionInstruction(
                                    signed
                                )
                        }
                        SessionIdentity.Owner.UNKNOWN -> {
                            t.put("identityNoteAt", now)
                            instruction =
                                SessionIdentity.unknownSessionInstruction(
                                    signed
                                )
                        }
                        else -> Unit
                    }
                }
                val phase = XCreationPhases.currentPhase(t)
                if (t.optString(XCreationPhases.NOTE_FIELD) !=
                    phase.name
                ) {
                    t.put(XCreationPhases.NOTE_FIELD, phase.name)
                    val script = XCreationPhases.scriptFor(t, phase)
                    instruction = listOfNotNull(instruction, script)
                        .joinToString("\n")
                }
            }
        }
        // The registry syncs only on a CONCLUSION — an
        // unreadable read must not mark the platform
        // signed out (that was the 1.0.8 conflation,
        // one level down).
        if (outcome !is IdentityOutcome.Unreadable) {
            syncAccountRegistry(store.get(id) ?: task, handle)
        }
        if (signupBlock != null) instruction = signupBlock
        instruction?.let {
            messages.put(
                JSONObject().put("role", "user").put("content", it)
            )
        }
    }

    private data class PhaseAdvance(
        val advanced: Boolean, val inject: String?
    )

    /**
     * The phase machine's ears: after each browser step
     * on the creation lane, judge the step's evidence
     * and stamp any checkpoint it earned. Exits are
     * earned by evidence the RUNNER can see — the
     * committed address typed into the form, X's own
     * answer asking for the code, the code typed on X,
     * the live session — and a later proof cascades the
     * earlier checkpoints it implies (a live session
     * proves the form and the code happened). Also the
     * EMAIL_VERIFY poll counter: mailbox visits are
     * polls, and exhausting the budget stamps the
     * evidence a mailbox switch stands on.
     */
    private fun advanceCreationPhases(
        id: String, name: String, args: JSONObject,
        observation: String, now: Long
    ): PhaseAdvance {
        var advanced = false
        var inject: String? = null
        val domain = BrowserPerception.domainOf(
            BrowserSession.lastState?.url ?: ""
        )
        store.update(id) { t ->
            if (!LilleXState.isCreationTask(t)) return@update
            val phase = XCreationPhases.currentPhase(t)
            if (domain == "x.com") {
                if (name == "browser_type") {
                    val typed = args.optString("text").trim()
                    if (typed.isNotEmpty() &&
                        typed == XCreationPhases.committedAddress(t)
                    ) {
                        t.put("signupEmailTyped", true)
                    }
                    // Auto-commit: the address first seen
                    // typed into the signup form IS the
                    // commitment, claim call or not.
                    if (XCreationPhases.committedAddress(t).isEmpty() &&
                        XCreationPhases.isEmailShaped(typed) &&
                        XCreationPhases.commitAddress(t, typed, now)
                    ) {
                        advanced = true
                        if (t.optString("mailboxPageDomain").isEmpty()) {
                            t.put(
                                "mailboxPageDomain",
                                typed.substringAfter("@").lowercase()
                            )
                        }
                    }
                    // EMAIL_VERIFY exit: the code, typed on X.
                    val codeShaped = typed.length in 4..8 &&
                        typed.all { it.isLetterOrDigit() }
                    if (codeShaped &&
                        phase == XCreationPhases.Phase.EMAIL_VERIFY
                    ) {
                        XCreationPhases.stamp(
                            t, XCreationPhases.Phase.EMAIL_VERIFY,
                            "verification code entered on X (the " +
                                "code itself is never recorded)",
                            now
                        )
                        advanced = true
                    }
                }
                if (name == "browser_type_secret") {
                    t.put("signupSecretTyped", true)
                }
                // SIGNUP_FORM exit, from the page's own
                // answer: X asks for the code — the form
                // was submitted and accepted.
                if (phase == XCreationPhases.Phase.SIGNUP_FORM) {
                    val obs = observation.lowercase()
                    if (obs.contains("verification code") ||
                        obs.contains("enter the code") ||
                        obs.contains("sent a code") ||
                        obs.contains("check your email")
                    ) {
                        XCreationPhases.stamp(
                            t, XCreationPhases.Phase.SIGNUP_FORM,
                            "X answered the submit by asking " +
                                "for the verification code",
                            now
                        )
                        advanced = true
                    }
                }
            }
            // PASSWORD_PROFILE exit: a live session as her
            // handle exists on the record (stamped by the
            // session reads) — the account itself is
            // proven, and the earlier phases it implies
            // cascade with it.
            val handleNow = t.optString("xHandle").trim()
            if (handleNow.isNotEmpty() &&
                !XCreationPhases.isComplete(
                    t, XCreationPhases.Phase.PASSWORD_PROFILE
                )
            ) {
                if (!XCreationPhases.isComplete(
                        t, XCreationPhases.Phase.SIGNUP_FORM
                    )
                ) {
                    XCreationPhases.stamp(
                        t, XCreationPhases.Phase.SIGNUP_FORM,
                        "proven by the live session as @$handleNow",
                        now
                    )
                }
                if (!XCreationPhases.isComplete(
                        t, XCreationPhases.Phase.EMAIL_VERIFY
                    )
                ) {
                    XCreationPhases.stamp(
                        t, XCreationPhases.Phase.EMAIL_VERIFY,
                        "proven by the live session as @$handleNow",
                        now
                    )
                }
                XCreationPhases.stamp(
                    t, XCreationPhases.Phase.PASSWORD_PROFILE,
                    "session verified as @$handleNow", now
                )
                advanced = true
            }
            // EMAIL_VERIFY polls: a visit to a mailbox
            // domain is a poll of the committed inbox.
            if (phase == XCreationPhases.Phase.EMAIL_VERIFY &&
                (name == "browser_open" || name == "browser_read") &&
                domain.isNotEmpty() && domain != "x.com" &&
                !domain.endsWith(".x.com")
            ) {
                val polls =
                    t.optInt(XCreationPhases.POLLS_FIELD, 0) + 1
                t.put(XCreationPhases.POLLS_FIELD, polls)
                if (polls >= XCreationPhases.POLL_BUDGET &&
                    !t.optBoolean(XCreationPhases.POLLS_EXHAUSTED_FIELD)
                ) {
                    t.put(XCreationPhases.POLLS_EXHAUSTED_FIELD, true)
                    LoopGuard.reset(t)
                    EventLog.append(
                        t, "code_poll_exhausted", now,
                        JSONObject().put("polls", polls)
                    )
                    inject = "The verification code has not " +
                        "arrived after $polls polls of the " +
                        "committed inbox — that evidence is " +
                        "now on the record. You may call " +
                        "mailbox_switch{reason: " +
                        "\"${XCreationPhases.REASON_CODE_NEVER_ARRIVED}\"} " +
                        "ONCE to change the mailbox, or keep " +
                        "waiting for the code here. Do not " +
                        "start shopping providers on your own."
                }
            }
        }
        return PhaseAdvance(advanced, inject)
    }

    /**
     * After every packaged browser step: the phase
     * machine's checkpoints and next-phase script, the
     * finish gate's profile evidence, and the loop
     * breaker. Returns a terminal state when the lane
     * parks (loop trip with no alternate branch), else
     * null to continue.
     */
    private fun postPackagedStep(
        id: String, name: String, args: JSONObject,
        observation: String, stepsDone: Int,
        messages: JSONArray, windowSteps: Int
    ): String? {
        val now = System.currentTimeMillis()
        var fresh = store.get(id) ?: return null
        val isCreation = LilleXState.isCreationTask(fresh)
        var advanced = false
        var inject: String? = null
        if (isCreation) {
            val result = advanceCreationPhases(
                id, name, args, observation, now
            )
            advanced = result.advanced
            inject = result.inject
            fresh = store.get(id) ?: return null
            val phase = XCreationPhases.currentPhase(fresh)
            if (fresh.optString(XCreationPhases.NOTE_FIELD) !=
                phase.name
            ) {
                // CREATION-LANE UNBLOCK (v1.0.10): the
                // step INTO signup signs out any live
                // session first, as a recorded step; a
                // failed sign-out replaces the script
                // with the stop-and-name instruction and
                // the phase note stays unstamped, so the
                // check re-runs on the next step.
                val signupBlock = if (
                    phase == XCreationPhases.Phase.SIGNUP_FORM
                ) {
                    ensureSignedOutForSignup(id)
                } else {
                    null
                }
                if (signupBlock != null) {
                    inject = listOfNotNull(inject, signupBlock)
                        .joinToString("\n")
                } else {
                    val script =
                        XCreationPhases.scriptFor(fresh, phase)
                    store.update(id) {
                        it.put(XCreationPhases.NOTE_FIELD, phase.name)
                    }
                    inject = listOfNotNull(inject, script)
                        .joinToString("\n")
                }
            }
        }
        // The gate's profile evidence: her profile page
        // observed loading as hers (the session's resting
        // page IS the profile URL for her handle).
        if (FinishGate.isGated(fresh)) {
            val handle = fresh.optString("xHandle")
            val url = BrowserSession.lastState?.url ?: ""
            if (handle.isNotEmpty() &&
                fresh.optString(FinishGate.PROFILE_EVIDENCE_FIELD)
                    .isEmpty() &&
                FinishGate.isProfileUrl(url, handle)
            ) {
                store.update(id) {
                    it.put(FinishGate.PROFILE_EVIDENCE_FIELD, url)
                }
                fresh = store.get(id) ?: fresh
            }
        }
        if (name in BROWSER_STEP_TOOLS) {
            val key = if (isCreation) {
                "phase:" + XCreationPhases.currentPhase(fresh).name
            } else {
                LoopGuard.clusterFor(
                    BrowserSession.lastState?.url
                        ?: args.optString("url")
                )
            }
            var verdict: LoopGuard.Verdict? = null
            store.update(id) {
                verdict = LoopGuard.noteStep(it, key, advanced)
            }
            val v = verdict
            if (v != null && v.tripped) {
                fresh = store.get(id) ?: fresh
                val alternate = isCreation &&
                    XCreationPhases.currentPhase(fresh) ==
                    XCreationPhases.Phase.MAILBOX &&
                    XCreationPhases.committedAddress(fresh).isEmpty()
                if (alternate) {
                    // The phase script's alternate branch:
                    // the next provider in the ladder, with
                    // the loop evidence stamped.
                    store.update(id) {
                        LoopGuard.stampTrip(it, v, now)
                        LoopGuard.reset(it)
                    }
                    inject = listOfNotNull(
                        inject,
                        "LOOP BREAKER: you have circled the " +
                            "mailbox providers for ${v.total} " +
                            "steps without the MAILBOX phase " +
                            "advancing. Take the alternate " +
                            "branch NOW: move to the NEXT " +
                            "provider in the ladder, take its " +
                            "address, and claim it with " +
                            "mailbox_claim. Do not revisit the " +
                            "providers you have already circled."
                    ).joinToString("\n")
                } else {
                    store.update(id, TaskStates.PAUSED) {
                        LoopGuard.stampTrip(it, v, now)
                        it.put("note", LoopGuard.parkNote(v))
                        it.put("steps", stepsDone)
                        it.put("messages", messages)
                        it.put(StepBudget.WINDOW_FIELD, windowSteps)
                    }
                    return TaskStates.PAUSED
                }
            }
        }
        if (inject != null) {
            messages.put(
                JSONObject().put("role", "user").put("content", inject)
            )
            store.update(id) { it.put("messages", messages) }
        }
        return null
    }

    /** The completed-phase refusal: on the creation
     *  lane, mailbox ground belongs to the MAILBOX phase
     *  (and to EMAIL_VERIFY's polls). Once the run has
     *  moved past both, opening a mailbox restarts a
     *  finished phase — refused, with the committed
     *  address named. */
    private fun completedPhaseRefusal(id: String, url: String): String? {
        val task = store.get(id) ?: return null
        if (!LilleXState.isCreationTask(task)) return null
        val domain = BrowserPerception.domainOf(url)
        if (domain == "x.com" || domain.endsWith(".x.com")) {
            return null
        }
        return when (XCreationPhases.currentPhase(task)) {
            XCreationPhases.Phase.MAILBOX,
            XCreationPhases.Phase.EMAIL_VERIFY -> null
            else -> "error: PHASE — the mailbox phase is " +
                "complete and this run's address is " +
                XCreationPhases.committedAddress(task) +
                " (now in phase " +
                XCreationPhases.currentPhase(task).name +
                "). Opening $domain restarts a finished " +
                "phase. Work within the current phase; the " +
                "address changes only through mailbox_switch " +
                "with recorded evidence."
        }
    }

    /** mailbox_claim: commit the run's ONE address —
     *  the phase machine's MAILBOX exit. The address
     *  must be email-shaped and at one of the task's
     *  allowlisted mailbox providers (it was read off
     *  that provider's page); a second claim against a
     *  different address is refused — changes travel
     *  through mailbox_switch, on evidence, once. */
    private fun mailboxClaim(
        id: String, task: JSONObject, args: JSONObject
    ): String {
        if (!LilleXState.isCreationTask(task)) {
            return "error: mailbox_claim exists only on her " +
                "X creation lane."
        }
        val address = args.optString("address").trim()
        if (!XCreationPhases.isEmailShaped(address)) {
            return "error: mailbox_claim needs the email " +
                "address you read off the mailbox page."
        }
        val domain = address.substringAfter("@").lowercase()
        val domains = browserDomains(task)
        if (!domains.any { it.equals(domain, true) }) {
            return "error: the address's domain ($domain) is " +
                "not one of this task's mailbox providers " +
                "(${domains.joinToString(", ")}). Claim the " +
                "address the provider's page gave you."
        }
        val fresh = store.get(id) ?: task
        val committed = XCreationPhases.committedAddress(fresh)
        if (committed.isNotEmpty()) {
            return if (committed.equals(address, true)) {
                "MAILBOX_ALREADY_COMMITTED address=$committed " +
                    "— that is the run's address; carry on to " +
                    "the signup form."
            } else {
                "error: an address is already committed for " +
                    "this run ($committed). It changes only " +
                    "through mailbox_switch with recorded " +
                    "evidence — not by claiming another."
            }
        }
        val pageDomain = BrowserPerception.domainOf(
            BrowserSession.lastState?.url ?: ""
        )
        var ok = false
        store.update(id) { t ->
            ok = XCreationPhases.commitAddress(
                t, address, System.currentTimeMillis()
            )
            if (ok && pageDomain.isNotEmpty()) {
                t.put("mailboxPageDomain", pageDomain)
            }
        }
        return if (ok) {
            "MAILBOX_CLAIMED address=$address — committed " +
                "for the whole run; phase MAILBOX is " +
                "complete. The address changes only through " +
                "mailbox_switch, once, on recorded evidence."
        } else {
            "error: the claim did not stamp — try again " +
                "from the provider's page."
        }
    }

    /** mailbox_switch: the ONE sanctioned change of the
     *  committed address, standing on recorded evidence
     *  (XCreationPhases.switchVerdict judges it). */
    private fun mailboxSwitch(
        id: String, task: JSONObject, args: JSONObject
    ): String {
        if (!LilleXState.isCreationTask(task)) {
            return "error: mailbox_switch exists only on " +
                "her X creation lane."
        }
        val reason = args.optString("reason").trim()
        val fresh = store.get(id) ?: task
        val verdict = XCreationPhases.switchVerdict(fresh, reason)
        if (verdict != null) {
            return "error: mailbox switch refused — $verdict."
        }
        store.update(id) { t ->
            XCreationPhases.applySwitch(
                t, reason, System.currentTimeMillis()
            )
            LoopGuard.reset(t)
        }
        return "MAILBOX_SWITCHED reason=$reason — the " +
            "previous address and the reason are on the " +
            "record. Claim the next provider's address " +
            "with mailbox_claim; that was this run's one " +
            "switch."
    }
}
