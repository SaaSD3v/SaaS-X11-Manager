package com.saas.x11manager.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.FixSettings
import com.saas.x11manager.util.PulseAudioFixManager
import kotlinx.coroutines.launch

@Composable
internal fun FixesDialog(
    containerName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember(containerName) {
        mutableStateOf(FixSettings.isPulseAudioEnabled(context, containerName))
    }
    var busy by remember(containerName) { mutableStateOf(false) }
    var status by remember(containerName) { mutableStateOf<String?>(null) }
    var lastSucceeded by remember(containerName) { mutableStateOf<Boolean?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Build, contentDescription = null) },
        title = { Text("Fixes") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PulseAudio fix", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (enabled) "Enabled" else "Disabled by default",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    } else {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { requested ->
                                busy = true
                                status = if (requested) {
                                    "Detecting DroidSpaces/Termux and applying the audio fix..."
                                } else {
                                    "Removing the Manager-owned audio integration..."
                                }
                                lastSucceeded = null

                                scope.launch {
                                    val result = if (requested) {
                                        PulseAudioFixManager.enable(containerName)
                                    } else {
                                        PulseAudioFixManager.disable(containerName)
                                    }
                                    enabled = FixSettings.isPulseAudioEnabled(context, containerName)
                                    status = buildString {
                                        append(result.message)
                                        val tail = result.details
                                            .filter { it.startsWith("[-]") || it.startsWith("[!]") }
                                            .takeLast(3)
                                        if (tail.isNotEmpty()) {
                                            append("\n")
                                            append(tail.joinToString("\n"))
                                        }
                                    }
                                    lastSucceeded = result.success
                                    busy = false
                                }
                            }
                        )
                    }
                }

                Text(
                    "When enabled, the Manager uses the bundled proven audio helper: it prefers the DroidSpaces native PulseAudio socket, detects AAudio/OpenSL ES, installs only missing apt/apk audio clients, and uses 127.0.0.1 TCP only as a safe host-network fallback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!enabled && status == null) {
                    Text(
                        "Nothing is installed or changed until this switch is turned on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                status?.let { message ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (lastSucceeded) {
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Close")
            }
        }
    )
}
