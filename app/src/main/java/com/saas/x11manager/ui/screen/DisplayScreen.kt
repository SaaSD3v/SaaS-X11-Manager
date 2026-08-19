package com.saas.x11manager.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.LoaderStatus
import com.saas.x11manager.util.X11SessionManager
import com.termux.x11.EmbeddedDisplayHost
import com.termux.x11.LorieView
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.RenderData
import kotlinx.coroutines.launch

@Composable
fun DisplayScreen(viewModel: HomeViewModel) {
    val serverStatus by viewModel.loaderStatus.collectAsState()
    val serverPid by viewModel.loaderPid.collectAsState()
    val containers by viewModel.containers.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var surfaceConnected by remember { mutableStateOf(false) }

    val xkbSeedContainer = containers.firstOrNull { it.isRunning } ?: containers.firstOrNull()

    fun refresh() = viewModel.refreshRuntimeState()

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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
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
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 10f)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        if (serverStatus == LoaderStatus.Running) {
                            EmbeddedX11Surface(
                                modifier = Modifier.fillMaxSize(),
                                onConnectionChanged = { surfaceConnected = it }
                            )
                            if (!surfaceConnected) {
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
                "The X11 SurfaceView above is hosted by SaaS X11 Manager itself. " +
                    "No separate Termux:X11 display Activity is opened.",
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
                val sender = InputEventSender(this)
                val renderData = RenderData()

                setCallback { screenWidth, screenHeight, inputTransform ->
                    renderData.screenWidth = screenWidth
                    renderData.screenHeight = screenHeight
                    renderData.setInputTransform(inputTransform)
                    onConnectionChanged(connected())
                }
                setOnTouchListener { _, event ->
                    if (!connected()) return@setOnTouchListener false
                    requestFocus()
                    sender.releaseStuckModifiers(event.metaState)
                    sender.syncLockKeysState(event.metaState)
                    sender.sendTouchEvent(event, renderData)
                    true
                }
                setOnKeyListener { _, _, event ->
                    connected() && sender.sendKeyEvent(event)
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
