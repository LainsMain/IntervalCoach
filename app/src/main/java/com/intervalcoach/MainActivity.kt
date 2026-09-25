package com.intervalcoach

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.intervalcoach.data.*
import com.intervalcoach.session.*
import com.intervalcoach.update.UpdateViewModel
import com.intervalcoach.update.UpdateUiState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CoachApp
        setContent { CoachTheme { AppUi(app) } }
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoachViewModel(private val app: CoachApp) : ViewModel() {
    val workouts = app.repository.workouts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val session = app.session.asStateFlow()
    private val selectedId = MutableStateFlow<Long?>(null)
    val selected = selectedId.flatMapLatest { id -> if (id == null) flowOf(null) else app.repository.workout(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun select(id: Long?) { selectedId.value = id }
    fun create() = viewModelScope.launch { selectedId.value = app.repository.create("New workout") }
    fun rename(id: Long, name: String) = viewModelScope.launch { app.repository.rename(id, name) }
    fun delete(id: Long) = viewModelScope.launch { app.repository.delete(id); selectedId.value = null }
    fun add(id: Long, activity: String, seconds: Int, instruction: String) = viewModelScope.launch { app.repository.add(id, activity, seconds, instruction) }
    fun edit(item: IntervalEntity, activity: String, seconds: Int, instruction: String) = viewModelScope.launch { app.repository.edit(item, activity, seconds, instruction) }
    fun duplicate(item: IntervalEntity) = viewModelScope.launch { app.repository.duplicate(item) }
    fun remove(item: IntervalEntity) = viewModelScope.launch { app.repository.remove(item) }
    fun move(id: Long, from: Int, to: Int) = viewModelScope.launch { app.repository.move(id, from, to) }
}

@Composable private fun CoachTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
        else if (dark) darkColorScheme(primary = Color(0xFFBBD0B8), onPrimary = Color(0xFF263C2A), primaryContainer = Color(0xFF384F3C), secondaryContainer = Color(0xFF3C4A45), surface = Color(0xFF121A17), surfaceContainer = Color(0xFF202A25))
        else lightColorScheme(primary = Color(0xFF456A4C), onPrimary = Color.White, primaryContainer = Color(0xFFD2E8D0), secondaryContainer = Color(0xFFD9E6DC), surface = Color(0xFFF7FAF5), surfaceContainer = Color(0xFFEAF1E9))
    MaterialTheme(colorScheme = scheme, shapes = Shapes(large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp)), content = content)
}

@Composable private fun AppUi(app: CoachApp) {
    val vm: CoachViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = CoachViewModel(app) as T
    })
    val updates: UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(app) as T
    })
    val updateState by updates.state.collectAsStateWithLifecycle()
    var updateSheetOpen by remember { mutableStateOf(false) }
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    var showWorkout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(session != null) { if (session != null) showWorkout = true }
    BackHandler(enabled = showWorkout || selected != null) {
        if (showWorkout) showWorkout = false else vm.select(null)
    }
    Surface(Modifier.fillMaxSize()) { when {
        showWorkout && session != null -> ActiveScreen(session!!, onCommand = { WorkoutService.command(context, it) }, onClose = { showWorkout = false })
        selected != null -> EditorScreen(selected!!, vm, onBack = { vm.select(null) }, onStart = { WorkoutService.command(context, WorkoutService.ACTION_START, selected!!.workout.id); showWorkout = true })
        else -> HomeScreen(workouts, onOpen = { vm.select(it) }, onStart = { WorkoutService.command(context, WorkoutService.ACTION_START, it); showWorkout = true }, onCreate = vm::create,
            active = session != null, onResume = { showWorkout = true }, updateAvailable = updateState.available, onUpdates = { updateSheetOpen = true })
    } }
    if (updateSheetOpen) UpdateSheet(updateState, updates, workoutActive = session != null, onDismiss = { updateSheetOpen = false })
}

@Composable private fun HomeScreen(workouts: List<WorkoutWithIntervals>, onOpen: (Long) -> Unit, onStart: (Long) -> Unit, onCreate: () -> Unit, active: Boolean, onResume: () -> Unit, updateAvailable: Boolean, onUpdates: () -> Unit) {
    Scaffold(floatingActionButton = { FloatingActionButton(onClick = onCreate, shape = CircleShape) { Icon(Icons.Default.Add, "Create workout") } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(32.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Runs", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                BadgedBox(badge = { if (updateAvailable) Badge() }) {
                    IconButton(onClick = onUpdates, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Default.SystemUpdate, if (updateAvailable) "Update available" else "Check for updates")
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            if (active) {
                FilledTonalButton(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Return to workout") }
                Spacer(Modifier.height(20.dp))
            }
            if (workouts.isEmpty()) {
                Spacer(Modifier.weight(1f))
                Text("Your next run starts here.", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text("Build a sequence of walks and runs, then let spoken cues guide you.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Button(onClick = onCreate) { Text("Create workout") }
                Spacer(Modifier.weight(1.4f))
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(workouts, key = { _, w -> w.workout.id }) { _, w ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(w.workout.id) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.workout.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                            Text("${formatTime(w.totalSeconds * 1000L)} · ${w.intervals.size} intervals", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onStart(w.workout.id) }, enabled = w.intervals.isNotEmpty(), modifier = Modifier.size(56.dp).semantics { contentDescription = "Start ${w.workout.name}" }) { Icon(Icons.Default.PlayArrow, null) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EditorScreen(w: WorkoutWithIntervals, vm: CoachViewModel, onBack: () -> Unit, onStart: () -> Unit) {
    var sheetItem by remember { mutableStateOf<IntervalEntity?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var menuId by remember { mutableStateOf<Long?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Edit workout") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, actions = {
        IconButton(onClick = { deleteDialog = true }) { Icon(Icons.Default.DeleteOutline, "Delete workout") }
    }) }, bottomBar = {
        Surface(tonalElevation = 3.dp) { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(w.totalSeconds * 1000L), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = onStart, enabled = w.intervals.isNotEmpty(), modifier = Modifier.height(56.dp), shape = RoundedCornerShape(20.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start") }
        } }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth().clickable { nameDialog = true }.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(w.workout.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Edit, "Rename workout", modifier = Modifier.size(20.dp))
                }
                Text("${w.intervals.size} ${if (w.intervals.size == 1) "interval" else "intervals"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(w.intervals, key = { _, item -> item.id }) { index, item ->
                var drag by remember { mutableFloatStateOf(0f) }
                Row(Modifier.fillMaxWidth().animateItem().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(24.dp))
                    .clickable { sheetItem = item; sheetOpen = true }.padding(start = 18.dp, top = 9.dp, bottom = 9.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.activity.uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(formatTime(item.durationSeconds * 1000L) + item.instruction.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menuId = item.id }) { Icon(Icons.Default.MoreVert, "Interval actions") }
                        DropdownMenu(expanded = menuId == item.id, onDismissRequest = { menuId = null }) {
                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { vm.duplicate(item); menuId = null })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { vm.remove(item); menuId = null })
                            if (index > 0) DropdownMenuItem(text = { Text("Move up") }, onClick = { vm.move(w.workout.id, index, index - 1); menuId = null })
                            if (index < w.intervals.lastIndex) DropdownMenuItem(text = { Text("Move down") }, onClick = { vm.move(w.workout.id, index, index + 1); menuId = null })
                        }
                    }
                    Box(Modifier.size(48.dp).pointerInput(item.id, index) {
                        detectDragGesturesAfterLongPress(onDragEnd = { drag = 0f }, onDragCancel = { drag = 0f }) { change, amount ->
                            change.consume(); drag += amount.y
                            if (drag > 64 && index < w.intervals.lastIndex) { vm.move(w.workout.id, index, index + 1); drag = 0f }
                            else if (drag < -64 && index > 0) { vm.move(w.workout.id, index, index - 1); drag = 0f }
                        }
                    }, contentAlignment = Alignment.Center) { Icon(Icons.Default.DragHandle, "Hold and drag to reorder") }
                }
            }
            item { OutlinedButton(onClick = { sheetItem = null; sheetOpen = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add interval") } }
        }
    }
    if (sheetOpen) IntervalSheet(sheetItem, onDismiss = { sheetOpen = false }, onSave = { activity, seconds, instruction ->
        if (sheetItem == null) vm.add(w.workout.id, activity, seconds, instruction) else vm.edit(sheetItem!!, activity, seconds, instruction)
        sheetOpen = false
    })
    if (nameDialog) {
        var name by remember(w.workout.id) { mutableStateOf(w.workout.name) }
        AlertDialog(onDismissRequest = { nameDialog = false }, title = { Text("Workout name") }, text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) }, confirmButton = { TextButton(onClick = { vm.rename(w.workout.id, name); nameDialog = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { nameDialog = false }) { Text("Cancel") } })
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("Delete workout?") }, text = { Text("This will remove ${w.workout.name} and its intervals.") }, confirmButton = { TextButton(onClick = { vm.delete(w.workout.id); deleteDialog = false }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun IntervalSheet(item: IntervalEntity?, onDismiss: () -> Unit, onSave: (String, Int, String) -> Unit) {
    var activity by remember(item?.id) { mutableStateOf(item?.activity ?: "Walk") }
    var instruction by remember(item?.id) { mutableStateOf(item?.instruction ?: "") }
    var minutes by remember(item?.id) { mutableIntStateOf(item?.durationSeconds?.div(60) ?: 1) }
    var seconds by remember(item?.id) { mutableIntStateOf(item?.durationSeconds?.rem(60) ?: 0) }
    val activityChoices = listOf("Walk", "Run", "Jog", "Sprint", "Rest")
    val instructionChoices = listOf("Easy", "Slow", "Normal", "Fast")
    var customActivity by remember(item?.id) { mutableStateOf(item != null && item.activity !in activityChoices) }
    var customInstruction by remember(item?.id) { mutableStateOf(item != null && item.instruction.isNotBlank() && item.instruction !in instructionChoices) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(if (item == null) "Add interval" else "Edit interval", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(24.dp))
            Text("ACTIVITY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            ChoiceRows(activityChoices + "Custom", if (customActivity) "Custom" else activity) {
                if (it == "Custom") { customActivity = true; activity = "" }
                else { customActivity = false; activity = it }
            }
            if (customActivity) OutlinedTextField(activity, { activity = it }, label = { Text("Activity name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(22.dp))
            Text("DURATION", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            ChoiceRows(listOf("30 sec", "1 min", "2 min", "5 min"), if (seconds == 30 && minutes == 0) "30 sec" else "$minutes min") { choice ->
                val total = when (choice) { "30 sec" -> 30; "1 min" -> 60; "2 min" -> 120; else -> 300 }
                minutes = total / 60; seconds = total % 60
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DurationStepper("Minutes", minutes, 0..599, { minutes = it }, Modifier.weight(1f))
                DurationStepper("Seconds", seconds, 0..59, { seconds = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(22.dp))
            Text("INSTRUCTION · OPTIONAL", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            ChoiceRows(listOf("None") + instructionChoices + "Custom", if (customInstruction) "Custom" else if (instruction.isBlank()) "None" else instruction) {
                when (it) {
                    "Custom" -> { customInstruction = true; instruction = "" }
                    "None" -> { customInstruction = false; instruction = "" }
                    else -> { customInstruction = false; instruction = it }
                }
            }
            if (customInstruction) OutlinedTextField(instruction, { instruction = it }, label = { Text("Your instruction") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            Button(onClick = { onSave(activity.trim(), minutes * 60 + seconds, instruction.trim()) }, enabled = activity.isNotBlank() && minutes * 60 + seconds in 1..35999, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(20.dp)) { Text(if (item == null) "Add interval" else "Save interval") }
        }
    }
}

@Composable private fun ChoiceRows(choices: List<String>, selected: String, onSelect: (String) -> Unit) {
    val groups = choices.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.forEach { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                group.forEach { choice ->
                    FilterChip(selected = selected.equals(choice, ignoreCase = true), onClick = { onSelect(choice) }, label = { Text(choice) })
                }
            }
        }
    }
}

@Composable private fun DurationStepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Remove, "Decrease $label") }
            Text(value.toString().padStart(2, '0'), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Add, "Increase $label") }
        }
    }
}

@Composable private fun ActiveScreen(s: SessionSnapshot, onCommand: (String) -> Unit, onClose: () -> Unit) {
    var stopDialog by remember { mutableStateOf(false) }
    if (s.complete) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(22.dp))
            Text("Done", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text("${formatTime(s.intervals.sumOf { it.durationSeconds } * 1000L)} · ${s.intervals.size} intervals", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Button(onClick = { onCommand(WorkoutService.ACTION_STOP); onClose() }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(20.dp)) { Text("Finish") }
        }
        return
    }
    val item = s.current
    val seconds = ceil(s.remainingMs / 1000.0).toInt()
    val progress = 1f - (s.remainingMs.toFloat() / (item.durationSeconds * 1000f)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.workoutName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = { stopDialog = true }) { Icon(Icons.Default.Close, "Stop workout") }
        }
        Spacer(Modifier.weight(0.7f))
        androidx.compose.animation.AnimatedContent(targetState = s.index, label = "interval") { index ->
            val displayed = s.intervals[index]
            Column {
                Text(displayed.activity.uppercase(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(displayed.instruction.ifBlank { " " }, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(formatTime(s.remainingMs), style = MaterialTheme.typography.displayLarge.copy(fontSize = 80.sp, lineHeight = 88.sp), fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$seconds seconds remaining" })
        Spacer(Modifier.height(18.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(if (seconds <= 10 && item.durationSeconds > 10) 8.dp else 5.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        Spacer(Modifier.weight(0.9f))
        Text(if (s.next == null) "FINAL INTERVAL" else "NEXT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(5.dp))
        Text(s.next?.let { "${it.activity} · ${formatTime(it.durationSeconds * 1000L)}" } ?: "Finish", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text("${s.index + 1} of ${s.intervals.size}${if (s.paused) " · Paused" else ""}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(0.55f))
        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onCommand(WorkoutService.ACTION_PREVIOUS) }, modifier = Modifier.size(64.dp)) { Icon(Icons.Default.SkipPrevious, "Previous interval") }
            Button(onClick = { onCommand(if (s.paused) WorkoutService.ACTION_RESUME else WorkoutService.ACTION_PAUSE) }, modifier = Modifier.height(ButtonDefaults.LargeContainerHeight), shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(28.dp), pressedShape = RoundedCornerShape(16.dp)), contentPadding = PaddingValues(horizontal = 26.dp)) {
                Icon(if (s.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                Spacer(Modifier.width(8.dp))
                Text(if (s.paused) "Resume" else "Pause", style = MaterialTheme.typography.titleMedium)
            }
            FilledTonalIconButton(onClick = { onCommand(WorkoutService.ACTION_NEXT) }, modifier = Modifier.size(64.dp)) { Icon(Icons.Default.SkipNext, "Next interval") }
        }
    }
    if (stopDialog) AlertDialog(onDismissRequest = { stopDialog = false }, title = { Text("Stop workout?") }, confirmButton = { TextButton(onClick = { onCommand(WorkoutService.ACTION_STOP); onClose(); stopDialog = false }) { Text("Stop") } }, dismissButton = { TextButton(onClick = { stopDialog = false }) { Text("Keep going") } })
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun UpdateSheet(state: UpdateUiState, vm: UpdateViewModel, workoutActive: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val installedVersion = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val permissionLauncher = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) {
            try { vm.installIntent()?.let(context::startActivity) }
            catch (e: Exception) { vm.reportError(e.message ?: "Could not open the installer") }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("App updates", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text("Installed · v$installedVersion", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.release != null) {
                Spacer(Modifier.height(6.dp))
                Text("Latest · ${state.release.tag}", style = MaterialTheme.typography.bodyLarge)
            }
            if (workoutActive && state.downloadedFile != null) {
                Spacer(Modifier.height(10.dp))
                Text("Finish your workout before installing the update.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            if (state.checking) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Checking GitHub…", style = MaterialTheme.typography.bodyMedium)
            } else {
                state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (state.available) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                state.progress?.let { progress ->
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Downloading · $progress%", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(18.dp))
                when {
                    state.downloadedFile != null -> Button(onClick = {
                        if (context.packageManager.canRequestPackageInstalls()) {
                            try { vm.installIntent()?.let(context::startActivity) }
                            catch (e: Exception) { vm.reportError(e.message ?: "Could not open the installer") }
                        } else permissionLauncher.launch(vm.permissionIntent())
                    }, enabled = !workoutActive, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Install update") }
                    state.available -> Button(onClick = vm::download, enabled = state.progress == null,
                        modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Download ${state.release?.tag.orEmpty()}") }
                    else -> OutlinedButton(onClick = vm::check, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Check again") }
                }
            }
        }
    }
}
