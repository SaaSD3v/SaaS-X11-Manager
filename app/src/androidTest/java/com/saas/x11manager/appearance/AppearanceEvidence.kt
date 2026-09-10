package com.saas.x11manager.appearance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.saas.x11manager.ui.theme.ManagerAppearanceSettings
import com.saas.x11manager.ui.theme.readableOn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import java.io.File

internal object AppearanceEvidence {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    val directory get() = File(context.getExternalFilesDir(null), "appearance-audit").apply { mkdirs() }

    fun screenshot(name: String, expectedPixel: Triple<Int, Int, Int>? = null) {
        // Compose can be idle while SurfaceFlinger still presents the previous
        // Activity snapshot during a configuration/splash transition.
        runCatching { instrumentation.uiAutomation.waitForIdle(100, 3000) }
        Thread.sleep(300)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (true) {
            // Use Android's display capture directly. UiAutomation's bitmap
            // capture can lose app layers on the API 34 software GPU emulator.
            val bitmap = instrumentation.uiAutomation.executeShellCommand("screencap -p").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                    requireNotNull(BitmapFactory.decodeStream(it)) { "Android screenshot failed" }
                }
            }
            val actual = expectedPixel?.let { (x, y, _) -> bitmap.getPixel(x, y) }
            val ready = expectedPixel == null || actual == expectedPixel.third
            if (ready || SystemClock.elapsedRealtime() >= deadline) {
                try {
                    File(directory, "api-${Build.VERSION.SDK_INT}-$name.png").outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    if (expectedPixel != null) File(directory, "api-${Build.VERSION.SDK_INT}-$name-pixel.json")
                        .writeText(JSONObject().put("x", expectedPixel.first).put("y", expectedPixel.second)
                            .put("expected", "%08x".format(expectedPixel.third))
                            .put("displayed", "%08x".format(actual)).toString())
                    check(ready) { "$name: Android still displays ${actual?.toUInt()?.toString(16)}, expected ${expectedPixel?.third?.toUInt()?.toString(16)}" }
                    return
                } finally { bitmap.recycle() }
            }
            bitmap.recycle()
            Thread.sleep(100)
        }
    }

    fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }

    fun assertSettingsWindow(light: Boolean) {
        onView(isRoot()).inRoot(isDialog()).check { view, error ->
            if (error != null) throw error
            val root = requireNotNull(view).rootView
            val flags = (root.layoutParams as WindowManager.LayoutParams).flags
            assertEquals("Full-size settings must not dim the system bars", 0,
                flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val lightStatus: Boolean
            val lightNavigation: Boolean
            if (Build.VERSION.SDK_INT >= 30) {
                val appearance = requireNotNull(root.windowInsetsController).systemBarsAppearance
                lightStatus = appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0
                lightNavigation = appearance and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0
            } else {
                @Suppress("DEPRECATION")
                val appearance = root.systemUiVisibility
                lightStatus = appearance and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0
                lightNavigation = appearance and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0
            }
            assertEquals("Settings status icons must follow the app theme", light, lightStatus)
            assertEquals("Settings navigation icons must follow the app theme", light, lightNavigation)
        }
    }

    fun settingsKeyboardVisible(): Boolean {
        var visible = false
        onView(isRoot()).inRoot(isDialog()).check { view, error ->
            if (error != null) throw error
            visible = ViewCompat.getRootWindowInsets(requireNotNull(view))
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    fun contrast(foreground: Color, background: Color): Double = ColorUtils.calculateContrast(
        foreground.compositeOver(background).toArgb(), background.toArgb()
    )

    fun record(name: String, settings: ManagerAppearanceSettings, colors: ColorScheme): List<String> {
        val ratios = JSONObject()
        val selectedBackground = colors.primary.copy(alpha = 0.12f).compositeOver(colors.surfaceContainer)
        val startBackground = colors.primaryContainer.copy(alpha = 0.4f).compositeOver(colors.surfaceContainerHigh)
        val pairs = mapOf(
            "body" to (colors.onSurface to colors.surface),
            "secondary_text" to (colors.onSurfaceVariant to colors.surfaceContainer),
            "primary_button" to (colors.onPrimary to colors.primary),
            "secondary_button" to (colors.onSecondary to colors.secondary),
            "tertiary_button" to (colors.onTertiary to colors.tertiary),
            "primary_container" to (colors.onPrimaryContainer to colors.primaryContainer.compositeOver(colors.surface)),
            "secondary_container" to (colors.onSecondaryContainer to colors.secondaryContainer.compositeOver(colors.surface)),
            "tertiary_container" to (colors.onTertiaryContainer to colors.tertiaryContainer.compositeOver(colors.surface)),
            "accent_text" to (colors.primary to colors.surfaceContainer),
            "selected_tab" to (colors.primary.readableOn(selectedBackground) to selectedBackground),
            "inactive_tab" to (colors.onSurfaceVariant to colors.surfaceContainer),
            "container_start" to (colors.primary.readableOn(startBackground) to startBackground),
            "container_status" to (colors.onSurfaceVariant to colors.onSurfaceVariant.copy(alpha = 0.1f).compositeOver(colors.surfaceContainer)),
            "log_warning" to (colors.tertiary.copy(alpha = 0.9f).readableOn(colors.surfaceContainerHighest) to colors.surfaceContainerHighest),
            "log_error" to (colors.error.copy(alpha = 0.9f).readableOn(colors.surfaceContainerHighest) to colors.surfaceContainerHighest)
        )
        pairs.forEach { (label, pair) -> ratios.put(label, contrast(pair.first, pair.second)) }
        val data = JSONObject().put("case", name).put("api", Build.VERSION.SDK_INT)
            .put("mode", settings.themeMode.name).put("dynamic", settings.dynamicColor)
            .put("amoled", settings.amoledMode).put("palette", settings.palette.name)
            .put("primary", "%08x".format(colors.primary.toArgb()))
            .put("background", "%08x".format(colors.background.toArgb()))
            .put("surface", "%08x".format(colors.surface.toArgb()))
            .put("ratios", ratios)
        File(directory, "theme-matrix.jsonl").appendText(data.toString() + "\n")
        return pairs.mapNotNull { (label, pair) ->
            val ratio = contrast(pair.first, pair.second)
            if (ratio < 4.5) "$name: $label contrast is $ratio, expected at least 4.5:1" else null
        }
    }
}
