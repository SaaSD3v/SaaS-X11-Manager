package com.saas.x11manager.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saas.x11manager.data.prefs.UserPreferences
import com.saas.x11manager.ui.component.TerminalConsole
import com.saas.x11manager.ui.component.TerminalLogger
import com.saas.x11manager.ui.theme.SaaSCatppuccin
import com.saas.x11manager.util.ContainerManager
import com.saas.x11manager.util.ContainerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContainerScreen(
    containerName: String,
    onDismiss: () -> Unit,
    preferences: UserPreferences,
    viewModel: EditContainerViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(containerName) {
        viewModel.load(containerName, context.cacheDir)
    }

    val name = viewModel.name
    val hostname = viewModel.hostname
    val status = viewModel.status
    val enablePulseAudio = viewModel.enablePulseAudio
    val logs = viewModel.logs
    val isSaving = viewModel.isSaving
    val saveError = viewModel.saveError

    val terminalColors = SaaSCatppuccin.Terminal
    val terminalColorsDark = SaaSCatppuccin.TerminalDark
    val palette = preferences.themePalette(terminalColors, terminalColorsDark)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.ifEmpty { containerName }) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
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
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // PulseAudio Fix toggle
            SettingsToggleCard(
                title = "PulseAudio Fix",
                description = "Adds PA socket bind mount",
                enabled = enablePulseAudio,
                palette = palette,
                onToggle = { viewModel.enablePulseAudio = it }
            )

            // Status row
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SaaSCatppuccin.Surface),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Status:", color = SaaSCatppuccin.Text, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (status) {
                            ContainerStatus.RUNNING -> "Running"
                            ContainerStatus.STOPPED -> "Stopped"
                            else -> "Unknown"
                        },
                        color = when (status) {
                            ContainerStatus.RUNNING -> SaaSCatppuccin.Green
                            ContainerStatus.STOPPED -> SaaSCatppuccin.Text
                            else -> SaaSCatppuccin.Overlay0
                        },
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Save button
            val buttonColor by animateColorAsState(
                targetValue = when {
                    isSaving -> SaaSCatppuccin.Surface1
                    saveError?.contains("OK") == true -> SaaSCatppuccin.Mauve
                    else -> SaaSCatppuccin.Mauve
                },
                animationSpec = tween(200)
            )

            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                shape = RoundedCornerShape(20.dp),
                enabled = !isSaving && name.isNotEmpty()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = SaaSCatppuccin.Text)
                } else {
                    Text("Save", color = SaaSCatppuccin.Base, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (saveError != null) {
                val isError = !saveError.contains("OK")
                Text(
                    saveError,
                    color = if (isError) SaaSCatppuccin.Red else SaaSCatppuccin.Green,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }

            // Logs
            if (logs.isNotEmpty()) {
                TerminalConsole(
                    logs = logs,
                    palette = palette,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 300.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsToggleCard(
    title: String,
    description: String,
    enabled: Boolean,
    palette: com.saas.x11manager.ui.theme.TerminalPalette,
    onToggle: (Boolean) -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SaaSCatppuccin.Surface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = SaaSCatppuccin.Text, fontSize = 14.sp)
                Text(description, color = SaaSCatppuccin.Subtext0, fontSize = 11.sp)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = SaaSCatppuccin.Mauve,
                    checkedTrackColor = SaaSCatppuccin.Surface0
                )
            )
        }
    }
}
