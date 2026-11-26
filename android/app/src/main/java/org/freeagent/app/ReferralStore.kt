package org.freeagent.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The referral ledger's file (REFERRALS.md): one JSON
 * document in the app's private storage
 * (filesDir/lille_referrals.json), rewritten atomically
 * (tmp + rename) on every change — the ChatStore/TaskStore
 * discipline. All shape decisions live in the pure
 * [Referrals] component; this class owns only the file and
 * the one-call operations the screens need.
 *
 * The ledger holds this device's pending inviter (set when
 * an invite link reaches the app before sign-in) and the
 * completed referral records this device knows about: the
 * record of who invited THIS user, plus every record merged
 * in through the export/import sync path. It rides the
 * v0.11 encrypted backup (BackupPayload's "referrals"
 * field, merged — never overwritten — on restore), so a
 * phone switch keeps the count.
 */
class ReferralStore(context: Context) {

    private val file = File(context.filesDir, "lille_referrals.json")

    @Synchronized
    fun root(): JSONObject = Referrals.parseLedger(rawText())

    @Synchronized
    private fun writeRoot(root: JSONObject) {
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    /** Backup snapshot (v0.11 pattern): the raw ledger text,
     *  or null when no ledger exists yet. */
    fun rawText(): String? =
        if (file.exists()) try { file.readText() } catch (e: Exception) { null } else null

    /** Merge a backup's or an imported file's ledger into
     *  this one (first referrer wins — see Referrals). Used
     *  by backup restore and by Settings → Import. Returns
     *  how many records were added. */
    @Synchronized
    fun mergeFrom(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val root = root()
        val added = Referrals.mergeLedgers(root, text)
        if (added > 0) writeRoot(root)
        return added
    }

    /** An invite link reached the app: remember its inviter
     *  until sign-in. First touch wins. */
    @Synchronized
    fun setPendingFromLink(rawReferrer: String) {
        val root = root()
        val before = Referrals.pendingReferrer(root)
        Referrals.setPending(root, rawReferrer, Referrals.SOURCE_LINK, System.currentTimeMillis())
        if (Referrals.pendingReferrer(root) != before) writeRoot(root)
    }

    fun pendingReferrer(): String? = Referrals.pendingReferrer(root())

    /** Settle at a successful sign-in: [typedReferrer] is the
     *  login screen's optional inviter field (may be blank);
     *  the stored pending inviter is the fallback. Safe to
     *  call on every sign-in/open — once a record exists for
     *  the name, later calls change nothing. */
    @Synchronized
    fun settleOnLogin(myName: String, typedReferrer: String?): Referrals.Outcome {
        val root = root()
        val outcome = Referrals.settle(root, myName, typedReferrer, System.currentTimeMillis())
        writeRoot(root)
        return outcome
    }

    fun completedCount(myName: String?): Int =
        Referrals.completedCountFor(root(), myName)

    /** The sync export: this ledger's completed records as
     *  JSON (REFERRALS.md — between the user's own devices,
     *  and the back-end count's input). */
    fun exportText(): String = Referrals.exportJson(root())
}
