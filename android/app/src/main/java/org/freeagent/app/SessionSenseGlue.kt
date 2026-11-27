package org.freeagent.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/** The sense document's file (metadata only). */
class SessionSenseStore(context: Context) {

    private val file =
        File(context.filesDir, "lille_session_sense.json")

    @Synchronized
    fun root(): JSONObject = SessionSense.parseRoot(rawText())

    @Synchronized
    private fun writeRoot(root: JSONObject) {
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    fun rawText(): String? =
        if (file.exists()) {
            try { file.readText() } catch (e: Exception) { null }
        } else {
            null
        }

    /** Record a conclusion; returns true when the
     *  CONCLUSION changed. An unchanged conclusion
     *  still refreshes the sensed-at time — throttled
     *  to a minute, so a busy lane doesn't rewrite
     *  the file on every page. */
    @Synchronized
    fun recordConclusion(
        platform: String, handle: String?, url: String,
        source: String, now: Long
    ): Boolean {
        val root = root()
        val prevAt = SessionSense.sensedAt(root, platform)
        val changed = SessionSense.applyConclusion(
            root, platform, handle, url, source, now
        )
        if (changed || now - prevAt > 60_000L) writeRoot(root)
        return changed
    }

    @Synchronized
    fun setPendingNotification(text: String, now: Long) {
        val root = root()
        root.put(
            "pendingNotification",
            JSONObject().put("text", text).put("at", now)
        )
        writeRoot(root)
    }

    @Synchronized
    fun pendingNotification(): String? =
        root().optJSONObject("pendingNotification")
            ?.optString("text")?.takeIf { it.isNotEmpty() }

    @Synchronized
    fun clearPendingNotification() {
        val root = root()
        root.remove("pendingNotification")
        writeRoot(root)
    }
}

// ---------- the sense's effects (glue) ----------

/** The handles already verified as HERS — the sense's
 *  owner resolution input. */
private fun herHandles(
    context: Context, tasks: List<JSONObject>,
    registryRoot: JSONObject
): Set<String> {
    val out = mutableSetOf<String>()
    LilleXState.verifiedHandle(tasks)?.let { out.add(it) }
    for (t in tasks) {
        t.optString(FinishGate.HER_HANDLE_FIELD)
            .trim().ifEmpty { null }?.let { out.add(it) }
    }
    for (e in AccountsRegistry.entries(registryRoot)) {
        if (e.optString("owner") == AccountsRegistry.OWNER_LILLE) {
            out.add(e.optString("handle"))
        }
    }
    return out
}

/** The chat a session announcement belongs to: the
 *  origin chat of the newest live X record, else of
 *  the newest X record at all, else none (the
 *  announcement is held pending for the next open). */
private fun senseChatId(tasks: List<JSONObject>): String? {
    val x = tasks.filter { BrowserControl.isXDomainRecord(it) }
    if (x.isEmpty()) return null
    val byRecency = compareByDescending<JSONObject> {
        maxOf(it.optLong("createdAt"), it.optLong("updatedAt"))
    }
    val live = x.filter {
        !TaskStates.isTerminal(it.optString("state")) &&
            !TaskControl.isArchived(it)
    }
    val pick = live.maxWithOrNull(byRecency)
        ?: x.maxWithOrNull(byRecency)
    return pick?.optString(ChatTaskLink.ORIGIN_FIELD)
        ?.takeIf { it.isNotEmpty() }
}

/**
 * Apply a sensed conclusion's effects: the sense
 * itself, then — on a CHANGE — the registry sync
 * (when [syncRegistry]: a page settle syncs it; a
 * lane read's caller syncs it through its own path
 * and passes false), the contradiction pass over
 * the records (reality outranks stamps, with the
 * event), and the unprompted announcement.
 */
private fun applySenseChange(
    context: Context, platform: String, handle: String?,
    url: String, source: String, syncRegistry: Boolean
) {
    val now = System.currentTimeMillis()
    val senseStore = SessionSenseStore(context)
    val before = senseStore.root()
    val prevChecked = SessionSense.checked(before, platform)
    val prevHandle = SessionSense.handle(before, platform)
    val changed = senseStore.recordConclusion(
        platform, handle, url, source, now
    )
    if (!changed) return
    if (syncRegistry) {
        val registry = AccountRegistryStore(context)
        if (handle != null) {
            val owner = SessionSense.ownerForHandle(
                handle,
                herHandles(context, TaskStore(context).all(), registry.root())
            )
            registry.recordVerifiedSignIn(platform, handle, owner, now)
        } else {
            registry.recordSignedOut(platform)
        }
    }
    val taskStore = TaskStore(context)
    val tasks = taskStore.all()
    for (t in SessionSense.contradictions(tasks, handle, now)) {
        val id = t.optString("id")
        if (id.isEmpty()) continue
        taskStore.update(id) { rec ->
            val from = SessionIdentity.signedInAs(rec)
            SessionIdentity.stamp(rec, handle, now)
            EventLog.append(
                rec, "identity_sensed", now,
                JSONObject()
                    .put("from", from ?: "")
                    .put("to", handle ?: "")
                    .put("source", source)
            )
        }
    }
    val text = SessionSense.notificationFor(
        platform, prevChecked, prevHandle, handle
    ) ?: return
    val chatId = senseChatId(TaskStore(context).all())
    if (chatId != null) {
        ChatStore(context).appendMessage(chatId, "lille", text)
    } else {
        senseStore.setPendingNotification(text, now)
    }
}

/** PAGE SETTLE sensing: a collected page on a
 *  platform domain, sensed where the page itself
 *  derives the session. Called from
 *  BrowserSession.collect (every settle the lane
 *  reads) and from the panel-open / app-open
 *  refreshes over the last settled state. */
fun sensePageSettled(
    context: Context, state: BrowserPerception.PageState
) {
    val domain = BrowserPerception.domainOf(state.url)
    if (domain != "x.com" && !domain.endsWith(".x.com")) return
    val outcome = SessionSense.deriveFromPage(state) ?: return
    val handle = when (outcome) {
        is XIdentityRead.Outcome.SignedIn -> outcome.handle
        is XIdentityRead.Outcome.SignedOut -> null
        is XIdentityRead.Outcome.Unreadable -> return
    }
    applySenseChange(
        context, AccountsRegistry.PLATFORM_X, handle,
        state.url, SessionSense.SOURCE_PAGE,
        syncRegistry = true
    )
}

/** READ sensing: a concluding identity read (the
 *  navigation proof) is a sense update too. The
 *  caller (the runner's registry sync) performs
 *  the registry write itself, so this applies the
 *  sense + contradictions + announcement only. */
fun recordSensedRead(
    context: Context, handle: String?, url: String
) {
    applySenseChange(
        context, AccountsRegistry.PLATFORM_X, handle,
        url, SessionSense.SOURCE_READ,
        syncRegistry = false
    )
}

/** APP-OPEN / HEARTBEAT RECONCILIATION: re-sense
 *  the last settled page, then owe every stale X
 *  record its live re-read (the freshness rule's
 *  sweep — reality reconciles the records, with
 *  the re-check pass as the reader). Runs on app
 *  open and inside the watchdog's passes. */
fun senseAppOpenSweep(context: Context) {
    BrowserSession.lastState?.let { sensePageSettled(context, it) }
    val store = TaskStore(context)
    val tasks = store.all()
    val now = System.currentTimeMillis()
    for (id in SessionSense.recheckTaskIds(
        tasks, BrowserSession.lastActivityAt
    )) {
        store.update(id) {
            it.put(SessionIdentity.RECHECK_AT_FIELD, now)
        }
        TaskWorker.enqueueIdentityRecheck(context, id)
    }
}

/** Deliver a held session announcement into the
 *  chat on screen (the "while you were away"
 *  surface for a change sensed with no chat to
 *  belong to). */
fun deliverPendingSenseNotification(
    context: Context, chatId: String
) {
    if (chatId.isEmpty()) return
    val store = SessionSenseStore(context)
    val text = store.pendingNotification() ?: return
    if (ChatStore(context).appendMessage(chatId, "lille", text)) {
        store.clearPendingNotification()
    }
}
