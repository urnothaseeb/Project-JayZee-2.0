package com.haseeb.jayzee

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A task rescheduled this many times is flagged as slipping. */
const val SLIPPING_AFTER = 3

fun Long.toLdt(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())
fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

fun formatWhen(item: Item): String {
    if (item.dueAt == 0L) return "No date"
    val t = item.dueAt.toLdt()
    val today = LocalDate.now()
    val day = when (t.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> t.format(dayFmt)
    }
    return if (item.hasTime) "$day ${t.format(timeFmt)}" else day
}

fun formatDay(millis: Long): String {
    val d = millis.toLdt().toLocalDate()
    val today = LocalDate.now()
    return when {
        d == today -> "today"
        d == today.plusDays(1) -> "tomorrow"
        d.isBefore(today) -> "now"
        else -> d.format(dayFmt)
    }
}

object Planner {
    /** Free working minutes on a day: evenings on weekdays (after 17:00), more at the weekend. */
    private fun capacity(day: LocalDate): Int =
        if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) 360 else 180

    /**
     * The day to begin a task so it is finished the day before it is due.
     * Example: due Friday, 5 hours of work -> Thursday holds 3h, Wednesday the other 2h,
     * so start by Wednesday.
     */
    fun startBy(item: Item): Long {
        if (item.type != TYPE_TASK || item.dueAt == 0L) return 0
        var remaining = item.effortMin.coerceAtLeast(1)
        var day = item.dueAt.toLdt().toLocalDate().minusDays(1)
        var guard = 0
        while (guard < 365) {
            remaining -= capacity(day)
            if (remaining <= 0) break
            day = day.minusDays(1)
            guard++
        }
        return day.atStartOfDay().toMillis()
    }

    data class Section(val title: String, val items: List<Item>)

    /** What the Today screen and the morning brief show, in order. Each item appears once. */
    fun today(all: List<Item>, now: LocalDateTime = LocalDateTime.now()): List<Section> {
        val nowMs = now.toMillis()
        val endOfToday = now.toLocalDate().plusDays(1).atStartOfDay().toMillis() - 1
        val open = all.filter { !it.done && it.type != TYPE_NOTE }
        val shown = HashSet<Long>()
        fun take(title: String, pick: (Item) -> Boolean): Section {
            val list = open.filter { it.id !in shown && pick(it) }
            list.forEach { shown.add(it.id) }
            return Section(title, list)
        }
        return listOf(
            take("Overdue") { it.dueAt in 1 until nowMs },
            take("Slipping") { it.rescheduleCount >= SLIPPING_AFTER },
            take("Reminders today") { it.type == TYPE_REMINDER && it.dueAt in nowMs..endOfToday },
            take("Start today") { it.type == TYPE_TASK && it.startBy in 1..endOfToday },
            take("Due today") { it.type == TYPE_TASK && it.dueAt in nowMs..endOfToday },
        ).filter { it.items.isNotEmpty() }
    }
}
