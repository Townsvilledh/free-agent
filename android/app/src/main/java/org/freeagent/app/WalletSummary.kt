package org.freeagent.app

import java.math.BigInteger

/**
 * The main-page wallet card's summary (v1.0.1): her
 * companion wallet at a glance, across its families, with
 * the balances law in miniature — every figure is a real
 * value, a true zero, or an honest "couldn't load"; a
 * failed read NEVER renders as a number, and zero-balance
 * non-native tokens are not listed (the Assets-view rule).
 *
 * Pure formatting — MainActivity feeds it the engines'
 * AssetBalance lists; WalletActivity keeps the full view.
 */
object WalletSummary {

    /** One family's glance state. */
    sealed class Family {
        /** No keyset for this family yet. */
        object NotSetUp : Family()

        /** Still reading. */
        object Loading : Family()

        /** Loaded (or partially loaded) entries. */
        data class Loaded(
            val entries: List<WalletEngine.AssetBalance>
        ) : Family()
    }

    const val TITLE = "Lille's wallet"
    const val NOT_SET_UP_LINE = "Lille's wallet — not set up yet"

    /**
     * One family's line: "Bitcoin · BTC 0.0004" — the native
     * asset always shows (even a true zero); held non-native
     * tokens join it; a failed native read says so plainly.
     */
    fun familyLine(label: String, family: Family): String =
        when (family) {
            is Family.NotSetUp -> "$label · not set up"
            is Family.Loading -> "$label · loading…"
            is Family.Loaded -> loadedLine(label, family.entries)
        }

    private fun loadedLine(
        label: String, entries: List<WalletEngine.AssetBalance>
    ): String {
        if (entries.isEmpty()) return "$label · couldn't load"
        val native = entries.firstOrNull { it.token.contract == null }
        val parts = mutableListOf<String>()
        if (native == null || native.raw == null) {
            parts.add("couldn't load")
        } else {
            parts.add(
                "${native.token.symbol} " +
                    Evm.formatUnits(native.raw, native.token.decimals)
            )
        }
        for (e in entries) {
            if (e === native) continue
            val raw = e.raw ?: continue // failed token read: omitted, never zeroed
            if (raw == BigInteger.ZERO) continue // not held: not listed
            parts.add(
                "${e.token.symbol} " +
                    Evm.formatUnits(raw, e.token.decimals)
            )
        }
        // Other tokens failed while the native read fine: say
        // the glance is partial rather than complete-looking.
        val anyTokenFailed = entries.any {
            it !== native && it.raw == null
        }
        if (anyTokenFailed && native?.raw != null) {
            parts.add("some tokens couldn't load")
        }
        return "$label · " + parts.joinToString(" · ")
    }

    /** The card's full text: one line per family, in order. */
    fun cardText(families: List<Pair<String, Family>>): String =
        families.joinToString("\n") { (label, family) ->
            familyLine(label, family)
        }
}
