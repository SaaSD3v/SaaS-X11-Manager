package com.saas.x11manager.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saas.x11manager.ui.component.StatusPill
import com.saas.x11manager.util.*

@Composable
fun SystemInfoScreen(
    viewModel: HomeViewModel
) {
    val rootStatus by viewModel.rootStatus.collectAsState()
    val dsStatus by viewModel.dsStatus.collectAsState()
    val serverStatus by viewModel.loaderStatus.collectAsState()
    val serverPid by viewModel.loaderPid.collectAsState()
    val rootProvider by viewModel.rootProvider.collectAsState()
    val kernelVersion by viewModel.kernelVersion.collectAsState()
    val arch by viewModel.arch.collectAsState()
    val androidVersion by viewModel.androidVersion.collectAsState()
    val androidSdk by viewModel.androidSdk.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            InfoCard(
                icon = Icons.Default.Security,
                title = "Root",
                status = when (rootStatus) {
                    RootStatus.Granted -> rootProvider.ifEmpty { "Granted" }
                    RootStatus.Denied -> "Denied"
                    RootStatus.Checking -> "Checking..."
                },
                isOk = rootStatus == RootStatus.Granted
            )
        }

        item {
            InfoCard(
                icon = Icons.Default.Computer,
                title = "DroidSpaces",
                status = if (dsStatus) "OK" else "Not Found",
                isOk = dsStatus,
                subtitle = Constants.DS_BINARY_PATH
            )
        }

        item {
            InfoCard(
                icon = Icons.Default.DisplaySettings,
                title = "Integrated X11 Engine",
                status = "Bundled",
                isOk = true,
                subtitle = "Termux:X11/Lorie module inside this APK"
            )
        }

        item {
            InfoCard(
                icon = Icons.Default.PlayCircle,
                title = "Integrated X11 Server",
                status = when (serverStatus) {
                    LoaderStatus.Running -> "Running"
                    LoaderStatus.Stopped -> "Stopped"
                },
                isOk = serverStatus == LoaderStatus.Running,
                subtitle = if (serverPid != null) {
                    "${Constants.X11_DISPLAY} · PID: $serverPid"
                } else {
                    Constants.X11_SOCK_FILE
                }
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Device",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    InfoRow("Android", androidVersion.ifEmpty { "Unknown" })
                    InfoRow("Android SDK", androidSdk.ifEmpty { "Unknown" })
                    InfoRow("Arch", arch.ifEmpty { "Unknown" })
                    InfoRow("Kernel", kernelVersion.ifEmpty { "Unknown" })
                }
            }
        }
    }
}

@Composable
private fun InfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    status: String,
    isOk: Boolean,
    subtitle: String? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (isOk) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                StatusPill(
                    label = status,
                    color = if (isOk) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
