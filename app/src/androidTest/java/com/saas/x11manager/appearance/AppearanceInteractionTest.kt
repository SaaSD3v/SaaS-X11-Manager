package com.saas.x11manager.appearance

import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saas.x11manager.MainActivity
import com.saas.x11manager.ui.theme.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Clicks the real production screen, including its persistence and activity recreation. */
@RunWith(AndroidJUnit4::class)
class AppearanceInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = AppearanceEvidence.context
    private fun current() = ManagerAppearancePreferences.load(context)

    @Before fun openConfiguration() {
        compose.waitForIdle()
        compose.onAllNodesWithText("Config").onLast().performClick()
        choose("Reset appearance defaults")
    }

    private fun choose(label: String) {
        reveal(label)
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    private fun reveal(label: String) {
        compose.onNodeWithTag("appearance-settings").performScrollToNode(hasText(label))
    }

    private fun capture(name: String, anchor: String = "Color source") {
        reveal(anchor)
        compose.waitForIdle()
        AppearanceEvidence.screenshot(name)
    }

    @Test fun themeDynamicAmoledPaletteAndResetControlsPersistTheirActualSelections() {
        choose("Light")
        assertEquals(ManagerThemeMode.LIGHT, current().themeMode)
        capture("main-light-default", "Manager configuration")

        reveal("Dynamic Color")
        if (Build.VERSION.SDK_INT >= 31) {
            choose("Dynamic Color")
            assertFalse(current().dynamicColor)
            capture("main-light-static")
            choose("Dynamic Color")
            assertTrue(current().dynamicColor)
            capture("main-light-dynamic")
            choose("Dynamic Color")
        } else {
            compose.onNodeWithText("Dynamic Color").assertIsNotEnabled()
            capture("main-dynamic-unavailable")
        }

        ThemePalette.entries.forEach { palette ->
            choose(palette.displayName)
            assertEquals(palette, current().palette)
        }
        capture("main-palette-selection", "Static palette")

        choose("Dark")
        choose("AMOLED black")
        assertTrue(current().amoledMode)
        capture("main-dark-amoled")
        if (Build.VERSION.SDK_INT >= 31) {
            choose("Dynamic Color")
            capture("main-dark-dynamic-amoled")
        }
        val saved = current()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(saved, current())
        compose.onAllNodesWithText("Config").onLast().assertIsDisplayed()
        capture("main-restored-appearance")

        choose("AMOLED black")
        assertFalse(current().amoledMode)
        capture("main-dark-without-amoled")
        choose("System")
        assertEquals(ManagerThemeMode.SYSTEM, current().themeMode)
        choose("Reset appearance defaults")
        assertEquals(ManagerAppearanceSettings(), current())
        capture("main-reset-defaults", "Manager configuration")
    }

    @Test fun systemThemeAndLargeTextKeepTheControlsAndNavigationReachable() {
        try {
            choose("System")
            AppearanceEvidence.shell("cmd uimode night yes")
            compose.waitUntil(15_000) {
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            compose.waitForIdle()
            capture("main-system-dark")
            AppearanceEvidence.shell("cmd uimode night no")
            compose.waitUntil(15_000) {
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO
            }
            compose.waitForIdle()
            capture("main-system-light")

            AppearanceEvidence.shell("settings put system font_scale 1.3")
            compose.waitUntil(15_000) { context.resources.configuration.fontScale > 1.2f }
            compose.waitForIdle()
            capture("main-large-text", "Manager configuration")
            choose("Dark")
            choose("AMOLED black")
            capture("main-large-text-amoled")
            reveal("Reset appearance defaults")
            compose.onNodeWithText("Reset appearance defaults").assertIsDisplayed()
            compose.onAllNodesWithText("Config").onLast().assertIsDisplayed()
        } finally {
            AppearanceEvidence.shell("settings put system font_scale 1.0")
            AppearanceEvidence.shell("cmd uimode night no")
        }
    }
}
