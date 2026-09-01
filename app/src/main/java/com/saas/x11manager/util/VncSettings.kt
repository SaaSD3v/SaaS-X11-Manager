package com.saas.x11manager.util

import android.content.Context

enum class SessionAccessMode(
    val label: String,
    val requiresVnc: Boolean,
    val usesIntegratedX11: Boolean
) {
    INTEGRATED_X11("Integrated X11", requiresVnc = false, usesIntegratedX11 = true),
    VNC("VNC", requiresVnc = true, usesIntegratedX11 = false),
    BOTH("Both", requiresVnc = true, usesIntegratedX11 = true)
}

/**
 * Persistent per-container settings for the external TigerVNC integration.
 *
 * The Manager does not embed a VNC server. These values are consumed by the
 * external TigerVNC launcher/configuration flow. VNC passwords are deliberately
 * not persisted here: TigerVNC stores its obfuscated VNCAuth password file inside
 * the container and the Manager drops the plaintext after a successful start.
 */
object VncSettings {
    const val DEFAULT_PORT = 5901
    const val MIN_PORT = 1
    const val MAX_PORT = 65535
    const val MIN_PASSWORD_LENGTH = 6
    const val MAX_PASSWORD_LENGTH = 8

    private const val PREFS_NAME = "vnc_settings"
    private const val PORT_PREFIX = "port::"
    private const val ACCESS_MODE_PREFIX = "access_mode::"

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

    fun getAccessMode(context: Context, containerName: String): SessionAccessMode {
        val stored = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(ACCESS_MODE_PREFIX + containerName, null)
        return SessionAccessMode.entries.firstOrNull { it.name == stored }
            ?: SessionAccessMode.INTEGRATED_X11
    }

    fun setAccessMode(
        context: Context,
        containerName: String,
        mode: SessionAccessMode
    ): Boolean = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(ACCESS_MODE_PREFIX + containerName, mode.name)
        .commit()

    fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

    fun isValidPassword(password: String): Boolean =
        password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH &&
            password.none { it == '\n' || it == '\r' || it.code < 0x20 }
}
