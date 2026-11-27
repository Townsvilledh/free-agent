package org.freeagent.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * CALENDAR, THE PURE LAYER — the on-device counterpart of
 * the Python core's free_agent/mailtools.py Calendar
 * handlers.
 *
 * Why this exists: Lille must read the user's calendar
 * HERSELF to plan around it (mailtools.py's founding case:
 * a job that needs the schedule cannot be done by asking
 * the user to fetch it). This file is the deterministic,
 * testable half: the event shape, the gateway interface the
 * Android half implements, and the observation formatting.
 * It is pure Kotlin + java.util — no Android imports — so
 * its rules are pinned by JVM tests (DeviceCalendarTest).
 *
 * Security shape (the same law as the mail layer):
 *
 * - Calendar text is THIRD-PARTY content — event titles and
 *   locations are written by whoever sent the invite — so
 *   titles and locations pass [MailRedact] before they can
 *   reach the model, exactly as the core redacts summary
 *   and location in calendar_list / calendar_get
 *   (mailtools.py). An invite titled with a verification
 *   code does not smuggle the code past the redactor.
 * - The OAuth token never enters this layer: a
 *   [CalendarGateway] holds it behind the interface, and
 *   nothing here ever sees it.
 * - Not connected is an honest state, as with mail: the
 *   Android half reports it plainly (naming the one Settings
 *   step) rather than faking an empty calendar. An empty
 *   RESULT from a connected calendar is a different, true
 *   statement — [CalendarFormat.formatEvents] words it as
 *   "(nothing scheduled)".
 *
 * Times: all formatting and day boundaries here are UTC,
 * deliberately. A formatter that read the phone's timezone
 * would render the same event differently as the user
 * travels and could never be pinned by a deterministic
 * test; the Android half converts for display if it needs
 * local time, and this layer stays the auditable record.
 */

/** One calendar event. [title] and [location] are raw as
 *  read from the calendar — redaction happens at FORMAT
 *  time ([CalendarFormat]), so the gateway stays a faithful
 *  reader and the model-facing surface is the redacted one. */
data class CalEvent(
    val id: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val location: String
)

/**
 * The calendar itself, behind an interface so this layer
 * stays pure. Implementations hold the OAuth token and the
 * Android/network machinery. [create] is a WRITE: in the
 * core it sits behind the same triple-gate discipline as
 * mail sends (host flag, write scope, per-action approval —
 * mailtools.py's _write_gate); the Android implementation
 * must gate it the same way and never call it ungated.
 */
interface CalendarGateway {
    fun events(fromMs: Long, toMs: Long): List<CalEvent>
    fun create(
        title: String, startMs: Long, endMs: Long, location: String
    ): CalEvent
}

/** Pure formatting / range helpers over [CalEvent]s. */
object CalendarFormat {

    private const val DAY_MS = 86_400_000L

    /** A fresh formatter per call (SimpleDateFormat is not
     *  thread-safe): "yyyy-MM-dd HH:mm" in UTC, Locale.US —
     *  deterministic on every device, pinned by tests. */
    private fun formatter(): SimpleDateFormat {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt
    }

    /**
     * The events observation: a "CALENDAR_EVENTS
     * range=<label> n=<count>" header, then one line per
     * event — "  <yyyy-MM-dd HH:mm> — <title>", plus
     * " @ <location>" when the location is non-empty. The
     * events render in the order given (the gateway returns
     * them in start order; a formatter does not re-sort the
     * evidence). Title and location pass [MailRedact]
     * first — calendar text is third-party content.
     *
     * An empty list is its own honest line:
     * "CALENDAR_EVENTS range=<label> n=0 (nothing scheduled)".
     */
    /** One instant, formatted for the model-facing
     *  observations ("yyyy-MM-dd HH:mm" UTC) — the anchor
     *  the environment note gives her for computing event
     *  times, and the stamp on a creation's confirmation. */
    fun formatInstant(epochMs: Long): String =
        formatter().format(Date(epochMs))

    fun formatEvents(events: List<CalEvent>, rangeLabel: String): String {
        if (events.isEmpty()) {
            return "CALENDAR_EVENTS range=$rangeLabel n=0 " +
                "(nothing scheduled)"
        }
        val fmt = formatter()
        val lines = mutableListOf(
            "CALENDAR_EVENTS range=$rangeLabel n=${events.size}"
        )
        for (ev in events) {
            val line = StringBuilder()
            line.append("  ")
            line.append(fmt.format(Date(ev.startMs)))
            line.append(" — ")
            line.append(MailRedact.redact(ev.title))
            if (ev.location.isNotEmpty()) {
                line.append(" @ ")
                line.append(MailRedact.redact(ev.location))
            }
            lines.add(line.toString())
        }
        return lines.joinToString("\n")
    }

    /**
     * The UTC day containing [epochMs], as (midnight,
     * next midnight): start inclusive, end exclusive, so
     * adjacent days tile with no gap or overlap. Computed
     * by floor division, so an instant exactly at midnight
     * starts its own day and one a millisecond before
     * belongs to the previous one.
     */
    fun dayRange(epochMs: Long): Pair<Long, Long> {
        val start = Math.floorDiv(epochMs, DAY_MS) * DAY_MS
        return Pair(start, start + DAY_MS)
    }
}
