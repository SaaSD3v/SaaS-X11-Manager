package com.saas.x11manager.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saas.x11manager.ui.component.TerminalDialog
import com.saas.x11manager.util.ContainerStatus
import com.saas.x11manager.util.GraphicSession
import com.saas.x11manager.util.InitSystem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContainerScreen(
    containerName: String,
    onDismiss: () -> Unit,
    viewModel: EditContainerViewModel = viewModel()
) {
    val context = LocalContext.current

    LaunchedEffect(containerName) {
        viewModel.load(containerName, context.cacheDir)
    }

    val name = viewModel.name
    val status = viewModel.status
    val initSystem = viewModel.initSystem
    val graphicSession = viewModel.graphicSession
    val isInstalling = viewModel.isInstallingSession

    LaunchedEffect(name, viewModel.wizardStarted) {
        if (name.isNotEmpty() && !viewModel.wizardStarted) {
            viewModel.startConfigurationWizard()
        }
    }

    if (viewModel.showInstallTerminal) {
        TerminalDialog(
            title = viewModel.sessionOperationTitle,
            logs = viewModel.installLogs,
            onDismiss = {
                viewModel.dismissInstallTerminal()
                onDismiss()
            },
            onClear = { viewModel.clearInstallLogs() },
            isBlocking = isInstalling,
            primaryActionLabel = if (viewModel.canStartX11FromInstall) "Start X11" else null,
            onPrimaryAction = if (viewModel.canStartX11FromInstall) {
                { viewModel.quickStartX11() }
            } else {
                null
            },
            primaryActionEnabled = !isInstalling
        )
    }

    if (viewModel.wizardError != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Configuration stopped") },
            text = { Text(viewModel.wizardError ?: "Configuration could not continue.") },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text("OK")
                }
            }
        )
    }

    when (viewModel.wizardStage) {
        ConfigurationWizardStage.RUNNING_WARNING -> {
            AlertDialog(
                onDismissRequest = {
                    if (!viewModel.isPreparingWizard) {
                        viewModel.dismissConfigurationWizard()
                        onDismiss()
                    }
                },
                title = { Text("Container restart required") },
                text = {
                    Text(
                        "This container is currently running. Continuing will stop it before changing " +
                            "the init system and graphic session. After installation you can start it " +
                            "again immediately with Start X11."
                    )
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            viewModel.dismissConfigurationWizard()
                            onDismiss()
                        },
                        enabled = !viewModel.isPreparingWizard
                    ) {
                        Text("Cancel")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.confirmRunningContainerRestart() },
                        enabled = !viewModel.isPreparingWizard
                    ) {
                        if (viewModel.isPreparingWizard) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Continue")
                    }
                }
            )
        }

        ConfigurationWizardStage.INIT_SELECTION -> {
            WizardChoiceDialog(
                title = "Choose init system",
                subtitle = "This choice controls which Graphic Session catalog is shown next.",
                onDismiss = {
                    viewModel.dismissConfigurationWizard()
                    onDismiss()
                }
            ) {
                WizardChoice(
                    title = "systemd",
                    subtitle = "Shows Debian/Ubuntu apt/dpkg session plans",
                    selected = initSystem == InitSystem.SYSTEMD,
                    onClick = { viewModel.selectWizardInitSystem(InitSystem.SYSTEMD) }
                )
                Spacer(Modifier.height(10.dp))
                WizardChoice(
                    title = "OpenRC",
                    subtitle = "Shows Alpine apk session plans",
                    selected = initSystem == InitSystem.OPENRC,
                    onClick = { viewModel.selectWizardInitSystem(InitSystem.OPENRC) }
                )
            }
        }

        ConfigurationWizardStage.SESSION_SELECTION -> {
            val selectedInit = viewModel.pendingWizardInitSystem ?: initSystem
            WizardChoiceDialog(
                title = "Choose graphic session",
                subtitle = if (selectedInit == InitSystem.OPENRC) {
                    "OpenRC selected · Alpine/apk catalog"
                } else {
                    "systemd selected · Debian/Ubuntu apt/dpkg catalog"
                },
                onDismiss = {
                    viewModel.dismissConfigurationWizard()
                    onDismiss()
                },
                secondaryActionLabel = "Back",
                onSecondaryAction = { viewModel.backToWizardInitSelection() }
            ) {
                val sessions = viewModel.wizardSessions()
                sessions.forEachIndexed { index, session ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    WizardChoice(
                        title = session.label,
                        subtitle = if (viewModel.isSessionInstalled(session)) {
                            "Installed · selecting will reinstall and apply it"
                        } else {
                            "Tap to install and apply"
                        },
                        selected = graphicSession == session,
                        onClick = { viewModel.configureWizardSession(session) }
                    )
                }
            }
        }

        ConfigurationWizardStage.HIDDEN -> Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.ifEmpty { containerName }) },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isInstalling && !viewModel.isPreparingWizard
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (name.isEmpty()) {
                CircularProgressIndicator()
            } else {
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
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Guided configuration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Configuration is handled step by step in the pop-up flow. " +
                                "There are no separate Install or Save steps on this screen.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        HorizontalDivider()
                        SummaryRow("Status", statusLabel(status))
                        SummaryRow("Init system", initSystem.name.lowercase())
                        SummaryRow("Graphic session", graphicSession.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardChoiceDialog(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState()),
                    content = content
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        TextButton(onClick = onSecondaryAction) {
                            Text(secondaryActionLabel)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
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
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Text(
            value,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp
        )
    }
}

private fun statusLabel(status: ContainerStatus): String = when (status) {
    ContainerStatus.RUNNING -> "Running"
    ContainerStatus.STOPPED -> "Stopped"
    ContainerStatus.UNKNOWN -> "Unknown"
}
