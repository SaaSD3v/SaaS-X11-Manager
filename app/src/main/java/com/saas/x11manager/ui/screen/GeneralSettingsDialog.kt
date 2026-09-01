package com.saas.x11manager.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.VncSettings

@Composable
fun GeneralSettingsDialog(
    containerName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val initialPort = remember(containerName) {
        VncSettings.getPort(context, containerName)
    }
    var portText by remember(containerName) { mutableStateOf(initialPort.toString()) }
    var saveError by remember(containerName) { mutableStateOf<String?>(null) }

    val parsedPort = portText.toIntOrNull()
    val validPort = parsedPort != null && VncSettings.isValidPort(parsedPort)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("General settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = containerName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "VNC",
                    style = MaterialTheme.typography.titleSmall
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { value ->
                        if (value.length <= 5 && value.all { it.isDigit() }) {
                            portText = value
                            saveError = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("VNC port") },
                    supportingText = {
                        Text(
                            if (validPort) {
                                "Default: ${VncSettings.DEFAULT_PORT}. Used by the external TigerVNC server."
                            } else {
                                "Enter a port from ${VncSettings.MIN_PORT} to ${VncSettings.MAX_PORT}."
                            }
                        )
                    },
                    isError = portText.isNotEmpty() && !validPort,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text(
                    text = "The Manager will use this port when VNC launch support is enabled. " +
                        "Changing it here does not start or install a VNC server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                saveError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(2.dp))

                OutlinedButton(
                    onClick = {
                        VncSettings.resetPort(context, containerName)
                        portText = VncSettings.DEFAULT_PORT.toString()
                        saveError = null
                    }
                ) {
                    Text("Reset to default")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = parsedPort ?: return@Button
                    if (VncSettings.setPort(context, containerName, port)) {
                        onDismiss()
                    } else {
                        saveError = "Could not save the VNC port."
                    }
                },
                enabled = validPort
            ) {
                Text("Save")
            }
        }
    )
}
