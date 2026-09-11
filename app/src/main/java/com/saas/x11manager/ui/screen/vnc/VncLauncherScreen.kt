package com.saas.x11manager.ui.screen.vnc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gaurav.avnc.vnc.XKeySym
import com.saas.x11manager.embeddedvnc.EmbeddedVncFrameView
import com.saas.x11manager.embeddedvnc.EmbeddedVncState
import java.util.UUID

/**
 * Standalone VNC viewer/launcher. No DroidSpaces or Integrated X11 state is read
 * or changed from this screen.
 */
@Composable
fun VncLauncherScreen(viewModel: VncLauncherViewModel) {
    val profiles by viewModel.profiles.collectAsState()
    val selectedId by viewModel.selectedProfileId.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val detail by viewModel.connectionDetail.collectAsState()
    val screenEnabled by viewModel.screenEnabled.collectAsState()
    val framebuffer by viewModel.framebufferSize.collectAsState()
    val selected = profiles.firstOrNull { it.id == selectedId }
    val active = viewModel.activeProfile()

    var editor by remember { mutableStateOf<VncLauncherProfile?>(null) }
    var viewerExpanded by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }
    var resizeWidth by remember { mutableStateOf("") }
    var resizeHeight by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!viewerExpanded) {
            VncStatusCard(
                profile = active ?: selected,
                state = state,
                detail = detail,
                screenEnabled = screenEnabled,
                framebuffer = framebuffer
            )
        }

        if (state == EmbeddedVncState.CONNECTED) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = Color.Black,
                tonalElevation = 1.dp
            ) {
                if (screenEnabled) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("vnc-frame"),
                        factory = { context ->
                            EmbeddedVncFrameView(context).apply {
                                attachSession(viewModel.session)
                                directTouch = active?.directTouch ?: false
                                inputEnabled = !(active?.viewOnly ?: false)
                            }
                        },
                        update = { frame ->
                            frame.directTouch = active?.directTouch ?: false
                            frame.inputEnabled = !(active?.viewOnly ?: false)
                        }
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.height(8.dp))
                            Text("VNC screen is off", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("The connection stays open without framebuffer updates.", color = Color.LightGray)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { viewModel.setScreenEnabled(!screenEnabled) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(if (screenEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (screenEnabled) "Screen off" else "Screen on")
                }
                FilledTonalButton(
                    onClick = { viewerExpanded = !viewerExpanded },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(if (viewerExpanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (viewerExpanded) "Controls" else "Expand")
                }
                Button(
                    onClick = {
                        viewerExpanded = false
                        viewModel.disconnect()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.LinkOff, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Disconnect")
                }
            }

            if (!viewerExpanded) {
                VncLiveControls(
                    viewModel = viewModel,
                    keyboardText = keyboardText,
                    onKeyboardText = { keyboardText = it },
                    resizeWidth = resizeWidth,
                    onResizeWidth = { resizeWidth = it },
                    resizeHeight = resizeHeight,
                    onResizeHeight = { resizeHeight = it },
                    onMessage = { message = it }
                )
            }
        } else if (!viewerExpanded) {
            Text("Connections", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            if (profiles.isEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.DesktopWindows, null, Modifier.size(38.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("No VNC connections yet", fontWeight = FontWeight.Bold)
                        Text(
                            "Add any VNC server just like a desktop viewer. This launcher is independent from DroidSpaces.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    profiles.forEach { profile ->
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectProfile(profile.id) }
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (selectedId == profile.id) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                    null
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(profile.name, fontWeight = FontWeight.Bold)
                                    Text("${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall)
                                    val extras = buildList {
                                        if (profile.viewOnly) add("view-only")
                                        if (profile.rawEncodingOnly) add("raw") else add("auto/Tight")
                                        if (profile.autoReconnect) add("reconnect")
                                        if (profile.repeaterId != null) add("repeater ${profile.repeaterId}")
                                    }
                                    Text(extras.joinToString(" • "), style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { editor = profile }) {
                                    Icon(Icons.Default.Settings, "Edit connection")
                                }
                                IconButton(onClick = { viewModel.connect(profile) }) {
                                    Icon(Icons.Default.PlayArrow, "Connect")
                                }
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        editor = VncLauncherProfile(
                            id = UUID.randomUUID().toString(),
                            name = "VNC connection"
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add connection")
                }
                if (selected != null) {
                    FilledTonalButton(
                        onClick = { viewModel.connect(selected) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Turn on")
                    }
                    if (selected.wakeMac.isNotBlank()) {
                        FilledTonalButton(
                            onClick = {
                                viewModel.wake(selected) { result ->
                                    message = result.fold(
                                        onSuccess = { "Wake-on-LAN packet sent" },
                                        onFailure = { it.message ?: "Wake-on-LAN failed" }
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.Default.Power, "Wake-on-LAN")
                        }
                    }
                }
            }
        }

        message?.let { value ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { message = null }) { Icon(Icons.Default.Close, "Dismiss") }
                }
            }
        }
    }

    editor?.let { profile ->
        VncProfileEditor(
            initial = profile,
            onDismiss = { editor = null },
            onDelete = if (profiles.any { it.id == profile.id }) {
                { viewModel.deleteProfile(profile.id); editor = null }
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
private fun VncStatusCard(
    profile: VncLauncherProfile?,
    state: EmbeddedVncState,
    detail: String?,
    screenEnabled: Boolean,
    framebuffer: Pair<Int, Int>?
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (state) {
                    EmbeddedVncState.CONNECTED -> Icons.Default.DesktopWindows
                    EmbeddedVncState.CONNECTING -> Icons.Default.Sync
                    EmbeddedVncState.ERROR -> Icons.Default.ErrorOutline
                    EmbeddedVncState.OFF -> Icons.Default.DesktopAccessDisabled
                },
                null,
                Modifier.size(30.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(profile?.name ?: "VNC Viewer", fontWeight = FontWeight.Bold)
                Text(
                    when (state) {
                        EmbeddedVncState.CONNECTED -> if (screenEnabled) "Connected • screen on" else "Connected • screen off"
                        EmbeddedVncState.CONNECTING -> "Connecting…"
                        EmbeddedVncState.ERROR -> "Connection error"
                        EmbeddedVncState.OFF -> "Off • no VNC resources active"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                profile?.let { Text("${it.host}:${it.port}", style = MaterialTheme.typography.labelSmall) }
                framebuffer?.let { Text("Framebuffer ${it.first} × ${it.second}", style = MaterialTheme.typography.labelSmall) }
                detail?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            if (state == EmbeddedVncState.CONNECTING) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun VncLiveControls(
    viewModel: VncLauncherViewModel,
    keyboardText: String,
    onKeyboardText: (String) -> Unit,
    resizeWidth: String,
    onResizeWidth: (String) -> Unit,
    resizeHeight: String,
    onResizeHeight: (String) -> Unit,
    onMessage: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { viewModel.refreshFramebuffer() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Refresh")
            }
            FilledTonalButton(onClick = { viewModel.sendClipboardFromAndroid() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(4.dp)); Text("Clipboard")
            }
            FilledTonalButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Tune, null); Spacer(Modifier.width(4.dp)); Text("Tools")
            }
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Esc" to XKeySym.XK_Escape,
                    "Tab" to XKeySym.XK_Tab,
                    "Ctrl" to XKeySym.XK_Control_L,
                    "Alt" to XKeySym.XK_Alt_L,
                    "Super" to XKeySym.XK_Super_L
                ).forEach { (label, key) ->
                    OutlinedButton(
                        onClick = { viewModel.tapKeySym(key) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(label, maxLines = 1) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = keyboardText,
                    onValueChange = onKeyboardText,
                    label = { Text("Send text") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        viewModel.sendText(keyboardText)
                        onKeyboardText("")
                    },
                    enabled = keyboardText.isNotEmpty(),
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) { Text("Send") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = resizeWidth,
                    onValueChange = { if (it.all(Char::isDigit)) onResizeWidth(it) },
                    label = { Text("Remote width") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = resizeHeight,
                    onValueChange = { if (it.all(Char::isDigit)) onResizeHeight(it) },
                    label = { Text("Remote height") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = {
                        val w = resizeWidth.toIntOrNull()
                        val h = resizeHeight.toIntOrNull()
                        if (w != null && h != null && w > 0 && h > 0) viewModel.resizeRemote(w, h)
                        else onMessage("Enter a valid remote width and height")
                    },
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) { Text("Resize") }
            }
        }
    }
}

@Composable
private fun VncProfileEditor(
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

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DesktopWindows, null) },
        title = { Text("VNC connection") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
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

                HorizontalDivider()
                Text("Protocol", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    securityType,
                    { if (it.all { c -> c.isDigit() }) securityType = it },
                    label = { Text("RFB security type (0 = automatic)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Image quality: ${imageQuality.toInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(value = imageQuality, onValueChange = { imageQuality = it }, valueRange = 0f..9f, steps = 8)
                SettingSwitch("Raw encoding only (debug/compatibility)", raw) { raw = it }
                SettingSwitch("Local cursor", localCursor) { localCursor = it }
                SettingSwitch("View only", viewOnly) { viewOnly = it }
                SettingSwitch("Direct touch instead of touchpad", directTouch) { directTouch = it }
                SettingSwitch("Clipboard sync", clipboardSync) { clipboardSync = it }
                SettingSwitch("Reconnect automatically", autoReconnect) { autoReconnect = it }

                HorizontalDivider()
                Text("Advanced", fontWeight = FontWeight.Bold)
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
                Text(
                    "AVNC/LibVNCClient is embedded only as the VNC protocol/rendering engine. These settings belong to this standalone viewer and do not modify any DroidSpaces container.",
                    style = MaterialTheme.typography.labelSmall
                )
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
private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
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
