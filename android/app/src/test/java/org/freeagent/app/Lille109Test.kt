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
 * v1.0.9 — identity answers are LIVE reads, never
 * stale stamps; the which-account family; no
 * ask-backs about her own state.
 *
 * Born of Ethuram's 2026-11-27 14:29–14:50 screenshots
 * (1.0.8): "which x account is logged in" fell to the
 * brain, which quoted a signed-OUT stamp written
 * before he signed in during a browser takeover —
 * while her own panel sat open on his signed-in home
 * feed — and closed by asking him to check the handle
 * himself. "what x profile is logged in now" reached
 * the deterministic family and got the verifying
 * line. Both phrasings, and the freshness rule behind
 * them, are pinned here.
 */
class Lille109Test {

    private val chat = "chat-109"
    private val now = 1_700_000_000_000L

    // ---------- fixtures ----------

    private fun record(
        title: String, state: String,
        extra: (JSONObject) -> Unit = {}
    ): JSONObject {
        val t = JSONObject()
            .put("id", "task-" + title.hashCode())
            .put("title", title)
            .put("text", title)
            .put("state", state)
            .put(ChatTaskLink.ORIGIN_FIELD, chat)
            .put("createdAt", now - 3_600_000L)
            .put("updatedAt", now - 60_000L)
            .put("messages", JSONArray())
            .put("steps", 3)
        extra(t)
        return t
    }

    private fun connectRecord(): JSONObject =
        record("Connect my X account", TaskStates.RUNNING) {
            it.put("kind", "browser")
        }

    private fun el(
        testid: String = "", text: String = "",
        href: String = "", tag: String = "a"
    ): BrowserPerception.Element =
        BrowserPerception.Element(
            ref = 1, tag = tag, type = "", role = "",
            testid = testid, text = text, name = "",
            placeholder = "", href = href, secret = false
        )

    private fun page(
        url: String, elements: List<BrowserPerception.Element>
    ): BrowserPerception.PageState =
        BrowserPerception.PageState(
            url = url, title = "X", text = "",
            elements = elements, frames = emptyList()
        )

    /** The 14:29 page, shape one: a signed-in home feed
     *  whose tree DOES carry the profile tab. */
    private fun handleInTreePage() = page(
        "https://x.com/home",
        listOf(
            el(testid = "SideNav_NewTweet_Button", tag = "button"),
            el(
                testid = "AppTabBar_Profile_Link",
                href = "/jeetethuram"
            )
        )
    )

    /** The 14:29 page, shape two: a signed-in home feed
     *  (compose + notifications furniture) whose handle
     *  is NOT in the tree — it sits behind the top-left
     *  avatar / account menu. The page 1.0.8's read
     *  stamped signed OUT. */
    private fun handleBehindMenuPage() = page(
        "https://x.com/home",
        listOf(
            el(testid = "SideNav_NewTweet_Button", tag = "button"),
            el(href = "/compose/post"),
            el(
                testid = "AppTabBar_Notifications_Link",
                href = "/notifications"
            )
        )
    )

    /** The same page after the account menu opens: the
     *  switcher names the handle. */
    private fun menuOpenPage() = page(
        "https://x.com/home",
        listOf(
            el(
                testid = "SideNav_AccountSwitcher_Button",
                text = "Ethuram\n@jeetethuram", tag = "button"
            )
        )
    )

    /** A signed-out page: the login/signup doors. */
    private fun signedOutPage() = page(
        "https://x.com/home",
        listOf(
            el(text = "Log in", href = "/login"),
            el(text = "Sign up", href = "/i/flow/signup")
        )
    )

    // ---------- 1. the freshness rule ----------

    @Test
    fun stampOlderThanBrowserActivityNeedsALiveRead() {
        val task = connectRecord()
        SessionIdentity.stamp(task, "jeetethuram", now - 3_600_000L)
        // The browser moved an hour after the stamp
        // (a takeover — exactly the 14:29 sequence).
        val activity = now
        assertTrue(SessionIdentity.stampStale(task, activity))
        assertTrue(SessionIdentity.needsLiveRead(task, activity))
    }

    @Test
    fun freshStampIsQuotedDirectly() {
        val task = connectRecord()
        SessionIdentity.stamp(task, "jeetethuram", now)
        val activity = now - 3_600_000L
        assertFalse(SessionIdentity.stampStale(task, activity))
        assertFalse(SessionIdentity.needsLiveRead(task, activity))
    }

    @Test
    fun lastBrowserActivityTakesTheNewestMarker() {
        val a = connectRecord()
        a.put("handedBackAt", now - 1_000L)
        val b = record("Create Lille's X account", TaskStates.RUNNING) {
            it.put(SessionIdentity.TAKEOVER_AT_FIELD, now - 500L)
        }
        // Session marker newest of all.
        assertEquals(
            now,
            SessionIdentity.lastBrowserActivity(listOf(a, b), now)
        )
        // Then the takeover stamp.
        assertEquals(
            now - 500L,
            SessionIdentity.lastBrowserActivity(
                listOf(a, b), now - 2_000L
            )
        )
        // Then the handback.
        assertEquals(
            now - 1_000L,
            SessionIdentity.lastBrowserActivity(
                listOf(a), now - 2_000L
            )
        )
        assertEquals(
            0L, SessionIdentity.lastBrowserActivity(emptyList(), 0L)
        )
    }

    @Test
    fun pendingHandbackAndRecheckRequestAlsoNeedALiveRead() {
        val task = connectRecord()
        SessionIdentity.stamp(task, "jeetethuram", now)
        assertFalse(SessionIdentity.needsLiveRead(task, 0L))
        // A handback lands after the verification: owed.
        task.put("handedBackAt", now + 1_000L)
        assertTrue(SessionIdentity.handbackPending(task))
        assertTrue(SessionIdentity.needsLiveRead(task, 0L))
        // A chat's re-check request after the last read: owed.
        val other = connectRecord()
        SessionIdentity.stamp(other, "jeetethuram", now)
        other.put(SessionIdentity.RECHECK_AT_FIELD, now + 5_000L)
        assertTrue(SessionIdentity.recheckRequested(other))
        assertTrue(SessionIdentity.needsLiveRead(other, 0L))
        // A concluded read satisfies the request.
        SessionIdentity.stamp(other, "jeetethuram", now + 9_000L)
        assertFalse(SessionIdentity.recheckRequested(other))
        assertFalse(SessionIdentity.needsLiveRead(other, 0L))
    }

    @Test
    fun stateSheetMarksAStaleIdentityAsBeingRechecked() {
        val task = connectRecord()
        SessionIdentity.stamp(task, null, now - 3_600_000L)
        val sheet = StateSheet.render(
            listOf(task),
            StateSheet.Facts(
                browserUrl = "https://x.com/home",
                browserTitle = "Home / X",
                xVerifiedHandle = null,
                walletFamilies = emptyList(),
                browserActivityAt = now
            ),
            now
        )
        assertTrue(sheet.contains("being re-checked"))
        // The stale signed-out read is never stated as
        // the browser's current state.
        assertFalse(sheet.contains("; it is signed out"))
        assertFalse(sheet.contains("Browser signed out"))
    }

    @Test
    fun stateSheetStatesAFreshIdentityAsFact() {
        val task = connectRecord()
        SessionIdentity.stamp(task, "jeetethuram", now)
        val sheet = StateSheet.render(
            listOf(task),
            StateSheet.Facts(
                browserUrl = "https://x.com/home",
                browserTitle = "Home / X",
                xVerifiedHandle = null,
                walletFamilies = emptyList(),
                browserActivityAt = now - 3_600_000L
            ),
            now
        )
        assertTrue(sheet.contains("signed in as @jeetethuram"))
        assertFalse(sheet.contains("being re-checked"))
    }

    // ---------- 2. the concluding read's shapes ----------

    @Test
    fun identityShapesJudgeBothSignedInPageShapes() {
        val inTree = handleInTreePage()
        assertEquals(
            "jeetethuram",
            BrowserPerception.xHandleFromState(inTree)
        )
        assertEquals(
            BrowserPerception.XIdentityShape.HANDLE_IN_TREE,
            BrowserPerception.xIdentityShape(
                inTree, BrowserPerception.xHandleFromState(inTree)
            )
        )
        // Behind the menu: signed-in chrome, no handle —
        // the read's next step, NEVER a signed-out call.
        val behind = handleBehindMenuPage()
        assertNull(BrowserPerception.xHandleFromState(behind))
        assertEquals(
            BrowserPerception.XIdentityShape.HANDLE_BEHIND_MENU,
            BrowserPerception.xIdentityShape(behind, null)
        )
        // After the menu opens, the switcher names it.
        val menu = menuOpenPage()
        assertEquals(
            "jeetethuram",
            BrowserPerception.xHandleFromState(menu)
        )
        // The profile-tab reveal: the URL itself names it.
        assertEquals(
            "jeetethuram",
            BrowserPerception.xHandleFromProfileUrl(
                "https://x.com/jeetethuram"
            )
        )
        assertNull(
            BrowserPerception.xHandleFromProfileUrl(
                "https://x.com/home"
            )
        )
        assertNull(
            BrowserPerception.xHandleFromProfileUrl(
                "https://x.com/settings/account"
            )
        )
    }

    @Test
    fun identityShapesJudgeSignedOutAndUnreadable() {
        assertEquals(
            BrowserPerception.XIdentityShape.SIGNED_OUT,
            BrowserPerception.xIdentityShape(signedOutPage(), null)
        )
        val blank = page("https://x.com/home", emptyList())
        assertEquals(
            BrowserPerception.XIdentityShape.UNREADABLE,
            BrowserPerception.xIdentityShape(blank, null)
        )
    }

    @Test
    fun unreadableStampsLeaveTheIdentityStampUntouched() {
        val task = connectRecord()
        SessionIdentity.stamp(task, "jeetethuram", now)
        SessionIdentity.stampUnreadable(
            task, "the page shows neither a signed-in account nor a sign-in form",
            now + 1_000L
        )
        // The old stamp survives as history…
        assertEquals("jeetethuram", SessionIdentity.signedInAs(task))
        // …but the unreadable mark is the latest word,
        // and any answer needs a live read.
        assertNotNull(SessionIdentity.unreadableReason(task))
        assertTrue(SessionIdentity.needsLiveRead(task, 0L))
        // A concluded read supersedes the mark.
        SessionIdentity.stamp(task, null, now + 2_000L)
        assertNull(SessionIdentity.unreadableReason(task))
        assertNull(SessionIdentity.signedInAs(task))
    }

    @Test
    fun watchdogRetriesOnlyAgedUnreadableReads() {
        val aged = connectRecord()
        SessionIdentity.stampUnreadable(
            aged, "reason", now - SessionIdentity.UNREADABLE_RETRY_MS - 1L
        )
        val fresh = record("Connect my X account", TaskStates.RUNNING) {
            it.put("kind", "browser")
            SessionIdentity.stampUnreadable(it, "reason", now - 60_000L)
        }
        val done = record("Connect my X account", TaskStates.COMPLETED) {
            it.put("kind", "browser")
            SessionIdentity.stampUnreadable(
                it, "reason", now - SessionIdentity.UNREADABLE_RETRY_MS - 1L
            )
        }
        val killed = record("Connect my X account", TaskStates.RUNNING) {
            it.put("kind", "browser")
            it.put(TaskControl.KILLED_FIELD, true)
            SessionIdentity.stampUnreadable(
                it, "reason", now - SessionIdentity.UNREADABLE_RETRY_MS - 1L
            )
        }
        val ids = TaskWatchdog.identityRecheckIds(
            listOf(aged, fresh, done, killed), now
        )
        assertEquals(listOf(aged.optString("id")), ids)
    }

    @Test
    fun stateSheetStatesAnUnreadableLatestReadAsUnknown() {
        val task = connectRecord()
        SessionIdentity.stamp(task, "jeetethuram", now - 3_600_000L)
        SessionIdentity.stampUnreadable(
            task, "the page shows neither a signed-in account nor a sign-in form",
            now - 60_000L
        )
        val sheet = StateSheet.render(
            listOf(task),
            StateSheet.Facts(
                browserUrl = "https://x.com/home",
                browserTitle = "Home / X",
                xVerifiedHandle = null,
                walletFamilies = emptyList(),
                browserActivityAt = now - 3_600_000L
            ),
            now
        )
        assertTrue(sheet.contains("couldn't read the page"))
        assertTrue(sheet.contains("no older sign-in state is current"))
        assertFalse(sheet.contains("Browser signed in as @jeetethuram"))
    }

    // ---------- 3. the which-account family ----------

    @Test
    fun whichAccountFixturesResolveDeterministically() {
        val tasks = listOf(connectRecord())
        // His two 2026-11-27 phrasings, verbatim.
        assertNotNull(
            StatusProbe.whichAccountTaskFor(
                "which x account is logged in", tasks, chat
            )
        )
        assertNotNull(
            StatusProbe.whichAccountTaskFor(
                "what x profile is logged in now", tasks, chat
            )
        )
        // Reworded variants resolve the same way.
        assertNotNull(
            StatusProbe.whichAccountTaskFor(
                "which account is signed in?", tasks, chat
            )
        )
        assertNotNull(
            StatusProbe.whichAccountTaskFor(
                "what handle is she logged in as", tasks, chat
            )
        )
        assertNotNull(
            StatusProbe.whichAccountTaskFor(
                "which profile is currently signed in", tasks, chat
            )
        )
    }

    @Test
    fun whichAccountLookalikesFallThrough() {
        val tasks = listOf(connectRecord())
        assertNull(
            StatusProbe.whichAccountTaskFor(
                "which account should i use for the signup",
                tasks, chat
            )
        )
        assertNull(
            StatusProbe.whichAccountTaskFor(
                "what profile picture should she use", tasks, chat
            )
        )
        assertNull(
            StatusProbe.whichAccountTaskFor(
                "which handle should she pick for the signup",
                tasks, chat
            )
        )
        // A job, not a question about the session.
        assertNull(
            StatusProbe.whichAccountTaskFor(
                "log in to the site and post the update", tasks, chat
            )
        )
    }

    @Test
    fun whichAccountAnswerNamesTheAccountFromAFreshStamp() {
        val connect = connectRecord()
        SessionIdentity.stamp(connect, "jeetethuram", now)
        val answer = StatusProbe.whichAccountAnswerFor(connect, now)
        assertTrue(answer.contains("signed in as @jeetethuram"))
        assertTrue(answer.contains("approval-gated"))
        val out = connectRecord()
        SessionIdentity.stamp(out, null, now)
        val outAnswer = StatusProbe.whichAccountAnswerFor(out, now)
        assertTrue(outAnswer.contains("signed out"))
        assertTrue(
            StatusProbe.verifyingIdentityLine(connect)
                .contains("verifying that from the browser itself")
        )
    }

    // ---------- 4. the ask-back guard ----------

    private val SCREENSHOT_ASK_BACK =
        "I don't have visibility into the specific X handle " +
            "that's currently logged in. The browser on this " +
            "phone is still showing a signed-out state (its " +
            "last recorded page is https://x.com/home and " +
            "it's marked \"signed out\"). If you've just " +
            "signed in, you can verify which account is " +
            "active by checking the profile icon or the URL " +
            "@username in the browser yourself. Let me know " +
            "once you've confirmed the handle, and I can " +
            "continue any next steps you need."

    @Test
    fun askBackScreenshotParagraphIsBlockedOnBothPaths() {
        val verdict = ChatOutputGuard.verdict(SCREENSHOT_ASK_BACK)
        assertTrue(
            verdict is ChatOutputGuard.Verdict.Rejected &&
                verdict.reason == "ask-back"
        )
        val report = ChatOutputGuard.verdictForReport(SCREENSHOT_ASK_BACK)
        assertTrue(
            report is ChatOutputGuard.Verdict.Rejected &&
                report.reason == "ask-back"
        )
    }

    @Test
    fun askBackVariantsAreBlocked() {
        val variants = listOf(
            "You can check the task status in the panel.",
            "Please verify which account is signed in before posting.",
            "Tell me which profile is active and I'll continue.",
            "Let me know once you've confirmed the handle.",
            "Check the browser page yourself and tell me what it shows."
        )
        for (v in variants) {
            val verdict = ChatOutputGuard.verdict(v)
            assertTrue(
                "expected ask-back for: $v",
                verdict is ChatOutputGuard.Verdict.Rejected &&
                    verdict.reason == "ask-back"
            )
        }
    }

    @Test
    fun legitimateAsksStayClean() {
        val clean = listOf(
            // The parked sign-in instructions: his step,
            // her verification — no ask-back shape.
            "Log in to X in Lille's browser: tap 'Open " +
                "Lille's browser', sign in with your own X " +
                "account, then tap 'Done — hand back to " +
                "Lille'. Lille never sees your password.",
            // An approval ask — genuinely his input.
            "The post is ready. Say 'approve' and I'll post it.",
            // A preference ask.
            "Let me know if you'd like the summary shorter.",
            // A world-fact ask: no own-state object.
            "Let me know once you've checked the price on " +
                "the exchange and I'll size the order.",
            // Her own verified statements about her state.
            "The browser is signed in as @jeetethuram — " +
                "she read it off the page just now.",
            "She's verifying that from the browser itself " +
                "right now — the verified answer lands here " +
                "from the record, not from messages."
        )
        for (c in clean) {
            assertEquals(
                "expected clean for: $c",
                ChatOutputGuard.Verdict.Clean,
                ChatOutputGuard.verdict(c)
            )
        }
    }
}
