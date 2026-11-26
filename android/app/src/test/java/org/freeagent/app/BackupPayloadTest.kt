package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Backup payload: build/parse fidelity (stores ride as text,
 *  the vault rides whole), typed prefs round-trips, and the
 *  restore binding to one Fluid identity. */
class BackupPayloadTest {

    private fun samplePayload(): String = BackupPayload.build(
        username = "Ethuram",
        address = "0xAbC0000000000000000000000000000000000001",
        createdAt = 999L,
        chatsText = """{"version":1,"chats":[]}""",
        tasksText = """[{"id":"task-1"}]""",
        proactiveText = null,
        prefs = mapOf(
            "lille_fluid_identity" to mapOf(
                "username" to "ethuram",
                "remembered" to true,
                "count" to 3,
                "big" to 4000000000L,
                "ratio" to 1.5f,
                "tags" to setOf("a", "b")
            )
        ),
        vaultSecrets = mapOf("x_password" to "s3cret-value"),
        vaultAccounts = """[{"site":"x.com","username":"LilleAgent"}]""",
        sessions = mapOf("x.com" to "auth_token=xyz")
    )

    @Test
    fun buildParseRoundTrip() {
        val parsed = BackupPayload.parse(samplePayload())!!
        assertEquals("Ethuram", parsed.username)
        assertEquals(999L, parsed.createdAt)
        assertEquals("""{"version":1,"chats":[]}""", parsed.chatsText)
        assertEquals("""[{"id":"task-1"}]""", parsed.tasksText)
        assertNull(parsed.proactiveText)
        assertEquals("s3cret-value", parsed.vaultSecrets["x_password"])
        assertTrue(parsed.vaultAccounts!!.contains("LilleAgent"))
        assertEquals("auth_token=xyz", parsed.sessions["x.com"])
    }

    @Test
    fun prefsRoundTripKeepsTypes() {
        val parsed = BackupPayload.parse(samplePayload())!!
        val file = parsed.prefs["lille_fluid_identity"]!!
        assertEquals("ethuram", file["username"])
        assertEquals(true, file["remembered"])
        assertEquals(3, file["count"])
        assertEquals(4000000000L, file["big"])
        assertEquals(1.5f, file["ratio"])
        assertEquals(setOf("a", "b"), file["tags"])
    }

    @Test
    fun bindingMatchesCaseInsensitivelyOnly() {
        val parsed = BackupPayload.parse(samplePayload())!!
        assertTrue(
            BackupPayload.belongsTo(
                parsed, "ethuram", "0xabc0000000000000000000000000000000000001"
            )
        )
        assertFalse(
            BackupPayload.belongsTo(
                parsed, "someoneelse", "0xabc0000000000000000000000000000000000001"
            )
        )
        assertFalse(
            BackupPayload.belongsTo(
                parsed, "ethuram", "0x0000000000000000000000000000000000000002"
            )
        )
    }

    @Test
    fun parseRejectsForeignDocuments() {
        assertNull(BackupPayload.parse("not json"))
        assertNull(BackupPayload.parse("""{"version":99,"username":"x","address":"0x1"}"""))
        assertNull(BackupPayload.parse("""{"version":1,"username":"","address":"0x1"}"""))
        assertNull(BackupPayload.parse("""{"version":1,"username":"x"}"""))
    }

    @Test
    fun minimalPayloadParsesWithEmptySections() {
        val text = BackupPayload.build(
            "u", "0xA", 1L, null, null, null,
            emptyMap(), emptyMap(), null, null
        )
        val parsed = BackupPayload.parse(text)!!
        assertNull(parsed.chatsText)
        assertTrue(parsed.vaultSecrets.isEmpty())
        assertTrue(parsed.sessions.isEmpty())
        assertTrue(parsed.prefs.isEmpty())
    }
}
