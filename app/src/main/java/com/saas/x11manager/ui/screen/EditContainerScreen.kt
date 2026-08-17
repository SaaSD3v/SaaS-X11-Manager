package com.saas.x11manager.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saas.x11manager.ui.component.TerminalDialog
import com.saas.x11manager.util.ContainerPlatform
import com.saas.x11manager.util.ContainerStatus
import com.saas.x11manager.util.GraphicSession
import com.saas.x11manager.util.GraphicSessionInstallPlans
import com.saas.x11manager.util.GraphicSessionSupport
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
    val logs = viewModel.logs
    val isSaving = viewModel.isSaving
    val saveError = viewModel.saveError
    val isInstalling = viewModel.isInstallingSession
    val installResult = viewModel.installResult
    val installResultSession = viewModel.installResultSession

    fun sessionVisibleForSelectedInit(session: GraphicSession): Boolean {
        val packagePlatform = when (initSystem) {
            InitSystem.OPENRC -> ContainerPlatform.ALPINE
            InitSystem.SYSTEMD -> ContainerPlatform.UBUNTU
        }
        return GraphicSessionInstallPlans.forSelection(packagePlatform, session) != null
    }

    if (viewModel.showInstallTerminal) {
        TerminalDialog(
            title = viewModel.sessionOperationTitle,
            logs = viewModel.installLogs,
            onDismiss = { viewModel.dismissInstallTerminal() },
            onClear = { viewModel.clearInstallLogs() },
            isBlocking = isInstalling
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.ifEmpty { containerName }) },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !isInstalling) {
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Init System",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Select which init system starts the X11 session inside this container",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    val options = listOf(
                        InitSystem.SYSTEMD to "systemd",
                        InitSystem.OPENRC to "OpenRC"
                    )

                    for ((sys, label) in options) {
                        val selected = initSystem == sys
                        val borderColor by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            animationSpec = tween(200),
                            label = "initBorder"
                        )
                        val bgColor by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            animationSpec = tween(200),
                            label = "initBackground"
                        )

                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isInstalling) {
                                if (initSystem != sys) viewModel.selectInitSystem(sys)
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = bgColor,
                            border = BorderStroke(1.dp, borderColor)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = {
                                        if (!isInstalling && initSystem != sys) {
                                            viewModel.selectInitSystem(sys)
                                        }
                                    },
                                    enabled = !isInstalling,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary,
                                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                                    Text(
                                        when (sys) {
                                            InitSystem.SYSTEMD -> "Starts x11-session through systemd units"
                                            InitSystem.OPENRC -> "Starts x11-session through OpenRC services"
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                        if (sys != InitSystem.OPENRC) Spacer(Modifier.height(6.dp))
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Graphic Session",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap a session to expand or collapse its controls.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    val visibleSessions = GraphicSessionSupport.installableSessions.filter {
                        sessionVisibleForSelectedInit(it)
                    }

                    visibleSessions.forEachIndexed { index, session ->
                        key(session) {
                            if (index > 0) Spacer(Modifier.height(10.dp))
                            var expanded by rememberSaveable(containerName, session.name) {
                                mutableStateOf(false)
                            }
                            GraphicSessionCard(
                                session = session,
                                installed = viewModel.isSessionInstalled(session),
                                selected = graphicSession == session,
                                expanded = expanded,
                                isInstalling = isInstalling,
                                isSaving = isSaving,
                                nameAvailable = name.isNotEmpty(),
                                result = if (installResultSession == session) installResult else null,
                                onToggleExpanded = { expanded = !expanded },
                                onToggleSelection = { viewModel.toggleSessionSelection(session) },
                                onInstall = { viewModel.installSession(session) },
                                onVerify = { viewModel.verifySession(session) }
                            )
                        }
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Status:", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (status) {
                            ContainerStatus.RUNNING -> "Running"
                            ContainerStatus.STOPPED -> "Stopped"
                            else -> "Unknown"
                        },
                        color = when (status) {
                            ContainerStatus.RUNNING -> MaterialTheme.colorScheme.primary
                            ContainerStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            val buttonColor by animateColorAsState(
                targetValue = if (isSaving) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.primary,
                animationSpec = tween(200),
                label = "saveColor"
            )

            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                shape = RoundedCornerShape(20.dp),
                enabled = !isSaving && !isInstalling && name.isNotEmpty()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        "Save",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (saveError != null) {
                val isError = !saveError.contains("OK")
                Text(
                    saveError,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (logs.isNotEmpty()) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        for ((level, msg) in logs) {
                            Text(
                                msg,
                                color = when (level) {
                                    android.util.Log.ERROR -> MaterialTheme.colorScheme.error
                                    android.util.Log.WARN -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GraphicSessionCard(
    session: GraphicSession,
    installed: Boolean,
    selected: Boolean,
    expanded: Boolean,
    isInstalling: Boolean,
    isSaving: Boolean,
    nameAvailable: Boolean,
    result: String?,
    onToggleExpanded: () -> Unit,
    onToggleSelection: () -> Unit,
    onInstall: () -> Unit,
    onVerify: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isInstalling) { onToggleExpanded() },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = {
                    if (installed && !isInstalling && !isSaving) onToggleSelection()
                },
                enabled = installed && !isInstalling && !isSaving,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(session.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                Text(
                    when {
                        installed && selected -> "Installed · Selected"
                        installed -> "Installed · Not selected"
                        else -> "Not installed"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
            Text(
                if (expanded) "▲" else "▼",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }

    if (expanded) {
        Spacer(Modifier.height(10.dp))
        Text(
            if (installed) {
                "${session.label} can be verified, reinstalled, selected or deselected without uninstalling its packages."
            } else {
                "Install ${session.label} and its required X11 session packages. Installation logs stream in real time."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(10.dp))

        if (installed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onVerify,
                    modifier = Modifier.weight(1f).height(44.dp),
                    enabled = !isInstalling && !isSaving && nameAvailable,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Verify")
                }
                Button(
                    onClick = onInstall,
                    modifier = Modifier.weight(1f).height(44.dp),
                    enabled = !isInstalling && !isSaving && nameAvailable,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Reinstall")
                }
            }
        } else {
            Button(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                enabled = !isInstalling && !isSaving && nameAvailable,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Install")
            }
        }

        if (result != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                result,
                color = when {
                    result.startsWith("OK") -> MaterialTheme.colorScheme.primary
                    result.startsWith("Warning") -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
