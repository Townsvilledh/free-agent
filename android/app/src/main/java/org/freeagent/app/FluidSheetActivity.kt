package org.freeagent.app

import android.annotation.SuppressLint
import android.os.Bundle
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
 * Fluid has no wc: deep link today (checked against its
 * source 2026-11-26: the WalletConnect panel is paste-only),
 * so the sheet carries the clipboard flow, not a deep link —
 * PARITY.md records the Fluid-side follow-up.
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
        webView.webViewClient = WebViewClient()
        webView.loadUrl(FLUID_URL)

        findViewById<Button>(R.id.fluidSheetClose).setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val FLUID_URL = "https://fluidwallet.dpdns.org"
    }
}
