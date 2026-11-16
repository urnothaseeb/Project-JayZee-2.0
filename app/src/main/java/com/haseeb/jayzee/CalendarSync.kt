package com.haseeb.jayzee

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/** Writes timed reminders into the phone's own calendar. Fails quietly if permission is missing. */
object CalendarSync {
    private fun allowed(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun calendarId(ctx: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        var first: Long? = null
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (first == null) first = id
                if (c.getInt(1) == 1) return id
            }
        }
        return first
    }

    /** Creates or updates the event for a reminder and returns its event id (0 if none). */
    fun upsert(ctx: Context, item: Item): Long {
        if (!allowed(ctx) || item.dueAt == 0L) return item.calendarEventId
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, item.title)
                put(CalendarContract.Events.DTSTART, item.dueAt)
                put(CalendarContract.Events.DTEND, item.dueAt + 30 * 60 * 1000)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            if (item.calendarEventId > 0) {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, item.calendarEventId)
                if (ctx.contentResolver.update(uri, values, null, null) > 0) return item.calendarEventId
            }
            val calId = calendarId(ctx) ?: return 0
            values.put(CalendarContract.Events.CALENDAR_ID, calId)
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri?.lastPathSegment?.toLongOrNull() ?: 0
        } catch (e: Exception) {
            item.calendarEventId
        }
    }

    fun delete(ctx: Context, item: Item) {
        if (!allowed(ctx) || item.calendarEventId <= 0) return
        try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, item.calendarEventId)
            ctx.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            // The event may already be gone; nothing to do.
        }
    }
}
