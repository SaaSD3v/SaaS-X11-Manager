package com.saas.x11manager.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saas.x11manager.ui.component.SettingsSection
import com.saas.x11manager.ui.component.SettingsToggleRow
import com.saas.x11manager.util.FixSettings
import com.saas.x11manager.util.VirGLFixManager
import com.saas.x11manager.util.VirGLRuntimeFlag
import com.saas.x11manager.util.VirGLRuntimeFlags

@Composable
internal fun FixesScreen(
    containerName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var audioEnabled by remember(containerName) {
        mutableStateOf(FixSettings.isPulseAudioEnabled(context, containerName))
    }
    var virglEnabled by remember(containerName) {
        mutableStateOf(FixSettings.isVirGLEnabled(context, containerName))
    }
    var selectedVirglFlags by remember(containerName) {
        mutableStateOf(FixSettings.getVirGLRuntimeFlags(context))
    }
    var supportedVirglFlags by remember(containerName) {
        mutableStateOf<Set<VirGLRuntimeFlag>?>(null)
    }
    var saveError by remember(containerName) { mutableStateOf(false) }

    LaunchedEffect(containerName, virglEnabled) {
        supportedVirglFlags = if (virglEnabled) {
            VirGLFixManager.supportedOptionalFlags()
        } else {
            null
        }
    }

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text(
                    "Fixes",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    containerName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Optional compatibility fixes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            SettingsSection(
                title = "Audio configuration",
                subtitle = "Android audio for Linux applications",
                icon = { Icon(Icons.Default.Build, contentDescription = null) }
            ) {
                SettingsToggleRow(
                    title = if (audioEnabled) "Enabled" else "Disabled by default",
                    subtitle = "HOST and NAT network modes supported",
                    checked = audioEnabled,
                    onCheckedChange = { requested ->
                        val saved = FixSettings.setPulseAudioEnabled(
                            context = context,
                            containerName = containerName,
                            enabled = requested
                        )
                        if (saved) {
                            audioEnabled = requested
                            saveError = false
                        } else {
                            saveError = true
                        }
                    }
                )
            }

            SettingsSection(
                title = "3D acceleration",
                subtitle = "Manager-owned VirGL transport for Integrated X11",
                icon = { Icon(Icons.Default.Build, contentDescription = null) }
            ) {
                SettingsToggleRow(
                    title = if (virglEnabled) "Enabled" else "Disabled by default",
                    subtitle = "Private virglrenderer socket; falls back to software rendering on failure",
                    checked = virglEnabled,
                    onCheckedChange = { requested ->
                        val saved = FixSettings.setVirGLEnabled(
                            context = context,
                            containerName = containerName,
                            enabled = requested
                        )
                        if (saved) {
                            virglEnabled = requested
                            saveError = false
                        } else {
                            saveError = true
                        }
                    }
                )
            }

            if (virglEnabled) {
                SettingsSection(
                    title = "VirGL renderer flags",
                    subtitle = "Optional host flags detected from the installed virglrenderer",
                    icon = { Icon(Icons.Default.Build, contentDescription = null) }
                ) {
                    val supported = supportedVirglFlags
                    if (supported == null) {
                        Text(
                            "Checking supported virglrenderer flags…",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val visibleFlags = VirGLRuntimeFlags.all.filter { it in supported }
                        if (visibleFlags.isEmpty()) {
                            Text(
                                "The installed renderer did not report any optional flags managed by this screen.",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            visibleFlags.forEach { flag ->
                                SettingsToggleRow(
                                    title = flag.title,
                                    subtitle = "${flag.argument} · ${flag.description}",
                                    checked = flag in selectedVirglFlags,
                                    onCheckedChange = { requested ->
                                        val saved = FixSettings.setVirGLRuntimeFlagEnabled(
                                            context = context,
                                            flag = flag,
                                            enabled = requested
                                        )
                                        if (saved) {
                                            selectedVirglFlags = FixSettings.getVirGLRuntimeFlags(context)
                                            saveError = false
                                        } else {
                                            saveError = true
                                        }
                                    }
                                )
                            }
                        }
                        Text(
                            "These flags configure the shared Manager VirGL renderer. Incompatible GLX/EGL choices are resolved automatically; changes apply on the next safe renderer restart.",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (saveError) {
                Text(
                    "Could not save this setting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
