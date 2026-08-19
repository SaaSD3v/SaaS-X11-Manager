package com.saas.x11manager.ui.screen

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Keyboard
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.LoaderStatus
import com.saas.x11manager.util.ScreenConfig
import com.saas.x11manager.util.ScreenFiltering
import com.saas.x11manager.util.ScreenManager
import com.saas.x11manager.util.ScreenResolutionMode
import com.saas.x11manager.util.ScreenTouchMode
import kotlinx.coroutines.launch

/**
 * Screen tab with the X11 renderer embedded in the app itself.
 *
 * The SurfaceView is kept outside the LazyColumn so scrolling through settings
 * never disposes the renderer or drops the X11 connection.
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
    var embeddedView by remember { mutableStateOf<EmbeddedX11View?>(null) }
    var surfaceConnected by remember { mutableStateOf(false) }
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

    fun applyToEmbeddedSurface(next: ScreenConfig = config) {
        ScreenManager.apply(context, next)
        embeddedView?.postDelayed({
            embeddedView?.applyScreenConfig(next)
            embeddedView?.keepScreenOn =
                serverStatus == LoaderStatus.Running && next.keepScreenAwake
        }, 100L)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        EmbeddedViewportCard(
            status = serverStatus,
            connected = surfaceConnected,
            config = config,
            onViewReady = { view ->
                embeddedView = view
                view.setConnectionListener { surfaceConnected = it }
                view.applyScreenConfig(config)
                view.keepScreenOn =
                    serverStatus == LoaderStatus.Running && config.keepScreenAwake
            }
        )

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                RuntimeControlsCard(
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
                                    message = "X11 started inside Screen · PID $pid"
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
                                    message = "X11 screen stopped"
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

            item {
                ConfigSection(
                    icon = Icons.Default.DisplaySettings,
                    title = "Output",
                    subtitle = "Resolution and rendering inside this tab"
                ) {
                    Text("Resolution mode", fontWeight = FontWeight.SemiBold)
                    IntegratedChoiceChips(
                        options = ScreenResolutionMode.entries.map { it.label },
                        selected = config.resolutionMode.label,
                        onSelected = { label ->
                            updateConfig(
                                config.copy(
                                    resolutionMode = ScreenResolutionMode.entries.first { it.label == label }
                                )
                            )
                        }
                    )

                    when (config.resolutionMode) {
                        ScreenResolutionMode.Native -> HintText(
                            "The X11 desktop follows the embedded surface size."
                        )
                        ScreenResolutionMode.Scaled -> {
                            Text("Scale · ${config.scalePercent}%")
                            Slider(
                                value = config.scalePercent.toFloat(),
                                onValueChange = { value ->
                                    val stepped = ((value / 10f).toInt() * 10).coerceIn(30, 300)
                                    updateConfig(config.copy(scalePercent = stepped))
                                },
                                valueRange = 30f..300f,
                                steps = 26
                            )
                        }
                        ScreenResolutionMode.Exact -> IntegratedDropdown(
                            label = "Resolution",
                            selected = config.exactResolution,
                            options = ScreenManager.supportedExactResolutions,
                            onSelected = { updateConfig(config.copy(exactResolution = it)) }
                        )
                        ScreenResolutionMode.Custom -> OutlinedTextField(
                            value = config.customResolution,
                            onValueChange = { updateConfig(config.copy(customResolution = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Custom resolution") },
                            placeholder = { Text("1280x1024") }
                        )
                    }

                    IntegratedDropdown(
                        label = "Filtering",
                        selected = config.filtering.label,
                        options = ScreenFiltering.entries.map { it.label },
                        onSelected = { label ->
                            updateConfig(
                                config.copy(
                                    filtering = ScreenFiltering.entries.first { it.label == label }
                                )
                            )
                        }
                    )

                    IntegratedSwitch(
                        title = "Stretch exact resolution",
                        subtitle = "Fill the embedded viewport when Exact or Custom is selected",
                        checked = config.stretch,
                        enabled = config.resolutionMode == ScreenResolutionMode.Exact ||
                            config.resolutionMode == ScreenResolutionMode.Custom,
                        onCheckedChange = { updateConfig(config.copy(stretch = it)) }
                    )
                }
            }

            item {
                ConfigSection(
                    icon = Icons.Default.TouchApp,
                    title = "Input",
                    subtitle = "Touch, clipboard and keyboard for the embedded desktop"
                ) {
                    Text("Touch mode", fontWeight = FontWeight.SemiBold)
                    IntegratedChoiceChips(
                        options = ScreenTouchMode.entries.map { it.label },
                        selected = config.touchMode.label,
                        onSelected = { label ->
                            updateConfig(
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
                        onCheckedChange = { updateConfig(config.copy(clipboard = it)) }
                    )
                    IntegratedSwitch(
                        title = "Extra keyboard row",
                        subtitle = "Keep desktop navigation keys directly below the X11 surface",
                        checked = config.showAdditionalKeyboard,
                        onCheckedChange = { updateConfig(config.copy(showAdditionalKeyboard = it)) }
                    )
                    IntegratedSwitch(
                        title = "Keep screen awake",
                        subtitle = "Prevent Android from sleeping while X11 is active in this tab",
                        checked = config.keepScreenAwake,
                        onCheckedChange = { updateConfig(config.copy(keepScreenAwake = it)) }
                    )
                }
            }

            item {
                ConfigSection(
                    icon = Icons.Default.Settings,
                    title = "Settings",
                    subtitle = "Apply the options to the live embedded renderer"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                applyToEmbeddedSurface()
                                message = if (serverStatus == LoaderStatus.Running) {
                                    "Settings applied to the embedded screen"
                                } else {
                                    "Settings saved for the next X11 start"
                                }
                                messageIsError = false
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Apply")
                        }

                        OutlinedButton(
                            onClick = {
                                val defaults = ScreenConfig()
                                updateConfig(defaults)
                                applyToEmbeddedSurface(defaults)
                                message = "Screen settings restored to defaults"
                                messageIsError = false
                            },
                            enabled = !busy
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Reset")
                        }
                    }

                    HintText(
                        "The X11 pixels stay in this Screen tab. Start no longer launches the upstream Lorie Activity."
                    )
                }
            }
        }
    }
}

@Composable
private fun EmbeddedViewportCard(
    status: LoaderStatus,
    connected: Boolean,
    config: ScreenConfig,
    onViewReady: (EmbeddedX11View) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "X11 · ${Constants.X11_DISPLAY}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            status != LoaderStatus.Running -> "Stopped · output stays here"
                            connected -> "Connected · embedded renderer"
                            else -> "Server running · attaching surface…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (status == LoaderStatus.Running && !connected) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            AndroidView(
                factory = { context ->
                    EmbeddedX11View(context).also(onViewReady)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp),
                update = { view ->
                    view.keepScreenOn = status == LoaderStatus.Running && config.keepScreenAwake
                }
            )

            if (config.showAdditionalKeyboard) {
                ExtraKeyRow()
            }
        }
    }
}

@Composable
private fun ExtraKeyRow() {
    var view by remember { mutableStateOf<EmbeddedX11View?>(null) }
    // The actual view is resolved from the focused AndroidView by the buttons through
    // the helper below. Keeping the row independent avoids forcing recomposition of
    // the SurfaceView itself while a desktop application is drawing.
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val activity = context as? android.app.Activity
        val root = activity?.window?.decorView
        fun embedded(): EmbeddedX11View? {
            view?.let { return it }
            fun find(v: android.view.View): EmbeddedX11View? {
                if (v is EmbeddedX11View) return v
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                }
                return null
            }
            return root?.let(::find)?.also { view = it }
        }

        listOf(
            "Esc" to KeyEvent.KEYCODE_ESCAPE,
            "Tab" to KeyEvent.KEYCODE_TAB,
            "←" to KeyEvent.KEYCODE_DPAD_LEFT,
            "↑" to KeyEvent.KEYCODE_DPAD_UP,
            "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
            "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "Enter" to KeyEvent.KEYCODE_ENTER
        ).forEach { (label, key) ->
            OutlinedButton(
                onClick = { embedded()?.sendKey(key) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) { Text(label) }
        }
        FilledTonalIconButton(onClick = { embedded()?.toggleSoftKeyboard() }) {
            Icon(Icons.Default.Keyboard, contentDescription = "Keyboard")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuntimeControlsCard(
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
        icon = Icons.Default.DisplaySettings,
        title = "Runtime",
        subtitle = if (connected) "X11 is attached to the Screen tab" else "Embedded X11 server"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("PID: ${pid ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text("Socket: ${Constants.X11_SOCK_FILE}", style = MaterialTheme.typography.bodySmall)
        }

        if (containerNames.isNotEmpty()) {
            IntegratedDropdown(
                label = "XKB source container",
                selected = selectedContainerName ?: containerNames.first(),
                options = containerNames,
                onSelected = onContainerSelected
            )
        } else {
            HintText("A container is only required to seed XKB data on the first start.")
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
                Text(if (status == LoaderStatus.Running) "Running" else "Start")
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
                message,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (messageIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ConfigSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
