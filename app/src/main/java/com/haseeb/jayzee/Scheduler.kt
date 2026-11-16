package com.haseeb.jayzee

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.LocalDateTime
import java.time.LocalTime

const val ACTION_FIRE = "com.haseeb.jayzee.FIRE"
const val ACTION_DONE = "com.haseeb.jayzee.DONE"
const val ACTION_SNOOZE = "com.haseeb.jayzee.SNOOZE"
const val ACTION_DISMISSED = "com.haseeb.jayzee.DISMISSED"
const val ACTION_BRIEF = "com.haseeb.jayzee.BRIEF"
const val EXTRA_ID = "id"
const val ACTION_WEEKLY = "com.haseeb.jayzee.WEEKLY"
const val ACTION_NIGHTLY = "com.haseeb.jayzee.NIGHTLY"
const val EXTRA_OPEN = "open"
const val OPEN_CAPTURE = "capture"
const val OPEN_WEEK = "week"
const val OPEN_NIGHTLY = "nightly"

private const val CH_REMINDERS = "reminders"
private const val CH_CRITICAL = "critical"
private const val CH_BRIEF = "brief"
private const val BRIEF_NOTIFICATION_ID = 2_000_000_000

/** Wake-up time for the morning brief. */
val BRIEF_TIME: LocalTime = LocalTime.of(7, 0)

/** When the app asks for a weekly plan if the week has none. */
val WEEKLY_PROMPT_TIME: LocalTime = LocalTime.of(18, 0)

/** When the nightly review is offered if it has not been done yet. */
val NIGHTLY_PROMPT_TIME: LocalTime = LocalTime.of(21, 30)

/** A swiped-away reminder comes back after this long. */
private const val RETURN_AFTER_MS = 10 * 60 * 1000L
private const val SNOOZE_MS = 60 * 60 * 1000L

object Scheduler {
    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        val critical = NotificationChannel(CH_CRITICAL, "Critical reminders", NotificationManager.IMPORTANCE_HIGH)
        critical.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        critical.enableVibration(true)
        nm.createNotificationChannel(critical)
        nm.createNotificationChannel(
            NotificationChannel(CH_BRIEF, "Brief and planning prompts", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun broadcast(ctx: Context, action: String, id: Long, slot: Int): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id)
        val code = ((id % 100_000_000L).toInt()) * 8 + slot
        return PendingIntent.getBroadcast(
            ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Sets (or clears) the alarm for one item. Only open reminders ring. */
    fun schedule(ctx: Context, item: Item) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = broadcast(ctx, ACTION_FIRE, item.id, 0)
        am.cancel(pi)
        if (item.type == TYPE_REMINDER && !item.done && item.dueAt > 0) {
            setAlarm(ctx, maxOf(item.dueAt, System.currentTimeMillis() + 1000), pi)
        }
    }

    fun cancel(ctx: Context, item: Item) {
        ctx.getSystemService(AlarmManager::class.java).cancel(broadcast(ctx, ACTION_FIRE, item.id, 0))
        ctx.getSystemService(NotificationManager::class.java).cancel(notificationId(item.id))
    }

    fun comeBackLater(ctx: Context, id: Long) {
        setAlarm(ctx, System.currentTimeMillis() + RETURN_AFTER_MS, broadcast(ctx, ACTION_FIRE, id, 0))
    }

    /**
     * Re-arms every alarm. After a restart, overdue reminders ring again; when the app is
     * merely opened they are left alone so opening it never sets off a burst of alerts.
     */
    fun rescheduleAll(ctx: Context, includeOverdue: Boolean) {
        val now = System.currentTimeMillis()
        Db.get(ctx).all().forEach { if (includeOverdue || it.dueAt > now) schedule(ctx, it) }
        scheduleDailyBrief(ctx)
    }

    private fun scheduleDaily(ctx: Context, action: String, at: LocalTime, slot: Int) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(at)
        if (!next.isAfter(now)) next = next.plusDays(1)
        setAlarm(ctx, next.toMillis(), broadcast(ctx, action, 0, slot))
    }

    fun scheduleDailyBrief(ctx: Context) {
        scheduleDaily(ctx, ACTION_BRIEF, BRIEF_TIME, 5)
        scheduleDaily(ctx, ACTION_WEEKLY, WEEKLY_PROMPT_TIME, 6)
        scheduleDaily(ctx, ACTION_NIGHTLY, NIGHTLY_PROMPT_TIME, 7)
    }

    private fun prompt(ctx: Context, notificationId: Int, title: String, text: String, open: String) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_BRIEF)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, open))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(notificationId, n)
    }

    /** Asks for a weekly plan when the week has none (Sunday: for the week ahead). */
    fun showWeeklyPrompt(ctx: Context) {
        if (Planner.hasPlan(ctx)) return
        val sunday = java.time.LocalDate.now().dayOfWeek == java.time.DayOfWeek.SUNDAY
        prompt(
            ctx, BRIEF_NOTIFICATION_ID + 1,
            if (sunday) "Plan next week" else "This week has no plan yet",
            "Place your tasks on days. It takes a couple of minutes.",
            OPEN_WEEK
        )
    }

    /** Offers the nightly review, then the muted-notification backlog. */
    fun showNightlyPrompt(ctx: Context) {
        if (Planner.reviewedTonight(ctx)) return
        val muted = Db.get(ctx).countByStatus(N_MUTED)
        val tail = if (muted > 0) " Then $muted muted notifications to look through." else ""
        prompt(ctx, BRIEF_NOTIFICATION_ID + 2, "Review tomorrow", "Check tomorrow's plan before bed.$tail", OPEN_NIGHTLY)
    }

    private fun notificationId(id: Long): Int = (id % 1_000_000_000L).toInt()

    private fun openApp(ctx: Context, open: String = ""): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (open.isNotEmpty()) intent.putExtra(EXTRA_OPEN, open)
        val code = when (open) {
            OPEN_WEEK -> 11
            OPEN_NIGHTLY -> 12
            else -> 1
        }
        return PendingIntent.getActivity(
            ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showReminder(ctx: Context, item: Item) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, if (item.critical) CH_CRITICAL else CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(item.title)
            .setContentText("Mark it done or give it a new time. Swiping brings it back.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (item.critical) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openApp(ctx))
            .setDeleteIntent(broadcast(ctx, ACTION_DISMISSED, item.id, 3))
            .addAction(0, "Done", broadcast(ctx, ACTION_DONE, item.id, 1))
            .addAction(0, "In 1 hour", broadcast(ctx, ACTION_SNOOZE, item.id, 2))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(notificationId(item.id), n)
    }

    fun showBrief(ctx: Context) {
        ensureChannels(ctx)
        val sections = Planner.today(Db.get(ctx).all())
        val text = if (sections.isEmpty()) {
            "Nothing scheduled for today."
        } else {
            sections.joinToString(" · ") { "${it.title}: ${it.items.size}" }
        }
        val lines = sections.flatMap { s -> s.items.take(4).map { "${s.title}: ${it.title}" } }.take(8)
        val n = NotificationCompat.Builder(ctx, CH_BRIEF)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Today")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText((listOf(text) + lines).joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(BRIEF_NOTIFICATION_ID, n)
    }

    fun snoozeMillis(): Long = SNOOZE_MS
}

/** All changes to items go through here so the alarm and the calendar stay in step. */
object Repo {
    fun save(ctx: Context, item: Item): Item {
        val db = Db.get(ctx)
        var cur = item.copy(startBy = Planner.startBy(item))
        if (cur.id == 0L) {
            cur = cur.copy(id = db.insert(cur))
        } else {
            db.update(cur)
        }
        if (cur.type == TYPE_REMINDER && !cur.done) {
            val eventId = CalendarSync.upsert(ctx, cur)
            if (eventId != cur.calendarEventId) {
                cur = cur.copy(calendarEventId = eventId)
                db.update(cur)
            }
        }
        Scheduler.cancel(ctx, cur)
        Scheduler.schedule(ctx, cur)
        return cur
    }

    fun markDone(ctx: Context, item: Item) {
        val cur = item.copy(done = true, doneAt = System.currentTimeMillis())
        Db.get(ctx).update(cur)
        Scheduler.cancel(ctx, cur)
    }

    fun reopen(ctx: Context, item: Item) {
        save(ctx, item.copy(done = false, doneAt = 0))
    }

    /** Moving an item to a new time counts towards "slipping". */
    fun reschedule(ctx: Context, item: Item, newDueAt: Long, hasTime: Boolean) {
        save(ctx, item.copy(dueAt = newDueAt, hasTime = hasTime, rescheduleCount = item.rescheduleCount + 1))
    }

    fun delete(ctx: Context, item: Item) {
        Scheduler.cancel(ctx, item)
        CalendarSync.delete(ctx, item)
        Db.get(ctx).delete(item.id)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val id = intent.getLongExtra(EXTRA_ID, 0)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Scheduler.rescheduleAll(ctx, includeOverdue = true)
            ACTION_BRIEF -> {
                Scheduler.showBrief(ctx)
                Scheduler.scheduleDailyBrief(ctx)
            }
            ACTION_WEEKLY -> {
                Scheduler.showWeeklyPrompt(ctx)
                Scheduler.scheduleDailyBrief(ctx)
            }
            ACTION_NIGHTLY -> {
                Scheduler.showNightlyPrompt(ctx)
                Scheduler.scheduleDailyBrief(ctx)
            }
            ACTION_FIRE -> {
                val item = Db.get(ctx).get(id) ?: return
                if (!item.done) Scheduler.showReminder(ctx, item)
            }
            ACTION_DONE -> {
                val item = Db.get(ctx).get(id) ?: return
                Repo.markDone(ctx, item)
            }
            ACTION_SNOOZE -> {
                val item = Db.get(ctx).get(id) ?: return
                Repo.reschedule(ctx, item, System.currentTimeMillis() + Scheduler.snoozeMillis(), true)
            }
            ACTION_DISMISSED -> {
                val item = Db.get(ctx).get(id) ?: return
                if (!item.done) Scheduler.comeBackLater(ctx, id)
            }
        }
    }
}
