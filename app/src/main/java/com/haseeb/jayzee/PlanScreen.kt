@file:OptIn(ExperimentalMaterial3Api::class)

package com.haseeb.jayzee

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val weekTitleFmt = DateTimeFormatter.ofPattern("d MMM")
private val dayHeaderFmt = DateTimeFormatter.ofPattern("EEEE d MMM")
private val dayPillFmt = DateTimeFormatter.ofPattern("EEE")

/**
 * The weekly plan. Tasks are placed on days with the day pills; reminders sit on their own day.
 * On a Sunday this shows the week ahead.
 */
@Composable
fun WeekTab(activity: Context, items: List<Item>, actions: Actions) {
    val ctx = activity.applicationContext
    val scheme = MaterialTheme.colorScheme
    val today = LocalDate.now()
    val start = Planner.weekStart(today)
    val days = (0L..6L).map { start.plusDays(it) }
    val weekStartMs = start.atStartOfDay().toMillis()
    val hasPlan = Planner.hasPlan(ctx)
    val open = items.filter { !it.done && it.type != TYPE_NOTE }
    // Never placed, or placed on a day that has already gone by.
    val unplaced = open.filter { it.type == TYPE_TASK && it.plannedDay < weekStartMs }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ListPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "top") {
            Column {
                Text(
                    if (start.isAfter(today)) "Next week" else "This week",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant
                )
                Text(
                    "Week of " + start.format(weekTitleFmt),
                    style = MaterialTheme.typography.headlineMedium,
                    color = scheme.onBackground
                )
            }
        }
        item(key = "status") {
            if (!hasPlan) {
                Banner("Place each task on a day, then confirm the plan.", "Confirm plan") {
                    Planner.confirmPlan(ctx)
                    actions.changed()
                }
            } else {
                Text(
                    "Plan confirmed. You can still move things.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        if (unplaced.isNotEmpty()) {
            item(key = "h-unplaced") { SectionHeader("Not placed", unplaced.size, !hasPlan) }
            items(unplaced, key = { "u" + it.id }) { item -> PlanRow(item, days, actions) }
        }
        days.forEach { day ->
            val s = day.atStartOfDay().toMillis()
            val e = day.plusDays(1).atStartOfDay().toMillis() - 1
            val list = open.filter {
                (it.type == TYPE_REMINDER && it.dueAt in s..e) || (it.type == TYPE_TASK && it.plannedDay in s..e)
            }
            item(key = "h-$day") {
                SectionHeader(day.format(dayHeaderFmt) + if (day == today) " · today" else "", list.size, false)
            }
            items(list, key = { "d" + it.id }) { item -> PlanRow(item, days, actions) }
        }
    }
}

@Composable
fun PlanRow(item: Item, days: List<LocalDate>, actions: Actions) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = { actions.edit(item) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            val meta = StringBuilder(typeLabel(item.type))
            if (item.type == TYPE_TASK) {
                meta.append(if (item.dueAt > 0) " · due " + formatWhen(item) else " · no due date")
                if (item.startBy > 0) meta.append(" · start ").append(formatDay(item.startBy))
            } else {
                meta.append(" · ").append(formatWhen(item))
            }
            Text(
                meta.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (item.type == TYPE_TASK) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState())
                ) {
                    days.forEach { day ->
                        val ms = day.atStartOfDay().toMillis()
                        val selected = item.plannedDay == ms
                        Pill(
                            day.format(dayPillFmt),
                            if (selected) scheme.primary else scheme.surfaceVariant,
                            if (selected) scheme.onPrimary else scheme.onSurface
                        ) { actions.plan(item, if (selected) 0L else ms) }
                    }
                    // "Later" parks the task past this week; it comes back as "Not placed" next week.
                    Pill("Later", scheme.surfaceVariant, scheme.onSurfaceVariant) {
                        actions.plan(item, days.last().plusDays(1).atStartOfDay().toMillis())
                    }
                }
            }
        }
    }
}

/**
 * The nightly review: clear or move what is still open, look at tomorrow, confirm.
 * After confirming, it offers the muted-notification pass.
 */
@Composable
fun NightlyScreen(activity: Context, items: List<Item>, actions: Actions, onFinished: (Boolean) -> Unit) {
    val ctx = activity.applicationContext
    val scheme = MaterialTheme.colorScheme
    val todayOpen = Planner.today(items).flatMap { it.items }
    val todayIds = todayOpen.map { it.id }.toSet()
    val tomorrowStart = LocalDate.now().plusDays(1).atStartOfDay().toMillis()
    val tomorrow = Planner.tomorrow(items).filter { it.id !in todayIds }
    val muted = remember(items) { Db.get(ctx).countByStatus(N_MUTED) }
    var ask by remember { mutableStateOf(false) }

    Scaffold(containerColor = scheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "top") {
                Column {
                    Text("Nightly review", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Text("Tomorrow", style = MaterialTheme.typography.headlineMedium, color = scheme.onBackground)
                    Text(
                        "Finish or move what is still open, check tomorrow, then confirm.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            if (todayOpen.isNotEmpty()) {
                item(key = "h-today") { SectionHeader("Still open today", todayOpen.size, true) }
                items(todayOpen, key = { "t" + it.id }) { item ->
                    if (item.type == TYPE_TASK && item.plannedDay != tomorrowStart) {
                        ItemCard(item, actions, "Do tomorrow") { actions.plan(item, tomorrowStart) }
                    } else {
                        ItemCard(item, actions)
                    }
                }
            }
            item(key = "h-tomorrow") { SectionHeader("Tomorrow", tomorrow.size, false) }
            if (tomorrow.isEmpty()) {
                item(key = "none") {
                    Text(
                        "Nothing is placed on tomorrow yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
            items(tomorrow, key = { "n" + it.id }) { item ->
                if (item.type == TYPE_TASK && item.plannedDay == tomorrowStart) {
                    ItemCard(item, actions, "Not tomorrow") { actions.plan(item, 0L) }
                } else {
                    ItemCard(item, actions)
                }
            }
            item(key = "confirm") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        Planner.confirmReview(ctx)
                        if (muted > 0) ask = true else onFinished(false)
                    }) { Text("Confirm tomorrow") }
                    OutlinedButton(onClick = { onFinished(false) }) { Text("Not now") }
                }
            }
        }
    }

    if (ask) {
        AlertDialog(
            onDismissRequest = { onFinished(false) },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Muted notifications") },
            text = { Text("You have $muted muted notifications you have not looked at. Got time to go through them now?") },
            confirmButton = { TextButton(onClick = { onFinished(true) }) { Text("Go through them") } },
            dismissButton = { TextButton(onClick = { onFinished(false) }) { Text("Not tonight") } }
        )
    }
}
