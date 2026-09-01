package com.saas.x11manager.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.saas.x11manager.util.SessionAccessMode
import com.saas.x11manager.util.VncSettings

@Composable
internal fun GraphicAccessDialog(
    containerName: String,
    port: Int,
    initialMode: SessionAccessMode,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onConfirm: (SessionAccessMode, String?) -> Unit
) {
    var selectedMode by remember(containerName, initialMode) { mutableStateOf(initialMode) }
    var password by remember(containerName) { mutableStateOf("") }

    val passwordRequired = selectedMode.requiresVnc
    val passwordValid = !passwordRequired || VncSettings.isValidPassword(password)

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
                    "Choose access method",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$containerName · final access configuration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                AccessChoice(
                    title = "Integrated X11",
                    subtitle = "Use the Manager's embedded X11 screen only.",
                    selected = selectedMode == SessionAccessMode.INTEGRATED_X11,
                    onClick = { selectedMode = SessionAccessMode.INTEGRATED_X11 }
                )
                Spacer(Modifier.height(10.dp))
                AccessChoice(
                    title = "VNC",
                    subtitle = "Start an external TigerVNC virtual display for the selected session.",
                    selected = selectedMode == SessionAccessMode.VNC,
                    onClick = { selectedMode = SessionAccessMode.VNC }
                )
                Spacer(Modifier.height(10.dp))
                AccessChoice(
                    title = "Both",
                    subtitle = "Start Integrated X11 and share that exact same screen through TigerVNC.",
                    selected = selectedMode == SessionAccessMode.BOTH,
                    onClick = { selectedMode = SessionAccessMode.BOTH }
                )

                if (passwordRequired) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("TigerVNC", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Port: $port · Change it from General settings on the container card.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { value ->
                                    if (value.length <= VncSettings.MAX_PASSWORD_LENGTH &&
                                        value.none { it == '\n' || it == '\r' || it.code < 0x20 }
                                    ) {
                                        password = value
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("VNC password") },
                                supportingText = {
                                    Text(
                                        "${VncSettings.MIN_PASSWORD_LENGTH}-${VncSettings.MAX_PASSWORD_LENGTH} characters. " +
                                            "It is converted to TigerVNC's password file and is not stored in Android preferences."
                                    )
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                isError = password.isNotEmpty() && !passwordValid
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) { Text("Back") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(
                                selectedMode,
                                password.takeIf { selectedMode.requiresVnc }
                            )
                        },
                        enabled = passwordValid
                    ) {
                        Text("Continue")
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessChoice(
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
                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
