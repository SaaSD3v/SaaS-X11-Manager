package com.saas.x11manager.appearance

import android.Manifest
import android.app.WallpaperManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.Window
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.WindowInsetsControllerCompat
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
        compose.onNodeWithTag("main-tab-Config").performClick()
        choose("Reset appearance defaults")
        assertWindowChrome()
    }

    private fun assertWindowChrome() {
        compose.runOnIdle {
            assertNull("A native action bar must not cover the Compose header", compose.activity.actionBar)
            assertFalse(compose.activity.window.hasFeature(Window.FEATURE_ACTION_BAR))
            val dark = when (current().themeMode) {
                ManagerThemeMode.DARK -> true
                ManagerThemeMode.LIGHT -> false
                ManagerThemeMode.SYSTEM -> context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            val bars = WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
            assertEquals("Status bar icons must follow the app theme", !dark, bars.isAppearanceLightStatusBars)
            assertEquals("Navigation bar icons must follow the app theme", !dark, bars.isAppearanceLightNavigationBars)
        }
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
        assertWindowChrome()
        AppearanceEvidence.screenshot(name)
    }

    @Test fun themeDynamicAmoledPaletteAndResetControlsPersistTheirActualSelections() {
        choose("Light")
        compose.onNodeWithText("Light").assertIsSelected()
        assertEquals(ManagerThemeMode.LIGHT, current().themeMode)
        capture("main-light-default", "Manager configuration")

        reveal("Dynamic Color")
        if (Build.VERSION.SDK_INT >= 31) {
            choose("Dynamic Color")
            assertFalse(current().dynamicColor)
            compose.onNodeWithText("Dynamic Color").assertIsOff()
            capture("main-light-static")
            choose("Dynamic Color")
            assertTrue(current().dynamicColor)
            compose.onNodeWithText("Dynamic Color").assertIsOn()
            capture("main-light-dynamic")
            choose("Dynamic Color")
        } else {
            compose.onNodeWithText("Dynamic Color").assertIsNotEnabled().assertIsOff()
            capture("main-dynamic-unavailable")
        }

        ThemePalette.entries.forEach { palette ->
            choose(palette.displayName)
            assertEquals(palette, current().palette)
            compose.onNodeWithText(palette.displayName).assertIsSelected()
        }
        capture("main-palette-selection", "Static palette")

        choose("Dark")
        choose("AMOLED black")
        assertTrue(current().amoledMode)
        compose.onNodeWithText("AMOLED black").assertIsOn()
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

    @Test fun mainScreensRenderWithStaticDynamicAndAmoledThemes() {
        fun visitScreens(name: String) {
            for (tab in listOf("Home", "Display", "Requirements", "Config")) {
                compose.onNodeWithTag("main-tab-$tab").performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("main-tab-$tab").assertIsDisplayed()
                assertWindowChrome()
                AppearanceEvidence.screenshot("screen-$name-${tab.lowercase()}")
            }
        }
        choose("Light")
        if (Build.VERSION.SDK_INT >= 31) choose("Dynamic Color")
        visitScreens("light-static")
        choose("Dark")
        visitScreens("dark-static")
        choose("AMOLED black")
        visitScreens("dark-amoled")
        if (Build.VERSION.SDK_INT >= 31) {
            choose("Dynamic Color")
            visitScreens("dark-dynamic-amoled")
            choose("Light")
            visitScreens("light-dynamic")
        }
    }

    @Test fun wallpaperChangesUpdateTheRealActivityAndRestoreTheStaticPalette() {
        // API 31's AOSP SystemUI has no Monet overlay generator (getOverlay is
        // a stub). API 32 and 34 exercise actual wallpaper extraction; API 31
        // still tests both color sources against its available Android palette.
        org.junit.Assume.assumeTrue("Wallpaper extraction requires the emulator's Monet implementation", Build.VERSION.SDK_INT >= 32)
        choose("Light")
        choose("Dynamic Color")
        choose("Ocean")
        fun backgroundPixel(): Int {
            reveal("Manager configuration")
            compose.waitForIdle()
            // This point is inside the list's empty horizontal padding, over the
            // production theme background rather than any text or card.
            return compose.onNodeWithTag("appearance-settings").captureToImage().toPixelMap()[1, 1].toArgb()
        }
        val staticBackground = backgroundPixel()
        choose("Dynamic Color")
        val automation = AppearanceEvidence.instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.SET_WALLPAPER)
        try {
            val manager = WallpaperManager.getInstance(context)
            for ((name, color) in listOf("green" to android.graphics.Color.rgb(20, 130, 60),
                "purple" to android.graphics.Color.rgb(130, 30, 190))) {
                val previousPrimary = dynamicLightColorScheme(context).primary
                val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                try { manager.setBitmap(bitmap) } finally { bitmap.recycle() }
                compose.waitUntil(20_000) { dynamicLightColorScheme(context).primary != previousPrimary }
                // MainActivity owns setContent in onCreate, so this also exercises
                // Android's real recreation after a wallpaper overlay change.
                compose.waitForIdle()
                val expected = dynamicLightColorScheme(context)
                compose.waitUntil(10_000) {
                    runCatching { backgroundPixel() == expected.background.toArgb() }.getOrDefault(false)
                }
                AppearanceEvidence.screenshot("wallpaper-$name-dynamic")
                choose("Dynamic Color")
                assertEquals(staticBackground, backgroundPixel())
                choose("Dynamic Color")
            }
        } finally { automation.dropShellPermissionIdentity() }
    }
}
