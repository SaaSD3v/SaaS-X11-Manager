package com.saas.x11manager.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saas.x11manager.X11Application
import com.saas.x11manager.ui.component.OperationResultCard
import com.saas.x11manager.ui.component.TerminalDialog
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.ContainerInfo
import com.saas.x11manager.util.X11ServerStatus
import com.saas.x11manager.util.X11SessionManager
import com.termux.x11.EmbeddedDisplayHost

/**
 * Current X11APP display workspace adapted to the X11-0nly contract.
 *
 * The visual model intentionally stays the same: an app top bar, a monitor deck,
 * a monitor card, operation logs and the managed viewport. X11-0nly exposes only
 * Monitor 1 on :0/X0, so there is no create/delete/switch UI and no secondary
 * Manager display lifecycle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagedDisplayScreen(
    viewModel: HomeViewModel,
    onClose: () -> Unit,
    displayViewModel: ManagedDisplayViewModel = androidx.lifecycle.viewmodel.compose.viewModel(viewModelStoreOwner = X11Application.instance)
) {
    val serverStatus by viewModel.x11ServerStatus.collectAsState()
    val serverPid by viewModel.x11ServerPid.collectAsState()
    val containers by viewModel.containers.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findManagedDisplayActivity() }
    val prefs = remember(context) { EmbeddedDisplayHost.getPrefs(context) }
    val store = remember(prefs) { prefs.get() }
    val xkbSeedContainer = containers.firstOrNull { it.isRunning } ?: containers.firstOrNull()

    val busy = displayViewModel.busy
    val message = displayViewModel.message
    var connected by remember { mutableStateOf(false) }
    var ownerName by remember { mutableStateOf<String?>(null) }
    var showConfiguration by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var showFullscreenExitConfirmation by remember { mutableStateOf(false) }
    var additionalKeysEnabled by remember {
        mutableStateOf(store.getBoolean(PREF_SHOW_ADDITIONAL_KEYS, false))
    }
    var additionalKeysVisible by remember { mutableStateOf(false) }
    var extraKeysConfig by remember { mutableStateOf(store.getString("extra_keys_config", null)) }

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
        if (value && serverStatus != X11ServerStatus.Running) return
        fullscreen = value
        if (!value) showFullscreenExitConfirmation = false
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
        viewModel.refreshRuntimeState()
        ownerName = X11SessionManager.getOwnerContainerName()
    }

    LaunchedEffect(serverStatus, containers) {
        if (serverStatus != X11ServerStatus.Running) connected = false
        ownerName = X11SessionManager.getOwnerContainerName()
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
                    fullscreen = requested && serverStatus == X11ServerStatus.Running
                    if (!fullscreen) showFullscreenExitConfirmation = false
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

    LaunchedEffect(serverStatus, fullscreen) {
        if (fullscreen && serverStatus != X11ServerStatus.Running) {
            setFullscreen(false)
        }
    }

    BackHandler {
        if (fullscreen) {
            showFullscreenExitConfirmation = true
        } else {
            onClose()
        }
    }

    if (showFullscreenExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showFullscreenExitConfirmation = false },
            title = { Text("Exit fullscreen?") },
            text = {
                Text("Return to the X11 monitor controls while keeping Monitor 1 running?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFullscreenExitConfirmation = false
                        setFullscreen(false)
                    }
                ) {
                    Text("Exit fullscreen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullscreenExitConfirmation = false }) {
                    Text("Stay fullscreen")
                }
            }
        )
    }

    if (displayViewModel.showMonitorLogs) {
        TerminalDialog(
            title = displayViewModel.logOperation.title,
            logs = displayViewModel.logOperation.logs,
            onDismiss = displayViewModel::dismissLogs,
            onMinimize = displayViewModel::minimizeLogs,
            onClear = displayViewModel::clearLogs,
            isBlocking = busy
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (fullscreen) Color.Black else MaterialTheme.colorScheme.surface
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
                FixedDisplayTopBar(
                    serverStatus = serverStatus,
                    serverPid = serverPid,
                    connected = connected,
                    ownerName = ownerName,
                    additionalKeysEnabled = additionalKeysEnabled,
                    additionalKeysVisible = additionalKeysVisible,
                    onClose = ::closeScreen,
                    onShowLogs = displayViewModel::openLogs,
                    onToggleAdditionalKeys = ::toggleAdditionalKeys,
                    onFullscreen = { setFullscreen(true) },
                    onConfiguration = { showConfiguration = true }
                )

                FixedMonitorDeck(
                    serverStatus = serverStatus,
                    serverPid = serverPid,
                    ownerName = ownerName,
                    busy = busy || viewModel.hasRunningOperations,
                    canStartStopped = xkbSeedContainer != null,
                    onToggle = { displayViewModel.toggleServer(xkbSeedContainer?.name) }
                )
            }

            if (!fullscreen) {
                OperationResultCard(displayViewModel.logOperation, displayViewModel::openLogs)
            }
            message?.let { DisplayErrorMessage(it) }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(
                        if (fullscreen) Modifier
                        else Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(8.dp),
                    color = Color.Black,
                    tonalElevation = 0.dp,
                    border = if (fullscreen) {
                        null
                    } else {
                        BorderStroke(
                            1.dp,
                            if (connected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            }
                        )
                    }
                ) {
                    ManagedDisplayViewport(
                        serverStatus = serverStatus,
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
private fun FixedDisplayTopBar(
    serverStatus: X11ServerStatus,
    serverPid: Int?,
    connected: Boolean,
    ownerName: String?,
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
                        add("Monitor 1")
                        add(Constants.X11_DISPLAY)
                        add(
                            when {
                                connected -> "connected"
                                serverStatus == X11ServerStatus.Running -> "running"
                                else -> "stopped"
                            }
                        )
                        ownerName?.let(::add)
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
            IconButton(onClick = onShowLogs) {
                Icon(Icons.Default.ReceiptLong, contentDescription = "Monitor logs")
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
                            MaterialTheme.colorScheme.primary
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
private fun FixedMonitorDeck(
    serverStatus: X11ServerStatus,
    serverPid: Int?,
    ownerName: String?,
    busy: Boolean,
    canStartStopped: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "X11 display",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (serverStatus == X11ServerStatus.Running) "Active" else "Inactive",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            FixedMonitorCard(
                serverStatus = serverStatus,
                serverPid = serverPid,
                ownerName = ownerName,
                busy = busy,
                canStart = canStartStopped,
                onToggle = onToggle
            )
        }
    }
}

@Composable
private fun FixedMonitorCard(
    serverStatus: X11ServerStatus,
    serverPid: Int?,
    ownerName: String?,
    busy: Boolean,
    canStart: Boolean,
    onToggle: () -> Unit
) {
    val running = serverStatus == X11ServerStatus.Running

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = if (running) 0.65f else 0.28f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Monitor 1",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = Constants.X11_DISPLAY,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (running) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {
                    Text(
                        text = if (running) "Running" else "Stopped",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (running) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Text(
                text = when {
                    ownerName != null -> ownerName
                    running -> "X11 server running"
                    else -> "Available"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            serverPid?.let {
                Text(
                    text = "PID $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (running) {
                OutlinedButton(
                    onClick = onToggle,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
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
                    enabled = !busy && canStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
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

@Composable
private fun ManagedDisplayViewport(
    serverStatus: X11ServerStatus,
    connected: Boolean,
    hasSeedContainer: Boolean,
    onConnectionChanged: (Boolean) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        if (serverStatus == X11ServerStatus.Running) {
            key("managed-display-lorie-surface") {
                EmbeddedX11Surface(
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
                        "Connecting to ${Constants.X11_DISPLAY}",
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
                    "Monitor 1 stopped",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (!hasSeedContainer) {
                        "Create a container before starting the embedded X11 server."
                    } else {
                        "Use the Monitor 1 card above to start ${Constants.X11_DISPLAY}."
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 0.dp
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private tailrec fun Context.findManagedDisplayActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findManagedDisplayActivity()
    else -> null
}
