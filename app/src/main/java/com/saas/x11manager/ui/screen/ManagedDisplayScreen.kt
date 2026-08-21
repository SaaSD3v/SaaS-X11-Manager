package com.saas.x11manager.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saas.x11manager.ui.component.TerminalDialog
import com.saas.x11manager.util.ViewModelLogger
import com.saas.x11manager.util.X11DisplayAllocator
import com.saas.x11manager.util.X11DisplaySlot
import com.saas.x11manager.util.X11MonitorInfo
import com.saas.x11manager.util.X11ServerStatus
import com.saas.x11manager.util.X11SessionManager
import com.termux.x11.EmbeddedDisplayHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PREF_KNOWN_MONITOR_SLOTS = "saas_known_monitor_slots"
private const val FULLSCREEN_CONTROL_HIDE_DELAY_MS = 1_600L

@Composable
fun ManagedDisplayScreen(
    viewModel: HomeViewModel,
    onClose: () -> Unit
) {
    val globalServerStatus by viewModel.x11ServerStatus.collectAsState()
    val globalServerPid by viewModel.x11ServerPid.collectAsState()
    val containers by viewModel.containers.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findManagedDisplayActivity() }
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { EmbeddedDisplayHost.getPrefs(context) }
    val store = remember(prefs) { prefs.get() }
    val xkbSeedContainer = containers.firstOrNull { it.isRunning } ?: containers.firstOrNull()

    var monitors by remember { mutableStateOf<List<X11MonitorInfo>>(emptyList()) }
    var knownDisplayNumbers by remember(store) {
        mutableStateOf(readKnownMonitorSlots(store))
    }
    var selectedDisplayNumber by remember { mutableIntStateOf(0) }
    var busyDisplayNumber by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var showConfiguration by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var fullscreenControlVisible by remember { mutableStateOf(false) }
    var fullscreenPointerPulse by remember { mutableLongStateOf(0L) }
    var additionalKeysEnabled by remember {
        mutableStateOf(store.getBoolean(PREF_SHOW_ADDITIONAL_KEYS, false))
    }
    var additionalKeysVisible by remember { mutableStateOf(false) }
    var extraKeysConfig by remember { mutableStateOf(store.getString("extra_keys_config", null)) }

    val monitorLogs = remember { mutableStateListOf<Pair<Int, String>>() }
    var showMonitorLogs by remember { mutableStateOf(false) }
    var monitorLogTitle by remember { mutableStateOf("X11 monitor logs") }

    val selectedMonitor = monitors.firstOrNull { it.slot.number == selectedDisplayNumber }
    val selectedSlot = selectedMonitor?.slot ?: X11DisplaySlot(selectedDisplayNumber)
    val selectedStatus = selectedMonitor?.status ?: X11ServerStatus.Stopped
    val selectedPid = selectedMonitor?.pid

    fun persistKnownDisplayNumbers(numbers: Set<Int>) {
        val sanitized = buildSet {
            add(0)
            numbers.filterTo(this) { it >= 0 }
        }.toSortedSet()
        knownDisplayNumbers = sanitized
        store.edit()
            .putStringSet(PREF_KNOWN_MONITOR_SLOTS, sanitized.map(Int::toString).toSet())
            .apply()
    }

    suspend fun refreshMonitors() {
        val live = X11SessionManager.getMonitors()
        val liveByNumber = live.associateBy { it.slot.number }
        val visibleNumbers = buildSet {
            add(0)
            addAll(knownDisplayNumbers)
            addAll(liveByNumber.keys)
        }.sorted()

        if (visibleNumbers.toSet() != knownDisplayNumbers) {
            persistKnownDisplayNumbers(visibleNumbers.toSet())
        }

        monitors = visibleNumbers.map { number ->
            liveByNumber[number] ?: X11MonitorInfo(
                slot = X11DisplaySlot(number),
                status = X11ServerStatus.Stopped,
                pid = null,
                containerName = null
            )
        }

        if (selectedDisplayNumber !in visibleNumbers) {
            connected = false
            selectedDisplayNumber = 0
        }
    }

    fun publishAdditionalKeysVisible() {
        store.edit()
            .putBoolean(PREF_ADDITIONAL_KEYS_VISIBLE, additionalKeysVisible)
            .apply()
        publishLoriePreferenceChange(context, PREF_ADDITIONAL_KEYS_VISIBLE)
    }

    fun toggleAdditionalKeys() {
        additionalKeysVisible = !additionalKeysVisible
        publishAdditionalKeysVisible()
    }

    fun setFullscreen(value: Boolean) {
        if (value && selectedStatus != X11ServerStatus.Running) return
        fullscreen = value
        if (value) fullscreenPointerPulse++ else fullscreenControlVisible = false
        store.edit()
            .putBoolean(PREF_FULLSCREEN, value)
            .putBoolean(PREF_ADDITIONAL_KEYS_VISIBLE, additionalKeysVisible)
            .apply()
        publishLoriePreferenceChange(context, PREF_FULLSCREEN)
        publishLoriePreferenceChange(context, PREF_ADDITIONAL_KEYS_VISIBLE)
    }

    fun closeScreen() {
        if (fullscreen) setFullscreen(false)
        onClose()
    }

    fun selectMonitor(slot: X11DisplaySlot) {
        if (slot.number == selectedDisplayNumber) return
        connected = false
        selectedDisplayNumber = slot.number
    }

    fun createMonitor() {
        if (busyDisplayNumber != null) return
        val occupied = knownDisplayNumbers + monitors.map { it.slot.number }
        val slot = X11DisplayAllocator.firstFree(occupied)
        persistKnownDisplayNumbers(knownDisplayNumbers + slot.number)
        connected = false
        selectedDisplayNumber = slot.number
        scope.launch { refreshMonitors() }
    }

    fun deleteMonitor(monitor: X11MonitorInfo) {
        if (
            busyDisplayNumber != null ||
            monitor.slot.number == 0 ||
            monitor.status == X11ServerStatus.Running ||
            monitor.containerName != null
        ) return

        persistKnownDisplayNumbers(knownDisplayNumbers - monitor.slot.number)
        if (selectedDisplayNumber == monitor.slot.number) {
            connected = false
            selectedDisplayNumber = 0
        }
        scope.launch { refreshMonitors() }
    }

    fun operationLogger(): ViewModelLogger = ViewModelLogger { level, line ->
        scope.launch { monitorLogs.add(level to line) }
    }

    fun toggleMonitor(monitor: X11MonitorInfo) {
        if (busyDisplayNumber != null) return
        selectMonitor(monitor.slot)
        monitorLogs.clear()
        monitorLogTitle = if (monitor.status == X11ServerStatus.Running) {
            "Stopping ${monitor.slot.describe()}"
        } else {
            "Starting ${monitor.slot.describe()}"
        }
        showMonitorLogs = true
        val logger = operationLogger()

        scope.launch {
            busyDisplayNumber = monitor.slot.number
            message = null
            try {
                if (monitor.status == X11ServerStatus.Running) {
                    logger.i("--- Stopping X11 monitor ---")
                    logger.i("[*] Monitor: ${monitor.monitorNumber}")
                    logger.i("[*] Display: ${monitor.displayName}")
                    monitor.pid?.let { logger.i("[*] PID: $it") }
                    monitor.containerName?.let { logger.i("[*] Container remains running: $it") }
                    logger.i("")

                    // A monitor action owns only the Manager X11 server. Stopping
                    // the card must never stop the associated DroidSpaces container.
                    val stopped = X11SessionManager.stopIntegratedServer(monitor.slot, logger)

                    if (!stopped) {
                        message = "${monitor.slot.describe()} could not be stopped"
                        logger.e("[-] ${monitor.slot.describe()} stop failed")
                    } else {
                        connected = false
                        if (monitor.containerName != null) {
                            // The running container still owns this display lease in
                            // its bind mount. Keep the card so the same X11 server can
                            // be started again without restarting the distro.
                            persistKnownDisplayNumbers(knownDisplayNumbers + monitor.slot.number)
                            logger.i("[+] Container '${monitor.containerName}' was left running")
                            logger.i("[+] ${monitor.slot.describe()} retained for this container")
                        } else if (monitor.slot.number > 0) {
                            persistKnownDisplayNumbers(knownDisplayNumbers - monitor.slot.number)
                            logger.i("[+] Monitor ${monitor.monitorNumber} removed from the monitor list")
                        } else {
                            logger.i("[+] Monitor 1 (:0) retained as the primary monitor")
                        }
                        if (fullscreen && monitor.slot.number == selectedDisplayNumber) {
                            setFullscreen(false)
                        }
                    }
                } else {
                    logger.i("--- Starting X11 monitor ---")
                    logger.i("[*] Monitor: ${monitor.monitorNumber}")
                    logger.i("[*] Display: ${monitor.displayName}")
                    logger.i("[*] Process: ${monitor.slot.processName}")
                    logger.i("[*] Runtime: ${monitor.slot.runtimeDir}")
                    logger.i("[*] Socket: ${monitor.slot.socketFile}")
                    logger.i("")

                    val seed = monitor.containerName ?: xkbSeedContainer?.name
                    seed?.let { logger.i("[*] XKB seed container: $it") }
                    val started = X11SessionManager.startIntegratedServer(
                        displaySlot = monitor.slot,
                        containerName = seed,
                        logger = logger
                    )
                    if (started.isSuccess) {
                        persistKnownDisplayNumbers(knownDisplayNumbers + monitor.slot.number)
                        logger.i("")
                        logger.i("[+] ${monitor.slot.describe()} is ready")
                        logger.i("[+] PID: ${started.getOrNull()}")
                    } else {
                        message = started.exceptionOrNull()?.message
                            ?: "${monitor.slot.describe()} could not start"
                        logger.e("[-] ${message ?: "X11 start failed"}")
                    }
                }
            } finally {
                viewModel.refreshRuntimeState()
                refreshMonitors()
                busyDisplayNumber = null
            }
        }
    }

    LaunchedEffect(store) {
        ensureManagedX11Defaults(context, store)
        additionalKeysEnabled = store.getBoolean(PREF_SHOW_ADDITIONAL_KEYS, false)
        additionalKeysVisible = false
        fullscreen = false
        store.edit()
            .putBoolean(PREF_ADDITIONAL_KEYS_VISIBLE, false)
            .putBoolean(PREF_FULLSCREEN, false)
            .apply()
        publishLoriePreferenceChange(context, PREF_ADDITIONAL_KEYS_VISIBLE)
        publishLoriePreferenceChange(context, PREF_FULLSCREEN)
        refreshMonitors()
    }

    LaunchedEffect(globalServerStatus, globalServerPid, containers) {
        refreshMonitors()
    }

    LaunchedEffect(fullscreen, fullscreenPointerPulse) {
        if (!fullscreen) {
            fullscreenControlVisible = false
            return@LaunchedEffect
        }
        fullscreenControlVisible = true
        delay(FULLSCREEN_CONTROL_HIDE_DELAY_MS)
        fullscreenControlVisible = false
    }

    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PREF_SHOW_ADDITIONAL_KEYS -> {
                    additionalKeysEnabled = store.getBoolean(PREF_SHOW_ADDITIONAL_KEYS, false)
                    if (!additionalKeysEnabled) {
                        additionalKeysVisible = false
                        publishAdditionalKeysVisible()
                    }
                }
                PREF_ADDITIONAL_KEYS_VISIBLE -> {
                    additionalKeysVisible = store.getBoolean(PREF_ADDITIONAL_KEYS_VISIBLE, false)
                }
                PREF_FULLSCREEN -> {
                    val requested = store.getBoolean(PREF_FULLSCREEN, false)
                    fullscreen = requested && selectedStatus == X11ServerStatus.Running
                }
                "extra_keys_config" -> {
                    extraKeysConfig = store.getString("extra_keys_config", null)
                }
            }
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    ManagedX11WindowEffects(
        activity = activity,
        store = store,
        connected = connected,
        fullscreen = fullscreen
    )

    LaunchedEffect(selectedStatus, fullscreen) {
        if (fullscreen && selectedStatus != X11ServerStatus.Running) {
            setFullscreen(false)
        }
    }

    BackHandler {
        if (fullscreen) setFullscreen(false) else onClose()
    }

    if (showMonitorLogs) {
        TerminalDialog(
            title = monitorLogTitle,
            logs = monitorLogs,
            onDismiss = { if (busyDisplayNumber == null) showMonitorLogs = false },
            onClear = { if (busyDisplayNumber == null) monitorLogs.clear() },
            isBlocking = busyDisplayNumber != null
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (fullscreen) {
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (
                                        event.type == PointerEventType.Move ||
                                        event.type == PointerEventType.Enter
                                    ) {
                                        fullscreenPointerPulse++
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (fullscreen) Modifier
                        else Modifier.statusBarsPadding().navigationBarsPadding()
                    )
            ) {
                if (!fullscreen) {
                    ManagedDisplayTopBar(
                        monitor = selectedMonitor,
                        slot = selectedSlot,
                        serverStatus = selectedStatus,
                        serverPid = selectedPid,
                        connected = connected,
                        hasLogs = monitorLogs.isNotEmpty(),
                        additionalKeysEnabled = additionalKeysEnabled,
                        additionalKeysVisible = additionalKeysVisible,
                        onClose = ::closeScreen,
                        onShowLogs = { showMonitorLogs = true },
                        onToggleAdditionalKeys = ::toggleAdditionalKeys,
                        onFullscreen = { setFullscreen(true) },
                        onConfiguration = { showConfiguration = true }
                    )

                    MonitorDeck(
                        monitors = monitors,
                        selectedDisplayNumber = selectedDisplayNumber,
                        busyDisplayNumber = busyDisplayNumber,
                        canStartStopped = xkbSeedContainer != null,
                        onCreate = ::createMonitor,
                        onSelect = ::selectMonitor,
                        onToggle = ::toggleMonitor,
                        onDelete = ::deleteMonitor
                    )
                }

                message?.let { DisplayErrorMessage(it) }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(
                            if (fullscreen) Modifier
                            else Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(10.dp),
                        color = Color.Black,
                        border = if (fullscreen) {
                            null
                        } else {
                            BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                            )
                        }
                    ) {
                        ManagedDisplayViewport(
                            slot = selectedSlot,
                            serverStatus = selectedStatus,
                            connected = connected,
                            hasSeedContainer = xkbSeedContainer != null,
                            onConnectionChanged = { connected = it }
                        )
                    }
                }

                if (additionalKeysEnabled && additionalKeysVisible) {
                    EmbeddedExtraKeysBar(
                        config = extraKeysConfig,
                        onOpenSettings = { showConfiguration = true },
                        onExitDisplay = ::closeScreen
                    )
                }
            }

            AnimatedVisibility(
                visible = fullscreen && fullscreenControlVisible,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.58f),
                    tonalElevation = 0.dp
                ) {
                    IconButton(
                        onClick = { setFullscreen(false) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.FullscreenExit,
                            contentDescription = "Exit fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }

    if (showConfiguration) {
        X11ConfigurationDialog(
            store = store,
            onDismiss = { showConfiguration = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagedDisplayTopBar(
    monitor: X11MonitorInfo?,
    slot: X11DisplaySlot,
    serverStatus: X11ServerStatus,
    serverPid: Int?,
    connected: Boolean,
    hasLogs: Boolean,
    additionalKeysEnabled: Boolean,
    additionalKeysVisible: Boolean,
    onClose: () -> Unit,
    onShowLogs: () -> Unit,
    onToggleAdditionalKeys: () -> Unit,
    onFullscreen: () -> Unit,
    onConfiguration: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "X11 Screen",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = buildList {
                        add("Monitor ${slot.monitorNumber}")
                        add(slot.displayName)
                        add(
                            when {
                                connected -> "connected"
                                serverStatus == X11ServerStatus.Running -> "running"
                                else -> "stopped"
                            }
                        )
                        monitor?.containerName?.let(::add)
                        serverPid?.let { add("PID $it") }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close screen")
            }
        },
        actions = {
            if (hasLogs) {
                IconButton(onClick = onShowLogs) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = "X11 logs")
                }
            }
            if (additionalKeysEnabled) {
                IconButton(
                    onClick = onToggleAdditionalKeys,
                    enabled = serverStatus == X11ServerStatus.Running
                ) {
                    Icon(
                        Icons.Default.Keyboard,
                        contentDescription = "Additional key bar",
                        tint = if (additionalKeysVisible) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            IconButton(
                onClick = onFullscreen,
                enabled = serverStatus == X11ServerStatus.Running
            ) {
                Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen")
            }
            IconButton(onClick = onConfiguration) {
                Icon(Icons.Default.Settings, contentDescription = "X11 configuration")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun MonitorDeck(
    monitors: List<X11MonitorInfo>,
    selectedDisplayNumber: Int,
    busyDisplayNumber: Int?,
    canStartStopped: Boolean,
    onCreate: () -> Unit,
    onSelect: (X11DisplaySlot) -> Unit,
    onToggle: (X11MonitorInfo) -> Unit,
    onDelete: (X11MonitorInfo) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Monitors",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${monitors.count { it.status == X11ServerStatus.Running }} active",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = onCreate,
                enabled = busyDisplayNumber == null,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create monitor")
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(monitors, key = { it.slot.number }) { monitor ->
                MonitorCard(
                    monitor = monitor,
                    selected = monitor.slot.number == selectedDisplayNumber,
                    busy = busyDisplayNumber == monitor.slot.number,
                    interactionsLocked = busyDisplayNumber != null,
                    canStart = monitor.status == X11ServerStatus.Running || canStartStopped,
                    onSelect = { onSelect(monitor.slot) },
                    onToggle = { onToggle(monitor) },
                    onDelete = { onDelete(monitor) }
                )
            }
        }
    }
}

@Composable
private fun MonitorCard(
    monitor: X11MonitorInfo,
    selected: Boolean,
    busy: Boolean,
    interactionsLocked: Boolean,
    canStart: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val running = monitor.status == X11ServerStatus.Running
    val canDelete =
        monitor.slot.number > 0 && !running && monitor.containerName == null && !interactionsLocked

    Surface(
        modifier = Modifier
            .width(204.dp)
            .clickable(enabled = !interactionsLocked, onClick = onSelect),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Monitor ${monitor.monitorNumber}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = monitor.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Reserve the same action footprint in every state so stopped
                // cards keep exactly the same geometry as running cards.
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (canDelete) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Delete monitor",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Text(
                text = when {
                    monitor.containerName != null -> monitor.containerName
                    running -> "X11 server running"
                    else -> "Available"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = monitor.pid?.let { pid ->
                    "PID $pid · ${monitor.slot.processName}"
                } ?: "${monitor.slot.processName} · idle",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (running) "Running" else "Stopped",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (running) {
                    OutlinedButton(
                        onClick = onToggle,
                        enabled = !interactionsLocked,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(7.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Stop", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = onToggle,
                        enabled = !interactionsLocked && canStart,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(7.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Start", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagedDisplayViewport(
    slot: X11DisplaySlot,
    serverStatus: X11ServerStatus,
    connected: Boolean,
    hasSeedContainer: Boolean,
    onConnectionChanged: (Boolean) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        if (serverStatus == X11ServerStatus.Running) {
            key("managed-display-lorie-surface") {
                EmbeddedX11Surface(
                    displayName = slot.displayName,
                    modifier = Modifier.fillMaxSize(),
                    onConnectionChanged = onConnectionChanged
                )
            }
            if (!connected) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        "Connecting to ${slot.describe()}",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Monitor ${slot.monitorNumber} is stopped",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (!hasSeedContainer) {
                        "Create a container before the first embedded X11 start."
                    } else {
                        "Start ${slot.displayName} from its monitor card."
                    },
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DisplayErrorMessage(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun readKnownMonitorSlots(store: SharedPreferences): Set<Int> =
    buildSet {
        add(0)
        store.getStringSet(PREF_KNOWN_MONITOR_SLOTS, emptySet())
            .orEmpty()
            .mapNotNullTo(this) { it.toIntOrNull()?.takeIf { number -> number >= 0 } }
    }

private tailrec fun Context.findManagedDisplayActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findManagedDisplayActivity()
    else -> null
}
