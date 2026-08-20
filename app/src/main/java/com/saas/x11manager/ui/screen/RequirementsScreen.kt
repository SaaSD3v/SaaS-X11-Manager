package com.saas.x11manager.ui.screen

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.DroidspacesRequirementState
import com.saas.x11manager.util.RootStatus
import com.saas.x11manager.util.X11MonitorInfo
import com.saas.x11manager.util.X11ServerStatus
import com.saas.x11manager.util.X11SessionManager

@Composable
fun RequirementsScreen(
    viewModel: HomeViewModel
) {
    val rootStatus by viewModel.rootStatus.collectAsState()
    val dsStatus by viewModel.dsStatus.collectAsState()
    val dsRequirements by viewModel.dsRequirements.collectAsState()
    val serverStatus by viewModel.x11ServerStatus.collectAsState()
    val serverPid by viewModel.x11ServerPid.collectAsState()
    val rootProvider by viewModel.rootProvider.collectAsState()
    val kernelVersion by viewModel.kernelVersion.collectAsState()
    val arch by viewModel.arch.collectAsState()
    val androidVersion by viewModel.androidVersion.collectAsState()
    val androidSdk by viewModel.androidSdk.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val containers by viewModel.containers.collectAsState()

    var monitors by remember { mutableStateOf<List<X11MonitorInfo>>(emptyList()) }

    LaunchedEffect(serverStatus, serverPid, containers) {
        monitors = X11SessionManager.getMonitors()
    }

    val hostCheckValue = when (dsRequirements?.state) {
        DroidspacesRequirementState.READY -> "Ready"
        DroidspacesRequirementState.MISSING_REQUIRED -> "Missing required host capabilities"
        DroidspacesRequirementState.INCONCLUSIVE -> "Inconclusive"
        null -> "Not available"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Runtime diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Host, DroidSpaces and integrated X11 technical state",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { viewModel.refresh() }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh")
                }
            }
        }

        item {
            TechnicalSection(
                icon = Icons.Default.PhoneAndroid,
                title = "Android host"
            ) {
                TechnicalRow("Device", deviceName.ifEmpty { "Unknown" })
                TechnicalDivider()
                TechnicalRow(
                    "Android",
                    if (androidVersion.isEmpty()) "Unknown" else androidVersion,
                    detail = "SDK ${androidSdk.ifEmpty { "?" }}"
                )
                TechnicalDivider()
                TechnicalRow(
                    "Primary ABI",
                    arch.ifEmpty { "Unknown" },
                    detail = Build.SUPPORTED_ABIS.joinToString(", ").ifEmpty { "No ABI reported" }
                )
                TechnicalDivider()
                TechnicalRow("Kernel", kernelVersion.ifEmpty { "Unknown" })
            }
        }

        item {
            TechnicalSection(
                icon = Icons.Default.Security,
                title = "Privileged host interface"
            ) {
                TechnicalRow(
                    label = "Root access",
                    value = when (rootStatus) {
                        RootStatus.Granted -> "Granted"
                        RootStatus.Denied -> "Denied"
                        RootStatus.Checking -> "Checking"
                    },
                    detail = rootProvider.ifEmpty { null },
                    isError = rootStatus == RootStatus.Denied
                )
                TechnicalDivider()
                TechnicalRow(
                    label = "DroidSpaces backend",
                    value = if (dsStatus) "Available" else "Unavailable",
                    detail = Constants.DS_BINARY_PATH,
                    isError = !dsStatus
                )
                TechnicalDivider()
                TechnicalRow(
                    label = "DroidSpaces base",
                    value = Constants.DS_BASE_DIR
                )
                TechnicalDivider()
                TechnicalRow(
                    label = "Host capability check",
                    value = hostCheckValue,
                    detail = dsRequirements?.summary,
                    isError = dsRequirements?.state == DroidspacesRequirementState.MISSING_REQUIRED
                )
            }
        }

        item {
            TechnicalSection(
                icon = Icons.Default.DisplaySettings,
                title = "Integrated X11 engine"
            ) {
                TechnicalRow(
                    "Renderer",
                    "Embedded Termux:X11 / Lorie",
                    detail = "Runs inside the SaaS X11 Manager APK"
                )
                TechnicalDivider()
                TechnicalRow(
                    "Server entry point",
                    "com.termux.x11.CmdEntryPoint",
                    detail = "Launched through Android app_process"
                )
                TechnicalDivider()
                TechnicalRow(
                    "Runtime root",
                    Constants.INTEGRATED_X11_RUNTIME_DIR
                )
                TechnicalDivider()
                TechnicalRow(
                    "Shared XKB root",
                    Constants.INTEGRATED_X11_XKB_DIR
                )
                TechnicalDivider()
                TechnicalRow(
                    "Display allocation",
                    "Lowest free X11 display",
                    detail = "Monitor 1 = :0; additional monitors use :1, :2, ..."
                )
                TechnicalDivider()
                TechnicalRow(
                    "Socket isolation",
                    "Per-display runtime",
                    detail = "display-N/.X11-unix/XN with a matching per-display process and log"
                )
            }
        }

        item {
            TechnicalSection(
                icon = Icons.Default.Computer,
                title = "Container integration"
            ) {
                TechnicalRow(
                    "Containers",
                    "${containers.count { it.isRunning }} running / ${containers.size} total"
                )
                TechnicalDivider()
                TechnicalRow(
                    "DroidSpaces X11 flag",
                    "enable_termux_x11=0",
                    detail = "The Manager owns the X11 server lifecycle"
                )
                TechnicalDivider()
                TechnicalRow(
                    "Host socket bind",
                    "display-N/.X11-unix → /usr/.X11-unix"
                )
                TechnicalDivider()
                TechnicalRow(
                    "Container socket bridge",
                    "/usr/.X11-unix → /tmp/.X11-unix"
                )
                TechnicalDivider()
                TechnicalRow(
                    "Session launcher",
                    "/usr/local/bin/x11-session.sh",
                    detail = "DISPLAY is resolved from the mounted XN socket at runtime"
                )
            }
        }

        item {
            TechnicalSection(
                icon = Icons.Default.Storage,
                title = "X11 monitor runtime",
                trailing = {
                    Text(
                        "${monitors.count { it.status == X11ServerStatus.Running }} active",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            ) {
                if (monitors.isEmpty()) {
                    TechnicalRow(
                        "Displays",
                        "No active or assigned monitor",
                        detail = "Monitor 1 (:0) remains the primary UI slot even while stopped"
                    )
                } else {
                    monitors.sortedBy { it.slot.number }.forEachIndexed { index, monitor ->
                        if (index > 0) TechnicalDivider()
                        MonitorRuntimeBlock(monitor)
                    }
                }
            }
        }

        item {
            TechnicalSection(
                icon = Icons.Default.Info,
                title = "Runtime ownership"
            ) {
                TechnicalRow(
                    "Renderer Binder",
                    "One service per X11 display",
                    detail = "The visible LorieView binds only to the currently selected display"
                )
                TechnicalDivider()
                TechnicalRow(
                    "Input routing",
                    "Selected display only",
                    detail = "Mouse, touch, stylus and keyboard are routed to the focused embedded surface"
                )
                TechnicalDivider()
                TechnicalRow(
                    "External Termux:X11 APK",
                    "Not used at runtime",
                    detail = "No upstream MainActivity or external loader is required"
                )
            }
        }
    }
}

@Composable
private fun MonitorRuntimeBlock(monitor: X11MonitorInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Monitor ${monitor.monitorNumber}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                monitor.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TechnicalCompactRow(
            "State",
            if (monitor.status == X11ServerStatus.Running) "Running" else "Stopped"
        )
        TechnicalCompactRow("Container", monitor.containerName ?: "Unassigned")
        TechnicalCompactRow("PID", monitor.pid?.toString() ?: "—")
        TechnicalCompactRow("Process", monitor.slot.processName)
        TechnicalCompactRow("Runtime", monitor.slot.runtimeDir)
        TechnicalCompactRow("Socket", monitor.slot.socketFile)
        TechnicalCompactRow("Log", monitor.slot.logFile)
    }
}

@Composable
private fun TechnicalSection(
    icon: ImageVector,
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 5.dp),
                content = content
            )
        }
    }
}

@Composable
private fun TechnicalRow(
    label: String,
    value: String,
    detail: String? = null,
    isError: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(0.42f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Column(
            modifier = Modifier.weight(0.58f),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TechnicalCompactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TechnicalDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
    )
}
