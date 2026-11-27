package org.freeagent.app

/**
 * THE CAPABILITY SHEET (v1.0.5) — what she can actually
 * do, as a fact block in the chat brain's context.
 *
 * Born of the 2026-11-27 screenshot: asked "why paused",
 * the chat brain answered that the work "requires
 * interactive browser-based tools… that aren't available
 * in this environment" and offered the user instructions
 * to run locally. Every clause was false on the build he
 * was running — the browser works in every job, Gmail and
 * calendar are device tools, code/video route to the
 * computer lane. Plain chat had NO grounding in her real
 * capabilities, so a weak route invented a smaller Lille
 * and spoke for her.
 *
 * This sheet is generated deterministically from the
 * build's actual feature set plus the live connection
 * state the glue reads at ask time (Gmail connected or
 * not, her X account's standing, which wallet families
 * exist, whether the user's own computer is configured
 * as a brain route). It rides every chat system prompt,
 * in plain terms the model can quote but not contradict.
 * The chat-output guard's capability-denial rule is the
 * net behind it: a reply that denies a capability she
 * has is regenerated once with [REASSERT_LINE] appended,
 * and a second offence is replaced by [GROUNDED_LINE].
 *
 * Pure; pinned by Lille105Test.
 */
object CapabilitySheet {

    /** Live connection state, read by the glue at ask
     *  time — the sheet is only as honest as these facts. */
    data class Facts(
        val gmailConnected: Boolean,
        val xLine: String,
        val walletFamilies: List<String>,
        val lanConfigured: Boolean
    )

    /** Her X lane's standing, in one plain clause for the
     *  sheet: a verified account, a creation in flight, or
     *  the not-yet state (creation/connect are packaged
     *  jobs the chat menu offers — she never claims an
     *  account she does not have). */
    fun xLineFor(
        verifiedHandle: String?,
        creationInProgress: Boolean
    ): String = when {
        !verifiedHandle.isNullOrEmpty() ->
            "her own X account exists (@$verifiedHandle) and she " +
                "can reconnect it in a task"
        creationInProgress ->
            "her own X account is being created by a task right now"
        else ->
            "she can create her own X account from the chat " +
                "actions, and connect the user's own X account " +
                "in a task"
    }

    /** The fact block appended to the chat system prompt. */
    fun render(facts: Facts): String {
        val out = StringBuilder()
        out.append(
            "WHAT YOU CAN ACTUALLY DO IN THIS APP — facts about " +
                "yourself; never contradict them:\n"
        )
        out.append(
            "- You work through tasks on this phone: when the user " +
                "gives you a job in chat, it runs as a task with " +
                "real tools, in this same conversation.\n"
        )
        out.append(
            "- Browser: you have a working browser on this phone. " +
                "In a task you can open sites, read pages, fill " +
                "forms and sign in where the user asks — in any " +
                "job, not only in special ones.\n"
        )
        if (facts.gmailConnected) {
            out.append(
                "- Email: Gmail is connected on this phone — in a " +
                    "task you can list, search and read the user's " +
                    "mail. Sending is a separate switch and may be " +
                    "off; if it is off, say sending is switched " +
                    "off, never that email does not work here.\n"
            )
        } else {
            out.append(
                "- Email: Gmail is not connected on this phone " +
                    "yet — the user can connect it in Settings " +
                    "(Gmail → Connect), one step. Until then say " +
                    "mail is connectable in Settings, never that " +
                    "email tools are unavailable.\n"
            )
        }
        out.append(
            "- Calendar: you can read the user's calendar and " +
                "create events (with the user's approval) in a " +
                "task.\n"
        )
        if (facts.walletFamilies.isNotEmpty()) {
            out.append(
                "- Wallet: your own agent wallet is set up for " +
                    facts.walletFamilies.joinToString(", ") +
                    ", with sending under the user's caps and " +
                    "approvals.\n"
            )
        } else {
            out.append(
                "- Wallet: your own agent wallet exists in the " +
                    "app; its families are set up from the wallet " +
                    "card in this chat.\n"
            )
        }
        out.append(
            "- Code execution and video work: those run on the " +
                "computer lane. When a job needs them, the task " +
                "hands that part over with a written brief and " +
                "does the rest here — never say those tools do " +
                "not exist or are unavailable.\n"
        )
        out.append("- X (Twitter): ").append(facts.xLine).append(".\n")
        out.append(
            "- Your own identity: your X handle, your mailbox " +
                "and your account passwords are YOURS — already " +
                "set up on this phone (your password was " +
                "generated by you and lives only in your " +
                "encrypted vault). Never ask the user for any " +
                "of them, and never ask for any password in " +
                "chat. If a service demands a phone check, that " +
                "step is parked in your browser panel for the " +
                "user to pass there — it is never a chat " +
                "question.\n"
        )
        if (facts.lanConfigured) {
            out.append(
                "- The user has also pointed you at their own " +
                    "computer as an extra brain.\n"
            )
        }
        out.append(
            "If a step is genuinely the user's alone — a sign-in " +
                "tap or a human check — name that one step. Never " +
                "claim a tool you have is unavailable in this " +
                "environment, and never offer the user instructions " +
                "to run locally in place of doing the work."
        )
        return out.toString()
    }

    /** Appended to the sheet for the ONE silent
     *  regeneration after a capability-denial rejection:
     *  the sheet, re-asserted as a correction. */
    const val REASSERT_LINE =
        "Correction: your previous reply contradicted the facts " +
            "above. Answer again using only those facts — do not " +
            "claim any listed capability is unavailable, and do " +
            "not offer the user instructions to run locally."

    /** The one grounded line that replaces a reply when a
     *  regenerated answer denies her capabilities a second
     *  time. Plain, true on every build, and itself clean
     *  under both guards (pinned). */
    const val GROUNDED_LINE =
        "I do have the tools for that here — my browser, " +
            "calendar and wallet all work in this app, and jobs " +
            "run as tasks in this conversation. Give me the job " +
            "and I'll take it on."
}
