package org.freeagent.app

import org.json.JSONObject

/**
 * Your own keys (v0.12.1) — the catalogue of OPTIONAL
 * own-key brain routes, as pure data + pure logic.
 *
 * The no-keys doctrine is unchanged (KEYLESS.md): Lille
 * works fully with zero keys, no key is shipped, collected,
 * or shared, and these routes exist only when the user adds
 * their OWN free-tier key in Settings. Keys live in the
 * Keystore-wrapped DeviceVault under the key names below —
 * this component never holds a key VALUE; it knows names,
 * endpoints, and default models only. The glue resolves a
 * name to a value at the moment of the call, and the value
 * crosses only into that provider's Authorization header.
 *
 * Chain order (BrainFailure.OWN_KEY_ORDER): Groq → Gemini →
 * Cerebras → OpenRouter → AgentRouter → custom. Since
 * v1.0.2 (OWN KEYS LEAD) the present own-key routes walk
 * FIRST and the keyless routes (Pollinations, AI Horde)
 * are the fallback; with no keys saved the chain is the
 * keyless pair alone, exactly as before.
 *
 * Default models (verified 2026-11-26 where a keyless check
 * exists; ROUTES.md carries the evidence):
 * - Groq `openai/gpt-oss-120b` — the model PROVEN LIVE on
 *   the owner's Groq account through the core engine on
 *   2026-11-26 (hostkeys proof, trace
 *   20261126-191027-4d9146). The previous default,
 *   `llama-3.3-70b-versatile`, returns 404
 *   model_not_found on that account — verified live the
 *   same day; the core fixed it identically in
 *   free_agent/config.py (pinned by
 *   tests/test_groq_provider_config.py). Never lead with
 *   the dead model again; OwnKeyRoutesTest pins this.
 * - Gemini `gemini-flash-latest` — Google's documented
 *   alias for the current Flash model on the Gemini API's
 *   OpenAI-compatible endpoint (the core's long-standing
 *   default).
 * - Cerebras `llama-3.3-70b` — Cerebras' documented
 *   free-tier flagship (provider docs; ~1M tokens/day).
 * - OpenRouter `openrouter/free` — OpenRouter's own
 *   free-model auto-router, verified present in the
 *   keyless GET /models list on 2026-11-26 (475 models;
 *   the :free roster rotates, the router id does not).
 * - AgentRouter `claude-opus-4-8` — the core config's
 *   documented first model; AgentRouter's model list is
 *   key-gated, so no keyless verification exists.
 * - Custom: the user's own endpoint; their model name if
 *   they gave one, else "default".
 */
object OwnKeyRoutes {

    /** One provider's shape. [baseUrl] is null only for
     *  CUSTOM, whose base URL is itself a vault value. */
    data class Spec(
        val route: BrainFailure.Route,
        val displayName: String,
        /** Vault key name holding the API key value. */
        val vaultKeyName: String,
        val baseUrl: String?,
        val defaultModel: String,
        /** Where the user gets a free key — shown in
         *  Settings so the door is actually findable. */
        val keyHint: String
    )

    const val CUSTOM_URL_VAULT_KEY = "apikey.custom.url"
    const val CUSTOM_MODEL_VAULT_KEY = "apikey.custom.model"
    const val CUSTOM_DEFAULT_MODEL = "default"

    /** "Your computer" (v1.0.4, steal #13): the LAN route's
     *  address + model live in the vault under these names.
     *  The LAN route usually carries NO key (a local server
     *  on the user's own network) — presence is the address
     *  plus the Settings on-switch (LanRoute), never a key. */
    const val LAN_URL_VAULT_KEY = "lan.base.url"
    const val LAN_MODEL_VAULT_KEY = "lan.base.model"
    const val LAN_DEFAULT_MODEL = "default"

    /** The LAN route's spec shape: baseUrl null (the vault
     *  holds the address, like CUSTOM), no vault key name
     *  (no key exists to hold). */
    fun lanSpec(): Spec = Spec(
        BrainFailure.Route.LAN, "Your computer", "",
        null, LAN_DEFAULT_MODEL,
        "A model server on your own computer, on your network"
    )

    val SPECS: List<Spec> = listOf(
        Spec(
            BrainFailure.Route.GROQ, "Groq", "apikey.groq",
            "https://api.groq.com/openai/v1", "openai/gpt-oss-120b",
            "Free key: console.groq.com"
        ),
        Spec(
            BrainFailure.Route.GEMINI, "Google Gemini", "apikey.gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-flash-latest",
            "Free key: Google AI Studio (aistudio.google.com)"
        ),
        Spec(
            BrainFailure.Route.CEREBRAS, "Cerebras", "apikey.cerebras",
            "https://api.cerebras.ai/v1", "llama-3.3-70b",
            "Free key: cloud.cerebras.ai"
        ),
        Spec(
            BrainFailure.Route.OPENROUTER, "OpenRouter", "apikey.openrouter",
            "https://openrouter.ai/api/v1", "openrouter/free",
            "Free key: openrouter.ai (free models)"
        ),
        Spec(
            BrainFailure.Route.AGENTROUTER, "AgentRouter", "apikey.agentrouter",
            "https://agentrouter.org/v1", "claude-opus-4-8",
            "Free key: agentrouter.org"
        ),
        Spec(
            BrainFailure.Route.CUSTOM, "Custom endpoint", "apikey.custom.key",
            null, CUSTOM_DEFAULT_MODEL,
            "Any OpenAI-compatible endpoint you run or trust"
        )
    )

    private val BY_ROUTE: Map<BrainFailure.Route, Spec> =
        SPECS.associateBy { it.route }

    fun spec(route: BrainFailure.Route): Spec? = BY_ROUTE[route]

    fun isOwnKeyRoute(route: BrainFailure.Route): Boolean = BY_ROUTE.containsKey(route)

    /**
     * Which own-key routes are PRESENT — a key saved in the
     * vault. [hasSecret] is the vault's hasSecret by key
     * name; no key value is read here. The custom route is
     * present only with BOTH a base URL and a key — a URL
     * alone is not a route. Absent routes are simply not in
     * the chain: no nagging, no error, no placeholder.
     */
    fun presentRoutes(
        hasSecret: (String) -> Boolean,
        lanEnabled: Boolean = false
    ): Set<BrainFailure.Route> {
        val out = mutableSetOf<BrainFailure.Route>()
        for (spec in SPECS) {
            if (!hasSecret(spec.vaultKeyName)) continue
            if (spec.route == BrainFailure.Route.CUSTOM &&
                !hasSecret(CUSTOM_URL_VAULT_KEY)
            ) {
                continue
            }
            out.add(spec.route)
        }
        // The LAN route is present when the user switched
        // it on AND gave an address — presence by address +
        // switch, not by key (there is no key).
        if (lanEnabled && hasSecret(LAN_URL_VAULT_KEY)) {
            out.add(BrainFailure.Route.LAN)
        }
        return out
    }

    /** The base URL a call should use: the spec's, or the
     *  vault-held custom base. Trailing slashes trimmed so
     *  path joining is exact. */
    fun baseUrl(spec: Spec, customBaseUrl: String?): String =
        (spec.baseUrl ?: customBaseUrl ?: "").trimEnd('/')

    fun chatUrl(spec: Spec, customBaseUrl: String?): String =
        baseUrl(spec, customBaseUrl) + "/chat/completions"

    fun modelsUrl(spec: Spec, customBaseUrl: String?): String =
        baseUrl(spec, customBaseUrl) + "/models"

    /** The model a chat call names: the user-given model
     *  when set (the custom route's and the LAN route's —
     *  both point at user-run servers), else the spec
     *  default. */
    fun chatModel(spec: Spec, customModel: String?): String =
        if ((spec.route == BrainFailure.Route.CUSTOM ||
                spec.route == BrainFailure.Route.LAN) &&
            !customModel.isNullOrBlank()
        ) {
            customModel.trim()
        } else {
            spec.defaultModel
        }

    /** Count the models in an OpenAI-style GET /models
     *  body ({"data":[...]}). Null when the body isn't that
     *  shape — a 200 with an uncountable list is still a
     *  working key, just not a countable one. */
    fun countModels(body: String): Int? {
        return try {
            val data = JSONObject(body).optJSONArray("data") ?: return null
            data.length()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The Settings Test button's verdict line. ONE minimal
     * call (GET /models) was made; this renders only its
     * outcome — success/failure and the model count when
     * the provider listed models. The key itself is never
     * in any input here, so it can never be echoed.
     *
     * @param httpCode the response status, or null when the
     *   call never got a response (I/O failure).
     */
    fun testLine(spec: Spec, httpCode: Int?, modelCount: Int?): String {
        val name = spec.displayName
        return when {
            httpCode == null ->
                "Couldn't reach $name — check the address and your " +
                    "connection, then test again."
            httpCode in 200..299 ->
                if (modelCount != null) {
                    "Works — your $name key answered, with $modelCount " +
                        "models listed. It is now one of Lille's brain routes."
                } else {
                    "Works — your $name key answered. It is now one of " +
                        "Lille's brain routes."
                }
            httpCode == 401 || httpCode == 403 ->
                "$name refused that key (HTTP $httpCode). Check it was " +
                    "copied in full, with no spaces — the key itself is " +
                    "never shown here."
            httpCode == 404 && spec.route == BrainFailure.Route.CUSTOM ->
                "That address answered 404 — the base URL should end " +
                    "at the API root (usually …/v1), with no " +
                    "/chat/completions on the end."
            httpCode == 429 ->
                "$name is rate-limiting right now (HTTP 429) — the key " +
                    "reached it, so the route is wired; try again later."
            httpCode in 500..599 ->
                "$name's server is having a bad moment (HTTP $httpCode) " +
                    "— the route is wired; try again later."
            else ->
                "$name answered HTTP $httpCode — the route did not " +
                    "accept the test call."
        }
    }
}
