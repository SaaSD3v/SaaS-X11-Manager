package com.saas.x11manager.ui.component

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.saas.x11manager.util.AnsiColorParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalDialog(
    title: String,
    logs: List<Pair<Int, String>>,
    onDismiss: () -> Unit,
    onMinimize: () -> Unit,
    onClear: (() -> Unit)? = null,
    isBlocking: Boolean = false,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    primaryActionEnabled: Boolean = true
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val dialogShape = RoundedCornerShape(28.dp)
    val buttonShape = RoundedCornerShape(14.dp)
    val minimize by rememberUpdatedState(onMinimize)
    fun minimizeWithNotification() {
        minimize()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Toast.makeText(context,
                "Notifications are disabled. You can reopen the logs from this operation's screen.",
                Toast.LENGTH_LONG).show()
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { minimizeWithNotification() }

    Dialog(
        onDismissRequest = if (isBlocking) { {} } else { onDismiss },
        properties = DialogProperties(
            dismissOnBackPress = !isBlocking,
            dismissOnClickOutside = !isBlocking,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenHeight * 0.78f)
                .padding(horizontal = 16.dp),
            shape = dialogShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    IconButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else minimizeWithNotification()
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Remove, "Minimize logs", modifier = Modifier.size(22.dp))
                    }
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                enabled = !isBlocking,
                                onClick = onDismiss,
                                indication = rememberRipple(bounded = true),
                                interactionSource = remember { MutableInteractionSource() }
                            ),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (!isBlocking) 0.08f else 0.04f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (!isBlocking) 0.3f else 0.15f)),
                        tonalElevation = 0.dp
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (!isBlocking) 1f else 0.38f)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (onClear != null) {
                        val canClear = logs.isNotEmpty() && !isBlocking
                        Surface(
                            modifier = Modifier
                                .height(38.dp)
                                .weight(1f)
                                .clip(buttonShape)
                                .clickable(
                                    enabled = canClear,
                                    onClick = onClear,
                                    indication = rememberRipple(bounded = true),
                                    interactionSource = remember { MutableInteractionSource() }
                                ),
                            shape = buttonShape,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canClear) 0.06f else 0.03f),
                            border = BorderStroke(1.dp, if (canClear) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                            tonalElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Delete, "Clear", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canClear) 0.8f else 0.38f))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Clear", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canClear) 0.8f else 0.38f))
                            }
                        }
                    }

                    val canCopy = logs.isNotEmpty() && !isBlocking
                    Surface(
                        modifier = Modifier
                            .height(38.dp)
                            .weight(1f)
                            .clip(buttonShape)
                            .clickable(
                                enabled = canCopy,
                                onClick = {
                                    // The ViewModel stores only the concise stream. Copy exactly
                                    // what the terminal renders instead of reducing it a second time.
                                    val logText = logs.joinToString("\n") {
                                        AnsiColorParser.stripAnsi(it.second)
                                    }
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    val clip = ClipData.newPlainText("Terminal Logs", logText)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                                },
                                indication = rememberRipple(bounded = true),
                                interactionSource = remember { MutableInteractionSource() }
                            ),
                        shape = buttonShape,
                        color = if (canCopy) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f),
                        border = BorderStroke(1.dp, if (canCopy) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                        tonalElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(16.dp), tint = if (canCopy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (canCopy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                        }
                    }
                }

                TerminalConsole(
                    logs = logs,
                    isProcessing = isBlocking,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )

                if (primaryActionLabel != null && onPrimaryAction != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onPrimaryAction,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = primaryActionEnabled && !isBlocking,
                        shape = buttonShape
                    ) {
                        Text(
                            primaryActionLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
