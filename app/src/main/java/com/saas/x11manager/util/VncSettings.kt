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
 * Per-container TigerVNC launch configuration.
 *
 * Values are persisted as strings/booleans so the full-screen editor can
 * preserve user input without converting it through locale-sensitive types.
 * Manager-owned secrets are intentionally absent from this structure.
 */
data class VncLaunchSettings(
    val geometry: String = VncSettings.DEFAULT_GEOMETRY,
    val depth: String = VncSettings.DEFAULT_DEPTH.toString(),
    val pixelFormat: String = "",
    val renderNode: String = "",
    val desktopName: String = "",
    val interfaceAddress: String = "",
    val localhostOnly: Boolean = false,
    val useIPv4: Boolean = true,
    val useIPv6: Boolean = true,
    val securityTypes: String = VncSettings.SECURITY_TYPES_AUTO,
    val alwaysShared: Boolean = true,
    val neverShared: Boolean = false,
    val disconnectClients: Boolean = true,
    val acceptKeyEvents: Boolean = true,
    val acceptPointerEvents: Boolean = true,
    val acceptSetDesktopSize: Boolean = true,
    val acceptCutText: Boolean = true,
    val sendCutText: Boolean = true,
    val sendPrimary: Boolean = true,
    val setPrimary: Boolean = true,
    val maxCutText: String = "262144",
    val rawKeyboard: Boolean = false,
    val avoidShiftNumLock: Boolean = false,
    val remapKeys: String = "",
    val protocol33: Boolean = false,
    val useBlacklist: Boolean = true,
    val blacklistThreshold: String = "5",
    val blacklistTimeout: String = "10",
    val queryConnect: Boolean = false,
    val queryConnectTimeout: String = "10",
    val requireUsername: Boolean = false,
    val pamService: String = "vnc",
    val plainUsers: String = "",
    val gnuTlsPriority: String = "",
    val x509Cert: String = "",
    val x509Key: String = "",
    val rsaKey: String = "",
    val idleTimeout: String = "0",
    val maxConnectionTime: String = "0",
    val maxDisconnectionTime: String = "0",
    val maxIdleTime: String = "0",
    val frameRate: String = "60",
    val compareFb: String = "2",
    val improvedHextile: Boolean = true,
    val logSpec: String = "",
    val allowOverride: String = "",
    val mirrorGeometry: String = "",
    val hostsFile: String = "",
    val maxProcessorUsage: String = "35",
    val pollingCycle: String = "30",
    val useShm: Boolean = true,
    val rfbUnixPath: String = "",
    val rfbUnixMode: String = "0600",
    val extraArguments: String = ""
)

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

    const val DEFAULT_GEOMETRY = "1280x720"
    const val DEFAULT_DEPTH = 24
    const val MIN_DIMENSION = 64
    const val MAX_DIMENSION = 16384
    const val SECURITY_TYPES_AUTO = "auto"

    private const val PREFS_NAME = "vnc_settings"
    private const val PORT_PREFIX = "port::"
    private const val ACCESS_MODE_PREFIX = "access_mode::"
    private const val OPTION_PREFIX = "option::"

    private fun key(containerName: String, option: String): String =
        "$OPTION_PREFIX$containerName::$option"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPort(context: Context, containerName: String): Int {
        val stored = prefs(context).getInt(PORT_PREFIX + containerName, DEFAULT_PORT)
        return if (isValidPort(stored)) stored else DEFAULT_PORT
    }

    fun setPort(context: Context, containerName: String, port: Int): Boolean {
        if (!isValidPort(port)) return false
        return prefs(context)
            .edit()
            .putInt(PORT_PREFIX + containerName, port)
            .commit()
    }

    fun resetPort(context: Context, containerName: String): Boolean =
        prefs(context)
            .edit()
            .remove(PORT_PREFIX + containerName)
            .commit()

    fun getGeometry(context: Context, containerName: String): String {
        val stored = prefs(context).getString(key(containerName, "geometry"), DEFAULT_GEOMETRY)
            ?: DEFAULT_GEOMETRY
        return if (isValidGeometry(stored)) stored else DEFAULT_GEOMETRY
    }

    fun setGeometry(context: Context, containerName: String, geometry: String): Boolean {
        val normalized = normalizeGeometry(geometry)
        if (!isValidGeometry(normalized)) return false
        return prefs(context)
            .edit()
            .putString(key(containerName, "geometry"), normalized)
            .commit()
    }

    fun resetGeometry(context: Context, containerName: String): Boolean =
        prefs(context)
            .edit()
            .remove(key(containerName, "geometry"))
            .commit()

    fun setGeneral(
        context: Context,
        containerName: String,
        port: Int,
        geometry: String
    ): Boolean {
        val normalized = normalizeGeometry(geometry)
        if (!isValidPort(port) || !isValidGeometry(normalized)) return false
        return prefs(context)
            .edit()
            .putInt(PORT_PREFIX + containerName, port)
            .putString(key(containerName, "geometry"), normalized)
            .commit()
    }

    fun resetGeneral(context: Context, containerName: String): Boolean =
        prefs(context)
            .edit()
            .remove(PORT_PREFIX + containerName)
            .remove(key(containerName, "geometry"))
            .commit()

    fun getAccessMode(context: Context, containerName: String): SessionAccessMode {
        val stored = prefs(context).getString(ACCESS_MODE_PREFIX + containerName, null)
        return SessionAccessMode.entries.firstOrNull { it.name == stored }
            ?: SessionAccessMode.INTEGRATED_X11
    }

    fun setAccessMode(
        context: Context,
        containerName: String,
        mode: SessionAccessMode
    ): Boolean = prefs(context)
        .edit()
        .putString(ACCESS_MODE_PREFIX + containerName, mode.name)
        .commit()

    fun getLaunchSettings(context: Context, containerName: String): VncLaunchSettings {
        val p = prefs(context)
        fun s(name: String, default: String): String =
            p.getString(key(containerName, name), default) ?: default
        fun b(name: String, default: Boolean): Boolean =
            p.getBoolean(key(containerName, name), default)

        val loaded = VncLaunchSettings(
            geometry = s("geometry", DEFAULT_GEOMETRY),
            depth = s("depth", DEFAULT_DEPTH.toString()),
            pixelFormat = s("pixelFormat", ""),
            renderNode = s("renderNode", ""),
            desktopName = s("desktopName", ""),
            interfaceAddress = s("interfaceAddress", ""),
            localhostOnly = b("localhostOnly", false),
            useIPv4 = b("useIPv4", true),
            useIPv6 = b("useIPv6", true),
            securityTypes = s("securityTypes", SECURITY_TYPES_AUTO),
            alwaysShared = b("alwaysShared", true),
            neverShared = b("neverShared", false),
            disconnectClients = b("disconnectClients", true),
            acceptKeyEvents = b("acceptKeyEvents", true),
            acceptPointerEvents = b("acceptPointerEvents", true),
            acceptSetDesktopSize = b("acceptSetDesktopSize", true),
            acceptCutText = b("acceptCutText", true),
            sendCutText = b("sendCutText", true),
            sendPrimary = b("sendPrimary", true),
            setPrimary = b("setPrimary", true),
            maxCutText = s("maxCutText", "262144"),
            rawKeyboard = b("rawKeyboard", false),
            avoidShiftNumLock = b("avoidShiftNumLock", false),
            remapKeys = s("remapKeys", ""),
            protocol33 = b("protocol33", false),
            useBlacklist = b("useBlacklist", true),
            blacklistThreshold = s("blacklistThreshold", "5"),
            blacklistTimeout = s("blacklistTimeout", "10"),
            queryConnect = b("queryConnect", false),
            queryConnectTimeout = s("queryConnectTimeout", "10"),
            requireUsername = b("requireUsername", false),
            pamService = s("pamService", "vnc"),
            plainUsers = s("plainUsers", ""),
            gnuTlsPriority = s("gnuTlsPriority", ""),
            x509Cert = s("x509Cert", ""),
            x509Key = s("x509Key", ""),
            rsaKey = s("rsaKey", ""),
            idleTimeout = s("idleTimeout", "0"),
            maxConnectionTime = s("maxConnectionTime", "0"),
            maxDisconnectionTime = s("maxDisconnectionTime", "0"),
            maxIdleTime = s("maxIdleTime", "0"),
            frameRate = s("frameRate", "60"),
            compareFb = s("compareFb", "2"),
            improvedHextile = b("improvedHextile", true),
            logSpec = s("logSpec", ""),
            allowOverride = s("allowOverride", ""),
            mirrorGeometry = s("mirrorGeometry", ""),
            hostsFile = s("hostsFile", ""),
            maxProcessorUsage = s("maxProcessorUsage", "35"),
            pollingCycle = s("pollingCycle", "30"),
            useShm = b("useShm", true),
            rfbUnixPath = s("rfbUnixPath", ""),
            rfbUnixMode = s("rfbUnixMode", "0600"),
            extraArguments = s("extraArguments", "")
        )

        return if (validateLaunchSettings(loaded) == null) {
            loaded.copy(geometry = normalizeGeometry(loaded.geometry))
        } else {
            VncLaunchSettings(geometry = getGeometry(context, containerName))
        }
    }

    fun setLaunchSettings(
        context: Context,
        containerName: String,
        settings: VncLaunchSettings
    ): Boolean {
        val normalized = settings.copy(geometry = normalizeGeometry(settings.geometry))
        if (validateLaunchSettings(normalized) != null) return false

        val e = prefs(context).edit()
        fun put(name: String, value: String) = e.putString(key(containerName, name), value)
        fun put(name: String, value: Boolean) = e.putBoolean(key(containerName, name), value)

        put("geometry", normalized.geometry)
        put("depth", normalized.depth)
        put("pixelFormat", normalized.pixelFormat.trim())
        put("renderNode", normalized.renderNode.trim())
        put("desktopName", normalized.desktopName)
        put("interfaceAddress", normalized.interfaceAddress.trim())
        put("localhostOnly", normalized.localhostOnly)
        put("useIPv4", normalized.useIPv4)
        put("useIPv6", normalized.useIPv6)
        put("securityTypes", normalized.securityTypes.trim())
        put("alwaysShared", normalized.alwaysShared)
        put("neverShared", normalized.neverShared)
        put("disconnectClients", normalized.disconnectClients)
        put("acceptKeyEvents", normalized.acceptKeyEvents)
        put("acceptPointerEvents", normalized.acceptPointerEvents)
        put("acceptSetDesktopSize", normalized.acceptSetDesktopSize)
        put("acceptCutText", normalized.acceptCutText)
        put("sendCutText", normalized.sendCutText)
        put("sendPrimary", normalized.sendPrimary)
        put("setPrimary", normalized.setPrimary)
        put("maxCutText", normalized.maxCutText)
        put("rawKeyboard", normalized.rawKeyboard)
        put("avoidShiftNumLock", normalized.avoidShiftNumLock)
        put("remapKeys", normalized.remapKeys)
        put("protocol33", normalized.protocol33)
        put("useBlacklist", normalized.useBlacklist)
        put("blacklistThreshold", normalized.blacklistThreshold)
        put("blacklistTimeout", normalized.blacklistTimeout)
        put("queryConnect", normalized.queryConnect)
        put("queryConnectTimeout", normalized.queryConnectTimeout)
        put("requireUsername", normalized.requireUsername)
        put("pamService", normalized.pamService.trim())
        put("plainUsers", normalized.plainUsers.trim())
        put("gnuTlsPriority", normalized.gnuTlsPriority)
        put("x509Cert", normalized.x509Cert.trim())
        put("x509Key", normalized.x509Key.trim())
        put("rsaKey", normalized.rsaKey.trim())
        put("idleTimeout", normalized.idleTimeout)
        put("maxConnectionTime", normalized.maxConnectionTime)
        put("maxDisconnectionTime", normalized.maxDisconnectionTime)
        put("maxIdleTime", normalized.maxIdleTime)
        put("frameRate", normalized.frameRate)
        put("compareFb", normalized.compareFb)
        put("improvedHextile", normalized.improvedHextile)
        put("logSpec", normalized.logSpec)
        put("allowOverride", normalized.allowOverride)
        put("mirrorGeometry", normalized.mirrorGeometry.trim())
        put("hostsFile", normalized.hostsFile.trim())
        put("maxProcessorUsage", normalized.maxProcessorUsage)
        put("pollingCycle", normalized.pollingCycle)
        put("useShm", normalized.useShm)
        put("rfbUnixPath", normalized.rfbUnixPath.trim())
        put("rfbUnixMode", normalized.rfbUnixMode.trim())
        put("extraArguments", normalized.extraArguments.trimEnd())
        return e.commit()
    }

    fun resetLaunchSettings(context: Context, containerName: String): Boolean {
        val p = prefs(context)
        val prefix = "$OPTION_PREFIX$containerName::"
        val e = p.edit()
        p.all.keys.filter { it.startsWith(prefix) }.forEach(e::remove)
        return e.commit()
    }

    fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

    fun isValidPassword(password: String): Boolean =
        password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH &&
            password.none { it == '\n' || it == '\r' || it.code < 0x20 }

    fun normalizeGeometry(value: String): String =
        value.trim().lowercase().replace(" ", "")

    fun isValidGeometry(value: String): Boolean {
        val match = Regex("""^(\d{2,5})x(\d{2,5})$""")
            .matchEntire(normalizeGeometry(value))
            ?: return false
        val width = match.groupValues[1].toIntOrNull() ?: return false
        val height = match.groupValues[2].toIntOrNull() ?: return false
        return width in MIN_DIMENSION..MAX_DIMENSION &&
            height in MIN_DIMENSION..MAX_DIMENSION
    }

    fun isValidMirrorGeometry(value: String): Boolean {
        if (value.isBlank()) return true
        val match = Regex("""^(\d{2,5})x(\d{2,5})(?:[+-]\d{1,5}[+-]\d{1,5})?$""")
            .matchEntire(value.trim().lowercase().replace(" ", ""))
            ?: return false
        val width = match.groupValues[1].toIntOrNull() ?: return false
        val height = match.groupValues[2].toIntOrNull() ?: return false
        return width in MIN_DIMENSION..MAX_DIMENSION &&
            height in MIN_DIMENSION..MAX_DIMENSION
    }

    fun validateLaunchSettings(settings: VncLaunchSettings): String? {
        if (!isValidGeometry(settings.geometry)) {
            return "Standalone resolution must use WIDTHxHEIGHT (${MIN_DIMENSION}-${MAX_DIMENSION})."
        }

        fun intIn(value: String, range: IntRange, label: String): String? {
            val parsed = value.trim().toIntOrNull()
                ?: return "$label must be a number."
            return if (parsed in range) null else "$label must be ${range.first}-${range.last}."
        }

        intIn(settings.depth, 8..32, "Color depth")?.let { return it }
        val depth = settings.depth.trim().toInt()
        if (depth !in setOf(16, 24, 32)) {
            return "Color depth must be 16, 24, or 32."
        }
        intIn(settings.maxCutText, 0..16_777_216, "Maximum clipboard bytes")?.let { return it }
        intIn(settings.blacklistThreshold, 0..1000, "Blacklist threshold")?.let { return it }
        intIn(settings.blacklistTimeout, 0..86_400, "Blacklist timeout")?.let { return it }
        intIn(settings.queryConnectTimeout, 1..3600, "Query timeout")?.let { return it }
        intIn(settings.idleTimeout, 0..2_147_483_647, "Idle timeout")?.let { return it }
        intIn(settings.maxConnectionTime, 0..2_147_483_647, "Max connection time")?.let { return it }
        intIn(settings.maxDisconnectionTime, 0..2_147_483_647, "Max disconnection time")?.let { return it }
        intIn(settings.maxIdleTime, 0..2_147_483_647, "Max idle time")?.let { return it }
        intIn(settings.frameRate, 1..240, "Frame rate")?.let { return it }
        intIn(settings.compareFb, 0..2, "CompareFB")?.let { return it }
        intIn(settings.maxProcessorUsage, 1..100, "Max processor usage")?.let { return it }
        intIn(settings.pollingCycle, 1..1000, "Polling cycle")?.let { return it }

        if (!isValidMirrorGeometry(settings.mirrorGeometry)) {
            return "Mirror crop must be WIDTHxHEIGHT or WIDTHxHEIGHT+X+Y."
        }
        if (!settings.useIPv4 && !settings.useIPv6 && settings.rfbUnixPath.isBlank()) {
            return "Enable IPv4 or IPv6, or configure an RFB UNIX socket."
        }
        if (settings.neverShared && settings.alwaysShared) {
            return "Always shared and Never shared cannot both be enabled."
        }
        if (!settings.securityTypes.equals(SECURITY_TYPES_AUTO, ignoreCase = true) &&
            !Regex("""^[A-Za-z0-9_-]+(?:,[A-Za-z0-9_-]+)*$""")
                .matches(settings.securityTypes.trim())
        ) {
            return "SecurityTypes must be 'auto' or a comma-separated TigerVNC security list."
        }
        if (settings.rfbUnixMode.isNotBlank() &&
            !Regex("""^0?[0-7]{3,4}$""").matches(settings.rfbUnixMode.trim())
        ) {
            return "RFB UNIX mode must be an octal mode such as 0600."
        }

        val singleLineValues = listOf(
            "Pixel format" to settings.pixelFormat,
            "Render node" to settings.renderNode,
            "Desktop name" to settings.desktopName,
            "Interface" to settings.interfaceAddress,
            "Remap keys" to settings.remapKeys,
            "PAM service" to settings.pamService,
            "Plain users" to settings.plainUsers,
            "GnuTLS priority" to settings.gnuTlsPriority,
            "X509 certificate" to settings.x509Cert,
            "X509 key" to settings.x509Key,
            "RSA key" to settings.rsaKey,
            "Log specification" to settings.logSpec,
            "AllowOverride" to settings.allowOverride,
            "Hosts file" to settings.hostsFile,
            "RFB UNIX path" to settings.rfbUnixPath
        )
        singleLineValues.firstOrNull { (_, value) ->
            value.any { it == '\n' || it == '\r' || it == '\u0000' || it.code < 0x20 && it != '\t' }
        }?.let { return "${it.first} contains unsupported control characters." }

        TigerVncCommandOptions.validateExtraArguments(settings.extraArguments)?.let { return it }
        return null
    }
}
