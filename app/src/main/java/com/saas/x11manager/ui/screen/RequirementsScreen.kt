package com.saas.x11manager.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.*

@Composable
fun RequirementsScreen(
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
        item {
            RequirementSection(
                title = "Root Access",
                icon = Icons.Default.Security,
                checks = listOf(
                    RequirementCheck(
                        label = "Root Permission",
                        status = when (rootStatus) {
                            RootStatus.Granted -> CheckStatus.OK
                            RootStatus.Denied -> CheckStatus.Failed
                            RootStatus.Checking -> CheckStatus.Checking
                        },
                        detail = when (rootStatus) {
                            RootStatus.Granted -> "Granted"
                            RootStatus.Denied -> "Denied — grant in KernelSU/Magisk"
                            RootStatus.Checking -> "Checking..."
                        }
                    ),
                    RequirementCheck(
                        label = "Root Provider",
                        status = if (rootProvider.isNotEmpty() && rootStatus == RootStatus.Granted) CheckStatus.OK else CheckStatus.Failed,
                        detail = if (rootProvider.isNotEmpty()) rootProvider else "Unknown"
                    )
                )
            )
        }

        item {
            RequirementSection(
                title = "DroidSpaces",
                icon = Icons.Default.Computer,
                checks = listOf(
                    RequirementCheck(
                        label = "DroidSpaces Binary",
                        status = if (dsStatus) CheckStatus.OK else CheckStatus.Failed,
                        detail = Constants.DS_BINARY_PATH,
                        extraInfo = if (dsStatus) "Backend available" else "Binary not found"
                    )
                )
            )
        }

        item {
            RequirementSection(
                title = "Termux Environment",
                icon = Icons.Default.Terminal,
                checks = listOf(
                    RequirementCheck(
                        label = "Termux App",
                        status = when (termuxStatus) {
                            TermuxStatus.Installed -> CheckStatus.OK
                            TermuxStatus.NotInstalled -> CheckStatus.Failed
                            TermuxStatus.Checking -> CheckStatus.Checking
                        },
                        detail = when (termuxStatus) {
                            TermuxStatus.Installed -> Constants.TERMUX_PACKAGE
                            TermuxStatus.NotInstalled -> "Not installed"
                            TermuxStatus.Checking -> "Checking..."
                        }
                    ),
                    RequirementCheck(
                        label = "Termux:X11",
                        status = when (x11ApkStatus) {
                            X11ApkStatus.Installed -> CheckStatus.OK
                            X11ApkStatus.NotInstalled -> CheckStatus.Failed
                            X11ApkStatus.Checking -> CheckStatus.Checking
                        },
                        detail = when (x11ApkStatus) {
                            X11ApkStatus.Installed -> Constants.TERMUX_X11_PACKAGE
                            X11ApkStatus.NotInstalled -> "Not installed"
                            X11ApkStatus.Checking -> "Checking..."
                        }
                    )
                )
            )
        }

        item {
            RequirementSection(
                title = "X11 Server",
                icon = Icons.Default.DisplaySettings,
                checks = listOf(
                    RequirementCheck(
                        label = "Loader Process",
                        status = when (loaderStatus) {
                            LoaderStatus.Running -> CheckStatus.OK
                            LoaderStatus.Stopped -> CheckStatus.Warning
                        },
                        detail = when (loaderStatus) {
                            LoaderStatus.Running -> "Running (PID: ${loaderPid ?: "?"})"
                            LoaderStatus.Stopped -> "Stopped"
                        }
                    ),
                    RequirementCheck(
                        label = "Loader APK",
                        status = CheckStatus.Info,
                        detail = Constants.LOADER_APK
                    ),
                    RequirementCheck(
                        label = "Socket Path",
                        status = CheckStatus.Info,
                        detail = Constants.X11_SOCK_FILE
                    )
                )
            )
        }

        item {
            RequirementSection(
                title = "Device Information",
                icon = Icons.Default.PhoneAndroid,
                checks = listOf(
                    RequirementCheck(
                        label = "Android Version",
                        status = CheckStatus.Info,
                        detail = androidVersion.ifEmpty { "Unknown" }
                    ),
                    RequirementCheck(
                        label = "Architecture",
                        status = CheckStatus.Info,
                        detail = arch.ifEmpty { "Unknown" }
                    ),
                    RequirementCheck(
                        label = "Kernel",
                        status = CheckStatus.Info,
                        detail = kernelVersion.ifEmpty { "Unknown" }
                    )
                )
            )
        }
    }
}

enum class CheckStatus { OK, Failed, Warning, Checking, Info }

data class RequirementCheck(
    val label: String,
    val status: CheckStatus,
    val detail: String? = null,
    val extraInfo: String? = null
)

@Composable
private fun RequirementSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checks: List<RequirementCheck>
) {
    var expanded by remember { mutableStateOf(true) }

    val failedCount = checks.count { it.status == CheckStatus.Failed }
    val okCount = checks.count { it.status == CheckStatus.OK }
    val sectionOk = failedCount == 0 && okCount > 0

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (sectionOk) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { expanded = !expanded }
                    .padding(16.dp),
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
                        tint = if (sectionOk) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!sectionOk && failedCount > 0) {
                            Text(
                                "$failedCount issue(s) found",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        thickness = 0.5.dp
                    )
                    checks.forEach { check ->
                        CheckRow(check)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: RequirementCheck) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                when (check.status) {
                    CheckStatus.OK -> Icons.Default.CheckCircle
                    CheckStatus.Failed -> Icons.Default.Cancel
                    CheckStatus.Warning -> Icons.Default.Warning
                    CheckStatus.Checking -> Icons.Default.Sync
                    CheckStatus.Info -> Icons.Default.Info
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = when (check.status) {
                    CheckStatus.OK -> MaterialTheme.colorScheme.primary
                    CheckStatus.Failed -> MaterialTheme.colorScheme.error
                    CheckStatus.Warning -> MaterialTheme.colorScheme.tertiary
                    CheckStatus.Checking -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    CheckStatus.Info -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
            )
            Column {
                Text(
                    check.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (check.detail != null) {
                    Text(
                        check.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                if (check.extraInfo != null) {
                    Text(
                        check.extraInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (check.status) {
                            CheckStatus.OK -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            CheckStatus.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                }
            }
        }

        when (check.status) {
            CheckStatus.Checking -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            else -> {}
        }
    }
}
