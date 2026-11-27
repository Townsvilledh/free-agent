package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.11 pins — the fix round from the independent
 * pre-delivery audit of 1.0.10 (AUDIT.md):
 *
 * - G3: "open the browser" is a command — the close
 *   command's mirror (grammar + pure plan). The view
 *   opens; work halted by his close stays halted
 *   until "resume", and the plan's line says so.
 * - G4: politeness tolerance, house-wide — a leading
 *   and/or trailing "please" is stripped before the
 *   command grammars match (kill, resume, close,
 *   open, log out, switch). This REVERSES the 1.0.5–
 *   1.0.10 pins that rejected "please kill all tasks
 *   now" and kin: he speaks that way, and the pin
 *   was the bug. The strip touches only the edges —
 *   a verb buried mid-sentence still leads nothing.
 * - G6: an identity re-check may not navigate the
 *   shared WebView away from another live task's
 *   resting page — the parked/flow in-place
 *   protection extends to resting pages (decision +
 *   the zero-navigation read over the resting page).
 * - G7: "log me out" / "sign me out" parse as the
 *   log-out command, under the same referent rules.
 */
class Lille111Test {

    // ---------- fixtures ----------

    private fun record(
        id: String, state: String,
        kind: String = "browser",
        domains: List<String> = listOf("x.com")
    ): JSONObject {
        val arr = JSONArray()
        domains.forEach { arr.put(it) }
        return JSONObject()
            .put("id", id)
            .put("state", state)
            .put("title", "Some work")
            .put("kind", kind)
            .put("browserDomains", arr)
            .put("createdAt", 100L)
            .put("updatedAt", 100L)
    }

    private fun el(
        ref: Int, tag: String = "div", testid: String = "",
        text: String = "", href: String = ""
    ) = BrowserPerception.Element(
        ref, tag, "", "", testid, text, "", "", href, false
    )

    private fun page(
        url: String,
        elements: List<BrowserPerception.Element> = emptyList(),
        text: String = ""
    ) = BrowserPerception.PageState(
        url, "", text, elements, emptyList()
    )

    private class FakeIdentityDriver(
        var state: BrowserPerception.PageState
    ) : XIdentityRead.Driver {
        val navigations = mutableListOf<String>()
        override fun currentState() = state
        override fun navigate(
            url: String
        ): BrowserPerception.PageState {
            navigations.add(url)
            return state
        }

        override fun revealAccountMenu():
            BrowserPerception.PageState = state
    }

    // ---------- G3: the open grammar ----------

    @Test
    fun openGrammarAcceptsTheCommandShapes() {
        for (s in listOf(
            "open the browser", "open browser",
            "Open the browser.", "show the browser",
            "reopen the panel", "open the browser panel",
            "open this browser", "show that webview",
            "open the browser now"
        )) {
            assertTrue(s, BrowserControl.parseOpenBrowser(s))
        }
    }

    @Test
    fun openGrammarRejectsContentAboutBrowsers() {
        for (s in listOf(
            // A job that OPENS with the word is not the
            // view command — extra tokens after the
            // referent.
            "open the browser and read my feed",
            "open the browser history",
            // No browser referent at all.
            "open x.com", "open the tab", "open",
            "show me the browser", "show the browser tab"
        )) {
            assertFalse(s, BrowserControl.parseOpenBrowser(s))
        }
    }

    @Test
    fun openAndCloseGrammarsAreDisjoint() {
        assertFalse(BrowserControl.parseOpenBrowser("close the browser"))
        assertFalse(BrowserControl.parseCloseBrowser("open the browser"))
        assertFalse(BrowserControl.parseOpenBrowser("stop browsing"))
    }

    // ---------- G3: the open plan ----------

    @Test
    fun openPlanReopensADismissedPanel() {
        val plan = BrowserControl.planOpen(
            BrowserPanel.State.WATCH,
            dismissed = true, closedByCommand = false,
            haltedByClose = false
        )
        assertTrue(plan.clearViewStamps)
        assertEquals(BrowserControl.OPEN_LINE, plan.reply)
    }

    @Test
    fun openPlanReopensACommandedCloseOverAPark() {
        val plan = BrowserControl.planOpen(
            BrowserPanel.State.PARKED,
            dismissed = false, closedByCommand = true,
            haltedByClose = false
        )
        assertTrue(plan.clearViewStamps)
        assertEquals(BrowserControl.OPEN_LINE, plan.reply)
    }

    @Test
    fun openPlanReopensTheWaitingOtherCard() {
        val plan = BrowserControl.planOpen(
            BrowserPanel.State.WAITING_OTHER,
            dismissed = false, closedByCommand = true,
            haltedByClose = false
        )
        assertTrue(plan.clearViewStamps)
        assertEquals(BrowserControl.OPEN_LINE, plan.reply)
    }

    @Test
    fun openPlanOnAVisiblePanelSaysSo() {
        val plan = BrowserControl.planOpen(
            BrowserPanel.State.WATCH,
            dismissed = false, closedByCommand = false,
            haltedByClose = false
        )
        assertFalse(plan.clearViewStamps)
        assertEquals(BrowserControl.ALREADY_OPEN_LINE, plan.reply)
    }

    @Test
    fun openPlanWithNoBrowserWorkAnswersHonestly() {
        val plan = BrowserControl.planOpen(
            BrowserPanel.State.HIDDEN,
            dismissed = false, closedByCommand = false,
            haltedByClose = false
        )
        assertFalse(plan.clearViewStamps)
        assertEquals(BrowserControl.NOT_IN_USE_LINE, plan.reply)
    }

    @Test
    fun openPlanOverCloseHaltedWorkNamesThePause() {
        // The view cannot open over a task his close
        // halted (a paused task has no live panel) —
        // the plan clears the stamps and names the
        // pause + the word that lifts it. The work is
        // NOT resumed by an open.
        val plan = BrowserControl.planOpen(
            BrowserPanel.State.HIDDEN,
            dismissed = true, closedByCommand = true,
            haltedByClose = true
        )
        assertTrue(plan.clearViewStamps)
        assertEquals(BrowserControl.OPEN_PAUSED_LINE, plan.reply)
    }

    // ---------- G4: politeness tolerance ----------

    @Test
    fun politenessStripTouchesOnlyTheEdges() {
        assertEquals(
            listOf("kill", "all"),
            StatusProbe.stripPoliteness(
                listOf("please", "kill", "all", "please")
            )
        )
        assertEquals(
            emptyList<String>(),
            StatusProbe.stripPoliteness(listOf("please"))
        )
        // A mid-sentence "please" is content and stays.
        assertEquals(
            listOf("kill", "please", "all"),
            StatusProbe.stripPoliteness(
                listOf("kill", "please", "all")
            )
        )
    }

    @Test
    fun killToleratesPoliteness() {
        assertTrue(TaskControl.isKillAll("please kill all tasks"))
        assertTrue(TaskControl.isKillAll("kill all tasks please"))
        assertTrue(TaskControl.isKillAll("please kill all tasks please"))
        // NOT loosened: a trailing "now" after the noun
        // was never part of the all-referent grammar
        // (the 1.0.5 pin — "kill all tasks now" is not a
        // kill-all), and edge politeness must not change
        // that verdict, only strip itself.
        assertFalse(TaskControl.isKillAll("please kill all tasks now"))
        assertEquals(
            TaskControl.KillCommand.Targeted(null, false),
            TaskControl.parseKill("please kill this task")
        )
        assertEquals(
            TaskControl.KillCommand.Targeted(null, false),
            TaskControl.parseKill("kill this task please")
        )
        // The "not …" clause still precedes the verb
        // clause once the edge politeness is gone.
        assertEquals(
            TaskControl.KillCommand.Targeted(null, false),
            TaskControl.parseKill("please not this task, kill this")
        )
    }

    @Test
    fun resumeToleratesPoliteness() {
        assertNotNull(TaskControl.parseResume("please resume"))
        assertNotNull(TaskControl.parseResume("resume please"))
        assertNotNull(TaskControl.parseResume("please go on"))
        assertEquals(
            "x", TaskControl.parseResume("please resume the x task")?.hint
        )
    }

    @Test
    fun browserCommandsToleratePoliteness() {
        assertTrue(BrowserControl.parseCloseBrowser("please close the browser"))
        assertTrue(BrowserControl.parseCloseBrowser("close the browser please"))
        assertTrue(BrowserControl.parseCloseBrowser("please close the browser please"))
        assertTrue(BrowserControl.parseOpenBrowser("please open the browser"))
        assertTrue(BrowserControl.parseOpenBrowser("open the browser please"))
        assertTrue(BrowserControl.parseLogOut("please log out"))
        assertTrue(BrowserControl.parseLogOut("log out please"))
        assertEquals(
            BrowserControl.SwitchCommand("lilleagent", true),
            BrowserControl.parseSwitch("please switch to @lilleagent")
        )
        assertEquals(
            BrowserControl.SwitchCommand("lilleagent", true),
            BrowserControl.parseSwitch("switch to @lilleagent please")
        )
        assertEquals(
            BrowserControl.SwitchCommand("dark", false),
            BrowserControl.parseSwitch("switch to dark please")
        )
    }

    @Test
    fun probesAlreadyToleratedPoliteness() {
        // The probe grammars never needed the strip —
        // "please" is a probe function word. Pinned so
        // the house-wide claim stays honest.
        assertTrue(StatusProbe.isProbe("please update"))
        assertTrue(StatusProbe.isProbe("update please"))
        assertTrue(StatusProbe.isReasonProbe("please why paused"))
    }

    @Test
    fun politenessNeverPromotesASentenceToACommand() {
        // The brief's lookalike: after the strip the
        // message leads with "tell" — a question about
        // the work, never a close command.
        val q = "please tell me why the browser closed"
        assertFalse(BrowserControl.parseCloseBrowser(q))
        assertFalse(BrowserControl.parseOpenBrowser(q))
        assertFalse(BrowserControl.parseLogOut(q))
        assertNull(BrowserControl.parseSwitch(q))
        assertNull(TaskControl.parseKill(q))
        assertNull(TaskControl.parseResume(q))
        // A verb buried mid-sentence leads nothing,
        // politeness or not.
        val buried = "please tell me how to close the browser"
        assertFalse(BrowserControl.parseCloseBrowser(buried))
        assertNull(TaskControl.parseKill("please ask her to stop the task"))
        // Politeness alone is not a command either.
        assertFalse(BrowserControl.parseCloseBrowser("please"))
        assertNull(TaskControl.parseKill("please"))
        assertNull(TaskControl.parseResume("please"))
    }

    // ---------- G7: first-person log out ----------

    @Test
    fun logOutFirstPersonShapesParse() {
        for (s in listOf(
            "log me out", "sign me out",
            "log me out of x", "sign me out of twitter",
            "log me out of the x", "sign me out from x",
            "log me out now", "please log me out"
        )) {
            assertTrue(s, BrowserControl.parseLogOut(s))
        }
    }

    @Test
    fun logOutFirstPersonKeepsTheReferentRules() {
        for (s in listOf(
            // Foreign referents stay content — the
            // platform rule is unchanged by "me".
            "log me out of the article comments",
            "log me out of the app",
            "sign me out of everything",
            // Not the shape at all.
            "log me into x", "log out me", "sign me in"
        )) {
            assertFalse(s, BrowserControl.parseLogOut(s))
        }
    }

    // ---------- G6: the resting page ----------

    private val formUrl = "https://example.com/signup-form"

    private fun restingFormTask(): JSONObject {
        val t = record(
            "task-a", TaskStates.WAITING_ROUTE,
            domains = listOf("example.com")
        )
        SessionIdentity.stampRestingPage(t, formUrl)
        return t
    }

    @Test
    fun restingPageStampWritesAndClears() {
        val t = record("t", TaskStates.WAITING_ROUTE)
        assertEquals("", SessionIdentity.restingPage(t))
        SessionIdentity.stampRestingPage(t, formUrl)
        assertEquals(formUrl, SessionIdentity.restingPage(t))
        SessionIdentity.stampRestingPage(t, "")
        assertEquals("", SessionIdentity.restingPage(t))
        assertFalse(t.has(SessionIdentity.RESTING_PAGE_FIELD))
    }

    @Test
    fun aWaitingTasksRestingPageBlocksRecheckNavigation() {
        // THE G6 FIXTURE: a waiting task rests on a
        // filled form page; an identity re-check for
        // ANOTHER record fires. The decision forbids
        // navigation, and the read it gates performs
        // ZERO navigations — the page is untouched and
        // the read lands where an in-place read lands
        // on a page that can't testify: Unreadable,
        // the deferred outcome.
        val resting = restingFormTask()
        val recheck = record("task-b", TaskStates.PAUSED)
        val tasks = listOf(resting, recheck)
        assertEquals(
            "task-a",
            SessionIdentity.restingPageOwner(
                tasks, exceptId = "task-b", currentUrl = formUrl
            )?.optString("id")
        )
        assertFalse(
            SessionIdentity.mayNavigateForRecheck(
                tasks, selfId = "task-b", currentUrl = formUrl
            )
        )
        val driver = FakeIdentityDriver(page(formUrl))
        val outcome = XIdentityRead.conclude(
            driver,
            mayNavigate = SessionIdentity.mayNavigateForRecheck(
                tasks, selfId = "task-b", currentUrl = formUrl
            )
        )
        assertTrue(driver.navigations.isEmpty())
        assertEquals(formUrl, driver.state.url)
        assertTrue(outcome is XIdentityRead.Outcome.Unreadable)
    }

    @Test
    fun aRestingXPageIsReadInPlaceAndConcludes() {
        // The same protection, the concluding case:
        // the resting page is an X page carrying the
        // session's furniture — the in-place read
        // names the session without moving the page.
        val resting = record("task-a", TaskStates.PAUSED)
        SessionIdentity.stampRestingPage(
            resting, "https://x.com/home"
        )
        val tasks = listOf(resting, record("task-b", TaskStates.PAUSED))
        assertFalse(
            SessionIdentity.mayNavigateForRecheck(
                tasks, selfId = "task-b",
                currentUrl = "https://x.com/home"
            )
        )
        val signedIn = page(
            "https://x.com/home",
            listOf(
                el(1, tag = "a", testid = "AppTabBar_Profile_Link",
                    href = "/jeetethuram"),
                el(2, tag = "a", testid = "SideNav_NewTweet_Button",
                    href = "/compose/post")
            )
        )
        val driver = FakeIdentityDriver(signedIn)
        val outcome = XIdentityRead.conclude(driver, mayNavigate = false)
        assertEquals(
            XIdentityRead.Outcome.SignedIn("jeetethuram"), outcome
        )
        assertTrue(driver.navigations.isEmpty())
    }

    @Test
    fun restingPageProtectionHasEdges() {
        val recheck = record("task-b", TaskStates.PAUSED)
        // The re-check's OWN resting page is not held
        // against itself.
        val own = restingFormTask()
        assertTrue(
            SessionIdentity.mayNavigateForRecheck(
                listOf(own), selfId = "task-a", currentUrl = formUrl
            )
        )
        // A different page is nobody's resting page.
        assertTrue(
            SessionIdentity.mayNavigateForRecheck(
                listOf(restingFormTask(), recheck),
                selfId = "task-b",
                currentUrl = "https://x.com/home"
            )
        )
        // Terminal, killed and archived records hold
        // nothing.
        val done = restingFormTask().put("state", TaskStates.COMPLETED)
        assertTrue(
            SessionIdentity.mayNavigateForRecheck(
                listOf(done, recheck), "task-b", formUrl
            )
        )
        val killed = restingFormTask()
            .put(TaskControl.KILLED_FIELD, true)
        assertTrue(
            SessionIdentity.mayNavigateForRecheck(
                listOf(killed, recheck), "task-b", formUrl
            )
        )
        val archived = restingFormTask()
            .put(TaskControl.ARCHIVED_FIELD, true)
        assertTrue(
            SessionIdentity.mayNavigateForRecheck(
                listOf(archived, recheck), "task-b", formUrl
            )
        )
        // A RUNNING record is not resting — its run
        // holds the lane through the gate, not here.
        val running = restingFormTask()
            .put("state", TaskStates.RUNNING)
        assertTrue(
            SessionIdentity.mayNavigateForRecheck(
                listOf(running, recheck), "task-b", formUrl
            )
        )
        // A non-browser record never holds a page.
        val note = record(
            "task-a", TaskStates.WAITING_ROUTE,
            kind = "", domains = emptyList()
        )
        SessionIdentity.stampRestingPage(note, formUrl)
        assertTrue(
            SessionIdentity.mayNavigateForRecheck(
                listOf(note, recheck), "task-b", formUrl
            )
        )
        // A trailing slash is the same page.
        val slashed = restingFormTask()
        SessionIdentity.stampRestingPage(slashed, "$formUrl/")
        assertFalse(
            SessionIdentity.mayNavigateForRecheck(
                listOf(slashed, recheck), "task-b", formUrl
            )
        )
        // No page at all: nothing to protect.
        assertTrue(
            SessionIdentity.mayNavigateForRecheck(
                listOf(restingFormTask(), recheck), "task-b", ""
            )
        )
    }
}
