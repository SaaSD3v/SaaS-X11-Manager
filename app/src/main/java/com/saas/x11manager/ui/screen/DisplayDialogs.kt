package com.saas.x11manager.ui.screen

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun X11ConfigurationDialog(
    store: SharedPreferences,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    fun putBoolean(key: String, value: Boolean) {
        store.edit().putBoolean(key, value).apply()
        publishLoriePreferenceChange(context, key)
    }

    fun putString(key: String, value: String) {
        store.edit().putString(key, value).apply()
        publishLoriePreferenceChange(context, key)
    }

    fun putInt(key: String, value: Int) {
        store.edit().putInt(key, value).apply()
        publishLoriePreferenceChange(context, key)
    }

    var resolutionMode by remember {
        mutableStateOf(store.getString("displayResolutionMode", "native") ?: "native")
    }
    var displayScale by remember { mutableIntStateOf(store.getInt("displayScale", 100)) }
    var exactResolution by remember {
        mutableStateOf(store.getString("displayResolutionExact", "1280x1024") ?: "1280x1024")
    }
    var customResolution by remember {
        mutableStateOf(store.getString("displayResolutionCustom", "1280x1024") ?: "1280x1024")
    }
    var filtering by remember {
        mutableStateOf(store.getString("displayFilteringMode", "nearest") ?: "nearest")
    }
    var orientation by remember {
        mutableStateOf(store.getString("forceOrientation", "auto") ?: "auto")
    }
    var idleTimeout by remember {
        mutableStateOf(store.getString("screenIdleTimeout", "system") ?: "system")
    }
    var touchMode by remember {
        mutableStateOf(store.getString("touchMode", "1") ?: "1")
    }

    var adjustResolution by remember {
        mutableStateOf(store.getBoolean("adjustResolution", false))
    }
    var displayStretch by remember {
        mutableStateOf(store.getBoolean("displayStretch", false))
    }
    var useDisplayCutoutArea by remember {
        mutableStateOf(store.getBoolean("hideCutout", false))
    }
    var scaleTouchpad by remember {
        mutableStateOf(store.getBoolean("scaleTouchpad", true))
    }
    var pointerCapture by remember {
        mutableStateOf(store.getBoolean("pointerCapture", false))
    }
    var tapToMove by remember {
        mutableStateOf(store.getBoolean("tapToMove", false))
    }
    var showAdditionalKbd by remember {
        mutableStateOf(store.getBoolean(PREF_SHOW_ADDITIONAL_KEYS, false))
    }
    var showIme by remember {
        mutableStateOf(store.getBoolean(PREF_SHOW_IME_WITH_EXTERNAL_KEYBOARD, false))
    }
    var preferScancodes by remember {
        mutableStateOf(store.getBoolean("preferScancodes", false))
    }
    var filterWinKey by remember {
        mutableStateOf(store.getBoolean("filterOutWinkey", false))
    }
    var charInput by remember {
        mutableStateOf(store.getBoolean("enforceCharBasedInput", false))
    }
    var clipboard by remember {
        mutableStateOf(store.getBoolean("clipboardEnable", true))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(26.dp),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "X11 Configuration",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Display and embedded X11 settings",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        ConfigSection("Display", Icons.Default.DisplaySettings) {
                            ChoiceSetting(
                                "Resolution mode",
                                resolutionMode,
                                listOf("native", "scaled", "exact", "custom")
                            ) {
                                resolutionMode = it
                                putString("displayResolutionMode", it)
                            }

                            if (resolutionMode == "scaled") {
                                Text(
                                    "Scale: $displayScale%",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = displayScale.toFloat(),
                                    onValueChange = { displayScale = it.toInt() },
                                    onValueChangeFinished = {
                                        putInt("displayScale", displayScale)
                                    },
                                    valueRange = 30f..300f,
                                    steps = 26
                                )
                            }

                            if (resolutionMode == "exact") {
                                OutlinedTextField(
                                    value = exactResolution,
                                    onValueChange = { exactResolution = it },
                                    label = { Text("Exact resolution") },
                                    singleLine = true,
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                putString("displayResolutionExact", exactResolution)
                                            }
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = "Apply")
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (resolutionMode == "custom") {
                                OutlinedTextField(
                                    value = customResolution,
                                    onValueChange = { customResolution = it },
                                    label = { Text("Custom resolution") },
                                    singleLine = true,
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                putString("displayResolutionCustom", customResolution)
                                            }
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = "Apply")
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            ChoiceSetting(
                                "Filtering",
                                filtering,
                                listOf("nearest", "bilinear")
                            ) {
                                filtering = it
                                putString("displayFilteringMode", it)
                            }

                            ChoiceSetting(
                                "Orientation",
                                orientation,
                                listOf(
                                    "auto",
                                    "portrait",
                                    "landscape",
                                    "reverse portrait",
                                    "reverse landscape"
                                )
                            ) {
                                orientation = it
                                putString("forceOrientation", it)
                            }

                            SwitchSetting(
                                "Adjust resolution to orientation",
                                adjustResolution
                            ) {
                                adjustResolution = it
                                putBoolean("adjustResolution", it)
                            }

                            SwitchSetting("Stretch display", displayStretch) {
                                displayStretch = it
                                putBoolean("displayStretch", it)
                            }

                            SwitchSetting("Use display cutout area", useDisplayCutoutArea) {
                                useDisplayCutoutArea = it
                                putBoolean("hideCutout", it)
                            }

                            ChoiceSetting(
                                "Screen idle timeout",
                                idleTimeout,
                                listOf("never", "1", "5", "10", "20", "60", "system"),
                                labels = mapOf(
                                    "never" to "Never",
                                    "1" to "1 minute",
                                    "5" to "5 minutes",
                                    "10" to "10 minutes",
                                    "20" to "20 minutes",
                                    "60" to "1 hour",
                                    "system" to "System"
                                )
                            ) {
                                idleTimeout = it
                                putString("screenIdleTimeout", it)
                            }
                        }
                    }

                    item {
                        ConfigSection("Input", Icons.Default.Mouse) {
                            ChoiceSetting(
                                "Touch mode",
                                touchMode,
                                listOf("1", "2", "3"),
                                labels = mapOf(
                                    "1" to "Trackpad",
                                    "2" to "Simulated touchscreen",
                                    "3" to "Direct touch"
                                )
                            ) {
                                touchMode = it
                                putString("touchMode", it)
                            }

                            SwitchSetting("Scale touchpad", scaleTouchpad) {
                                scaleTouchpad = it
                                putBoolean("scaleTouchpad", it)
                            }

                            SwitchSetting("Pointer capture", pointerCapture) {
                                pointerCapture = it
                                putBoolean("pointerCapture", it)
                            }

                            SwitchSetting("Tap to move", tapToMove) {
                                tapToMove = it
                                putBoolean("tapToMove", it)
                            }
                        }
                    }

                    item {
                        ConfigSection("Keyboard", Icons.Default.Keyboard) {
                            SwitchSetting("Enable additional key bar", showAdditionalKbd) {
                                showAdditionalKbd = it
                                putBoolean(PREF_SHOW_ADDITIONAL_KEYS, it)
                                if (!it) {
                                    putBoolean(PREF_ADDITIONAL_KEYS_VISIBLE, false)
                                }
                            }

                            Text(
                                "Enables the ESC/CTRL/ALT/arrows toolbar. The toolbar itself is shown only from the keyboard icon inside Screen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            SwitchSetting(
                                "Allow Android IME with external keyboard",
                                showIme
                            ) {
                                showIme = it
                                putBoolean(PREF_SHOW_IME_WITH_EXTERNAL_KEYBOARD, it)
                            }

                            Text(
                                "Android IME is separate from the additional key bar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            SwitchSetting("Prefer scancodes", preferScancodes) {
                                preferScancodes = it
                                putBoolean("preferScancodes", it)
                            }

                            SwitchSetting("Filter Windows/Meta key", filterWinKey) {
                                filterWinKey = it
                                putBoolean("filterOutWinkey", it)
                            }

                            SwitchSetting("Force character based input", charInput) {
                                charInput = it
                                putBoolean("enforceCharBasedInput", it)
                            }
                        }
                    }

                    item {
                        ConfigSection("X11", Icons.Default.Memory) {
                            SwitchSetting("Clipboard synchronization", clipboard) {
                                clipboard = it
                                putBoolean("clipboardEnable", it)
                            }

                            Text(
                                "Advanced Lorie settings remain available for helpers, accessibility, actions, pointer transforms and the custom extra-key layout.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedButton(
                                onClick = { openLorieSettings(context) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Advanced X11 settings")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ChoiceSetting(
    label: String,
    value: String,
    choices: List<String>,
    labels: Map<String, String> = emptyMap(),
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(labels[value] ?: value, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(labels[choice] ?: choice) },
                        onClick = {
                            expanded = false
                            onValueChange(choice)
                        }
                    )
                }
            }
        }
    }
}
