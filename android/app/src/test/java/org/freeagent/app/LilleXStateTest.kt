package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state-driven owner X action + the recoverability
 * chain it stands on:
 *
 *   creation record (graded evidence: COMPLETED + xHandle)
 *   + her password ONLY in the vault (key x_password)
 *   → encrypted-backup payload (tasks text + vault ride)
 *   → restore into a fresh store
 *   → "Connect Lille's X account" is offered, Create is
 *     gone, and the vault credential is present for it.
 *
 * The backup leg runs through the REAL BackupPayload
 * (de)serialiser — the same document BackupCrypto seals.
 */
class LilleXStateTest {

    private fun creationTask(
        title: String = XAccountTask.TITLE,
        state: String = TaskStates.COMPLETED,
        handle: String = "lille_agent"
    ): JSONObject = JSONObject()
        .put("id", "task-creation")
        .put("title", title)
        .put("state", state)
        .put("xHandle", handle)
        .put("steps", 14)

    @Test
    fun noRecordMeansNoVerifiedAccount() {
        assertFalse(LilleXState.hasVerifiedAccount(emptyList()))
        assertNull(LilleXState.verifiedHandle(emptyList()))
        // An unfinished creation is not a verified account.
        assertFalse(
            LilleXState.hasVerifiedAccount(
                listOf(creationTask(state = TaskStates.PARKED_AT_WALL))
            )
        )
        assertFalse(
            LilleXState.hasVerifiedAccount(
                listOf(creationTask(state = TaskStates.RUNNING))
            )
        )
        // Completed without the graded handle evidence is not either.
        assertFalse(
            LilleXState.hasVerifiedAccount(
                listOf(creationTask(handle = ""))
            )
        )
    }

    @Test
    fun verifiedRecordYieldsHerHandle() {
        assertEquals(
            "lille_agent",
            LilleXState.verifiedHandle(listOf(creationTask()))
        )
        assertTrue(LilleXState.hasVerifiedAccount(listOf(creationTask())))
    }

    @Test
    fun legacyTitleRecordsCount() {
        // The shipped build's parked/completed creation
        // records carry the old title — they are the same
        // creation, and must flip the action just the same.
        assertTrue(
            LilleXState.hasVerifiedAccount(
                listOf(creationTask(title = XAccountTask.LEGACY_TITLE))
            )
        )
        assertTrue(
            LilleXState.isCreationTask(creationTask(title = "anything else"))
                .not()
        )
    }

    @Test
    fun titlesSayHersNotHis() {
        assertEquals("Create Lille's X account", XAccountTask.TITLE)
        assertEquals("Connect Lille's X account", XAccountTask.CONNECT_TITLE)
        assertFalse(XAccountTask.TITLE.contains("my X account"))
    }

    @Test
    fun creationTextKeepsTheVaultDiscipline() {
        // Her password is generated into the vault and typed
        // only via the secret path — the creation spec's own
        // words, pinned so a future edit can't weaken them.
        val kit = XIdentityKit(
            handles = listOf("lille_agent"),
            bio = "bio",
            dob = "2000-01-01",
            mailboxes = listOf("mail.example")
        )
        val text = XAccountTask.taskText(kit)
        assertTrue(text.contains("vault_gen_secret"))
        assertTrue(text.contains("browser_type_secret"))
        assertTrue(text.contains(LilleXState.X_PASSWORD_VAULT_KEY))
        assertTrue(text.contains("Create your OWN X account, Lille"))
        // The connect flow signs her back in from the same
        // vault key — and only from there.
        val connect = XAccountTask.connectTaskText("lille_agent")
        assertTrue(connect.contains("@lille_agent"))
        assertTrue(connect.contains("browser_type_secret"))
        assertTrue(connect.contains(LilleXState.X_PASSWORD_VAULT_KEY))
    }

    @Test
    fun backupRestoreRoundTripOffersConnectWithCredential() {
        // --- the creation record + vault, as creation left them ---
        val tasksText = JSONArray()
            .put(creationTask())
            .put(
                JSONObject()
                    .put("id", "task-other")
                    .put("title", "Something else")
                    .put("state", TaskStates.COMPLETED)
            )
            .toString()
        val vaultSecrets = mapOf(
            LilleXState.X_PASSWORD_VAULT_KEY to "s3cret-value-never-shown"
        )
        val accountsText = JSONArray()
            .put(
                JSONObject()
                    .put("site", "x.com")
                    .put("username", "lille_agent")
                    .put("vault_key", LilleXState.X_PASSWORD_VAULT_KEY)
            )
            .toString()

        // --- export (the payload BackupCrypto seals) ---
        val payload = BackupPayload.build(
            username = "ethuram",
            address = "0x0000000000000000000000000000000000000001",
            createdAt = 123_456L,
            chatsText = null,
            tasksText = tasksText,
            proactiveText = null,
            prefs = emptyMap(),
            vaultSecrets = vaultSecrets,
            vaultAccounts = accountsText,
            sessions = null
        )

        // --- restore into a fresh store ---
        val parsed = BackupPayload.parse(payload)!!
        assertTrue(
            BackupPayload.belongsTo(
                parsed, "ethuram",
                "0x0000000000000000000000000000000000000001"
            )
        )
        val restoredTasks = mutableListOf<JSONObject>()
        val arr = JSONArray(parsed.tasksText!!)
        for (i in 0 until arr.length()) restoredTasks.add(arr.getJSONObject(i))

        // The connect action is offered; create is gone.
        assertTrue(LilleXState.hasVerifiedAccount(restoredTasks))
        assertEquals(
            "lille_agent", LilleXState.verifiedHandle(restoredTasks)
        )
        val offered = MainChatActions.packagedActions(
            isOwner = true,
            lilleXVerified = LilleXState.hasVerifiedAccount(restoredTasks)
        )
        assertTrue(offered.contains(MainChatActions.Action.CONNECT_LILLE_X_ACCOUNT))
        assertFalse(offered.contains(MainChatActions.Action.CREATE_X_ACCOUNT))

        // The vault credential is present for the connect,
        // and the accounts witness agrees on the handle.
        assertEquals(
            "s3cret-value-never-shown",
            parsed.vaultSecrets[LilleXState.X_PASSWORD_VAULT_KEY]
        )
        assertEquals(
            "lille_agent",
            LilleXState.accountsRecordHandle(parsed.vaultAccounts)
        )
    }

    @Test
    fun accountsWitnessReadsTheVaultArrayShape() {
        assertNull(LilleXState.accountsRecordHandle(null))
        assertNull(LilleXState.accountsRecordHandle(""))
        assertNull(LilleXState.accountsRecordHandle("not json"))
        val other = JSONArray()
            .put(JSONObject().put("site", "github.com").put("username", "x"))
            .toString()
        assertNull(LilleXState.accountsRecordHandle(other))
    }
}
