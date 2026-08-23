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
import com.saas.x11manager.util.AlpineInstallProfile
import com.saas.x11manager.util.AlpineInstallProfileOverride
import com.saas.x11manager.util.AptInstallRecommendationOverride
import com.saas.x11manager.util.ContainerPlatform
import com.saas.x11manager.util.ContainerStatus
import com.saas.x11manager.util.GraphicProtocol
import com.saas.x11manager.util.GraphicSession
import com.saas.x11manager.util.GraphicSessionCatalogMode
import com.saas.x11manager.util.InitSystem
import com.saas.x11manager.util.X11SessionManager
import kotlinx.coroutines.launch

private enum class RunningWarningMode {
    ENTRY,
    CONFIGURATION
}

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
        viewModel.dismissConfigurationWizard()
        viewModel.load(containerName, context.cacheDir)
    }

    val name = viewModel.name
    val status = viewModel.status
    val initSystem = viewModel.initSystem
    val graphicSession = viewModel.graphicSession
    val isInstalling = viewModel.isInstallingSession
    val isSaving = viewModel.isSaving
    val saveError = viewModel.saveError

    var runningWarningMode by remember(containerName) {
        mutableStateOf<RunningWarningMode?>(null)
    }
    var entryRunningWarningHandled by remember(containerName) { mutableStateOf(false) }
    var installedActionSession by remember { mutableStateOf<GraphicSession?>(null) }
    var selectingInstalledSession by remember { mutableStateOf<GraphicSession?>(null) }
    var quickStartingSelectedSession by remember { mutableStateOf(false) }
    var aptRecommendationSession by remember { mutableStateOf<GraphicSession?>(null) }
    var activeAptRecommendationOverrideSession by remember { mutableStateOf<GraphicSession?>(null) }
    var alpineProfileSession by remember { mutableStateOf<GraphicSession?>(null) }
    var activeAlpineProfileOverrideSession by remember { mutableStateOf<GraphicSession?>(null) }

    fun requestSessionInstall(session: GraphicSession) {
        when (viewModel.containerCapabilities?.platform) {
            ContainerPlatform.UBUNTU -> aptRecommendationSession = session
            ContainerPlatform.ALPINE -> alpineProfileSession = session
            null -> viewModel.configureWizardSession(session)
        }
    }

    fun startAptInstall(session: GraphicSession, installRecommendedPackages: Boolean) {
        AptInstallRecommendationOverride.set(session, installRecommendedPackages)
        activeAptRecommendationOverrideSession = session
        aptRecommendationSession = null
        viewModel.configureWizardSession(session)
    }

    fun startAlpineInstall(session: GraphicSession, profile: AlpineInstallProfile) {
        AlpineInstallProfileOverride.set(session, profile)
        activeAlpineProfileOverrideSession = session
        alpineProfileSession = null
        viewModel.configureWizardSession(session)
    }

    LaunchedEffect(isInstalling, activeAptRecommendationOverrideSession) {
        val session = activeAptRecommendationOverrideSession
        if (session != null && !isInstalling) {
            AptInstallRecommendationOverride.clear(session)
            activeAptRecommendationOverrideSession = null
        }
    }

    LaunchedEffect(isInstalling, activeAlpineProfileOverrideSession) {
        val session = activeAlpineProfileOverrideSession
        if (session != null && !isInstalling) {
            AlpineInstallProfileOverride.clear(session)
            activeAlpineProfileOverrideSession = null
        }
    }

    DisposableEffect(activeAptRecommendationOverrideSession) {
        val session = activeAptRecommendationOverrideSession
        onDispose {
            if (session != null) AptInstallRecommendationOverride.clear(session)
        }
    }

    DisposableEffect(activeAlpineProfileOverrideSession) {
        val session = activeAlpineProfileOverrideSession
        onDispose {
            if (session != null) AlpineInstallProfileOverride.clear(session)
        }
    }

    LaunchedEffect(name, status, containerName) {
        if (!entryRunningWarningHandled && name == containerName) {
            when (status) {
                ContainerStatus.RUNNING -> {
                    entryRunningWarningHandled = true
                    viewModel.dismissConfigurationWizard()
                    runningWarningMode = RunningWarningMode.ENTRY
                }

                ContainerStatus.STOPPED -> entryRunningWarningHandled = true
                ContainerStatus.UNKNOWN -> Unit
            }
        }
    }

    val effectiveRunningWarningMode = runningWarningMode ?: if (
        viewModel.wizardStage == ConfigurationWizardStage.RUNNING_WARNING
    ) {
        RunningWarningMode.CONFIGURATION
    } else {
        null
    }

    when {
        viewModel.showInstallTerminal -> {
            TerminalDialog(
                title = viewModel.sessionOperationTitle,
                logs = viewModel.installLogs,
                onDismiss = {
                    viewModel.dismissInstallTerminal()
                    onDismiss()
                },
                onClear = { viewModel.clearInstallLogs() },
                isBlocking = isInstalling,
                primaryActionLabel = if (viewModel.canStartGraphicSessionFromInstall) {
                    "Start ${graphicSession.protocol.label}"
                } else {
                    null
                },
                onPrimaryAction = if (viewModel.canStartGraphicSessionFromInstall) {
                    { viewModel.quickStartGraphicSession() }
                } else {
                    null
                },
                primaryActionEnabled = !isInstalling
            )
        }

        viewModel.wizardError != null -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Configuration stopped") },
                text = { Text(viewModel.wizardError ?: "Configuration could not continue.") },
                confirmButton = { Button(onClick = onDismiss) { Text("OK") } }
            )
        }

        effectiveRunningWarningMode != null -> {
            val isEntryWarning = effectiveRunningWarningMode == RunningWarningMode.ENTRY
            AlertDialog(
                onDismissRequest = {
                    if (!viewModel.isPreparingWizard) {
                        runningWarningMode = null
                        viewModel.dismissConfigurationWizard()
                        if (isEntryWarning) onDismiss()
                    }
                },
                title = {
                    Text(if (isEntryWarning) "Container is running" else "Replace graphic session")
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "This container is currently running with ${graphicSession.label} " +
                                "using ${initSystem.name.lowercase()}."
                        )
                        if (isEntryWarning) {
                            Text(
                                "You can inspect its current settings while it is running. " +
                                    "Changing the init system or graphic session will require stopping it first."
                            )
                            Text("Continue to Edit Container?")
                        } else {
                            Text(
                                "The distribution has been detected. To safely change the init backend, protocol " +
                                    "or graphic session, the container will be stopped before continuing."
                            )
                            Text(
                                "Next you will choose the init backend, X11 or Wayland, Stable or Experimental, " +
                                    "and then a session compatible with the detected distro and architecture."
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            runningWarningMode = null
                            viewModel.dismissConfigurationWizard()
                            if (isEntryWarning) onDismiss()
                        },
                        enabled = !viewModel.isPreparingWizard
                    ) {
                        Text(if (isEntryWarning) "Back" else "Cancel")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            runningWarningMode = null
                            if (isEntryWarning) {
                                viewModel.dismissConfigurationWizard()
                            } else {
                                viewModel.confirmRunningContainerRestart()
                            }
                        },
                        enabled = !viewModel.isPreparingWizard
                    ) {
                        if (!isEntryWarning && viewModel.isPreparingWizard) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isEntryWarning) "Edit anyway" else "Advance")
                    }
                }
            )
        }

        aptRecommendationSession != null -> {
            val session = requireNotNull(aptRecommendationSession)
            AlertDialog(
                onDismissRequest = {
                    aptRecommendationSession = null
                    viewModel.returnToWizardSessionSelection()
                },
                title = { Text("APT package options") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Choose how APT should install ${session.label}.")
                        Text(
                            "Recommended lets APT include packages marked as recommended by the selected packages. " +
                                "This can install a larger desktop stack."
                        )
                        Text("No recommends installs the explicit session plan and required dependencies only.")
                        Text("This choice applies only to this installation or reinstall.")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        aptRecommendationSession = null
                        viewModel.returnToWizardSessionSelection()
                    }) { Text("Back") }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            startAptInstall(session, installRecommendedPackages = false)
                        }) { Text("No recommends") }
                        Button(onClick = {
                            startAptInstall(session, installRecommendedPackages = true)
                        }) { Text("Recommended") }
                    }
                }
            )
        }

        alpineProfileSession != null -> {
            val session = requireNotNull(alpineProfileSession)
            AlertDialog(
                onDismissRequest = {
                    alpineProfileSession = null
                    viewModel.returnToWizardSessionSelection()
                },
                title = { Text("Alpine package options") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Choose how apk should install ${session.label}.")
                        Text("Minimal keeps the current session package plan exactly as defined.")
                        Text(
                            "Full installs the same session plan plus common desktop integration: " +
                                "D-Bus support, XDG utilities, fonts and icon themes."
                        )
                        Text("This choice applies only to this installation or reinstall.")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        alpineProfileSession = null
                        viewModel.returnToWizardSessionSelection()
                    }) { Text("Back") }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            startAlpineInstall(session, AlpineInstallProfile.MINIMAL)
                        }) { Text("Minimal") }
                        Button(onClick = {
                            startAlpineInstall(session, AlpineInstallProfile.FULL)
                        }) { Text("Full") }
                    }
                }
            )
        }

        installedActionSession != null -> {
            val session = requireNotNull(installedActionSession)
            val selectedInit = viewModel.pendingWizardInitSystem ?: initSystem
            AlertDialog(
                onDismissRequest = {
                    installedActionSession = null
                    viewModel.returnToWizardSessionSelection()
                },
                title = { Text(session.label) },
                text = {
                    Text(
                        "${session.label} is already installed for ${session.protocol.label}. Select changes only " +
                            "the default graphic session and ${selectedInit.name.lowercase()} startup configuration, " +
                            "without running apk/apt again. Reinstall runs the full installer."
                    )
                },
                dismissButton = {
                    TextButton(onClick = {
                        installedActionSession = null
                        viewModel.returnToWizardSessionSelection()
                    }) { Text("Back") }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            installedActionSession = null
                            requestSessionInstall(session)
                        }) { Text("Reinstall") }
                        Button(onClick = {
                            installedActionSession = null
                            viewModel.selectInitSystem(selectedInit)
                            if (viewModel.graphicSession != session) {
                                viewModel.toggleSessionSelection(session)
                            }
                            viewModel.dismissConfigurationWizard()
                            selectingInstalledSession = session
                            viewModel.save()
                        }) { Text("Select") }
                    }
                }
            )
        }

        selectingInstalledSession != null -> {
            val session = requireNotNull(selectingInstalledSession)
            val selectionSucceeded = !isSaving && saveError?.startsWith("OK") == true
            val selectionFailed = !isSaving && saveError != null && !selectionSucceeded
            AlertDialog(
                onDismissRequest = {
                    if (!isSaving && !quickStartingSelectedSession) selectingInstalledSession = null
                },
                title = { Text("Selecting ${session.label}") },
                text = {
                    when {
                        isSaving -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
                        ) { Text("Done") }
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
                                        // The outer integrated X11 server is also the host
                                        // transport used by nested Wayland sessions.
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
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Start ${session.protocol.label}")
                        }

                        selectionFailed -> Button(onClick = { selectingInstalledSession = null }) {
                            Text("OK")
                        }
                    }
                }
            )
        }

        viewModel.wizardStage == ConfigurationWizardStage.DISTRIBUTION_SELECTION -> {
            val capabilities = viewModel.containerCapabilities
            val packageLabel = when (capabilities?.platform) {
                ContainerPlatform.ALPINE -> "apk"
                ContainerPlatform.UBUNTU -> "apt/dpkg"
                null -> "unknown package manager"
            }
            WizardChoiceDialog(
                title = "Detected distribution",
                subtitle = "Detected from the running container. Confirm to continue with its package and session catalog.",
                onDismiss = { viewModel.dismissConfigurationWizard() }
            ) {
                WizardChoice(
                    title = capabilities?.distributionDisplayName ?: "Unknown distribution",
                    subtitle = "${capabilities?.distribution?.label ?: "Unknown"} · " +
                        "${capabilities?.architectureDisplayName ?: "unknown arch"} · $packageLabel",
                    selected = true,
                    installed = false,
                    onClick = { viewModel.confirmDetectedDistribution() }
                )
            }
        }

        viewModel.wizardStage == ConfigurationWizardStage.INIT_SELECTION -> {
            val capabilities = viewModel.containerCapabilities
            val packageLabel = when (capabilities?.platform) {
                ContainerPlatform.ALPINE -> "apk"
                ContainerPlatform.UBUNTU -> "apt/dpkg"
                null -> "unknown"
            }
            val initSystems = viewModel.availableWizardInitSystems()
            val pendingInit = viewModel.pendingWizardInitSystem ?: initSystem
            WizardChoiceDialog(
                title = "Choose init system",
                subtitle = "${capabilities?.distributionDisplayName ?: "Container"} · $packageLabel · " +
                    "${capabilities?.architectureDisplayName ?: "unknown arch"}. Only detected init backends are shown.",
                onDismiss = { viewModel.dismissConfigurationWizard() },
                secondaryActionLabel = "Back",
                onSecondaryAction = { viewModel.backToWizardDistributionSelection() }
            ) {
                initSystems.forEachIndexed { index, system ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    WizardChoice(
                        title = if (system == InitSystem.SYSTEMD) "systemd" else "OpenRC",
                        subtitle = "Detected in this container",
                        selected = pendingInit == system,
                        installed = false,
                        onClick = { viewModel.selectWizardInitSystem(system) }
                    )
                }
            }
        }

        viewModel.wizardStage == ConfigurationWizardStage.PROTOCOL_SELECTION -> {
            val selectedInit = viewModel.pendingWizardInitSystem ?: initSystem
            val capabilities = viewModel.containerCapabilities
            WizardChoiceDialog(
                title = "Choose graphic protocol",
                subtitle = "${if (selectedInit == InitSystem.OPENRC) "OpenRC" else "systemd"} · " +
                    "${capabilities?.distributionDisplayName ?: "container"} · " +
                    "${capabilities?.architectureDisplayName ?: "unknown arch"}",
                onDismiss = { viewModel.dismissConfigurationWizard() },
                secondaryActionLabel = "Back",
                onSecondaryAction = { viewModel.backToWizardInitSelection() }
            ) {
                WizardChoice(
                    title = "X11",
                    subtitle = "Direct X11 window managers and desktop sessions on the Manager display.",
                    selected = viewModel.pendingWizardProtocol == GraphicProtocol.X11,
                    installed = false,
                    onClick = { viewModel.selectWizardProtocol(GraphicProtocol.X11) }
                )
                Spacer(Modifier.height(10.dp))
                WizardChoice(
                    title = "Wayland",
                    subtitle = "Native Wayland compositors using the integrated X11 display only as the outer Android transport.",
                    selected = viewModel.pendingWizardProtocol == GraphicProtocol.WAYLAND,
                    installed = false,
                    onClick = { viewModel.selectWizardProtocol(GraphicProtocol.WAYLAND) }
                )
            }
        }

        viewModel.wizardStage == ConfigurationWizardStage.CATALOG_SELECTION -> {
            val selectedInit = viewModel.pendingWizardInitSystem ?: initSystem
            val capabilities = viewModel.containerCapabilities
            WizardChoiceDialog(
                title = "Choose session catalog",
                subtitle = "${if (selectedInit == InitSystem.OPENRC) "OpenRC" else "systemd"} · " +
                    "${viewModel.pendingWizardProtocol.label} · ${capabilities?.distributionDisplayName ?: "container"}",
                onDismiss = { viewModel.dismissConfigurationWizard() },
                secondaryActionLabel = "Back",
                onSecondaryAction = { viewModel.backToWizardProtocolSelection() }
            ) {
                WizardChoice(
                    title = "Stable",
                    subtitle = "Sessions using normal supported repositories for the detected distro.",
                    selected = viewModel.pendingWizardCatalogMode == GraphicSessionCatalogMode.STABLE,
                    installed = false,
                    onClick = { viewModel.selectWizardCatalogMode(GraphicSessionCatalogMode.STABLE) }
                )
                Spacer(Modifier.height(10.dp))
                WizardChoice(
                    title = "Experimental",
                    subtitle = "Stable sessions plus options that may require testing or experimental repositories.",
                    selected = viewModel.pendingWizardCatalogMode == GraphicSessionCatalogMode.EXPERIMENTAL,
                    installed = false,
                    onClick = { viewModel.selectWizardCatalogMode(GraphicSessionCatalogMode.EXPERIMENTAL) }
                )
            }
        }

        viewModel.wizardStage == ConfigurationWizardStage.SESSION_SELECTION -> {
            val selectedInit = viewModel.pendingWizardInitSystem ?: initSystem
            val capabilities = viewModel.containerCapabilities
            val catalogLabel = when (viewModel.pendingWizardCatalogMode) {
                GraphicSessionCatalogMode.STABLE -> "Stable"
                GraphicSessionCatalogMode.EXPERIMENTAL -> "Experimental"
            }
            WizardChoiceDialog(
                title = "Choose graphic session",
                subtitle = "${if (selectedInit == InitSystem.OPENRC) "OpenRC" else "systemd"} · " +
                    "${viewModel.pendingWizardProtocol.label} · $catalogLabel · " +
                    "${capabilities?.distributionDisplayName ?: "container"} · " +
                    "${capabilities?.architectureDisplayName ?: "unknown arch"}",
                onDismiss = { viewModel.dismissConfigurationWizard() },
                secondaryActionLabel = "Back",
                onSecondaryAction = { viewModel.backToWizardCatalogSelection() }
            ) {
                val sessions = viewModel.wizardSessions()
                if (sessions.isEmpty()) {
                    Text(
                        "No ${viewModel.pendingWizardProtocol.label} sessions are enabled for this distro, " +
                            "architecture and catalog.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                sessions.forEachIndexed { index, session ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    val installed = viewModel.isSessionInstalled(session)
                    val experimental = viewModel.isWizardSessionExperimental(session)
                    val transport = if (session.protocol == GraphicProtocol.WAYLAND) {
                        " · nested on integrated X11 transport"
                    } else {
                        ""
                    }
                    WizardChoice(
                        title = session.label,
                        subtitle = when {
                            installed && graphicSession == session && experimental ->
                                "Installed · Current default · Experimental$transport"
                            installed && graphicSession == session ->
                                "Installed · Current default$transport"
                            installed && experimental ->
                                "Installed · Experimental · tap for Select or Reinstall$transport"
                            installed ->
                                "Installed · tap for Select or Reinstall$transport"
                            experimental ->
                                "Experimental · tap to install and apply$transport"
                            else -> "Tap to install and apply$transport"
                        },
                        selected = graphicSession == session,
                        installed = installed,
                        onClick = {
                            if (installed) {
                                viewModel.dismissConfigurationWizard()
                                installedActionSession = session
                            } else {
                                requestSessionInstall(session)
                            }
                        }
                    )
                }
            }
        }
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
                            "Detect the container first, then choose init system, graphical protocol, catalog and session. " +
                                "Installed sessions can be selected again without reinstalling packages.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        HorizontalDivider()
                        SummaryRow("Status", statusLabel(status))
                        viewModel.containerCapabilities?.let {
                            SummaryRow("Distribution", it.distributionDisplayName)
                            SummaryRow("Architecture", it.architectureDisplayName)
                        }
                        SummaryRow("Init system", initSystem.name.lowercase())
                        SummaryRow("Protocol", graphicSession.protocol.label)
                        SummaryRow("Graphic session", graphicSession.label)
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                installedActionSession = null
                                selectingInstalledSession = null
                                viewModel.startConfigurationWizard()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isInstalling && !viewModel.isPreparingWizard && !isSaving
                        ) {
                            if (viewModel.isPreparingWizard) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Detecting container...")
                            } else {
                                Text("Change configuration")
                            }
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
            Column(modifier = Modifier.padding(20.dp)) {
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
                        TextButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
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
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
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
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    }
}

private fun statusLabel(status: ContainerStatus): String = when (status) {
    ContainerStatus.RUNNING -> "Running"
    ContainerStatus.STOPPED -> "Stopped"
    ContainerStatus.UNKNOWN -> "Unknown"
}
