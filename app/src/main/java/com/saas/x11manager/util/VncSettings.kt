package com.saas.x11manager.util

import android.content.Context

/**
 * Persistent per-container settings for the external TigerVNC integration.
 *
 * The Manager does not embed a VNC server. These values are consumed by the
 * external VNC launcher/configuration flow when that feature is enabled.
 */
object VncSettings {
    const val DEFAULT_PORT = 5901
    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    private const val PREFS_NAME = "vnc_settings"
    private const val PORT_PREFIX = "port::"

    fun getPort(context: Context, containerName: String): Int {
        val stored = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PORT_PREFIX + containerName, DEFAULT_PORT)
        return if (isValidPort(stored)) stored else DEFAULT_PORT
    }

    fun setPort(context: Context, containerName: String, port: Int): Boolean {
        if (!isValidPort(port)) return false
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PORT_PREFIX + containerName, port)
            .commit()
    }

    fun resetPort(context: Context, containerName: String): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PORT_PREFIX + containerName)
            .commit()

    fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
}
