package com.saas.x11manager.ui.screen

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.LoaderStatus
import com.saas.x11manager.util.X11SessionManager
import com.termux.x11.EmbeddedDisplayHost
import kotlinx.coroutines.launch

@Composable
fun ManagedDisplayScreen(
    viewModel: HomeViewModel,
    onClose: () -> Unit,
    onFullscreen: () -> Unit
) {
    val serverStatus by viewModel.loaderStatus.collectAsState()
    val serverPid by viewModel.loaderPid.collectAsState()
    val containers by viewModel.containers.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { EmbeddedDisplayHost.getPrefs(context) }
    val store = remember(prefs) { prefs.get() }
    val xkbSeedContainer = containers.firstOrNull { it.isRunning } ?: containers.firstOrNull()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var showConfiguration by remember { mutableStateOf(false) }
    var additionalKeysEnabled by remember {
        mutableStateOf(store.getBoolean(PREF_SHOW_ADDITIONAL_KEYS, false))
    }
    var additionalKeysVisible by remember { mutableStateOf(false) }
    var extraKeysConfig by remember { mutableStateOf(store.getString("extra_keys_config", null)) }

    fun publishAdditionalKeysVisible() {
        store.edit().putBoolean(PREF_ADDITIONAL_KEYS_VISIBLE, additionalKeysVisible).apply()
        publishLoriePreferenceChange(context, PREF_ADDITIONAL_KEYS_VISIBLE)
    }

    fun toggleAdditionalKeys() {
        additionalKeysVisible = !additionalKeysVisible
        publishAdditionalKeysVisible()
    }

    fun enterFullscreen() {
        if (serverStatus != LoaderStatus.Running) return
        store.edit()
            .putBoolean(PREF_FULLSCREEN, true)
            .putBoolean(PREF_ADDITIONAL_KEYS_VISIBLE, additionalKeysVisible)
            .apply()
        publishLoriePreferenceChange(context, PREF_FULLSCREEN)
        publishLoriePreferenceChange(context, PREF_ADDITIONAL_KEYS_VISIBLE)
        onFullscreen()
    }

    LaunchedEffect(store) {
        ensureManagedX11Defaults(context, store)
        additionalKeysEnabled = store.getBoolean(PREF_SHOW_ADDITIONAL_KEYS, false)
        additionalKeysVisible = false
        publishAdditionalKeysVisible()
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
                "extra_keys_config" -> {
                    extraKeysConfig = store.getString("extra_keys_config", null)
                }
            }
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    BackHandler(onBack = onClose)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF07090C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
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
                                "Display ${Constants.X11_DISPLAY}",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = when {
                                    serverStatus != LoaderStatus.Running -> Color(0xFF2B2F36)
                                    connected -> Color(0xFF163B2C)
                                    else -> Color(0xFF3A3217)
                                }
                            ) {
                                Text(
                                    when {
                                        serverStatus != LoaderStatus.Running -> "Stopped"
                                        connected -> "Connected"
                                        else -> "Connecting"
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    color = Color.White.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        Text(
                            serverPid?.let { "PID $it" } ?: "Embedded X11 workspace",
                            color = Color.White.copy(alpha = 0.52f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    IconButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                message = null
                                try {
                                    if (serverStatus == LoaderStatus.Running) {
                                        if (!X11SessionManager.stopIntegratedServer()) {
                                            message = "Integrated X11 server could not be stopped"
                                        }
                                        connected = false
                                    } else {
                                        val started = X11SessionManager.startIntegratedServer(xkbSeedContainer?.name)
                                        if (started.isFailure) {
                                            message = started.exceptionOrNull()?.message
                                                ?: "Integrated X11 server could not start"
                                        }
                                    }
                                } finally {
                                    busy = false
                                    viewModel.refreshRuntimeState()
                                }
                            }
                        },
                        enabled = !busy && (serverStatus == LoaderStatus.Running || xkbSeedContainer != null)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                if (serverStatus == LoaderStatus.Running) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (serverStatus == LoaderStatus.Running) "Stop display" else "Start display",
                                tint = Color.White
                            )
                        }
                    }

                    if (additionalKeysEnabled) {
                        IconButton(
                            onClick = ::toggleAdditionalKeys,
                            enabled = serverStatus == LoaderStatus.Running
                        ) {
                            Icon(
                                Icons.Default.Keyboard,
                                contentDescription = "Additional key bar",
                                tint = if (additionalKeysVisible) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                    }

                    IconButton(
                        onClick = ::enterFullscreen,
                        enabled = serverStatus == LoaderStatus.Running
                    ) {
                        Icon(
                            Icons.Default.Fullscreen,
                            contentDescription = "Fullscreen",
                            tint = if (serverStatus == LoaderStatus.Running) Color.White else Color.White.copy(alpha = 0.3f)
                        )
                    }

                    IconButton(onClick = { showConfiguration = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "X11 configuration",
                            tint = Color.White
                        )
                    }
                }
            }

            if (message != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        message.orEmpty(),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black,
                    border = BorderStroke(
                        1.dp,
                        if (connected) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        else Color.White.copy(alpha = 0.12f)
                    )
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (serverStatus == LoaderStatus.Running) {
                            EmbeddedX11Surface(
                                modifier = Modifier.fillMaxSize(),
                                onConnectionChanged = { connected = it }
                            )
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
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Display stopped",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (xkbSeedContainer == null)
                                        "Create a container before starting the embedded X11 server."
                                    else
                                        "Use the play button above to start ${Constants.X11_DISPLAY}.",
                                    color = Color.White.copy(alpha = 0.58f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            if (additionalKeysEnabled && additionalKeysVisible) {
                EmbeddedExtraKeysBar(
                    config = extraKeysConfig,
                    onOpenSettings = { showConfiguration = true },
                    onExitDisplay = onClose
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
