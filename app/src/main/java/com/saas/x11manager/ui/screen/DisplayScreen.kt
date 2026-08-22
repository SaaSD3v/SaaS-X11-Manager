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
import com.saas.x11manager.util.X11ServerStatus
import com.termux.x11.EmbeddedDisplayHost

@Composable
fun DisplayScreen(
    viewModel: HomeViewModel,
    onOpenScreen: () -> Unit
) {
    val monitors by viewModel.monitors.collectAsState()
    val context = LocalContext.current
    val prefs = remember(context) { EmbeddedDisplayHost.getPrefs(context) }
    val store = remember(prefs) { prefs.get() }
    var showConfig by remember { mutableStateOf(false) }

    LaunchedEffect(store) {
        ensureManagedX11Defaults(context, store)
    }

    val activeDisplays = remember(monitors) {
        monitors
            .asSequence()
            .filter { it.status == X11ServerStatus.Running }
            .sortedBy { it.slot.number }
            .map { it.displayName }
            .toList()
    }

    val screenSubtitle = when {
        activeDisplays.isEmpty() -> "Open the managed X11 monitor workspace"
        activeDisplays.size == 1 -> "1 monitor active · ${activeDisplays.first()}"
        else -> {
            val visible = activeDisplays.take(3).joinToString(", ")
            val remaining = activeDisplays.size - 3
            "${activeDisplays.size} monitors active · $visible" +
                if (remaining > 0) " +$remaining" else ""
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Integrated X11",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Configure the embedded X11 engine or switch between managed monitors.",
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
                subtitle = screenSubtitle,
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
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 0.dp
            ) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    index,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
