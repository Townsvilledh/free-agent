package org.freeagent.app

/**
 * Brain-failure classification + retry discipline (v0.12;
 * seamless since 2026-11-26).
 *
 * Born of a live failure on Ethuram's phone (2026-11-26): he
 * sent "hi", his connection was fine, the free route refused
 * — and the chat answered with a terminal "Couldn't reach the
 * free brain — check your connection and try again." Wrong
 * cause, wrong instruction, dead end.
 *
 * The v0.12 fix classified every failure and composed an
 * honest cause line for the chat. Ethuram's seamless law
 * (same day, from a screenshot of that very copy) supersedes
 * the copy half: route failures, refusals and rate-limits
 * are NEVER narrated on a user surface. Fallback is
 * immediate and invisible; the user sees the thinking state,
 * then the answer. So this component now keeps ONLY the
 * machinery half:
 *
 * - Every brain-call failure is CLASSIFIED (below) from the
 *   HTTP status / the I/O failure / the device's actual
 *   connectivity state — never guessed. The class drives
 *   behaviour (fall through / wait / end), never prose.
 * - Every retryable class keeps the user's message queued
 *   and auto-retries with escalating waits until a route
 *   recovers. There is no retry cap and no error wall for a
 *   retryable failure — she thinks longer, on another route
 *   if needed, exactly like the task engine.
 * - Terminal errors are reserved for genuinely permanent
 *   failures (the request itself is malformed for every
 *   route) — and even then the user's message stays in the
 *   chat; only the reply attempt ends, with the ONE plain
 *   line ([EXHAUSTED_LINE]).
 *
 * The waits reuse BrainPacing's arithmetic (Retry-After
 * wins; otherwise 60 s × 2^streak, capped at 15 min), so
 * chat and the task engine share one pacing discipline.
 * Serving-route facts live in traces and graded report
 * proof blocks — never in failure narration (NarrationGuard
 * vets any stored note a surface would echo).
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

    /** The ONE plain line a chat attempt may end with when
     *  every route is exhausted terminally. No route names,
     *  no mechanics — the seamless law's only permitted
     *  failure sentence. (Retryable exhaustion never reaches
     *  it: she waits in the ordinary thinking state and
     *  retries on her own.) */
    const val EXHAUSTED_LINE =
        "I can't reach a brain just now — try me again in a moment."

    /** The routes the chat chain can walk, in chain
     *  order. The keyless pair is LIVE-VERIFIED as
     *  genuinely keyless (ROUTES.md carries the dated
     *  verdicts); the rest are the user's OWN key routes
     *  (v0.12.1, OwnKeyRoutes) — present in a chain only
     *  when the user has saved that key (see [chain]).
     *  Routes carry no user-facing labels any more: naming
     *  a route to the user is narration (seamless law). */
    enum class Route(val key: String) {
        POLLINATIONS("pollinations"),
        HORDE("horde"),
        GROQ("groq"),
        GEMINI("gemini"),
        CEREBRAS("cerebras"),
        OPENROUTER("openrouter"),
        AGENTROUTER("agentrouter"),
        CUSTOM("custom")
    }

    /** The keyless chain — always present, needs nothing
     *  from anybody (the no-keys doctrine's floor). */
    val KEYLESS_CHAIN: List<Route> = listOf(Route.POLLINATIONS, Route.HORDE)

    /** The own-key routes in their walk order: Groq →
     *  Gemini → Cerebras → OpenRouter → AgentRouter →
     *  custom. */
    val OWN_KEY_ORDER: List<Route> = listOf(
        Route.GROQ, Route.GEMINI, Route.CEREBRAS,
        Route.OPENROUTER, Route.AGENTROUTER, Route.CUSTOM
    )

    /** The chain for one conversation. OWN KEYS LEAD
     *  (v1.0.2, Ethuram 2026-11-27): whichever own-key
     *  routes the user has actually saved a key for walk
     *  FIRST, in walk order — a user's own key is their
     *  front door, not a fallback — and the keyless pair
     *  follows as the fallback that needs nothing from
     *  anybody. With no keys saved the chain is exactly
     *  the keyless pair, as it has always been. A route
     *  with no key saved is simply absent — never a
     *  placeholder, never an error. The reorder touches
     *  ONLY the order among routes already eligible for
     *  the content: privacy routing decides eligibility
     *  before any chain is walked, unchanged. */
    fun chain(ownKeyPresent: Set<Route>): List<Route> =
        OWN_KEY_ORDER.filter { it in ownKeyPresent } + KEYLESS_CHAIN

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
