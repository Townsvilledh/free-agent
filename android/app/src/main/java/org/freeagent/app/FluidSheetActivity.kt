package org.freeagent.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * The embedded Fluid sheet (v0.12 — "sign where you stand",
 * Ethuram 2026-11-26): Fluid is a web wallet with no native
 * app; until now, approving Lille's login pairing meant
 * leaving Lille, opening Fluid elsewhere, pasting the code,
 * and switching back. This sheet loads Fluid's own page
 * INSIDE Lille instead: the user unlocks Fluid and approves
 * the pairing (the code is already on the clipboard) without
 * leaving the app, and the WalletConnect approval reaches
 * Lille's session listener exactly as before.
 *
 * TRUST BOUNDARY, stated plainly (WALLET.md): this WebView
 * is a plain container. Lille installs NO JavaScript bridge,
 * injects NO script, and never reads keystrokes, form
 * values, or page content inside Fluid. Fluid's keys stay
 * Fluid's, in Fluid's own storage on this embedded browser.
 *
 * DEEP LINK (v0.13.1): Fluid gained a #wc= deep link (Fluid
 * build stamp 4d7f509193, 2026-11-26) —
 * https://fluidwallet.dpdns.org/#wc=<encodeURIComponent(uri)>
 * opens Fluid straight at its WalletConnect panel with the
 * pairing started. When the caller hands this sheet a
 * pairing URI (EXTRA_PAIRING_URI), the sheet loads that
 * deep link (FluidTx.deepLink builds it) and no paste step
 * exists. If the deep-link load itself fails, the sheet
 * falls back to Fluid's plain page and the clipboard flow
 * the login screen still prepares — paste remains the
 * fallback, never the plan. With no pairing URI (a session
 * request on an existing session, e.g. a main-wallet send)
 * the sheet simply loads Fluid, whose own approval UI
 * surfaces the request.
 */
class FluidSheetActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fluid_sheet)

        webView = findViewById(R.id.fluidSheetWeb)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        // No addJavascriptInterface, ever — see the trust
        // boundary in this file's header.
        val pairingUri = intent.getStringExtra(EXTRA_PAIRING_URI)
            ?.takeIf { FluidGate.isPairingUri(it) }
        var fellBack = false
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // The deep link (or Fluid itself) failed to
                // load: fall back ONCE to Fluid's plain page,
                // where the clipboard paste flow still works.
                if (request.isForMainFrame && !fellBack) {
                    fellBack = true
                    view.loadUrl(FLUID_URL)
                }
            }
        }
        webView.loadUrl(
            if (pairingUri != null) FluidTx.deepLink(pairingUri) else FLUID_URL
        )

        findViewById<Button>(R.id.fluidSheetClose).setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val FLUID_URL = FluidTx.FLUID_BASE_URL

        /** A wc: pairing URI to carry into Fluid via its
         *  #wc= deep link. Absent = plain Fluid load. */
        const val EXTRA_PAIRING_URI = "org.freeagent.app.extra.PAIRING_URI"
    }
}
