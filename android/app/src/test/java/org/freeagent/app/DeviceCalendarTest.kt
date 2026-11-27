package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar pure layer: UTC formatting in the order
 * given, title/location redaction (calendar text is
 * third-party content), the honest empty line, and
 * dayRange boundaries at exact midnights.
 *
 * All instants are pinned UTC milliseconds:
 * 2026-11-27T00:00:00Z = 1795737600000,
 * 2026-11-27T09:30:00Z = 1795771800000,
 * 2026-11-27T18:00:00Z = 1795802400000,
 * 2026-11-28T00:00:00Z = 1795824000000.
 */
class DeviceCalendarTest {

    private val nov27Midnight = 1_795_737_600_000L
    private val nov27Morning = 1_795_771_800_000L // 09:30 UTC
    private val nov27Evening = 1_795_802_400_000L // 18:00 UTC
    private val nov28Midnight = 1_795_824_000_000L
    private val dayMs = 86_400_000L

    // ---------- formatEvents ----------

    @Test
    fun formatEventsRendersHeaderAndUtcLines() {
        val events = listOf(
            CalEvent(
                id = "ev-1", title = "Stellar workshop",
                startMs = nov27Evening, endMs = nov27Evening + 3_600_000L,
                location = "Lagos"
            ),
            CalEvent(
                id = "ev-2", title = "Dentist",
                startMs = nov27Morning, endMs = nov27Morning + 1_800_000L,
                location = ""
            )
        )
        val out = CalendarFormat.formatEvents(events, "today")
        val lines = out.split("\n")
        assertEquals("CALENDAR_EVENTS range=today n=2", lines[0])
        assertEquals("  2026-11-27 18:00 — Stellar workshop @ Lagos", lines[1])
        // No location → no trailing " @".
        assertEquals("  2026-11-27 09:30 — Dentist", lines[2])
    }

    @Test
    fun formatEventsKeepsTheGivenOrder() {
        // The evening event is listed before the morning
        // one: the formatter renders the gateway's order,
        // it does not re-sort the evidence.
        val events = listOf(
            CalEvent("b", "Evening", nov27Evening, nov27Evening, ""),
            CalEvent("a", "Morning", nov27Morning, nov27Morning, "")
        )
        val lines = CalendarFormat.formatEvents(events, "today").split("\n")
        assertTrue(lines[1].contains("Evening"))
        assertTrue(lines[2].contains("Morning"))
    }

    @Test
    fun formatEventsRedactsTitleAndLocation() {
        // An invite titled with a code, at a location that
        // is an email address: neither reaches the output.
        val events = listOf(
            CalEvent(
                id = "ev-3", title = "Sync — your code is 482913",
                startMs = nov27Morning, endMs = nov27Morning,
                location = "host@example.com"
            )
        )
        val out = CalendarFormat.formatEvents(events, "today")
        assertFalse(out.contains("482913"))
        assertTrue(out.contains("[REDACTED-CODE]"))
        assertFalse(out.contains("host@example.com"))
        assertTrue(out.contains("[REDACTED-EMAIL]"))
    }

    @Test
    fun formatEventsEmptyIsTheHonestLine() {
        assertEquals(
            "CALENDAR_EVENTS range=next 7 days n=0 (nothing scheduled)",
            CalendarFormat.formatEvents(emptyList(), "next 7 days")
        )
    }

    // ---------- dayRange ----------

    @Test
    fun dayRangeCoversTheContainingUtcDay() {
        val (start, end) = CalendarFormat.dayRange(nov27Morning)
        assertEquals(nov27Midnight, start)
        assertEquals(nov28Midnight, end)
        assertEquals(dayMs, end - start)
    }

    @Test
    fun dayRangeMidnightStartsItsOwnDay() {
        val (start, end) = CalendarFormat.dayRange(nov27Midnight)
        assertEquals(nov27Midnight, start)
        assertEquals(nov28Midnight, end)
        // …and the next midnight starts the next day.
        val (start2, end2) = CalendarFormat.dayRange(nov28Midnight)
        assertEquals(nov28Midnight, start2)
        assertEquals(nov28Midnight + dayMs, end2)
    }

    @Test
    fun dayRangeLastMillisecondBelongsToTheDay() {
        val (start, end) = CalendarFormat.dayRange(nov28Midnight - 1)
        assertEquals(nov27Midnight, start)
        assertEquals(nov28Midnight, end)
    }

    @Test
    fun dayRangeAdjacentDaysTile() {
        val (_, endToday) = CalendarFormat.dayRange(nov27Morning)
        val (startTomorrow, _) = CalendarFormat.dayRange(nov27Morning + dayMs)
        assertEquals(endToday, startTomorrow)
    }
}
