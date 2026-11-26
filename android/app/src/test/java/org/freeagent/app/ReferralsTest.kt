package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Referrals (REFERRALS.md): the invite link and pitch are
 * purely Lille — no other product's name anywhere in them;
 * a referral completes only at a first sign-in carrying an
 * inviter, first referrer wins, self-referral never counts;
 * ledgers merge under the same first-wins rule. These tests
 * pin the pure component the screens act on.
 */
class ReferralsTest {

    private fun ledgerWithPending(name: String): JSONObject {
        val root = Referrals.emptyLedger()
        Referrals.setPending(root, name, Referrals.SOURCE_LINK, 100L)
        return root
    }

    // ---- the link + pitch: Lille only ----

    @Test
    fun inviteLinkCarriesTheNormalisedName() {
        assertEquals(
            "https://townsvilledh.github.io/free-agent/invite.html?ref=ethuram",
            Referrals.inviteLink("@Ethuram")
        )
    }

    @Test
    fun inviteLinkIsNullWithoutAName() {
        assertNull(Referrals.inviteLink(null))
        assertNull(Referrals.inviteLink("  "))
    }

    @Test
    fun linkAndPitchNeverMentionTheOtherProduct() {
        val link = Referrals.inviteLink("ethuram")!!
        val pitch = Referrals.shareText("ethuram")!!
        assertFalse(link.lowercase().contains("fluid"))
        assertFalse(pitch.lowercase().contains("fluid"))
        assertTrue(pitch.contains(link))
        assertTrue(pitch.contains("ethuram"))
    }

    // ---- invite links reaching the app ----

    @Test
    fun referrerParsedFromTheWebLink() {
        assertEquals(
            "alice",
            Referrals.referrerFromUri(
                "https://townsvilledh.github.io/free-agent/invite.html?ref=Alice"
            )
        )
        assertEquals(
            "alice",
            Referrals.referrerFromUri(
                "https://townsvilledh.github.io/free-agent/invite.html?utm=x&ref=%40alice"
            )
        )
    }

    @Test
    fun referrerParsedFromTheAppScheme() {
        assertEquals("bob", Referrals.referrerFromUri("lille://invite?ref=bob"))
    }

    @Test
    fun nonInviteLinksNeverYieldAReferrer() {
        assertNull(Referrals.referrerFromUri(null))
        assertNull(Referrals.referrerFromUri("https://example.com/?ref=alice"))
        assertNull(
            Referrals.referrerFromUri(
                "https://townsvilledh.github.io/free-agent/invite.html"
            )
        )
        assertNull(
            Referrals.referrerFromUri(
                "https://townsvilledh.github.io/free-agent/invite.html?ref="
            )
        )
        assertNull(Referrals.referrerFromUri("lille://invite"))
    }

    // ---- pending: first touch wins ----

    @Test
    fun firstPendingInviterWins() {
        val root = Referrals.emptyLedger()
        Referrals.setPending(root, "alice", Referrals.SOURCE_LINK, 1L)
        Referrals.setPending(root, "bob", Referrals.SOURCE_LINK, 2L)
        assertEquals("alice", Referrals.pendingReferrer(root))
    }

    // ---- settle: completion at first sign-in ----

    @Test
    fun settleCompletesFromPendingLink() {
        val root = ledgerWithPending("alice")
        val outcome = Referrals.settle(root, "carol", null, 500L)
        assertTrue(outcome is Referrals.Outcome.Completed)
        val record = (outcome as Referrals.Outcome.Completed).record
        assertEquals("alice", record.referrer)
        assertEquals("carol", record.referred)
        assertEquals(Referrals.SOURCE_LINK, record.source)
        assertNull(Referrals.pendingReferrer(root))
        assertEquals(1, Referrals.completedCountFor(root, "alice"))
    }

    @Test
    fun typedNameBeatsPendingAndMarksItsSource() {
        val root = ledgerWithPending("alice")
        val outcome = Referrals.settle(root, "carol", "@Dave", 500L)
        assertTrue(outcome is Referrals.Outcome.Completed)
        val record = (outcome as Referrals.Outcome.Completed).record
        assertEquals("dave", record.referrer)
        assertEquals(Referrals.SOURCE_NAME, record.source)
    }

    @Test
    fun selfReferralNeverCounts() {
        val root = Referrals.emptyLedger()
        val outcome = Referrals.settle(root, "carol", "Carol", 500L)
        assertEquals(Referrals.Outcome.SelfReferral, outcome)
        assertTrue(Referrals.recordsOf(root).isEmpty())
    }

    @Test
    fun noInviterIsAnHonestNoReferrer() {
        val root = Referrals.emptyLedger()
        assertEquals(
            Referrals.Outcome.NoReferrer,
            Referrals.settle(root, "carol", "  ", 500L)
        )
        assertTrue(Referrals.recordsOf(root).isEmpty())
    }

    @Test
    fun firstReferrerWinsAcrossSettles() {
        val root = ledgerWithPending("alice")
        Referrals.settle(root, "carol", null, 500L)
        // A later sign-in offering another inviter changes nothing.
        val again = Referrals.settle(root, "carol", "mallory", 900L)
        assertEquals(Referrals.Outcome.AlreadyRecorded, again)
        val records = Referrals.recordsOf(root)
        assertEquals(1, records.size)
        assertEquals("alice", records[0].referrer)
        assertEquals(0, Referrals.completedCountFor(root, "mallory"))
    }

    // ---- parse: defensive, first-wins inside one document ----

    @Test
    fun parseDropsJunkAndSelfPairsAndKeepsEarliest() {
        val text = """
            {"version":1,"records":[
              {"referrer":"alice","referred":"carol","at":900,"source":"name"},
              {"referrer":"bob","referred":"carol","at":300,"source":"link"},
              {"referrer":"carol","referred":"carol","at":100,"source":"name"},
              {"referrer":"","referred":"dan","at":100,"source":"name"}
            ],"pending":{"referrer":"@Eve","source":"link","at":50}}
        """.trimIndent()
        val root = Referrals.parseLedger(text)
        val records = Referrals.recordsOf(root)
        assertEquals(1, records.size)
        assertEquals("bob", records[0].referrer)
        assertEquals(300L, records[0].completedAt)
        assertEquals("eve", Referrals.pendingReferrer(root))
    }

    @Test
    fun parseOfGarbageIsAnEmptyLedger() {
        assertTrue(Referrals.recordsOf(Referrals.parseLedger("not json")).isEmpty())
        assertTrue(Referrals.recordsOf(Referrals.parseLedger(null)).isEmpty())
    }

    // ---- export + merge: the sync path ----

    @Test
    fun exportCarriesRecordsOnlyNeverPending() {
        val root = ledgerWithPending("alice")
        Referrals.settle(root, "carol", null, 500L)
        Referrals.setPending(root, "zoe", Referrals.SOURCE_LINK, 600L)
        val exported = Referrals.exportJson(root)
        assertFalse(exported.contains("zoe"))
        val parsed = Referrals.parseLedger(exported)
        assertEquals(1, Referrals.recordsOf(parsed).size)
        assertNull(Referrals.pendingReferrer(parsed))
    }

    @Test
    fun mergeUnionsAndEarliestCompletionWins() {
        val mine = Referrals.emptyLedger()
        Referrals.settle(mine, "carol", "alice", 500L)
        val theirs = Referrals.emptyLedger()
        Referrals.settle(theirs, "carol", "bob", 300L)
        Referrals.settle(theirs, "dan", "alice", 700L)
        val added = Referrals.mergeLedgers(mine, Referrals.exportJson(theirs))
        assertEquals(1, added)
        val records = Referrals.recordsOf(mine)
        assertEquals(2, records.size)
        // Carol's record flipped to the earlier completion (bob).
        assertEquals("bob", records.first { it.referred == "carol" }.referrer)
        assertEquals(1, Referrals.completedCountFor(mine, "alice"))
        assertEquals(1, Referrals.completedCountFor(mine, "bob"))
        // Merging the same export again adds nothing.
        assertEquals(0, Referrals.mergeLedgers(mine, Referrals.exportJson(theirs)))
    }

    @Test
    fun mergeKeepsTheLocalPendingSlot() {
        val mine = ledgerWithPending("alice")
        val theirs = ledgerWithPending("zoe")
        Referrals.mergeLedgers(mine, theirs.toString())
        assertEquals("alice", Referrals.pendingReferrer(mine))
    }
}
