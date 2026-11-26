package org.freeagent.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lille's wallet screen (v0.12, WALLET.md) — her agentic
 * (companion) wallet, whole life in this one screen: create,
 * fund (her address, shown for copying), balances, send
 * (prepare → approval sheet → gated signer), sweep-back to
 * the user's Fluid address, caps + the autonomous switch,
 * and her activity list. Nothing here leaves the app: no
 * step sends the user to Fluid or a browser ("sign where
 * you stand" — her own key signs here; the main Fluid
 * wallet is a separate thing this screen never touches).
 */
class WalletActivity : AppCompatActivity() {

    private lateinit var wallet: AgentWallet
    private lateinit var store: WalletStore
    private lateinit var engine: WalletEngine
    private lateinit var btcEngine: BtcEngine
    private lateinit var solEngine: SolEngine

    private lateinit var createBox: View
    private lateinit var mainBox: View
    private lateinit var addressView: TextView
    private lateinit var btcAddressView: TextView
    private lateinit var solAddressView: TextView
    private lateinit var createBtcButton: Button
    private lateinit var createSolButton: Button
    private lateinit var sweepButton: Button
    private lateinit var balancesView: TextView
    private lateinit var statusView: TextView
    private lateinit var activityView: TextView
    private lateinit var chainSpinner: Spinner
    private lateinit var assetSpinner: Spinner
    private lateinit var amountInput: EditText
    private lateinit var usdCheck: CheckBox
    private lateinit var recipientInput: EditText
    private lateinit var capPerTxInput: EditText
    private lateinit var capDailyInput: EditText
    private lateinit var capPerTxOff: CheckBox
    private lateinit var capDailyOff: CheckBox
    private lateinit var autonomousSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet)
        wallet = AgentWallet(this)
        store = WalletStore(this)
        engine = WalletEngine(this)
        btcEngine = BtcEngine(this)
        solEngine = SolEngine(this)

        createBox = findViewById(R.id.walletCreateBox)
        mainBox = findViewById(R.id.walletMainBox)
        addressView = findViewById(R.id.walletAddress)
        btcAddressView = findViewById(R.id.walletBtcAddress)
        solAddressView = findViewById(R.id.walletSolAddress)
        createBtcButton = findViewById(R.id.walletCreateBtcButton)
        createSolButton = findViewById(R.id.walletCreateSolButton)
        sweepButton = findViewById(R.id.walletSweepButton)
        balancesView = findViewById(R.id.walletBalances)
        statusView = findViewById(R.id.walletStatus)
        activityView = findViewById(R.id.walletActivity)
        chainSpinner = findViewById(R.id.walletChainSpinner)
        assetSpinner = findViewById(R.id.walletSendAsset)
        amountInput = findViewById(R.id.walletSendAmount)
        usdCheck = findViewById(R.id.walletSendUsd)
        recipientInput = findViewById(R.id.walletSendRecipient)
        capPerTxInput = findViewById(R.id.walletCapPerTx)
        capDailyInput = findViewById(R.id.walletCapDaily)
        capPerTxOff = findViewById(R.id.walletCapPerTxOff)
        capDailyOff = findViewById(R.id.walletCapDailyOff)
        autonomousSwitch = findViewById(R.id.walletAutonomousSwitch)

        // The network list: the seven EVM chains, then the
        // two other families. Selection position decides the
        // family (see selectedFamily()).
        chainSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            WalletGate.CHAINS.map { it.label } + listOf("Bitcoin", "Solana")
        )
        // The asset list belongs to the selected network:
        // switching swaps the assets (BNB is not an Ethereum
        // asset; BTC lives only on Bitcoin) and reloads that
        // network's balances. The sweep button exists only
        // on EVM chains — the sweep destination on record is
        // the user's Fluid EVM address (WALLET.md).
        chainSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?, position: Int, id: Long
                ) {
                    setAssetAdapter(
                        tokensForSelection(position).map { it.symbol }
                    )
                    sweepButton.visibility =
                        if (position < WalletGate.CHAINS.size) View.VISIBLE
                        else View.GONE
                    recipientInput.setHint(
                        when (familyForSelection(position)) {
                            WalletGate.FAMILY_BITCOIN ->
                                R.string.wallet_send_recipient_hint_btc
                            WalletGate.FAMILY_SOLANA ->
                                R.string.wallet_send_recipient_hint_sol
                            else -> R.string.wallet_send_recipient_hint
                        }
                    )
                    if (wallet.hasWallet()) refreshBalances()
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) = Unit
            }
        setAssetAdapter(WalletGate.ETHEREUM.tokens.map { it.symbol })

        findViewById<Button>(R.id.walletCreateButton).setOnClickListener {
            // One tap creates all three keysets — "her
            // wallet" is every family.
            if (!wallet.createAllWallets()) {
                Toast.makeText(
                    this, "Her wallet couldn't be created — the vault refused.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                store.recordActivity(
                    "CREATED",
                    "Lille's wallet created: ${wallet.address()} · " +
                        "Bitcoin: ${wallet.btcAddress()} · " +
                        "Solana: ${wallet.solAddress()}"
                )
                render()
            }
        }
        findViewById<Button>(R.id.walletCopyAddressButton).setOnClickListener {
            copyAddress(addressView.text.toString())
        }
        findViewById<Button>(R.id.walletCopyBtcButton).setOnClickListener {
            wallet.btcAddress()?.let { copyAddress(it) }
        }
        findViewById<Button>(R.id.walletCopySolButton).setOnClickListener {
            wallet.solAddress()?.let { copyAddress(it) }
        }
        createBtcButton.setOnClickListener {
            if (wallet.createBtcWallet() == null) {
                Toast.makeText(
                    this, "Her Bitcoin wallet couldn't be created — the vault refused.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                store.recordActivity(
                    "CREATED", "Lille's Bitcoin wallet created: ${wallet.btcAddress()}"
                )
                render()
            }
        }
        createSolButton.setOnClickListener {
            if (wallet.createSolWallet() == null) {
                Toast.makeText(
                    this, "Her Solana wallet couldn't be created — the vault refused.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                store.recordActivity(
                    "CREATED", "Lille's Solana wallet created: ${wallet.solAddress()}"
                )
                render()
            }
        }
        findViewById<Button>(R.id.walletRefreshButton).setOnClickListener {
            refreshBalances()
        }
        findViewById<Button>(R.id.walletSendButton).setOnClickListener {
            prepareFromForm()
        }
        findViewById<Button>(R.id.walletSweepButton).setOnClickListener {
            prepareSweep()
        }
        findViewById<Button>(R.id.walletSaveCapsButton).setOnClickListener {
            saveCaps()
        }
        render()
    }

    private fun selectedChain(): WalletGate.Chain =
        WalletGate.CHAINS[chainSpinner.selectedItemPosition.coerceIn(
            0, WalletGate.CHAINS.size - 1
        )]

    /** The family a spinner position selects: the EVM
     *  chains first, then Bitcoin, then Solana. */
    private fun familyForSelection(position: Int): String = when {
        position < WalletGate.CHAINS.size -> WalletGate.FAMILY_EVM
        position == WalletGate.CHAINS.size -> WalletGate.FAMILY_BITCOIN
        else -> WalletGate.FAMILY_SOLANA
    }

    private fun selectedFamily(): String =
        familyForSelection(chainSpinner.selectedItemPosition)

    private fun tokensForSelection(position: Int): List<WalletGate.Token> =
        when (familyForSelection(position)) {
            WalletGate.FAMILY_BITCOIN -> WalletGate.BITCOIN_TOKENS
            WalletGate.FAMILY_SOLANA -> WalletGate.SOLANA_TOKENS
            else -> WalletGate.CHAINS[
                position.coerceIn(0, WalletGate.CHAINS.size - 1)
            ].tokens
        }

    private fun setAssetAdapter(symbols: List<String>) {
        assetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            symbols
        )
    }

    private fun copyAddress(address: String) {
        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Lille's wallet address", address)
        )
        Toast.makeText(this, "Her address copied.", Toast.LENGTH_SHORT).show()
    }

    /** Her address on a family — the approval sheet's
     *  "from" line and the sweep's source. */
    private fun fromAddressFor(family: String): String = when (family) {
        WalletGate.FAMILY_BITCOIN -> wallet.btcAddress() ?: ""
        WalletGate.FAMILY_SOLANA -> wallet.solAddress() ?: ""
        else -> wallet.address() ?: ""
    }

    private fun render() {
        val has = wallet.hasWallet()
        createBox.visibility = if (has) View.GONE else View.VISIBLE
        mainBox.visibility = if (has) View.VISIBLE else View.GONE
        if (!has) return
        addressView.text = wallet.address()
        // Per-family rows: an existing keyset shows its
        // address; a missing one (a wallet created before
        // the family existed) shows its set-up button.
        btcAddressView.text =
            wallet.btcAddress() ?: getString(R.string.wallet_not_set_up)
        solAddressView.text =
            wallet.solAddress() ?: getString(R.string.wallet_not_set_up)
        createBtcButton.visibility =
            if (wallet.hasBtcWallet()) View.GONE else View.VISIBLE
        createSolButton.visibility =
            if (wallet.hasSolWallet()) View.GONE else View.VISIBLE
        val caps = store.capState()
        capPerTxOff.isChecked = WalletGate.isUnlimited(caps.perTxCapCents)
        capDailyOff.isChecked = WalletGate.isUnlimited(caps.dailyCapCents)
        capPerTxInput.setText(
            if (WalletGate.isUnlimited(caps.perTxCapCents)) ""
            else (BigDecimal(caps.perTxCapCents).movePointLeft(2)).toPlainString()
        )
        capDailyInput.setText(
            if (WalletGate.isUnlimited(caps.dailyCapCents)) ""
            else (BigDecimal(caps.dailyCapCents).movePointLeft(2)).toPlainString()
        )
        autonomousSwitch.isChecked = store.autonomousOn()
        renderActivity()
        refreshBalances()
    }

    private fun renderActivity() {
        val entries = store.activity()
        activityView.text = if (entries.isEmpty()) {
            "Nothing yet — her wallet's record will appear here."
        } else {
            val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.UK)
            entries.joinToString("\n\n") { e ->
                "${fmt.format(Date(e.atMs))} · ${e.kind}\n${e.text}" +
                    (e.txHash?.let { "\n$it" } ?: "")
            }
        }
    }

    private fun refreshBalances() {
        val renderBalances: (List<WalletEngine.AssetBalance>) -> Unit = { balances ->
            if (!isFinishing && !isDestroyed) {
                balancesView.text = balances.joinToString("\n") { b ->
                    if (b.raw == null) {
                        "${b.token.symbol}: couldn't load" +
                            " — that's a failed read, not a zero."
                    } else {
                        "${b.token.symbol}: " +
                            Evm.formatUnits(b.raw, b.token.decimals) +
                            (b.source?.let { " (via ${hostOf(it)})" } ?: "")
                    }
                }
                renderActivity()
            }
        }
        when (selectedFamily()) {
            WalletGate.FAMILY_BITCOIN -> {
                balancesView.text = "Loading balances on Bitcoin…"
                btcEngine.loadBalances(renderBalances)
            }
            WalletGate.FAMILY_SOLANA -> {
                balancesView.text = "Loading balances on Solana…"
                solEngine.loadBalances(renderBalances)
            }
            else -> {
                val chain = selectedChain()
                balancesView.text = "Loading balances on ${chain.label}…"
                engine.loadBalances(chain, renderBalances)
            }
        }
    }

    private fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore("/")

    // ---- send ----

    private fun prepareFromForm() {
        val amount = amountInput.text.toString().trim()
        val recipient = recipientInput.text.toString().trim()
        if (amount.isEmpty() || recipient.isEmpty()) {
            statusView.text = "Type an amount and who it's for first."
            return
        }
        val asset = assetSpinner.selectedItem.toString()
        val amountText = if (usdCheck.isChecked) null else amount
        val usdAmountText = if (usdCheck.isChecked) amount else null
        statusView.text = "Preparing…"
        val onOutcome: (WalletEngine.PrepareOutcome) -> Unit = { outcome ->
            if (!isFinishing && !isDestroyed) {
                when (outcome) {
                    is WalletEngine.PrepareOutcome.Failed ->
                        statusView.text = outcome.message
                    is WalletEngine.PrepareOutcome.Ready ->
                        offerApproval(outcome.prepared)
                }
                renderActivity()
            }
        }
        when (selectedFamily()) {
            WalletGate.FAMILY_BITCOIN ->
                btcEngine.prepareFromForm(asset, amountText, usdAmountText, recipient, onOutcome)
            WalletGate.FAMILY_SOLANA ->
                solEngine.prepareFromForm(asset, amountText, usdAmountText, recipient, onOutcome)
            else ->
                engine.prepareFromForm(
                    chainKey = selectedChain().key,
                    asset = asset,
                    amountText = amountText,
                    usdAmountText = usdAmountText,
                    targetRaw = recipient,
                    done = onOutcome
                )
        }
    }

    /** The gated signer of a prepared spend's own family. */
    private fun executeOn(
        prepared: WalletGate.PreparedTx,
        userApproved: Boolean,
        rawAmountConfirmed: Boolean,
        done: (WalletEngine.ExecOutcome) -> Unit
    ) {
        when (prepared.family) {
            WalletGate.FAMILY_BITCOIN ->
                btcEngine.execute(prepared, userApproved, rawAmountConfirmed, done)
            WalletGate.FAMILY_SOLANA ->
                solEngine.execute(prepared, userApproved, rawAmountConfirmed, done)
            else ->
                engine.execute(prepared, userApproved, rawAmountConfirmed, done)
        }
    }

    /** Prepare-and-ask: the gate first (autonomous mode may
     *  allow outright), the sheet whenever it asks. */
    private fun offerApproval(prepared: WalletGate.PreparedTx) {
        executeOn(prepared, userApproved = false, rawAmountConfirmed = false) { outcome ->
            if (isFinishing || isDestroyed) return@executeOn
            when (outcome) {
                WalletEngine.ExecOutcome.NeedsApproval -> {
                    WalletApprovalUi.show(
                        this, prepared, fromAddressFor(prepared.family),
                        onApprove = { rawConfirmed ->
                            runExecute(prepared, rawConfirmed)
                        },
                        onCancel = {
                            statusView.text = "Cancelled — nothing was sent."
                        }
                    )
                }
                else -> reportExec(outcome)
            }
            renderActivity()
        }
    }

    private fun runExecute(prepared: WalletGate.PreparedTx, rawConfirmed: Boolean) {
        statusView.text = "Signing and sending…"
        executeOn(
            prepared, userApproved = true, rawAmountConfirmed = rawConfirmed
        ) { outcome ->
            if (isFinishing || isDestroyed) return@executeOn
            reportExec(outcome)
            renderActivity()
            refreshBalances()
        }
    }

    private fun reportExec(outcome: WalletEngine.ExecOutcome) {
        statusView.text = when (outcome) {
            is WalletEngine.ExecOutcome.Sent -> outcome.message
            is WalletEngine.ExecOutcome.Refused -> outcome.reason
            is WalletEngine.ExecOutcome.Failed -> outcome.message
            WalletEngine.ExecOutcome.NeedsApproval ->
                "That still needs your approval on the sheet — nothing was sent."
        }
    }

    // ---- sweep ----

    private fun prepareSweep() {
        val identity = FluidIdentityStore(this).load()
        if (identity == null) {
            statusView.text =
                "No Fluid identity is stored, so Lille doesn't know your " +
                    "Fluid address to sweep back to."
            return
        }
        statusView.text = "Preparing the sweep…"
        engine.prepareSweep(selectedChain()) { outcome ->
            if (isFinishing || isDestroyed) return@prepareSweep
            when (outcome) {
                is WalletEngine.SweepOutcome.Failed ->
                    statusView.text = outcome.message
                is WalletEngine.SweepOutcome.Ready -> {
                    WalletApprovalUi.showSweep(
                        this, outcome.prepared, wallet.address() ?: "",
                        identity.username,
                        onApprove = { runSweep(outcome.prepared, 0) },
                        onCancel = {
                            statusView.text = "Cancelled — nothing was sent."
                        }
                    )
                }
            }
            renderActivity()
        }
    }

    private fun runSweep(prepared: List<WalletGate.PreparedTx>, index: Int) {
        if (index >= prepared.size) {
            statusView.text = "Sweep complete — everything is back with your Fluid wallet."
            renderActivity()
            refreshBalances()
            return
        }
        val p = prepared[index]
        statusView.text = "Sweeping ${p.amountDisplay} ${p.tokenSymbol} " +
            "(${index + 1} of ${prepared.size})…"
        engine.execute(p, userApproved = true, rawAmountConfirmed = true) { outcome ->
            if (isFinishing || isDestroyed) return@execute
            when (outcome) {
                is WalletEngine.ExecOutcome.Sent -> runSweep(prepared, index + 1)
                else -> {
                    reportExec(outcome)
                    renderActivity()
                }
            }
        }
    }

    // ---- caps ----

    private fun saveCaps() {
        val perTx = parseCap(capPerTxInput.text.toString(), capPerTxOff.isChecked)
        val daily = parseCap(capDailyInput.text.toString(), capDailyOff.isChecked)
        if (perTx == null || daily == null) {
            statusView.text =
                "Caps are dollar amounts like 5 or 12.50 — or tick \"no limit\"."
            return
        }
        store.saveCaps(perTx, daily)
        store.setAutonomous(autonomousSwitch.isChecked)
        statusView.text = "Saved. New caps: " +
            (if (WalletGate.isUnlimited(perTx)) "no per-transaction limit"
            else "${WalletGate.formatUsd(perTx)} per transaction") + ", " +
            (if (WalletGate.isUnlimited(daily)) "no daily limit"
            else "${WalletGate.formatUsd(daily)} per day") +
            ". Autonomous spending is " +
            (if (autonomousSwitch.isChecked) "ON" else "off") + "."
    }

    /** A cap field → cents. "No limit" wins over the field;
     *  an empty/invalid field without it is an error (null),
     *  never a silent default. */
    private fun parseCap(text: String, off: Boolean): Long? {
        if (off) return WalletGate.NO_LIMIT
        val value = text.trim().toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        return value.movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP).toLong()
    }
}
