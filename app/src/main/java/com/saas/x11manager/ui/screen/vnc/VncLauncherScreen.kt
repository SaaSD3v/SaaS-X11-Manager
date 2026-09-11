package com.saas.x11manager.ui.screen.vnc

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.saas.x11manager.embeddedvnc.EmbeddedVncState
import java.util.UUID

@Composable
fun VncLauncherScreen(
    viewModel: VncLauncherViewModel,
    onOpenScreen: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val selectedId by viewModel.selectedProfileId.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val screenEnabled by viewModel.screenEnabled.collectAsState()
    val framebuffer by viewModel.framebufferSize.collectAsState()
    val active = viewModel.activeProfile()
    val selected = profiles.firstOrNull { it.id == selectedId }

    var showConnections by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<VncLauncherProfile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val connectionSubtitle = when {
        active != null && state == EmbeddedVncState.CONNECTED ->
            "${active.name} · ${active.host}:${active.port}"
        active != null && state == EmbeddedVncState.CONNECTING ->
            "Connecting to ${active.name}"
        profiles.isEmpty() ->
            if (viewModel.singleDisplayMode) "Register the VNC display" else "Add a VNC connection"
        viewModel.singleDisplayMode ->
            "${profiles.first().name} · ${profiles.first().host}:${profiles.first().port}"
        profiles.size == 1 ->
            "1 saved connection · ${profiles.first().name}"
        else -> "${profiles.size} saved connections"
    }

    val screenSubtitle = when (state) {
        EmbeddedVncState.CONNECTED -> buildString {
            append(if (screenEnabled) "Connected · screen on" else "Connected · screen off")
            framebuffer?.let { append(" · ${it.first} × ${it.second}") }
        }
        EmbeddedVncState.CONNECTING -> "Connecting · screen waiting"
        EmbeddedVncState.ERROR -> "Connection error · open Screen for details"
        EmbeddedVncState.OFF -> selected?.let { "Ready for ${it.name}" } ?: "No connection selected"
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
                "VNC Viewer",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (viewModel.singleDisplayMode) {
                    "One fixed VNC display with its own connection, screen and client controls."
                } else {
                    "Register VNC endpoints here and open the dedicated screen only when you want to render one."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            VncLauncherCard(
                index = "01",
                icon = Icons.Default.Link,
                title = if (viewModel.singleDisplayMode) "Connection" else "Connections",
                subtitle = connectionSubtitle,
                onClick = { showConnections = true }
            )
        }

        item {
            VncLauncherCard(
                index = "02",
                icon = Icons.Default.DesktopWindows,
                title = "Screen",
                subtitle = screenSubtitle,
                onClick = onOpenScreen
            )
        }

        message?.let { value ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 0.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { message = null }) {
                            Icon(Icons.Default.Close, "Dismiss")
                        }
                    }
                }
            }
        }
    }

    if (showConnections) {
        VncConnectionsDialog(
            viewModel = viewModel,
            onDismiss = { showConnections = false },
            onAdd = {
                showConnections = false
                editor = VncLauncherProfile(
                    id = UUID.randomUUID().toString(),
                    name = "VNC connection"
                )
            },
            onEdit = {
                showConnections = false
                editor = it
            },
            onConnect = { profile ->
                showConnections = false
                viewModel.connect(profile)
            },
            onDisconnect = {
                showConnections = false
                viewModel.disconnect()
            },
            onWakeResult = { result ->
                message = result.fold(
                    onSuccess = { "Wake-on-LAN packet sent" },
                    onFailure = { it.message ?: "Wake-on-LAN failed" }
                )
            }
        )
    }

    editor?.let { profile ->
        VncProfileEditor(
            initial = profile,
            onDismiss = { editor = null },
            onDelete = if (profiles.any { it.id == profile.id }) {
                {
                    viewModel.deleteProfile(profile.id)
                    editor = null
                }
            } else null,
            onSave = {
                runCatching { viewModel.saveProfile(it) }
                    .onSuccess { editor = null }
                    .onFailure { error -> message = error.message ?: "Could not save connection" }
            }
        )
    }
}

@Composable
private fun VncLauncherCard(
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
                Text(index, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VncConnectionsDialog(
    viewModel: VncLauncherViewModel,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (VncLauncherProfile) -> Unit,
    onConnect: (VncLauncherProfile) -> Unit,
    onDisconnect: () -> Unit,
    onWakeResult: (Result<Unit>) -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val selectedId by viewModel.selectedProfileId.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val active = viewModel.activeProfile()
    val canAdd = !viewModel.singleDisplayMode || profiles.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Link, null) },
        title = { Text(if (viewModel.singleDisplayMode) "VNC connection" else "VNC connections") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (profiles.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp
                    ) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.DesktopWindows, null, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (viewModel.singleDisplayMode) "No VNC display registered" else "No VNC connections yet",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    profiles.forEach { profile ->
                        val isActive = active?.id == profile.id
                        val isSelected = selectedId == profile.id
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectProfile(profile.id) },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 0.dp,
                            border = BorderStroke(
                                if (isSelected) 2.dp else 1.dp,
                                if (isSelected) MaterialTheme.colorScheme.outline
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                            )
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(profile.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        Text("${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { onEdit(profile) }) {
                                        Icon(Icons.Default.Settings, "Edit connection")
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when {
                                            isActive && state == EmbeddedVncState.CONNECTED -> "Connected"
                                            isActive && state == EmbeddedVncState.CONNECTING -> "Connecting"
                                            isActive && state == EmbeddedVncState.ERROR -> "Error"
                                            else -> "Saved"
                                        },
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (profile.wakeMac.isNotBlank() && !isActive) {
                                        IconButton(onClick = { viewModel.wake(profile, onWakeResult) }) {
                                            Icon(Icons.Default.Power, "Wake-on-LAN")
                                        }
                                    }
                                    if (isActive && (state == EmbeddedVncState.CONNECTED || state == EmbeddedVncState.CONNECTING)) {
                                        OutlinedButton(onClick = onDisconnect, shape = RoundedCornerShape(7.dp)) {
                                            Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Disconnect")
                                        }
                                    } else {
                                        FilledTonalButton(onClick = { onConnect(profile) }, shape = RoundedCornerShape(7.dp)) {
                                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(17.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Connect")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (canAdd) {
                    OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp)) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (viewModel.singleDisplayMode) "Register VNC display" else "Add connection")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        shape = RoundedCornerShape(26.dp)
    )
}

@Composable
internal fun VncProfileEditor(
    initial: VncLauncherProfile,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (VncLauncherProfile) -> Unit
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var host by remember(initial.id) { mutableStateOf(initial.host) }
    var port by remember(initial.id) { mutableStateOf(initial.port.toString()) }
    var username by remember(initial.id) { mutableStateOf(initial.username) }
    var password by remember(initial.id) { mutableStateOf(initial.password) }
    var rememberPassword by remember(initial.id) { mutableStateOf(initial.rememberPassword) }
    var securityType by remember(initial.id) { mutableStateOf(initial.securityType.toString()) }
    var imageQuality by remember(initial.id) { mutableFloatStateOf(initial.imageQuality.toFloat()) }
    var raw by remember(initial.id) { mutableStateOf(initial.rawEncodingOnly) }
    var localCursor by remember(initial.id) { mutableStateOf(initial.localCursor) }
    var viewOnly by remember(initial.id) { mutableStateOf(initial.viewOnly) }
    var directTouch by remember(initial.id) { mutableStateOf(initial.directTouch) }
    var clipboardSync by remember(initial.id) { mutableStateOf(initial.clipboardSync) }
    var autoReconnect by remember(initial.id) { mutableStateOf(initial.autoReconnect) }
    var repeaterId by remember(initial.id) { mutableStateOf(initial.repeaterId?.toString().orEmpty()) }
    var trustAll by remember(initial.id) { mutableStateOf(initial.trustAllCertificates) }
    var certificate by remember(initial.id) { mutableStateOf(initial.trustedCertificateSha256) }
    var wakeMac by remember(initial.id) { mutableStateOf(initial.wakeMac) }
    var viewerExpanded by remember(initial.id) { mutableStateOf(false) }
    var advancedExpanded by remember(initial.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DesktopWindows, null) },
        title = { Text("VNC connection") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text("Host / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    port,
                    { if (it.all(Char::isDigit)) port = it },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(username, { username = it }, label = { Text("Username (when required)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("VNC password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                SettingSwitch("Remember password", rememberPassword) { rememberPassword = it }

                ExpandableSettingsSection("Viewer", viewerExpanded, { viewerExpanded = it }) {
                    Text("Image quality: ${imageQuality.toInt()}", style = MaterialTheme.typography.bodySmall)
                    Slider(value = imageQuality, onValueChange = { imageQuality = it }, valueRange = 0f..9f, steps = 8)
                    SettingSwitch("Raw encoding only", raw) { raw = it }
                    SettingSwitch("Local cursor", localCursor) { localCursor = it }
                    SettingSwitch("View only", viewOnly) { viewOnly = it }
                    SettingSwitch("Direct touch", directTouch) { directTouch = it }
                    SettingSwitch("Clipboard sync", clipboardSync) { clipboardSync = it }
                    SettingSwitch("Reconnect automatically", autoReconnect) { autoReconnect = it }
                }

                ExpandableSettingsSection("Security & advanced", advancedExpanded, { advancedExpanded = it }) {
                    OutlinedTextField(
                        securityType,
                        { if (it.all { c -> c.isDigit() }) securityType = it },
                        label = { Text("RFB security type (0 = automatic)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        repeaterId,
                        { if (it.all { c -> c.isDigit() }) repeaterId = it },
                        label = { Text("VNC Repeater ID (optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SettingSwitch("Trust all TLS certificates", trustAll) { trustAll = it }
                    if (!trustAll) {
                        OutlinedTextField(
                            certificate,
                            { certificate = it },
                            label = { Text("Trusted certificate SHA-256") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        wakeMac,
                        { wakeMac = it },
                        label = { Text("Wake-on-LAN MAC (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.ifBlank { host.ifBlank { "VNC connection" } },
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 5900,
                            username = username,
                            password = password,
                            rememberPassword = rememberPassword,
                            securityType = securityType.toIntOrNull() ?: 0,
                            imageQuality = imageQuality.toInt(),
                            rawEncodingOnly = raw,
                            localCursor = localCursor,
                            viewOnly = viewOnly,
                            directTouch = directTouch,
                            clipboardSync = clipboardSync,
                            autoReconnect = autoReconnect,
                            repeaterId = repeaterId.toIntOrNull(),
                            trustAllCertificates = trustAll,
                            trustedCertificateSha256 = certificate,
                            wakeMac = wakeMac
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { delete ->
                    TextButton(onClick = delete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        shape = RoundedCornerShape(26.dp)
    )
}

@Composable
private fun ExpandableSettingsSection(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
internal fun SettingSwitch(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
