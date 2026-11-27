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
        private const val SYSTEM_PROMPT =
            "You are Lille, an autonomous agent completing a task on the user's phone by calling tools. " +
            "Reply with exactly ONE fenced JSON block per step, like:\n" +
            "```json\n{\"tool\": \"write_note\", \"args\": {\"path\": \"note.txt\", \"content\": \"...\"}}\n```\n" +
            "Tools: write_note{path,content}, read_note{path}, list_notes{}, web_search{query}, " +
            "finish{summary, outputs}. All files are notes inside the app sandbox; use relative paths. " +
            "When fully done, call finish and list produced notes in outputs. Never invent tool results."
        private const val BROWSER_SYSTEM_PROMPT =
            "You are Lille, an autonomous agent completing a task in your OWN browser on the user's phone by calling tools. " +
            "Reply with exactly ONE fenced JSON block per step, like:\n" +
            "```json\n{\"tool\": \"browser_open\", \"args\": {\"url\": \"https://example.com\"}}\n```\n" +
            "Tools: browser_open{url}, browser_read{}, browser_click{ref or text}, browser_type{ref,text}, " +
            "browser_type_secret{ref,vault_key}, browser_upload{ref,path}, browser_screenshot{name}, " +
            "browser_wait{seconds or text,timeout}, browser_handoff{step}, vault_gen_secret{key}, " +
            "vault_record_account{site,username,vault_key}, x_session_status{}, x_read_feed{count}, " +
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
        private const val GENERAL_BROWSER_SYSTEM_PROMPT =
            "You are Lille, an autonomous agent completing a task on the user's phone by calling tools. " +
            "Reply with exactly ONE fenced JSON block per step, like:\n" +
            "```json\n{\"tool\": \"browser_open\", \"args\": {\"url\": \"https://example.com\"}}\n```\n" +
            "Tools: browser_open{url}, browser_read{}, browser_click{ref or text}, browser_type{ref,text}, " +
            "browser_type_secret{ref,vault_key}, browser_upload{ref,path}, browser_screenshot{name}, " +
            "browser_wait{seconds or text,timeout}, browser_handoff{step}, vault_gen_secret{key}, " +
            "vault_record_account{site,username,vault_key}, web_search{query}, write_note{path,content}, " +
            "read_note{path}, list_notes{}, finish{summary}. The browser is bound by this task's domain " +
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
            "so. Never invent tool results."
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
        val text = task.optString("text")
        val browserTask = isBrowserTask(task)
        // TOOL AVAILABILITY (v1.0.3, TaskTools): a general
        // task is PLANNED from its text — which tool classes
        // it requires, which of those this phone lacks, and
        // the browser allowlist its own words authorise.
        // Packaged tasks (kind set) keep their package's
        // toolset and are never planned.
        val packaged = TaskTools.isPackaged(task)
        val plan = if (packaged) null else TaskTools.planFor(text)
        val browserToolsActive = browserTask ||
            (plan?.requiresBrowser == true)

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

        store.update(id, TaskStates.RUNNING) {
            // The plan is stamped at run start (creation
            // stamps it too, for the chat's first status):
            // requires/unavailable classes + the derived
            // browser allowlist are record evidence from
            // here on. A new run also supersedes the last
            // budget rest's flag.
            if (plan != null) TaskTools.stampPlan(it, plan)
            it.remove(StepBudget.REST_FLAG)
        }
        var messages = task.optJSONArray("messages") ?: JSONArray()
        if (messages.length() == 0) {
            messages.put(JSONObject().put("role", "system")
                .put("content", when {
                    packaged -> BROWSER_SYSTEM_PROMPT
                    browserToolsActive -> GENERAL_BROWSER_SYSTEM_PROMPT
                    else -> SYSTEM_PROMPT
                }))
            messages.put(JSONObject().put("role", "user").put("content", text))
        }
        var stepsDone = task.optInt("steps", 0)
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
            // A planned user handoff (v0.10.1) resumes with its
            // own note: the user acted themselves in her session
            // — she verifies from the page, never from assumption.
            val resumeContent =
                if (wall.optString("kind") == UserHandoff.WALL_KIND) {
                    UserHandoff.resumeNote(wall.optString("domain"))
                } else {
                    "Ethuram has completed the human check in your browser session " +
                        "(wall was: ${wall.optString("kind")} on " +
                        "${wall.optString("domain")}). Continue the task now: " +
                        "browser_read the page and carry on from where you stopped."
                }
            messages.put(JSONObject().put("role", "user").put("content", resumeContent))
            store.update(id) {
                it.put("wallResumeNoted", handedBackAt)
                it.remove("wall")
                it.remove("handoffStep")
            }
            checkpoint(id, messages, stepsDone, windowSteps)
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
            store.update(id) { it.put("envNotePosted", true) }
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

        while (windowSteps < maxSteps) {
            // Honour pause/cancel that landed while we were working.
            val fresh = store.get(id) ?: return TaskStates.FAILED
            when (fresh.optString("state")) {
                TaskStates.CANCELLED -> return TaskStates.CANCELLED
                TaskStates.PAUSED -> return TaskStates.PAUSED
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
                store.update(id, TaskStates.COMPLETED) {
                    it.put("summary", args.optString("summary", "Done."))
                    it.put("steps", stepsDone)
                    it.put("messages", messages)
                    // Never silently downgrade (v1.0.3): a
                    // finish with computer-lane classes
                    // outstanding is labelled partial on the
                    // record, and the graded report says so.
                    if (plan != null && plan.partial) {
                        it.put("partialCompletion", true)
                    }
                }
                return TaskStates.COMPLETED
            }
            val observation = when {
                packaged -> executeBrowserTool(id, task, name, args)
                browserToolsActive ->
                    // General task, browser in play (v1.0.3):
                    // the pure dispatch (TaskTools.executorFor)
                    // routes browser-family calls to the SAME
                    // device browser executor the packaged
                    // lane uses, refuses the X lane tools by
                    // Sentinel, and leaves notes/search to the
                    // note executor.
                    when (TaskTools.executorFor(true, name)) {
                        TaskTools.Executor.BROWSER ->
                            executeBrowserTool(id, task, name, args)
                        TaskTools.Executor.REFUSE_X -> TaskTools.X_REFUSAL
                        else -> executeTool(id, name, args)
                    }
                else -> executeTool(id, name, args)
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

    private fun wait(id: String, state: String, note: String, waitMs: Long): String {
        store.update(id, state) {
            it.put("note", note)
            it.put("nextRetryAt", System.currentTimeMillis() + waitMs)
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

        val present = OwnKeyRoutes.presentRoutes { name -> vault.hasSecret(name) }
        for (route in BrainFailure.OWN_KEY_ORDER) {
            if (route !in present) continue
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
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(content.substring(start, end + 1))
            if (!obj.has("tool")) return null
            obj.optString("tool") to (obj.optJSONObject("args") ?: JSONObject())
        } catch (e: Exception) {
            null
        }
    }

    // ---------- note tools (v0.4 scope) ----------

    private fun executeTool(id: String, name: String, args: JSONObject): String {
        val dir = taskDir(id)
        return try {
            when (name) {
                "write_note" -> writeNote(dir, args)
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
                "write_note" -> writeNote(taskDir(id), args)
                "read_note" -> readNote(taskDir(id), args)
                "browser_open" -> {
                    val url = args.optString("url")
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        "error: browser_open needs an http(s) URL: ${url.take(80)}"
                    } else if (!BrowserPerception.domainAllowed(url, domains)) {
                        "error: domain ${BrowserPerception.domainOf(url)} is not " +
                            "in this task's browser allowlist " +
                            "(${domains.joinToString(", ").ifEmpty { "none authorised" }})"
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
                        "ACCOUNT_RECORDED site=${args.optString("site")} " +
                            "username=${args.optString("username")} " +
                            "vault_key=$key (metadata only — no secret " +
                            "stored in this record)"
                    } else {
                        "error: could not record the account metadata"
                    }
                }
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
        val handle = xHandle()
        return if (handle != null) {
            try {
                File(taskDir(id), "x_session.json").writeText(
                    JSONObject().put("handle", handle)
                        .put("ts", System.currentTimeMillis()).toString(2)
                )
            } catch (e: Exception) {
                // The observation is the evidence; the file is a copy.
            }
            store.update(id) { it.put("xHandle", handle) }
            "X_SESSION logged_in as @$handle — session is live in " +
                "Lille's persistent browser profile"
        } else {
            "X_SESSION logged_out — no account is signed in in Lille's " +
                "browser profile (page: ${state.url.take(80)})"
        }
    }

    private fun xReadFeed(
        id: String, domains: List<String>, args: JSONObject
    ): String {
        val n = (args.optInt("count", 10)).coerceIn(1, 20)
        val state = xOpen("https://x.com/home", domains)
        state.challenge?.let {
            return BrowserPerception.challengeObservation(it) +
                "\n(page: ${state.url})"
        }
        if (xHandle() == null) {
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
}
