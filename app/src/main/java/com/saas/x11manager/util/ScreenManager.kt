package com.saas.x11manager.util

import android.content.Context
import com.termux.x11.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Project-owned configuration bridge for the embedded X11 display.
 *
 * This object owns persisted display/input preferences and only forwards the
 * subset that the embedded Lorie renderer actually consumes. X11 process and
 * container lifecycle stay in [X11SessionManager].
 */
object ScreenManager {
    private const val PREFS_NAME = "screen_manager"
    private const val IME_TOOLBAR_KEY = "ime_toolbar_enabled_v2"
    private const val LEGACY_IME_TOOLBAR_KEY = "show_additional_keyboard"

    val supportedExactResolutions = listOf(
        "800x600",
        "1024x768",
        "1280x720",
        "1280x1024",
        "1366x768",
        "1680x1050",
        "1920x1080",
        "1920x1200",
        "2560x1600",
        "3840x2160"
    )

    fun load(context: Context): ScreenConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ScreenConfig(
            resolutionMode = ScreenResolutionMode.fromWireValue(
                prefs.getString("resolution_mode", null)
            ),
            scalePercent = prefs.getInt("scale_percent", 100),
            exactResolution = prefs.getString("exact_resolution", null)
                ?.takeIf { it in supportedExactResolutions }
                ?: "1280x1024",
            customResolution = prefs.getString("custom_resolution", null)
                ?: "1280x1024",
            filtering = ScreenFiltering.fromWireValue(
                prefs.getString("filtering", null)
            ),
            adjustResolution = prefs.getBoolean("adjust_resolution", false),
            stretch = prefs.getBoolean("stretch", false),
            clipboard = prefs.getBoolean("clipboard", true),
            touchMode = ScreenTouchMode.fromWireValue(
                prefs.getString("touch_mode", null)
            ),
            keepScreenAwake = prefs.getBoolean("keep_screen_awake", false),
            // v2 intentionally ignores the old key so upgraded installations
            // also start with the redesigned IME toolbar disabled.
            showAdditionalKeyboard = prefs.getBoolean(IME_TOOLBAR_KEY, false)
        ).normalized()
    }

    fun save(context: Context, config: ScreenConfig) {
        val normalized = config.normalized()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("resolution_mode", normalized.resolutionMode.wireValue)
            .putInt("scale_percent", normalized.scalePercent)
            .putString("exact_resolution", normalized.exactResolution)
            .putString("custom_resolution", normalized.customResolution)
            .putString("filtering", normalized.filtering.wireValue)
            .putBoolean("adjust_resolution", normalized.adjustResolution)
            .putBoolean("stretch", normalized.stretch)
            .putBoolean("clipboard", normalized.clipboard)
            .putString("touch_mode", normalized.touchMode.wireValue)
            .putBoolean("keep_screen_awake", normalized.keepScreenAwake)
            .putBoolean(IME_TOOLBAR_KEY, normalized.showAdditionalKeyboard)
            .remove(LEGACY_IME_TOOLBAR_KEY)
            // Standalone-Lorie Activity settings are intentionally retired.
            .remove("fullscreen")
            .remove("orientation")
            .remove("hide_cutout")
            .apply()
    }

    /**
     * Only keys read by LorieView itself belong in this payload. Touch mode,
     * keep-awake, fullscreen and our IME toolbar are implemented by the host UI.
     */
    internal fun buildPreferencePayload(config: ScreenConfig): Map<String, String> {
        val normalized = config.normalized()
        return linkedMapOf(
            "displayResolutionMode" to normalized.resolutionMode.wireValue,
            "displayScale" to normalized.scalePercent.toString(),
            "displayResolutionExact" to normalized.exactResolution,
            "displayResolutionCustom" to normalized.customResolution,
            "displayFilteringMode" to normalized.filtering.wireValue,
            "adjustResolution" to normalized.adjustResolution.toString(),
            "displayStretch" to normalized.stretch.toString(),
            "clipboardEnable" to normalized.clipboard.toString()
        )
    }

    /**
     * Lorie is embedded in this APK, so its renderer preferences live in the
     * host application's own SharedPreferences. Write them directly instead of
     * routing an in-process settings change through the standalone Termux:X11
     * exported preference receiver.
     *
     * SharedPreferences.Editor.apply() updates the in-process memory view before
     * returning, so EmbeddedX11View.reloadPreferences() can observe the values
     * immediately while disk persistence continues asynchronously.
     */
    private fun applyEmbeddedLoriePreferences(context: Context, config: ScreenConfig) {
        val normalized = config.normalized()
        Prefs(context.applicationContext)
            .get()
            .edit()
            .putString("displayResolutionMode", normalized.resolutionMode.wireValue)
            .putInt("displayScale", normalized.scalePercent)
            .putString("displayResolutionExact", normalized.exactResolution)
            .putString("displayResolutionCustom", normalized.customResolution)
            .putString("displayFilteringMode", normalized.filtering.wireValue)
            .putBoolean("adjustResolution", normalized.adjustResolution)
            .putBoolean("displayStretch", normalized.stretch)
            .putBoolean("clipboardEnable", normalized.clipboard)
            .apply()
    }

    fun apply(context: Context, config: ScreenConfig) {
        val normalized = config.normalized()
        save(context, normalized)
        applyEmbeddedLoriePreferences(context, normalized)
    }

    /** Starts only the server; rendering remains hosted by the Screen tab. */
    suspend fun startServer(
        context: Context,
        xkbContainerName: String?,
        config: ScreenConfig,
        logger: ContainerLogger? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            apply(context, config)
            X11SessionManager.startIntegratedServer(
                containerName = xkbContainerName,
                logger = logger
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun stop(logger: ContainerLogger? = null): Boolean =
        X11SessionManager.stopIntegratedServer(logger)
}

data class ScreenConfig(
    val resolutionMode: ScreenResolutionMode = ScreenResolutionMode.Native,
    val scalePercent: Int = 100,
    val exactResolution: String = "1280x1024",
    val customResolution: String = "1280x1024",
    val filtering: ScreenFiltering = ScreenFiltering.Nearest,
    val adjustResolution: Boolean = false,
    val stretch: Boolean = false,
    val clipboard: Boolean = true,
    val touchMode: ScreenTouchMode = ScreenTouchMode.Trackpad,
    val keepScreenAwake: Boolean = false,
    val showAdditionalKeyboard: Boolean = false
) {
    fun normalized(): ScreenConfig = copy(
        scalePercent = scalePercent.coerceIn(30, 300),
        exactResolution = exactResolution.takeIf { it in ScreenManager.supportedExactResolutions }
            ?: "1280x1024",
        customResolution = normalizeResolution(customResolution)
    )

    private fun normalizeResolution(value: String): String {
        val match = Regex("^(\\d{2,5})x(\\d{2,5})$").matchEntire(value.trim())
            ?: return "1280x1024"
        val width = match.groupValues[1].toIntOrNull() ?: return "1280x1024"
        val height = match.groupValues[2].toIntOrNull() ?: return "1280x1024"
        return if (width > 0 && height > 0) "${width}x${height}" else "1280x1024"
    }
}

enum class ScreenResolutionMode(val label: String, val wireValue: String) {
    Native("Native", "native"),
    Scaled("Scaled", "scaled"),
    Exact("Exact", "exact"),
    Custom("Custom", "custom");

    companion object {
        fun fromWireValue(value: String?): ScreenResolutionMode =
            entries.firstOrNull { it.wireValue == value } ?: Native
    }
}

enum class ScreenFiltering(val label: String, val wireValue: String) {
    Nearest("Nearest", "nearest"),
    Bilinear("Bilinear", "bilinear");

    companion object {
        fun fromWireValue(value: String?): ScreenFiltering =
            entries.firstOrNull { it.wireValue == value } ?: Nearest
    }
}

enum class ScreenTouchMode(val label: String, val wireValue: String) {
    Trackpad("Trackpad", "1"),
    SimulatedTouch("Simulated touch", "2"),
    DirectTouch("Direct touch", "3");

    companion object {
        fun fromWireValue(value: String?): ScreenTouchMode =
            entries.firstOrNull { it.wireValue == value } ?: Trackpad
    }
}
