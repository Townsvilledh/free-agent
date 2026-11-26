package org.freeagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lille's wallet — persisted state (v0.12): the user's caps
 * and autonomous switch, the rolling 24 h spend log, and
 * her wallet activity list. Plain SharedPreferences: none
 * of this is secret (amounts she spent are the user's own
 * record, shown back to them in the Wallet screen), and the
 * cap VALUES are WalletGate.CapState — the pure component
 * owns the arithmetic; this class only serialises it.
 *
 * The spend log persists across restarts by construction
 * (it is written on every recorded spend), which is what
 * "daily totals persist across restarts" means in code.
 */
class WalletStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        "lille_wallet_state", Context.MODE_PRIVATE
    )

    data class ActivityEntry(
        val atMs: Long,
        val kind: String,       // CREATED | PREPARED | REFUSED | SENT | CONFIRMED | FAILED | SWEEP
        val text: String,
        val txHash: String?
    )

    companion object {
        private const val MAX_ACTIVITY = 100
    }

    // ---- caps + switch ----

    fun capState(): WalletGate.CapState {
        val spends = mutableListOf<WalletGate.Spend>()
        try {
            val arr = JSONArray(prefs.getString("spends", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                spends.add(WalletGate.Spend(o.getLong("at"), o.getLong("cents")))
            }
        } catch (e: Exception) {
            // A corrupt log reads as no spends — and the
            // per-tx cap still binds every single spend.
        }
        return WalletGate.CapState(
            perTxCapCents = prefs.getLong(
                "cap_per_tx_cents", WalletGate.DEFAULT_PER_TX_CAP_CENTS
            ),
            dailyCapCents = prefs.getLong(
                "cap_daily_cents", WalletGate.DEFAULT_DAILY_CAP_CENTS
            ),
            spends = spends
        )
    }

    fun saveCaps(perTxCents: Long, dailyCents: Long) {
        prefs.edit()
            .putLong("cap_per_tx_cents", perTxCents.coerceAtLeast(0))
            .putLong("cap_daily_cents", dailyCents.coerceAtLeast(0))
            .apply()
    }

    fun recordSpend(state: WalletGate.CapState) {
        val arr = JSONArray()
        for (s in state.spends) {
            arr.put(JSONObject().put("at", s.atMs).put("cents", s.usdCents))
        }
        prefs.edit().putString("spends", arr.toString()).apply()
    }

    fun autonomousOn(): Boolean = prefs.getBoolean("autonomous", false)

    /** The autonomous switch is set ONLY from the Wallet
     *  settings UI — this setter is never called by chat,
     *  task, or model-reachable code (WALLET.md). */
    fun setAutonomous(on: Boolean) {
        prefs.edit().putBoolean("autonomous", on).apply()
    }

    // ---- activity ----

    fun recordActivity(kind: String, text: String, txHash: String? = null) {
        val entries = activity().toMutableList()
        entries.add(
            0,
            ActivityEntry(System.currentTimeMillis(), kind, text, txHash)
        )
        val trimmed = entries.take(MAX_ACTIVITY)
        val arr = JSONArray()
        for (e in trimmed) {
            arr.put(
                JSONObject()
                    .put("at", e.atMs)
                    .put("kind", e.kind)
                    .put("text", e.text)
                    .put("hash", e.txHash ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString("activity", arr.toString()).apply()
    }

    fun activity(): List<ActivityEntry> {
        val out = mutableListOf<ActivityEntry>()
        try {
            val arr = JSONArray(prefs.getString("activity", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    ActivityEntry(
                        atMs = o.getLong("at"),
                        kind = o.getString("kind"),
                        text = o.getString("text"),
                        txHash = if (o.isNull("hash")) null else o.getString("hash")
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupt activity reads as empty, never as a crash.
        }
        return out
    }
}
