package org.freeagent.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.util.TimeZone

/**
 * The device calendar gateway (v1.0.4): CalendarContract
 * behind the pure [CalendarGateway] interface. Reads go
 * through Instances (so recurring events expand); creates
 * land in the device's first writable calendar. The
 * runtime permissions (READ_CALENDAR / WRITE_CALENDAR)
 * are the user's grant — a SecurityException from the
 * provider is turned by the runner into the honest
 * observation naming the Android permission step, never
 * into a fake empty calendar.
 */
class CalendarContractGateway(context: Context) : CalendarGateway {

    private val appContext = context.applicationContext

    override fun events(fromMs: Long, toMs: Long): List<CalEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uri, fromMs)
        ContentUris.appendId(uri, toMs)
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION
        )
        val out = mutableListOf<CalEvent>()
        appContext.contentResolver.query(
            uri.build(), projection, null, null,
            CalendarContract.Instances.BEGIN + " ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    CalEvent(
                        id = cursor.getLong(0).toString(),
                        title = cursor.getString(1) ?: "",
                        startMs = cursor.getLong(2),
                        endMs = cursor.getLong(3),
                        location = cursor.getString(4) ?: ""
                    )
                )
            }
        }
        return out
    }

    override fun create(
        title: String, startMs: Long, endMs: Long, location: String
    ): CalEvent {
        val calendarId = firstWritableCalendarId()
            ?: throw IllegalStateException(
                "no writable calendar on this device"
            )
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(
                CalendarContract.Events.EVENT_TIMEZONE,
                TimeZone.getDefault().id
            )
        }
        val uri = appContext.contentResolver.insert(
            CalendarContract.Events.CONTENT_URI, values
        ) ?: throw IllegalStateException("the calendar refused the event")
        val id = ContentUris.parseId(uri).toString()
        return CalEvent(id, title, startMs, endMs, location)
    }

    private fun firstWritableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        appContext.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection,
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getInt(1) >=
                    CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                ) {
                    return cursor.getLong(0)
                }
            }
        }
        return null
    }
}

/**
 * "Your computer" as a brain route (v1.0.4, steal #13 —
 * the QVAC delegated-inference pattern, LAN-first): the
 * user points Lille at an OpenAI-compatible server on
 * their OWN machine (LM Studio, Ollama, llama.cpp, a QVAC
 * server — anything that speaks the same shape). The
 * address + model live in the vault (OwnKeyRoutes'
 * LAN_* keys); this object holds only the on/off fact.
 * The route is a user-owned route: it walks FIRST among
 * the own routes when enabled and reachable, and falls
 * back seamlessly when it is not (seamless law — no route
 * talk ever reaches a user surface).
 */
object LanRoute {

    private const val PREFS = "lille_lan"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
