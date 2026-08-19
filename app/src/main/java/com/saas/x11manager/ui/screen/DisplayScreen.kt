package com.saas.x11manager.ui.screen

import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.LoaderStatus
import com.termux.x11.EmbeddedDisplayHost

@Composable
fun DisplayScreen(
    viewModel: HomeViewModel,
    onFullscreenChanged: (Boolean) -> Unit = {}
) {
    val serverStatus by viewModel.loaderStatus.collectAsState()
    val serverPid by viewModel.loaderPid.collectAsState()
    val containers by viewModel.containers.collectAsState()
    val context = LocalContext.current
    val prefs = remember(context) { EmbeddedDisplayHost.getPrefs(context) }
    val store = remember(prefs) { prefs.get() }

    var showConfig by remember { mutableStateOf(false) }
    var showScreen by remember { mutableStateOf(false) }
    var additionalKeysEnabled by remember { mutableStateOf(store.getBoolean("showAdditionalKbd", true)) }

    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "showAdditionalKbd") {
                additionalKeysEnabled = store.getBoolean("showAdditionalKbd", true)
            }
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                "Integrated X11",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Configure the embedded X11 engine or open its screen. Runtime controls stay inside the screen panel.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            DisplayLauncherCard(
                index = "01",
                icon = Icons.Default.Tune,
                title = "Configuration",
                subtitle = "Display, input, keyboard and X11 behavior",
                onClick = { showConfig = true }
            )
        }

        item {
            DisplayLauncherCard(
                index = "02",
                icon = Icons.Default.DesktopWindows,
                title = "Screen",
                subtitle = when (serverStatus) {
                    LoaderStatus.Running -> "${Constants.X11_DISPLAY} running${serverPid?.let { " · PID $it" } ?: ""}"
                    else -> "Open the X11 screen and runtime controls"
                },
                onClick = { showScreen = true }
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Current backend", fontWeight = FontWeight.SemiBold)
                    DisplaySummaryRow("Display", Constants.X11_DISPLAY)
                    DisplaySummaryRow(
                        "Server",
                        if (serverStatus == LoaderStatus.Running) "Running" else "Stopped"
                    )
                    DisplaySummaryRow(
                        "Additional keys",
                        if (additionalKeysEnabled) "Enabled in X11 config" else "Disabled in X11 config"
                    )
                }
            }
        }
    }

    if (showConfig) {
        X11ConfigurationDialog(
            store = store,
            onDismiss = { showConfig = false }
        )
    }

    if (showScreen) {
        X11ScreenDialog(
            viewModel = viewModel,
            serverStatus = serverStatus,
            containers = containers,
            additionalKeysEnabled = additionalKeysEnabled,
            onDismiss = { showScreen = false },
            onOpenConfiguration = {
                showScreen = false
                showConfig = true
            },
            onFullscreen = {
                showScreen = false
                onFullscreenChanged(true)
            }
        )
    }
}

@Composable
private fun DisplayLauncherCard(
    index: String,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            ) {
                Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(index, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun DisplaySummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
