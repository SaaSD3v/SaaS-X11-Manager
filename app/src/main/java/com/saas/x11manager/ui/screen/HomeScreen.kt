package com.saas.x11manager.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.saas.x11manager.ui.component.ContainerCard
import com.saas.x11manager.ui.component.ContainerCardActions
import com.saas.x11manager.ui.component.TerminalDialog
import com.saas.x11manager.util.SessionAccessMode
import com.saas.x11manager.util.VncSettings

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel
) {
    val context = LocalContext.current
    val containers by viewModel.containers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val expandedContainerName = remember { mutableStateOf<String?>(null) }
    var generalSettingsContainer by remember { mutableStateOf<String?>(null) }
    val activeOperation = viewModel.runningOperationContainer

    generalSettingsContainer?.let { containerName ->
        GeneralSettingsDialog(
            containerName = containerName,
            onDismiss = { generalSettingsContainer = null }
        )
    }

    viewModel.showLogViewerFor?.let { containerName ->
        val memoryLogs: List<Pair<Int, String>> =
            viewModel.containerLogs[containerName] ?: emptyList()
        val isBlocking = activeOperation == containerName || activeOperation == "__all__"
        TerminalDialog(
            title = "Logs: $containerName",
            logs = memoryLogs,
            onDismiss = { viewModel.dismissLogViewer() },
            onClear = { viewModel.clearLogsBuffer(containerName) },
            isBlocking = isBlocking
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            containers.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            "No containers installed",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { expandedContainerName.value = null }
                        )
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(containers, key = { it.name }) { container ->
                        // These are small Android preference reads and are intentionally
                        // performed in the UI so a General settings save is reflected on
                        // the card immediately after the dialog closes.
                        val accessMode = VncSettings.getAccessMode(context, container.name)
                        val vncPort = VncSettings.getPort(context, container.name)
                        val startLabel = when (accessMode) {
                            SessionAccessMode.INTEGRATED_X11 -> "Start X11"
                            SessionAccessMode.VNC -> "Start VNC"
                            SessionAccessMode.BOTH -> "Start Both"
                        }

                        ContainerCard(
                            container = container,
                            isExpanded = expandedContainerName.value == container.name,
                            isOperationRunning = activeOperation != null,
                            actions = ContainerCardActions(
                                startLabel = startLabel,
                                onToggleExpand = {
                                    expandedContainerName.value =
                                        if (expandedContainerName.value == container.name) null else container.name
                                },
                                onShowLogs = { viewModel.showLogs(container) },
                                onStartX11 = {
                                    viewModel.startSession(
                                        container = container,
                                        accessMode = accessMode,
                                        vncPort = vncPort
                                    )
                                },
                                onStop = { viewModel.stopContainer(container) },
                                onEdit = {
                                    expandedContainerName.value = null
                                    viewModel.navigateToEditContainer(container.name)
                                },
                                onGeneralSettings = {
                                    expandedContainerName.value = null
                                    generalSettingsContainer = container.name
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}
