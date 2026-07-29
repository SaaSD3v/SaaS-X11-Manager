package com.saas.x11manager.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.ContainerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContainerScreen(
    containerName: String,
    viewModel: HomeViewModel,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var pulseAudioFix by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(containerName) {
        val info = ContainerManager.getContainerInfo(containerName)
        if (info != null) {
            pulseAudioFix = info.hasPulseAudioBindMount
        }
        loaded = true
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Computer,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp).size(24.dp)
                        )
                        Text(
                            text = "Edit $containerName",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 1.dp
                    )
                    val buttonColor by animateColorAsState(
                        targetValue = when {
                            isSaved -> MaterialTheme.colorScheme.primaryContainer
                            isSaving -> MaterialTheme.colorScheme.primary
                            hasChanges -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        },
                        label = "ButtonColor"
                    )
                    Surface(
                        onClick = {
                            if (hasChanges && !isSaving && !isSaved) {
                                isSaving = true
                                scope.launch {
                                    val success = ContainerManager.updateContainerConfig(
                                        name = containerName,
                                        enablePulseAudioFix = pulseAudioFix
                                    )
                                    isSaving = false
                                    if (success) {
                                        isSaved = true
                                        hasChanges = false
                                        delay(1500)
                                        isSaved = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = buttonColor,
                        border = BorderStroke(
                            1.dp,
                            if (hasChanges) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            } else if (isSaved) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Text(
                                text = when {
                                    isSaving -> "Saving..."
                                    isSaved -> "Saved"
                                    hasChanges -> "Save Changes"
                                    else -> "No Changes"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    isSaved -> MaterialTheme.colorScheme.onPrimaryContainer
                                    hasChanges -> MaterialTheme.colorScheme.onPrimary
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (loaded) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                SectionHeader(title = "Integration")

                ToggleCard(
                    icon = Icons.Default.VolumeUp,
                    title = "PulseAudio Fix",
                    subtitle = if (pulseAudioFix) "Audio via host PulseAudio" else "Default audio",
                    checked = pulseAudioFix,
                    onCheckedChange = { newValue ->
                        pulseAudioFix = newValue
                        hasChanges = true
                        isSaved = false
                    }
                )

                if (pulseAudioFix) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Starts PulseAudio before X11. Fixes audio when Droidspaces bridge fails.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun ToggleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onCheckedChange(!checked) },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (checked) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            )
        }
    }
}
