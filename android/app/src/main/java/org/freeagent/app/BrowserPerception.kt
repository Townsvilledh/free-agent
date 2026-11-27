package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * On-device browser perception + policy (v0.9) — the device-lane
 * mirror of the host lane's deterministic layer
 * (free_agent/browsertools.py + tools/capture/browse.mjs).
 *
 * Perception is STRUCTURAL (DESIGN #2): the WebView driver runs
 * the fixed COLLECT script below via evaluateJavascript and gets
 * back the page's visible text + an element list with stable
 * refs (data-lille-ref). No vision, no raw JS from the model —
 * the model names a ref, a visible text, or an allowlisted URL,
 * exactly as on the host lane.
 *
 * Everything in this object is pure (org.json only, no Android
 * imports) so the policy — element-ref extraction, SECRET-field
 * classification, presented-only CHALLENGE detection, the domain
 * allowlist, the type guards — is unit-testable on the JVM
 * against fixture page states. BrowserSession does the WebView
 * work; this object decides what the page MEANS.
 */
object BrowserPerception {

    const val MAX_TEXT_CHARS = 1400

    class Element(
        val ref: Int, val tag: String, val type: String,
        val role: String, val testid: String, val text: String,
        val name: String, val placeholder: String, val href: String,
        val secret: Boolean
    ) {
        /** File inputs are how browser_upload targets a page. */
        val isFileInput: Boolean get() = type == "file"
    }

    /** hasGeometry=false mirrors the host driver's "missing
     *  geometry fails safe (counts as presented)" rule. */
    class Frame(val src: String, val w: Int, val h: Int,
                val visible: Boolean, val hasGeometry: Boolean)

    class Challenge(val kind: String, val detail: String)

    class PageState(
        val url: String, val title: String, val text: String,
        val elements: List<Element>, val frames: List<Frame>
    ) {
        val challenge: Challenge? by lazy { detectChallenge(this) }
        fun element(ref: Int): Element? = elements.firstOrNull { it.ref == ref }
    }

    class Article(val author: String, val text: String, val url: String)

    /** A Set-of-Marks region (v1.0.4, steal #4 — DroidRun /
     *  AppAgent): when a page yields NO element tree
     *  (canvas apps, custom-rendered surfaces), the
     *  screenshot is divided into a numbered grid and the
     *  model picks a NUMBER — never raw pixels. Fractions
     *  of the viewport, so any screen size resolves them. */
    class Mark(
        val n: Int, val fx: Float, val fy: Float,
        val fw: Float, val fh: Float
    ) {
        /** The tap point: the region's centre. */
        val cx: Float get() = fx + fw / 2f
        val cy: Float get() = fy + fh / 2f
    }

    const val MARK_COLS = 3
    const val MARK_ROWS = 4

    /** True when the page gave no usable element tree —
     *  the condition under which marks replace refs. */
    fun treeEmpty(state: PageState): Boolean = state.elements.isEmpty()

    /** The numbered grid for an empty-tree page: row-major,
     *  1-based, [MARK_COLS] × [MARK_ROWS]. A page WITH an
     *  element tree gets no marks — refs are strictly
     *  better and the two never mix in one observation. */
    fun marksFor(state: PageState): List<Mark> {
        if (!treeEmpty(state)) return emptyList()
        val out = mutableListOf<Mark>()
        var n = 0
        for (row in 0 until MARK_ROWS) {
            for (col in 0 until MARK_COLS) {
                n += 1
                out.add(
                    Mark(
                        n,
                        col.toFloat() / MARK_COLS,
                        row.toFloat() / MARK_ROWS,
                        1f / MARK_COLS,
                        1f / MARK_ROWS
                    )
                )
            }
        }
        return out
    }

    fun mark(marks: List<Mark>, n: Int): Mark? =
        marks.firstOrNull { it.n == n }

    // ---------- JS scripts (fixed; the model never writes JS) ----------

    /** The collect script: same extraction as the host driver's
     *  COLLECT (browse.mjs) — visible interactive elements get a
     *  data-lille-ref in document order; password-shaped fields
     *  are flagged SECRET; iframes report src + geometry +
     *  visibility for presented-only challenge detection. Wrapped
     *  in JSON.stringify so evaluateJavascript hands back one
     *  uniform string. */
    val COLLECT_JS: String = "JSON.stringify((() => {\n" +
        "  const vis = (e) => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);\n" +
        "  document.querySelectorAll('[data-lille-ref]').forEach(e => e.removeAttribute('data-lille-ref'));\n" +
        "  const sel = 'a[href], button, input, select, textarea, [role=\"button\"], [contenteditable=\"true\"], [data-testid]';\n" +
        "  const seen = new Set();\n" +
        "  const els = [];\n" +
        "  let n = 0;\n" +
        "  for (const e of document.querySelectorAll(sel)) {\n" +
        "    if (seen.has(e)) continue;\n" +
        "    if (!vis(e) && (e.getAttribute('type') || '').toLowerCase() !== 'file') continue;\n" +
        "    seen.add(e);\n" +
        "    if (e.tagName === 'A' && !e.innerText.trim() && !e.getAttribute('aria-label')) {\n" +
        "      if (!e.querySelector('img[alt]:not([alt=\"\"])')) continue;\n" +
        "    }\n" +
        "    n += 1;\n" +
        "    e.setAttribute('data-lille-ref', String(n));\n" +
        "    const type = (e.getAttribute('type') || '').toLowerCase();\n" +
        "    const secret = type === 'password' ||\n" +
        "      /pass(word)?|pwd|secret|one-time|otp/i.test(e.getAttribute('autocomplete') || '') ||\n" +
        "      /pass(word)?|pwd/i.test(e.getAttribute('name') || '');\n" +
        "    els.push({\n" +
        "      ref: n,\n" +
        "      tag: e.tagName.toLowerCase(),\n" +
        "      type: type || undefined,\n" +
        "      role: e.getAttribute('role') || undefined,\n" +
        "      testid: e.getAttribute('data-testid') || undefined,\n" +
        "      text: (e.innerText || e.getAttribute('aria-label') || '').replace(/\\s+/g, ' ').trim().slice(0, 90),\n" +
        "      name: e.getAttribute('name') || undefined,\n" +
        "      placeholder: e.getAttribute('placeholder') || undefined,\n" +
        "      href: e.tagName === 'A' ? (e.getAttribute('href') || undefined) : undefined,\n" +
        "      secret: secret || undefined,\n" +
        "    });\n" +
        "    if (els.length >= 40) break;\n" +
        "  }\n" +
        "  const text = document.body ? (document.body.innerText || '') : '';\n" +
        "  const frames = [...document.querySelectorAll('iframe')].map(f => {\n" +
        "    const r = f.getBoundingClientRect();\n" +
        "    const cs = getComputedStyle(f);\n" +
        "    return { src: f.getAttribute('src') || '', w: Math.round(r.width),\n" +
        "             h: Math.round(r.height),\n" +
        "             visible: cs.display !== 'none' && cs.visibility !== 'hidden' };\n" +
        "  });\n" +
        "  return { url: location.href, title: document.title || '', text, elements: els, frames };\n" +
        "})())"

    fun clickRefJs(ref: Int): String =
        "(() => { const el = document.querySelector('[data-lille-ref=\"$ref\"]'); " +
            "if (!el) return 'missing'; el.scrollIntoView({block:'center'}); " +
            "el.click(); return 'clicked'; })()"

    /** Same text-matching rule as the host driver's CLICK_TEXT:
     *  exact normalised match first, then a short partial match,
     *  preferring real buttons/links. */
    fun clickTextJs(text: String): String =
        "(() => {\n" +
            "  const norm = (t) => (t || '').replace(/\\s+/g, ' ').trim().toLowerCase();\n" +
            "  const want = norm(" + JSONObject.quote(text) + ");\n" +
            "  const vis = (e) => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);\n" +
            "  const els = [...document.querySelectorAll('button, a, [role=\"button\"], div, span, li')];\n" +
            "  const pick = (list) => list.filter(vis).find(e => e.tagName === 'BUTTON' || e.tagName === 'A' || e.getAttribute('role') === 'button') || list.filter(vis)[0] || list[0];\n" +
            "  const exact = els.filter(e => norm(e.innerText) === want);\n" +
            "  if (exact.length) { pick(exact).click(); return 'clicked'; }\n" +
            "  const part = els.filter(e => vis(e) && norm(e.innerText).includes(want) && norm(e.innerText).length < 200);\n" +
            "  if (part.length) { pick(part).click(); return 'clicked'; }\n" +
            "  return 'NOT FOUND';\n" +
            "})()"

    /** Live secret probe for a ref — the driver-side half of the
     *  type guards (the snapshot check is the other half). */
    fun typeProbeJs(ref: Int): String =
        "JSON.stringify((() => {\n" +
            "  const el = document.querySelector('[data-lille-ref=\"$ref\"]');\n" +
            "  if (!el) return { missing: true };\n" +
            "  const type = (el.getAttribute('type') || '').toLowerCase();\n" +
            "  const secret = type === 'password' ||\n" +
            "    /pass(word)?|pwd|secret|one-time|otp/i.test(el.getAttribute('autocomplete') || '') ||\n" +
            "    /pass(word)?|pwd/i.test(el.getAttribute('name') || '');\n" +
            "  return { secret, tag: el.tagName };\n" +
            "})())"

    /** Type into a field: for input/textarea use the native value
     *  setter + input/change events (React-shaped forms ignore a
     *  plain .value assignment); for contenteditable, focus +
     *  insertText. Existing content is replaced, mirroring the
     *  host driver's select-all + insertText. */
    fun typeSetJs(ref: Int, value: String): String =
        "(() => {\n" +
            "  const el = document.querySelector('[data-lille-ref=\"$ref\"]');\n" +
            "  if (!el) return 'missing';\n" +
            "  el.focus();\n" +
            "  const v = " + JSONObject.quote(value) + ";\n" +
            "  const tag = el.tagName;\n" +
            "  if (tag === 'INPUT' || tag === 'TEXTAREA') {\n" +
            "    const proto = tag === 'INPUT' ? window.HTMLInputElement.prototype : window.HTMLTextAreaElement.prototype;\n" +
            "    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;\n" +
            "    setter.call(el, v);\n" +
            "    el.dispatchEvent(new Event('input', { bubbles: true }));\n" +
            "    el.dispatchEvent(new Event('change', { bubbles: true }));\n" +
            "    return 'typed';\n" +
            "  }\n" +
            "  document.execCommand('selectAll', false, null);\n" +
            "  document.execCommand('insertText', false, v);\n" +
            "  return 'typed';\n" +
            "})()"

    /** X lane: the account handle, read from the PAGE (never from
     *  cookies) — same expression as the host lane's _HANDLE_EXPR,
     *  stringified for a uniform return. */
    val X_HANDLE_JS: String =
        "JSON.stringify((() => {\n" +
            "  const a = document.querySelector('a[data-testid=\"AppTabBar_Profile_Link\"]');\n" +
            "  if (a) return a.getAttribute('href');\n" +
            "  const nav = document.querySelector('[data-testid=\"SideNav_AccountSwitcher_Button\"]');\n" +
            "  return nav ? ('text:' + (nav.innerText || '').replace(/\\s+/g, ' ')) : null;\n" +
            "})())"

    /** X lane: open the account menu (v1.0.9) — the
     *  scripted reveal for a signed-in page whose handle
     *  is not in the tree: tap the account-switcher
     *  button, or the profile tab (which lands on the
     *  profile page, whose URL and switcher then name
     *  the handle). Fixed script; the model never
     *  writes JS. */
    val X_OPEN_ACCOUNT_MENU_JS: String =
        "(() => { const b = document.querySelector('[data-testid=\"SideNav_AccountSwitcher_Button\"]') " +
            "|| document.querySelector('a[data-testid=\"AppTabBar_Profile_Link\"]'); " +
            "if (!b) return 'missing'; b.click(); return 'clicked'; })()"

    /** What a page can tell the identity read (v1.0.9):
     *  the handle is right there, the session is real
     *  but the handle sits behind the account menu, the
     *  page is a signed-out page, or the page says
     *  nothing either way. The read must CONCLUDE —
     *  signed in as a handle, or signed out — and only
     *  the last shape may end a read unconcluded, as an
     *  explicit UNREADABLE with this shape as its
     *  reason's evidence. */
    enum class XIdentityShape {
        HANDLE_IN_TREE, HANDLE_BEHIND_MENU, SIGNED_OUT, UNREADABLE
    }

    private val HANDLE_RE = Regex("^[A-Za-z0-9_]{1,15}$")

    /** A bare profile path ("/LilleAgent") as a handle;
     *  null for anything longer or not handle-shaped. */
    private fun profilePathHandle(path: String): String? {
        val seg = path.trim().trimEnd('/').removePrefix("/")
        if (seg.isEmpty() || seg.contains("/")) return null
        return if (HANDLE_RE.matches(seg)) seg else null
    }

    /** The handle a bare profile URL names
     *  ("https://x.com/LilleAgent") — the reveal the
     *  profile-tab tap produces. Reserved first
     *  segments (home, settings…) are not handles. */
    fun xHandleFromProfileUrl(url: String): String? {
        val domain = domainOf(url)
        if (domain != "x.com" && !domain.endsWith(".x.com")) {
            return null
        }
        val path = url.substringAfter("x.com", "")
            .substringBefore("?").substringBefore("#")
        val seg = path.trim().trimEnd('/').removePrefix("/")
            .substringBefore("/")
        if (seg.isEmpty() || seg.lowercase() in
            setOf(
                "home", "explore", "notifications", "messages",
                "i", "settings", "compose", "search", "login",
                "signup", "bookmarks", "lists", "hashtag"
            )
        ) {
            return null
        }
        return if (HANDLE_RE.matches(seg)) seg else null
    }

    /** The signed-in handle from a collected page's own
     *  elements: the profile tab's href, or the account
     *  switcher's text. The same testimony X_HANDLE_JS
     *  reads live, judged from the snapshot — so the
     *  shape logic is pinnable against fixture states. */
    fun xHandleFromState(state: PageState): String? {
        for (el in state.elements) {
            when (el.testid) {
                "AppTabBar_Profile_Link" ->
                    profilePathHandle(el.href)?.let { return it }
                "SideNav_AccountSwitcher_Button" -> {
                    val m = Regex("@([A-Za-z0-9_]{1,15})")
                        .find(el.text)
                    if (m != null) return m.groupValues[1]
                }
            }
        }
        return null
    }

    /** Signed-in chrome: the furniture only a live
     *  session renders (compose, the profile tab, the
     *  account switcher). */
    fun xSignedInChrome(state: PageState): Boolean =
        state.elements.any { el ->
            el.testid == "SideNav_NewTweet_Button" ||
                el.testid == "AppTabBar_Profile_Link" ||
                el.testid == "SideNav_AccountSwitcher_Button" ||
                el.href == "/compose/post" ||
                el.href == "/compose/tweet"
        }

    /** Signed-out chrome: the login/signup doors a
     *  signed-out page renders. */
    fun xSignedOutChrome(state: PageState): Boolean =
        state.elements.any { el ->
            el.href.contains("/i/flow/login") ||
                el.href.contains("/i/flow/signup") ||
                el.href == "/login" || el.href == "/signup" ||
                ((el.tag == "a" || el.role == "button") &&
                    (el.text.equals("Log in", ignoreCase = true) ||
                        el.text.equals("Sign up", ignoreCase = true)))
        }

    /** Judge a collected page for the identity read:
     *  [handle] is the read's handle evidence (the live
     *  X_HANDLE_JS value, or [xHandleFromState]) when
     *  either found one. A signed-in page with no
     *  handle in the tree is HANDLE_BEHIND_MENU — the
     *  read's next step, never a signed-out conclusion:
     *  on 2026-11-27 exactly that page (a signed-in
     *  home feed whose handle sits behind the avatar)
     *  was stamped signed OUT, and the stale stamp was
     *  later quoted as fact. */
    fun xIdentityShape(
        state: PageState, handle: String?
    ): XIdentityShape = when {
        handle != null -> XIdentityShape.HANDLE_IN_TREE
        xSignedOutChrome(state) && !xSignedInChrome(state) ->
            XIdentityShape.SIGNED_OUT
        xSignedInChrome(state) -> XIdentityShape.HANDLE_BEHIND_MENU
        else -> XIdentityShape.UNREADABLE
    }

    /** X lane: timeline/search articles — same extraction as the
     *  host lane's _EXTRACT_ARTICLES. */
    fun xArticlesJs(count: Int): String =
        "JSON.stringify((() => [...document.querySelectorAll('article')]\n" +
            "  .slice(0, $count).map(a => {\n" +
            "    const t = a.querySelector('a[href*=\"/status/\"] time');\n" +
            "    const link = t ? t.closest('a') : null;\n" +
            "    const userBox = a.querySelector('[data-testid=\"User-Name\"]');\n" +
            "    return {\n" +
            "      author: userBox ? userBox.innerText.replace(/\\s+/g, ' ').slice(0, 90) : '',\n" +
            "      text: (a.innerText || '').replace(/\\s+/g, ' ').slice(0, 420),\n" +
            "      url: link ? link.href : ''};\n" +
            "  }))())"

    const val X_CLICK_COMPOSER_JS: String =
        "(() => { const box = document.querySelector('[data-testid=\"tweetTextarea_0\"]'); " +
            "if (!box) return 'missing'; box.focus(); box.click(); return 'clicked'; })()"

    const val X_CLICK_POST_BUTTON_JS: String =
        "(() => { const b = document.querySelector('[data-testid=\"tweetButton\"]') " +
            "|| document.querySelector('[data-testid=\"tweetButtonInline\"]'); " +
            "if (!b || b.getAttribute('aria-disabled') === 'true') return 'disabled'; " +
            "b.click(); return 'clicked'; })()"

    const val SCROLL_JS: String = "window.scrollBy(0, 1600); true"

    // ---------- evaluateJavascript plumbing ----------

    /** evaluateJavascript hands back the JS result JSON-encoded
     *  (a string result arrives quoted). Unwrap one level: returns
     *  the string value, or null for JS null/undefined. */
    fun unquoteJsResult(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try {
            when (val v = JSONTokener(raw).nextValue()) {
                is String -> v
                JSONObject.NULL -> null
                else -> v.toString()
            }
        } catch (e: Exception) {
            raw
        }
    }

    /** Parse a COLLECT_JS result into a PageState. Refs come from
     *  the script (document order, 1-based); a missing ref falls
     *  back to position so refs are always usable. */
    fun parseCollect(json: String): PageState {
        val o = JSONObject(json)
        val els = mutableListOf<Element>()
        val arr = o.optJSONArray("elements") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            els.add(
                Element(
                    ref = if (e.has("ref")) e.optInt("ref") else i + 1,
                    tag = e.optString("tag"),
                    type = e.optString("type"),
                    role = e.optString("role"),
                    testid = e.optString("testid"),
                    text = e.optString("text"),
                    name = e.optString("name"),
                    placeholder = e.optString("placeholder"),
                    href = e.optString("href"),
                    secret = e.optBoolean("secret", false)
                )
            )
        }
        val frames = mutableListOf<Frame>()
        val farr = o.optJSONArray("frames") ?: JSONArray()
        for (i in 0 until farr.length()) {
            val f = farr.getJSONObject(i)
            frames.add(
                Frame(
                    src = f.optString("src"),
                    w = f.optInt("w", -1),
                    h = f.optInt("h", -1),
                    visible = f.optBoolean("visible", true),
                    hasGeometry = f.has("w") && f.has("h")
                )
            )
        }
        return PageState(
            url = o.optString("url"),
            title = o.optString("title"),
            text = o.optString("text").take(20_000),
            elements = els,
            frames = frames
        )
    }

    fun parseArticles(json: String): List<Article> {
        val out = mutableListOf<Article>()
        val arr = try {
            JSONArray(json)
        } catch (e: Exception) {
            return out
        }
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            out.add(
                Article(
                    a.optString("author"),
                    a.optString("text"),
                    a.optString("url")
                )
            )
        }
        return out
    }

    /** Extract the handle from an X_HANDLE_JS value: "/LilleAgent"
     *  or "text:Display Name @handle …" — mirrors _x_handle(). */
    fun parseHandle(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        if (value.startsWith("/")) return value.trim('/').ifEmpty { null }
        if (value.startsWith("text:")) {
            val m = Regex("@([A-Za-z0-9_]{1,15})").find(value)
            return m?.groupValues?.get(1)
        }
        return null
    }

    // ---------- SECRET-field classification (mirrors browse.mjs) ----------

    private val SECRET_AUTOCOMPLETE =
        Regex("pass(word)?|pwd|secret|one-time|otp", RegexOption.IGNORE_CASE)
    private val SECRET_NAME =
        Regex("pass(word)?|pwd", RegexOption.IGNORE_CASE)

    fun isSecretField(type: String, autocomplete: String, name: String): Boolean =
        type.lowercase() == "password" ||
            SECRET_AUTOCOMPLETE.containsMatchIn(autocomplete) ||
            SECRET_NAME.containsMatchIn(name)

    /** browser_type's refusal: plain typing never enters a
     *  SECRET field. Null element = unknown (the live probe in
     *  the session is the second half of the guard). */
    fun plainTypeRefusal(el: Element?): String? =
        if (el != null && el.secret) {
            "error: SECRET_FIELD — element [${el.ref}] is a password/secret " +
                "field. browser_type refuses it. Use browser_type_secret " +
                "with the vault key name instead (the value never enters " +
                "your context)."
        } else null

    /** browser_type_secret's refusal (v0.8 X attempt 18): a vault
     *  value may ONLY land in a field the page flags SECRET —
     *  typing it into a plain field leaks it into a visible input
     *  and silently corrupts the form. */
    fun secretTypeRefusal(el: Element?): String? =
        if (el != null && !el.secret) {
            "error: NOT_A_SECRET_FIELD — element [${el.ref}] is not a " +
                "password field, so the vault value was NOT typed. " +
                "browser_read again and use the ref whose element is " +
                "marked SECRET."
        } else null

    // ---------- domain allowlist (mirrors domain_allowed) ----------

    fun domainOf(url: String): String {
        val m = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?#]+)").find(url.trim())
        return m?.groupValues?.get(1)?.lowercase() ?: ""
    }

    fun domainAllowed(url: String, domains: List<String>): Boolean {
        val host = domainOf(url)
        if (host.isEmpty()) return false
        return domains.any { d ->
            val dom = d.trim().lowercase()
            dom.isNotEmpty() && (host == dom || host.endsWith(".$dom"))
        }
    }

    // ---------- CHALLENGE detection (mirrors browse.mjs
    // detectChallenge: presented-only, evidence-only) ----------

    private val CHALLENGE_TEXT: List<Pair<String, String>> = listOf(
        "captcha" to "verify you are human",
        "captcha" to "are you a robot",
        "captcha" to "confirm you’re human",
        "captcha" to "confirm you're human",
        "captcha" to "i am human",
        "code" to "enter the code we sent",
        "code" to "we sent a code",
        "code" to "enter the verification code",
        "code" to "check your email for a code",
        "code" to "we emailed you a code",
        "phone" to "confirm your phone number",
        "phone" to "enter your phone number",
        "phone" to "add a phone number",
        "login-challenge" to "unusual login activity",
        "login-challenge" to "help us keep your account secure"
    )

    private val CHALLENGE_IFRAME =
        Regex("arkose|funcaptcha|hcaptcha|recaptcha")
    private val ACCOUNT_ACCESS =
        Regex("/account/access|/i/flow/login\\?.*challenge")

    fun detectChallenge(state: PageState): Challenge? {
        val url = state.url.lowercase()
        if (url.contains("arkose") || url.contains("funcaptcha")) {
            return Challenge("captcha", "challenge in URL: ${state.url}")
        }
        for (fr in state.frames) {
            val s = fr.src.lowercase()
            if (!CHALLENGE_IFRAME.containsMatchIn(s)) continue
            // Presented only: passive scoring/badge iframes
            // (reCAPTCHA v3, invisible anchors) sit at 0x0 or
            // hidden on countless ordinary pages. Missing
            // geometry fails safe (counts as presented).
            val presented = !fr.hasGeometry ||
                (fr.visible && fr.w >= 40 && fr.h >= 40)
            if (presented) {
                return Challenge(
                    "captcha",
                    "human-verification iframe: ${fr.src.take(120)}"
                )
            }
        }
        val text = state.text.lowercase()
        for ((kind, needle) in CHALLENGE_TEXT) {
            if (text.contains(needle)) {
                return Challenge(kind, "page text: \"$needle\"")
            }
        }
        if (ACCOUNT_ACCESS.containsMatchIn(url)) {
            return Challenge("login-challenge",
                "account access wall: ${state.url}")
        }
        return null
    }

    fun challengeObservation(ch: Challenge): String =
        "CHALLENGE kind=${ch.kind} detail=${ch.detail} " +
            "— STOP that route. This is a human-only wall: do NOT " +
            "attempt to solve, bypass, or work around it, and do " +
            "not retry the action. If the spec names another " +
            "legitimate route (another provider, another entry " +
            "point), take it now. Finish with a wall claim only " +
            "when every route in the spec has been worked and " +
            "walled — opening a homepage is not working a route."

    // ---------- observation formatting (mirrors _fmt_state /
    // _fmt_lite in browsertools.py) ----------

    private fun elementLine(e: Element, labelCap: Int): String {
        val bits = mutableListOf("[${e.ref}]", e.tag.ifEmpty { "?" })
        if (e.type.isNotEmpty()) bits.add("type=${e.type}")
        if (e.testid.isNotEmpty()) bits.add("testid=${e.testid}")
        if (e.secret) bits.add("SECRET")
        val label = e.text.ifEmpty { e.placeholder.ifEmpty { e.name } }
        if (label.isNotEmpty()) {
            bits.add("'" + label.take(labelCap) + "'")
        } else if (e.type == "submit") {
            bits.add("(submit button)")
        } else if (e.tag == "button") {
            bits.add("(button)")
        }
        if (e.href.isNotEmpty()) bits.add("href=${e.href.take(70)}")
        return bits.joinToString(" ")
    }

    fun formatState(state: PageState, header: String): String {
        state.challenge?.let {
            return challengeObservation(it) + "\n(page: ${state.url})"
        }
        val out = StringBuilder()
        out.append(header).append("\nURL: ").append(state.url)
        out.append("\nTitle: ").append(state.title)
        val full = state.text
        if (full.isNotEmpty()) {
            out.append("\nTEXT:\n").append(full.take(MAX_TEXT_CHARS))
        }
        if (full.length > MAX_TEXT_CHARS + 400) {
            // Form errors and footers live at the END of a page's
            // text (v0.8 X proof, attempt 11).
            out.append("\nTEXT TAIL:\n").append(full.takeLast(500))
        }
        if (state.elements.isNotEmpty()) {
            out.append("\nELEMENTS (${state.elements.size}):\n")
            out.append(state.elements.joinToString("\n") { elementLine(it, 70) })
        } else {
            appendMarks(out, state)
        }
        return out.toString()
    }

    /** The marks block for an empty-tree page: numbered
     *  regions the model can tap by number
     *  (browser_click_mark). Appended only when the tree
     *  is empty — with refs present, marks never appear. */
    private fun appendMarks(out: StringBuilder, state: PageState) {
        val marks = marksFor(state)
        if (marks.isEmpty()) return
        out.append(
            "\nMARKS (this page gave no element tree — the screen " +
                "is a numbered grid instead; browser_click_mark{n} " +
                "taps the centre of a region):\n"
        )
        out.append(
            marks.joinToString("  ") { "[${it.n}]" } +
                "\n(grid: $MARK_COLS across × $MARK_ROWS down, " +
                "numbered left-to-right, top-to-bottom)"
        )
    }

    fun formatLite(state: PageState, header: String): String {
        state.challenge?.let {
            return challengeObservation(it) + "\n(page: ${state.url})"
        }
        val out = StringBuilder()
        out.append(header).append("\nURL: ").append(state.url)
        out.append("\nTitle: ").append(state.title)
        out.append("\n").append(state.text.take(700))
        if (state.elements.isNotEmpty()) {
            out.append("\nELEMENTS (${state.elements.size}):\n")
            out.append(state.elements.take(28)
                .joinToString("\n") { elementLine(it, 40) })
        } else {
            appendMarks(out, state)
        }
        return out.toString()
    }

    fun formatPosts(header: String, posts: List<Article>): String {
        val out = StringBuilder(header)
        posts.forEachIndexed { i, p ->
            out.append("\n${i + 1}. ${p.author} — ${p.text.take(300)}")
            if (p.url.isNotEmpty()) out.append(" [${p.url}]")
        }
        return out.toString()
    }
}
