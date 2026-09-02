package com.saas.x11manager.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.FixSettings

/**
 * Full-size Fixes settings surface.
 *
 * Switches are declarative only: changing one stores the desired state but does
 * not install packages, start PulseAudio, restart a container, or invoke root.
 * SessionAccessManager reconciles the desired state when Start X11/VNC/Both is
 * pressed so the operation is visible in the normal session logs.
 */
@Composable
internal fun FixesScreen(
    containerName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var enabled by remember(containerName) {
        mutableStateOf(FixSettings.isPulseAudioEnabled(context, containerName))
    }
    var applied by remember(containerName) {
        mutableStateOf(FixSettings.isPulseAudioApplied(context, containerName))
    }
    var status by remember(containerName) { mutableStateOf<String?>(null) }
    var statusIsError by remember(containerName) { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text("Fixes", style = MaterialTheme.typography.headlineSmall)
                Text(
                    containerName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Divider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Optional compatibility fixes",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Nothing is installed or started from this screen. Select the fixes you want, then press Start X11, Start VNC or Start Both. The Manager applies the selected fixes immediately before starting the graphical session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Build,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    "PulseAudio fix",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    if (enabled) "Enabled for next graphical start" else "Disabled by default",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = enabled,
                            onCheckedChange = { requested ->
                                val saved = FixSettings.setPulseAudioEnabled(
                                    context = context,
                                    containerName = containerName,
                                    enabled = requested
                                )
                                if (saved) {
                                    enabled = requested
                                    applied = FixSettings.isPulseAudioApplied(context, containerName)
                                    statusIsError = false
                                    status = if (requested) {
                                        "Selected. No installation was started. The PulseAudio fix will be detected, installed if needed, and applied automatically when you next start X11/VNC."
                                    } else if (applied) {
                                        "Disabled for the next start. The existing Manager-owned PulseAudio integration will be removed during the next graphical start; the current running session is not interrupted."
                                    } else {
                                        "Disabled. Nothing will be installed or applied."
                                    }
                                } else {
                                    statusIsError = true
                                    status = "Could not save the PulseAudio fix preference. No system changes were made."
                                }
                            }
                        )
                    }

                    Text(
                        if (applied) {
                            "Runtime state: the Manager has previously applied this fix to the container."
                        } else {
                            "Runtime state: not applied yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        "At Start, the Manager uses the bundled audited helper. It prefers the DroidSpaces native /tmp/.pulse-socket bridge, detects AAudio/OpenSL ES, installs only missing apt/apk audio clients, and uses 127.0.0.1 TCP only as a host-network fallback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        "Root note: the graphical Manager uses root for DroidSpaces operations, while PulseAudio itself still runs as the normal Termux user. If Magisk asks for Termux root access during the first application, grant it so the helper can perform DroidSpaces commands.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            status?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
