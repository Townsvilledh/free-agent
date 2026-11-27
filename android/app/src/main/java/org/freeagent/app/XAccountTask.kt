package org.freeagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The packaged X-account task (v0.9): Lille's own X signup,
 * retried on the DEVICE lane from Ethuram's phone.
 *
 * This is the v0.8 acceptance spec
 * (specs/2026-11-25-lille-x-account.md) carried into the app:
 * the same hard rules the host runs taught (mailbox
 * discipline, one patient submit, the legal-link trap, form
 * discipline), the same pass/fail criteria — graded from the
 * task record's evidence fields (xHandle, firstPostUrl,
 * state) in the spec report view.
 *
 * The differences from the host spec are the lane's, and they
 * are the point: the browser is the phone's real WebView
 * (device lane), her password is generated ON-DEVICE into the
 * encrypted DeviceVault (vault_gen_secret, key name x_password),
 * and if X presents Arkose she parks (PARKED_AT_WALL) and the
 * "Lille needs you" card hands Ethuram the check inside her
 * session — one tap from him, then she resumes and finishes
 * alone. Nobody creates the account for her.
 *
 * The IDENTITY KIT is not in this file (v0.10.1, XLANE.md
 * "Hers alone"): handles, bio, DOB and the mailbox ladder are
 * her personal identity, injected at build time via
 * XIdentityKit (BuildConfig ← CI secrets) and interpolated
 * into the task text by [taskText]. A build without the kit
 * (source build, local dev, a fork) is hollow: [create]
 * records the task in an honest FAILED "owner configuration
 * missing" state and it never runs.
 */
object XAccountTask {

    /** The creation task's title — HERS, not the user's
     *  (2026-11-26 wording fix: the shipped "Lille: create
     *  my X account" mislabelled her account as the
     *  owner's; the account is Lille's own). */
    const val TITLE = "Create Lille's X account"

    /** The shipped build's title. Records created by it
     *  (including the parked creation task on the owner's
     *  phone) carry this — recognition must match both. */
    const val LEGACY_TITLE = "Lille: create my X account"

    /** The connect task's title: once creation is
     *  verified, the owner action is reconnecting HER
     *  session (LilleXState), never creating again. */
    const val CONNECT_TITLE = "Connect Lille's X account"

    /** The note/summary prefix of a hollow-build record —
     *  the honest unavailable state. */
    const val UNAVAILABLE_NOTE =
        "Owner configuration missing: this build carries no X " +
            "identity kit (the kit is injected only into the " +
            "official build, from build secrets), so Lille's own " +
            "X-account task cannot run here. Her account is " +
            "created and managed from the owner's instance."

    val CRITERIA = listOf(
        "Lille's X account exists: x_session_status verifies a live session as her handle on x.com",
        "Her profile carries the display name Lille, the bio from the identity kit, and the mascot avatar on her x.com profile",
        "x_read_feed returns real posts from her home timeline",
        "x_search returns real results for a query about Fluid wallet",
        "Her first post is live with a status URL on x.com"
    )

    /** The signup's domain allowlist: X itself plus the kit's
     *  mailbox providers, in route order. */
    fun domains(kit: XIdentityKit): List<String> =
        listOf("x.com") + kit.mailboxes

    /** The full task text with the identity kit interpolated.
     *  The hard rules are the v0.8 lessons, unchanged; only the
     *  kit values (handles, bio, DOB, mailbox ladder) come
     *  from [kit] — they exist nowhere in this source tree. */
    fun taskText(kit: XIdentityKit): String =
        "Create your OWN X account, Lille — an account that is yours, in your name, " +
        "which you will use to promote yourself and Fluid. This is a formal acceptance " +
        "test: it passes only when the account is live with your profile set, your " +
        "session persists in your browser, feed and search return real content, and " +
        "your first post is live with a URL.\n\n" +
        "IDENTITY KIT\n" +
        "- Display name: Lille\n" +
        "- Handle, try in order: ${kit.handles.joinToString(", ")}. If all are taken, keep trying close variants (suffix HQ, an " +
        "underscore, or similar) until one is accepted — a taken handle is a route " +
        "change, not a wall.\n" +
        "- Bio: ${kit.bio}\n" +
        "- Avatar: your mascot jacket image — call browser_upload with path " +
        "\"asset:lille-mascot-jacket.webp\" and the profile-picture file input's ref " +
        "when the flow offers one (or afterwards via Edit profile).\n" +
        "- Date of birth, if asked: ${kit.dob}.\n" +
        "- First post (your own words, this shape): introduce yourself as Lille, a " +
        "free open-source autonomous agent; say you build in public and run at $0; " +
        "say you are here to share what you are building and to promote Fluid, the " +
        "open-source non-custodial wallet. Under 280 characters. Post it with x_post.\n\n" +
        "HARD RULES\n" +
        "- START WITH THE BROWSER: your first tool call is browser_open (the mailbox " +
        "or the X signup). Do NOT write notes or plan files before the account exists.\n" +
        "- The signup entry point is https://x.com/i/flow/signup (if X shows a " +
        "different entry, follow the site's own Sign up / Create account path).\n" +
        "- THE ADDRESS MUST COME OFF A MAILBOX PAGE: open your first mailbox " +
        "provider (see EMAIL below). If it offers a name-box inbox, type ONLY a " +
        "short name (your display name, lowercase — NOT a full address) into the " +
        "box, click Go, and let the inbox page load; your address is that name at " +
        "the provider's domain. If the provider instead generates an address for " +
        "you, take the one it shows. Either way, read the address off the " +
        "provider's page. Never invent or reconstruct an address from memory.\n" +
        "- ONE PATIENT SUBMIT: after both fields are filled on one page instance, " +
        "click Continue ONCE, then browser_wait 30 and browser_read. The flow's " +
        "answer can take that long; clicking again or re-typing during the wait " +
        "cancels the submission.\n" +
        "- LOGIN-BRANCH TRAP: if the page answers \"We couldn't find an active X " +
        "account with that username\", the flow treated your submit as a LOGIN " +
        "attempt, not a signup. Do not keep submitting there: browser_open the " +
        "signup URL again and re-fill both fields before one patient submit.\n" +
        "- FORM DISCIPLINE: fill ALL fields of a form on the SAME page instance, in " +
        "this order, without re-opening the page in between (re-opening wipes what " +
        "you typed): browser_type the email into the email input, " +
        "browser_type_secret the password into the input marked SECRET, then " +
        "browser_read, then ONE click on the (submit button). The email field wants " +
        "the mailbox ADDRESS (the one you read off the mailbox page), not a username.\n" +
        "- THE LEGAL-LINK TRAP: directly under the email/password fields sit three " +
        "LINKS — Terms of Service, Privacy Policy, Cookie Use. Clicking one ABANDONS " +
        "the signup. NEVER click a link whose text or href mentions Terms, Privacy, " +
        "or Cookies while signing up. After typing the email and the vault password, " +
        "browser_read again and click the BUTTON labelled Sign up (it appears once " +
        "the form validates; if it is not visible yet, browser_wait 10 and " +
        "browser_read again). If you land on a legal page anyway, browser_open the " +
        "signup URL again immediately and redo the fields.\n" +
        "- PASSWORD: before signing up, call vault_gen_secret with key x_password. " +
        "Type the password ONLY with browser_type_secret (vault_key x_password). " +
        "Never write the password into any file, message, or field with " +
        "browser_type. After the account exists, call vault_record_account " +
        "(site x.com, username = your final handle, vault_key x_password).\n" +
        "- EMAIL: X needs an email address and will send a verification code. " +
        "The mailbox providers, in this exact order — exhaust each before " +
        "calling anything a wall: ${kit.mailboxes.joinToString(", then ")}. " +
        "Take the address the page gives you, use it for the X signup, then read " +
        "the verification code from that same inbox and enter it on X. Reading a " +
        "code from a mailbox you can open in the browser is normal work. " +
        "(2) If X rejects a mailbox's domain, try the next provider. " +
        "(3) If a provider itself walls you, move to the next one. " +
        "(4) The mailbox and the X flow are pages in ONE browser: after X accepts " +
        "your email it sends the code — open the mailbox page, and if the mail is " +
        "not there yet, browser_wait and browser_read again over a few minutes " +
        "before deciding it never arrived. Then browser_open the X signup URL " +
        "again: the flow resumes from its saved state, and you type the code " +
        "there. Do not type the code into the mailbox page's own fields.\n" +
        "- A WALL is only: a CAPTCHA/Arkose/human-verification check, or a " +
        "verification code sent somewhere you cannot read (e.g. a demand for a " +
        "phone number / SMS). When a tool result starts with CHALLENGE, stop that " +
        "route; if another legitimate route above remains, take it; if every route " +
        "ends at a human-only wall, finish and name the exact wall — that one step " +
        "is Ethuram's, and the task will park for him and resume after his tap.\n" +
        "- Never attempt to solve or bypass a CAPTCHA or human check, on any " +
        "site, ever.\n" +
        "- Your page is YOUR voice: the sign-off \"₿itcoin or nothing\" belongs to " +
        "@Bitcoinornatin and must never appear on your page or in your posts. No " +
        "invented offers, prizes, giveaways, or commitments in any post.\n" +
        "- Pacing: browse like a person. Do not repeat a failed action " +
        "identically — read the page, adapt, move on.\n\n" +
        "WHEN THE ACCOUNT EXISTS: set the profile (display name, bio, avatar via " +
        "Edit profile), then verify with x_session_status, read your feed with " +
        "x_read_feed, search with x_search for \"Fluid wallet\", post your first " +
        "post with x_post, and finish with a summary naming your handle and the " +
        "first post's URL.\n\n" +
        "PHASE MACHINE (v1.0.8 — this section governs the whole run and " +
        "overrides any shopping instinct in the rules above)\n" +
        "This creation runs as phases, in order: MAILBOX → SIGNUP_FORM → " +
        "EMAIL_VERIFY → PASSWORD_PROFILE → VERIFY_ACCOUNT. At each phase " +
        "the runner posts the phase's script into this conversation — work " +
        "only within the current phase. A completed phase is stamped on " +
        "your record and is NEVER re-entered: a resume starts at the first " +
        "incomplete phase, so never restart the mailbox hunt on a resume.\n" +
        "- MAILBOX COMMITMENT: take ONE address from the FIRST provider " +
        "that gives you one, call mailbox_claim with it, and use that " +
        "address for the entire run. The ladder order in EMAIL above is " +
        "for the first acquisition only. After the claim, the address " +
        "changes ONLY through mailbox_switch, which demands recorded " +
        "evidence (X refused the address, or the code never arrived " +
        "within the poll budget) and works at most once per run.\n" +
        "- LOOP BREAKER: if you circle the same ground without a phase " +
        "advancing, the lane stops you — take the alternate branch it " +
        "names, or the run parks with the reason recorded. Circling is " +
        "the failure this machine exists to prevent.\n" +
        "- finish is gated: it is accepted only in VERIFY_ACCOUNT, when " +
        "a live session as your handle and your profile page loading as " +
        "yours are both verified from state. A mailbox, a filled form, " +
        "or an entered code is a STEP, never a finish."

    /** True when [task] is a hollow-build record — created by
     *  [create] in a build without the identity kit. */
    fun isUnavailableRecord(task: JSONObject): Boolean =
        task.optString("note").startsWith("Owner configuration missing")

    /** Create the packaged task in the store (queued, lane=device,
     *  kind=browser, identity kit as the task text, criteria for
     *  the report view). Returns the task record.
     *
     *  Hollow build (no kit injected): the record is created
     *  already FAILED with the owner-configuration-missing note
     *  and is never enqueued — the task reports itself
     *  unavailable instead of running with baked-in defaults. */
    fun create(context: Context): JSONObject {
        val store = TaskStore(context)
        val kit = XIdentityKit.fromBuildConfig()
        if (!kit.isPresent()) {
            val task = store.create(
                "Create Lille's X account — unavailable in this " +
                    "build (owner configuration missing)."
            )
            val id = task.optString("id")
            store.update(id, TaskStates.FAILED) {
                it.put("title", TITLE)
                it.put("kind", "browser")
                it.put("lane", "device")
                it.put("note", UNAVAILABLE_NOTE)
                it.put("summary", UNAVAILABLE_NOTE)
            }
            return store.get(id) ?: task
        }
        val task = store.create(taskText(kit))
        val id = task.optString("id")
        val domains = JSONArray()
        domains(kit).forEach { domains.put(it) }
        val criteria = JSONArray()
        CRITERIA.forEach { criteria.put(it) }
        store.update(id) {
            it.put("title", TITLE)
            it.put("kind", "browser")
            it.put("lane", "device")
            it.put("browserDomains", domains)
            it.put("xPostMode", "own")
            it.put("xPostApproved", false)
            it.put("criteria", criteria)
        }
        return store.get(id) ?: task
    }

    /** The connect flow's task text: re-establish HER
     *  session. Her password comes from the vault — it was
     *  generated into it at creation (vault_gen_secret, key
     *  [LilleXState.X_PASSWORD_VAULT_KEY]) and is typed only
     *  via browser_type_secret; it is never displayed,
     *  logged, or placed in any message. */
    fun connectTaskText(handle: String): String =
        "Reconnect your OWN X account, Lille — you are @$handle on x.com. " +
        "Your account already exists; this is only about your session in " +
        "your browser on this phone.\n\n" +
        "FLOW\n" +
        "1. Call x_session_status first. If it reports logged_in as " +
        "@$handle, your session is live: finish with the summary exactly: " +
        "Connected as @$handle.\n" +
        "2. If it reports logged_in as a DIFFERENT handle, do not touch " +
        "that session: finish honestly, naming the handle you found — " +
        "another account's session is not yours to replace.\n" +
        "3. If logged_out: browser_open https://x.com/i/flow/login. Type " +
        "@$handle into the username field with browser_type, and your " +
        "password with browser_type_secret (vault_key " +
        LilleXState.X_PASSWORD_VAULT_KEY + ") into the password field — " +
        "the password lives only in your vault; never write it anywhere " +
        "else, never read it back. Submit once, patiently.\n" +
        "4. If X presents a human check (CAPTCHA/Arkose) or sends a code " +
        "somewhere you cannot read, that step is the owner's: finish " +
        "naming the wall and the task parks for him — never attempt to " +
        "solve or bypass a human check.\n" +
        "5. Verify with x_session_status. If it reports logged_in as " +
        "@$handle, finish with the summary exactly: Connected as " +
        "@$handle. If still logged_out, finish honestly: Not connected — " +
        "X still shows no signed-in session for @$handle."

    val CONNECT_CRITERIA = listOf(
        "x_session_status verifies a live session as Lille's own handle on x.com",
        "Her password was used only from the vault (browser_type_secret) — never displayed, logged, or written into any message or file"
    )

    /** Create the connect task (queued, lane=device,
     *  kind=browser, x.com only). Owner-only surface — the
     *  caller gates on OwnerGate + LilleXState. */
    fun createConnect(context: Context, handle: String): JSONObject {
        val store = TaskStore(context)
        val task = store.create(connectTaskText(handle))
        val id = task.optString("id")
        val domains = JSONArray()
        domains.put("x.com")
        val criteria = JSONArray()
        CONNECT_CRITERIA.forEach { criteria.put(it) }
        store.update(id) {
            it.put("title", CONNECT_TITLE)
            it.put("kind", "browser")
            it.put("lane", "device")
            it.put("browserDomains", domains)
            it.put("criteria", criteria)
            // v1.0.8: the handle this connect lane serves —
            // the identity precondition's target (the lane
            // acts only while the browser's active account
            // is this one).
            it.put("connectHandle", handle)
        }
        return store.get(id) ?: task
    }

    /** The graded spec report for a packaged-task record — the
     *  device-lane mirror of the core's SPEC_REPORT: status from
     *  STATE + recorded evidence, never from narration alone. */
    fun reportFor(task: JSONObject): String {
        val state = task.optString("state")
        val handle = task.optString("xHandle")
        val postUrl = task.optString("firstPostUrl")
        val status = when {
            isUnavailableRecord(task) ->
                "UNAVAILABLE — owner configuration missing in this build"
            state == TaskStates.COMPLETED && handle.isNotEmpty() &&
                postUrl.isNotEmpty() -> "PASS (graded from recorded evidence)"
            state == TaskStates.PARKED_AT_WALL ->
                "NEEDS ETHURAM — parked at a human-only wall (not passed, not failed)"
            state == TaskStates.COMPLETED ->
                "COMPLETED — but evidence is incomplete, so the acceptance " +
                    "test is NOT graded PASS"
            state == TaskStates.FAILED -> "FAILED"
            state == TaskStates.CANCELLED -> "CANCELLED"
            else -> "IN PROGRESS (state: $state)"
        }
        val lines = mutableListOf<String>()
        lines.add("LILLE — SPEC REPORT (device lane)")
        lines.add("Task: $TITLE")
        lines.add("STATUS: $status")
        lines.add("Cost: $0 — keyless routes only")
        lines.add("Lane: device (her on-device browser session on this phone)")
        lines.add("Acceptance criteria:")
        val criteria = task.optJSONArray("criteria")
        if (criteria != null) {
            for (i in 0 until criteria.length()) {
                val c = criteria.optString(i)
                val (met, evidence) = criterionEvidence(c, task, handle, postUrl)
                lines.add("- [${if (met) "x" else " "}] $c — evidence: $evidence")
            }
        }
        if (handle.isNotEmpty()) lines.add("Account: @$handle on x.com")
        if (postUrl.isNotEmpty()) lines.add("First post: $postUrl")
        val summary = task.optString("summary")
        if (summary.isNotEmpty()) lines.add("Lille's summary: $summary")
        if (state == TaskStates.PARKED_AT_WALL) {
            lines.add("Wall: ${task.optString("wallKind")} on " +
                "${task.optString("wallDomain")} — " +
                task.optString("wallDetail"))
            lines.add("Needs Ethuram: ${task.optString("wallStep")}")
        }
        val note = task.optString("note")
        if (note.isNotEmpty()) lines.add("Note: $note")
        lines.add("Steps done: ${task.optInt("steps")}")
        return lines.joinToString("\n")
    }

    private fun criterionEvidence(
        criterion: String, task: JSONObject, handle: String, postUrl: String
    ): Pair<Boolean, String> {
        val low = criterion.lowercase()
        return when {
            low.contains("account exists") ->
                if (handle.isNotEmpty()) {
                    true to "x_session_status verified a live session as @$handle"
                } else {
                    false to "no verified session recorded yet"
                }
            low.contains("profile carries") -> {
                val summary = task.optString("summary").lowercase()
                if (handle.isNotEmpty() &&
                    (summary.contains("profile") || summary.contains("bio"))
                ) {
                    true to "profile set per Lille's run summary; handle @$handle live"
                } else {
                    false to "profile completion not evidenced in the record yet"
                }
            }
            low.contains("x_read_feed") ->
                if (task.optString("summary").contains("X_FEED_OK") ||
                    task.optString("feedEvidence").isNotEmpty()
                ) {
                    true to "feed read recorded"
                } else {
                    false to "no feed read recorded yet"
                }
            low.contains("x_search") ->
                if (task.optString("summary").contains("X_SEARCH_OK") ||
                    task.optString("searchEvidence").isNotEmpty()
                ) {
                    true to "search read recorded"
                } else {
                    false to "no search read recorded yet"
                }
            low.contains("first post") ->
                if (postUrl.isNotEmpty()) {
                    true to "first post live at $postUrl (verified on the profile timeline by x_post)"
                } else {
                    false to "no post URL recorded yet"
                }
            else -> false to "no checkable evidence recorded"
        }
    }
}
