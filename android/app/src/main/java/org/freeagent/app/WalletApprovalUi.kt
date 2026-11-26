package org.freeagent.app

import android.app.Activity
import androidx.appcompat.app.AlertDialog

/**
 * The approval sheet (v0.12) — one dialog the chat flow and
 * the Wallet screen both use, showing WalletGate's
 * plain-language sheet text for the exact prepared
 * transaction the signer will gate on. The user approves
 * THESE words or nothing is signed.
 *
 * Approval semantics (WALLET.md): the Approve tap is the
 * ONLY source of userApproved=true in the app. For an
 * unpriced transaction the button says so — tapping it is
 * the explicit raw-amount confirmation the gate requires.
 */
object WalletApprovalUi {

    fun show(
        activity: Activity,
        prepared: WalletGate.PreparedTx,
        fromAddress: String,
        onApprove: (rawAmountConfirmed: Boolean) -> Unit,
        onCancel: () -> Unit
    ) {
        val unpriced = prepared.usdCents == null
        val approveLabel = if (unpriced) {
            "I confirm the raw amount — approve"
        } else {
            "Approve"
        }
        AlertDialog.Builder(activity)
            .setTitle(if (prepared.isSweep) "Return funds to your Fluid wallet" else "Approve this send")
            .setMessage(WalletGate.approvalSheetText(prepared, fromAddress))
            .setPositiveButton(approveLabel) { _, _ -> onApprove(unpriced) }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    /** Sweep approval: one sheet listing every prepared leg,
     *  one tap covering the whole sweep-back. */
    fun showSweep(
        activity: Activity,
        prepared: List<WalletGate.PreparedTx>,
        fromAddress: String,
        identityLabel: String,
        onApprove: () -> Unit,
        onCancel: () -> Unit
    ) {
        val text = buildString {
            append("Lille will return everything in her wallet to your ")
            append("Fluid wallet (@$identityLabel):\n")
            for (p in prepared) {
                append("\n• ${p.amountDisplay} ${p.tokenSymbol} on ")
                append(WalletGate.chainByKey(p.chainKey)?.label ?: p.chainKey)
                if (p.usdCents != null) {
                    append(" (about ${WalletGate.formatUsd(p.usdCents)})")
                }
            }
            append("\n\nEach leg pays its own network fee from her wallet. ")
            append("This spends from LILLE'S wallet only — your Fluid wallet ")
            append("is never touched.")
        }
        AlertDialog.Builder(activity)
            .setTitle("Return funds to your Fluid wallet")
            .setMessage(text)
            .setPositiveButton("Approve — return it all") { _, _ -> onApprove() }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
