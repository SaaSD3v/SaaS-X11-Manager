package com.saas.x11manager.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Project-owned control plane for the embedded Lorie X11 display.
 *
 * The X server lifecycle remains in [X11SessionManager]. This manager owns the
 * user-facing screen configuration, persists it independently from container
 * configuration and translates it to the preference contract exposed by the
 * bundled Lorie module.
 */
object ScreenManager {
    private const val PREFS_NAME = "screen_manager"
    private const val LORIE_CHANGE_PREFERENCE = "com.termux.x11.CHANGE_PREFERENCE"
    private const val LORIE_PREFERENCE_RECEIVER = "com.termux.x11.LoriePreferences\$Receiver"

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
            stretch = prefs.getBoolean("stretch", false),
            fullscreen = prefs.getBoolean("fullscreen", false),
            orientation = ScreenOrientation.fromWireValue(
                prefs.getString("orientation", null)
            ),
            hideCutout = prefs.getBoolean("hide_cutout", false),
            clipboard = prefs.getBoolean("clipboard", true),
            touchMode = ScreenTouchMode.fromWireValue(
                prefs.getString("touch_mode", null)
            ),
            keepScreenAwake = prefs.getBoolean("keep_screen_awake", false),
            showAdditionalKeyboard = prefs.getBoolean("show_additional_keyboard", false)
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
            .putBoolean("stretch", normalized.stretch)
            .putBoolean("fullscreen", normalized.fullscreen)
            .putString("orientation", normalized.orientation.wireValue)
            .putBoolean("hide_cutout", normalized.hideCutout)
            .putBoolean("clipboard", normalized.clipboard)
            .putString("touch_mode", normalized.touchMode.wireValue)
            .putBoolean("keep_screen_awake", normalized.keepScreenAwake)
            .putBoolean("show_additional_keyboard", normalized.showAdditionalKeyboard)
            .apply()
    }

    /**
     * Convert our stable project model to the preference keys used by the exact
     * Lorie revision pinned by this repository.
     */
    internal fun buildPreferencePayload(config: ScreenConfig): Map<String, String> {
        val normalized = config.normalized()
        return linkedMapOf(
            "displayResolutionMode" to normalized.resolutionMode.wireValue,
            "displayScale" to normalized.scalePercent.toString(),
            "displayResolutionExact" to normalized.exactResolution,
            "displayResolutionCustom" to normalized.customResolution,
            "displayFilteringMode" to normalized.filtering.wireValue,
            "displayStretch" to normalized.stretch.toString(),
            "fullscreen" to normalized.fullscreen.toString(),
            "forceOrientation" to normalized.orientation.wireValue,
            "hideCutout" to normalized.hideCutout.toString(),
            "clipboardEnable" to normalized.clipboard.toString(),
            "touchMode" to normalized.touchMode.wireValue,
            "screenIdleTimeout" to if (normalized.keepScreenAwake) "never" else "system",
            "showAdditionalKbd" to normalized.showAdditionalKeyboard.toString()
        )
    }

    fun apply(context: Context, config: ScreenConfig) {
        val normalized = config.normalized()
        save(context, normalized)

        val intent = Intent(LORIE_CHANGE_PREFERENCE).apply {
            component = ComponentName(context.packageName, LORIE_PREFERENCE_RECEIVER)
            buildPreferencePayload(normalized).forEach { (key, value) ->
                putExtra(key, value)
            }
        }
        context.sendBroadcast(intent)
    }

    /**
     * Starts only the embedded X11 server. Rendering is attached by the
     * EmbeddedX11View that lives in the Screen tab; no secondary Activity is opened.
     */
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

    // Legacy detached-Activity path kept temporarily while the existing ScreenScreen
    // is migrated to the embedded surface.
    suspend fun start(
        context: Context,
        xkbContainerName: String?,
        config: ScreenConfig,
        logger: ContainerLogger? = null
    ): Result<ScreenLaunchResult> = withContext(Dispatchers.IO) {
        try {
            apply(context, config)

            val started = X11SessionManager.startIntegratedServer(
                containerName = xkbContainerName,
                logger = logger
            )
            if (started.isFailure) {
                return@withContext Result.failure(
                    started.exceptionOrNull()
                        ?: IllegalStateException("Integrated X11 server could not start")
                )
            }

            val pid = started.getOrThrow()
            val opened = X11SessionManager.openIntegratedDisplay(logger)
            Result.success(ScreenLaunchResult(pid = pid, displayOpened = opened))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun open(
        context: Context,
        config: ScreenConfig,
        logger: ContainerLogger? = null
    ): Boolean {
        apply(context, config)
        return X11SessionManager.openIntegratedDisplay(logger)
    }

    suspend fun stop(logger: ContainerLogger? = null): Boolean =
        X11SessionManager.stopIntegratedServer(logger)
}

data class ScreenLaunchResult(
    val pid: Int,
    val displayOpened: Boolean
)

data class ScreenConfig(
    val resolutionMode: ScreenResolutionMode = ScreenResolutionMode.Native,
    val scalePercent: Int = 100,
    val exactResolution: String = "1280x1024",
    val customResolution: String = "1280x1024",
    val filtering: ScreenFiltering = ScreenFiltering.Nearest,
    val stretch: Boolean = false,
    val fullscreen: Boolean = false,
    val orientation: ScreenOrientation = ScreenOrientation.Auto,
    val hideCutout: Boolean = false,
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

enum class ScreenOrientation(val label: String, val wireValue: String) {
    Auto("Auto", "auto"),
    Portrait("Portrait", "portrait"),
    Landscape("Landscape", "landscape"),
    ReversePortrait("Reverse portrait", "reverse portrait"),
    ReverseLandscape("Reverse landscape", "reverse landscape");

    companion object {
        fun fromWireValue(value: String?): ScreenOrientation =
            entries.firstOrNull { it.wireValue == value } ?: Auto
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
