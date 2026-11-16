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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)
    private val launch = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensureChannels(this)
        Scheduler.rescheduleAll(this)
        launch.value = intent?.getStringExtra(EXTRA_OPEN)
        enableEdgeToEdge()
        setContent {
            JayZeeTheme {
                App(this, tick.intValue, launch)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { launch.value = it }
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

/** What the add/edit box should open with, and what to do once it is saved. */
data class EditorRequest(
    val existing: Item? = null,
    val initialText: String = "",
    val initialType: String? = null,
    val onSaved: () -> Unit = {},
)

/** Everything a card can do to an item. Passed down so every screen behaves the same. */
class Actions(
    val activity: Context,
    val changed: () -> Unit,
    val openEditor: (EditorRequest) -> Unit,
) {
    private val app: Context get() = activity.applicationContext

    fun done(item: Item) { Repo.markDone(app, item); changed() }
    fun reopen(item: Item) { Repo.reopen(app, item); changed() }
    fun edit(item: Item) { openEditor(EditorRequest(existing = item)) }
    fun move(item: Item) {
        pickNewTime(activity, item) { due, hasTime ->
            Repo.reschedule(app, item, due, hasTime)
            changed()
        }
    }
    fun firstStep(item: Item, step: String) {
        Repo.save(app, item.copy(firstStep = step.trim()))
        changed()
    }
    /** Places a task on a day of the weekly plan (0 = not placed). */
    fun plan(item: Item, day: Long) {
        Repo.save(app, item.copy(plannedDay = day))
        changed()
    }
}

val ListPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 104.dp)

@Composable
fun App(activity: Context, tick: Int, launch: MutableState<String?>) {
    val appContext = activity.applicationContext
    var version by remember { mutableIntStateOf(0) }
    val stamp = tick + version
    val items = remember(stamp) { Db.get(appContext).all() }
    var tab by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf("") }
    var request by remember { mutableStateOf<EditorRequest?>(null) }
    val actions = remember { Actions(activity, { version = version + 1 }, { request = it }) }

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

    // Opened from the widget or from a prompt notification.
    val launchTarget = launch.value
    LaunchedEffect(launchTarget) {
        when (launchTarget) {
            OPEN_CAPTURE -> request = EditorRequest()
            OPEN_WEEK -> { overlay = ""; tab = 2 }
            OPEN_NIGHTLY -> overlay = OPEN_NIGHTLY
        }
        if (launchTarget != null) launch.value = null
    }

    // The inbox refreshes by itself while it is open, so new notifications show up.
    LaunchedEffect(tab, overlay) {
        if (tab == 1 && overlay.isEmpty()) {
            while (true) {
                delay(4000)
                version = version + 1
            }
        }
    }

    BackHandler(enabled = overlay.isNotEmpty()) { overlay = "" }

    val scheme = MaterialTheme.colorScheme
    if (overlay == "review") {
        ReviewScreen(activity, stamp, actions, onClose = { overlay = "" })
    } else if (overlay == OPEN_NIGHTLY) {
        NightlyScreen(activity, items, actions, onFinished = { review -> overlay = if (review) "review" else "" })
    } else {
        Scaffold(
            containerColor = scheme.background,
            bottomBar = {
                val navColors = NavigationBarItemDefaults.colors(
                    indicatorColor = scheme.primaryContainer,
                    selectedIconColor = scheme.onPrimaryContainer,
                )
                NavigationBar(containerColor = scheme.surface) {
                    NavigationBarItem(
                        selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Today") }, colors = navColors
                    )
                    NavigationBarItem(
                        selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                        label = { Text("Inbox") }, colors = navColors
                    )
                    NavigationBarItem(
                        selected = tab == 2, onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                        label = { Text("Week") }, colors = navColors
                    )
                    NavigationBarItem(
                        selected = tab == 3, onClick = { tab = 3 },
                        icon = { Icon(Icons.Default.Menu, contentDescription = null) },
                        label = { Text("All") }, colors = navColors
                    )
                }
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { request = EditorRequest() },
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add") }
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    0 -> TodayTab(activity, items, actions, onPlan = { tab = 2 }, onNightly = { overlay = OPEN_NIGHTLY })
                    1 -> InboxTab(activity, stamp, actions, onReview = { overlay = "review" })
                    2 -> WeekTab(activity, items, actions)
                    else -> EverythingTab(items, actions)
                }
            }
        }
    }

    val req = request
    if (req != null) {
        key(req) {
            EditorDialog(
                activity = activity,
                existing = req.existing,
                initialText = req.initialText,
                initialType = req.initialType,
                onDismiss = { request = null },
                onSave = { Repo.save(appContext, it); request = null; req.onSaved(); actions.changed() },
                onDelete = { Repo.delete(appContext, it); request = null; actions.changed() }
            )
        }
    }
}

fun LazyListScope.sectionList(sections: List<Planner.Section>, actions: Actions) {
    sections.forEach { section ->
        item(key = "header-" + section.title) {
            SectionHeader(section.title, section.items.size, section.title == "Overdue" || section.title == "Slipping")
        }
        items(section.items, key = { it.id }) { item -> ItemCard(item, actions) }
    }
}

@Composable
fun TodayTab(activity: Context, items: List<Item>, actions: Actions, onPlan: () -> Unit, onNightly: () -> Unit) {
    val ctx = activity.applicationContext
    val sections = Planner.today(items)
    val now = LocalDateTime.now()
    val needsPlan = !Planner.hasPlan(ctx)
    // Offered once the day's list is empty (after uni hours), or from the nightly prompt time.
    val nightlyDue = !Planner.reviewedTonight(ctx) &&
        ((sections.isEmpty() && now.hour >= 17) || !now.toLocalTime().isBefore(NIGHTLY_PROMPT_TIME))
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ListPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "top") { TodayHeader(items, sections) }
        if (needsPlan) {
            item(key = "plan") {
                Banner(
                    if (now.dayOfWeek == DayOfWeek.SUNDAY) "Next week has no plan yet." else "This week has no plan yet.",
                    "Plan the week", onPlan
                )
            }
        }
        if (nightlyDue) {
            item(key = "nightly") { Banner("Wrapping up? Check tomorrow's plan.", "Review tomorrow", onNightly) }
        }
        if (sections.isEmpty()) item(key = "empty") { EmptyState(true) }
        sectionList(sections, actions)
    }
}

@Composable
fun EverythingTab(items: List<Item>, actions: Actions) {
    val open = items.filter { !it.done }
    val sections = listOf(
        Planner.Section("Reminders", open.filter { it.type == TYPE_REMINDER }),
        Planner.Section("Tasks", open.filter { it.type == TYPE_TASK }),
        Planner.Section("Notes", open.filter { it.type == TYPE_NOTE }),
        Planner.Section("Done", items.filter { it.done }.sortedByDescending { it.doneAt }.take(20)),
    ).filter { it.items.isNotEmpty() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ListPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "top") { EverythingHeader(items) }
        if (sections.isEmpty()) item(key = "empty") { EmptyState(false) }
        sectionList(sections, actions)
    }
}

@Composable
fun Banner(text: String, button: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.secondaryContainer)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Pill(button, scheme.secondary, scheme.onSecondary, onClick)
    }
}

fun typeLabel(type: String): String = when (type) {
    TYPE_REMINDER -> "Reminder"
    TYPE_TASK -> "Task"
    else -> "Note"
}

@Composable
fun TodayHeader(items: List<Item>, sections: List<Planner.Section>) {
    val scheme = MaterialTheme.colorScheme
    val now = LocalDateTime.now()
    val startOfToday = now.toLocalDate().atStartOfDay().toMillis()
    val open = sections.sumOf { it.items.size }
    val overdue = sections.firstOrNull { it.title == "Overdue" }?.items?.size ?: 0
    val doneToday = items.count { it.done && it.doneAt >= startOfToday }
    // Dark mode gets a quieter card; light mode keeps the solid navy block.
    val darkMode = scheme.background.luminance() < 0.5f
    val heroBg = if (darkMode) scheme.primaryContainer else scheme.primary
    val heroFg = if (darkMode) scheme.onPrimaryContainer else scheme.onPrimary
    val barColor = if (darkMode) scheme.primary else scheme.onPrimary
    val greeting = when {
        now.hour < 12 -> "Good morning"
        now.hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
    Column {
        Text(
            now.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant
        )
        Text(greeting, style = MaterialTheme.typography.headlineMedium, color = scheme.onBackground)
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(heroBg)
                .padding(18.dp)
        ) {
            Text(
                if (open == 0) "Nothing left for today" else "$open left for today",
                style = MaterialTheme.typography.titleLarge,
                color = heroFg
            )
            Text(
                (if (overdue > 0) "$overdue overdue · " else "") + "$doneToday done today",
                style = MaterialTheme.typography.bodyMedium,
                color = heroFg.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(12.dp))
            val total = open + doneToday
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else doneToday.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = barColor,
                trackColor = heroFg.copy(alpha = 0.18f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
fun EverythingHeader(items: List<Item>) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text(
            "${items.count { !it.done }} open · ${items.count { it.done }} done",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant
        )
        Text("Everything", style = MaterialTheme.typography.headlineMedium, color = scheme.onBackground)
    }
}

@Composable
fun SectionHeader(title: String, count: Int, urgent: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp)
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = if (urgent) scheme.error else scheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Pill(
            count.toString(),
            if (urgent) scheme.errorContainer else scheme.surfaceVariant,
            if (urgent) scheme.onErrorContainer else scheme.onSurfaceVariant
        )
    }
}

@Composable
fun EmptyState(today: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp)
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(scheme.tertiaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = scheme.onTertiaryContainer)
        }
        Spacer(Modifier.height(14.dp))
        Text(if (today) "All clear" else "Nothing here yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Tap Add and type a line, like \"dentist thursday 3pm\".",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant
        )
    }
}

@Composable
fun Pill(text: String, container: Color, content: Color, onClick: (() -> Unit)? = null) {
    val shaped = Modifier.clip(RoundedCornerShape(50)).background(container)
    val mod = if (onClick != null) shaped.clickable(onClick = onClick) else shaped
    Text(
        text,
        color = content,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = mod.padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
fun ItemCard(
    item: Item,
    actions: Actions,
    extraLabel: String = "",
    onExtra: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val nowMs = System.currentTimeMillis()
    val overdue = !item.done && item.dueAt in 1 until nowMs
    val slipping = !item.done && item.rescheduleCount >= SLIPPING_AFTER
    val accent = when {
        item.done -> scheme.tertiary
        overdue || item.critical -> scheme.error
        item.type == TYPE_REMINDER -> scheme.primary
        item.type == TYPE_TASK -> scheme.secondary
        else -> scheme.outline
    }
    Card(
        onClick = { actions.edit(item) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(accent, size = Size(5.dp.toPx(), size.height)) }
                .padding(start = 13.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (item.type != TYPE_NOTE) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { if (item.done) actions.reopen(item) else actions.done(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .then(if (item.done) Modifier.background(accent) else Modifier.border(2.dp, accent, CircleShape)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (item.done) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Reopen",
                                tint = scheme.onTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.done) scheme.onSurfaceVariant else scheme.onSurface,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null
                )
                val whenText = if (item.type == TYPE_NOTE) "Note" else typeLabel(item.type) + " · " + formatWhen(item)
                Text(
                    whenText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) scheme.error else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (item.firstStep.isNotBlank() && !item.done) {
                    Text(
                        "First step: " + item.firstStep,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                if (slipping && item.firstStep.isBlank()) {
                    var step by remember(item.id) { mutableStateOf("") }
                    Text(
                        "This keeps moving. What is a first step of ten minutes or less?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = step,
                            onValueChange = { step = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { if (step.isNotBlank()) actions.firstStep(item, step) }) { Text("Save") }
                    }
                }
                if (!item.done && item.type != TYPE_NOTE) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())
                    ) {
                        if (extraLabel.isNotEmpty()) Pill(extraLabel, scheme.primaryContainer, scheme.onPrimaryContainer, onExtra)
                        Pill("New time", scheme.surfaceVariant, scheme.onSurface) { actions.move(item) }
                        if (item.type == TYPE_TASK && item.startBy > 0) {
                            Pill("Start " + formatDay(item.startBy), scheme.secondaryContainer, scheme.onSecondaryContainer)
                        }
                        if (item.critical) Pill("Critical", scheme.errorContainer, scheme.onErrorContainer)
                        if (item.rescheduleCount > 0) {
                            Pill(
                                "Moved " + item.rescheduleCount + "×",
                                if (slipping) scheme.errorContainer else scheme.surfaceVariant,
                                if (slipping) scheme.onErrorContainer else scheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The capture box (existing == null) and the edit box share this dialog. */
@Composable
fun EditorDialog(
    activity: Context,
    existing: Item?,
    initialText: String = "",
    initialType: String? = null,
    onDismiss: () -> Unit,
    onSave: (Item) -> Unit,
    onDelete: (Item) -> Unit,
) {
    var text by remember { mutableStateOf(existing?.title ?: initialText) }
    var typeChoice by remember { mutableStateOf<String?>(existing?.type ?: initialType) }
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
        shape = RoundedCornerShape(24.dp),
        title = { Text(if (existing == null) "New entry" else "Edit") },
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
                            Text(
                                "Saved as: " + parsed.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
