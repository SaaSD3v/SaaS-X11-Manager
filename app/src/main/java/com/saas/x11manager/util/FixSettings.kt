package com.saas.x11manager.util

import android.content.Context

/**
 * Per-container optional fixes.
 *
 * A switch stores intent only. No package, host service or container file is
 * touched from the settings screen. The selected state is reconciled later by
 * SessionAccessManager when the user actually presses Start X11/VNC/Both.
 */
object FixSettings {
    private const val PREFS_NAME = "container_fixes"
    private const val PULSEAUDIO_PREFIX = "pulseaudio::"
    private const val PULSEAUDIO_APPLIED_PREFIX = "pulseaudio_applied::"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPulseAudioEnabled(context: Context, containerName: String): Boolean =
        prefs(context).getBoolean(PULSEAUDIO_PREFIX + containerName, false)

    fun setPulseAudioEnabled(
        context: Context,
        containerName: String,
        enabled: Boolean
    ): Boolean = prefs(context)
        .edit()
        .putBoolean(PULSEAUDIO_PREFIX + containerName, enabled)
        .commit()

    fun isPulseAudioApplied(context: Context, containerName: String): Boolean =
        prefs(context).getBoolean(PULSEAUDIO_APPLIED_PREFIX + containerName, false)

    fun setPulseAudioApplied(
        context: Context,
        containerName: String,
        applied: Boolean
    ): Boolean = prefs(context)
        .edit()
        .putBoolean(PULSEAUDIO_APPLIED_PREFIX + containerName, applied)
        .commit()
}
