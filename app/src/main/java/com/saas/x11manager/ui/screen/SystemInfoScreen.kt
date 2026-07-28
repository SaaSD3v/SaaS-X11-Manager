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
    val termuxStatus by viewModel.termuxStatus.collectAsState()
    val x11ApkStatus by viewModel.x11ApkStatus.collectAsState()
    val dsStatus by viewModel.dsStatus.collectAsState()
    val loaderStatus by viewModel.loaderStatus.collectAsState()
    val loaderPid by viewModel.loaderPid.collectAsState()
    val rootProvider by viewModel.rootProvider.collectAsState()
    val kernelVersion by viewModel.kernelVersion.collectAsState()
    val arch by viewModel.arch.collectAsState()
    val androidVersion by viewModel.androidVersion.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Root
        item {
            InfoCard(
                icon = Icons.Default.Security,
                title = "Root",
                status = when (rootStatus) {
                    RootStatus.Granted -> "Granted"
                    RootStatus.Denied -> "Denied"
                    RootStatus.Checking -> "Checking..."
                },
                isOk = rootStatus == RootStatus.Granted,
                subtitle = if (rootProvider.isNotEmpty() && rootStatus == RootStatus.Granted) "Provider: $rootProvider" else null
            )
        }

        // DroidSpaces
        item {
            InfoCard(
                icon = Icons.Default.Computer,
                title = "DroidSpaces",
                status = if (dsStatus) "OK" else "Not Found",
                isOk = dsStatus,
                subtitle = Constants.DS_BINARY_PATH
            )
        }

        // Termux
        item {
            InfoCard(
                icon = Icons.Default.Terminal,
                title = "Termux",
                status = when (termuxStatus) {
                    TermuxStatus.Installed -> "Installed"
                    TermuxStatus.NotInstalled -> "Not Installed"
                    TermuxStatus.Checking -> "Checking..."
                },
                isOk = termuxStatus == TermuxStatus.Installed
            )
        }

        // Termux:X11
        item {
            InfoCard(
                icon = Icons.Default.DisplaySettings,
                title = "Termux:X11 APK",
                status = when (x11ApkStatus) {
                    X11ApkStatus.Installed -> "Installed"
                    X11ApkStatus.NotInstalled -> "Not Installed"
                    X11ApkStatus.Checking -> "Checking..."
                },
                isOk = x11ApkStatus == X11ApkStatus.Installed
            )
        }

        // X11 Loader
        item {
            InfoCard(
                icon = Icons.Default.PlayCircle,
                title = "X11 Loader",
                status = when (loaderStatus) {
                    LoaderStatus.Running -> "Running"
                    LoaderStatus.Stopped -> "Stopped"
                },
                isOk = loaderStatus == LoaderStatus.Running,
                subtitle = if (loaderPid != null) "PID: ${loaderPid}" else null
            )
        }

        // Device
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

                    InfoRow("Kernel", kernelVersion.ifEmpty { "Unknown" })
                    InfoRow("Arch", arch.ifEmpty { "Unknown" })
                    InfoRow("Android SDK", androidVersion.ifEmpty { "Unknown" })
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
