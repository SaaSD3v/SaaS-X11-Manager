package com.saas.x11manager.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.AnimationUtils
import com.saas.x11manager.util.ContainerInfo
import com.saas.x11manager.util.ContainerStatus

data class ContainerCardActions(
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onToggleExpand: () -> Unit = {},
    val onShowLogs: () -> Unit = {},
    val onStartX11: () -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContainerCard(
    container: ContainerInfo,
    actions: ContainerCardActions = ContainerCardActions(),
    isExpanded: Boolean = false,
    isOperationRunning: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(20.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = AnimationUtils.mediumSpec())
            .combinedClickable(
                onClick = {
                    if (container.isRunning) actions.onShowLogs() else actions.onToggleExpand()
                },
                onLongClick = actions.onToggleExpand,
                indication = rememberRipple(bounded = true),
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Computer,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (container.isRunning) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = container.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = actions.onShowLogs, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = "View logs",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    val (statusText, statusColor) = when (container.status) {
                        ContainerStatus.RUNNING -> "RUNNING" to MaterialTheme.colorScheme.primary
                        ContainerStatus.STOPPED -> "STOPPED" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        ContainerStatus.UNKNOWN -> "UNKNOWN" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    }
                    StatusPill(label = statusText, color = statusColor)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // PID
            if (container.pid != null) {
                Text(
                    "PID: ${container.pid}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Control buttons
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (container.isRunning) {
                        // STOP button
                        val isStopEnabled = !isOperationRunning
                        Surface(
                            onClick = actions.onStop,
                            enabled = isStopEnabled,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = if (isStopEnabled) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, (if (isStopEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant).copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Stop, null, modifier = Modifier.size(20.dp), tint = if (isStopEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.width(8.dp))
                                Text("Stop", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (isStopEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    } else {
                        // START X11 button
                        val isStartEnabled = !isOperationRunning
                        Surface(
                            onClick = actions.onStartX11,
                            enabled = isStartEnabled,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = if (isStartEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, (if (isStartEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant).copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp), tint = if (isStartEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.width(8.dp))
                                Text("Start X11", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (isStartEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }

            // Expanded actions (like Droidspaces)
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = AnimationUtils.mediumSpec(), expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(animationSpec = AnimationUtils.mediumSpec(), shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(12.dp))

                    ActionItem(
                        icon = Icons.Default.Terminal,
                        label = "Logs",
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = actions.onShowLogs
                    )

                    if (!container.isRunning) {
                        ActionItem(
                            icon = Icons.Default.PlayArrow,
                            label = "Start X11",
                            tint = MaterialTheme.colorScheme.primary,
                            onClick = actions.onStartX11
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
