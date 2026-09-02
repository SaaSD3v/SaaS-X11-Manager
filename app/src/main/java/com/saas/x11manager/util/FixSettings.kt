package com.saas.x11manager.util

import android.content.Context

object FixSettings {
    private const val PREFS_NAME = "container_fixes"
    private const val PULSEAUDIO_PREFIX = "pulseaudio::"
    private const val PULSEAUDIO_APPLIED_PREFIX = "pulseaudio_applied::"
    private const val PULSEAUDIO_ORIGINAL_PREFIX = "pulseaudio_original::"

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

    fun getPulseAudioOriginalState(context: Context, containerName: String): String? =
        prefs(context).getString(PULSEAUDIO_ORIGINAL_PREFIX + containerName, null)

    fun setPulseAudioOriginalState(
        context: Context,
        containerName: String,
        state: String
    ): Boolean = prefs(context)
        .edit()
        .putString(PULSEAUDIO_ORIGINAL_PREFIX + containerName, state)
        .commit()

    fun clearPulseAudioRuntimeState(context: Context, containerName: String): Boolean =
        prefs(context)
            .edit()
            .remove(PULSEAUDIO_APPLIED_PREFIX + containerName)
            .remove(PULSEAUDIO_ORIGINAL_PREFIX + containerName)
            .commit()
}
