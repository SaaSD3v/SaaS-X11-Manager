package com.saas.x11manager.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    var showTigerVncSettings by remember(containerName) { mutableStateOf(false) }
    var portText by remember(containerName) {
        mutableStateOf(VncSettings.getPort(context, containerName).toString())
    }
    var geometryText by remember(containerName) {
        mutableStateOf(VncSettings.getGeometry(context, containerName))
    }
    var saveError by remember(containerName) { mutableStateOf<String?>(null) }

    if (showTigerVncSettings) {
        TigerVncSettingsDialog(
            containerName = containerName,
            onDismiss = {
                showTigerVncSettings = false
                geometryText = VncSettings.getGeometry(context, containerName)
            },
            onSaved = {
                showTigerVncSettings = false
                portText = VncSettings.getPort(context, containerName).toString()
                geometryText = VncSettings.getGeometry(context, containerName)
                saveError = null
            }
        )
        return
    }

    val parsedPort = portText.toIntOrNull()
    val validPort = parsedPort != null && VncSettings.isValidPort(parsedPort)
    val validGeometry = VncSettings.isValidGeometry(geometryText)
    val canSave = validPort && validGeometry

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

                OutlinedTextField(
                    value = geometryText,
                    onValueChange = { value ->
                        if (value.length <= 11 &&
                            value.all { it.isDigit() || it == 'x' || it == 'X' || it == ' ' }
                        ) {
                            geometryText = value
                            saveError = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("VNC resolution") },
                    supportingText = {
                        Text(
                            if (validGeometry) {
                                "Standalone TigerVNC virtual display. Default: ${VncSettings.DEFAULT_GEOMETRY}."
                            } else {
                                "Use WIDTHxHEIGHT, from ${VncSettings.MIN_DIMENSION} to ${VncSettings.MAX_DIMENSION} per side."
                            }
                        )
                    },
                    isError = geometryText.isNotEmpty() && !validGeometry,
                    singleLine = true
                )

                Text(
                    text = "Port and resolution are used when VNC launch support is enabled. " +
                        "Changing them here does not start or install a VNC server.",
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
                        VncSettings.resetGeneral(context, containerName)
                        portText = VncSettings.DEFAULT_PORT.toString()
                        geometryText = VncSettings.DEFAULT_GEOMETRY
                        saveError = null
                    }
                ) {
                    Text("Reset port & resolution")
                }

                OutlinedButton(
                    onClick = { showTigerVncSettings = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Advanced TigerVNC settings")
                }

                Text(
                    text = "Full-screen TigerVNC editor: security, sharing, clipboard, input, " +
                        "timeouts, performance, TLS, Xvnc and x0vncserver options.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    if (VncSettings.setGeneral(context, containerName, port, geometryText)) {
                        onDismiss()
                    } else {
                        saveError = "Could not save the VNC port and resolution."
                    }
                },
                enabled = canSave
            ) {
                Text("Save")
            }
        }
    )
}
