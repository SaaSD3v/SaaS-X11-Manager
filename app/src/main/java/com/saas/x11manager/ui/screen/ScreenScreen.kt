package com.saas.x11manager.ui.screen

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.LoaderStatus
import com.saas.x11manager.util.ScreenConfig
import com.saas.x11manager.util.ScreenFiltering
import com.saas.x11manager.util.ScreenManager
import com.saas.x11manager.util.ScreenOrientation
import com.saas.x11manager.util.ScreenResolutionMode
import com.saas.x11manager.util.ScreenTouchMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScreen(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val serverStatus by viewModel.loaderStatus.collectAsState()
    val serverPid by viewModel.loaderPid.collectAsState()
    val containers by viewModel.containers.collectAsState()
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(ScreenManager.load(context)) }
    var selectedContainerName by rememberSaveable { mutableStateOf<String?>(null) }
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

    fun updateConfig(next: ScreenConfig) {
        config = next
        ScreenManager.save(context, next)
        message = null
    }

    fun refreshRuntime() {
        viewModel.refreshRuntimeState()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenRuntimeCard(
                status = serverStatus,
                pid = serverPid,
                selectedContainerName = selectedContainerName,
                containerNames = containers.map { it.name },
                busy = busy,
                message = message,
                messageIsError = messageIsError,
                onContainerSelected = { selectedContainerName = it },
                onStartOrOpen = {
                    scope.launch {
                        busy = true
                        message = null
                        messageIsError = false
                        try {
                            if (serverStatus == LoaderStatus.Running) {
                                val opened = ScreenManager.open(context, config)
                                if (opened) {
                                    message = "Screen opened with the saved settings"
                                } else {
                                    message = "The X11 server is running, but the screen could not be opened"
                                    messageIsError = true
                                }
                            } else {
                                val result = ScreenManager.start(
                                    context = context,
                                    xkbContainerName = selectedContainerName,
                                    config = config
                                )
                                if (result.isSuccess) {
                                    val launch = result.getOrThrow()
                                    message = if (launch.displayOpened) {
                                        "Screen started · PID ${launch.pid}"
                                    } else {
                                        "X11 server started, but the screen Activity could not be opened"
                                    }
                                    messageIsError = !launch.displayOpened
                                } else {
                                    message = result.exceptionOrNull()?.message
                                        ?: "Screen could not be started"
                                    messageIsError = true
                                }
                            }
                        } finally {
                            busy = false
                            refreshRuntime()
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
                                message = "Screen stopped"
                            } else {
                                message = "Screen could not be stopped cleanly"
                                messageIsError = true
                            }
                        } finally {
                            busy = false
                            refreshRuntime()
                        }
                    }
                }
            )
        }

        item {
            ScreenSection(
                icon = Icons.Default.DisplaySettings,
                title = "Output",
                subtitle = "Resolution, scaling and presentation"
            ) {
                Text(
                    "Resolution mode",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                ChoiceChips(
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
                    ScreenResolutionMode.Native -> {
                        SupportingText("Uses the Android display's current native size.")
                    }
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
                                updateConfig(config.copy(scalePercent = stepped))
                            },
                            valueRange = 30f..300f,
                            steps = 26
                        )
                    }
                    ScreenResolutionMode.Exact -> {
                        DropdownSetting(
                            label = "Resolution",
                            selected = config.exactResolution,
                            options = ScreenManager.supportedExactResolutions,
                            onSelected = { updateConfig(config.copy(exactResolution = it)) }
                        )
                    }
                    ScreenResolutionMode.Custom -> {
                        OutlinedTextField(
                            value = config.customResolution,
                            onValueChange = { updateConfig(config.copy(customResolution = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Custom resolution") },
                            placeholder = { Text("1280x1024") },
                            supportingText = { Text("Format: widthxheight") }
                        )
                    }
                }

                DropdownSetting(
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

                DropdownSetting(
                    label = "Orientation",
                    selected = config.orientation.label,
                    options = ScreenOrientation.entries.map { it.label },
                    onSelected = { label ->
                        updateConfig(
                            config.copy(
                                orientation = ScreenOrientation.entries.first { it.label == label }
                            )
                        )
                    }
                )

                SwitchSetting(
                    title = "Fullscreen",
                    subtitle = "Use the entire Android display for the X11 surface",
                    checked = config.fullscreen,
                    onCheckedChange = { updateConfig(config.copy(fullscreen = it)) }
                )
                SwitchSetting(
                    title = "Stretch exact resolution",
                    subtitle = "Only affects Exact and Custom resolution modes",
                    checked = config.stretch,
                    enabled = config.resolutionMode == ScreenResolutionMode.Exact ||
                        config.resolutionMode == ScreenResolutionMode.Custom,
                    onCheckedChange = { updateConfig(config.copy(stretch = it)) }
                )
                SwitchSetting(
                    title = "Hide display cutout",
                    subtitle = "Keep the X11 surface away from notches and camera cutouts",
                    checked = config.hideCutout,
                    onCheckedChange = { updateConfig(config.copy(hideCutout = it)) }
                )
            }
        }

        item {
            ScreenSection(
                icon = Icons.Default.TouchApp,
                title = "Input",
                subtitle = "Touch, keyboard and Android integration"
            ) {
                Text(
                    "Touch mode",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                ChoiceChips(
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

                SwitchSetting(
                    title = "Clipboard",
                    subtitle = "Synchronize clipboard data with the X11 display",
                    checked = config.clipboard,
                    onCheckedChange = { updateConfig(config.copy(clipboard = it)) }
                )
                SwitchSetting(
                    title = "Additional keyboard bar",
                    subtitle = "Show the Lorie extra-key row for desktop shortcuts",
                    checked = config.showAdditionalKeyboard,
                    icon = Icons.Default.Keyboard,
                    onCheckedChange = { updateConfig(config.copy(showAdditionalKeyboard = it)) }
                )
                SwitchSetting(
                    title = "Keep screen awake",
                    subtitle = "Prevent Android from timing out while this display is in use",
                    checked = config.keepScreenAwake,
                    onCheckedChange = { updateConfig(config.copy(keepScreenAwake = it)) }
                )
            }
        }

        item {
            ScreenSection(
                icon = Icons.Default.Settings,
                title = "Settings",
                subtitle = "Apply or restore the project defaults"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            ScreenManager.apply(context, config)
                            message = if (serverStatus == LoaderStatus.Running) {
                                "Settings applied to the running screen"
                            } else {
                                "Settings saved for the next screen start"
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
                            ScreenManager.apply(context, defaults)
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

                SupportingText(
                    "These controls write directly to the embedded Lorie preference interface. " +
                        "They do not depend on the external Termux:X11 APK."
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenRuntimeCard(
    status: LoaderStatus,
    pid: Int?,
    selectedContainerName: String?,
    containerNames: List<String>,
    busy: Boolean,
    message: String?,
    messageIsError: Boolean,
    onContainerSelected: (String) -> Unit,
    onStartOrOpen: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.DisplaySettings,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            "DroidSpaces Screen",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Integrated X11 · embedded Lorie engine",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(if (status == LoaderStatus.Running) "Running" else "Stopped")
                    }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            ScreenInfoRow("Display", Constants.X11_DISPLAY)
            ScreenInfoRow("Socket", Constants.X11_SOCK_FILE)
            ScreenInfoRow("PID", pid?.toString() ?: "—")

            if (containerNames.isNotEmpty()) {
                DropdownSetting(
                    label = "XKB source container",
                    selected = selectedContainerName ?: containerNames.first(),
                    options = containerNames,
                    onSelected = onContainerSelected
                )
                SupportingText(
                    "Only needed to seed keyboard data the first time. The cache is reused afterwards."
                )
            } else {
                SupportingText(
                    "No container is available for first-time XKB setup. If the XKB cache already exists, Start can still reuse it."
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartOrOpen,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(if (status == LoaderStatus.Running) "Open" else "Start")
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
                    color = if (messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ScreenSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
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
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun ChoiceChips(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
private fun DropdownSetting(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
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
private fun SwitchSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun ScreenInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun SupportingText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
