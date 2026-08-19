package com.saas.x11manager.ui.screen

import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager

internal object ManagedX11WindowPolicy {
    fun requestedOrientation(value: String): Int = when (value) {
        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        "reverse portrait" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        "reverse landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun cutoutMode(useDisplayCutoutArea: Boolean, sdkInt: Int): Int? {
        if (sdkInt < Build.VERSION_CODES.P) return null
        if (!useDisplayCutoutArea) {
            return WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        return if (sdkInt >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /**
     * Extra time for which the Manager should hold FLAG_KEEP_SCREEN_ON before
     * handing control back to Android's normal screen-off timeout.
     *
     * null = use Android's timeout unchanged
     * Long.MAX_VALUE = keep the display on while X11 is connected
     */
    fun keepScreenOnWindowMillis(mode: String, systemTimeoutMillis: Long): Long? {
        if (mode == "system") return null
        if (mode == "never") return Long.MAX_VALUE

        val minutes = mode.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val requestedMillis = minutes * 60_000L
        return (requestedMillis - systemTimeoutMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
    }
}
