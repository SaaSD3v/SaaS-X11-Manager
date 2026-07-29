package com.saas.x11manager.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saas.x11manager.util.ContainerStatus

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
    val hostname = viewModel.hostname
    val status = viewModel.status
    val enablePulseAudio = viewModel.enablePulseAudio
    val logs = viewModel.logs
    val isSaving = viewModel.isSaving
    val saveError = viewModel.saveError

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
                    Column(Modifier.weight(1f)) {
                        Text("PulseAudio Fix", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        Text("Adds PA socket bind mount", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    Switch(
                        checked = enablePulseAudio,
                        onCheckedChange = { viewModel.enablePulseAudio = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
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
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }

            val buttonColor by animateColorAsState(
                targetValue = if (isSaving) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primary,
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
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Save", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (saveError != null) {
                val isError = !saveError.contains("OK")
                Text(
                    saveError,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
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
