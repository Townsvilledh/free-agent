package org.freeagent.app

/**
 * Brain-failure classification + retry discipline (v0.12).
 *
 * Born of a live failure on Ethuram's phone (2026-11-26): he
 * sent "hi", his connection was fine, the free route refused
 * — and the chat answered with a terminal "Couldn't reach the
 * free brain — check your connection and try again." Wrong
 * cause, wrong instruction, dead end. That breaks the
 * Fallback Law, and this component is the fix's pure half:
 *
 * - Every brain-call failure is CLASSIFIED (below) from the
 *   HTTP status / the I/O failure / the device's actual
 *   connectivity state — never guessed.
 * - Every retryable class keeps the user's message queued,
 *   shows an honest status line naming the REAL cause, and
 *   auto-retries with escalating waits until a route
 *   recovers. There is no retry cap and no error wall for a
 *   retryable failure — she thinks longer, on another route
 *   if needed, exactly like the task engine.
 * - "Check your connection" copy is chosen ONLY when the
 *   device is actually offline — the glue checks
 *   ConnectivityManager before this component is consulted.
 * - Terminal errors are reserved for genuinely permanent
 *   failures (the request itself is malformed for every
 *   route) — and even then the user's message stays in the
 *   chat; only the reply attempt ends.
 *
 * The waits reuse BrainPacing's arithmetic (Retry-After
 * wins; otherwise 60 s × 2^streak, capped at 15 min), so
 * chat and the task engine share one pacing discipline.
 */
object BrainFailure {

    enum class Class {
        /** The device itself has no network. Copy may say so. */
        OFFLINE,

        /** I/O failure or timeout while the device IS online:
         *  the route or the path to it hiccuped. */
        NETWORK,

        /** 402 / 429 — the free tier's per-IP limit. On mobile
         *  networks that IP is shared with everyone on the
         *  same carrier NAT, so the copy says so. */
        RATE_LIMITED,

        /** 5xx — the route's server is having a bad moment. */
        SERVER,

        /** Other 4xx — the request shape itself is refused by
         *  this route. Another route may still take it; if
         *  every route refuses permanently, the attempt ends. */
        PERMANENT
    }

    /** What kind of I/O failure an OkHttp onFailure carried,
     *  reduced to what the classifier needs. */
    enum class IoKind { TIMEOUT, OTHER }

    /**
     * Classify one failed brain call.
     *
     * @param httpCode the response status, or null when the
     *   call never got a response (I/O failure).
     * @param ioKind for the no-response case.
     * @param deviceOnline the device's real connectivity
     *   state, checked by the glue at failure time. An I/O
     *   failure while offline is OFFLINE, whatever the
     *   exception was.
     */
    fun classify(httpCode: Int?, ioKind: IoKind?, deviceOnline: Boolean): Class {
        if (httpCode != null) {
            return when {
                httpCode == 402 || httpCode == 429 -> Class.RATE_LIMITED
                httpCode in 500..599 -> Class.SERVER
                httpCode in 400..499 -> Class.PERMANENT
                else -> Class.SERVER
            }
        }
        if (!deviceOnline) return Class.OFFLINE
        return when (ioKind) {
            IoKind.TIMEOUT -> Class.NETWORK
            IoKind.OTHER -> Class.NETWORK
            null -> Class.NETWORK
        }
    }

    /** Retryable classes never dead-end the message: it
     *  stays queued and the chain retries until a route
     *  recovers or the user stops it. */
    fun isRetryable(failure: Class): Boolean = failure != Class.PERMANENT

    /** The wait before the next attempt of the chain.
     *  Retry-After (seconds, from the failing response) wins
     *  when larger; otherwise BrainPacing's escalating
     *  cooldown. OFFLINE waits longer between probes — the
     *  phone, not the route, has to come back. */
    fun waitMs(failure: Class, retryAfterSec: Long?, streak: Int): Long {
        val base = BrainPacing.cooldownMs(retryAfterSec, streak)
        return if (failure == Class.OFFLINE) maxOf(base, 120_000L) else base
    }

    /**
     * The honest in-chat status line for a failed attempt.
     * [nextRouteLabel] names the fallback that is about to be
     * tried ("the slow community route"), or null when the
     * chain is exhausted and the wait is for recovery.
     * [waitText] is BrainPacing.describeWait's phrasing.
     */
    fun statusLine(
        failure: Class,
        nextRouteLabel: String?,
        waitText: String,
        ownKeyName: String? = null
    ): String {
        // v0.12.1: when the route that failed is one of the
        // user's OWN key routes, the cause names that route
        // and its own free-tier limit — the shared-carrier-IP
        // explanation belongs to the anonymous keyless tier
        // only, and saying it about a personal key would be
        // the wrong cause (the discipline this file exists
        // for). OFFLINE is a device fact either way.
        val cause = when (failure) {
            Class.OFFLINE ->
                "You're offline right now — this message is saved here, " +
                    "and Lille keeps retrying on her own as soon as a " +
                    "connection appears."
            Class.RATE_LIMITED ->
                if (ownKeyName != null) {
                    "Your $ownKeyName key route hit its free-tier limit " +
                        "for now — your message is safe and queued."
                } else {
                    "The free brain route is busy — its anonymous tier limits " +
                        "replies per internet address, and on mobile networks " +
                        "that address is shared with other people on your " +
                        "carrier, so the limit can be used up by strangers."
                }
            Class.NETWORK ->
                if (ownKeyName != null) {
                    "Your $ownKeyName key route didn't answer in time — " +
                        "the route or the path to it hiccuped. Your " +
                        "connection is fine; this one is on the route."
                } else {
                    "The free brain route didn't answer in time — the route " +
                        "or the path to it hiccuped. Your connection is fine; " +
                        "this one is on the route."
                }
            Class.SERVER ->
                if (ownKeyName != null) {
                    "Your $ownKeyName key route's server is having a bad " +
                        "moment (it answered with an error). Your message " +
                        "is safe and queued."
                } else {
                    "The free brain route's server is having a bad moment " +
                        "(it answered with an error). Your message is safe " +
                        "and queued."
                }
            Class.PERMANENT ->
                if (ownKeyName != null) {
                    "Your $ownKeyName key route refused that request's shape."
                } else {
                    "The free brain route refused that request's shape."
                }
        }
        val next = when {
            nextRouteLabel != null ->
                " Trying $nextRouteLabel next."
            failure == Class.PERMANENT -> ""
            else ->
                " Lille will retry on her own in about $waitText — no " +
                    "need to re-send, and nothing is sent anywhere else."
        }
        return cause + next
    }

    /** The routes the chat chain can walk, in chain
     *  order. The keyless pair is LIVE-VERIFIED as
     *  genuinely keyless (ROUTES.md carries the dated
     *  verdicts); the rest are the user's OWN key routes
     *  (v0.12.1, OwnKeyRoutes) — present in a chain only
     *  when the user has saved that key (see [chain]). */
    enum class Route(val key: String, val label: String) {
        POLLINATIONS("pollinations", "the main free route"),
        HORDE("horde", "the slow community route"),
        GROQ("groq", "your Groq key route"),
        GEMINI("gemini", "your Gemini key route"),
        CEREBRAS("cerebras", "your Cerebras key route"),
        OPENROUTER("openrouter", "your OpenRouter key route"),
        AGENTROUTER("agentrouter", "your AgentRouter key route"),
        CUSTOM("custom", "your custom endpoint")
    }

    /** The keyless chain — always present, needs nothing
     *  from anybody (the no-keys doctrine's floor). */
    val KEYLESS_CHAIN: List<Route> = listOf(Route.POLLINATIONS, Route.HORDE)

    /** The own-key routes in their walk order, after the
     *  keyless pair: Groq → Gemini → Cerebras → OpenRouter
     *  → AgentRouter → custom. */
    val OWN_KEY_ORDER: List<Route> = listOf(
        Route.GROQ, Route.GEMINI, Route.CEREBRAS,
        Route.OPENROUTER, Route.AGENTROUTER, Route.CUSTOM
    )

    /** The chain for one conversation: the keyless pair,
     *  then whichever own-key routes the user has actually
     *  saved a key for, in walk order. A route with no key
     *  saved is simply absent — never a placeholder, never
     *  an error. */
    fun chain(ownKeyPresent: Set<Route>): List<Route> =
        KEYLESS_CHAIN + OWN_KEY_ORDER.filter { it in ownKeyPresent }

    /** The route to try after [route] failed in [chain],
     *  or null when the chain is exhausted for this
     *  attempt. Any failure falls through — another
     *  route's request shape and limits differ — but the
     *  attempt ends when the LAST route refuses
     *  permanently. The no-chain overload walks the
     *  keyless chain (the v0.12 semantics, unchanged). */
    fun nextRoute(route: Route, chain: List<Route>): Route? {
        val idx = chain.indexOf(route)
        if (idx < 0 || idx >= chain.size - 1) return null
        return chain[idx + 1]
    }

    fun nextRoute(route: Route): Route? = nextRoute(route, KEYLESS_CHAIN)

    // ---- per-route health (v0.12 hunt) ----
    //
    // A route that keeps failing is DEMOTED: it cools down
    // for an escalating stretch and the chain skips it while
    // it rests. Recovery is automatic — when the cooldown
    // expires the route is simply eligible again and the next
    // message probes it; a success clears its record. The
    // state itself (failure counts + cooldown deadlines)
    // lives in the glue; the arithmetic lives here, pinned
    // by tests.

    /** How long a route rests after [consecutiveFailures]
     *  failures in a row: 2 min, then 10, then 30 (cap).
     *  Zero failures = not resting. */
    fun routeCooldownMs(consecutiveFailures: Int): Long = when {
        consecutiveFailures <= 0 -> 0L
        consecutiveFailures == 1 -> 120_000L
        consecutiveFailures == 2 -> 600_000L
        else -> 1_800_000L
    }

    /** The next route to try after [after] failed, SKIPPING
     *  any route whose health cooldown is still running at
     *  [nowMs] ([coolingUntilMs] maps route -> deadline).
     *  Null when every later route is resting (or none is
     *  left) — the attempt then waits for recovery, and the
     *  earliest deadline is when a probe makes sense. */
    fun pickNext(
        after: Route,
        chain: List<Route>,
        coolingUntilMs: Map<Route, Long>,
        nowMs: Long
    ): Route? {
        val idx = chain.indexOf(after)
        if (idx < 0) return null
        for (i in idx + 1 until chain.size) {
            val candidate = chain[i]
            val until = coolingUntilMs[candidate] ?: 0L
            if (until <= nowMs) return candidate
        }
        return null
    }

    /** The keyless-chain overload (v0.12 semantics,
     *  unchanged): the walk when no own keys exist. */
    fun pickNext(
        after: Route,
        coolingUntilMs: Map<Route, Long>,
        nowMs: Long
    ): Route? = pickNext(after, KEYLESS_CHAIN, coolingUntilMs, nowMs)

    /** The first route of [chain] that is not resting at
     *  [nowMs] — where a NEW message should start. Null when
     *  the whole chain is resting (the message then waits
     *  for the earliest recovery instead of firing into a
     *  wall). */
    fun firstHealthy(
        chain: List<Route>,
        coolingUntilMs: Map<Route, Long>,
        nowMs: Long
    ): Route? {
        for (route in chain) {
            val until = coolingUntilMs[route] ?: 0L
            if (until <= nowMs) return route
        }
        return null
    }

    /** The keyless-chain overload (v0.12 semantics,
     *  unchanged). */
    fun firstHealthy(
        coolingUntilMs: Map<Route, Long>,
        nowMs: Long
    ): Route? = firstHealthy(KEYLESS_CHAIN, coolingUntilMs, nowMs)

    /** True when a failure on the LAST route ends the whole
     *  attempt terminally (permanent everywhere). Retryable
     *  last-route failures schedule the next attempt instead. */
    fun attemptEndsTerminally(lastRouteFailure: Class): Boolean =
        lastRouteFailure == Class.PERMANENT
}
