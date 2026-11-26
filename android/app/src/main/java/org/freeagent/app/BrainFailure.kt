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
        waitText: String
    ): String {
        val cause = when (failure) {
            Class.OFFLINE ->
                "You're offline right now — this message is saved here, " +
                    "and Lille keeps retrying on her own as soon as a " +
                    "connection appears."
            Class.RATE_LIMITED ->
                "The free brain route is busy — its anonymous tier limits " +
                    "replies per internet address, and on mobile networks " +
                    "that address is shared with other people on your " +
                    "carrier, so the limit can be used up by strangers."
            Class.NETWORK ->
                "The free brain route didn't answer in time — the route " +
                    "or the path to it hiccuped. Your connection is fine; " +
                    "this one is on the route."
            Class.SERVER ->
                "The free brain route's server is having a bad moment " +
                    "(it answered with an error). Your message is safe " +
                    "and queued."
            Class.PERMANENT ->
                "The free brain route refused that request's shape."
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

    /** The routes the chat chain walks, in order. Only
     *  routes LIVE-VERIFIED as genuinely keyless are listed
     *  (ROUTES.md carries the dated verdicts). */
    enum class Route(val key: String, val label: String) {
        POLLINATIONS("pollinations", "the main free route"),
        HORDE("horde", "the slow community route")
    }

    /** The route to try after [route] failed, or null when
     *  the chain is exhausted for this attempt. Any failure
     *  falls through — another route's request shape and
     *  limits differ — but the attempt ends when the LAST
     *  route refuses permanently. */
    fun nextRoute(route: Route): Route? {
        val routes = Route.values()
        val idx = routes.indexOf(route)
        if (idx < 0 || idx >= routes.size - 1) return null
        return routes[idx + 1]
    }

    /** True when a failure on the LAST route ends the whole
     *  attempt terminally (permanent everywhere). Retryable
     *  last-route failures schedule the next attempt instead. */
    fun attemptEndsTerminally(lastRouteFailure: Class): Boolean =
        lastRouteFailure == Class.PERMANENT
}
