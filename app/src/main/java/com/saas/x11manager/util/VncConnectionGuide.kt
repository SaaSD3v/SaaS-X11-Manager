package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Final user-facing connection information printed after a successful VNC start.
 *
 * Every endpoint comes from either the effective Manager configuration or a real
 * runtime probe. The Manager never claims that a PC-side ADB forward already
 * exists; it only prints an exact command when the Android host-side TCP target
 * is actually listening.
 */
object VncConnectionGuide {
    const val ACTIVE_SUMMARY_BEGIN = "[VNC] ── Active VNC session ──"
    const val ACTIVE_SUMMARY_END = "[VNC] ─────────────────────────"

    suspend fun logAfterSuccessfulStart(
        containerName: String,
        port: Int,
        adbLocalPort: Int,
        displayName: String?,
        desktopUser: String,
        session: GraphicSession,
        password: String?,
        logger: ContainerLogger?
    ) {
        if (logger == null) return

        val effectiveLocalPort = if (VncSettings.isValidPort(adbLocalPort)) adbLocalPort else port
        val runtime = withContext(Dispatchers.IO) {
            val info = ContainerManager.getContainerInfo(containerName)
            val settings = VncSettings.getLaunchSettings(
                com.saas.x11manager.X11Application.instance,
                containerName
            )
            RuntimeConnectionSnapshot(
                netMode = info?.netMode?.trim()?.lowercase(),
                containerIpv4 = containerIpv4Addresses(containerName),
                preferredLan = preferredLanAddress(),
                androidHostPortListening = isAndroidHostPortListening(port),
                settings = settings
            )
        }

        logger.i("")
        logger.i(ACTIVE_SUMMARY_BEGIN)
        logger.i("[VNC] ✓ Standalone VNC is ready")
        logger.i("[CONTAINER] • Container: $containerName")
        logger.i("[USER] • Desktop user: $desktopUser")
        logger.i("[SESSION] • Desktop: ${session.label}")
        displayName?.let { logger.i("[VNC] • Virtual X display: $it") }
        logger.i("[VNC] • Server port: $port")
        logger.i("[VNC] • Resolution: ${runtime.settings.geometry}")
        logger.i("[VNC] • Color depth: ${runtime.settings.depth}")

        val configuredBind = runtime.settings.interfaceAddress.trim()
        if (configuredBind.isEmpty()) {
            logger.i("[VNC] • Bind/interface: automatic")
        } else {
            logger.i("[VNC] • Configured bind/interface: $configuredBind")
        }
        logger.i("[VNC] • Localhost only: ${onOff(runtime.settings.localhostOnly)}")
        logger.i("[VNC] • IPv4: ${onOff(runtime.settings.useIPv4)} · IPv6: ${onOff(runtime.settings.useIPv6)}")

        if (runtime.containerIpv4.isEmpty()) {
            logger.w("[VNC] ! Container IPv4 address could not be resolved")
        } else {
            runtime.containerIpv4.forEach { ip ->
                logger.i("[VNC] • Container IPv4: $ip:$port")
            }
        }

        when {
            runtime.preferredLan == null -> {
                logger.w("[VNC] ! Android LAN/Wi-Fi IPv4 address could not be detected")
            }
            runtime.settings.localhostOnly -> {
                logger.i("[VNC] • Detected Android LAN: ${runtime.preferredLan}:$port")
                logger.w("[VNC] ! Direct LAN VNC is disabled because Localhost only is enabled")
            }
            runtime.androidHostPortListening -> {
                logger.i("[VNC] • Android/LAN endpoint: ${runtime.preferredLan}:$port")
            }
            runtime.netMode == "host" -> {
                logger.i("[VNC] • Detected Android LAN: ${runtime.preferredLan}:$port")
                logger.w("[VNC] ! Android host TCP $port was not confirmed listening; do not assume this LAN endpoint is reachable")
            }
            else -> {
                logger.i("[VNC] • Detected Android LAN: ${runtime.preferredLan}:$port")
                logger.w(
                    "[VNC] ! Container network mode is ${runtime.netMode ?: "unknown"}; direct LAN access requires a real DroidSpaces host-port publication"
                )
            }
        }

        logger.i("[VNC] • ADB forward local port selected: $effectiveLocalPort")
        if (runtime.androidHostPortListening) {
            logger.i("[VNC] • Android host target: 127.0.0.1:$port")
            logger.i("[VNC] • ADB forward command: adb forward tcp:$effectiveLocalPort tcp:$port")
            logger.i("[VNC] • USB local endpoint after forward: 127.0.0.1:$effectiveLocalPort")
            logger.i("[VNC] • ADB mapping: run the command on the PC; the Manager does not create PC-side forwards")
        } else {
            logger.w("[VNC] ! Android host TCP $port is not listening, so an exact USB ADB forward target cannot be advertised yet")
            logger.i("[VNC] • USB local endpoint after a valid forward will be: 127.0.0.1:$effectiveLocalPort")
            logger.i("[VNC] • After DroidSpaces publishes VNC on Android host port <hostPort>, use: adb forward tcp:$effectiveLocalPort tcp:<hostPort>")
        }

        if (password != null) {
            logger.i("[VNC] • Password: $password")
            logger.i("[VNC] • Password visibility: kept only in this in-memory active-session log")
        } else {
            logger.i("[VNC] • Password: already configured inside the container")
            logger.i("[VNC] • Password plaintext: unavailable to the Manager on this start")
        }

        val overrides = effectiveNonDefaultSettings(runtime.settings)
        if (overrides.isEmpty()) {
            logger.i("[VNC] • Advanced overrides: none; TigerVNC defaults/Manager defaults are in use")
        } else {
            logger.i("[VNC] • Effective non-default VNC settings:")
            overrides.forEach { value -> logger.i("[VNC]   • $value") }
        }

        logger.i(ACTIVE_SUMMARY_END)
        logger.i("[VNC] ✓ Connection information pinned until this VNC/container session ends")
    }

    /**
     * Keeps only the active-session summary from a terminal buffer. Used by the
     * UI Clear action so connection credentials/endpoints remain visible until
     * the VNC/container session actually ends.
     */
    fun retainPinnedSummary(entries: List<Pair<Int, String>>): List<Pair<Int, String>> {
        val retained = mutableListOf<Pair<Int, String>>()
        var inside = false
        entries.forEach { entry ->
            when (entry.second) {
                ACTIVE_SUMMARY_BEGIN -> {
                    inside = true
                    retained += entry
                }
                ACTIVE_SUMMARY_END -> {
                    if (inside) retained += entry
                    inside = false
                }
                else -> if (inside) retained += entry
            }
        }
        return retained
    }

    fun hasPinnedSummary(entries: List<Pair<Int, String>>): Boolean =
        entries.any { it.second == ACTIVE_SUMMARY_BEGIN }

    /**
     * Explains exact stop/restart ordering for a PC-side ADB forward. Local and
     * remote ports may be different. This recovery helper assumes the caller is
     * referring to a host-side port that was already known to work previously.
     */
    suspend fun logAdbForwardRestartRecovery(
        port: Int,
        localPort: Int = port,
        logger: ContainerLogger?,
        onlyIfTroubleshooting: Boolean = true
    ) {
        if (logger == null) return
        val effectiveLocalPort = if (VncSettings.isValidPort(localPort)) localPort else port
        logger.i("")
        if (onlyIfTroubleshooting) {
            logger.w("[VNC] ! If this VNC start failed after you previously created an ADB forward, remove the old PC mapping first")
        } else {
            logger.w("[VNC] ! For a later VNC restart over USB, recreate the ADB mapping only after VNC is ready")
        }
        logger.i("[VNC] • Remove old PC mapping: adb forward --remove tcp:$effectiveLocalPort")
        logger.i("[VNC] • Start VNC again and wait until the Manager reports VNC ready")
        logger.i("[VNC] • Recreate the mapping only if Android host TCP $port is published and listening")
        logger.i("[VNC] • Mapping command: adb forward tcp:$effectiveLocalPort tcp:$port")
        logger.i("[VNC] • USB client endpoint: 127.0.0.1:$effectiveLocalPort")
        logger.w("[VNC] ! adb forward --remove changes only the PC-side ADB mapping; it does not stop TigerVNC")
    }

    private data class RuntimeConnectionSnapshot(
        val netMode: String?,
        val containerIpv4: List<String>,
        val preferredLan: String?,
        val androidHostPortListening: Boolean,
        val settings: VncLaunchSettings
    )

    private data class HostIpv4(
        val interfaceName: String,
        val address: String
    )

    private fun preferredLanAddress(): String? {
        val addresses = androidHostIpv4Addresses()
        if (addresses.isEmpty()) return null

        return addresses.minWithOrNull(
            compareBy<HostIpv4> { lanPriority(it) }
                .thenBy { it.interfaceName }
                .thenBy { it.address }
        )?.address
    }

    private fun containerIpv4Addresses(containerName: String): List<String> {
        return try {
            val command =
                "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run sh -c " +
                    shellQuote(
                        "hostname -I 2>/dev/null || " +
                            "ip -4 -o addr show scope global 2>/dev/null | " +
                            "sed -n 's/.* inet \\([0-9.]*\\)\\/.*/\\1/p'"
                    )
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) return emptyList()
            result.out
                .flatMap { it.trim().split(Regex("\\s+")) }
                .filter(::isIpv4Address)
                .filterNot { it == "127.0.0.1" }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isAndroidHostPortListening(port: Int): Boolean {
        if (!VncSettings.isValidPort(port)) return false
        return try {
            val command =
                "hex=\$(printf '%04X' $port); " +
                    "for table in /proc/net/tcp /proc/net/tcp6; do " +
                    "[ -r \"\$table\" ] || continue; " +
                    "while read -r sl local remote state rest; do " +
                    "case \"\$local\" in *:\$hex) [ \"\$state\" = 0A ] && exit 0 ;; esac; " +
                    "done < \"\$table\"; done; exit 1"
            Shell.cmd(command).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun lanPriority(entry: HostIpv4): Int {
        val iface = entry.interfaceName.lowercase()
        val ip = entry.address
        return when {
            iface.startsWith("wlan") -> 0
            iface.startsWith("wifi") -> 1
            iface.startsWith("eth") -> 2
            ip.startsWith("192.168.") -> 3
            ip.startsWith("10.") -> 4
            isPrivate172(ip) -> 5
            else -> 6
        }
    }

    private fun androidHostIpv4Addresses(): List<HostIpv4> {
        return try {
            val result = Shell.cmd("ip -4 -o addr show scope global 2>/dev/null").exec()
            result.out.mapNotNull { line ->
                val match = Regex("^\\d+:\\s+([^\\s]+)\\s+inet\\s+([0-9.]+)/").find(line)
                    ?: return@mapNotNull null
                val iface = match.groupValues[1].substringBefore('@')
                val address = match.groupValues[2]
                if (!isIpv4Address(address) || address == "127.0.0.1") null
                else HostIpv4(iface, address)
            }.distinctBy { it.address }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun effectiveNonDefaultSettings(settings: VncLaunchSettings): List<String> {
        val defaults = VncLaunchSettings()
        val out = mutableListOf<String>()
        fun changed(value: Any?, default: Any?, label: String, display: String = value.toString()) {
            if (value != default) out += "$label: $display"
        }

        changed(settings.geometry, defaults.geometry, "Resolution", settings.geometry)
        changed(settings.depth, defaults.depth, "Color depth", settings.depth)
        changed(settings.pixelFormat, defaults.pixelFormat, "Pixel format", settings.pixelFormat)
        changed(settings.renderNode, defaults.renderNode, "Render node", settings.renderNode)
        changed(settings.desktopName, defaults.desktopName, "Desktop name", settings.desktopName)
        changed(settings.interfaceAddress, defaults.interfaceAddress, "Interface", settings.interfaceAddress)
        changed(settings.localhostOnly, defaults.localhostOnly, "Localhost only", onOff(settings.localhostOnly))
        changed(settings.useIPv4, defaults.useIPv4, "IPv4", onOff(settings.useIPv4))
        changed(settings.useIPv6, defaults.useIPv6, "IPv6", onOff(settings.useIPv6))
        changed(settings.securityTypes, defaults.securityTypes, "SecurityTypes", settings.securityTypes)
        changed(settings.alwaysShared, defaults.alwaysShared, "Always shared", onOff(settings.alwaysShared))
        changed(settings.neverShared, defaults.neverShared, "Never shared", onOff(settings.neverShared))
        changed(settings.disconnectClients, defaults.disconnectClients, "Disconnect clients", onOff(settings.disconnectClients))
        changed(settings.acceptKeyEvents, defaults.acceptKeyEvents, "Accept key events", onOff(settings.acceptKeyEvents))
        changed(settings.acceptPointerEvents, defaults.acceptPointerEvents, "Accept pointer events", onOff(settings.acceptPointerEvents))
        changed(settings.acceptSetDesktopSize, defaults.acceptSetDesktopSize, "Accept desktop resize", onOff(settings.acceptSetDesktopSize))
        changed(settings.acceptCutText, defaults.acceptCutText, "Accept client clipboard", onOff(settings.acceptCutText))
        changed(settings.sendCutText, defaults.sendCutText, "Send clipboard", onOff(settings.sendCutText))
        changed(settings.sendPrimary, defaults.sendPrimary, "Send primary selection", onOff(settings.sendPrimary))
        changed(settings.setPrimary, defaults.setPrimary, "Set primary selection", onOff(settings.setPrimary))
        changed(settings.maxCutText, defaults.maxCutText, "Maximum clipboard bytes", settings.maxCutText)
        changed(settings.rawKeyboard, defaults.rawKeyboard, "Raw keyboard", onOff(settings.rawKeyboard))
        changed(settings.avoidShiftNumLock, defaults.avoidShiftNumLock, "Avoid Shift/NumLock", onOff(settings.avoidShiftNumLock))
        changed(settings.remapKeys, defaults.remapKeys, "RemapKeys", settings.remapKeys)
        changed(settings.protocol33, defaults.protocol33, "Protocol 3.3", onOff(settings.protocol33))
        changed(settings.useBlacklist, defaults.useBlacklist, "Blacklist", onOff(settings.useBlacklist))
        changed(settings.blacklistThreshold, defaults.blacklistThreshold, "Blacklist threshold", settings.blacklistThreshold)
        changed(settings.blacklistTimeout, defaults.blacklistTimeout, "Blacklist timeout", settings.blacklistTimeout)
        changed(settings.queryConnect, defaults.queryConnect, "QueryConnect", onOff(settings.queryConnect))
        changed(settings.queryConnectTimeout, defaults.queryConnectTimeout, "QueryConnect timeout", settings.queryConnectTimeout)
        changed(settings.requireUsername, defaults.requireUsername, "Require username", onOff(settings.requireUsername))
        changed(settings.pamService, defaults.pamService, "PAM service", settings.pamService)
        changed(settings.plainUsers, defaults.plainUsers, "PlainUsers", settings.plainUsers)
        changed(settings.gnuTlsPriority, defaults.gnuTlsPriority, "GnuTLS priority", settings.gnuTlsPriority)
        changed(settings.x509Cert, defaults.x509Cert, "X509 certificate", settings.x509Cert)
        changed(settings.x509Key, defaults.x509Key, "X509 key", settings.x509Key)
        changed(settings.rsaKey, defaults.rsaKey, "RSA key", settings.rsaKey)
        changed(settings.idleTimeout, defaults.idleTimeout, "Idle timeout", settings.idleTimeout)
        changed(settings.maxConnectionTime, defaults.maxConnectionTime, "Max connection time", settings.maxConnectionTime)
        changed(settings.maxDisconnectionTime, defaults.maxDisconnectionTime, "Max disconnection time", settings.maxDisconnectionTime)
        changed(settings.maxIdleTime, defaults.maxIdleTime, "Max idle time", settings.maxIdleTime)
        changed(settings.frameRate, defaults.frameRate, "Frame rate", settings.frameRate)
        changed(settings.compareFb, defaults.compareFb, "CompareFB", settings.compareFb)
        changed(settings.improvedHextile, defaults.improvedHextile, "Improved Hextile", onOff(settings.improvedHextile))
        changed(settings.logSpec, defaults.logSpec, "TigerVNC Log", settings.logSpec)
        changed(settings.allowOverride, defaults.allowOverride, "AllowOverride", settings.allowOverride)
        changed(settings.rfbUnixPath, defaults.rfbUnixPath, "RFB UNIX path", settings.rfbUnixPath)
        changed(settings.rfbUnixMode, defaults.rfbUnixMode, "RFB UNIX mode", settings.rfbUnixMode)
        changed(settings.extraArguments, defaults.extraArguments, "Extra arguments", compactMultiline(settings.extraArguments))

        return out.filterNot { it.substringAfter(':').trim().isEmpty() }
    }

    private fun compactMultiline(value: String): String =
        value.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(" | ")

    private fun onOff(value: Boolean): String = if (value) "On" else "Off"

    private fun isPrivate172(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4 || parts[0] != "172") return false
        val second = parts[1].toIntOrNull() ?: return false
        return second in 16..31
    }

    private fun isIpv4Address(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            val number = part.toIntOrNull() ?: return@all false
            number in 0..255
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
