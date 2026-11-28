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
 *   REPO_CODE, VIDEO and (v1.0.4) CALENDAR; notes + web
 *   search + her memory are the base kit every task has
 *   and are never "required".
 * - WHAT THIS ENVIRONMENT HAS — computed against the
 *   phone's live capabilities ([DeviceCaps]): the browser,
 *   the calendar (CalendarContract), mail once Gmail is
 *   connected in Settings, notes, web search, memory.
 *   REPO_CODE and VIDEO live in the Python core (the
 *   computer lane); since v1.0.4 they are ROUTED there —
 *   exported as a spec with a receipt recorded on the
 *   task — never faked on-device, never silently dropped,
 *   and no longer a terminal "cannot execute". MAIL with
 *   Gmail unconnected is declared up front with the ONE
 *   Settings step that connects it.
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

    enum class ToolClass { BROWSER, MAIL, REPO_CODE, VIDEO, CALENDAR }

    /** Record fields the plan stamps. */
    const val REQUIRES_FIELD = "requiresClasses"
    const val UNAVAILABLE_FIELD = "unavailableClasses"
    const val BROWSER_TOOLS_FIELD = "browserTools"
    const val ROUTED_FIELD = "routedClasses"
    const val MAIL_NOT_CONNECTED_FIELD = "mailNotConnected"

    /**
     * What this phone can actually do right now (v1.0.4).
     * The plan is computed against CAPABILITIES, not just
     * the job text: the calendar is on the device, and mail
     * is on the device once Gmail is connected in Settings
     * (the app's own Google OAuth stack — the same sign-in
     * machinery as the Drive backup). Repo/code and video
     * remain computer-lane classes, but since v1.0.4 they
     * ROUTE (a spec export with a receipt) instead of
     * dead-ending the job.
     */
    data class DeviceCaps(val gmailConnected: Boolean = false)

    /** The plan for one general task: the classes its text
     *  requires, the subset that cannot be performed on
     *  this phone in this run, the subset that is ROUTED
     *  to the computer lane with a receipt, and the
     *  browser allowlist its own words authorise. */
    data class Plan(
        val requires: Set<ToolClass>,
        val unavailable: Set<ToolClass>,
        val browserDomains: List<String>,
        val routed: Set<ToolClass> = emptySet(),
        val mailNotConnected: Boolean = false
    ) {
        val requiresBrowser: Boolean get() = ToolClass.BROWSER in requires
        val requiresCalendar: Boolean get() = ToolClass.CALENDAR in requires
        val mailActive: Boolean
            get() = ToolClass.MAIL in requires && !mailNotConnected
        val partial: Boolean get() = unavailable.isNotEmpty()
    }

    /** A packaged record (the X lanes) carries a "kind";
     *  general records never do. Packaged tasks keep their
     *  package's toolset and are never planned here. */
    fun isPackaged(task: JSONObject): Boolean {
        val kind = task.optString("kind")
        // A recipe (v1.0.4) is NOT a package: it runs on the
        // general kit under its own tool allowlist, with the
        // general plan/declaration discipline — its file is
        // the instruction, not a hard-coded lane.
        return kind.isNotEmpty() && kind != "recipe"
    }

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

    /** Calendar phrases (v1.0.4): the job is ABOUT the
     *  calendar — reading it or writing to it. Bare
     *  reminders are deliberately absent: "remind me" is a
     *  scheduling ask, not calendar work, and must not drag
     *  the calendar class (or its tools) into a job that
     *  never named the calendar. */
    private val CALENDAR_PHRASES = listOf(
        "calendar", "appointment", "schedule a meeting",
        "schedule an event", "schedule a call", "add to my calendar",
        "put it on my calendar", "create an event", "book a meeting",
        "what's on my day", "what is on my day", "my agenda",
        "meetings today", "events today", "free time", "am i free",
        "are you free"
    )

    /** Consequential-work phrases (v1.0.4, PlanAct): the
     *  job COMMITS the user to the outside world — sends,
     *  pays, posts, books, deletes, creates accounts. A
     *  general task whose words carry one of these starts
     *  in PLAN mode (mutating tools absent) until the user
     *  approves the plan in chat. Conservative on purpose:
     *  research, drafting and reading never trip it. */
    private val CONSEQUENTIAL_PHRASES = listOf(
        "send an email", "send email", "send money", "send the email",
        "make a payment", "pay for", "transfer money", "wire money",
        "delete", "post on x", "post to x", "post it", "publish",
        "book a", "book the", "buy ", "purchase", "place an order",
        "create an account", "sign up for", "register for",
        "schedule a meeting", "create an event", "add to my calendar",
        "put it on my calendar"
    )

    /** True when the job's own words make it consequential
     *  (PlanAct gate). Reads the user's words only — the
     *  chat-intake direction is stripped first. */
    fun isConsequential(text: String): Boolean {
        val low = jobText(text).lowercase()
        return CONSEQUENTIAL_PHRASES.any { low.contains(it) }
    }

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
        if (CALENDAR_PHRASES.any { low.contains(it) }) {
            out.add(ToolClass.CALENDAR)
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

    /** Classes that can never run on the phone — they
     *  belong to the computer lane. Since v1.0.4 they are
     *  ROUTED there (spec export + receipt), not dropped. */
    fun routedClasses(requires: Set<ToolClass>): Set<ToolClass> =
        requires intersect setOf(ToolClass.REPO_CODE, ToolClass.VIDEO)

    /** Classes that cannot be performed on this phone in
     *  this run, given what the phone can do right now:
     *  the routed classes, plus mail when Gmail is not
     *  connected. The calendar is device kit (v1.0.4) and
     *  mail is device kit once connected — neither is ever
     *  declared missing again. */
    fun unavailableOnDevice(
        requires: Set<ToolClass>, caps: DeviceCaps = DeviceCaps()
    ): Set<ToolClass> {
        val out = routedClasses(requires).toMutableSet()
        if (ToolClass.MAIL in requires && !caps.gmailConnected) {
            out.add(ToolClass.MAIL)
        }
        return out
    }

    /** Her own repository — a fact from her own records
     *  (v1.0.13). A job about her own product targets it by
     *  default: the target is STATED on the record and in
     *  the job's first status, never asked of the user.
     *  Born of the 2026-11-28 screenshot, where the brain
     *  asked him to "share the repository URL" for a job
     *  about Lille's own referral link. */
    const val OWN_REPO = "Townsvilledh/free-agent"

    /** Record field carrying the job's default target
     *  repository, when [ownRepoTarget] settles one. */
    const val TARGET_REPO_FIELD = "targetRepo"

    /** The repository a job targets when the job is about
     *  HER OWN product — her name in the job's words, her
     *  referral system named as hers, or the app itself —
     *  AND the job is repository work. Null otherwise: a
     *  job about the world's code keeps whatever target
     *  its own words name, and a non-repo job has no
     *  repository target at all. */
    fun ownRepoTarget(text: String, plan: Plan): String? {
        if (ToolClass.REPO_CODE !in plan.requires) return null
        val low = jobText(text).lowercase()
        val hers = wordHit(low, "lille") ||
            low.contains("your referral") ||
            low.contains("your refferal") ||
            low.contains("her referral") ||
            low.contains("her refferal") ||
            low.contains("your own app") ||
            low.contains("this app")
        return if (hers) OWN_REPO else null
    }

    /** The plan for a general task's text, against the
     *  phone's current capabilities. (Packaged tasks are
     *  the caller's concern — see [isPackaged].) */
    fun planFor(text: String, caps: DeviceCaps = DeviceCaps()): Plan {
        val requires = requiredClasses(text)
        val mailNotConnected =
            ToolClass.MAIL in requires && !caps.gmailConnected
        return Plan(
            requires = requires,
            unavailable = unavailableOnDevice(requires, caps),
            browserDomains = if (ToolClass.BROWSER in requires) {
                domainsFor(text)
            } else {
                emptyList()
            },
            routed = routedClasses(requires),
            mailNotConnected = mailNotConnected
        )
    }

    /** The plan recorded on [task], or null when the task
     *  was never planned (packaged tasks, pre-1.0.3
     *  records the runner has not re-entered yet). Legacy
     *  records (pre-1.0.4) carry no routed/mail fields:
     *  both are derived from the unavailable set, which is
     *  exactly what those fields meant when stamped. */
    fun planFromRecord(task: JSONObject): Plan? {
        if (!task.has(REQUIRES_FIELD)) return null
        val requires = classSet(task.optJSONArray(REQUIRES_FIELD))
        val unavailable = classSet(task.optJSONArray(UNAVAILABLE_FIELD))
        val domains = mutableListOf<String>()
        val arr = task.optJSONArray("browserDomains")
        if (arr != null) {
            for (i in 0 until arr.length()) domains.add(arr.optString(i))
        }
        val routed = if (task.has(ROUTED_FIELD)) {
            classSet(task.optJSONArray(ROUTED_FIELD))
        } else {
            routedClasses(unavailable)
        }
        val mailNotConnected = if (task.has(MAIL_NOT_CONNECTED_FIELD)) {
            task.optBoolean(MAIL_NOT_CONNECTED_FIELD)
        } else {
            ToolClass.MAIL in unavailable
        }
        return Plan(requires, unavailable, domains, routed, mailNotConnected)
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
        task.put(ROUTED_FIELD, classArray(plan.routed))
        task.put(MAIL_NOT_CONNECTED_FIELD, plan.mailNotConnected)
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
        ToolClass.CALENDAR -> "calendar work"
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
     *  half of the up-front declaration. Only the classes
     *  the job actually names are listed (plus the base
     *  kit), so the line stays a statement about THIS job. */
    fun availableLine(plan: Plan): String {
        val parts = mutableListOf<String>()
        if (plan.requiresBrowser) parts.add("the browser")
        if (plan.requiresCalendar) parts.add("the calendar")
        if (plan.mailActive) parts.add("email")
        parts.add("notes")
        parts.add("web search")
        parts.add("her memory")
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
        val tools = mutableListOf<String>()
        if (plan.requiresBrowser) {
            tools.add(
                "the browser tools (browser_open/read/click/type/" +
                    "type_secret/upload/screenshot/wait), the vault tools"
            )
        }
        if (plan.requiresCalendar) {
            tools.add("calendar_read and calendar_create")
        }
        if (plan.mailActive) {
            tools.add(
                "gmail_list, gmail_search and gmail_read " +
                    "(mail bodies reach you only as short redacted " +
                    "snippets — codes and secrets are stripped " +
                    "before you ever see them)"
            )
        }
        tools.add("write_note/read_note/list_notes")
        tools.add("web_search")
        tools.add(
            "memory_read/memory_write/memory_search (your stored " +
                "memory of the user)"
        )
        tools.add("plan_note and finish")
        out.append(tools.joinToString(", ")).append(".")
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
        if (plan.mailNotConnected) {
            out.append(
                " This job names email work, but Gmail is not " +
                    "connected on this phone yet: the mail tools " +
                    "will answer GMAIL_NOT_CONNECTED, naming the " +
                    "one Settings step that connects it. Do NOT " +
                    "ask the user to fetch or paste mail contents. " +
                    "Do the other parts of the job, and in your " +
                    "finish summary name the email part as not " +
                    "done here."
            )
        }
        if (plan.routed.isNotEmpty()) {
            out.append(
                " This job also names " +
                    labelsFor(plan.routed) +
                    " — that part cannot run on a phone. It is " +
                    "being sent to the computer lane with a " +
                    "receipt (the export is recorded on this " +
                    "task). Do the parts this phone supports, " +
                    "and in your finish summary name plainly " +
                    "what was sent to the computer lane. Never " +
                    "claim to have done a part you have no " +
                    "tool for."
            )
        }
        return out.toString()
    }

    // ---------- tool dispatch ----------

    enum class Executor {
        BROWSER, NOTES, CALENDAR, MAIL, MEMORY, REFUSE_X, UNKNOWN
    }

    private val BROWSER_FAMILY = setOf(
        "browser_open", "browser_read", "browser_click",
        "browser_click_mark",
        "browser_type", "browser_type_secret", "browser_upload",
        "browser_screenshot", "browser_wait", "browser_handoff",
        "vault_gen_secret", "vault_record_account"
    )

    private val NOTE_FAMILY = setOf(
        "write_note", "read_note", "list_notes", "web_search"
    )

    /** The device calendar (v1.0.4): on-device kit, gated
     *  by the plan like the browser — a job that never
     *  named the calendar gets no calendar tools. */
    private val CALENDAR_FAMILY = setOf("calendar_read", "calendar_create")

    /** On-device Gmail (v1.0.4): live only when the plan
     *  requires mail AND Gmail is connected — a task whose
     *  plan says mail is not connected hears the honest
     *  NOT_CONNECTED from the mail executor instead. */
    private val MAIL_FAMILY = setOf(
        "gmail_list", "gmail_search", "gmail_read", "gmail_send"
    )

    /** Her stored memory (v1.0.4): base kit for every
     *  general task, like notes — knowing the user is not
     *  a class a job has to name. */
    private val MEMORY_FAMILY = setOf(
        "memory_read", "memory_write", "memory_search"
    )

    /** Tools that CHANGE the world (v1.0.4, PlanAct +
     *  checkpoints): writes, sends, posts, creations,
     *  anything whose effect survives the step. In PLAN
     *  mode these are ABSENT from the offered registry;
     *  in ACT mode each one is checkpointed before it
     *  runs (where a snapshot exists — notes). */
    val MUTATING_TOOLS: Set<String> = setOf(
        "write_note", "calendar_create", "gmail_send",
        "browser_click", "browser_click_mark", "browser_type",
        "browser_type_secret", "browser_upload",
        "vault_gen_secret", "vault_record_account",
        "x_post", "memory_write"
    )

    fun isMutating(tool: String): Boolean = tool in MUTATING_TOOLS

    /** Which executor a tool call belongs to, for a task
     *  whose browser tools are [browserActive]. The X lane
     *  tools (x_*) belong to packaged X tasks alone: on a
     *  general task they are refused by Sentinel, never
     *  routed to the browser executor that implements them
     *  for the packaged lane. Pure, so the general runner's
     *  dispatch is testable end-to-end with a fake driver. */
    fun executorFor(
        browserActive: Boolean, tool: String,
        mailActive: Boolean = false, calendarActive: Boolean = false
    ): Executor =
        when {
            tool.startsWith("x_") -> Executor.REFUSE_X
            tool in BROWSER_FAMILY ->
                if (browserActive) Executor.BROWSER else Executor.UNKNOWN
            tool in CALENDAR_FAMILY ->
                if (calendarActive) Executor.CALENDAR else Executor.UNKNOWN
            tool in MAIL_FAMILY ->
                if (mailActive) Executor.MAIL else Executor.UNKNOWN
            tool in MEMORY_FAMILY -> Executor.MEMORY
            tool in NOTE_FAMILY -> Executor.NOTES
            else -> Executor.UNKNOWN
        }

    /** The Sentinel refusal a general task hears when it
     *  reaches for an X lane tool. */
    const val X_REFUSAL =
        "error: SENTINEL — the X tools belong to packaged X " +
            "tasks; this task has no X lane. Use the browser " +
            "tools on the sites this job names instead."

    /** The Plan-mode refusal (v1.0.4): a mutating tool was
     *  called while the task is still in PLAN mode. The
     *  offered registry already omits these tools — this
     *  is the second wall, for a model that names one
     *  anyway. */
    const val PLAN_REFUSAL =
        "error: SENTINEL — this task is in PLAN mode: acting " +
            "tools are not available until the user approves " +
            "the plan. Call propose_plan with your plan and " +
            "sub-goals instead."
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
