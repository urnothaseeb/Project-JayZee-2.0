package com.haseeb.jayzee

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class Parsed(
    val type: String,
    val title: String,
    val date: LocalDate?,
    val time: LocalTime?,
    val critical: Boolean,
)

/**
 * Turns one typed or dictated line into a reminder, task or note.
 * Rules only, no AI. To teach it something new, add a pattern here.
 *
 * Examples:
 *   "dentist thursday 3pm"        -> reminder, Thursday 15:00
 *   "submit report by friday"     -> task, due Friday
 *   "remind me to call mom in 2 hours"
 *   "note wifi password is on the fridge"
 */
object Parser {
    private val I = RegexOption.IGNORE_CASE

    private val weekdays = linkedMapOf(
        "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
        "saturday" to DayOfWeek.SATURDAY, "tuesday" to DayOfWeek.TUESDAY,
        "monday" to DayOfWeek.MONDAY, "friday" to DayOfWeek.FRIDAY, "sunday" to DayOfWeek.SUNDAY,
        "thurs" to DayOfWeek.THURSDAY, "tues" to DayOfWeek.TUESDAY, "thur" to DayOfWeek.THURSDAY,
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY,
    )

    private val reRemind = Regex("^\\s*(remind me to|remind me|reminder|remind)\\b:?", I)
    private val reTask = Regex("^\\s*(task|todo|to-do)\\b:?", I)
    private val reNote = Regex("^\\s*note\\b:?", I)
    private val reCritical = Regex("\\b(urgent|important|critical)\\b|!!+", I)
    private val reRelative = Regex(
        "\\bin\\s+(\\d+)\\s*(minutes|minute|mins|min|hours|hour|hrs|hr|h|days|day|weeks|week)\\b", I
    )
    private val reDayWord = Regex("(?:\\b(by|on|due)\\s+)?\\b(today|tonight|tomorrow|tmrw|tmr)\\b", I)
    private val reWeekday = Regex(
        "(?:\\b(by|on|due|next)\\s+)?\\b(" + weekdays.keys.joinToString("|") + ")\\b", I
    )
    private val reNumericDate = Regex("(?:\\b(by|on|due)\\s+)?\\b(\\d{1,2})/(\\d{1,2})\\b", I)
    private val reTimeAmPm = Regex("\\b(?:at\\s+)?(\\d{1,2})(?:[:.h](\\d{2}))?\\s*(am|pm)\\b", I)
    private val reTime24 = Regex("\\b(?:at\\s+)?(\\d{1,2})[:h](\\d{2})\\b", I)
    private val reTimeH = Regex("\\b(?:at\\s+)?(\\d{1,2})h\\b", I)
    private val reTimeAt = Regex("\\bat\\s+(\\d{1,2})\\b", I)
    private val reDue = Regex("\\bdue\\b", I)

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): Parsed {
        var rest = input.trim()
        var type: String? = null
        var date: LocalDate? = null
        var time: LocalTime? = null
        var dueHint = false
        var critical = false

        fun cut(m: MatchResult) {
            rest = rest.substring(0, m.range.first) + " " + rest.substring(m.range.last + 1)
        }

        reRemind.find(rest)?.let { type = TYPE_REMINDER; cut(it) }
        if (type == null) reTask.find(rest)?.let { type = TYPE_TASK; cut(it) }
        if (type == null) reNote.find(rest)?.let { type = TYPE_NOTE; cut(it) }

        if (type == TYPE_NOTE) {
            return Parsed(TYPE_NOTE, tidy(rest, input), null, null, false)
        }

        reCritical.find(rest)?.let { critical = true; cut(it) }

        reRelative.find(rest)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@let
            val unit = m.groupValues[2].lowercase()
            when {
                unit.startsWith("m") -> {
                    val t = now.plusMinutes(n).truncatedTo(ChronoUnit.MINUTES)
                    date = t.toLocalDate(); time = t.toLocalTime()
                }
                unit.startsWith("h") -> {
                    val t = now.plusHours(n).truncatedTo(ChronoUnit.MINUTES)
                    date = t.toLocalDate(); time = t.toLocalTime()
                }
                unit.startsWith("d") -> date = now.toLocalDate().plusDays(n)
                else -> date = now.toLocalDate().plusWeeks(n)
            }
            cut(m)
        }

        if (date == null) {
            reDayWord.find(rest)?.let { m ->
                if (m.groupValues[1].isNotEmpty()) dueHint = true
                when (m.groupValues[2].lowercase()) {
                    "today" -> date = now.toLocalDate()
                    "tonight" -> { date = now.toLocalDate(); time = LocalTime.of(20, 0) }
                    else -> date = now.toLocalDate().plusDays(1)
                }
                cut(m)
            }
        }

        if (date == null) {
            reWeekday.find(rest)?.let { m ->
                val hint = m.groupValues[1].lowercase()
                if (hint == "by" || hint == "due") dueHint = true
                val day = weekdays[m.groupValues[2].lowercase()] ?: return@let
                date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(day))
                cut(m)
            }
        }

        if (date == null) {
            reNumericDate.find(rest)?.let { m ->
                val d = m.groupValues[2].toIntOrNull() ?: return@let
                val mo = m.groupValues[3].toIntOrNull() ?: return@let
                val parsed = try {
                    LocalDate.of(now.year, mo, d)
                } catch (e: Exception) {
                    return@let
                }
                if (m.groupValues[1].isNotEmpty()) dueHint = true
                date = if (parsed.isBefore(now.toLocalDate())) parsed.plusYears(1) else parsed
                cut(m)
            }
        }

        // Clock time. An explicit time always wins over "tonight"'s default.
        val amPm = reTimeAmPm.find(rest)
        val t24 = if (amPm == null) reTime24.find(rest) else null
        val tH = if (amPm == null && t24 == null) reTimeH.find(rest) else null
        val tAt = if (amPm == null && t24 == null && tH == null) reTimeAt.find(rest) else null
        when {
            amPm != null -> {
                var h = amPm.groupValues[1].toInt()
                val min = amPm.groupValues[2].toIntOrNull() ?: 0
                val pm = amPm.groupValues[3].lowercase() == "pm"
                if (h in 1..12 && min in 0..59) {
                    if (pm && h < 12) h += 12
                    if (!pm && h == 12) h = 0
                    time = LocalTime.of(h, min); cut(amPm)
                }
            }
            t24 != null -> {
                val h = t24.groupValues[1].toInt()
                val min = t24.groupValues[2].toInt()
                if (h in 0..23 && min in 0..59) { time = LocalTime.of(h, min); cut(t24) }
            }
            tH != null -> {
                val h = tH.groupValues[1].toInt()
                if (h in 0..23) { time = LocalTime.of(h, 0); cut(tH) }
            }
            tAt != null -> {
                var h = tAt.groupValues[1].toInt()
                if (h in 0..23) {
                    if (h in 1..7) h += 12 // "at 3" almost always means 15:00
                    time = LocalTime.of(h, 0); cut(tAt)
                }
            }
        }

        reDue.find(rest)?.let { dueHint = true; cut(it) }

        val finalTime = time
        if (finalTime != null && date == null) {
            date = if (finalTime.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
        }

        val finalType = type ?: when {
            dueHint -> TYPE_TASK
            time != null -> TYPE_REMINDER
            else -> TYPE_TASK
        }
        return Parsed(finalType, tidy(rest, input), date, time, critical)
    }

    private fun tidy(rest: String, original: String): String {
        var t = rest.replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^(to|that|[:,-])\\s+", I), "")
        t = t.replace(Regex("\\s+(at|on|by|[:,-])$", I), "")
        t = t.trim()
        if (t.isEmpty()) t = original.trim()
        return t.replaceFirstChar { it.uppercase() }
    }
}
