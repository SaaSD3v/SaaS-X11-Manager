package com.saas.x11manager.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.DroidspacesRequirementState
import com.saas.x11manager.util.LoaderStatus
import com.saas.x11manager.util.RootStatus

@Composable
fun RequirementsScreen(viewModel: HomeViewModel) {
    val rootStatus by viewModel.rootStatus.collectAsState()
    val dsStatus by viewModel.dsStatus.collectAsState()
    val dsRequirements by viewModel.dsRequirements.collectAsState()
    val serverStatus by viewModel.loaderStatus.collectAsState()
    val serverPid by viewModel.loaderPid.collectAsState()
    val rootProvider by viewModel.rootProvider.collectAsState()
    val kernelVersion by viewModel.kernelVersion.collectAsState()
    val arch by viewModel.arch.collectAsState()
    val androidVersion by viewModel.androidVersion.collectAsState()
    val androidSdk by viewModel.androidSdk.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()

    val dsRequirementsBlocking =
        dsRequirements?.state == DroidspacesRequirementState.MISSING_REQUIRED
    val dsRequirementsWarning =
        dsRequirements?.state == DroidspacesRequirementState.INCONCLUSIVE
    val requiredReady =
        rootStatus == RootStatus.Granted && dsStatus && !dsRequirementsBlocking

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            DeviceOverviewCard(
                deviceName = deviceName.ifEmpty { "Android device" },
                androidVersion = androidVersion.ifEmpty { "Unknown" },
                androidSdk = androidSdk.ifEmpty { "?" },
                arch = arch.ifEmpty { "Unknown" },
                kernel = kernelVersion.ifEmpty { "Unknown" }
            )
        }

        item {
            RequirementCard(
                icon = Icons.Default.Security,
                title = "Required environment",
                subtitle = when {
                    requiredReady && dsRequirementsWarning ->
                        "Ready with a DroidSpaces diagnostic warning"
                    requiredReady -> "Ready for DroidSpaces Screen"
                    else -> "Check the required items below"
                },
                highlighted = requiredReady
            ) {
                RequirementRow(
                    icon = Icons.Default.Security,
                    label = "Root access",
                    value = when (rootStatus) {
                        RootStatus.Granted -> rootProvider.ifEmpty { "Granted" }
                        RootStatus.Denied -> "Denied"
                        RootStatus.Checking -> "Checking…"
                    },
                    detail = when (rootStatus) {
                        RootStatus.Granted -> "Root permission granted"
                        RootStatus.Denied -> "Grant root access to use the host X11 runtime"
                        RootStatus.Checking -> null
                    },
                    state = when (rootStatus) {
                        RootStatus.Granted -> RowState.OK
                        RootStatus.Denied -> RowState.ERROR
                        RootStatus.Checking -> RowState.INFO
                    }
                )

                RequirementRow(
                    icon = Icons.Default.Computer,
                    label = "DroidSpaces",
                    value = if (dsStatus) "Available" else "Not found",
                    detail = Constants.DS_BINARY_PATH,
                    state = if (dsStatus) RowState.OK else RowState.ERROR
                )

                if (dsStatus && dsRequirements != null) {
                    RequirementRow(
                        icon = Icons.Default.Security,
                        label = "DroidSpaces host check",
                        value = when (dsRequirements?.state) {
                            DroidspacesRequirementState.READY -> "Ready"
                            DroidspacesRequirementState.MISSING_REQUIRED -> "Missing requirements"
                            DroidspacesRequirementState.INCONCLUSIVE -> "Inconclusive"
                            null -> "Not checked"
                        },
                        detail = dsRequirements?.summary,
                        state = when (dsRequirements?.state) {
                            DroidspacesRequirementState.READY -> RowState.OK
                            DroidspacesRequirementState.MISSING_REQUIRED -> RowState.ERROR
                            DroidspacesRequirementState.INCONCLUSIVE -> RowState.WARNING
                            null -> RowState.INFO
                        }
                    )
                }

                RequirementRow(
                    icon = Icons.Default.DisplaySettings,
                    label = "Integrated X11 engine",
                    value = "Bundled",
                    detail = "The pinned Lorie/Xorg engine is compiled into this app",
                    state = RowState.OK
                )
            }
        }

        item {
            RequirementCard(
                icon = Icons.Default.DisplaySettings,
                title = "Screen runtime",
                subtitle = "Current project-owned X11 display state"
            ) {
                RequirementRow(
                    icon = Icons.Default.DisplaySettings,
                    label = "X11 server",
                    value = when (serverStatus) {
                        LoaderStatus.Running -> "Running"
                        LoaderStatus.Stopped -> "Stopped"
                    },
                    detail = if (serverPid != null) {
                        "${Constants.X11_SERVER_PROCESS} · PID $serverPid"
                    } else {
                        "Start and configure it from the Screen tab"
                    },
                    state = when (serverStatus) {
                        LoaderStatus.Running -> RowState.OK
                        LoaderStatus.Stopped -> RowState.INFO
                    }
                )

                RequirementRow(
                    icon = Icons.Default.Info,
                    label = "X11 display",
                    value = Constants.X11_DISPLAY,
                    detail = Constants.X11_SOCK_FILE,
                    state = RowState.INFO
                )

                RequirementRow(
                    icon = Icons.Default.Security,
                    label = "External Termux:X11 APK",
                    value = "Not required",
                    detail = "The Screen runtime launches the embedded engine from this APK",
                    state = RowState.OK
                )
            }
        }

        item {
            Text(
                "The embedded engine still comes from the pinned Termux:X11/Lorie source, " +
                    "but the external Termux:X11 application and loader.apk are not runtime requirements for Screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RequirementCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (highlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            SectionHeader(icon = icon, title = title, subtitle = subtitle)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun DeviceOverviewCard(
    deviceName: String,
    androidVersion: String,
    androidSdk: String,
    arch: String,
    kernel: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        deviceName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Android $androidVersion · SDK $androidSdk",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            DeviceInfoRow("Architecture", arch)
            DeviceInfoRow("Kernel", kernel)
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private enum class RowState { OK, ERROR, WARNING, INFO }

@Composable
private fun RequirementRow(
    icon: ImageVector,
    label: String,
    value: String,
    detail: String?,
    state: RowState
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (!detail.isNullOrEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Icon(
                imageVector = when (state) {
                    RowState.OK -> Icons.Default.CheckCircle
                    RowState.ERROR -> Icons.Default.Cancel
                    RowState.WARNING -> Icons.Default.Warning
                    RowState.INFO -> Icons.Default.Info
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = when (state) {
                    RowState.OK -> MaterialTheme.colorScheme.primary
                    RowState.ERROR -> MaterialTheme.colorScheme.error
                    RowState.WARNING -> MaterialTheme.colorScheme.tertiary
                    RowState.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DeviceInfoRow(label: String, value: String) {
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
