package com.saas.x11manager.ui.screen.vnc

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gaurav.avnc.vnc.XKeySym
import com.saas.x11manager.embeddedvnc.EmbeddedVncFrameView
import com.saas.x11manager.embeddedvnc.EmbeddedVncState
import com.saas.x11manager.ui.component.OperationResultCard
import com.saas.x11manager.ui.component.TerminalDialog
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VncManagedScreen(
    viewModel: VncLauncherViewModel,
    onClose: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val selectedId by viewModel.selectedProfileId.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val detail by viewModel.connectionDetail.collectAsState()
    val screenEnabled by viewModel.screenEnabled.collectAsState()
    val framebuffer by viewModel.framebufferSize.collectAsState()

    val selectedProfile = profiles.firstOrNull { it.id == selectedId }
    val activeProfile = viewModel.activeProfile()
    val shownProfile = activeProfile ?: selectedProfile

    var fullscreen by remember { mutableStateOf(false) }
    var showFullscreenExitConfirmation by remember { mutableStateOf(false) }
    var additionalKeysVisible by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<VncLauncherProfile?>(null) }

    val context = LocalContext.current
    val activity = remember(context) { context.findVncActivity() }

    DisposableEffect(fullscreen, activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(state) {
        if (state != EmbeddedVncState.CONNECTED) {
            additionalKeysVisible = false
            if (fullscreen) fullscreen = false
        }
    }

    BackHandler {
        if (fullscreen) showFullscreenExitConfirmation = true else onClose()
    }

    if (viewModel.showLogs) {
        viewModel.logOperation?.let { operation ->
            TerminalDialog(
                title = operation.title,
                logs = operation.logs,
                onDismiss = viewModel::dismissLogs,
                onMinimize = viewModel::minimizeLogs,
                onClear = viewModel::clearLogs,
                isBlocking = operation.running
            )
        }
    }

    if (showFullscreenExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showFullscreenExitConfirmation = false },
            title = { Text("Exit fullscreen?") },
            text = { Text("Return to the VNC controls while keeping the current connection open?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFullscreenExitConfirmation = false
                        fullscreen = false
                    }
                ) { Text("Exit fullscreen") }
            },
            dismissButton = {
                TextButton(onClick = { showFullscreenExitConfirmation = false }) {
                    Text("Stay fullscreen")
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (fullscreen) Color.Black else MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (fullscreen) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())
        ) {
            if (!fullscreen) {
                VncManagedTopBar(
                    profile = shownProfile,
                    state = state,
                    screenEnabled = screenEnabled,
                    framebuffer = framebuffer,
                    hasLogs = viewModel.selectedLogOperation?.available == true,
                    additionalKeysVisible = additionalKeysVisible,
                    onClose = onClose,
                    onShowLogs = { viewModel.openLogs() },
                    onToggleAdditionalKeys = {
                        if (state == EmbeddedVncState.CONNECTED) additionalKeysVisible = !additionalKeysVisible
                    },
                    onFullscreen = { fullscreen = true },
                    onTools = { showTools = true }
                )

                VncDisplayDeck(
                    profiles = profiles,
                    selectedId = selectedId,
                    activeProfile = activeProfile,
                    state = state,
                    framebuffer = framebuffer,
                    singleDisplayMode = viewModel.singleDisplayMode,
                    onAdd = {
                        editor = VncLauncherProfile(
                            id = UUID.randomUUID().toString(),
                            name = "VNC connection"
                        )
                    },
                    onSelect = viewModel::selectProfile,
                    onEdit = { editor = it },
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect
                )

                viewModel.selectedLogOperation?.let { operation ->
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        OperationResultCard(operation) { viewModel.openLogs() }
                    }
                }

                if (state == EmbeddedVncState.ERROR && !detail.isNullOrBlank()) {
                    VncErrorMessage(detail!!)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (fullscreen) Modifier else Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(8.dp),
                    color = Color.Black,
                    tonalElevation = 0.dp,
                    border = if (fullscreen) null else BorderStroke(
                        1.dp,
                        if (state == EmbeddedVncState.CONNECTED) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        }
                    )
                ) {
                    VncViewport(
                        viewModel = viewModel,
                        profile = shownProfile,
                        activeProfile = activeProfile,
                        state = state,
                        detail = detail,
                        screenEnabled = screenEnabled
                    )
                }
            }

            if (!fullscreen && additionalKeysVisible && state == EmbeddedVncState.CONNECTED) {
                VncExtraKeysBar(viewModel)
            }
        }
    }

    if (showTools) {
        VncToolsDialog(
            viewModel = viewModel,
            profile = shownProfile,
            state = state,
            screenEnabled = screenEnabled,
            framebuffer = framebuffer,
            onEditConnection = {
                showTools = false
                shownProfile?.let { editor = it }
            },
            onDismiss = { showTools = false }
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
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VncManagedTopBar(
    profile: VncLauncherProfile?,
    state: EmbeddedVncState,
    screenEnabled: Boolean,
    framebuffer: Pair<Int, Int>?,
    hasLogs: Boolean,
    additionalKeysVisible: Boolean,
    onClose: () -> Unit,
    onShowLogs: () -> Unit,
    onToggleAdditionalKeys: () -> Unit,
    onFullscreen: () -> Unit,
    onTools: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text("VNC Screen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        if (profile == null) {
                            append("No connection selected")
                        } else {
                            append(profile.name)
                            append(" · ")
                            append(profile.host)
                            append(":")
                            append(profile.port)
                            append(" · ")
                            append(
                                when (state) {
                                    EmbeddedVncState.CONNECTED -> if (screenEnabled) "connected" else "screen off"
                                    EmbeddedVncState.CONNECTING -> "connecting"
                                    EmbeddedVncState.ERROR -> "error"
                                    EmbeddedVncState.OFF -> "disconnected"
                                }
                            )
                            framebuffer?.let { append(" · ${it.first}×${it.second}") }
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close VNC screen")
            }
        },
        actions = {
            if (hasLogs) {
                IconButton(onClick = onShowLogs) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = "VNC logs")
                }
            }
            IconButton(onClick = onToggleAdditionalKeys, enabled = state == EmbeddedVncState.CONNECTED) {
                Icon(
                    Icons.Default.Keyboard,
                    contentDescription = "VNC additional keys",
                    tint = if (additionalKeysVisible) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onFullscreen, enabled = state == EmbeddedVncState.CONNECTED && screenEnabled) {
                Icon(Icons.Default.Fullscreen, contentDescription = "VNC fullscreen")
            }
            IconButton(onClick = onTools) {
                Icon(Icons.Default.Settings, contentDescription = "VNC tools")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun VncDisplayDeck(
    profiles: List<VncLauncherProfile>,
    selectedId: String?,
    activeProfile: VncLauncherProfile?,
    state: EmbeddedVncState,
    framebuffer: Pair<Int, Int>?,
    singleDisplayMode: Boolean,
    onAdd: () -> Unit,
    onSelect: (String?) -> Unit,
    onEdit: (VncLauncherProfile) -> Unit,
    onConnect: (VncLauncherProfile) -> Unit,
    onDisconnect: () -> Unit
) {
    val activeCount = if (
        activeProfile != null &&
        (state == EmbeddedVncState.CONNECTED || state == EmbeddedVncState.CONNECTING)
    ) 1 else 0
    val canAdd = !singleDisplayMode || profiles.isEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (singleDisplayMode) "VNC display" else "VNC displays",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "$activeCount active",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (canAdd) {
                IconButton(onClick = onAdd, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = if (singleDisplayMode) "Register VNC display" else "Add VNC connection"
                    )
                }
            }
        }

        if (profiles.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No VNC connection", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Register a connection before turning on the VNC screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (singleDisplayMode) {
            val profile = profiles.first()
            VncDisplayCard(
                profile = profile,
                selected = true,
                active = activeProfile?.id == profile.id,
                state = state,
                framebuffer = framebuffer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                onSelect = { onSelect(profile.id) },
                onEdit = { onEdit(profile) },
                onConnect = { onConnect(profile) },
                onDisconnect = onDisconnect
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    VncDisplayCard(
                        profile = profile,
                        selected = selectedId == profile.id,
                        active = activeProfile?.id == profile.id,
                        state = state,
                        framebuffer = if (activeProfile?.id == profile.id) framebuffer else null,
                        modifier = Modifier.width(230.dp),
                        onSelect = { onSelect(profile.id) },
                        onEdit = { onEdit(profile) },
                        onConnect = { onConnect(profile) },
                        onDisconnect = onDisconnect
                    )
                }
            }
        }
    }
}

@Composable
private fun VncDisplayCard(
    profile: VncLauncherProfile,
    selected: Boolean,
    active: Boolean,
    state: EmbeddedVncState,
    framebuffer: Pair<Int, Int>?,
    modifier: Modifier,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val running = active && (state == EmbeddedVncState.CONNECTED || state == EmbeddedVncState.CONNECTING)

    Surface(
        modifier = modifier.clickable(onClick = onSelect),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${profile.host}:${profile.port}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Settings, "Edit VNC connection", modifier = Modifier.size(18.dp))
                }
            }

            Text(
                when {
                    active && state == EmbeddedVncState.CONNECTED -> framebuffer?.let { "${it.first} × ${it.second}" } ?: "Connected"
                    active && state == EmbeddedVncState.CONNECTING -> "Connecting"
                    active && state == EmbeddedVncState.ERROR -> "Connection error"
                    else -> "Ready"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (running) "Active" else "Inactive",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (running) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 9.dp),
                        shape = RoundedCornerShape(7.dp)
                    ) {
                        if (state == EmbeddedVncState.CONNECTING) {
                            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Stop, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Disconnect", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = onConnect,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 11.dp),
                        shape = RoundedCornerShape(7.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Connect", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun VncViewport(
    viewModel: VncLauncherViewModel,
    profile: VncLauncherProfile?,
    activeProfile: VncLauncherProfile?,
    state: EmbeddedVncState,
    detail: String?,
    screenEnabled: Boolean
) {
    Box(Modifier.fillMaxSize()) {
        when {
            profile == null -> VncViewportMessage(
                title = "No VNC display",
                detail = "Register a connection from the VNC launcher or the + button."
            )

            state == EmbeddedVncState.CONNECTING -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text("Connecting to ${profile.name}", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
                }
            }

            state == EmbeddedVncState.CONNECTED && activeProfile != null && screenEnabled -> {
                key("vnc-frame-${activeProfile.id}") {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            EmbeddedVncFrameView(context).apply {
                                attachSession(viewModel.session)
                                directTouch = activeProfile.directTouch
                                inputEnabled = !activeProfile.viewOnly
                            }
                        },
                        update = { frame ->
                            frame.directTouch = activeProfile.directTouch
                            frame.inputEnabled = !activeProfile.viewOnly
                        }
                    )
                }
            }

            state == EmbeddedVncState.CONNECTED && !screenEnabled -> VncViewportMessage(
                title = "VNC screen is off",
                detail = "The connection stays open while framebuffer updates are paused.",
                icon = Icons.Default.VisibilityOff
            )

            state == EmbeddedVncState.ERROR -> VncViewportMessage(
                title = "VNC connection error",
                detail = detail ?: "Open logs for connection details.",
                icon = Icons.Default.ErrorOutline
            )

            else -> VncViewportMessage(
                title = "${profile.name} is disconnected",
                detail = "Connect it from the VNC display card.",
                icon = Icons.Default.DesktopAccessDisabled
            )
        }
    }
}

@Composable
private fun BoxScope.VncViewportMessage(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.DesktopWindows
) {
    Column(
        modifier = Modifier.align(Alignment.Center).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.82f), modifier = Modifier.size(30.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(detail, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun VncErrorMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 0.dp
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun VncExtraKeysBar(viewModel: VncLauncherViewModel) {
    val keys = remember {
        listOf(
            "Esc" to XKeySym.XK_Escape,
            "Tab" to XKeySym.XK_Tab,
            "Ctrl" to XKeySym.XK_Control_L,
            "Alt" to XKeySym.XK_Alt_L,
            "Super" to XKeySym.XK_Super_L
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            keys.forEach { (label, key) ->
                FilledTonalButton(
                    onClick = { viewModel.tapKeySym(key) },
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(7.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun VncToolsDialog(
    viewModel: VncLauncherViewModel,
    profile: VncLauncherProfile?,
    state: EmbeddedVncState,
    screenEnabled: Boolean,
    framebuffer: Pair<Int, Int>?,
    onEditConnection: () -> Unit,
    onDismiss: () -> Unit
) {
    var keyboardText by remember { mutableStateOf("") }
    var resizeWidth by remember(framebuffer) { mutableStateOf(framebuffer?.first?.toString().orEmpty()) }
    var resizeHeight by remember(framebuffer) { mutableStateOf(framebuffer?.second?.toString().orEmpty()) }
    val connected = state == EmbeddedVncState.CONNECTED

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Settings, null) },
        title = { Text("VNC tools") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Screen", fontWeight = FontWeight.SemiBold)
                SettingSwitch("Framebuffer updates", screenEnabled) { if (connected) viewModel.setScreenEnabled(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = viewModel::refreshFramebuffer,
                        enabled = connected,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Refresh")
                    }
                    FilledTonalButton(
                        onClick = viewModel::resetZoom,
                        enabled = connected && screenEnabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FullscreenExit, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Reset zoom")
                    }
                }

                HorizontalDivider()
                Text("Input", fontWeight = FontWeight.SemiBold)
                if (profile != null) {
                    SettingSwitch("Direct touch", profile.directTouch) {
                        viewModel.saveProfile(profile.copy(directTouch = it))
                    }
                    SettingSwitch("View only", profile.viewOnly) {
                        viewModel.saveProfile(profile.copy(viewOnly = it))
                    }
                }
                FilledTonalButton(
                    onClick = viewModel::sendClipboardFromAndroid,
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentPaste, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Send Android clipboard")
                }

                HorizontalDivider()
                Text("Keyboard", fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = keyboardText,
                        onValueChange = { keyboardText = it },
                        label = { Text("Send text") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            viewModel.sendText(keyboardText)
                            keyboardText = ""
                        },
                        enabled = connected && keyboardText.isNotEmpty()
                    ) { Text("Send") }
                }

                HorizontalDivider()
                Text("Remote display", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = resizeWidth,
                        onValueChange = { if (it.all(Char::isDigit)) resizeWidth = it },
                        label = { Text("Width") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = resizeHeight,
                        onValueChange = { if (it.all(Char::isDigit)) resizeHeight = it },
                        label = { Text("Height") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                FilledTonalButton(
                    onClick = {
                        val width = resizeWidth.toIntOrNull()
                        val height = resizeHeight.toIntOrNull()
                        if (width != null && height != null && width > 0 && height > 0) {
                            viewModel.resizeRemote(width, height)
                        }
                    },
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Resize remote desktop") }

                HorizontalDivider()
                Text("Connection", fontWeight = FontWeight.SemiBold)
                if (profile != null) {
                    OutlinedButton(onClick = onEditConnection, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Settings, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Edit connection settings")
                    }
                }
                if (connected || state == EmbeddedVncState.CONNECTING) {
                    OutlinedButton(
                        onClick = {
                            viewModel.disconnect()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LinkOff, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Disconnect")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        shape = RoundedCornerShape(26.dp)
    )
}

private tailrec fun Context.findVncActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findVncActivity()
    else -> null
}
