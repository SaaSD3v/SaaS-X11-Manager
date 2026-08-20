package com.saas.x11manager.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.ContainerInfo
import com.saas.x11manager.util.X11DisplayAllocator
import com.saas.x11manager.util.X11DisplaySlot
import com.saas.x11manager.util.X11MonitorInfo
import com.saas.x11manager.util.X11ServerStatus
import com.saas.x11manager.util.X11SessionManager
import com.termux.x11.EmbeddedDisplayHost
import kotlinx.coroutines.launch

private const val PREF_KNOWN_MONITOR_SLOTS = "saas_known_monitor_slots"

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
    var additionalKeysEnabled by remember {
        mutableStateOf(store.getBoolean(PREF_SHOW_ADDITIONAL_KEYS, false))
    }
    var additionalKeysVisible by remember { mutableStateOf(false) }
    var extraKeysConfig by remember { mutableStateOf(store.getString("extra_keys_config", null)) }

    val selectedMonitor = monitors.firstOrNull { it.slot.number == selectedDisplayNumber }
    val selectedSlot = selectedMonitor?.slot ?: X11DisplaySlot(selectedDisplayNumber)
    val selectedStatus = selectedMonitor?.status ?: X11ServerStatus.Stopped
    val selectedPid = selectedMonitor?.pid

    fun persistKnownDisplayNumbers(numbers: Set<Int>) {
        val sanitized = numbers.filter { it >= 0 }.toSortedSet()
        knownDisplayNumbers = sanitized
        store.edit()
            .putStringSet(PREF_KNOWN_MONITOR_SLOTS, sanitized.map(Int::toString).toSet())
            .apply()
    }

    suspend fun refreshMonitors() {
        val live = X11SessionManager.getMonitors()
        val liveByNumber = live.associateBy { it.slot.number }
        val durableNumbers = buildSet {
            add(0)
            addAll(knownDisplayNumbers)
            addAll(liveByNumber.keys)
        }
        if (durableNumbers != knownDisplayNumbers) {
            persistKnownDisplayNumbers(durableNumbers)
        }

        val nextAvailable = X11DisplayAllocator.firstFree(durableNumbers).number
        val visibleNumbers = (durableNumbers + nextAvailable).sorted()
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
            selectedDisplayNumber = visibleNumbers.firstOrNull() ?: 0
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

    fun toggleMonitor(monitor: X11MonitorInfo) {
        if (busyDisplayNumber != null) return
        selectMonitor(monitor.slot)
        scope.launch {
            busyDisplayNumber = monitor.slot.number
            message = null
            try {
                if (monitor.status == X11ServerStatus.Running) {
                    if (!X11SessionManager.stopIntegratedServer(monitor.slot)) {
                        message = "${monitor.slot.describe()} could not be stopped"
                    }
                    connected = false
                    if (fullscreen && monitor.slot.number == selectedDisplayNumber) {
                        setFullscreen(false)
                    }
                } else {
                    val seed = monitor.containerName ?: xkbSeedContainer?.name
                    val started = X11SessionManager.startIntegratedServer(
                        displaySlot = monitor.slot,
                        containerName = seed
                    )
                    if (started.isSuccess) {
                        persistKnownDisplayNumbers(knownDisplayNumbers + monitor.slot.number)
                    } else {
                        message = started.exceptionOrNull()?.message
                            ?: "${monitor.slot.describe()} could not start"
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
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
                    additionalKeysEnabled = additionalKeysEnabled,
                    additionalKeysVisible = additionalKeysVisible,
                    onClose = ::closeScreen,
                    onToggleAdditionalKeys = ::toggleAdditionalKeys,
                    onFullscreen = { setFullscreen(true) },
                    onConfiguration = { showConfiguration = true }
                )

                MonitorDeck(
                    monitors = monitors,
                    selectedDisplayNumber = selectedDisplayNumber,
                    busyDisplayNumber = busyDisplayNumber,
                    canStartStopped = xkbSeedContainer != null,
                    onSelect = ::selectMonitor,
                    onToggle = ::toggleMonitor
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
                    shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp),
                    color = Color.Black,
                    border = if (fullscreen) {
                        null
                    } else {
                        BorderStroke(
                            1.dp,
                            if (connected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                            }
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
    additionalKeysEnabled: Boolean,
    additionalKeysVisible: Boolean,
    onClose: () -> Unit,
    onToggleAdditionalKeys: () -> Unit,
    onFullscreen: () -> Unit,
    onConfiguration: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "Monitor ${slot.monitorNumber}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = buildList {
                        add(slot.displayName)
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
            MonitorStatusBadge(serverStatus = serverStatus, connected = connected)
            if (additionalKeysEnabled) {
                IconButton(
                    onClick = onToggleAdditionalKeys,
                    enabled = serverStatus == X11ServerStatus.Running
                ) {
                    Icon(
                        Icons.Default.Keyboard,
                        contentDescription = "Additional key bar",
                        tint = if (additionalKeysVisible) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
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
    onSelect: (X11DisplaySlot) -> Unit,
    onToggle: (X11MonitorInfo) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Monitors",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${monitors.count { it.status == X11ServerStatus.Running }} active",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(monitors, key = { it.slot.number }) { monitor ->
                MonitorCard(
                    monitor = monitor,
                    selected = monitor.slot.number == selectedDisplayNumber,
                    busy = busyDisplayNumber == monitor.slot.number,
                    interactionsLocked = busyDisplayNumber != null,
                    canStart = monitor.status == X11ServerStatus.Running || canStartStopped,
                    onSelect = { onSelect(monitor.slot) },
                    onToggle = { onToggle(monitor) }
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
    onToggle: () -> Unit
) {
    val running = monitor.status == X11ServerStatus.Running
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }

    Surface(
        modifier = Modifier
            .width(220.dp)
            .clickable(enabled = !interactionsLocked, onClick = onSelect),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        tonalElevation = if (selected) 4.dp else 1.dp,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Monitor ${monitor.monitorNumber}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = monitor.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalIconButton(
                    onClick = onToggle,
                    enabled = !interactionsLocked && canStart
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (running) "Stop monitor" else "Start monitor"
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            color = if (running) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape
                        )
                )
                Text(
                    text = when {
                        monitor.containerName != null -> monitor.containerName
                        running -> "X11 server running"
                        else -> "Available to start"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            monitor.pid?.let { pid ->
                Text(
                    text = "PID $pid",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MonitorStatusBadge(
    serverStatus: X11ServerStatus,
    connected: Boolean
) {
    val running = serverStatus == X11ServerStatus.Running
    Surface(
        modifier = Modifier.padding(end = 4.dp),
        shape = CircleShape,
        color = when {
            connected -> MaterialTheme.colorScheme.primaryContainer
            running -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        }
    ) {
        Text(
            text = when {
                connected -> "Connected"
                running -> "Running"
                else -> "Stopped"
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = when {
                connected -> MaterialTheme.colorScheme.onPrimaryContainer
                running -> MaterialTheme.colorScheme.onTertiaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
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
                    CircularProgressIndicator()
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
                        "Use the Play button on this monitor card to start ${slot.displayName}."
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
