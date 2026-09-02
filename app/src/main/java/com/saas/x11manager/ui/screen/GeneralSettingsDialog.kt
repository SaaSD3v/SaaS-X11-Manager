package com.saas.x11manager.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "General settings",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            containerName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "VNC",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    item {
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
                    }

                    item {
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
                    }

                    item {
                        Text(
                            text = "Port and resolution are used when VNC launch support is enabled. " +
                                "Changing them here does not start or install a VNC server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (saveError != null) {
                        item {
                            Text(
                                text = saveError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    item { Spacer(Modifier.height(2.dp)) }

                    item {
                        OutlinedButton(
                            onClick = {
                                VncSettings.resetGeneral(context, containerName)
                                portText = VncSettings.DEFAULT_PORT.toString()
                                geometryText = VncSettings.DEFAULT_GEOMETRY
                                saveError = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reset port & resolution")
                        }
                    }

                    item {
                        OutlinedButton(
                            onClick = { showTigerVncSettings = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Advanced TigerVNC settings")
                        }
                    }

                    item {
                        Text(
                            text = "Full-screen TigerVNC editor: security, sharing, clipboard, input, " +
                                "timeouts, performance, TLS, Xvnc and x0vncserver options.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(6.dp))
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
            }
        }
    }
}
