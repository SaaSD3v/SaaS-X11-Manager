package com.saas.x11manager.ui.screen

import android.os.Build
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saas.x11manager.ui.component.SettingsSection
import com.saas.x11manager.ui.component.SettingsToggleRow
import com.saas.x11manager.ui.theme.ManagerAppearanceSettings
import com.saas.x11manager.ui.theme.ManagerThemeMode
import com.saas.x11manager.ui.theme.ThemePalette

@Composable
fun ConfigScreen(
    settings: ManagerAppearanceSettings,
    onSettingsChange: (ManagerAppearanceSettings) -> Unit,
    onReset: () -> Unit
) {
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("appearance-settings")
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Manager configuration",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Appearance settings for the Manager UI. X11 display and input settings remain under Display.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            SettingsSection(
                title = "Theme mode",
                subtitle = "Choose how the Manager follows light and dark appearance",
                icon = { Icon(Icons.Default.DarkMode, contentDescription = null) }
            ) {
                ManagerThemeMode.entries.forEach { mode ->
                    ChoiceRow(
                        title = mode.displayName,
                        subtitle = when (mode) {
                            ManagerThemeMode.SYSTEM -> "Follow Android system appearance"
                            ManagerThemeMode.LIGHT -> "Always use the light color scheme"
                            ManagerThemeMode.DARK -> "Always use the dark color scheme"
                        },
                        selected = settings.themeMode == mode,
                        onClick = { onSettingsChange(settings.copy(themeMode = mode)) }
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = "Color source",
                subtitle = "Control Material You and OLED behavior",
                icon = { Icon(Icons.Default.Wallpaper, contentDescription = null) }
            ) {
                SettingsToggleRow(
                    title = "Dynamic Color",
                    subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "Use Android wallpaper-derived Material You colors"
                    } else {
                        "Requires Android 12 or newer; static palette is used on this device"
                    },
                    checked = settings.dynamicColor && dynamicAvailable,
                    enabled = dynamicAvailable,
                    onCheckedChange = { onSettingsChange(settings.copy(dynamicColor = it)) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                SettingsToggleRow(
                    title = "AMOLED black",
                    subtitle = "Use pure black surfaces while the effective theme is dark",
                    checked = settings.amoledMode,
                    onCheckedChange = { onSettingsChange(settings.copy(amoledMode = it)) }
                )
            }
        }

        item {
            SettingsSection(
                title = "Static palette",
                subtitle = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "Saved for use when Dynamic Color is disabled"
                } else {
                    "Choose the Manager accent palette"
                },
                icon = { Icon(Icons.Default.Palette, contentDescription = null) }
            ) {
                ThemePalette.entries.forEach { palette ->
                    PaletteRow(
                        palette = palette,
                        selected = settings.palette == palette,
                        onClick = { onSettingsChange(settings.copy(palette = palette)) }
                    )
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reset appearance defaults")
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PaletteRow(
    palette: ThemePalette,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PaletteDot(palette.primaryDark)
            PaletteDot(palette.secondaryDark)
            PaletteDot(palette.tertiaryDark)
        }
        Spacer(Modifier.width(12.dp))
        Text(palette.displayName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun PaletteDot(color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color) {
        Spacer(Modifier.size(12.dp))
    }
}
