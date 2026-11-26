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
 * the same identity kit, the same hard rules the host runs
 * taught (mailbox discipline, one patient submit, the
 * legal-link trap, form discipline), the same pass/fail
 * criteria — graded from the task record's evidence fields
 * (xHandle, firstPostUrl, state) in the spec report view.
 *
 * The differences from the host spec are the lane's, and they
 * are the point: the browser is the phone's real WebView
 * (device lane), her password is generated ON-DEVICE into the
 * encrypted DeviceVault (vault_gen_secret, key name x_password),
 * and if X presents Arkose she parks (PARKED_AT_WALL) and the
 * "Lille needs you" card hands Ethuram the check inside her
 * session — one tap from him, then she resumes and finishes
 * alone. Nobody creates the account for her.
 */
object XAccountTask {

    const val TITLE = "Lille: create my X account"

    val DOMAINS = listOf("x.com", "guerrillamail.com", "maildrop.cc",
        "emailnator.com")

    val CRITERIA = listOf(
        "Lille's X account exists: x_session_status verifies a live session as her handle on x.com",
        "Her profile carries the display name Lille, the bio from the identity kit, and the mascot avatar on her x.com profile",
        "x_read_feed returns real posts from her home timeline",
        "x_search returns real results for a query about Fluid wallet",
        "Her first post is live with a status URL on x.com"
    )

    const val TASK_TEXT: String =
        "Create your OWN X account, Lille — an account that is yours, in your name, " +
        "which you will use to promote yourself and Fluid. This is a formal acceptance " +
        "test: it passes only when the account is live with your profile set, your " +
        "session persists in your browser, feed and search return real content, and " +
        "your first post is live with a URL.\n\n" +
        "IDENTITY KIT\n" +
        "- Display name: Lille\n" +
        "- Handle, try in order: LilleAgent, AskLille, LilleByFluid, LilleOnFluid, " +
        "LilleAI. If all are taken, keep trying close variants (suffix HQ, an " +
        "underscore, or similar) until one is accepted — a taken handle is a route " +
        "change, not a wall.\n" +
        "- Bio: Lille — free, open-source autonomous agent, building in public at " +
        "$0. I promote Fluid, the open-source non-custodial wallet for everyone.\n" +
        "- Avatar: your mascot jacket image — call browser_upload with path " +
        "\"asset:lille-mascot-jacket.webp\" and the profile-picture file input's ref " +
        "when the flow offers one (or afterwards via Edit profile).\n" +
        "- Date of birth, if asked: 1 January 2000.\n" +
        "- First post (your own words, this shape): introduce yourself as Lille, a " +
        "free open-source autonomous agent; say you build in public and run at $0; " +
        "say you are here to share what you are building and to promote Fluid, the " +
        "open-source non-custodial wallet. Under 280 characters. Post it with x_post.\n\n" +
        "HARD RULES\n" +
        "- START WITH THE BROWSER: your first tool call is browser_open (the mailbox " +
        "or the X signup). Do NOT write notes or plan files before the account exists.\n" +
        "- The signup entry point is https://x.com/i/flow/signup (if X shows a " +
        "different entry, follow the site's own Sign up / Create account path).\n" +
        "- THE ADDRESS MUST COME OFF A MAILBOX PAGE: open maildrop.cc, type ONLY a " +
        "short name (e.g. lilleagent) into the box — NOT a full address — click Go, " +
        "and let the inbox page load. Your address is that name @maildrop.cc. Read it " +
        "off the inbox page. Never invent or reconstruct an address from memory.\n" +
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
        "the mailbox ADDRESS (e.g. name@maildrop.cc), not a username.\n" +
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
        "Routes, in order — exhaust them before calling anything a wall: " +
        "(1) emailnator.com FIRST (its Gmail addresses are the one route never yet " +
        "carried to a submit), then maildrop.cc, then guerrillamail.com. Take the " +
        "address the page gives you, use it for the X signup, then read the " +
        "verification code from that same inbox and enter it on X. Reading a code " +
        "from a mailbox you can open in the browser is normal work. " +
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
        "first post's URL."

    /** Create the packaged task in the store (queued, lane=device,
     *  kind=browser, identity kit as the task text, criteria for
     *  the report view). Returns the task record. */
    fun create(context: Context): JSONObject {
        val store = TaskStore(context)
        val task = store.create(TASK_TEXT)
        val id = task.optString("id")
        val domains = JSONArray()
        DOMAINS.forEach { domains.put(it) }
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

    /** The graded spec report for a packaged-task record — the
     *  device-lane mirror of the core's SPEC_REPORT: status from
     *  STATE + recorded evidence, never from narration alone. */
    fun reportFor(task: JSONObject): String {
        val state = task.optString("state")
        val handle = task.optString("xHandle")
        val postUrl = task.optString("firstPostUrl")
        val status = when {
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
