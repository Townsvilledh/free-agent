package org.freeagent.app

/**
 * IDENTITY BY NAVIGATION (v1.0.10) — the X sign-in read,
 * rebuilt as a navigation proof.
 *
 * Why (2026-11-27, Ethuram, after 1.0.8 AND 1.0.9 both
 * failed him on-device): "she still can't identify
 * which ex profile is logged in her browser". The
 * 1.0.9 read judged the PAGE — handle in the tree, or
 * behind the account menu, or nowhere — and on his
 * phone the tree never testified: mobile X's settled
 * pages did not expose the assumed testids, so reads
 * landed UNREADABLE or stale while the browser sat
 * signed in. Scraping harder was never going to fix a
 * witness that doesn't speak.
 *
 * So the primary read no longer asks the page who is
 * signed in. It NAVIGATES: the WebView is driven to
 * X's own-profile route ([OWN_PROFILE_URL]) and X
 * itself decides where that route lands — the
 * signed-in session's own profile (x.com/<handle>),
 * or the login flow when there is no session. The
 * handle is read from the RESULTING URL — a fact the
 * browser's own navigation settled, not text scraped
 * from a tree. The 1.0.9 DOM/menu read survives as
 * [concludeFromPage], the corroborating fallback for
 * the case where navigation yields no verdict (the
 * route loaded as a shell, the redirect didn't move
 * the URL) — and for the call sites that must not
 * navigate at all (a parked page is evidence; a
 * mid-signup flow page must survive a resume).
 *
 * Pure composition over a [Driver]; the runner's
 * driver performs the WebView work, the tests' fake
 * drivers perform scripted landings. Every conclusion
 * still lands through SessionIdentity's stamps and
 * the 1.0.9 freshness machinery — this object only
 * decides what the browser's state MEANS.
 *
 * Pinned by Lille110Test.
 */
object XIdentityRead {

    /** X's own-profile route: X resolves it against
     *  the live session — signed in, it lands on the
     *  session's own profile; signed out, on the login
     *  flow. The navigation IS the proof. */
    const val OWN_PROFILE_URL = "https://x.com/i/profile"

    const val HOME_URL = "https://x.com/home"
    const val LOGIN_URL = "https://x.com/i/flow/login"

    /** The three ways a read can land: signed in as a
     *  named handle, signed out, or UNREADABLE — the
     *  browser said neither, and the read says so
     *  instead of guessing. */
    sealed class Outcome {
        data class SignedIn(val handle: String) : Outcome()
        object SignedOut : Outcome()
        data class Unreadable(val reason: String) : Outcome()
    }

    /** The driver's surface the read composes over:
     *  read the resting page, navigate (redirects
     *  settle before the returned state's URL is
     *  judged), and the 1.0.9 scripted menu reveal. */
    interface Driver {
        fun currentState(): BrowserPerception.PageState
        fun navigate(url: String): BrowserPerception.PageState
        fun revealAccountMenu(): BrowserPerception.PageState
    }

    /** True when [url] is X's login flow — where the
     *  own-profile route lands a sessionless browser,
     *  and where a signed-in browser never rests. */
    fun isLoginUrl(url: String): Boolean {
        val path = url.substringAfter("x.com", "")
            .substringBefore("?").substringBefore("#")
        return path.contains("/i/flow/login") ||
            path == "/login" || path.startsWith("/login/")
    }

    /** What a landed URL proves on its own, after a
     *  navigation the read commanded: a bare profile
     *  path is the session's own profile (the route
     *  chose it), the login flow is a signed-out
     *  browser. Anything else proves nothing — the
     *  caller falls back to reading the page. */
    fun verdictForLandedUrl(url: String): Outcome? {
        BrowserPerception.xHandleFromProfileUrl(url)?.let {
            return Outcome.SignedIn(it)
        }
        if (isLoginUrl(url)) return Outcome.SignedOut
        return null
    }

    /**
     * THE CONCLUDING READ (v1.0.10). With
     * [mayNavigate] the read is a navigation proof:
     * drive to the own-profile route, judge the landed
     * URL, and only when the URL is silent read the
     * landed page ([concludeFromPage]). Without it —
     * a parked page, a flow page that must survive —
     * the resting page is read in place.
     */
    fun conclude(driver: Driver, mayNavigate: Boolean): Outcome {
        if (!mayNavigate) {
            return concludeFromPage(driver, driver.currentState())
        }
        val landed = driver.navigate(OWN_PROFILE_URL)
        verdictForLandedUrl(landed.url)?.let { return it }
        return concludeFromPage(driver, landed)
    }

    /**
     * The page read (the 1.0.9 concluding read, kept
     * as the corroborating fallback): the handle from
     * the page's own session furniture when it is
     * there; the shape judged otherwise — sign-in
     * chrome alone is signed OUT, session furniture
     * without a handle earns the scripted reveal, and
     * a page that says neither is UNREADABLE with the
     * reason. A signed-in page is never concluded
     * signed out from a missing handle.
     */
    fun concludeFromPage(
        driver: Driver, state: BrowserPerception.PageState
    ): Outcome {
        BrowserPerception.xHandleFromState(state)?.let {
            return Outcome.SignedIn(it)
        }
        return when (BrowserPerception.xIdentityShape(state, null)) {
            BrowserPerception.XIdentityShape.SIGNED_OUT ->
                Outcome.SignedOut
            BrowserPerception.XIdentityShape.HANDLE_BEHIND_MENU -> {
                val revealed = driver.revealAccountMenu()
                val handle =
                    BrowserPerception.xHandleFromState(revealed)
                        ?: BrowserPerception
                            .xHandleFromProfileUrl(revealed.url)
                if (handle != null) {
                    Outcome.SignedIn(handle)
                } else {
                    Outcome.Unreadable(
                        "the account menu opened but showed no handle"
                    )
                }
            }
            BrowserPerception.XIdentityShape.HANDLE_IN_TREE ->
                Outcome.Unreadable(
                    "the page showed the account area but no " +
                        "readable handle"
                )
            BrowserPerception.XIdentityShape.UNREADABLE ->
                Outcome.Unreadable(
                    "the page shows neither a signed-in account " +
                        "nor a sign-in form"
                )
        }
    }
}
