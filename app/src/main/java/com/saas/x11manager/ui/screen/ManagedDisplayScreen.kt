package com.saas.x11manager.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.ContainerInfo
import com.saas.x11manager.util.X11DisplaySlot
import com.saas.x11manager.util.X11MonitorInfo
import com.saas.x11manager.util.X11ServerStatus
import com.saas.x11manager.util.X11SessionManager
import com.termux.x11.EmbeddedDisplayHost
import kotlinx.coroutines.launch

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
    var selectedDisplayNumber by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
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

    suspend fun refreshMonitors() {
        val live = X11SessionManager.getMonitors()
        monitors = live
        when {
            live.isEmpty() -> selectedDisplayNumber = 0
            live.none { it.slot.number == selectedDisplayNumber } -> {
                connected = false
                selectedDisplayNumber = live.first().slot.number
            }
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

    fun toggleServer() {
        scope.launch {
            busy = true
            message = null
            try {
                if (selectedStatus == X11ServerStatus.Running) {
                    if (!X11SessionManager.stopIntegratedServer(selectedSlot)) {
                        message = "${selectedSlot.describe()} could not be stopped"
                    }
                    connected = false
                    if (fullscreen) setFullscreen(false)
                } else {
                    val started = X11SessionManager.startIntegratedServer(
                        displaySlot = selectedSlot,
                        containerName = xkbSeedContainer?.name
                    )
                    if (started.isFailure) {
                        message = started.exceptionOrNull()?.message
                            ?: "${selectedSlot.describe()} could not start"
                    }
                }
            } finally {
                viewModel.refreshRuntimeState()
                refreshMonitors()
                busy = false
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
        color = Color(0xFF07090C)
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
                ManagedDisplayToolbar(
                    monitor = selectedMonitor,
                    slot = selectedSlot,
                    serverStatus = selectedStatus,
                    serverPid = selectedPid,
                    connected = connected,
                    busy = busy,
                    xkbSeedContainer = xkbSeedContainer,
                    additionalKeysEnabled = additionalKeysEnabled,
                    additionalKeysVisible = additionalKeysVisible,
                    onClose = ::closeScreen,
                    onToggleServer = ::toggleServer,
                    onToggleAdditionalKeys = ::toggleAdditionalKeys,
                    onFullscreen = { setFullscreen(true) },
                    onConfiguration = { showConfiguration = true }
                )

                MonitorSelector(
                    monitors = monitors,
                    selectedDisplayNumber = selectedDisplayNumber,
                    onSelect = ::selectMonitor
                )
            }

            message?.let { DisplayErrorMessage(it) }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(
                        if (fullscreen) Modifier
                        else Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(18.dp),
                    color = Color.Black,
                    border = if (fullscreen) {
                        null
                    } else {
                        BorderStroke(
                            1.dp,
                            if (connected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                            } else {
                                Color.White.copy(alpha = 0.12f)
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

@Composable
private fun ManagedDisplayToolbar(
    monitor: X11MonitorInfo?,
    slot: X11DisplaySlot,
    serverStatus: X11ServerStatus,
    serverPid: Int?,
    connected: Boolean,
    busy: Boolean,
    xkbSeedContainer: ContainerInfo?,
    additionalKeysEnabled: Boolean,
    additionalKeysVisible: Boolean,
    onClose: () -> Unit,
    onToggleServer: () -> Unit,
    onToggleAdditionalKeys: () -> Unit,
    onFullscreen: () -> Unit,
    onConfiguration: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0E1116),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close screen",
                    tint = Color.White
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Monitor ${slot.monitorNumber} · ${slot.displayName}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    DisplayStatusPill(serverStatus, connected)
                }
                val details = buildList {
                    monitor?.containerName?.let { add(it) }
                    serverPid?.let { add("PID $it") }
                    if (isEmpty()) add("Embedded X11 workspace")
                }.joinToString(" · ")
                Text(
                    details,
                    color = Color.White.copy(alpha = 0.52f),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            DisplayControlButton(
                busy = busy,
                running = serverStatus == X11ServerStatus.Running,
                enabled = !busy && (
                    serverStatus == X11ServerStatus.Running || xkbSeedContainer != null
                ),
                onClick = onToggleServer
            )

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
                            Color.White
                        }
                    )
                }
            }

            IconButton(
                onClick = onFullscreen,
                enabled = serverStatus == X11ServerStatus.Running
            ) {
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = if (serverStatus == X11ServerStatus.Running) {
                        Color.White
                    } else {
                        Color.White.copy(alpha = 0.3f)
                    }
                )
            }

            IconButton(onClick = onConfiguration) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "X11 configuration",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun MonitorSelector(
    monitors: List<X11MonitorInfo>,
    selectedDisplayNumber: Int,
    onSelect: (X11DisplaySlot) -> Unit
) {
    val choices = if (monitors.isEmpty()) {
        listOf(
            X11MonitorInfo(
                slot = X11DisplaySlot(0),
                status = X11ServerStatus.Stopped,
                pid = null
            )
        )
    } else {
        monitors.sortedBy { it.slot.number }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEach { monitor ->
            val selected = monitor.slot.number == selectedDisplayNumber
            Surface(
                modifier = Modifier
                    .widthIn(min = 140.dp)
                    .clickable { onSelect(monitor.slot) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                } else {
                    Color(0xFF12161C)
                },
                border = BorderStroke(
                    1.dp,
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.12f)
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "Monitor ${monitor.monitorNumber} · ${monitor.displayName}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(
                        monitor.containerName
                            ?: if (monitor.status == X11ServerStatus.Running) "X11 server" else "Available",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun DisplayStatusPill(serverStatus: X11ServerStatus, connected: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = when {
            serverStatus != X11ServerStatus.Running -> Color(0xFF2B2F36)
            connected -> Color(0xFF163B2C)
            else -> Color(0xFF3A3217)
        }
    ) {
        Text(
            when {
                serverStatus != X11ServerStatus.Running -> "Stopped"
                connected -> "Connected"
                else -> "Connecting"
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall
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
                        "Connecting to Monitor ${slot.monitorNumber} (${slot.displayName})",
                        color = Color.White.copy(alpha = 0.68f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Monitor ${slot.monitorNumber} stopped",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (!hasSeedContainer) {
                        "Create a container before starting the embedded X11 server."
                    } else {
                        "Use the play button above to start ${slot.displayName}."
                    },
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DisplayErrorMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DisplayControlButton(
    busy: Boolean,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
        } else {
            Icon(
                if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (running) "Stop monitor" else "Start monitor",
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f)
            )
        }
    }
}

private tailrec fun Context.findManagedDisplayActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findManagedDisplayActivity()
    else -> null
}
