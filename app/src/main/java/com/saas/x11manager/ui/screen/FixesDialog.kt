package com.saas.x11manager.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.FixSettings
import com.saas.x11manager.util.PulseAudioFixManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen per-container fixes settings. This deliberately is not a Dialog:
 * long-running package/audio setup should feel like a real settings operation,
 * keep enough room for status details and never look like a frozen popup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FixesScreen(
    containerName: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember(containerName) {
        mutableStateOf(FixSettings.isPulseAudioEnabled(context, containerName))
    }
    var busy by remember(containerName) { mutableStateOf(false) }
    var status by remember(containerName) { mutableStateOf<String?>(null) }
    var lastSucceeded by remember(containerName) { mutableStateOf<Boolean?>(null) }
    var elapsedSeconds by remember(containerName) { mutableIntStateOf(0) }

    // The shell helper is transactional. Do not dispose this screen halfway
    // through an apt/apk/config operation; instead show continuous progress and
    // allow navigation again as soon as the transaction finishes.
    BackHandler(enabled = busy) { }

    LaunchedEffect(busy) {
        if (!busy) return@LaunchedEffect
        elapsedSeconds = 0
        while (true) {
            delay(1_000)
            elapsedSeconds++
        }
    }

    fun requestPulseAudioState(requested: Boolean) {
        if (busy || requested == enabled) return
        busy = true
        elapsedSeconds = 0
        status = if (requested) {
            "Preparing the PulseAudio fix for $containerName..."
        } else {
            "Removing the Manager-owned PulseAudio integration from $containerName..."
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
                    .filter {
                        it.startsWith("[-]") ||
                            it.startsWith("[!]") ||
                            it.contains("error", ignoreCase = true) ||
                            it.contains("failed", ignoreCase = true)
                    }
                    .distinct()
                    .takeLast(5)
                if (tail.isNotEmpty()) {
                    append("\n\n")
                    append(tail.joinToString("\n"))
                }
            }
            lastSucceeded = result.success
            busy = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Fixes", fontWeight = FontWeight.Bold)
                        Text(
                            containerName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        enabled = !busy
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp).size(26.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column {
                        Text(
                            "Container fixes",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Optional compatibility fixes. All fixes are disabled by default.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "PulseAudio fix",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    when {
                                        busy -> "Applying configuration..."
                                        enabled -> "Enabled"
                                        else -> "Disabled by default"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enabled,
                                enabled = !busy,
                                onCheckedChange = ::requestPulseAudioState
                            )
                        }

                        Text(
                            "When enabled, the Manager detects DroidSpaces and the container distribution, " +
                                "prefers the native /tmp/.pulse-socket bridge, probes AAudio/OpenSL ES, installs " +
                                "only missing apt/apk audio clients, and uses 127.0.0.1 TCP only as a safe " +
                                "host-network fallback.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "The Manager now keeps root inside its own already-authorized Magisk/libsu shell. " +
                                    "PulseAudio and package commands still run as the real Termux user, but Termux " +
                                    "does not need a separate Magisk root grant.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (busy) {
                            Spacer(Modifier.height(2.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                buildString {
                                    append("Working · ")
                                    append(formatElapsed(elapsedSeconds))
                                    append(". First-time package installation can take a few minutes. ")
                                    append("The screen is active; it is not frozen.")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        status?.let { message ->
                            val success = lastSucceeded
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = when (success) {
                                    true -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    false -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                                    null -> MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = when (success) {
                                            true -> Icons.Default.CheckCircle
                                            false -> Icons.Default.ErrorOutline
                                            null -> Icons.Default.Info
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = when (success) {
                                            true -> MaterialTheme.colorScheme.primary
                                            false -> MaterialTheme.colorScheme.error
                                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Text(
                                        message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when (success) {
                                            false -> MaterialTheme.colorScheme.onErrorContainer
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Turning this fix on is the point where installation/configuration is allowed. " +
                        "Simply opening this screen changes nothing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatElapsed(seconds: Int): String {
    val minutes = seconds / 60
    val remainder = seconds % 60
    return if (minutes > 0) "%d:%02d".format(minutes, remainder) else "${seconds}s"
}
