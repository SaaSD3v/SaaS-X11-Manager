package com.saas.x11manager.ui.screen

import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.LoaderStatus
import com.saas.x11manager.util.ScreenConfig
import com.saas.x11manager.util.ScreenFiltering
import com.saas.x11manager.util.ScreenManager
import com.saas.x11manager.util.ScreenResolutionMode
import com.saas.x11manager.util.ScreenTouchMode
import kotlinx.coroutines.launch

enum class ScreenSettingsPage { Display, X11 }

/**
 * Project-owned Screen UI.
 *
 * Display presentation and X11 runtime controls are deliberately separated.
 * The X11 SurfaceView remains embedded in this app; fullscreen is an immersive
 * viewer for the same embedded renderer, not the upstream Lorie Activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegratedScreenScreen(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val serverStatus by viewModel.loaderStatus.collectAsState()
    val serverPid by viewModel.loaderPid.collectAsState()
    val containers by viewModel.containers.collectAsState()
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(ScreenManager.load(context)) }
    var selectedContainerName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPage by rememberSaveable { mutableStateOf(ScreenSettingsPage.Display) }
    var embeddedView by remember { mutableStateOf<EmbeddedX11View?>(null) }
    var surfaceConnected by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    LaunchedEffect(containers) {
        val names = containers.map { it.name }
        if (selectedContainerName !in names) {
            selectedContainerName = containers.firstOrNull { it.isRunning }?.name
                ?: containers.firstOrNull()?.name
        }
    }

    LaunchedEffect(serverStatus, config.keepScreenAwake, embeddedView) {
        embeddedView?.keepScreenOn =
            serverStatus == LoaderStatus.Running && config.keepScreenAwake
    }

    fun updateConfig(next: ScreenConfig) {
        config = next.normalized()
        ScreenManager.save(context, config)
        message = null
    }

    fun bindView(view: EmbeddedX11View) {
        embeddedView = view
        view.setConnectionListener { surfaceConnected = it }
        view.applyScreenConfig(config)
        view.keepScreenOn = serverStatus == LoaderStatus.Running && config.keepScreenAwake
    }

    fun applyToEmbeddedSurface(next: ScreenConfig = config) {
        ScreenManager.apply(context, next)
        embeddedView?.postDelayed({
            embeddedView?.applyScreenConfig(next)
            embeddedView?.keepScreenOn =
                serverStatus == LoaderStatus.Running && next.keepScreenAwake
        }, 100L)
    }

    if (fullscreen) {
        FullscreenX11Dialog(
            status = serverStatus,
            config = config,
            onViewReady = ::bindView,
            onDismiss = {
                surfaceConnected = false
                embeddedView = null
                fullscreen = false
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        DisplayViewportCard(
            status = serverStatus,
            connected = surfaceConnected,
            config = config,
            view = embeddedView,
            onViewReady = ::bindView,
            onFullscreen = {
                surfaceConnected = false
                embeddedView = null
                fullscreen = true
            }
        )

        Spacer(Modifier.height(10.dp))

        SettingsPageSelector(
            selected = selectedPage,
            onSelected = { selectedPage = it }
        )

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedPage) {
                ScreenSettingsPage.Display -> {
                    item {
                        DisplaySettingsCard(
                            config = config,
                            onConfigChanged = ::updateConfig
                        )
                    }
                    item {
                        InputSettingsCard(
                            config = config,
                            onConfigChanged = ::updateConfig
                        )
                    }
                    item {
                        ApplySettingsCard(
                            busy = busy,
                            running = serverStatus == LoaderStatus.Running,
                            onApply = {
                                applyToEmbeddedSurface()
                                message = if (serverStatus == LoaderStatus.Running) {
                                    "Display settings applied"
                                } else {
                                    "Display settings saved for the next X11 start"
                                }
                                messageIsError = false
                            },
                            onReset = {
                                val defaults = ScreenConfig()
                                updateConfig(defaults)
                                applyToEmbeddedSurface(defaults)
                                message = "Display settings restored to defaults"
                                messageIsError = false
                            }
                        )
                    }
                }

                ScreenSettingsPage.X11 -> {
                    item {
                        X11RuntimeCard(
                            status = serverStatus,
                            pid = serverPid,
                            connected = surfaceConnected,
                            selectedContainerName = selectedContainerName,
                            containerNames = containers.map { it.name },
                            busy = busy,
                            message = message,
                            messageIsError = messageIsError,
                            onContainerSelected = { selectedContainerName = it },
                            onStart = {
                                scope.launch {
                                    busy = true
                                    message = null
                                    messageIsError = false
                                    try {
                                        val result = ScreenManager.startServer(
                                            context = context,
                                            xkbContainerName = selectedContainerName,
                                            config = config
                                        )
                                        if (result.isSuccess) {
                                            val pid = result.getOrThrow()
                                            embeddedView?.postDelayed({
                                                embeddedView?.applyScreenConfig(config)
                                            }, 100L)
                                            message = "X11 server started · PID $pid"
                                        } else {
                                            message = result.exceptionOrNull()?.message
                                                ?: "X11 server could not start"
                                            messageIsError = true
                                        }
                                    } finally {
                                        busy = false
                                        viewModel.refreshRuntimeState()
                                    }
                                }
                            },
                            onStop = {
                                scope.launch {
                                    busy = true
                                    message = null
                                    messageIsError = false
                                    try {
                                        if (ScreenManager.stop()) {
                                            message = "X11 server stopped"
                                            surfaceConnected = false
                                        } else {
                                            message = "X11 server could not be stopped cleanly"
                                            messageIsError = true
                                        }
                                    } finally {
                                        busy = false
                                        viewModel.refreshRuntimeState()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayViewportCard(
    status: LoaderStatus,
    connected: Boolean,
    config: ScreenConfig,
    view: EmbeddedX11View?,
    onViewReady: (EmbeddedX11View) -> Unit,
    onFullscreen: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Display",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        displayStatusText(status, connected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalButton(
                    onClick = onFullscreen,
                    enabled = status == LoaderStatus.Running
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Fullscreen")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
            ) {
                AndroidView(
                    factory = { context ->
                        EmbeddedX11View(context).also(onViewReady)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(previewAspectRatio(config)),
                    update = { embedded ->
                        embedded.keepScreenOn =
                            status == LoaderStatus.Running && config.keepScreenAwake
                    }
                )
            }

            if (config.showAdditionalKeyboard) {
                ImeToolbar(
                    view = view,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun FullscreenX11Dialog(
    status: LoaderStatus,
    config: ScreenConfig,
    onViewReady: (EmbeddedX11View) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogView = LocalView.current
        var fullscreenView by remember { mutableStateOf<EmbeddedX11View?>(null) }
        var connected by remember { mutableStateOf(false) }

        BackHandler(onBack = onDismiss)

        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            if (window != null) {
                window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            onDispose {
                if (window != null) {
                    WindowInsetsControllerCompat(window, window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { context ->
                    EmbeddedX11View(context).also { embedded ->
                        fullscreenView = embedded
                        embedded.setConnectionListener { connected = it }
                        embedded.applyScreenConfig(config)
                        embedded.keepScreenOn =
                            status == LoaderStatus.Running && config.keepScreenAwake
                        onViewReady(embedded)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { embedded ->
                    embedded.keepScreenOn =
                        status == LoaderStatus.Running && config.keepScreenAwake
                }
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                ) {
                    Text(
                        if (connected) "X11 connected" else "Attaching X11…",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                FilledTonalIconButton(onClick = onDismiss) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "Exit fullscreen")
                }
            }

            if (config.showAdditionalKeyboard) {
                ImeToolbar(
                    view = fullscreenView,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsPageSelector(
    selected: ScreenSettingsPage,
    onSelected: (ScreenSettingsPage) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selected == ScreenSettingsPage.Display,
                onClick = { onSelected(ScreenSettingsPage.Display) },
                label = { Text("Display") },
                leadingIcon = {
                    Icon(Icons.Default.DisplaySettings, contentDescription = null)
                },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = selected == ScreenSettingsPage.X11,
                onClick = { onSelected(ScreenSettingsPage.X11) },
                label = { Text("X11") },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DisplaySettingsCard(
    config: ScreenConfig,
    onConfigChanged: (ScreenConfig) -> Unit
) {
    ConfigSection(
        icon = Icons.Default.DisplaySettings,
        title = "Display settings",
        subtitle = "Viewport size, scaling and image quality"
    ) {
        Text("Resolution mode", fontWeight = FontWeight.SemiBold)
        IntegratedChoiceChips(
            options = ScreenResolutionMode.entries.map { it.label },
            selected = config.resolutionMode.label,
            onSelected = { label ->
                onConfigChanged(
                    config.copy(
                        resolutionMode = ScreenResolutionMode.entries.first { it.label == label }
                    )
                )
            }
        )

        when (config.resolutionMode) {
            ScreenResolutionMode.Native -> HintText(
                "Native follows the size of the embedded viewer. Fullscreen automatically uses the whole Android display."
            )

            ScreenResolutionMode.Scaled -> {
                Text(
                    "Scale · ${config.scalePercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = config.scalePercent.toFloat(),
                    onValueChange = { value ->
                        val stepped = ((value / 10f).toInt() * 10).coerceIn(30, 300)
                        onConfigChanged(config.copy(scalePercent = stepped))
                    },
                    valueRange = 30f..300f,
                    steps = 26
                )
            }

            ScreenResolutionMode.Exact -> IntegratedDropdown(
                label = "Resolution",
                selected = config.exactResolution,
                options = ScreenManager.supportedExactResolutions,
                onSelected = { onConfigChanged(config.copy(exactResolution = it)) }
            )

            ScreenResolutionMode.Custom -> OutlinedTextField(
                value = config.customResolution,
                onValueChange = { onConfigChanged(config.copy(customResolution = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Custom resolution") },
                placeholder = { Text("1280x1024") },
                supportingText = { Text("Format: widthxheight") }
            )
        }

        IntegratedDropdown(
            label = "Filtering",
            selected = config.filtering.label,
            options = ScreenFiltering.entries.map { it.label },
            onSelected = { label ->
                onConfigChanged(
                    config.copy(
                        filtering = ScreenFiltering.entries.first { it.label == label }
                    )
                )
            }
        )

        IntegratedSwitch(
            title = "Fill viewport",
            subtitle = "Stretch Exact or Custom resolutions to the available display area",
            checked = config.stretch,
            enabled = config.resolutionMode == ScreenResolutionMode.Exact ||
                config.resolutionMode == ScreenResolutionMode.Custom,
            onCheckedChange = { onConfigChanged(config.copy(stretch = it)) }
        )

        HintText(
            "Fullscreen is controlled by the Display viewer above. It no longer depends on the removed Lorie Activity."
        )
    }
}

@Composable
private fun InputSettingsCard(
    config: ScreenConfig,
    onConfigChanged: (ScreenConfig) -> Unit
) {
    ConfigSection(
        icon = Icons.Default.TouchApp,
        title = "Input & IME",
        subtitle = "Touch behavior and optional desktop key controls"
    ) {
        Text("Touch mode", fontWeight = FontWeight.SemiBold)
        IntegratedChoiceChips(
            options = ScreenTouchMode.entries.map { it.label },
            selected = config.touchMode.label,
            onSelected = { label ->
                onConfigChanged(
                    config.copy(
                        touchMode = ScreenTouchMode.entries.first { it.label == label }
                    )
                )
            }
        )

        IntegratedSwitch(
            title = "Clipboard",
            subtitle = "Synchronize Android text clipboard with X11",
            checked = config.clipboard,
            onCheckedChange = { onConfigChanged(config.copy(clipboard = it)) }
        )

        IntegratedSwitch(
            title = "IME & desktop key bar",
            subtitle = "Show Esc, Tab, arrows, Enter and an Android keyboard button below the display",
            checked = config.showAdditionalKeyboard,
            onCheckedChange = { onConfigChanged(config.copy(showAdditionalKeyboard = it)) }
        )

        IntegratedSwitch(
            title = "Keep display awake",
            subtitle = "Prevent Android from sleeping while the X11 server is active",
            checked = config.keepScreenAwake,
            onCheckedChange = { onConfigChanged(config.copy(keepScreenAwake = it)) }
        )
    }
}

@Composable
private fun ApplySettingsCard(
    busy: Boolean,
    running: Boolean,
    onApply: () -> Unit,
    onReset: () -> Unit
) {
    ConfigSection(
        icon = Icons.Default.Settings,
        title = "Display profile",
        subtitle = "Apply the current options to the embedded renderer"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onApply,
                modifier = Modifier.weight(1f),
                enabled = !busy
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (running) "Apply" else "Save")
            }

            OutlinedButton(onClick = onReset, enabled = !busy) {
                Icon(Icons.Default.Restore, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Reset")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun X11RuntimeCard(
    status: LoaderStatus,
    pid: Int?,
    connected: Boolean,
    selectedContainerName: String?,
    containerNames: List<String>,
    busy: Boolean,
    message: String?,
    messageIsError: Boolean,
    onContainerSelected: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    ConfigSection(
        icon = Icons.Default.Settings,
        title = "X11 server",
        subtitle = "Runtime controls are separate from Display presentation"
    ) {
        RuntimeInfoRow("State", if (status == LoaderStatus.Running) "Running" else "Stopped")
        RuntimeInfoRow("Surface", if (connected) "Attached" else "Not attached")
        RuntimeInfoRow("Display", Constants.X11_DISPLAY)
        RuntimeInfoRow("PID", pid?.toString() ?: "—")
        RuntimeInfoRow("Socket", Constants.X11_SOCK_FILE)

        if (containerNames.isNotEmpty()) {
            IntegratedDropdown(
                label = "XKB source container",
                selected = selectedContainerName ?: containerNames.first(),
                options = containerNames,
                onSelected = onContainerSelected
            )
            HintText("The container is only used to seed XKB data when the cache is empty.")
        } else {
            HintText("A configured container is only required for the first XKB bootstrap.")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onStart,
                enabled = !busy && status != LoaderStatus.Running,
                modifier = Modifier.weight(1f)
            ) {
                if (busy && status != LoaderStatus.Running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                }
                Spacer(Modifier.width(6.dp))
                Text(if (status == LoaderStatus.Running) "Running" else "Start X11")
            }

            OutlinedButton(
                onClick = onStop,
                enabled = !busy && status == LoaderStatus.Running
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Stop")
            }
        }

        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (messageIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
    }
}

@Composable
private fun RuntimeInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ImeToolbar(
    view: EmbeddedX11View?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ImeKeyButton(label = "Esc", enabled = view != null) {
                view?.sendKey(KeyEvent.KEYCODE_ESCAPE)
            }
            ImeKeyButton(label = "Tab", enabled = view != null) {
                view?.sendKey(KeyEvent.KEYCODE_TAB)
            }
            ImeKeyButton(
                icon = Icons.Default.KeyboardArrowLeft,
                contentDescription = "Left",
                enabled = view != null
            ) { view?.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            ImeKeyButton(
                icon = Icons.Default.KeyboardArrowUp,
                contentDescription = "Up",
                enabled = view != null
            ) { view?.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
            ImeKeyButton(
                icon = Icons.Default.KeyboardArrowDown,
                contentDescription = "Down",
                enabled = view != null
            ) { view?.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            ImeKeyButton(
                icon = Icons.Default.KeyboardArrowRight,
                contentDescription = "Right",
                enabled = view != null
            ) { view?.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            ImeKeyButton(label = "Enter", enabled = view != null) {
                view?.sendKey(KeyEvent.KEYCODE_ENTER)
            }

            FilledTonalButton(
                onClick = { view?.toggleSoftKeyboard() },
                enabled = view != null,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Keyboard, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("IME")
            }
        }
    }
}

@Composable
private fun ImeKeyButton(
    label: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = label,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(40.dp)
            .defaultMinSize(minWidth = 42.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
            } else if (label != null) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ConfigSection(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun IntegratedChoiceChips(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelected(option) },
                label = { Text(option) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntegratedDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun IntegratedSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun displayStatusText(status: LoaderStatus, connected: Boolean): String = when {
    status != LoaderStatus.Running -> "X11 stopped"
    connected -> "Live · embedded X11 surface"
    else -> "X11 running · attaching display…"
}

private fun previewAspectRatio(config: ScreenConfig): Float {
    val resolution = when (config.resolutionMode) {
        ScreenResolutionMode.Exact -> config.exactResolution
        ScreenResolutionMode.Custom -> config.customResolution
        else -> "16x9"
    }
    val match = Regex("^(\\d+)x(\\d+)$").matchEntire(resolution.trim())
    val width = match?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 16f
    val height = match?.groupValues?.getOrNull(2)?.toFloatOrNull() ?: 9f
    if (width <= 0f || height <= 0f) return 16f / 9f
    return (width / height).coerceIn(1.15f, 2.4f)
}
