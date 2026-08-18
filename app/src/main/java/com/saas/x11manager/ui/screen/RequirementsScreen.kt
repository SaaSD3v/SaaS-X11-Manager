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
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.DroidspacesRequirementState
import com.saas.x11manager.util.LoaderStatus
import com.saas.x11manager.util.RootStatus
import com.saas.x11manager.util.TermuxChecker
import com.saas.x11manager.util.TermuxStatus
import com.saas.x11manager.util.X11ApkStatus

@Composable
fun RequirementsScreen(
    viewModel: HomeViewModel
) {
    val rootStatus by viewModel.rootStatus.collectAsState()
    val termuxStatus by viewModel.termuxStatus.collectAsState()
    val x11ApkStatus by viewModel.x11ApkStatus.collectAsState()
    val dsStatus by viewModel.dsStatus.collectAsState()
    val dsRequirements by viewModel.dsRequirements.collectAsState()
    val loaderStatus by viewModel.loaderStatus.collectAsState()
    val loaderPid by viewModel.loaderPid.collectAsState()
    val rootProvider by viewModel.rootProvider.collectAsState()
    val kernelVersion by viewModel.kernelVersion.collectAsState()
    val arch by viewModel.arch.collectAsState()
    val androidVersion by viewModel.androidVersion.collectAsState()
    val androidSdk by viewModel.androidSdk.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()

    val loaderAssetAvailable by produceState<Boolean?>(
        initialValue = null,
        key1 = x11ApkStatus
    ) {
        value = if (x11ApkStatus == X11ApkStatus.Installed) {
            TermuxChecker.checkTermuxX11Loader()
        } else {
            false
        }
    }

    val dsRequirementsBlocking =
        dsRequirements?.state == DroidspacesRequirementState.MISSING_REQUIRED
    val dsRequirementsWarning =
        dsRequirements?.state == DroidspacesRequirementState.INCONCLUSIVE

    val requiredReady =
        rootStatus == RootStatus.Granted &&
            termuxStatus == TermuxStatus.Installed &&
            x11ApkStatus == X11ApkStatus.Installed &&
            loaderAssetAvailable == true &&
            dsStatus &&
            !dsRequirementsBlocking

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(
                    1.dp,
                    if (requiredReady) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    }
                )
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = Icons.Default.Security,
                        title = "Required environment",
                        subtitle = when {
                            requiredReady && dsRequirementsWarning -> "Ready with a DroidSpaces diagnostic warning"
                            requiredReady -> "Ready"
                            else -> "Check the items below"
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()

                    RequirementRow(
                        icon = Icons.Default.Security,
                        label = "Root Access",
                        value = when (rootStatus) {
                            RootStatus.Granted -> rootProvider.ifEmpty { "Granted" }
                            RootStatus.Denied -> "Denied"
                            RootStatus.Checking -> "Checking..."
                        },
                        detail = when (rootStatus) {
                            RootStatus.Granted -> "Root permission granted"
                            RootStatus.Denied -> "Grant root access to continue"
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
                            label = "DroidSpaces Host Check",
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
                        icon = Icons.Default.Terminal,
                        label = "Termux",
                        value = when (termuxStatus) {
                            TermuxStatus.Installed -> "Installed"
                            TermuxStatus.NotInstalled -> "Not installed"
                            TermuxStatus.Checking -> "Checking..."
                        },
                        detail = Constants.TERMUX_PACKAGE,
                        state = when (termuxStatus) {
                            TermuxStatus.Installed -> RowState.OK
                            TermuxStatus.NotInstalled -> RowState.ERROR
                            TermuxStatus.Checking -> RowState.INFO
                        }
                    )

                    RequirementRow(
                        icon = Icons.Default.DisplaySettings,
                        label = "Termux:X11",
                        value = when (x11ApkStatus) {
                            X11ApkStatus.Installed -> "Installed"
                            X11ApkStatus.NotInstalled -> "Not installed"
                            X11ApkStatus.Checking -> "Checking..."
                        },
                        detail = Constants.TERMUX_X11_PACKAGE,
                        state = when (x11ApkStatus) {
                            X11ApkStatus.Installed -> RowState.OK
                            X11ApkStatus.NotInstalled -> RowState.ERROR
                            X11ApkStatus.Checking -> RowState.INFO
                        }
                    )

                    RequirementRow(
                        icon = Icons.Default.DisplaySettings,
                        label = "Termux:X11 Loader",
                        value = when (loaderAssetAvailable) {
                            true -> "Available"
                            false -> if (x11ApkStatus == X11ApkStatus.Checking) "Checking..." else "Missing"
                            null -> "Checking..."
                        },
                        detail = Constants.LOADER_APK,
                        state = when (loaderAssetAvailable) {
                            true -> RowState.OK
                            false -> if (x11ApkStatus == X11ApkStatus.Checking) RowState.INFO else RowState.ERROR
                            null -> RowState.INFO
                        }
                    )
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
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    SectionHeader(
                        icon = Icons.Default.DisplaySettings,
                        title = "X11 runtime",
                        subtitle = "Current host-side Termux:X11 state"
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()

                    RequirementRow(
                        icon = Icons.Default.DisplaySettings,
                        label = "Loader",
                        value = when (loaderStatus) {
                            LoaderStatus.Running -> "Running"
                            LoaderStatus.Stopped -> "Stopped"
                        },
                        detail = if (loaderPid != null) "PID $loaderPid" else "Starts when X11 is launched",
                        state = when (loaderStatus) {
                            LoaderStatus.Running -> RowState.OK
                            LoaderStatus.Stopped -> RowState.WARNING
                        }
                    )

                    RequirementRow(
                        icon = Icons.Default.Info,
                        label = "X11 Socket",
                        value = Constants.X11_SOCK_FILE,
                        detail = "Termux:X11 display :0 socket",
                        state = RowState.INFO
                    )
                }
            }
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
            modifier = Modifier.fillMaxWidth().padding(20.dp),
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
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
