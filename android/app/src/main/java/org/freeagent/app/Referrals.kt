package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Referrals — Lille invites (REFERRALS.md). The pure decision
 * component: no Android, no I/O, pinned by JVM tests. The
 * Android glue is ReferralStore (the file), MainActivity
 * (invite-link capture), LoginActivity (the optional inviter
 * field + the completion hook) and SettingsActivity (the
 * user's link, share action and count).
 *
 * The design, as directed (2026-11-26, corrected the same
 * day): every Lille user gets a personal invite link. The
 * link, its landing page and its pitch are purely about
 * Lille — they carry NO Fluid branding and the word "fluid"
 * appears nowhere in them (the tests pin that). A referral
 * COMPLETES only when the invited person installs Lille AND
 * signs in with their own name — the sign-in is the proof,
 * because the login gate (FluidGate) verifies the connected
 * wallet owns the name. First referrer wins: a name can be
 * referred once, ever. No self-referral.
 *
 * How the inviter's name reaches a fresh install: the link
 * opens the landing page in a browser (install breaks any
 * automatic chain), so the landing page shows the inviter's
 * name and the new user enters it on Lille's login screen —
 * one optional field. If Lille is already installed, the
 * same link (verified App Link, or the lille:// scheme from
 * the landing page's open-in-app button) lands here as a
 * PENDING referrer, pre-fills that field, and the user can
 * edit or clear it before signing in. Whatever stands in
 * the field at the first successful sign-in is what counts.
 *
 * The ledger lives on-device (ReferralStore). Completed
 * records are the count's only source: Settings shows how
 * many records name the user as referrer. Records move
 * between a user's own devices — and to the operator's
 * back-end count — through the export/import sync path
 * (REFERRALS.md): an export is this ledger's records as
 * JSON, imports merge under the same first-wins rule, and
 * the core tool (free_agent/referrals.py) merges exports
 * the same way. Nothing about that bookkeeping is ever
 * mentioned in user-facing copy.
 */
object Referrals {

    /** The invite landing page (docs/invite.html, served by
     *  GitHub Pages from the repo's docs/ folder). */
    const val LANDING_URL =
        "https://townsvilledh.github.io/free-agent/invite.html"

    /** The custom-scheme twin of the landing link, for the
     *  "open in the app" path when Lille is installed. */
    const val APP_LINK_PREFIX = "lille://invite"

    const val SOURCE_LINK = "link"
    const val SOURCE_NAME = "name"

    private const val LEDGER_VERSION = 1

    data class Record(
        val referrer: String,
        val referred: String,
        val completedAt: Long,
        val source: String
    )

    /** What a settle attempt did — the login glue acts on
     *  Completed only for copy; every outcome clears the
     *  pending slot so a stale inviter never lingers. */
    sealed class Outcome {
        data class Completed(val record: Record) : Outcome()
        object SelfReferral : Outcome()
        object AlreadyRecorded : Outcome()
        object NoReferrer : Outcome()
    }

    // ---------- the link + pitch ----------

    /** The user's personal invite link, or null when the
     *  name is blank. The name rides as the `ref` query
     *  parameter, percent-encoded. */
    fun inviteLink(rawUsername: String?): String? {
        val name = FluidGate.normaliseName(rawUsername ?: "")
        if (name.isEmpty()) return null
        return LANDING_URL + "?ref=" + URLEncoder.encode(name, "UTF-8")
    }

    /** The share pitch. Purely about Lille — the tests pin
     *  that it never mentions the login's other product. */
    fun shareText(rawUsername: String?): String? {
        val name = FluidGate.normaliseName(rawUsername ?: "")
        val link = inviteLink(name) ?: return null
        return "Try Lille — a free, open-source AI agent for your phone. " +
            "She chats, runs tasks in the background, browses the web and " +
            "keeps everything on your device. Get her here: $link — and " +
            "when she asks who invited you, my name is $name."
    }

    /** The inviter's name out of an invite link that reached
     *  the app (the https App Link or the lille:// scheme).
     *  Null for anything else — other links are not invites
     *  and must never set a referrer. */
    fun referrerFromUri(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        fun bounded(prefix: String): Boolean =
            uri == prefix || uri.startsWith("$prefix?") || uri.startsWith("$prefix#")
        if (!bounded(LANDING_URL) && !bounded(APP_LINK_PREFIX)) return null
        val query = uri.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            val key = part.substringBefore('=')
            if (key != "ref") continue
            val raw = part.substringAfter('=', "")
            val decoded = try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (e: Exception) {
                return null
            }
            val name = FluidGate.normaliseName(decoded)
            return if (name.isEmpty()) null else name
        }
        return null
    }

    // ---------- the ledger document ----------

    fun emptyLedger(): JSONObject =
        JSONObject()
            .put("version", LEDGER_VERSION)
            .put("records", JSONArray())

    /** Defensive parse, ChatData-style: an unreadable or
     *  malformed document becomes an empty ledger, and every
     *  record is re-validated (names normalised, self-pairs
     *  and duplicates dropped, earliest completion kept). */
    fun parseLedger(text: String?): JSONObject {
        if (text.isNullOrBlank()) return emptyLedger()
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return emptyLedger()
        }
        val out = emptyLedger()
        val seen = HashSet<String>()
        val records = recordsOf(root)
            .sortedBy { it.completedAt }
        val arr = JSONArray()
        for (record in records) {
            if (!seen.add(record.referred)) continue
            arr.put(recordJson(record))
        }
        out.put("records", arr)
        val pending = root.optJSONObject("pending")
        if (pending != null) {
            val name = FluidGate.normaliseName(pending.optString("referrer", ""))
            if (name.isNotEmpty()) {
                out.put(
                    "pending",
                    JSONObject()
                        .put("referrer", name)
                        .put("source", pending.optString("source", SOURCE_LINK))
                        .put("at", pending.optLong("at", 0L))
                )
            }
        }
        return out
    }

    fun recordsOf(root: JSONObject): List<Record> {
        val arr = root.optJSONArray("records") ?: return emptyList()
        val out = ArrayList<Record>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val referrer = FluidGate.normaliseName(item.optString("referrer", ""))
            val referred = FluidGate.normaliseName(item.optString("referred", ""))
            if (referrer.isEmpty() || referred.isEmpty()) continue
            if (referrer == referred) continue
            out.add(
                Record(
                    referrer = referrer,
                    referred = referred,
                    completedAt = item.optLong("at", 0L),
                    source = item.optString("source", SOURCE_NAME)
                )
            )
        }
        return out
    }

    fun recordJson(record: Record): JSONObject =
        JSONObject()
            .put("referrer", record.referrer)
            .put("referred", record.referred)
            .put("at", record.completedAt)
            .put("source", record.source)

    fun pendingReferrer(root: JSONObject): String? {
        val pending = root.optJSONObject("pending") ?: return null
        val name = FluidGate.normaliseName(pending.optString("referrer", ""))
        return if (name.isEmpty()) null else name
    }

    /** Record an invite link's inviter as pending. First
     *  touch wins: a later link never overwrites an earlier
     *  pending inviter (the user can still edit the field on
     *  the login screen — that is their hand, not a link's). */
    fun setPending(root: JSONObject, rawReferrer: String, source: String, nowMs: Long) {
        if (pendingReferrer(root) != null) return
        val name = FluidGate.normaliseName(rawReferrer)
        if (name.isEmpty()) return
        root.put(
            "pending",
            JSONObject()
                .put("referrer", name)
                .put("source", source)
                .put("at", nowMs)
        )
    }

    fun clearPending(root: JSONObject) {
        root.remove("pending")
    }

    /** Settle the referral at a successful first sign-in.
     *  The effective inviter is the typed field when it is
     *  non-blank, otherwise the pending inviter. Completion
     *  is recorded at most once per name — a name that
     *  already has a record is AlreadyRecorded whatever
     *  inviter is offered now (first referrer wins). */
    fun settle(
        root: JSONObject,
        rawMyName: String,
        typedReferrer: String?,
        nowMs: Long
    ): Outcome {
        val me = FluidGate.normaliseName(rawMyName)
        if (me.isEmpty()) return Outcome.NoReferrer
        val typed = FluidGate.normaliseName(typedReferrer ?: "")
        val pending = pendingReferrer(root)
        val effective = if (typed.isNotEmpty()) typed else pending
        if (recordsOf(root).any { it.referred == me }) {
            clearPending(root)
            return Outcome.AlreadyRecorded
        }
        if (effective.isNullOrEmpty()) return Outcome.NoReferrer
        if (effective == me) {
            clearPending(root)
            return Outcome.SelfReferral
        }
        val record = Record(
            referrer = effective,
            referred = me,
            completedAt = nowMs,
            source = if (typed.isNotEmpty()) SOURCE_NAME else SOURCE_LINK
        )
        val arr = root.optJSONArray("records") ?: JSONArray()
        arr.put(recordJson(record))
        root.put("records", arr)
        clearPending(root)
        return Outcome.Completed(record)
    }

    /** How many completed referrals name this user as the
     *  inviter — the Settings count's only source. */
    fun completedCountFor(root: JSONObject, rawUsername: String?): Int {
        val me = FluidGate.normaliseName(rawUsername ?: "")
        if (me.isEmpty()) return 0
        return recordsOf(root).count { it.referrer == me }
    }

    /** The export document: the ledger's records only (the
     *  pending slot is this device's private state and never
     *  travels). This is the sync unit — between the user's
     *  own devices and to the back-end count. */
    fun exportJson(root: JSONObject): String {
        val out = emptyLedger()
        val arr = JSONArray()
        for (record in recordsOf(root).sortedBy { it.completedAt }) {
            arr.put(recordJson(record))
        }
        out.put("records", arr)
        return out.toString()
    }

    /** Merge an incoming ledger/export into [into]: union of
     *  records; on a clash for the same referred name the
     *  EARLIEST completion stands (first referrer wins,
     *  across devices too). The local pending slot is kept;
     *  the incoming one is ignored. Returns how many records
     *  were added. */
    fun mergeLedgers(into: JSONObject, incomingText: String?): Int {
        val incoming = parseLedger(incomingText)
        val byReferred = LinkedHashMap<String, Record>()
        for (record in recordsOf(into)) byReferred[record.referred] = record
        var added = 0
        for (record in recordsOf(incoming)) {
            val existing = byReferred[record.referred]
            if (existing == null) {
                byReferred[record.referred] = record
                added++
            } else if (record.completedAt < existing.completedAt) {
                byReferred[record.referred] = record
            }
        }
        val arr = JSONArray()
        for (record in byReferred.values.sortedBy { it.completedAt }) {
            arr.put(recordJson(record))
        }
        into.put("records", arr)
        return added
    }
}
