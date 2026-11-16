@file:OptIn(ExperimentalMaterial3Api::class)

package com.haseeb.jayzee

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dayClockFmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

private fun formatPosted(ms: Long): String {
    val t = ms.toLdt()
    return if (t.toLocalDate() == LocalDate.now()) t.format(clockFmt) else t.format(dayClockFmt)
}

/** The line a notification becomes when you turn it into a reminder or task. */
private fun asEntryText(n: Notif): String {
    val joined = if (n.title.isNotEmpty() && n.text.isNotEmpty()) n.title + ": " + n.text else n.title + n.text
    return joined.replace('\n', ' ').take(140)
}

@Composable
fun NotifCard(n: Notif, modifier: Modifier = Modifier, pills: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                n.app + " · " + formatPosted(n.postedAt),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )
            if (n.title.isNotEmpty()) {
                Text(
                    n.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (n.text.isNotEmpty()) {
                Text(
                    n.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState())
            ) { pills() }
        }
    }
}

@Composable
fun InboxTab(activity: Context, stamp: Int, actions: Actions, onReview: () -> Unit) {
    val ctx = activity.applicationContext
    val db = Db.get(ctx)
    val scheme = MaterialTheme.colorScheme
    val enabled = Triage.enabled(ctx)
    val fresh = remember(stamp) { db.notifsByStatus(N_NEW, 100) }
    val kept = remember(stamp) { db.notifsByStatus(N_KEPT, 100) }
    val mutedCount = remember(stamp) { db.countByStatus(N_MUTED) }
    val rules = remember(stamp) { db.rules() }
    var alwaysFor by remember { mutableStateOf<Notif?>(null) }
    var showRules by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val found = remember(stamp, query) { if (query.trim().length >= 2) db.searchNotifs(query, 50) else emptyList() }

    fun toEntry(n: Notif, type: String) {
        actions.openEditor(
            EditorRequest(
                initialText = asEntryText(n),
                initialType = type,
                onSaved = { if (n.status == N_NEW || n.status == N_KEPT) db.setNotifStatus(n.id, N_HANDLED) }
            )
        )
    }

    fun decide(n: Notif, keep: Boolean) {
        val learned = Triage.decide(ctx, n, keep)
        if (learned != null) Toast.makeText(ctx, learned, Toast.LENGTH_LONG).show()
        actions.changed()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ListPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "top") {
            Column {
                Text(
                    "${fresh.size} to decide · ${kept.size} to respond · $mutedCount muted",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant
                )
                Text("Inbox", style = MaterialTheme.typography.headlineMedium, color = scheme.onBackground)
            }
        }
        if (!enabled) {
            item(key = "access") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Banner("JayZee cannot read notifications yet. Turn on notification access.", "Open settings") {
                        try {
                            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Open Settings and search for \"notification access\".", Toast.LENGTH_LONG).show()
                        }
                    }
                    Text(
                        "If the switch is greyed out: open Settings, Apps, JayZee, tap the three dots at the top right, " +
                            "choose \"Allow restricted settings\", then come back and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
        if (mutedCount > 0) {
            item(key = "muted") {
                Banner("$mutedCount muted notifications you have not looked at.", "Go through them", onReview)
            }
        }
        if (enabled && fresh.isEmpty() && kept.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing waiting. New notifications appear here, and the app asks what to do with each kind until it has learned.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
        if (fresh.isNotEmpty()) {
            item(key = "h-new") { SectionHeader("Needs a decision", fresh.size, false) }
            items(fresh, key = { "n" + it.id }) { n ->
                NotifCard(n) {
                    Pill("Keep", scheme.primaryContainer, scheme.onPrimaryContainer) { decide(n, true) }
                    Pill("Mute", scheme.surfaceVariant, scheme.onSurface) { decide(n, false) }
                    Pill("Always…", scheme.surfaceVariant, scheme.onSurface) { alwaysFor = n }
                    Pill("Reminder", scheme.surfaceVariant, scheme.onSurface) { toEntry(n, TYPE_REMINDER) }
                    Pill("Task", scheme.surfaceVariant, scheme.onSurface) { toEntry(n, TYPE_TASK) }
                }
            }
        }
        if (kept.isNotEmpty()) {
            item(key = "h-kept") { SectionHeader("To respond", kept.size, false) }
            items(kept, key = { "k" + it.id }) { n ->
                NotifCard(n) {
                    Pill("Handled", scheme.primaryContainer, scheme.onPrimaryContainer) {
                        db.setNotifStatus(n.id, N_HANDLED)
                        actions.changed()
                    }
                    Pill("Reminder", scheme.surfaceVariant, scheme.onSurface) { toEntry(n, TYPE_REMINDER) }
                    Pill("Task", scheme.surfaceVariant, scheme.onSurface) { toEntry(n, TYPE_TASK) }
                    Pill("Always…", scheme.surfaceVariant, scheme.onSurface) { alwaysFor = n }
                }
            }
        }
        item(key = "log") {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "LOG AND RULES",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    )
                    Pill("${rules.size} rules", scheme.surfaceVariant, scheme.onSurface) { showRules = true }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search every notification, muted ones too") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        items(found, key = { "s" + it.id }) { n ->
            NotifCard(n) {
                Pill("Reminder", scheme.surfaceVariant, scheme.onSurface) { toEntry(n, TYPE_REMINDER) }
                Pill("Task", scheme.surfaceVariant, scheme.onSurface) { toEntry(n, TYPE_TASK) }
                Pill("Always…", scheme.surfaceVariant, scheme.onSurface) { alwaysFor = n }
            }
        }
    }

    val target = alwaysFor
    if (target != null) {
        AlwaysDialog(
            n = target,
            onPick = { rule ->
                Triage.addRule(ctx, rule)
                // The notification you were looking at follows the new rule too.
                if (target.status == N_NEW || target.status == N_KEPT) {
                    db.setNotifStatus(target.id, if (rule.action == RULE_MUTE) N_REVIEWED else N_KEPT)
                }
                alwaysFor = null
                actions.changed()
            },
            onDismiss = { alwaysFor = null }
        )
    }
    if (showRules) {
        RulesDialog(rules, onDelete = { db.deleteRule(it.id); actions.changed() }, onDismiss = { showRules = false })
    }
}

@Composable
fun AlwaysDialog(n: Notif, onPick: (Rule) -> Unit, onDismiss: () -> Unit) {
    var keyword by remember { mutableStateOf("") }
    fun rule(action: String, bySender: Boolean) = Rule(
        pkg = n.pkg, app = n.app, sender = if (bySender) n.title else "", keyword = keyword.trim(), action = action
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Always…") },
        text = {
            Column {
                Text(
                    "Applies to future notifications and to the ones waiting now.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("Only when it contains (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
                )
                TextButton(onClick = { onPick(rule(RULE_MUTE, false)) }) { Text("Mute everything from ${n.app}") }
                if (n.title.isNotEmpty()) {
                    TextButton(onClick = { onPick(rule(RULE_MUTE, true)) }) { Text("Mute \"${n.title}\" in ${n.app}") }
                }
                TextButton(onClick = { onPick(rule(RULE_KEEP, false)) }) { Text("Keep everything from ${n.app}") }
                if (n.title.isNotEmpty()) {
                    TextButton(onClick = { onPick(rule(RULE_KEEP, true)) }) { Text("Keep \"${n.title}\" in ${n.app}") }
                }
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun RulesDialog(rules: List<Rule>, onDelete: (Rule) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Rules") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (rules.isEmpty()) {
                    Text("No rules yet. They appear as you choose Keep, Mute or Always on notifications.")
                }
                rules.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Triage.describe(r), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onDelete(r) }) { Text("Delete") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/**
 * The evening pass over every muted notification not yet looked at, newest first.
 * Swipe left to dismiss, right to make a reminder, up to make a task.
 */
@Composable
fun ReviewScreen(activity: Context, stamp: Int, actions: Actions, onClose: () -> Unit) {
    val ctx = activity.applicationContext
    val db = Db.get(ctx)
    val scheme = MaterialTheme.colorScheme
    var local by remember { mutableIntStateOf(0) }
    val total = remember(stamp, local) { db.countByStatus(N_MUTED) }
    val current = remember(stamp, local) { db.notifsByStatus(N_MUTED, 1).firstOrNull() }

    fun seen(n: Notif) {
        db.setNotifStatus(n.id, N_REVIEWED)
        local = local + 1
    }

    fun toEntry(n: Notif, type: String) {
        actions.openEditor(EditorRequest(initialText = asEntryText(n), initialType = type, onSaved = { seen(n) }))
    }

    Scaffold(containerColor = scheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (total == 0) "All caught up" else "$total left, newest first",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant
                    )
                    Text("Muted notifications", style = MaterialTheme.typography.headlineMedium)
                }
                TextButton(onClick = onClose) { Text(if (total == 0) "Done" else "Stop") }
            }
            Spacer(Modifier.height(20.dp))
            if (current == null) {
                EmptyState(true)
            } else {
                key(current.id) {
                    SwipeCard(
                        n = current,
                        onDismiss = { seen(current) },
                        onReminder = { toEntry(current, TYPE_REMINDER) },
                        onTask = { toEntry(current, TYPE_TASK) }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Swipe left to dismiss, right for a reminder, up for a task.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { seen(current) }) { Text("Dismiss") }
                    OutlinedButton(onClick = { toEntry(current, TYPE_REMINDER) }) { Text("Reminder") }
                    OutlinedButton(onClick = { toEntry(current, TYPE_TASK) }) { Text("Task") }
                }
            }
        }
    }
}

@Composable
fun SwipeCard(n: Notif, onDismiss: () -> Unit, onReminder: () -> Unit, onTask: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val hint = when {
        dx < -threshold -> "Release to dismiss"
        dx > threshold -> "Release to make a reminder"
        dy < -threshold -> "Release to make a task"
        else -> ""
    }
    Column {
        Text(
            hint,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            modifier = Modifier.height(24.dp)
        )
        Box(
            Modifier
                .offset { IntOffset(dx.roundToInt(), dy.coerceAtMost(0f).roundToInt()) }
                .pointerInput(n.id) {
                    detectDragGestures(
                        onDragEnd = {
                            val x = dx
                            val y = dy
                            dx = 0f
                            dy = 0f
                            when {
                                x < -threshold -> onDismiss()
                                x > threshold -> onReminder()
                                y < -threshold -> onTask()
                            }
                        },
                        onDragCancel = {
                            dx = 0f
                            dy = 0f
                        }
                    ) { change, drag ->
                        change.consume()
                        dx += drag.x
                        dy += drag.y
                    }
                }
        ) {
            NotifCard(n) { }
        }
    }
}
