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
import androidx.compose.ui.unit.dp
import com.saas.x11manager.ui.component.TerminalDialog
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.ContainerInfo
import com.saas.x11manager.util.ViewModelLogger
import com.saas.x11manager.util.X11ServerStatus
import com.saas.x11manager.util.X11SessionManager
import com.termux.x11.EmbeddedDisplayHost
import kotlinx.coroutines.launch

/**
 * Single-display version of the current X11APP managed monitor workspace.
 * X11-0nly deliberately owns only Monitor 1 / :0 / X0.
 */
@Composable
fun ManagedDisplayScreen(
    viewModel: HomeViewModel,
    onClose: () -> Unit
) {
    val serverStatus by viewModel.x11ServerStatus.collectAsState()
    val serverPid by viewModel.x11ServerPid.collectAsState()
    val containers by viewModel.containers.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findManagedDisplayActivity() }
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { EmbeddedDisplayHost.getPrefs(context) }
    val store = remember(prefs) { prefs.get() }
    val xkbSeedContainer = containers.firstOrNull { it.isRunning } ?: containers.firstOrNull()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var showConfiguration by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var showFullscreenExitConfirmation by remember { mutableStateOf(false) }
    var additionalKeysEnabled by remember {
        mutableStateOf(store.getBoolean(PREF_SHOW_ADDITIONAL_KEYS, false))
    }
    var additionalKeysVisible by remember { mutableStateOf(false) }
    var extraKeysConfig by remember { mutableStateOf(store.getString("extra_keys_config", null)) }

    val monitorLogs = remember { mutableStateListOf<Pair<Int, String>>() }
    var showMonitorLogs by remember { mutableStateOf(false) }
    var monitorLogTitle by remember { mutableStateOf("Monitor 1 logs") }

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

    fun operationLogger(): ViewModelLogger = ViewModelLogger { level, line ->
        scope.launch { monitorLogs.add(level to line) }
    }

    fun toggleServer() {
        if (busy) return
        monitorLogs.clear()
        monitorLogTitle = if (serverStatus == X11ServerStatus.Running) {
            "Stopping Monitor 1"
        } else {
            "Starting Monitor 1"
        }
        showMonitorLogs = true
        val logger = operationLogger()

        scope.launch {
            busy = true
            message = null
            try {
                if (serverStatus == X11ServerStatus.Running) {
                    logger.i("--- Stopping X11 monitor ---")
                    logger.i("[CTX] Monitor: 1")
                    logger.i("[CTX] Display: ${Constants.X11_DISPLAY}")
                    serverPid?.let { logger.i("[CTX] Live PIDs before stop: $it") }

                    val owner = X11SessionManager.getOwnerContainerName()
                    owner?.let {
                        logger.i("[CTX] Container owner: $it")
                        X11SessionManager.stopContainerGraphicSession(it, logger)
                    }

                    val stopped = X11SessionManager.stopIntegratedServer(logger)
                    if (!stopped) {
                        message = "Monitor 1 could not be stopped"
                        logger.e("[-] Monitor 1 (${Constants.X11_DISPLAY}) stop failed")
                    } else {
                        connected = false
                        logger.i("[+] Monitor 1 (${Constants.X11_DISPLAY}) fully stopped")
                        owner?.let { logger.i("[+] Container '$it' was left running") }
                        if (fullscreen) setFullscreen(false)
                    }
                } else {
                    logger.i("--- Starting X11 monitor ---")
                    logger.i("[CTX] Monitor: 1")
                    logger.i("[CTX] Display: ${Constants.X11_DISPLAY}")
                    logger.i("[CTX] Process: ${Constants.X11_SERVER_PROCESS}")
                    logger.i("[CTX] Runtime: ${Constants.INTEGRATED_X11_RUNTIME_DIR}")
                    logger.i("[CTX] Socket: ${Constants.X11_SOCK_FILE}")

                    val started = X11SessionManager.startIntegratedServer(
                        containerName = xkbSeedContainer?.name,
                        logger = logger
                    )
                    if (started.isSuccess) {
                        logger.i("[+] Monitor 1 (${Constants.X11_DISPLAY}) is ready")
                        logger.i("[+] PID: ${started.getOrNull()}")
                    } else {
                        message = started.exceptionOrNull()?.message
                            ?: "Monitor 1 could not start"
                        logger.e("[-] ${message ?: "X11 start failed"}")
                    }
                }
            } finally {
                busy = false
                viewModel.refreshRuntimeState()
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
        viewModel.refreshRuntimeState()
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
                Text("Return to the Monitor 1 controls while keeping ${Constants.X11_DISPLAY} running?")
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

    if (showMonitorLogs) {
        TerminalDialog(
            title = monitorLogTitle,
            logs = monitorLogs,
            onDismiss = { if (!busy) showMonitorLogs = false },
            onClear = { if (!busy) monitorLogs.clear() },
            isBlocking = busy
        )
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
                SingleMonitorToolbar(
                    serverStatus = serverStatus,
                    serverPid = serverPid,
                    connected = connected,
                    busy = busy,
                    xkbSeedContainer = xkbSeedContainer,
                    additionalKeysEnabled = additionalKeysEnabled,
                    additionalKeysVisible = additionalKeysVisible,
                    onClose = ::closeScreen,
                    onShowLogs = { showMonitorLogs = true },
                    onToggleServer = ::toggleServer,
                    onToggleAdditionalKeys = ::toggleAdditionalKeys,
                    onFullscreen = { setFullscreen(true) },
                    onConfiguration = { showConfiguration = true }
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

@Composable
private fun SingleMonitorToolbar(
    serverStatus: X11ServerStatus,
    serverPid: Int?,
    connected: Boolean,
    busy: Boolean,
    xkbSeedContainer: ContainerInfo?,
    additionalKeysEnabled: Boolean,
    additionalKeysVisible: Boolean,
    onClose: () -> Unit,
    onShowLogs: () -> Unit,
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
                Icon(Icons.Default.Close, contentDescription = "Close screen", tint = Color.White)
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
                        "Monitor 1 · ${Constants.X11_DISPLAY}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    DisplayStatusPill(serverStatus, connected)
                }
                Text(
                    serverPid?.let { "PID $it" } ?: "Single embedded X11 display",
                    color = Color.White.copy(alpha = 0.52f),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            IconButton(onClick = onShowLogs) {
                Icon(Icons.Default.ReceiptLong, contentDescription = "Monitor logs", tint = Color.White)
            }

            IconButton(
                onClick = onToggleServer,
                enabled = !busy && (
                    serverStatus == X11ServerStatus.Running || xkbSeedContainer != null
                )
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    val enabled = serverStatus == X11ServerStatus.Running || xkbSeedContainer != null
                    Icon(
                        if (serverStatus == X11ServerStatus.Running) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (serverStatus == X11ServerStatus.Running) "Stop monitor" else "Start monitor",
                        tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f)
                    )
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
                        tint = if (additionalKeysVisible) MaterialTheme.colorScheme.primary else Color.White
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
                    tint = if (serverStatus == X11ServerStatus.Running) Color.White else Color.White.copy(alpha = 0.3f)
                )
            }

            IconButton(onClick = onConfiguration) {
                Icon(Icons.Default.Settings, contentDescription = "X11 configuration", tint = Color.White)
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
                        "Use the play button above to start ${Constants.X11_DISPLAY}."
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

private tailrec fun Context.findManagedDisplayActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findManagedDisplayActivity()
    else -> null
}
