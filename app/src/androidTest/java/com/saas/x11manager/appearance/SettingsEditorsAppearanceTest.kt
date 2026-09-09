package com.saas.x11manager.appearance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saas.x11manager.ui.screen.FixesScreen
import com.saas.x11manager.ui.screen.GeneralSettingsDialog
import com.saas.x11manager.ui.theme.*
import com.saas.x11manager.util.VncSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production settings editors with an isolated preference-only container name. */
@RunWith(AndroidJUnit4::class)
class SettingsEditorsAppearanceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = AppearanceEvidence.context
    private val container = "appearance-preview"
    private var settings by mutableStateOf(ManagerAppearanceSettings())
    private var visible by mutableStateOf(false)
    private var fixes by mutableStateOf(false)
    private var largeText by mutableStateOf(false)
    private var expectedBackground = 0

    @Test fun generalAdvancedAndCompatibilitySettingsFollowTheManagerTheme() {
        compose.setContent {
            X11ManagerTheme(
                darkTheme = settings.themeMode == ManagerThemeMode.DARK,
                dynamicColor = settings.dynamicColor,
                amoledMode = settings.amoledMode,
                themePalette = settings.palette
            ) {
                val background = MaterialTheme.colorScheme.background
                SideEffect { expectedBackground = background.toArgb() }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 1.3f else 1f)) {
                    Surface(Modifier.fillMaxSize(), color = background) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            if (fixes) FixesScreen(containerName = container, onBack = { fixes = false })
                        }
                    }
                    if (visible) GeneralSettingsDialog(containerName = container, onDismiss = { visible = false })
                }
            }
        }
        val cases = listOf(
            ManagerAppearanceSettings(ManagerThemeMode.LIGHT, false, false, ThemePalette.OCEAN),
            ManagerAppearanceSettings(ManagerThemeMode.DARK, false, false, ThemePalette.OCEAN),
            ManagerAppearanceSettings(ManagerThemeMode.DARK, false, true, ThemePalette.OCEAN),
            ManagerAppearanceSettings(ManagerThemeMode.LIGHT, true),
            ManagerAppearanceSettings(ManagerThemeMode.DARK, true, true)
        )
        for ((index, value) in cases.withIndex()) {
            compose.runOnIdle { settings = value; visible = true; largeText = index == 2 }
            compose.onNodeWithText("General settings").assertIsDisplayed()
            assertEditorBackground()
            compose.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
            AppearanceEvidence.screenshot("editor-$index-general")
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Advanced TigerVNC settings"))
            compose.onNodeWithText("Advanced TigerVNC settings").performClick()
            compose.onNodeWithText("TigerVNC settings").assertIsDisplayed()
            assertEditorBackground()
            AppearanceEvidence.screenshot("editor-$index-tigervnc")
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Use IPv4"))
            compose.onNodeWithText("Use IPv4").assertIsOn().performClick().assertIsOff()
            AppearanceEvidence.screenshot("editor-$index-tigervnc-network")
            compose.onNodeWithText("Save").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertTrue("Cancel must preserve the saved VNC settings", VncSettings.getLaunchSettings(context, container).useIPv4)
            compose.onNodeWithContentDescription("Close").performClick()
            compose.runOnIdle { fixes = true }
            compose.onNodeWithText("Audio configuration").assertIsDisplayed()
            AppearanceEvidence.screenshot("editor-$index-fixes")
            compose.runOnIdle { fixes = false }
        }
        // Verify the shared footer still performs the editor's existing save action.
        compose.runOnIdle { visible = true; largeText = false }
        compose.onNodeWithText("VNC port").performTextReplacement("5904")
        compose.onNodeWithText("Save").performClick()
        assertEquals(5904, VncSettings.getPort(context, container))
        VncSettings.resetGeneral(context, container)
    }

    private fun assertEditorBackground() {
        compose.waitForIdle()
        val image = compose.onNodeWithTag("settings-dialog").captureToImage().toPixelMap()
        assertEquals("Settings must not add a tonal tint over the selected background", expectedBackground,
            image[1, image.height / 2].toArgb())
    }
}
