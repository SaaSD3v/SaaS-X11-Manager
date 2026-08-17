package com.saas.x11manager.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import com.saas.x11manager.util.X11SessionManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContainerScreen(
    containerName: String,
    onDismiss: () -> Unit,
    viewModel: EditContainerViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(containerName) {
        viewModel.load(containerName, context.cacheDir)
    }

    val name = viewModel.name
    val status = viewModel.status
    val initSystem = viewModel.initSystem
    val graphicSession = viewModel.graphicSession
    val isInstalling = viewModel.isInstallingSession
    val isSaving = viewModel.isSaving
    val saveError = viewModel.saveError

    var showManualRestartWarning by remember { mutableStateOf(false) }
    var installedActionSession by remember { mutableStateOf<GraphicSession?>(null) }
    var selectingInstalledSession by remember { mutableStateOf<GraphicSession?>(null) }
    var quickStartingSelectedSession by remember { mutableStateOf(false) }

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

    if (viewModel.wizardStage == ConfigurationWizardStage.RUNNING_WARNING || showManualRestartWarning) {
        AlertDialog(
            onDismissRequest = {
                if (!viewModel.isPreparingWizard) {
                    showManualRestartWarning = false
                    viewModel.dismissConfigurationWizard()
                }
            },
            title = { Text("Replace graphic session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This container is currently running with ${graphicSession.label} " +
                            "using ${initSystem.name.lowercase()}."
                    )
                    Text(
                        "You are about to replace the current graphic session or change its init setup. " +
                            "To apply the change safely, the container will be stopped before you continue."
                    )
                    Text(
                        "Next you will choose systemd or OpenRC and then the graphic session. " +
                            "Sessions that are already installed can be selected without downloading packages again, " +
                            "or reinstalled if you want to run the full installer again."
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showManualRestartWarning = false
                        viewModel.dismissConfigurationWizard()
                    },
                    enabled = !viewModel.isPreparingWizard
                ) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showManualRestartWarning = false
                        viewModel.confirmRunningContainerRestart()
                    },
                    enabled = !viewModel.isPreparingWizard
                ) {
                    if (viewModel.isPreparingWizard) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Advance")
                }
            }
        )
    }

    when (viewModel.wizardStage) {
        ConfigurationWizardStage.RUNNING_WARNING -> Unit

        ConfigurationWizardStage.INIT_SELECTION -> {
            WizardChoiceDialog(
                title = "Choose init system",
                subtitle = "This choice controls which Graphic Session catalog is shown next.",
                onDismiss = { viewModel.dismissConfigurationWizard() }
            ) {
                WizardChoice(
                    title = "systemd",
                    subtitle = "Shows Debian/Ubuntu apt/dpkg session plans",
                    selected = initSystem == InitSystem.SYSTEMD,
                    installed = false,
                    onClick = { viewModel.selectWizardInitSystem(InitSystem.SYSTEMD) }
                )
                Spacer(Modifier.height(10.dp))
                WizardChoice(
                    title = "OpenRC",
                    subtitle = "Shows Alpine apk session plans",
                    selected = initSystem == InitSystem.OPENRC,
                    installed = false,
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
                onDismiss = { viewModel.dismissConfigurationWizard() },
                secondaryActionLabel = "Back",
                onSecondaryAction = { viewModel.backToWizardInitSelection() }
            ) {
                val sessions = viewModel.wizardSessions()
                sessions.forEachIndexed { index, session ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    val installed = viewModel.isSessionInstalled(session)
                    WizardChoice(
                        title = session.label,
                        subtitle = when {
                            installed && graphicSession == session -> "Installed · Current default"
                            installed -> "Installed · tap for Select or Reinstall"
                            else -> "Tap to install and apply"
                        },
                        selected = graphicSession == session,
                        installed = installed,
                        onClick = {
                            if (installed) {
                                installedActionSession = session
                            } else {
                                viewModel.configureWizardSession(session)
                            }
                        }
                    )
                }
            }
        }

        ConfigurationWizardStage.HIDDEN -> Unit
    }

    installedActionSession?.let { session ->
        val selectedInit = viewModel.pendingWizardInitSystem ?: initSystem
        AlertDialog(
            onDismissRequest = { installedActionSession = null },
            title = { Text(session.label) },
            text = {
                Text(
                    "${session.label} is already installed. Select changes only the default graphic session " +
                        "and ${selectedInit.name.lowercase()} startup configuration, without running apk/apt or " +
                        "downloading packages. Reinstall runs the full installer again."
                )
            },
            dismissButton = {
                TextButton(onClick = { installedActionSession = null }) {
                    Text("Back")
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            installedActionSession = null
                            viewModel.configureWizardSession(session)
                        }
                    ) {
                        Text("Reinstall")
                    }
                    Button(
                        onClick = {
                            installedActionSession = null
                            viewModel.selectInitSystem(selectedInit)
                            if (viewModel.graphicSession != session) {
                                viewModel.toggleSessionSelection(session)
                            }
                            viewModel.dismissConfigurationWizard()
                            selectingInstalledSession = session
                            viewModel.save()
                        }
                    ) {
                        Text("Select")
                    }
                }
            }
        )
    }

    selectingInstalledSession?.let { session ->
        val selectionSucceeded = !isSaving && saveError?.startsWith("OK") == true
        val selectionFailed = !isSaving && saveError != null && !selectionSucceeded
        AlertDialog(
            onDismissRequest = {
                if (!isSaving && !quickStartingSelectedSession) {
                    selectingInstalledSession = null
                }
            },
            title = { Text("Selecting ${session.label}") },
            text = {
                when {
                    isSaving -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Applying the existing installation without downloading packages...")
                    }
                    selectionSucceeded -> Text(
                        "${session.label} is now the default graphic session. No package installation command was run."
                    )
                    selectionFailed -> Text(saveError ?: "Could not apply ${session.label}.")
                    else -> Text("Preparing configuration...")
                }
            },
            dismissButton = if (selectionSucceeded) {
                {
                    TextButton(
                        onClick = { selectingInstalledSession = null },
                        enabled = !quickStartingSelectedSession
                    ) {
                        Text("Done")
                    }
                }
            } else {
                {}
            },
            confirmButton = {
                when {
                    selectionSucceeded -> Button(
                        onClick = {
                            quickStartingSelectedSession = true
                            scope.launch {
                                try {
                                    X11SessionManager.startX11Session(containerName = containerName)
                                    onDismiss()
                                } finally {
                                    quickStartingSelectedSession = false
                                }
                            }
                        },
                        enabled = !quickStartingSelectedSession
                    ) {
                        if (quickStartingSelectedSession) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Start X11")
                    }
                    selectionFailed -> Button(onClick = { selectingInstalledSession = null }) {
                        Text("OK")
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.ifEmpty { containerName }) },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isInstalling && !viewModel.isPreparingWizard && !isSaving
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
                            "Use the guided flow to change the init system or graphic session. " +
                                "Installed sessions can be selected again without reinstalling packages.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        HorizontalDivider()
                        SummaryRow("Status", statusLabel(status))
                        SummaryRow("Init system", initSystem.name.lowercase())
                        SummaryRow("Graphic session", graphicSession.label)
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                if (status == ContainerStatus.RUNNING) {
                                    showManualRestartWarning = true
                                } else {
                                    viewModel.backToWizardInitSelection()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isInstalling && !viewModel.isPreparingWizard && !isSaving
                        ) {
                            Text("Change configuration")
                        }
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
    installed: Boolean,
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
            if (installed) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Installed",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
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
