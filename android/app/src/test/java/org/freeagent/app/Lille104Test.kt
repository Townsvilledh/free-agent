package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-cutting pins for the 1.0.4 release: the LAN route's
 * place in the chain (steal #13), the heartbeat phase inside
 * the proactive pass (steals #1 + #6), the Set-of-Marks
 * fallback (steal #4), and the backup payload carrying her
 * agent state.
 */
class Lille104Test {

    // ---------- LAN route (#13) ----------

    @Test
    fun lanRouteLeadsTheOwnRoutesWhenPresent() {
        val chain = BrainFailure.chain(
            setOf(BrainFailure.Route.LAN, BrainFailure.Route.GROQ)
        )
        assertEquals(BrainFailure.Route.LAN, chain.first())
        assertEquals(BrainFailure.Route.GROQ, chain[1])
    }

    @Test
    fun lanPresenceNeedsTheSwitchAndAnAddress() {
        val vault = mapOf(OwnKeyRoutes.LAN_URL_VAULT_KEY to "http://192.168.1.20:1234/v1")
        val has: (String) -> Boolean = { vault.containsKey(it) }
        assertFalse(
            BrainFailure.Route.LAN in
                OwnKeyRoutes.presentRoutes(has, lanEnabled = false)
        )
        assertTrue(
            BrainFailure.Route.LAN in
                OwnKeyRoutes.presentRoutes(has, lanEnabled = true)
        )
        // Switch on, no address: absent.
        assertFalse(
            BrainFailure.Route.LAN in OwnKeyRoutes.presentRoutes(
                { false }, lanEnabled = true
            )
        )
    }

    @Test
    fun keylessChainIsUnchangedWithoutOwnRoutes() {
        assertEquals(
            listOf(
                BrainFailure.Route.POLLINATIONS,
                BrainFailure.Route.HORDE
            ),
            BrainFailure.chain(emptySet())
        )
    }

    // ---------- heartbeat in the pass (#1 + #6) ----------

    private class FakeData : ProactiveData {
        // Like the real store, every read returns a FRESH
        // list — a pass mutates the copy and saves it back.
        val cmts = mutableListOf<JSONObject>()
        val rpts = mutableListOf<JSONObject>()
        val pks = mutableMapOf<String, String>()
        override fun commitments() = cmts.toMutableList()
        override fun saveCommitments(list: List<JSONObject>) {
            cmts.clear(); cmts.addAll(list)
        }
        override fun reports() = rpts.toMutableList()
        override fun saveReports(list: List<JSONObject>) {
            rpts.clear(); rpts.addAll(list)
        }
        override fun pickups() = pks.toMutableMap()
        override fun savePickups(map: Map<String, String>) {
            pks.clear(); pks.putAll(map)
        }
    }

    private class FakeHeartbeat(
        var text: String?, var signature: String = ""
    ) : HeartbeatData {
        override fun heartbeatText() = text
        override fun heartbeatSignature() = signature
        override fun saveHeartbeatSignature(signature: String) {
            this.signature = signature
        }
    }

    @Test
    fun heartbeatPhaseIsSilentThenSurfacesOnce() {
        val data = FakeData()
        val hb = FakeHeartbeat("- [ ] Water the plants")
        val pass = ProactivePass(
            data, { emptyList() }, { 200 to "" }, { 1_000L },
            heartbeat = hb
        )
        // Nothing due, nothing parked: silent — no report.
        val first = pass.run()
        assertEquals(0, first.reportsWritten)
        assertTrue(hb.signature.isNotEmpty())
        // A parked task appears: ONE report, needs counted.
        val parked = JSONObject()
            .put("id", "t1")
            .put("text", "do the thing")
            .put("state", TaskStates.PARKED_AT_WALL)
            .put("updatedAt", 2_000L)
        val pass2 = ProactivePass(
            data, { listOf(parked) }, { 200 to "" }, { 3_000L },
            heartbeat = hb
        )
        val second = pass2.run()
        assertEquals(1, second.heartbeatNeeds)
        assertEquals(1, data.rpts.size)
    }

    @Test
    fun heartbeatSkipsWhenNothingChangedAndChecklistEmpty() {
        val data = FakeData()
        val hb = FakeHeartbeat(null)
        // First pass over an empty world: SILENT — it
        // establishes the baseline signature, writes nothing.
        val first = ProactivePass(
            data, { emptyList() }, { 200 to "" }, { 1_000L },
            heartbeat = hb
        ).run()
        assertEquals(0, first.reportsWritten)
        assertTrue(hb.signature.isNotEmpty())
        // Second pass, nothing changed, checklist empty:
        // SKIP — the signature is not even re-saved.
        val before = hb.signature
        val second = ProactivePass(
            data, { emptyList() }, { 200 to "" }, { 2_000L },
            heartbeat = hb
        ).run()
        assertEquals(0, second.reportsWritten)
        assertEquals(0, second.heartbeatNeeds)
        assertEquals(before, hb.signature)
    }

    // ---------- Set-of-Marks (#4) ----------

    @Test
    fun marksExistOnlyForEmptyTreePages() {
        val empty = BrowserPerception.PageState(
            url = "https://canvas.example.com",
            title = "Canvas app",
            text = "a rendered surface",
            elements = emptyList(),
            frames = emptyList()
        )
        val marks = BrowserPerception.marksFor(empty)
        assertEquals(
            BrowserPerception.MARK_COLS * BrowserPerception.MARK_ROWS,
            marks.size
        )
        assertEquals(1, marks.first().n)
        // Centre of region 1 is inside the top-left cell.
        assertTrue(marks.first().cx < 0.34f)
        assertTrue(marks.first().cy < 0.26f)
        // The formatted state carries the MARKS block.
        assertTrue(
            BrowserPerception.formatState(empty, "PAGE")
                .contains("MARKS")
        )
        assertTrue(
            BrowserPerception.formatLite(empty, "PAGE")
                .contains("browser_click_mark")
        )
        // A page WITH a tree gets refs, never marks.
        val withTree = BrowserPerception.PageState(
            url = "https://x.com",
            title = "X",
            text = "hello",
            elements = listOf(
                BrowserPerception.Element(
                    1, "button", "", "", "", "Post",
                    "", "", "", false
                )
            ),
            frames = emptyList()
        )
        assertTrue(BrowserPerception.marksFor(withTree).isEmpty())
        assertFalse(
            BrowserPerception.formatState(withTree, "PAGE")
                .contains("MARKS")
        )
    }

    // ---------- backup carries agent state ----------

    @Test
    fun payloadRoundTripsAgentState() {
        val built = BackupPayload.build(
            username = "ethuram",
            address = "0x0000000000000000000000000000000000000001",
            createdAt = 1L,
            chatsText = null,
            tasksText = null,
            proactiveText = null,
            prefs = emptyMap(),
            vaultSecrets = emptyMap(),
            vaultAccounts = null,
            sessions = null,
            agentStateText = "{\"sections\":{\"memory\":\"{}\"}}"
        )
        val parsed = BackupPayload.parse(built)!!
        assertEquals(
            "{\"sections\":{\"memory\":\"{}\"}}",
            parsed.agentStateText
        )
        // An older payload without the field parses with null.
        val old = BackupPayload.build(
            username = "ethuram",
            address = "0x0000000000000000000000000000000000000001",
            createdAt = 1L,
            chatsText = null,
            tasksText = null,
            proactiveText = null,
            prefs = emptyMap(),
            vaultSecrets = emptyMap(),
            vaultAccounts = null,
            sessions = null
        )
        assertEquals(null, BackupPayload.parse(old)!!.agentStateText)
    }
}
