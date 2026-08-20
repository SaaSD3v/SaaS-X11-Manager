package com.saas.x11manager.ui.screen

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
import com.saas.x11manager.util.X11ServerStatus
import com.termux.x11.EmbeddedDisplayHost

@Composable
fun DisplayScreen(
    viewModel: HomeViewModel,
    onOpenScreen: () -> Unit
) {
    val serverStatus by viewModel.x11ServerStatus.collectAsState()
    val serverPid by viewModel.x11ServerPid.collectAsState()
    val context = LocalContext.current
    val prefs = remember(context) { EmbeddedDisplayHost.getPrefs(context) }
    val store = remember(prefs) { prefs.get() }
    var showConfig by remember { mutableStateOf(false) }

    LaunchedEffect(store) {
        ensureManagedX11Defaults(context, store)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
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
                "Configure the embedded X11 engine or open its managed screen workspace.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            DisplayLauncherCard(
                index = "01",
                icon = Icons.Default.Tune,
                title = "Configuration",
                subtitle = "Display, input, keyboard and embedded X11 behavior",
                onClick = { showConfig = true }
            )
        }

        item {
            DisplayLauncherCard(
                index = "02",
                icon = Icons.Default.DesktopWindows,
                title = "Screen",
                subtitle = when (serverStatus) {
                    X11ServerStatus.Running ->
                        "${Constants.X11_DISPLAY} running${serverPid?.let { " · PID $it" } ?: ""}"
                    X11ServerStatus.Stopped -> "Open the full-size X11 workspace"
                },
                onClick = onOpenScreen
            )
        }
    }

    if (showConfig) {
        X11ConfigurationDialog(
            store = store,
            onDismiss = { showConfig = false }
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
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
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
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    index,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
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
