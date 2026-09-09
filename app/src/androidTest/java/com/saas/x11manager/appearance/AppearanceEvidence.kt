package com.saas.x11manager.appearance

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.saas.x11manager.ui.theme.ManagerAppearanceSettings
import org.json.JSONObject
import java.io.File

internal object AppearanceEvidence {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    val directory get() = File(context.getExternalFilesDir(null), "appearance-audit").apply { mkdirs() }

    fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Android screenshot failed" }
        File(directory, "api-${Build.VERSION.SDK_INT}-$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }

    fun contrast(foreground: Color, background: Color): Double = ColorUtils.calculateContrast(
        foreground.compositeOver(background).toArgb(), background.toArgb()
    )

    fun record(name: String, settings: ManagerAppearanceSettings, colors: ColorScheme) {
        val ratios = JSONObject()
        val pairs = mapOf(
            "body" to (colors.onSurface to colors.surface),
            "secondary_text" to (colors.onSurfaceVariant to colors.surfaceContainer),
            "primary_button" to (colors.onPrimary to colors.primary),
            "secondary_button" to (colors.onSecondary to colors.secondary),
            "tertiary_button" to (colors.onTertiary to colors.tertiary),
            "accent_text" to (colors.primary to colors.surfaceContainer),
            "selected_tab" to (colors.primary to colors.primary.copy(alpha = 0.12f).compositeOver(colors.surfaceContainer)),
            "inactive_tab" to (colors.onSurfaceVariant.copy(alpha = 0.6f) to colors.surfaceContainer),
            "log_warning" to (colors.tertiary.copy(alpha = 0.9f) to colors.surfaceContainerHighest),
            "log_error" to (colors.error.copy(alpha = 0.9f) to colors.surfaceContainerHighest)
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
    }
}
