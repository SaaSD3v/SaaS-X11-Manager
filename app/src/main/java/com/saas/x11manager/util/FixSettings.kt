package com.saas.x11manager.util

import android.content.Context

/**
 * Per-container optional fixes. Every fix is opt-in: missing preferences always
 * resolve to false so installing/updating the Manager cannot silently modify a
 * container or the Termux host.
 */
object FixSettings {
    private const val PREFS_NAME = "container_fixes"
    private const val PULSEAUDIO_PREFIX = "pulseaudio::"

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
}
