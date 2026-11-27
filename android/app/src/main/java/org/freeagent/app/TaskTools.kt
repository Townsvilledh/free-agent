package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TOOL AVAILABILITY FOR GENERAL TASKS (v1.0.3).
 *
 * The gap this closes (Ethuram's screenshots, 2026-11-27
 * 06:23): the Agentware job ran on the on-device GENERAL
 * runner, whose whole toolset was notes + web search, and
 * only discovered at the END — in its graded report — that
 * "the required browser, Gmail, Discord, GitHub, and video
 * tools are not available in this environment". Meanwhile
 * the packaged X lane drove 37 real browser steps the same
 * morning: the device browser existed in the app, wired to
 * exactly one packaged task.
 *
 * This component is the deterministic front door for
 * general (non-packaged) tasks:
 *
 * - WHICH TOOL CLASSES THE JOB REQUIRES — read off the job
 *   text by keyword/shape matching: conservative, word-
 *   boundary phrases, no model call, no guessing beyond
 *   what the words say. The classes are BROWSER, MAIL,
 *   REPO_CODE and VIDEO; notes + web search are the base
 *   kit every task has and are never "required".
 * - WHAT THIS ENVIRONMENT HAS — on the phone: the browser
 *   (since this release), notes and web search. MAIL,
 *   REPO_CODE and VIDEO live in the Python core (the
 *   computer lane); they are named as such, never faked
 *   on-device and never silently dropped.
 * - THE BROWSER ALLOWLIST — the Sentinel rule is a
 *   per-task domain allowlist; for a general task the
 *   allowlist is derived from the job itself: the URLs and
 *   bare domains the job text names, plus the canonical
 *   domains of web services the job names (its own words
 *   authorise them). Derived once, recorded on the task
 *   record, auditable — never widened at runtime.
 * - THE PLAN — stamped on the record at creation and at
 *   run start ([stampPlan]): requiresClasses,
 *   unavailableClasses, browserTools, browserDomains. The
 *   transparency renderer reads the same fields, so the
 *   job's FIRST status in its chat declares up front what
 *   she can and cannot do here — instead of the truth
 *   arriving in the final report.
 *
 * Packaged tasks (a record carrying a "kind" — the X
 * creation/connect lanes) are NOT planned here: their
 * toolset is their package's, and their text legitimately
 * mentions email (mailboxes she reads IN the browser)
 * without needing the mail tool class.
 *
 * Pure + org.json; pinned by TaskToolsTest.
 */
object TaskTools {

    enum class ToolClass { BROWSER, MAIL, REPO_CODE, VIDEO }

    /** Record fields the plan stamps. */
    const val REQUIRES_FIELD = "requiresClasses"
    const val UNAVAILABLE_FIELD = "unavailableClasses"
    const val BROWSER_TOOLS_FIELD = "browserTools"

    /** The plan for one general task: the classes its text
     *  requires, the subset this phone does not have, and
     *  the browser allowlist its own words authorise. */
    data class Plan(
        val requires: Set<ToolClass>,
        val unavailable: Set<ToolClass>,
        val browserDomains: List<String>
    ) {
        val requiresBrowser: Boolean get() = ToolClass.BROWSER in requires
        val partial: Boolean get() = unavailable.isNotEmpty()
    }

    /** A packaged record (the X lanes) carries a "kind";
     *  general records never do. Packaged tasks keep their
     *  package's toolset and are never planned here. */
    fun isPackaged(task: JSONObject): Boolean =
        task.optString("kind").isNotEmpty()

    // ---------- detection ----------

    /** The original code-execution hints (v0.4) — a task
     *  tripping these needs code run somewhere, which this
     *  phone never fakes. Kept verbatim: the NEEDS_RUNNER
     *  export gate keys on exactly this set. */
    val CODE_HINTS = listOf(
        "python", "run code", "execute code", "compile", "build an app",
        "build the app", "create an apk", "make an apk", "script that runs",
        "run a script", "run the script"
    )

    /** Wider repository-work phrases: they make REPO_CODE
     *  a required class for the DECLARATION (the work is
     *  named, so its absence here is declared), without
     *  widening the export gate above. */
    private val REPO_PHRASES = listOf(
        "github", "repository", "pull request", "codebase",
        "source code", "clone the", "commit the", "push the code"
    )

    private val MAIL_PHRASES = listOf(
        "gmail", "e-mail", "inbox", "my mail", "your mail",
        "read my email", "check my email", "send an email",
        "send email", "email inbox"
    )

    private val VIDEO_PHRASES = listOf(
        "video", "screencast", "screen recording", "record a demo",
        "demo video", "film a", "youtube"
    )

    /** Web-action phrases: the job cannot be done without
     *  opening pages. Deliberately action-shaped (a verb or
     *  a web surface), not topic-shaped. */
    private val BROWSER_PHRASES = listOf(
        "website", "web page", "webpage", "in the browser",
        "your browser", "open the site", "visit the site",
        "sign up", "sign-up", "signup", "log in", "login",
        "sign in", "create an account", "register for",
        "fill in the form", "fill the form", "submit the form",
        "online form", "google form", "typeform",
        "join the discord", "discord server", "discord channel",
        "register on", "book on", "apply on"
    )

    /** Web services a job may name by name; naming one
     *  requires the browser and authorises its canonical
     *  domain(s). (Gmail is deliberately absent: mail is
     *  the MAIL class, and webmail-as-workaround is a
     *  separate decision — see the release scope.) */
    private val SERVICE_DOMAINS: List<Pair<String, List<String>>> = listOf(
        "discord" to listOf("discord.com"),
        "github" to listOf("github.com"),
        "lu.ma" to listOf("lu.ma", "luma.com"),
        "tiktok" to listOf("tiktok.com"),
        "devpost" to listOf("devpost.com"),
        "dorahacks" to listOf("dorahacks.io"),
        "x.com" to listOf("x.com"),
        "twitter" to listOf("x.com", "twitter.com")
    )

    private val URL_RE = Regex("https?://[^\\s)\\]<>\"']+")
    private val BARE_DOMAIN_RE = Regex(
        "\\b(?:[a-z0-9-]+\\.)+(?:com|org|net|io|ai|dev|app|co|cc|gg|me|so|to|fm|tv|xyz|site|online)\\b"
    )

    /** The job text with the chat-intake direction removed
     *  — detection reads what the USER asked for, never the
     *  standing instructions appended to it. */
    fun jobText(raw: String): String {
        val intake = raw.indexOf("\n\n[Chat intake:")
        return if (intake >= 0) raw.substring(0, intake) else raw
    }

    private fun wordHit(low: String, phrase: String): Boolean {
        if (phrase.contains(' ')) return low.contains(phrase)
        return Regex("\\b" + Regex.escape(phrase) + "\\b").containsMatchIn(low)
    }

    /** The tool classes [text] requires — deterministic,
     *  conservative: a class is required only when the
     *  words ask for that kind of work. */
    fun requiredClasses(text: String): Set<ToolClass> {
        val low = jobText(text).lowercase()
        val out = mutableSetOf<ToolClass>()
        if (URL_RE.containsMatchIn(low) ||
            BARE_DOMAIN_RE.containsMatchIn(low) ||
            BROWSER_PHRASES.any { low.contains(it) } ||
            SERVICE_DOMAINS.any { (name, _) -> wordHit(low, name) }
        ) {
            out.add(ToolClass.BROWSER)
        }
        if (wordHit(low, "email") ||
            MAIL_PHRASES.any { low.contains(it) }
        ) {
            out.add(ToolClass.MAIL)
        }
        if (CODE_HINTS.any { low.contains(it) } ||
            REPO_PHRASES.any { low.contains(it) } ||
            wordHit(low, "repo")
        ) {
            out.add(ToolClass.REPO_CODE)
        }
        if (VIDEO_PHRASES.any { low.contains(it) }) {
            out.add(ToolClass.VIDEO)
        }
        return out
    }

    /** The original export-gate predicate (v0.4): the text
     *  asks for code to be RUN. TaskRunner's NEEDS_RUNNER
     *  gate keys on this, unchanged. */
    fun looksLikeCodeTask(text: String): Boolean {
        val low = jobText(text).lowercase()
        return CODE_HINTS.any { low.contains(it) }
    }

    /** The browser allowlist the job's own words authorise:
     *  domains of URLs it links, bare domains it names, and
     *  the canonical domains of services it names — in
     *  first-mention order, deduplicated. */
    fun domainsFor(text: String): List<String> {
        val raw = jobText(text)
        val low = raw.lowercase()
        val out = mutableListOf<String>()
        fun add(domain: String) {
            val d = domain.trim().lowercase()
                .removePrefix("www.")
            if (d.isNotEmpty() && d !in out) out.add(d)
        }
        for (m in URL_RE.findAll(low)) {
            add(BrowserPerception.domainOf(m.value))
        }
        for (m in BARE_DOMAIN_RE.findAll(low)) {
            add(m.value)
        }
        for ((name, domains) in SERVICE_DOMAINS) {
            if (wordHit(low, name)) domains.forEach { add(it) }
        }
        return out
    }

    /** Classes this phone does not have: they live in the
     *  core (computer lane) and are declared, never faked. */
    fun unavailableOnDevice(requires: Set<ToolClass>): Set<ToolClass> =
        requires intersect setOf(
            ToolClass.MAIL, ToolClass.REPO_CODE, ToolClass.VIDEO
        )

    /** The plan for a general task's text. (Packaged tasks
     *  are the caller's concern — see [isPackaged].) */
    fun planFor(text: String): Plan {
        val requires = requiredClasses(text)
        return Plan(
            requires = requires,
            unavailable = unavailableOnDevice(requires),
            browserDomains = if (ToolClass.BROWSER in requires) {
                domainsFor(text)
            } else {
                emptyList()
            }
        )
    }

    /** The plan recorded on [task], or null when the task
     *  was never planned (packaged tasks, pre-1.0.3
     *  records the runner has not re-entered yet). */
    fun planFromRecord(task: JSONObject): Plan? {
        if (!task.has(REQUIRES_FIELD)) return null
        val requires = classSet(task.optJSONArray(REQUIRES_FIELD))
        val unavailable = classSet(task.optJSONArray(UNAVAILABLE_FIELD))
        val domains = mutableListOf<String>()
        val arr = task.optJSONArray("browserDomains")
        if (arr != null) {
            for (i in 0 until arr.length()) domains.add(arr.optString(i))
        }
        return Plan(requires, unavailable, domains)
    }

    private fun classSet(arr: JSONArray?): Set<ToolClass> {
        if (arr == null) return emptySet()
        val out = mutableSetOf<ToolClass>()
        for (i in 0 until arr.length()) {
            try {
                out.add(ToolClass.valueOf(arr.optString(i)))
            } catch (e: Exception) {
                // An unknown class name in an old record is
                // ignored, never fatal.
            }
        }
        return out
    }

    private fun classArray(classes: Set<ToolClass>): JSONArray {
        val arr = JSONArray()
        // Enum order, always — the record is evidence and
        // reads the same on every device.
        for (c in ToolClass.values()) if (c in classes) arr.put(c.name)
        return arr
    }

    /** Stamp [plan] onto a record being mutated (the
     *  TaskStore.update lambda's receiver). Idempotent:
     *  re-stamping the same plan changes nothing. An
     *  existing browserDomains list is never narrowed —
     *  packaged records keep theirs exactly. */
    fun stampPlan(task: JSONObject, plan: Plan) {
        task.put(REQUIRES_FIELD, classArray(plan.requires))
        task.put(UNAVAILABLE_FIELD, classArray(plan.unavailable))
        if (plan.requiresBrowser) {
            task.put(BROWSER_TOOLS_FIELD, true)
            if (!task.has("browserDomains")) {
                val arr = JSONArray()
                plan.browserDomains.forEach { arr.put(it) }
                task.put("browserDomains", arr)
            }
        }
    }

    // ---------- work-terms wording ----------

    /** A class named as work, for user surfaces. */
    fun labelFor(toolClass: ToolClass): String = when (toolClass) {
        ToolClass.BROWSER -> "the browser"
        ToolClass.MAIL -> "email work"
        ToolClass.REPO_CODE -> "code and repository work"
        ToolClass.VIDEO -> "video work"
    }

    /** The classes named as a list ("email work and video
     *  work"), in enum order. */
    fun labelsFor(classes: Set<ToolClass>): String {
        val labels = ToolClass.values()
            .filter { it in classes }
            .map { labelFor(it) }
        return when (labels.size) {
            0 -> ""
            1 -> labels[0]
            2 -> labels[0] + " and " + labels[1]
            else -> labels.dropLast(1).joinToString(", ") +
                " and " + labels.last()
        }
    }

    /** What she HAS here, in work terms — the available
     *  half of the up-front declaration. */
    fun availableLine(plan: Plan): String {
        val parts = mutableListOf<String>()
        if (plan.requiresBrowser) parts.add("the browser")
        parts.add("notes")
        parts.add("web search")
        return "On this phone she has " +
            parts.joinToString(", ") + "."
    }

    /** The model-facing environment note, appended to a
     *  general task's messages once at its first run: the
     *  same facts the user sees, as working instructions —
     *  so the run plans around the real toolset from step
     *  one instead of discovering it at the end. */
    fun environmentNote(plan: Plan): String {
        val out = StringBuilder()
        out.append("Environment note (this phone): your tools here are ")
        out.append(
            if (plan.requiresBrowser) {
                "the browser tools (browser_open/read/click/type/" +
                    "type_secret/upload/screenshot/wait), the vault " +
                    "tools, write_note/read_note/list_notes, " +
                    "web_search and finish."
            } else {
                "write_note/read_note/list_notes, web_search " +
                    "and finish."
            }
        )
        if (plan.requiresBrowser) {
            out.append(
                " The browser is bound by this task's domain " +
                    "allowlist: " +
                    plan.browserDomains.joinToString(", ")
                        .ifEmpty { "(no domains named yet)" } +
                    ". Work from browser_read's element refs — " +
                    "refs are reassigned on every read, so re-read " +
                    "after each navigation. Passwords are NEVER " +
                    "typed by you and NEVER written into any file " +
                    "or message: create them with vault_gen_secret " +
                    "and type them with browser_type_secret. If any " +
                    "tool result starts with CHALLENGE, the page is " +
                    "a human-only wall: do NOT try to solve or " +
                    "bypass it — if the task names another " +
                    "legitimate route, take it; otherwise finish " +
                    "naming that exact wall."
            )
        }
        if (plan.partial) {
            out.append(
                " This job also names " +
                    labelsFor(plan.unavailable) +
                    " — those tools are NOT available in this " +
                    "environment (they live on the computer " +
                    "lane). Do the parts this phone supports, " +
                    "and in your finish summary name plainly " +
                    "what remains for the computer lane. Never " +
                    "claim to have done a part you have no " +
                    "tool for."
            )
        }
        return out.toString()
    }

    // ---------- tool dispatch ----------

    enum class Executor { BROWSER, NOTES, REFUSE_X, UNKNOWN }

    private val BROWSER_FAMILY = setOf(
        "browser_open", "browser_read", "browser_click",
        "browser_type", "browser_type_secret", "browser_upload",
        "browser_screenshot", "browser_wait", "browser_handoff",
        "vault_gen_secret", "vault_record_account"
    )

    private val NOTE_FAMILY = setOf(
        "write_note", "read_note", "list_notes", "web_search"
    )

    /** Which executor a tool call belongs to, for a task
     *  whose browser tools are [browserActive]. The X lane
     *  tools (x_*) belong to packaged X tasks alone: on a
     *  general task they are refused by Sentinel, never
     *  routed to the browser executor that implements them
     *  for the packaged lane. Pure, so the general runner's
     *  dispatch is testable end-to-end with a fake driver. */
    fun executorFor(browserActive: Boolean, tool: String): Executor =
        when {
            tool.startsWith("x_") -> Executor.REFUSE_X
            tool in BROWSER_FAMILY ->
                if (browserActive) Executor.BROWSER else Executor.UNKNOWN
            tool in NOTE_FAMILY -> Executor.NOTES
            else -> Executor.UNKNOWN
        }

    /** The Sentinel refusal a general task hears when it
     *  reaches for an X lane tool. */
    const val X_REFUSAL =
        "error: SENTINEL — the X tools belong to packaged X " +
            "tasks; this task has no X lane. Use the browser " +
            "tools on the sites this job names instead."
}

/**
 * PROGRESS-AWARE STEP BUDGET (v1.0.3).
 *
 * Until now a task that hit its step budget (8 note steps,
 * 40 browser steps) parked dead in PAUSED and waited for
 * the user's word — however well it was doing. The X run's
 * 37 good steps paused at the 40 cap mid-flow (Ethuram's
 * screenshot, 2026-11-27 06:40).
 *
 * Now a budget pause is a REST while the task is making
 * progress: it auto-resumes after [REST_MS] (the ordinary
 * waiting machinery carries it — same ~60 s discipline as
 * every other rest), up to [MAX_AUTO_RESTS_PER_DAY] rests
 * per task per day. It parks for the user only when the
 * window shows a stall (errors dominating, a run of
 * consecutive failures) or the day's rests are spent. The
 * decision reads only the window's recorded outcomes —
 * deterministic, like everything else in the engine.
 *
 * Pure; pinned by TaskToolsTest.
 */
object StepBudget {

    /** The budget rest: short, like every rest since the
     *  no-sleeping law. */
    const val REST_MS = 60_000L

    /** Generous, not endless: six fresh windows a day
     *  (a browser task can spend 6 × 40 = 240 steps a day
     *  before the user is asked). */
    const val MAX_AUTO_RESTS_PER_DAY = 6

    const val RESTS_FIELD = "budgetRests"
    const val REST_DAY_FIELD = "budgetRestDay"
    const val WINDOW_FIELD = "stepsInWindow"
    const val REST_FLAG = "budgetRest"

    /** The work-terms note a budget rest records. */
    const val REST_NOTE =
        "Resting between work sessions — she continues on " +
            "her own after a short pause."

    enum class Decision { AUTO_REST, PARK }

    /** Steps already spent in the current window. Legacy
     *  records (pre-1.0.3) carry no window field: derive it
     *  from the lifetime count, so a task that paused at
     *  exactly its budget (40 of 40) reads as a fresh
     *  window (0), and one mid-window (37 of 40) keeps its
     *  remaining steps. */
    fun windowFor(task: JSONObject, maxSteps: Int): Int {
        if (maxSteps <= 0) return 0
        val raw = if (task.has(WINDOW_FIELD)) {
            task.optInt(WINDOW_FIELD, 0)
        } else {
            task.optInt("steps", 0) % maxSteps
        }
        // Entering a run with a spent window means someone
        // deliberately resumed the task (the user's word, a
        // Run now, a rest expiring): a fresh window is the
        // honest reading, never an instant re-park.
        return if (raw >= maxSteps) 0 else raw.coerceAtLeast(0)
    }

    /** Rests already taken on [dayKey]'s date. */
    fun restsToday(task: JSONObject, dayKey: String): Int =
        if (task.optString(REST_DAY_FIELD) == dayKey) {
            task.optInt(RESTS_FIELD, 0)
        } else {
            0
        }

    /** Progress = the window's successes outnumber its
     *  failures AND no failure streak is running. A window
     *  of pure nudges (no tool ever landed) is not
     *  progress either. */
    fun decide(
        windowOk: Int, windowFailed: Int,
        consecutiveErrors: Int, restsToday: Int
    ): Decision {
        val progressing = windowOk >= 1 &&
            windowOk > windowFailed &&
            consecutiveErrors < 3
        return if (progressing &&
            restsToday < MAX_AUTO_RESTS_PER_DAY
        ) {
            Decision.AUTO_REST
        } else {
            Decision.PARK
        }
    }

    /** The local calendar day a rest counts against. */
    fun dayKey(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMs))
}
