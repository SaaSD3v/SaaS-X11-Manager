package com.saas.x11manager.util

import android.content.Context

object FixSettings {
    private const val PREFS_NAME = "container_fixes"
    private const val PULSEAUDIO_PREFIX = "pulseaudio::"
    private const val PULSEAUDIO_APPLIED_PREFIX = "pulseaudio_applied::"
    private const val PULSEAUDIO_ORIGINAL_PREFIX = "pulseaudio_original::"
    private const val VIRGL_PREFIX = "virgl::"
    private const val VIRGL_APPLIED_PREFIX = "virgl_applied::"
    private const val VIRGL_ORIGINAL_PREFIX = "virgl_original::"
    private const val VIRGL_FLAGS_KEY = "virgl_runtime_flags"

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

    fun isVirGLEnabled(context: Context, containerName: String): Boolean =
        prefs(context).getBoolean(VIRGL_PREFIX + containerName, false)

    fun setVirGLEnabled(
        context: Context,
        containerName: String,
        enabled: Boolean
    ): Boolean = prefs(context)
        .edit()
        .putBoolean(VIRGL_PREFIX + containerName, enabled)
        .commit()

    fun isVirGLApplied(context: Context, containerName: String): Boolean =
        prefs(context).getBoolean(VIRGL_APPLIED_PREFIX + containerName, false)

    fun setVirGLApplied(
        context: Context,
        containerName: String,
        applied: Boolean
    ): Boolean = prefs(context)
        .edit()
        .putBoolean(VIRGL_APPLIED_PREFIX + containerName, applied)
        .commit()

    fun getVirGLOriginalState(context: Context, containerName: String): String? =
        prefs(context).getString(VIRGL_ORIGINAL_PREFIX + containerName, null)

    fun setVirGLOriginalState(
        context: Context,
        containerName: String,
        state: String
    ): Boolean = prefs(context)
        .edit()
        .putString(VIRGL_ORIGINAL_PREFIX + containerName, state)
        .commit()

    fun getVirGLRuntimeFlags(context: Context): Set<VirGLRuntimeFlag> =
        VirGLRuntimeFlags.fromStored(
            prefs(context).getStringSet(VIRGL_FLAGS_KEY, emptySet()).orEmpty()
        )

    fun setVirGLRuntimeFlagEnabled(
        context: Context,
        flag: VirGLRuntimeFlag,
        enabled: Boolean
    ): Boolean {
        val updated = VirGLRuntimeFlags.withToggled(getVirGLRuntimeFlags(context), flag, enabled)
        return prefs(context)
            .edit()
            .putStringSet(VIRGL_FLAGS_KEY, VirGLRuntimeFlags.toStored(updated))
            .commit()
    }
    fun clearVirGLRuntimeState(context: Context, containerName: String): Boolean =
        prefs(context)
            .edit()
            .remove(VIRGL_APPLIED_PREFIX + containerName)
            .remove(VIRGL_ORIGINAL_PREFIX + containerName)
            .commit()
}
