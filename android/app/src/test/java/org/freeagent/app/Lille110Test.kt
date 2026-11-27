package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v1.0.10 pins — the two deterministic fixes, the
 * switching machinery, and continuous session
 * awareness:
 *
 * - Fix 1: "close the browser" is a command grammar
 *   (structure, not phrases) + a pure close plan
 *   (halt the lane's work to a pause, dismiss the
 *   panel, one line) + the panel's commanded-close
 *   state. Lookalikes — content ABOUT browsers —
 *   never parse.
 * - Fix 2: identity by navigation — the own-profile
 *   route's landed URL is the witness; login landing
 *   is signed out; neither is Unreadable with a
 *   reason, never a guess. The DOM read corroborates
 *   where navigation is forbidden (mayNavigate=false
 *   performs ZERO navigations).
 * - Fix 3: the scripted sign-out (menu → Log out →
 *   confirm → verify-by-state), the switch (sign-out
 *   then sign-in, each verified; the registry's
 *   active entry flips ONLY on verified reads), the
 *   precondition's execution shapes (hers autonomous,
 *   his parks — his sign-in is never attempted), the
 *   creation lane's sign-out-before-signup ordering,
 *   and the spoken session commands' grammar + plans.
 * - Awareness: the sense derives only from the
 *   session's own furniture (a viewed profile URL is
 *   NOT a session), conclusions change-detect,
 *   contradictions select the stale records, the
 *   sweep selects the stale stamps, notifications
 *   name the transitions, and the registry's session
 *   bookkeeping (live / expired / park-once) holds.
 */
class Lille110Test {

    // ---------- fixtures ----------

    private fun el(
        ref: Int, tag: String = "div", type: String = "",
        role: String = "", testid: String = "", text: String = "",
        name: String = "", placeholder: String = "",
        href: String = "", secret: Boolean = false
    ) = BrowserPerception.Element(
        ref, tag, type, role, testid, text, name, placeholder,
        href, secret
    )

    private fun page(
        url: String,
        elements: List<BrowserPerception.Element> = emptyList(),
        text: String = ""
    ) = BrowserPerception.PageState(
        url, "", text, elements, emptyList()
    )

    /** A signed-in page whose chrome names the handle
     *  (the profile tab's target). */
    private fun signedInPage(handle: String) = page(
        "https://x.com/home",
        listOf(
            el(1, tag = "a", testid = "AppTabBar_Profile_Link",
                href = "/$handle"),
            el(2, tag = "a", testid = "SideNav_NewTweet_Button",
                href = "/compose/post")
        )
    )

    private fun signedOutPage() = page(
        "https://x.com/",
        listOf(
            el(1, tag = "a", text = "Log in", href = "/login"),
            el(2, tag = "a", text = "Sign up", href = "/signup")
        )
    )

    private fun xRecord(
        id: String, state: String, chatId: String = "chat-1",
        domains: List<String> = listOf("x.com")
    ): JSONObject {
        val arr = JSONArray()
        domains.forEach { arr.put(it) }
        return JSONObject()
            .put("id", id)
            .put("state", state)
            .put("title", "Some X work")
            .put("browserDomains", arr)
            .put(ChatTaskLink.ORIGIN_FIELD, chatId)
            .put("createdAt", 100L)
            .put("updatedAt", 100L)
    }

    // ---------- Fix 1: the close grammar ----------

    @Test
    fun closeGrammarAcceptsTheCommandShapes() {
        for (s in listOf(
            "close the browser", "close browser",
            "Close the browser.", "dismiss the browser",
            "shut the panel", "hide the browser",
            "close the browser panel", "close this browser",
            "stop browsing", "close the browser now",
            "shut the browser"
        )) {
            assertTrue(s, BrowserControl.parseCloseBrowser(s))
        }
    }

    @Test
    fun closeGrammarRewordedVariantsHold() {
        // The grammar is structural: unseen wordings of
        // the same shape parse; the user's sentences are
        // fixtures, not the mechanism.
        assertTrue(BrowserControl.parseCloseBrowser("dismiss this panel"))
        assertTrue(BrowserControl.parseCloseBrowser("hide that webview"))
        assertTrue(BrowserControl.parseCloseBrowser("shut the browser panel now"))
    }

    @Test
    fun closeGrammarRejectsContentAboutBrowsers() {
        for (s in listOf(
            // A task request ABOUT a browser is not the
            // command — extra referent tokens.
            "close the browser tab in the article",
            "close the browser and tell me a joke",
            "the browser is slow, close it",
            // No leading verb / no referent — the kill
            // grammar's discipline. (v1.0.11, G4: edge
            // politeness is stripped, so "please close
            // the browser" and "close the browser please"
            // are commands now — pinned in Lille111Test.)
            "close it", "close", "close the tab",
            "stop the task", "stop it"
        )) {
            assertFalse(s, BrowserControl.parseCloseBrowser(s))
        }
    }

    @Test
    fun closeGrammarIsDisjointFromTheKillGrammar() {
        // "stop" belongs to both verb classes; the
        // referents keep the commands apart — a browser
        // command never resolves as a kill, and a kill
        // never resolves as a close.
        assertNull(TaskControl.parseKill("stop the browser"))
        assertNull(TaskControl.parseKill("stop browsing"))
        assertFalse(
            BrowserControl.parseCloseBrowser("stop the x task")
        )
        assertFalse(BrowserControl.parseCloseBrowser("kill the browser"))
    }

    // ---------- Fix 1: the close plan ----------

    @Test
    fun closePlanHaltsTheDrivingWorkAndDismisses() {
        val owner = xRecord("t1", TaskStates.RUNNING)
        val plan = BrowserControl.planClose(
            listOf(owner), panelTaskId = "t1",
            panelShowing = true, laneOwnerTaskId = "t1"
        )
        assertEquals("t1", plan.haltTaskId)
        assertEquals("t1", plan.dismissTaskId)
        assertEquals(BrowserControl.CLOSED_LINE, plan.reply)
    }

    @Test
    fun closePlanOnAParkDismissesWithoutHalting() {
        // A parked record is not driving — it waits on
        // the user already. The panel still closes on
        // his word (the commanded close outranks the
        // park's panel override).
        val parked = xRecord("t1", TaskStates.PARKED_AT_WALL)
        val plan = BrowserControl.planClose(
            listOf(parked), panelTaskId = "t1",
            panelShowing = true, laneOwnerTaskId = null
        )
        assertNull(plan.haltTaskId)
        assertEquals("t1", plan.dismissTaskId)
        assertEquals(BrowserControl.CLOSED_LINE, plan.reply)
    }

    @Test
    fun closePlanWithNothingOpenAnswersHonestly() {
        val plan = BrowserControl.planClose(
            emptyList(), panelTaskId = null,
            panelShowing = false, laneOwnerTaskId = null
        )
        assertNull(plan.haltTaskId)
        assertNull(plan.dismissTaskId)
        assertEquals(BrowserControl.NOT_OPEN_LINE, plan.reply)
    }

    @Test
    fun closePlanHaltsAnotherChatsDrivingWork() {
        // WAITING_OTHER: the panel shows this chat's
        // record while ANOTHER chat's work drives the
        // lane — the close halts the DRIVER (the work
        // actually on the browser) and dismisses here.
        val mine = xRecord("mine", TaskStates.QUEUED)
        val other = xRecord("other", TaskStates.RUNNING, chatId = "chat-2")
        val plan = BrowserControl.planClose(
            listOf(mine, other), panelTaskId = "mine",
            panelShowing = true, laneOwnerTaskId = "other"
        )
        assertEquals("other", plan.haltTaskId)
        assertEquals("mine", plan.dismissTaskId)
        assertEquals(BrowserControl.CLOSED_LINE, plan.reply)
    }

    @Test
    fun closePlanNeverHaltsTerminalOrKilledWork() {
        val done = xRecord("t1", TaskStates.COMPLETED)
        val plan = BrowserControl.planClose(
            listOf(done), panelTaskId = null,
            panelShowing = false, laneOwnerTaskId = "t1"
        )
        assertNull(plan.haltTaskId)
        assertEquals(BrowserControl.NOT_OPEN_LINE, plan.reply)
    }

    @Test
    fun stampBrowserClosedRecordsTheReason() {
        val task = xRecord("t1", TaskStates.RUNNING)
        BrowserControl.stampBrowserClosed(task, "close the browser", 500L)
        assertTrue(task.optBoolean(BrowserControl.CLOSED_FIELD))
        assertEquals(BrowserControl.CLOSED_NOTE, task.optString("note"))
        val events = task.optJSONArray("events")
        assertNotNull(events)
        assertEquals(
            "browser_closed",
            events!!.getJSONObject(events.length() - 1)
                .optString("kind")
        )
    }

    @Test
    fun panelCommandedCloseHidesEveryState() {
        // The park override (1.0.3) governs the button —
        // his spoken close hides even a parked panel.
        assertFalse(
            BrowserPanel.panelVisible(
                BrowserPanel.State.PARKED,
                dismissed = false, closedByCommand = true
            )
        )
        assertTrue(
            BrowserPanel.panelVisible(
                BrowserPanel.State.PARKED,
                dismissed = false, closedByCommand = false
            )
        )
        assertFalse(
            BrowserPanel.panelVisible(
                BrowserPanel.State.WATCH,
                dismissed = false, closedByCommand = true
            )
        )
        // The chip still offers the way back for live
        // work under a commanded close — never for a
        // park or for nothing.
        assertTrue(
            BrowserPanel.indicatorShown(
                BrowserPanel.State.WATCH,
                dismissed = false, closedByCommand = true
            )
        )
        assertFalse(
            BrowserPanel.indicatorShown(
                BrowserPanel.State.PARKED,
                dismissed = false, closedByCommand = true
            )
        )
        assertFalse(
            BrowserPanel.indicatorShown(
                BrowserPanel.State.HIDDEN,
                dismissed = false, closedByCommand = true
            )
        )
    }

    // ---------- Fix 2: identity by navigation ----------

    private class FakeIdentityDriver(
        var state: BrowserPerception.PageState,
        val onNavigate: (String) -> BrowserPerception.PageState =
            { state },
        val onReveal: () -> BrowserPerception.PageState =
            { state }
    ) : XIdentityRead.Driver {
        val navigations = mutableListOf<String>()
        override fun currentState() = state
        override fun navigate(
            url: String
        ): BrowserPerception.PageState {
            navigations.add(url)
            state = onNavigate(url)
            return state
        }

        override fun revealAccountMenu():
            BrowserPerception.PageState {
            state = onReveal()
            return state
        }
    }

    @Test
    fun navigationLandingOnAProfileNamesTheSession() {
        val driver = FakeIdentityDriver(
            state = page("https://x.com/home"),
            onNavigate = { page("https://x.com/jeetethuram") }
        )
        val outcome = XIdentityRead.conclude(driver, mayNavigate = true)
        assertEquals(
            XIdentityRead.Outcome.SignedIn("jeetethuram"), outcome
        )
        assertEquals(
            listOf(XIdentityRead.OWN_PROFILE_URL), driver.navigations
        )
    }

    @Test
    fun navigationLandingOnLoginIsSignedOut() {
        val driver = FakeIdentityDriver(
            state = page("https://x.com/home"),
            onNavigate = { page("https://x.com/i/flow/login") }
        )
        assertEquals(
            XIdentityRead.Outcome.SignedOut,
            XIdentityRead.conclude(driver, mayNavigate = true)
        )
    }

    @Test
    fun navigationYieldingNeitherIsUnreadableNeverAGuess() {
        val driver = FakeIdentityDriver(
            state = page("https://x.com/home"),
            onNavigate = { page("https://x.com/i/profile") }
        )
        val outcome = XIdentityRead.conclude(driver, mayNavigate = true)
        assertTrue(outcome is XIdentityRead.Outcome.Unreadable)
    }

    @Test
    fun inPlaceReadPerformsZeroNavigations() {
        // mayNavigate=false is the parked/flow-page
        // discipline: the read judges the resting page
        // and NEVER drives it anywhere.
        val driver = FakeIdentityDriver(
            state = signedInPage("jeetethuram"),
            onNavigate = {
                throw AssertionError(
                    "an in-place read must not navigate"
                )
            }
        )
        val outcome = XIdentityRead.conclude(driver, mayNavigate = false)
        assertEquals(
            XIdentityRead.Outcome.SignedIn("jeetethuram"), outcome
        )
        assertTrue(driver.navigations.isEmpty())
    }

    @Test
    fun navigationFallsBackToTheMenuReveal() {
        // The own-profile route resolved silently (no
        // handle in the URL) and the landed page hides
        // the handle behind the account menu: the DOM
        // read corroborates — reveal, then conclude.
        val behindMenu = page(
            "https://x.com/i/profile",
            listOf(
                el(1, tag = "a", testid = "SideNav_NewTweet_Button",
                    href = "/compose/post"),
                el(2, testid = "SideNav_AccountSwitcher_Button",
                    text = "jeetethuram")
            )
        )
        val revealed = page(
            "https://x.com/i/profile",
            listOf(
                el(2, testid = "SideNav_AccountSwitcher_Button",
                    text = "Jeet @jeetethuram")
            )
        )
        val driver = FakeIdentityDriver(
            state = page("https://x.com/home"),
            onNavigate = { behindMenu },
            onReveal = { revealed }
        )
        assertEquals(
            XIdentityRead.Outcome.SignedIn("jeetethuram"),
            XIdentityRead.conclude(driver, mayNavigate = true)
        )
    }

    @Test
    fun signedOutPageReadInPlaceConcludesSignedOut() {
        val driver = FakeIdentityDriver(state = signedOutPage())
        assertEquals(
            XIdentityRead.Outcome.SignedOut,
            XIdentityRead.conclude(driver, mayNavigate = false)
        )
    }

    // ---------- Fix 3: the scripted routines ----------

    /** A scripted fake X: a session variable, pages
     *  per state, and a step log. The read is the
     *  session variable itself, concluded. */
    private class FakeSessionDriver(
        var session: String?,
        var state: BrowserPerception.PageState,
        val pages: Map<String, BrowserPerception.PageState> =
            emptyMap(),
        val onClick: (FakeSessionDriver,
            BrowserPerception.Element) -> BrowserPerception.PageState =
            { d, _ -> d.state },
        val onReveal: (FakeSessionDriver) ->
            BrowserPerception.PageState = { d -> d.state }
    ) : AccountSession.Driver {
        val log = mutableListOf<String>()

        override fun currentState() = state

        override fun navigate(
            url: String
        ): BrowserPerception.PageState {
            log.add("navigate:$url")
            state = pages[url] ?: state
            return state
        }

        override fun revealAccountMenu():
            BrowserPerception.PageState {
            log.add("reveal")
            state = onReveal(this)
            return state
        }

        override fun clickRef(
            ref: Int
        ): BrowserPerception.PageState {
            val e = state.elements.first { it.ref == ref }
            log.add(
                "click:" + e.testid.ifEmpty {
                    e.text.ifEmpty { "ref$ref" }
                }
            )
            state = onClick(this, e)
            return state
        }

        override fun clickText(
            text: String
        ): BrowserPerception.PageState {
            log.add("clickText:$text")
            return state
        }

        override fun type(
            ref: Int, value: String, secret: Boolean
        ): BrowserPerception.PageState {
            log.add(if (secret) "type:secret" else "type:$value")
            return state
        }

        fun read(): IdentityRead = {
            val s = session
            if (s == null) {
                XIdentityRead.Outcome.SignedOut
            } else {
                XIdentityRead.Outcome.SignedIn(s)
            }
        }
    }

    private fun homeWithSwitcher(handle: String) = page(
        "https://x.com/home",
        listOf(
            el(1, testid = "SideNav_AccountSwitcher_Button",
                text = handle),
            el(2, tag = "a", testid = "AppTabBar_Profile_Link",
                href = "/$handle")
        )
    )

    /** The sign-out click chain: switcher → menu with
     *  "Log out @h" → confirm page → signed out. */
    private fun signOutClicks(
        d: FakeSessionDriver, e: BrowserPerception.Element
    ): BrowserPerception.PageState = when (e.ref) {
        1 -> page(
            "https://x.com/home",
            listOf(el(3, tag = "a", text = "Log out @x"))
        )
        3 -> page(
            "https://x.com/logout",
            listOf(el(4, tag = "button", text = "Log out"))
        )
        4 -> {
            d.session = null
            signedOutPage()
        }
        else -> d.state
    }

    @Test
    fun signOutRunsTheMenuFlowAndVerifiesByState() {
        val driver = FakeSessionDriver(
            session = "jeetethuram",
            state = homeWithSwitcher("jeetethuram"),
            onClick = { d, e -> signOutClicks(d, e) }
        )
        val result = AccountSession.signOut(driver, driver.read())
        assertEquals(AccountSession.SignOutResult.SignedOut, result)
        assertEquals(
            listOf(
                "click:SideNav_AccountSwitcher_Button",
                "click:Log out @x",
                "click:Log out"
            ),
            driver.log
        )
        assertNull(driver.session)
    }

    @Test
    fun signOutWhenAlreadyOutClicksNothing() {
        val driver = FakeSessionDriver(
            session = null, state = signedOutPage()
        )
        assertEquals(
            AccountSession.SignOutResult.AlreadySignedOut,
            AccountSession.signOut(driver, driver.read())
        )
        assertTrue(driver.log.isEmpty())
    }

    @Test
    fun signOutFailsNamingTheMenuStep() {
        // The menu opens without a Log out control —
        // the failure names the step, and nothing is
        // claimed over the page.
        val driver = FakeSessionDriver(
            session = "jeetethuram",
            state = homeWithSwitcher("jeetethuram"),
            onClick = { d, _ ->
                page(
                    "https://x.com/home",
                    listOf(el(9, text = "Settings and privacy"))
                )
            },
            onReveal = { d -> d.state }
        )
        val result = AccountSession.signOut(driver, driver.read())
        assertTrue(result is AccountSession.SignOutResult.Failed)
        assertEquals(
            AccountSession.STEP_MENU,
            (result as AccountSession.SignOutResult.Failed).step
        )
    }

    @Test
    fun signOutFailsWhenTheVerifyStillReadsSignedIn() {
        // Every tap lands, but the session survives:
        // verify-by-state refuses the success.
        val driver = FakeSessionDriver(
            session = "jeetethuram",
            state = homeWithSwitcher("jeetethuram"),
            onClick = { d, e ->
                if (e.ref == 4) d.state else signOutClicks(d, e)
            }
        )
        val result = AccountSession.signOut(driver, driver.read())
        assertTrue(result is AccountSession.SignOutResult.Failed)
        assertEquals(
            AccountSession.STEP_VERIFY_LOGOUT,
            (result as AccountSession.SignOutResult.Failed).step
        )
    }

    private fun loginPages(): Map<String, BrowserPerception.PageState> =
        mapOf(
            XIdentityRead.LOGIN_URL to page(
                "https://x.com/i/flow/login",
                listOf(
                    el(10, tag = "input", type = "text",
                        name = "text"),
                    el(11, tag = "button", text = "Next")
                )
            )
        )

    private fun signInClicks(
        d: FakeSessionDriver, e: BrowserPerception.Element
    ): BrowserPerception.PageState = when (e.ref) {
        11 -> page(
            "https://x.com/i/flow/login",
            listOf(
                el(12, tag = "input", type = "password",
                    secret = true),
                el(13, tag = "button", text = "Log in")
            )
        )
        13 -> {
            d.session = "lilleagent"
            signedInPage("lilleagent")
        }
        else -> d.state
    }

    @Test
    fun signInRunsTheLoginFlowAndVerifiesTheHandle() {
        val driver = FakeSessionDriver(
            session = null, state = signedOutPage(),
            pages = loginPages(),
            onClick = { d, e -> signInClicks(d, e) }
        )
        val result = AccountSession.signIn(
            driver, "lilleagent", "her-vault-password", driver.read()
        )
        assertEquals(
            AccountSession.SignInResult.SignedIn("lilleagent"), result
        )
        // Her password is typed as a SECRET and never
        // appears in the step log.
        assertTrue(driver.log.contains("type:lilleagent"))
        assertTrue(driver.log.contains("type:secret"))
        assertFalse(driver.log.any { it.contains("her-vault-password") })
    }

    @Test
    fun signInStopsAtAWall() {
        val driver = FakeSessionDriver(
            session = null, state = signedOutPage(),
            pages = loginPages(),
            onClick = { _, _ -> page("https://x.com/account/access") }
        )
        val result = AccountSession.signIn(
            driver, "lilleagent", "pw", driver.read()
        )
        assertTrue(result is AccountSession.SignInResult.Walled)
    }

    @Test
    fun signInFailsWhenTheVerifyNamesAnotherHandle() {
        val driver = FakeSessionDriver(
            session = null, state = signedOutPage(),
            pages = loginPages(),
            onClick = { d, e ->
                if (e.ref == 13) {
                    d.session = "someoneelse"
                    signedInPage("someoneelse")
                } else {
                    signInClicks(d, e)
                }
            }
        )
        val result = AccountSession.signIn(
            driver, "lilleagent", "pw", driver.read()
        )
        assertTrue(result is AccountSession.SignInResult.Failed)
        assertEquals(
            AccountSession.STEP_VERIFY_LOGIN,
            (result as AccountSession.SignInResult.Failed).step
        )
    }

    // ---------- Fix 3: the switch + the registry ----------

    /** A read that applies its conclusions to the
     *  registry exactly as the runner's wrapper does —
     *  and records the registry's active handle at
     *  every conclusion into [flips], so the flip
     *  points are observable. */
    private fun registryRead(
        driver: FakeSessionDriver, root: JSONObject,
        flips: MutableList<String?>
    ): IdentityRead = {
        val s = driver.session
        val now = 1_000L + flips.size
        if (s == null) {
            AccountsRegistry.markSignedOut(
                root, AccountsRegistry.PLATFORM_X, now
            )
            flips.add(
                AccountsRegistry.activeEntry(
                    root, AccountsRegistry.PLATFORM_X
                )?.optString("handle")
            )
            XIdentityRead.Outcome.SignedOut
        } else {
            val owner = if (s == "lilleagent") {
                AccountsRegistry.OWNER_LILLE
            } else {
                AccountsRegistry.OWNER_USER
            }
            AccountsRegistry.register(
                root, AccountsRegistry.PLATFORM_X, s, owner, now
            )
            AccountsRegistry.markActive(
                root, AccountsRegistry.PLATFORM_X, s, now
            )
            flips.add(s)
            XIdentityRead.Outcome.SignedIn(s)
        }
    }

    private fun registryWithBoth(): JSONObject {
        val root = JSONObject()
        AccountsRegistry.register(
            root, AccountsRegistry.PLATFORM_X,
            "jeetethuram", AccountsRegistry.OWNER_USER, 100L
        )
        AccountsRegistry.register(
            root, AccountsRegistry.PLATFORM_X,
            "lilleagent", AccountsRegistry.OWNER_LILLE, 100L
        )
        AccountsRegistry.markActive(
            root, AccountsRegistry.PLATFORM_X, "jeetethuram", 100L
        )
        return root
    }

    @Test
    fun switchToHersIsAutonomousAndFlipsOnlyOnVerifiedReads() {
        val root = registryWithBoth()
        val driver = FakeSessionDriver(
            session = "jeetethuram",
            state = homeWithSwitcher("jeetethuram"),
            pages = loginPages(),
            // The click chain serves both halves: refs
            // 1/3/4 sign out; refs 11/13 sign in.
            onClick = { d, e ->
                when (e.ref) {
                    1, 3, 4 -> signOutClicks(d, e)
                    else -> signInClicks(d, e)
                }
            }
        )
        val flips = mutableListOf<String?>()
        val read = registryRead(driver, root, flips)
        val result = AccountSession.switchTo(
            driver, "lilleagent", targetIsHers = true,
            herPassword = "pw", read = read
        )
        assertEquals(
            AccountSession.SwitchResult.Switched("lilleagent"), result
        )
        // The active entry flipped to signed-OUT the
        // moment the sign-out verified — before any
        // sign-in — and to hers only when her sign-in
        // verified. It never pointed at an account the
        // browser did not hold.
        val firstOut = flips.indexOf(null)
        assertTrue("a signed-out conclusion was applied", firstOut >= 0)
        assertEquals("lilleagent", flips.last())
        assertEquals(
            "lilleagent",
            AccountsRegistry.activeEntry(
                root, AccountsRegistry.PLATFORM_X
            )?.optString("handle")
        )
    }

    @Test
    fun switchToHisSignsOutAndParksWithoutAttemptingSignIn() {
        val root = registryWithBoth()
        AccountsRegistry.markActive(
            root, AccountsRegistry.PLATFORM_X, "lilleagent", 200L
        )
        val driver = FakeSessionDriver(
            session = "lilleagent",
            state = homeWithSwitcher("lilleagent"),
            pages = loginPages(),
            onClick = { d, e -> signOutClicks(d, e) }
        )
        val result = AccountSession.switchTo(
            driver, "jeetethuram", targetIsHers = false,
            herPassword = null, read = registryRead(driver, root, mutableListOf())
        )
        assertEquals(
            AccountSession.SwitchResult.NeedsUserLogin("jeetethuram"),
            result
        )
        assertNull(driver.session)
        // His sign-in was NEVER attempted: no navigation
        // to the login flow, no typing at all.
        assertFalse(
            driver.log.any {
                it.startsWith("navigate:") || it.startsWith("type:")
            }
        )
        assertNull(
            AccountsRegistry.activeEntry(
                root, AccountsRegistry.PLATFORM_X
            )
        )
    }

    @Test
    fun failedSwitchLeavesTheRegistrySignedOut() {
        // Sign-out verifies; her sign-in then fails (no
        // password field appears). The registry must
        // read signed OUT — never still pointing at the
        // previous account.
        val root = registryWithBoth()
        val driver = FakeSessionDriver(
            session = "jeetethuram",
            state = homeWithSwitcher("jeetethuram"),
            pages = loginPages(),
            onClick = { d, e ->
                if (e.ref == 11) {
                    page(
                        "https://x.com/i/flow/login",
                        listOf(el(19, text = "Something went wrong"))
                    )
                } else {
                    signOutClicks(d, e)
                }
            }
        )
        val result = AccountSession.switchTo(
            driver, "lilleagent", targetIsHers = true,
            herPassword = "pw", read = registryRead(driver, root, mutableListOf())
        )
        assertTrue(result is AccountSession.SwitchResult.Failed)
        assertEquals(
            AccountSession.STEP_PASSWORD,
            (result as AccountSession.SwitchResult.Failed).step
        )
        assertNull(
            AccountsRegistry.activeEntry(
                root, AccountsRegistry.PLATFORM_X
            )
        )
    }

    @Test
    fun switchToTheLiveAccountIsAConclusionNotAJob() {
        val driver = FakeSessionDriver(
            session = "lilleagent",
            state = homeWithSwitcher("lilleagent")
        )
        val result = AccountSession.switchTo(
            driver, "lilleagent", targetIsHers = true,
            herPassword = "pw", read = driver.read()
        )
        assertEquals(
            AccountSession.SwitchResult.AlreadyActive("lilleagent"),
            result
        )
        assertTrue(driver.log.isEmpty())
    }

    @Test
    fun creationEntrySignsOutBeforeSignupBegins() {
        // The creation lane's entry sequence, at the
        // composition level: the concluding read finds
        // HIS session; the scripted sign-out runs; only
        // then would the signup script post. The step
        // order is the pin.
        val driver = FakeSessionDriver(
            session = "jeetethuram",
            state = homeWithSwitcher("jeetethuram"),
            onClick = { d, e -> signOutClicks(d, e) }
        )
        val steps = mutableListOf<String>()
        val read = driver.read()
        val outcome = read(true)
        assertEquals(
            XIdentityRead.Outcome.SignedIn("jeetethuram"), outcome
        )
        val result = AccountSession.signOut(driver, read)
        steps.addAll(driver.log)
        steps.add("signup begins")
        assertEquals(AccountSession.SignOutResult.SignedOut, result)
        val signupAt = steps.indexOf("signup begins")
        assertTrue(steps.subList(0, signupAt).isNotEmpty())
        assertTrue(
            steps.subList(0, signupAt).all {
                it.startsWith("click:")
            }
        )
    }

    // ---------- Fix 3.5: the spoken session commands ----------

    @Test
    fun logOutGrammarAcceptsAndRejects() {
        for (s in listOf(
            "log out", "log out of x", "log out of X",
            "sign out", "logout", "signout",
            "log out from twitter", "log out of the x",
            "log out now"
        )) {
            assertTrue(s, BrowserControl.parseLogOut(s))
        }
        for (s in listOf(
            "log out of the app", "log output settings",
            "logout of my email account",
            // (v1.0.11, G4: "please log out" is a command
            // now — edge politeness is stripped; pinned in
            // Lille111Test.)
            "log out of x and check mail",
            "sign out of everything"
        )) {
            assertFalse(s, BrowserControl.parseLogOut(s))
        }
    }

    @Test
    fun switchGrammarAndTheInterceptionRule() {
        assertEquals(
            BrowserControl.SwitchCommand("lilleagent", true),
            BrowserControl.parseSwitch("switch to @lilleagent")
        )
        assertEquals(
            BrowserControl.SwitchCommand("jeetethuram", true),
            BrowserControl.parseSwitch("switch account to jeetethuram")
        )
        // A bare "switch to <word>" parses but does NOT
        // intercept unless the word is a registered
        // account — "switch to dark" is conversation.
        val bare = BrowserControl.parseSwitch("switch to dark")
        assertEquals(
            BrowserControl.SwitchCommand("dark", false), bare
        )
        assertFalse(
            BrowserControl.shouldIntercept(
                bare!!, setOf("jeetethuram", "lilleagent")
            )
        )
        assertTrue(
            BrowserControl.shouldIntercept(bare, setOf("dark"))
        )
        assertNull(BrowserControl.parseSwitch("switch to dark mode"))
        assertNull(BrowserControl.parseSwitch("switch the account"))
        assertNull(BrowserControl.parseSwitch("switch to @waytoolonghandle1"))
    }

    @Test
    fun sessionPlansResolveRecordActionAndReply() {
        val root = registryWithBoth()
        val live = xRecord("t1", TaskStates.PAUSED)
        val logOut = BrowserControl.planLogOut(listOf(live), "chat-1")
        assertEquals("t1", logOut.recordId)
        assertFalse(logOut.createRecord)
        assertEquals(
            BrowserControl.SessionAction.SIGN_OUT, logOut.action
        )
        val noRecord = BrowserControl.planLogOut(emptyList(), "chat-1")
        assertTrue(noRecord.createRecord)
        assertNull(noRecord.recordId)

        val hers = BrowserControl.planSwitch(
            listOf(live), root, "chat-1",
            BrowserControl.SwitchCommand("lilleagent", true),
            herVerifiedHandle = "lilleagent"
        )
        assertEquals(
            BrowserControl.SessionAction.SWITCH_HER, hers.action
        )
        val his = BrowserControl.planSwitch(
            listOf(live), root, "chat-1",
            BrowserControl.SwitchCommand("jeetethuram", true),
            herVerifiedHandle = "lilleagent"
        )
        assertEquals(
            BrowserControl.SessionAction.SWITCH_HIS, his.action
        )
        assertTrue(his.reply.contains("sign-in"))
        // A terminal record is never borrowed — the
        // command creates its own.
        val done = xRecord("t9", TaskStates.COMPLETED)
        val fresh = BrowserControl.planLogOut(listOf(done), "chat-1")
        assertTrue(fresh.createRecord)
    }

    @Test
    fun sessionLinesAreComposedFromVerifiedOutcomes() {
        assertTrue(
            BrowserControl.signOutLine(
                AccountSession.SignOutResult.SignedOut
            ).startsWith("Logged out of X")
        )
        assertTrue(
            BrowserControl.signOutLine(
                AccountSession.SignOutResult.Failed(
                    AccountSession.STEP_MENU, "no Log out control"
                )
            ).contains(AccountSession.STEP_MENU)
        )
        assertTrue(
            BrowserControl.switchLine(
                AccountSession.SwitchResult.Switched("lilleagent"),
                "lilleagent"
            ).contains("@lilleagent")
        )
        assertTrue(
            BrowserControl.switchParkStep("jeetethuram")
                .contains("@jeetethuram")
        )
    }

    // ---------- continuous session awareness ----------

    @Test
    fun senseDerivesOnlyFromTheSessionsOwnFurniture() {
        assertEquals(
            XIdentityRead.Outcome.SignedIn("jeetethuram"),
            SessionSense.deriveFromPage(signedInPage("jeetethuram"))
        )
        assertEquals(
            XIdentityRead.Outcome.SignedOut,
            SessionSense.deriveFromPage(signedOutPage())
        )
        // SOUNDNESS: resting on someone else's profile
        // URL is VIEWING, not a session — no conclusion.
        val viewing = page(
            "https://x.com/someoneelse",
            listOf(el(1, tag = "button", text = "Follow"))
        )
        assertNull(SessionSense.deriveFromPage(viewing))
        // A page that says nothing derives nothing.
        assertNull(
            SessionSense.deriveFromPage(
                page("https://x.com/home", text = "Welcome")
            )
        )
    }

    @Test
    fun senseConclusionsChangeDetect() {
        val root = JSONObject()
        assertTrue(
            SessionSense.applyConclusion(
                root, "x", "jeetethuram",
                "https://x.com/home", "page", 100L
            )
        )
        assertFalse(
            SessionSense.applyConclusion(
                root, "x", "jeetethuram",
                "https://x.com/home", "page", 200L
            )
        )
        assertTrue(
            SessionSense.applyConclusion(
                root, "x", null, "https://x.com/", "page", 300L
            )
        )
        assertFalse(
            SessionSense.applyConclusion(
                root, "x", null, "https://x.com/", "page", 400L
            )
        )
        assertTrue(
            SessionSense.applyConclusion(
                root, "x", "lilleagent",
                "https://x.com/home", "read", 500L
            )
        )
        assertEquals(
            "lilleagent", SessionSense.handle(root, "x")
        )
        assertEquals(500L, SessionSense.sensedAt(root, "x"))
    }

    @Test
    fun senseNotificationsNameTheTransitions() {
        assertEquals(
            "Signed in as @jeetethuram.",
            SessionSense.notificationFor("x", false, null, "jeetethuram")
        )
        assertEquals(
            "Signed in as @jeetethuram.",
            SessionSense.notificationFor("x", true, null, "jeetethuram")
        )
        assertEquals(
            "Signed out of X.",
            SessionSense.notificationFor("x", true, "jeetethuram", null)
        )
        assertEquals(
            "Switched to @lilleagent.",
            SessionSense.notificationFor(
                "x", true, "jeetethuram", "lilleagent"
            )
        )
        assertNull(
            SessionSense.notificationFor(
                "x", true, "jeetethuram", "jeetethuram"
            )
        )
        // A first sense of signed-out is standing
        // state, not news.
        assertNull(SessionSense.notificationFor("x", false, null, null))
        assertNull(SessionSense.notificationFor("x", true, null, null))
    }

    @Test
    fun senseContradictionsSelectTheStaleRecords() {
        val stale = xRecord("a", TaskStates.PAUSED)
        SessionIdentity.stamp(stale, "oldhandle", 100L)
        val agreeing = xRecord("b", TaskStates.PAUSED)
        SessionIdentity.stamp(agreeing, "jeetethuram", 100L)
        val terminal = xRecord("c", TaskStates.COMPLETED)
        SessionIdentity.stamp(terminal, "oldhandle", 100L)
        val fresher = xRecord("d", TaskStates.PAUSED)
        SessionIdentity.stamp(fresher, "oldhandle", 300L)
        val otherDomain = xRecord(
            "e", TaskStates.PAUSED, domains = listOf("example.com")
        )
        SessionIdentity.stamp(otherDomain, "oldhandle", 100L)
        val hits = SessionSense.contradictions(
            listOf(stale, agreeing, terminal, fresher, otherDomain),
            handle = "jeetethuram", senseAt = 200L
        ).map { it.optString("id") }
        assertEquals(listOf("a"), hits)
        // A sensed sign-out contradicts a signed-in stamp.
        val hitsOut = SessionSense.contradictions(
            listOf(agreeing), handle = null, senseAt = 200L
        ).map { it.optString("id") }
        assertEquals(listOf("b"), hitsOut)
    }

    @Test
    fun sweepSelectsStaleStampsOnly() {
        val stale = xRecord("a", TaskStates.PAUSED)
        SessionIdentity.stamp(stale, "jeetethuram", 100L)
        val fresh = xRecord("b", TaskStates.PAUSED)
        SessionIdentity.stamp(fresh, "jeetethuram", 9_000L)
        val terminal = xRecord("c", TaskStates.COMPLETED)
        SessionIdentity.stamp(terminal, "jeetethuram", 100L)
        val ids = SessionSense.recheckTaskIds(
            listOf(stale, fresh, terminal),
            sessionActivityAt = 5_000L
        )
        assertEquals(listOf("a"), ids)
    }

    @Test
    fun freshSenseAnswersOnlyWhileFresh() {
        val root = JSONObject()
        SessionSense.applyConclusion(
            root, "x", "jeetethuram",
            "https://x.com/home", "page", 1_000L
        )
        val fresh = SessionSense.freshConclusion(
            root, "x", emptyList(), sessionActivityAt = 500L
        )
        assertEquals("jeetethuram", fresh?.handle)
        // Browser activity AFTER the sense makes it
        // stale — it may no longer be quoted as the
        // current sign-in.
        assertNull(
            SessionSense.freshConclusion(
                root, "x", emptyList(), sessionActivityAt = 2_000L
            )
        )
        val line = SessionSense.senseLine(root, "x")
        assertNotNull(line)
        assertTrue(line!!.contains("@jeetethuram"))
        assertTrue(line.contains("sensed"))
    }

    @Test
    fun registrySessionBookkeepingHolds() {
        val root = JSONObject()
        AccountsRegistry.register(
            root, "x", "jeetethuram",
            AccountsRegistry.OWNER_USER, 100L
        )
        AccountsRegistry.markActive(root, "x", "jeetethuram", 100L)
        assertTrue(AccountsRegistry.sessionLive(root, "x", "jeetethuram"))
        // Sign-out expires the session.
        AccountsRegistry.markSignedOut(root, "x", 200L)
        assertFalse(AccountsRegistry.sessionLive(root, "x", "jeetethuram"))
        // A park stamped BEFORE the expiry does not
        // count for it; one stamped after does (park
        // ONCE per expiry).
        AccountsRegistry.noteSessionLoginPark(root, "x", "jeetethuram", 150L)
        assertFalse(AccountsRegistry.sessionLoginParked(root, "x", "jeetethuram"))
        AccountsRegistry.noteSessionLoginPark(root, "x", "jeetethuram", 250L)
        assertTrue(AccountsRegistry.sessionLoginParked(root, "x", "jeetethuram"))
        // Losing the active mark to another account
        // expires the loser's session too.
        AccountsRegistry.register(
            root, "x", "lilleagent",
            AccountsRegistry.OWNER_LILLE, 300L
        )
        AccountsRegistry.markActive(root, "x", "jeetethuram", 300L)
        AccountsRegistry.markActive(root, "x", "lilleagent", 400L)
        assertFalse(AccountsRegistry.sessionLive(root, "x", "jeetethuram"))
        assertTrue(AccountsRegistry.sessionLive(root, "x", "lilleagent"))
        // Owner resolution for a sensed handle.
        assertEquals(
            AccountsRegistry.OWNER_LILLE,
            SessionSense.ownerForHandle("lilleagent", setOf("lilleagent"))
        )
        assertEquals(
            AccountsRegistry.OWNER_USER,
            SessionSense.ownerForHandle("jeetethuram", setOf("lilleagent"))
        )
    }
}
