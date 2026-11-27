package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OwnKeyRoutes (v0.12.1) — the own-keys door. Pins:
 * route presence is purely a function of what is saved
 * in the vault (absent key = absent route, no nagging),
 * the chain order is keyless-first then Groq → Gemini →
 * Cerebras → OpenRouter → AgentRouter → custom, the
 * custom route needs URL + key, and the Settings Test
 * verdict classifies outcomes without ever touching a
 * key value (the component never receives one).
 *
 * The vault itself is Android-bound (Keystore-wrapped
 * EncryptedSharedPreferences), so presence/round-trip is
 * pinned here against an in-memory stand-in keyed by the
 * same vault key names the glue uses — put under a
 * spec's name and the route appears; remove it and the
 * route is gone.
 */
class OwnKeyRoutesTest {

    /** In-memory vault stand-in: key name -> dummy value.
     *  Values are dummy strings only — no real key ever
     *  appears in a test. */
    private class FakeVault {
        val store = mutableMapOf<String, String>()
        val has: (String) -> Boolean = { store.containsKey(it) }
        fun put(name: String) {
            store[name] = "dummy-key-value"
        }
        fun remove(name: String) {
            store.remove(name)
        }
    }

    // ---- presence / vault round-trip ----

    @Test
    fun noKeysSavedMeansNoOwnKeyRoutes() {
        val vault = FakeVault()
        assertTrue(OwnKeyRoutes.presentRoutes(vault.has).isEmpty())
        // And the chain is exactly the keyless pair.
        assertEquals(
            BrainFailure.KEYLESS_CHAIN,
            BrainFailure.chain(OwnKeyRoutes.presentRoutes(vault.has))
        )
    }

    @Test
    fun savingAKeyAddsItsRouteAndRemovingItDropsIt() {
        val vault = FakeVault()
        val groq = OwnKeyRoutes.spec(BrainFailure.Route.GROQ)!!
        vault.put(groq.vaultKeyName)
        assertEquals(
            setOf(BrainFailure.Route.GROQ),
            OwnKeyRoutes.presentRoutes(vault.has)
        )
        vault.remove(groq.vaultKeyName)
        assertTrue(OwnKeyRoutes.presentRoutes(vault.has).isEmpty())
    }

    @Test
    fun customRouteNeedsBothUrlAndKey() {
        val vault = FakeVault()
        val custom = OwnKeyRoutes.spec(BrainFailure.Route.CUSTOM)!!
        vault.put(custom.vaultKeyName)
        // Key alone: not a route.
        assertFalse(
            BrainFailure.Route.CUSTOM in OwnKeyRoutes.presentRoutes(vault.has)
        )
        vault.put(OwnKeyRoutes.CUSTOM_URL_VAULT_KEY)
        assertTrue(
            BrainFailure.Route.CUSTOM in OwnKeyRoutes.presentRoutes(vault.has)
        )
        vault.remove(OwnKeyRoutes.CUSTOM_URL_VAULT_KEY)
        assertFalse(
            BrainFailure.Route.CUSTOM in OwnKeyRoutes.presentRoutes(vault.has)
        )
    }

    @Test
    fun everyVaultKeyNameIsValidForTheDeviceVault() {
        // DeviceVault refuses names outside this pattern —
        // a bad name here would make Save silently fail.
        val keyRe = Regex("^[A-Za-z0-9_.-]{1,64}$")
        for (spec in OwnKeyRoutes.SPECS) {
            assertTrue(spec.vaultKeyName, keyRe.matches(spec.vaultKeyName))
        }
        assertTrue(keyRe.matches(OwnKeyRoutes.CUSTOM_URL_VAULT_KEY))
        assertTrue(keyRe.matches(OwnKeyRoutes.CUSTOM_MODEL_VAULT_KEY))
    }

    @Test
    fun specsCoverExactlyTheOwnKeyOrder() {
        assertEquals(
            BrainFailure.OWN_KEY_ORDER,
            OwnKeyRoutes.SPECS.map { it.route }
        )
        // No keyless route may appear as an own-key spec.
        for (spec in OwnKeyRoutes.SPECS) {
            assertFalse(spec.route in BrainFailure.KEYLESS_CHAIN)
        }
    }

    // ---- chain order (OWN KEYS LEAD, v1.0.2) ----

    @Test
    fun chainIsOwnKeysFirstThenKeylessFallback() {
        val all = BrainFailure.OWN_KEY_ORDER.toSet()
        assertEquals(
            listOf(
                BrainFailure.Route.GROQ,
                BrainFailure.Route.GEMINI,
                BrainFailure.Route.CEREBRAS,
                BrainFailure.Route.OPENROUTER,
                BrainFailure.Route.AGENTROUTER,
                BrainFailure.Route.CUSTOM,
                BrainFailure.Route.POLLINATIONS,
                BrainFailure.Route.HORDE
            ),
            BrainFailure.chain(all)
        )
    }

    @Test
    fun chainSubsetKeepsWalkOrderNotSaveOrder() {
        val present = setOf(
            BrainFailure.Route.OPENROUTER,
            BrainFailure.Route.GROQ,
            BrainFailure.Route.CUSTOM
        )
        assertEquals(
            listOf(
                BrainFailure.Route.GROQ,
                BrainFailure.Route.OPENROUTER,
                BrainFailure.Route.CUSTOM,
                BrainFailure.Route.POLLINATIONS,
                BrainFailure.Route.HORDE
            ),
            BrainFailure.chain(present)
        )
    }

    @Test
    fun ownKeyLeadsWhenPresentKeylessLeadsWhenNot() {
        // A saved Groq key leads the chain; the keyless
        // pair is its fallback.
        val withGroq = BrainFailure.chain(setOf(BrainFailure.Route.GROQ))
        assertEquals(BrainFailure.Route.GROQ, withGroq.first())
        assertEquals(BrainFailure.KEYLESS_CHAIN, withGroq.drop(1))
        // No keys at all: the keyless pair leads, exactly
        // as it always has.
        assertEquals(
            BrainFailure.Route.POLLINATIONS,
            BrainFailure.chain(emptySet()).first()
        )
        // A failed own-key route falls through TO the
        // keyless pair (seamless fallback, new order).
        assertEquals(
            BrainFailure.Route.POLLINATIONS,
            BrainFailure.pickNext(
                BrainFailure.Route.CUSTOM,
                BrainFailure.chain(BrainFailure.OWN_KEY_ORDER.toSet()),
                emptyMap(), 1_000L
            )
        )
    }

    // ---- health demotion applies to own-key routes ----

    @Test
    fun ownKeyRoutesRestAndRecoverLikeKeylessOnes() {
        val chain = BrainFailure.chain(BrainFailure.OWN_KEY_ORDER.toSet())
        val now = 2_000_000L
        // Groq (the lead) + the keyless pair resting: a new
        // message starts at Gemini, the first healthy
        // own-key route — resting leaders are skipped, not
        // waited on, while another route exists.
        val resting = mapOf(
            BrainFailure.Route.POLLINATIONS to now + 60_000L,
            BrainFailure.Route.HORDE to now + 60_000L,
            BrainFailure.Route.GROQ to now + 60_000L
        )
        assertEquals(
            BrainFailure.Route.GEMINI,
            BrainFailure.firstHealthy(chain, resting, now)
        )
        // After Groq fails, the walk skips a resting Gemini.
        val geminiResting = mapOf(
            BrainFailure.Route.GEMINI to now + 600_000L,
            BrainFailure.Route.GROQ to now + 60_000L
        )
        assertEquals(
            BrainFailure.Route.CEREBRAS,
            BrainFailure.pickNext(
                BrainFailure.Route.GROQ, chain, geminiResting, now
            )
        )
        // When Groq's rest expires it leads again —
        // automatic recovery, no manual reset.
        assertEquals(
            BrainFailure.Route.GROQ,
            BrainFailure.firstHealthy(chain, geminiResting, now + 61_000L)
        )
        // The demotion arithmetic itself is route-agnostic.
        assertEquals(120_000L, BrainFailure.routeCooldownMs(1))
        assertEquals(1_800_000L, BrainFailure.routeCooldownMs(5))
        // Nothing exists after the last route (Horde, the
        // keyless fallback of last resort).
        assertNull(
            BrainFailure.pickNext(BrainFailure.Route.HORDE, chain, emptyMap(), now)
        )
    }

    // ---- URL / model shaping ----

    @Test
    fun urlsJoinCleanlyAndCustomModelWins() {
        val groq = OwnKeyRoutes.spec(BrainFailure.Route.GROQ)!!
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            OwnKeyRoutes.chatUrl(groq, null)
        )
        assertEquals(
            "https://api.groq.com/openai/v1/models",
            OwnKeyRoutes.modelsUrl(groq, null)
        )
        val custom = OwnKeyRoutes.spec(BrainFailure.Route.CUSTOM)!!
        assertEquals(
            "http://192.168.1.5:1234/v1/chat/completions",
            OwnKeyRoutes.chatUrl(custom, "http://192.168.1.5:1234/v1/")
        )
        assertEquals(
            "my-local-model",
            OwnKeyRoutes.chatModel(custom, "  my-local-model ")
        )
        assertEquals(
            OwnKeyRoutes.CUSTOM_DEFAULT_MODEL,
            OwnKeyRoutes.chatModel(custom, "  ")
        )
        assertEquals(
            "llama-3.3-70b-versatile",
            OwnKeyRoutes.chatModel(groq, "ignored-for-fixed-providers")
        )
    }

    // ---- Test-button verdicts ----

    @Test
    fun modelCountingParsesTheOpenAiShapeOnly() {
        assertEquals(
            3,
            OwnKeyRoutes.countModels(
                """{"data":[{"id":"a"},{"id":"b"},{"id":"c"}]}"""
            )
        )
        assertEquals(0, OwnKeyRoutes.countModels("""{"data":[]}"""))
        assertNull(OwnKeyRoutes.countModels("""{"models":[]}"""))
        assertNull(OwnKeyRoutes.countModels("not json"))
    }

    @Test
    fun testLineClassifiesOutcomesAndNeverEchoesAKey() {
        val groq = OwnKeyRoutes.spec(BrainFailure.Route.GROQ)!!
        val ok = OwnKeyRoutes.testLine(groq, 200, 15)
        assertTrue(ok.contains("Works"))
        assertTrue(ok.contains("15"))
        val okNoCount = OwnKeyRoutes.testLine(groq, 200, null)
        assertTrue(okNoCount.contains("Works"))
        val refused = OwnKeyRoutes.testLine(groq, 401, null)
        assertTrue(refused.contains("refused"))
        assertTrue(refused.contains("401"))
        val limited = OwnKeyRoutes.testLine(groq, 429, null)
        assertTrue(limited.contains("rate-limiting"))
        val server = OwnKeyRoutes.testLine(groq, 503, null)
        assertTrue(server.contains("503"))
        val unreachable = OwnKeyRoutes.testLine(groq, null, null)
        assertTrue(unreachable.contains("Couldn't reach"))
        // The verdict's inputs are (spec, code, count) —
        // there is no key anywhere in this API, so no line
        // can contain one. Pinned with a dummy marker: the
        // marker string is never an input, so it can never
        // be an output.
        val marker = "dummy-key-value"
        for (line in listOf(ok, okNoCount, refused, limited, server, unreachable)) {
            assertFalse(line.contains(marker))
        }
    }

    @Test
    fun testLineCustom404ExplainsTheBaseUrlShape() {
        val custom = OwnKeyRoutes.spec(BrainFailure.Route.CUSTOM)!!
        val line = OwnKeyRoutes.testLine(custom, 404, null)
        assertTrue(line.contains("404"))
        assertTrue(line.contains("/v1"))
    }
}
