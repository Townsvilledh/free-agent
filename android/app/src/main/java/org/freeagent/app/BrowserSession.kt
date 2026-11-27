package org.freeagent.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class BrowserException(message: String) : Exception(message)

/**
 * The device-lane browser driver (v0.9): ONE WebView, owned by
 * this singleton, driven by deterministic ops that mirror the
 * host lane's browse.mjs actions (open/read/click/type/wait/
 * screenshot/eval) with the same semantics:
 *
 * - Structural perception via BrowserPerception.COLLECT_JS —
 *   visible text + ref-addressed elements; refs are reassigned
 *   on every collect, exactly like the host lane.
 * - The WebView's own storage (cookies, local storage, DOM
 *   storage) is the persistent on-device profile: sessions
 *   survive app restarts. It never leaves the app sandbox.
 * - Human pacing before clicks/typing; SPA shells are waited
 *   out on open (the host lane learned this in the v0.8 X proof:
 *   a blank shell is not a page).
 * - Challenges are detected by BrowserPerception on every
 *   collect and surface in the formatted observation as a
 *   CHALLENGE result. Nothing here solves or bypasses one.
 * - PARKED_AT_WALL handoff: the SAME WebView instance is
 *   re-parented into BrowserActivity so Ethuram passes a human
 *   check in the exact parked page state — no reload, no lost
 *   form (WALLS.md handoff protocol). attachTo/detach do that.
 *
 * Threading: all ops BLOCK and must be called from a background
 * thread (the task runner's worker). WebView calls hop to the
 * main thread; evaluateJavascript answers are awaited on a latch.
 */
object BrowserSession {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var pageLatch: CountDownLatch? = null

    @Volatile
    private var stagedUpload: File? = null

    @Volatile
    var lastState: BrowserPerception.PageState? = null
        private set

    /**
     * The browser lane's last-activity marker (v1.0.9):
     * stamped on every page finish and every collect —
     * page changes included. SessionIdentity's
     * freshness rule reads it beside the records'
     * takeover/handback stamps: an identity stamp
     * older than this marker may never be quoted as
     * the current sign-in.
     */
    @Volatile
    var lastActivityAt: Long = 0L
        private set

    /** Set by BrowserActivity while it is on screen: how a page's
     *  file chooser should be answered when no upload is staged
     *  (the real system picker). Unset (headless running): a
     *  chooser with nothing staged is cancelled honestly. */
    @Volatile
    var fileChooserHook: ((ValueCallback<Array<Uri>>) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun ensure(context: Context): WebView {
        webView?.let { return it }
        val created = onMain {
            val wv = WebView(context.applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.databaseEnabled = true
            wv.settings.setSupportMultipleWindows(false)
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(wv, true)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    lastActivityAt = System.currentTimeMillis()
                    pageLatch?.countDown()
                }
            }
            wv.webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: WebChromeClient.FileChooserParams?
                ): Boolean {
                    val staged = stagedUpload
                    if (staged != null && staged.isFile) {
                        stagedUpload = null
                        filePathCallback?.onReceiveValue(
                            arrayOf(Uri.fromFile(staged))
                        )
                        return true
                    }
                    val hook = fileChooserHook
                    if (hook != null && filePathCallback != null) {
                        hook(filePathCallback)
                        return true
                    }
                    filePathCallback?.onReceiveValue(null)
                    return true
                }
            }
            wv
        }
        webView = created
        return created
    }

    /** Run `block` on the main thread and return its result.
     *  Inline when already on main; posted + awaited otherwise. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Any?>(1)
        val error = arrayOfNulls<Throwable>(1)
        mainHandler.post {
            try {
                holder[0] = block()
            } catch (t: Throwable) {
                error[0] = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(60, TimeUnit.SECONDS)) {
            throw BrowserException("main thread did not answer in time")
        }
        error[0]?.let { throw it }
        return holder[0] as T
    }

    private fun evalJs(context: Context, js: String, timeoutMs: Long = 30_000): String? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "browser ops must run off the main thread"
        }
        val wv = ensure(context)
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        mainHandler.post {
            try {
                wv.evaluateJavascript(js) { value ->
                    holder[0] = value
                    latch.countDown()
                }
            } catch (t: Throwable) {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw BrowserException("page did not answer (script timeout)")
        }
        return BrowserPerception.unquoteJsResult(holder[0])
    }

    private fun pace() {
        Thread.sleep(700L + Random.nextLong(0, 600))
    }

    /** Collect the current page state (also refreshes lastState
     *  and flushes cookies to the persistent profile). */
    fun collect(context: Context): BrowserPerception.PageState {
        val json = evalJs(context, BrowserPerception.COLLECT_JS)
            ?: throw BrowserException("page collect returned nothing")
        val state = BrowserPerception.parseCollect(json)
        lastState = state
        lastActivityAt = System.currentTimeMillis()
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // flush is best-effort; storage persists regardless.
        }
        return state
    }

    fun open(context: Context, url: String, settleSeconds: Double = 4.0):
        BrowserPerception.PageState {
        val wv = ensure(context)
        val latch = CountDownLatch(1)
        pageLatch = latch
        onMain { wv.loadUrl(url) }
        latch.await(45, TimeUnit.SECONDS)
        Thread.sleep((settleSeconds * 1000).toLong())
        var state = collect(context)
        // SPA shells (X's onboarding among them) can sit empty
        // behind a slow network before the app bootstraps —
        // returning the blank shell makes the loop act on
        // nothing (v0.8 X proof). Wait the shell out.
        val deadline = System.currentTimeMillis() + 70_000
        while (System.currentTimeMillis() < deadline &&
            state.elements.isEmpty() &&
            state.text.trim().length < 100 &&
            state.challenge == null
        ) {
            Thread.sleep(5_000)
            state = collect(context)
        }
        return state
    }

    fun read(context: Context): BrowserPerception.PageState = collect(context)

    fun clickRef(context: Context, ref: Int): BrowserPerception.PageState {
        pace()
        val r = evalJs(context, BrowserPerception.clickRefJs(ref))
        if (r == "missing") {
            throw BrowserException(
                "click target missing: ref $ref (call browser_read first)"
            )
        }
        Thread.sleep(2_000)
        return collect(context)
    }

    fun clickText(context: Context, text: String): BrowserPerception.PageState {
        pace()
        val r = evalJs(context, BrowserPerception.clickTextJs(text))
        if (r == "NOT FOUND") {
            throw BrowserException("click text not found: $text")
        }
        Thread.sleep(2_000)
        return collect(context)
    }

    /** Tap a Set-of-Marks region (v1.0.4): the fallback for
     *  pages with no element tree. The mark's centre, as a
     *  fraction of the WebView, becomes a real touch pair
     *  (down+up) dispatched to the view on the main thread —
     *  the same gesture a finger makes, at the number the
     *  model picked. Marks come from the LAST collected
     *  state; a page that has since grown a tree answers
     *  with refs instead, and the caller re-reads. */
    fun clickMark(context: Context, n: Int): BrowserPerception.PageState {
        pace()
        val state = lastState
            ?: throw BrowserException("no page collected yet — browser_read first")
        val mark = BrowserPerception.mark(BrowserPerception.marksFor(state), n)
            ?: throw BrowserException(
                "no mark $n on this page (marks exist only when the " +
                    "page gives no element tree — browser_read again)"
            )
        val wv = ensure(context)
        onMain {
            val x = mark.cx * wv.width
            val y = mark.cy * wv.height
            val down = android.view.MotionEvent.obtain(
                android.os.SystemClock.uptimeMillis(),
                android.os.SystemClock.uptimeMillis(),
                android.view.MotionEvent.ACTION_DOWN, x, y, 0
            )
            val up = android.view.MotionEvent.obtain(
                down.downTime,
                android.os.SystemClock.uptimeMillis() + 60,
                android.view.MotionEvent.ACTION_UP, x, y, 0
            )
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
        Thread.sleep(2_000)
        return collect(context)
    }

    /** Type into a field by ref. The live probe is the driver-side
     *  half of the type guards: plain typing into a SECRET field
     *  is refused (SECRET_FIELD), and a vault value into a
     *  non-secret field is refused (NOT_A_SECRET_FIELD) — the
     *  runner's snapshot check (BrowserPerception) is the other
     *  half, mirroring the host lane's two-layer refusal. The
     *  value itself is never logged or returned. */
    fun type(
        context: Context, ref: Int, value: String, secret: Boolean
    ): BrowserPerception.PageState {
        pace()
        val probeJson = evalJs(context, BrowserPerception.typeProbeJs(ref))
            ?: throw BrowserException("type probe returned nothing: ref $ref")
        val probe = JSONObject(probeJson)
        if (probe.optBoolean("missing", false)) {
            throw BrowserException(
                "type target missing: ref $ref (call browser_read first — " +
                    "refs are assigned per read)"
            )
        }
        val probeSecret = probe.optBoolean("secret", false)
        if (probeSecret && !secret) {
            throw BrowserException(
                "SECRET_FIELD: ref $ref is a password/secret field — " +
                    "browser_type refuses it; use browser_type_secret " +
                    "with a vault key name"
            )
        }
        if (!probeSecret && secret) {
            throw BrowserException(
                "NOT_A_SECRET_FIELD: ref $ref is not a password/secret " +
                    "field — the vault value was NOT typed"
            )
        }
        val r = evalJs(context, BrowserPerception.typeSetJs(ref, value))
        if (r == "missing") {
            throw BrowserException("type target missing: ref $ref")
        }
        Thread.sleep(350)
        return collect(context)
    }

    fun waitText(
        context: Context, text: String, timeoutSeconds: Double = 30.0
    ): BrowserPerception.PageState {
        val deadline = System.currentTimeMillis() +
            (timeoutSeconds * 1000).toLong()
        var state = collect(context)
        while (System.currentTimeMillis() < deadline) {
            if (state.text.lowercase().contains(text.lowercase())) {
                return state
            }
            Thread.sleep(1_200)
            state = collect(context)
        }
        throw BrowserException("wait_text timed out: ${text.take(60)}")
    }

    fun waitSeconds(context: Context, seconds: Double):
        BrowserPerception.PageState {
        Thread.sleep((seconds * 1000).toLong())
        return collect(context)
    }

    /** Evaluate one of BrowserPerception's fixed X-lane scripts
     *  and return its (unquoted) string value. Internal machinery
     *  only — never a model-supplied expression. */
    fun evalValue(context: Context, js: String): String? = evalJs(context, js)

    /** Stage a file for the page's next file chooser (the
     *  browser_upload mechanism in a WebView: the chooser is the
     *  only door a page offers, so the staged file answers it). */
    fun stageUpload(file: File) {
        stagedUpload = file
    }

    /** True while a staged upload has not yet been consumed by a
     *  file chooser — how browser_upload reports STAGED vs OK. */
    fun stagedUploadPending(): Boolean = stagedUpload != null

    fun screenshot(context: Context, dir: File, name: String): File {
        val wv = ensure(context)
        val bmp = onMain {
            val w = if (wv.width > 0) wv.width else 1080
            val h = if (wv.height > 0) wv.height else 1920
            if (wv.width == 0) {
                wv.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        w, android.view.View.MeasureSpec.EXACTLY
                    ),
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        h, android.view.View.MeasureSpec.EXACTLY
                    )
                )
                wv.layout(0, 0, w, h)
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            wv.draw(canvas)
            bitmap
        }
        dir.mkdirs()
        val out = File(dir, name)
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }

    // ---------- PARKED_AT_WALL handoff (re-parenting) ----------

    /** Move the session WebView into `container` (BrowserActivity)
     *  so Ethuram sees — and can pass — the exact parked page.
     *  Main thread only (activity lifecycle). */
    fun attachTo(context: Context, container: ViewGroup) {
        val wv = ensure(context)
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(
            wv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    /** Take the WebView back from the handoff screen. The page
     *  state is untouched — the runner re-collects and continues. */
    fun detach() {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
    }

    fun currentUrl(): String = lastState?.url ?: ""
}
