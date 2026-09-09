package com.saas.x11manager.appearance

import android.Manifest
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saas.x11manager.ui.component.ContainerCard
import com.saas.x11manager.ui.component.ContainerCardActions
import com.saas.x11manager.ui.component.TerminalDialog
import com.saas.x11manager.ui.theme.*
import com.saas.x11manager.util.ContainerInfo
import com.saas.x11manager.util.ContainerStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Android-rendered production components, not image mockups or host-side raster approximations. */
@RunWith(AndroidJUnit4::class)
class AppearanceRenderingTest {
    @get:Rule val compose = createComposeRule()
    private var settings by mutableStateOf(ManagerAppearanceSettings())
    private var showLogs by mutableStateOf(false)
    private lateinit var colors: ColorScheme

    private fun render() {
        compose.setContent {
            X11ManagerTheme(
                darkTheme = settings.themeMode == ManagerThemeMode.DARK,
                dynamicColor = settings.dynamicColor,
                amoledMode = settings.amoledMode,
                themePalette = settings.palette
            ) {
                val scheme = MaterialTheme.colorScheme
                SideEffect { colors = scheme }
                Surface(Modifier.fillMaxSize(), color = scheme.background) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("X11 Manager", style = MaterialTheme.typography.headlineSmall)
                        Text("${settings.themeMode.displayName} · ${settings.palette.displayName}", color = scheme.onSurfaceVariant)
                        ContainerCard(
                            container = ContainerInfo("alpine", "/containers/alpine", "/containers/alpine/container.conf",
                                hostname = "alpine", status = ContainerStatus.STOPPED),
                            actions = ContainerCardActions(startLabel = "Start"),
                            isExpanded = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {}) { Text("Confirm") }
                            OutlinedButton(onClick = {}) { Text("View logs") }
                            Switch(checked = true, onCheckedChange = {})
                        }
                        LinearProgressIndicator(progress = 0.6f, modifier = Modifier.fillMaxWidth())
                        Text("Operation completed", color = scheme.tertiary)
                        Text("Check the operation result", color = scheme.error)
                    }
                }
                if (showLogs) TerminalDialog(
                    title = "Installing IceWM — alpine",
                    logs = listOf(
                        Log.INFO to "[INSTALL] Installing IceWM",
                        Log.INFO to "[CONTAINER] ✓ Container ready",
                        Log.INFO to "[INSTALL] Refreshing package index",
                        Log.WARN to "[INSTALL] ! Repository is temporarily unavailable",
                        Log.ERROR to "[INSTALL] ✗ Package download failed",
                        Log.INFO to "[CONTAINER] ✓ Original stopped state restored"
                    ),
                    onDismiss = { showLogs = false }, onMinimize = { showLogs = false },
                    onClear = {}, isBlocking = true
                )
            }
        }
    }

    private fun show(value: ManagerAppearanceSettings, logs: Boolean = false) {
        compose.runOnIdle { settings = value; showLogs = logs }
        compose.waitForIdle()
    }

    @Test fun auditEveryPaletteDynamicFallbackAndAmoledCombination() {
        render()
        for (mode in listOf(ManagerThemeMode.LIGHT, ManagerThemeMode.DARK)) {
            for (dynamic in listOf(false, true)) {
                for (amoled in listOf(false, true)) {
                    var dynamicPrimary: Int? = null
                    for (palette in ThemePalette.entries) {
                        val value = ManagerAppearanceSettings(mode, dynamic, amoled, palette)
                        show(value)
                        val name = "${mode.name.lowercase()}-${if (dynamic) "dynamic" else "static"}-${if (amoled) "amoled" else "normal"}-${palette.name.lowercase()}"
                        AppearanceEvidence.record(name, value, colors)
                        if (mode == ManagerThemeMode.DARK && amoled) {
                            assertEquals(Color.Black.toArgb(), colors.background.toArgb())
                            assertEquals(Color.Black.toArgb(), colors.surfaceContainer.toArgb())
                        }
                        if (dynamic && Build.VERSION.SDK_INT >= 31) {
                            if (dynamicPrimary == null) dynamicPrimary = colors.primary.toArgb()
                            else assertEquals(dynamicPrimary, colors.primary.toArgb())
                        }
                        if ((!dynamic && !amoled && mode == ManagerThemeMode.LIGHT) ||
                            palette == ThemePalette.CATPPUCCIN) {
                            AppearanceEvidence.screenshot("components-$name")
                        }
                    }
                }
            }
        }
        // Android 8 must use the chosen static scheme regardless of the saved dynamic preference.
        if (Build.VERSION.SDK_INT < 31) {
            show(ManagerAppearanceSettings(ManagerThemeMode.LIGHT, false, false, ThemePalette.OCEAN))
            val staticPrimary = colors.primary
            show(settings.copy(dynamicColor = true))
            assertEquals(staticPrimary, colors.primary)
        }
    }

    @Test fun terminalControlsAndWarningsRemainVisibleAcrossThemes() {
        render()
        val cases = listOf(
            ManagerAppearanceSettings(ManagerThemeMode.LIGHT, false),
            ManagerAppearanceSettings(ManagerThemeMode.DARK, false),
            ManagerAppearanceSettings(ManagerThemeMode.DARK, false, true),
            ManagerAppearanceSettings(ManagerThemeMode.LIGHT, true),
            ManagerAppearanceSettings(ManagerThemeMode.DARK, true, true)
        )
        cases.forEachIndexed { index, value ->
            show(value, logs = true)
            val minimize = compose.onNodeWithContentDescription("Minimize logs").assertIsEnabled()
            val close = compose.onNodeWithContentDescription("Close").assertIsNotEnabled()
            assertTrue(minimize.fetchSemanticsNode().boundsInRoot.right <= close.fetchSemanticsNode().boundsInRoot.left + 1f)
            compose.onNodeWithText("[INSTALL] ! Repository is temporarily unavailable").assertIsDisplayed()
            compose.onNodeWithText("[INSTALL] ✗ Package download failed").assertIsDisplayed()
            AppearanceEvidence.screenshot("terminal-$index-${value.themeMode.name.lowercase()}-${if (value.amoledMode) "amoled" else "normal"}")
        }
    }

    @Test fun dynamicColorsFollowAndroidWallpaperAndSwitchBackToTheSavedPalette() {
        if (Build.VERSION.SDK_INT < 31) return
        render()
        val automation = AppearanceEvidence.instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.SET_WALLPAPER)
        try {
            val manager = WallpaperManager.getInstance(AppearanceEvidence.context)
            for ((name, color) in listOf("green" to android.graphics.Color.rgb(20, 130, 60),
                "purple" to android.graphics.Color.rgb(130, 30, 190))) {
                val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                manager.setBitmap(bitmap)
                bitmap.recycle()
                // SystemUI extracts wallpaper colors asynchronously; allow its overlay to settle.
                Thread.sleep(3000)
                show(ManagerAppearanceSettings(ManagerThemeMode.LIGHT, false, false, ThemePalette.OCEAN))
                val savedStatic = colors.primary
                show(settings.copy(dynamicColor = true))
                val expected = dynamicLightColorScheme(AppearanceEvidence.context).primary
                assertEquals(expected, colors.primary)
                AppearanceEvidence.record("wallpaper-$name", settings, colors)
                AppearanceEvidence.screenshot("wallpaper-$name-dynamic")
                show(settings.copy(dynamicColor = false))
                assertEquals(savedStatic, colors.primary)
            }
        } finally { automation.dropShellPermissionIdentity() }
    }
}
