package com.saas.x11manager.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.LoaderStatus
import com.saas.x11manager.util.X11SessionManager
import com.termux.x11.EmbeddedDisplayHost
import com.termux.x11.LoriePreferences
import com.termux.x11.LorieView
import kotlinx.coroutines.launch

private const val ACTION_PREFERENCES_CHANGED = "com.termux.x11.ACTION_PREFERENCES_CHANGED"

@Composable
fun DisplayScreen(viewModel: HomeViewModel) {
    val serverStatus by viewModel.loaderStatus.collectAsState()
    val serverPid by viewModel.loaderPid.collectAsState()
    val containers by viewModel.containers.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loriePrefs = remember(context) { EmbeddedDisplayHost.getPrefs(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var surfaceConnected by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(loriePrefs.fullscreen.get()) }

    val xkbSeedContainer = containers.firstOrNull { it.isRunning } ?: containers.firstOrNull()

    fun refresh() = viewModel.refreshRuntimeState()

    fun publishPreferenceChange(key: String) {
        context.sendBroadcast(Intent(ACTION_PREFERENCES_CHANGED).apply {
            putExtra("key", key)
            putExtra("fromBroadcast", true)
            setPackage(context.packageName)
        })
    }

    fun setFullscreen(enabled: Boolean) {
        loriePrefs.fullscreen.put(enabled)
        fullscreen = enabled
        publishPreferenceChange("fullscreen")
    }

    fun openSettings() {
        context.startActivity(Intent(context, LoriePreferences::class.java).apply {
            action = Intent.ACTION_MAIN
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    DisposableEffect(loriePrefs) {
        val store = loriePrefs.get()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "fullscreen") {
                fullscreen = loriePrefs.fullscreen.get()
            }
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    if (fullscreen && serverStatus == LoaderStatus.Running) {
        FullscreenX11Dialog(
            onExitFullscreen = { setFullscreen(false) },
            onConnectionChanged = { surfaceConnected = it }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.DisplaySettings,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                "Integrated Display",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "X11 is rendered directly inside this tab",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    DisplayInfoRow(
                        "Server",
                        if (serverStatus == LoaderStatus.Running) "Running" else "Stopped"
                    )
                    DisplayInfoRow("Display", Constants.X11_DISPLAY)
                    DisplayInfoRow("Socket", Constants.X11_SOCK_FILE)
                    DisplayInfoRow("PID", serverPid?.toString() ?: "—")
                    DisplayInfoRow(
                        "XKB source",
                        xkbSeedContainer?.name ?: "No container available"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    message = null
                                    try {
                                        if (serverStatus != LoaderStatus.Running) {
                                            val started = X11SessionManager.startIntegratedServer(
                                                containerName = xkbSeedContainer?.name
                                            )
                                            if (started.isFailure) {
                                                message = started.exceptionOrNull()?.message
                                                    ?: "Integrated X11 server could not start"
                                                return@launch
                                            }
                                        }
                                    } finally {
                                        busy = false
                                        refresh()
                                    }
                                }
                            },
                            enabled = !busy && serverStatus != LoaderStatus.Running && xkbSeedContainer != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (serverStatus == LoaderStatus.Running) "Display Active" else "Start Display")
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    message = null
                                    try {
                                        if (!X11SessionManager.stopIntegratedServer()) {
                                            message = "Integrated X11 server could not be stopped"
                                        }
                                    } finally {
                                        surfaceConnected = false
                                        if (fullscreen) setFullscreen(false)
                                        busy = false
                                        refresh()
                                    }
                                }
                            },
                            enabled = !busy && serverStatus == LoaderStatus.Running
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                    }

                    OutlinedButton(
                        onClick = ::openSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("X11 Settings")
                    }

                    if (message != null) {
                        Text(
                            message ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (serverStatus != LoaderStatus.Running && xkbSeedContainer == null) {
                        Text(
                            "Create a container first. Lorie needs XKB keyboard data from a Linux rootfs on its first start.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color.Black,
                border = BorderStroke(
                    1.dp,
                    if (surfaceConnected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(
                                "X11 Screen ${Constants.X11_DISPLAY}",
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                when {
                                    serverStatus != LoaderStatus.Running -> "Stopped"
                                    surfaceConnected -> "Connected"
                                    else -> "Connecting…"
                                },
                                color = Color.White.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Row {
                            IconButton(onClick = ::openSettings) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "X11 settings",
                                    tint = Color.White
                                )
                            }
                            IconButton(
                                onClick = { setFullscreen(true) },
                                enabled = serverStatus == LoaderStatus.Running
                            ) {
                                Icon(
                                    Icons.Default.Fullscreen,
                                    contentDescription = "Fullscreen",
                                    tint = if (serverStatus == LoaderStatus.Running) Color.White
                                    else Color.White.copy(alpha = 0.35f)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 10f)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        if (serverStatus == LoaderStatus.Running) {
                            if (!fullscreen) {
                                EmbeddedX11Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    onConnectionChanged = { surfaceConnected = it }
                                )
                            }
                            if (!surfaceConnected && !fullscreen) {
                                CircularProgressIndicator()
                            }
                        } else {
                            Text(
                                "Start the integrated display to render X11 here.",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Computer, contentDescription = null)
                        Text(
                            "Container clients",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    HorizontalDivider()

                    val running = containers.filter { it.isRunning }
                    if (running.isEmpty()) {
                        Text(
                            "No container is running. The display can still be prepared from an installed container and then reused when its session starts.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        running.forEach { container ->
                            DisplayInfoRow(container.name, "Running${container.pid?.let { " · PID $it" } ?: ""}")
                        }
                    }
                }
            }
        }

        item {
            Text(
                "X11 Settings uses the complete preference screen bundled with the pinned Lorie engine. " +
                    "The X11 SurfaceView stays owned by SaaS X11 Manager; no separate display Activity is launched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmbeddedX11Surface(
    modifier: Modifier = Modifier,
    onConnectionChanged: (Boolean) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            LorieView(context).apply {
                setCallback { screenWidth, screenHeight, inputTransform ->
                    EmbeddedDisplayHost.updateInputTransform(
                        this,
                        screenWidth,
                        screenHeight,
                        inputTransform
                    )
                    onConnectionChanged(connected())
                }
                setOnTouchListener { _, event ->
                    EmbeddedDisplayHost.handleTouch(this, event)
                }
                setOnKeyListener { _, _, event ->
                    connected() && EmbeddedDisplayHost.handleKey(this, event)
                }
                requestFocus()
                EmbeddedDisplayHost.tryConnect()
            }
        },
        update = { view ->
            val connected = view.connected()
            onConnectionChanged(connected)
            if (!connected) EmbeddedDisplayHost.tryConnect()
        }
    )
}

@Composable
private fun FullscreenX11Dialog(
    onExitFullscreen: () -> Unit,
    onConnectionChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let {
            WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Dialog(
        onDismissRequest = onExitFullscreen,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            EmbeddedX11Surface(
                modifier = Modifier.fillMaxSize(),
                onConnectionChanged = onConnectionChanged
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.55f)
            ) {
                IconButton(onClick = onExitFullscreen) {
                    Icon(
                        Icons.Default.FullscreenExit,
                        contentDescription = "Exit fullscreen",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun DisplayInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
