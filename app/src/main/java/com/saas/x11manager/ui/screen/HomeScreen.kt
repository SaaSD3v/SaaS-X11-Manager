package com.saas.x11manager.ui.screen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saas.x11manager.ui.component.ContainerCard
import com.saas.x11manager.ui.component.ContainerCardActions
import com.saas.x11manager.ui.component.TerminalDialog
import com.saas.x11manager.ui.component.StatusPill
import com.saas.x11manager.util.ContainerInfo

@Composable
fun HomeScreen(
    viewModel: HomeViewModel
) {
    val containers by viewModel.containers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val expandedContainerName = remember { mutableStateOf<String?>(null) }

    // Log dialog
    viewModel.showLogViewerFor?.let { containerName ->
        val memoryLogs = viewModel.containerLogs[containerName]?.toList() ?: emptyList()
        val isBlocking = viewModel.runningOperationContainer == containerName
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
                        ContainerCard(
                            container = container,
                            isExpanded = expandedContainerName.value == container.name,
                            isOperationRunning = viewModel.runningOperationContainer == container.name,
                            actions = ContainerCardActions(
                                onToggleExpand = {
                                    expandedContainerName.value =
                                        if (expandedContainerName.value == container.name) null else container.name
                                },
                                onShowLogs = { viewModel.showLogs(container) },
                                onStartX11 = { viewModel.startX11(container) },
                                onStop = { viewModel.stopContainer(container) },
                                onStart = { viewModel.startX11(container) }
                            )
                        )
                    }
                }
            }
        }
    }
}
