@file:OptIn(ExperimentalMaterial3Api::class)

package com.haseeb.jayzee

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)
    private val openCapture = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensureChannels(this)
        Scheduler.rescheduleAll(this)
        if (intent?.getBooleanExtra(EXTRA_CAPTURE, false) == true) openCapture.value = true
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                App(this, tick.intValue, openCapture)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CAPTURE, false)) openCapture.value = true
    }

    override fun onResume() {
        super.onResume()
        // Items may have changed from a notification button while the app was in the background.
        tick.intValue = tick.intValue + 1
    }
}

private val dayButtonFmt = DateTimeFormatter.ofPattern("EEE d MMM")
private val timeButtonFmt = DateTimeFormatter.ofPattern("HH:mm")

private fun pickDate(activity: Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(
        activity,
        { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
        initial.year, initial.monthValue - 1, initial.dayOfMonth
    ).show()
}

private fun pickTime(activity: Context, initial: LocalTime, onPicked: (LocalTime) -> Unit) {
    TimePickerDialog(
        activity,
        { _, h, min -> onPicked(LocalTime.of(h, min)) },
        initial.hour, initial.minute, true
    ).show()
}

private fun pickNewTime(activity: Context, item: Item, onPicked: (Long, Boolean) -> Unit) {
    val base = if (item.dueAt > 0) item.dueAt.toLdt() else LocalDateTime.now()
    val today = LocalDate.now()
    val startDay = if (base.toLocalDate().isBefore(today)) today else base.toLocalDate()
    pickDate(activity, startDay) { d ->
        if (item.type == TYPE_REMINDER || item.hasTime) {
            pickTime(activity, base.toLocalTime()) { t -> onPicked(LocalDateTime.of(d, t).toMillis(), true) }
        } else {
            onPicked(LocalDateTime.of(d, LocalTime.of(23, 59)).toMillis(), false)
        }
    }
}

@Composable
fun App(activity: Context, tick: Int, openCapture: MutableState<Boolean>) {
    val appContext = activity.applicationContext
    var version by remember { mutableIntStateOf(0) }
    val items = remember(tick, version) { Db.get(appContext).all() }
    val changed: () -> Unit = { version = version + 1 }
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Item?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        wanted.add(Manifest.permission.READ_CALENDAR)
        wanted.add(Manifest.permission.WRITE_CALENDAR)
        permissionLauncher.launch(wanted.toTypedArray())
    }

    val onDone: (Item) -> Unit = { Repo.markDone(appContext, it); changed() }
    val onReopen: (Item) -> Unit = { Repo.reopen(appContext, it); changed() }
    val onMove: (Item) -> Unit = { item ->
        pickNewTime(activity, item) { due, hasTime ->
            Repo.reschedule(appContext, item, due, hasTime)
            changed()
        }
    }
    val onFirstStep: (Item, String) -> Unit = { item, step ->
        Repo.save(appContext, item.copy(firstStep = step.trim()))
        changed()
    }
    val onEdit: (Item) -> Unit = { editing = it }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (tab == 0) "Today" else "Everything") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Today") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Menu, contentDescription = null) },
                    label = { Text("Everything") }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { openCapture.value = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val sections = if (tab == 0) {
                Planner.today(items)
            } else {
                val open = items.filter { !it.done }
                listOf(
                    Planner.Section("Reminders", open.filter { it.type == TYPE_REMINDER }),
                    Planner.Section("Tasks", open.filter { it.type == TYPE_TASK }),
                    Planner.Section("Notes", open.filter { it.type == TYPE_NOTE }),
                    Planner.Section("Done", items.filter { it.done }.sortedByDescending { it.doneAt }.take(20)),
                ).filter { it.items.isNotEmpty() }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (sections.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (tab == 0) "Nothing for today. Tap + to add something."
                            else "Nothing here yet. Tap + to add something.",
                            modifier = Modifier.padding(top = 24.dp)
                        )
                    }
                }
                sections.forEach { section ->
                    item(key = "header-" + section.title) {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(section.items, key = { it.id }) { item ->
                        ItemCard(item, onDone, onReopen, onMove, onEdit, onFirstStep)
                    }
                }
            }
        }
    }

    if (openCapture.value) {
        EditorDialog(
            activity = activity,
            existing = null,
            onDismiss = { openCapture.value = false },
            onSave = { Repo.save(appContext, it); openCapture.value = false; changed() },
            onDelete = { }
        )
    }
    val current = editing
    if (current != null) {
        EditorDialog(
            activity = activity,
            existing = current,
            onDismiss = { editing = null },
            onSave = { Repo.save(appContext, it); editing = null; changed() },
            onDelete = { Repo.delete(appContext, it); editing = null; changed() }
        )
    }
}

private fun typeLabel(type: String): String = when (type) {
    TYPE_REMINDER -> "Reminder"
    TYPE_TASK -> "Task"
    else -> "Note"
}

@Composable
fun ItemCard(
    item: Item,
    onDone: (Item) -> Unit,
    onReopen: (Item) -> Unit,
    onMove: (Item) -> Unit,
    onEdit: (Item) -> Unit,
    onFirstStep: (Item, String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            val meta = ArrayList<String>()
            meta.add(typeLabel(item.type))
            if (item.type != TYPE_NOTE) meta.add(formatWhen(item))
            if (item.type == TYPE_TASK && item.startBy > 0 && !item.done) meta.add("start " + formatDay(item.startBy))
            if (item.critical) meta.add("critical")
            if (item.rescheduleCount > 0) meta.add("moved " + item.rescheduleCount + " times")
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall)

            if (item.firstStep.isNotBlank()) {
                Text("First step: " + item.firstStep, style = MaterialTheme.typography.bodyMedium)
            }
            val slipping = !item.done && item.rescheduleCount >= SLIPPING_AFTER
            if (slipping && item.firstStep.isBlank()) {
                var step by remember(item.id) { mutableStateOf("") }
                Text(
                    "This keeps moving. What is a first step of ten minutes or less?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = step,
                        onValueChange = { step = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { if (step.isNotBlank()) onFirstStep(item, step) }) { Text("Save") }
                }
            }
            Row {
                if (item.done) {
                    TextButton(onClick = { onReopen(item) }) { Text("Reopen") }
                } else if (item.type != TYPE_NOTE) {
                    TextButton(onClick = { onDone(item) }) { Text("Done") }
                    TextButton(onClick = { onMove(item) }) { Text("New time") }
                }
                TextButton(onClick = { onEdit(item) }) { Text("Edit") }
            }
        }
    }
}

/** The capture box (existing == null) and the edit box share this dialog. */
@Composable
fun EditorDialog(
    activity: Context,
    existing: Item?,
    onDismiss: () -> Unit,
    onSave: (Item) -> Unit,
    onDelete: (Item) -> Unit,
) {
    var text by remember { mutableStateOf(existing?.title ?: "") }
    var typeChoice by remember { mutableStateOf<String?>(existing?.type) }
    var dateChoice by remember {
        mutableStateOf<LocalDate?>(if (existing != null && existing.dueAt > 0) existing.dueAt.toLdt().toLocalDate() else null)
    }
    var timeChoice by remember {
        mutableStateOf<LocalTime?>(
            if (existing != null && existing.dueAt > 0 && existing.hasTime) existing.dueAt.toLdt().toLocalTime() else null
        )
    }
    var criticalChoice by remember { mutableStateOf<Boolean?>(existing?.critical) }
    var effort by remember { mutableStateOf((existing?.effortMin ?: 60).toString()) }
    var error by remember { mutableStateOf("") }

    // While capturing, the line is re-read on every keystroke; anything you tap overrides the guess.
    val parsed = remember(text) {
        if (existing == null) Parser.parse(text) else Parsed(existing.type, text.trim(), null, null, false)
    }
    val type = typeChoice ?: parsed.type
    val date = dateChoice ?: parsed.date
    val time = timeChoice ?: parsed.time
    val critical = criticalChoice ?: parsed.critical

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!heard.isNullOrBlank()) text = if (text.isBlank()) heard else "$text $heard"
        }
    }

    fun trySave() {
        val title = if (existing == null) parsed.title else text.trim()
        if (text.isBlank() || title.isBlank()) {
            error = "Type something first."
            return
        }
        var d = date
        var t = time
        if (type == TYPE_REMINDER) {
            if (d == null && t == null) {
                error = "A reminder needs a day or a time."
                return
            }
            val tt = t ?: LocalTime.of(9, 0)
            t = tt
            if (d == null) d = if (tt.isAfter(LocalTime.now())) LocalDate.now() else LocalDate.now().plusDays(1)
        }
        if (type == TYPE_NOTE) {
            d = null
            t = null
        }
        val dd = d
        val due = if (dd != null) LocalDateTime.of(dd, t ?: LocalTime.of(23, 59)).toMillis() else 0L
        val base = existing ?: Item()
        val moved = existing != null && existing.dueAt != 0L && existing.dueAt != due
        onSave(
            base.copy(
                type = type,
                title = title,
                dueAt = due,
                hasTime = dd != null && t != null,
                critical = critical && type == TYPE_REMINDER,
                effortMin = (effort.toIntOrNull() ?: 60).coerceIn(5, 6000),
                rescheduleCount = base.rescheduleCount + if (moved) 1 else 0,
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add" else "Edit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = "" },
                    label = { Text(if (existing == null) "e.g. dentist thursday 3pm" else "Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (existing == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            try {
                                speechLauncher.launch(
                                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                    )
                                )
                            } catch (e: Exception) {
                                error = "Voice input is not available on this phone."
                            }
                        }) { Text("Speak") }
                        if (text.isNotBlank()) {
                            Text("Saved as: " + parsed.title, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(TYPE_REMINDER, TYPE_TASK, TYPE_NOTE).forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { typeChoice = option; error = "" },
                            label = { Text(typeLabel(option)) }
                        )
                    }
                }
                if (type != TYPE_NOTE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            pickDate(activity, date ?: LocalDate.now()) { dateChoice = it; error = "" }
                        }) { Text(date?.format(dayButtonFmt) ?: "Pick day") }
                        OutlinedButton(onClick = {
                            pickTime(activity, time ?: LocalTime.of(18, 0)) { timeChoice = it; error = "" }
                        }) { Text(time?.format(timeButtonFmt) ?: "Pick time") }
                    }
                }
                if (type == TYPE_TASK) {
                    OutlinedTextField(
                        value = effort,
                        onValueChange = { effort = it.filter { ch -> ch.isDigit() }.take(4) },
                        label = { Text("Minutes of work") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (type == TYPE_REMINDER) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = critical, onCheckedChange = { criticalChoice = it })
                        Text("Critical: use the alarm sound")
                    }
                }
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { trySave() }) { Text("Save") } },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = { onDelete(existing) }) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
