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
    const val ACTIVE_SUMMARY_BEGIN = "[VNC] Active VNC session"
    const val ACTIVE_SUMMARY_END = "[VNC] ✓ Connection details pinned until this session ends"

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
            val containerIpv4 = containerIpv4Addresses(containerName)
            RuntimeConnectionSnapshot(
                netMode = info?.netMode?.trim()?.lowercase(),
                containerIpv4 = containerIpv4,
                preferredHost = preferredHostAddress(containerIpv4.toSet()),
                androidHostPortListening = isAndroidHostPortListening(port),
                settings = settings
            )
        }

        logger.i(LogLayout.SPACER)
        logger.i(ACTIVE_SUMMARY_BEGIN)
        logger.i("[VNC] ✓ Standalone VNC is ready")
        logger.i("[CONTAINER] • Container: $containerName")
        logger.i("[USER] • Desktop user: $desktopUser")
        logger.i("[SESSION] • Desktop: ${session.label}")
        displayName?.let { logger.i("[VNC] • Virtual X display: $it") }

        logger.i(LogLayout.SPACER)
        logger.i("[VNC] Connection")
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
        logger.i("[VNC] • Container network mode: ${runtime.netMode ?: "unknown"}")

        if (runtime.containerIpv4.isEmpty()) {
            logger.w("[VNC] ! Container IPv4 address could not be resolved")
        } else {
            runtime.containerIpv4.forEach { ip ->
                logger.i("[VNC] • Container endpoint: $ip:$port")
            }
        }

        val host = runtime.preferredHost
        if (host == null) {
            logger.w("[VNC] ! Android host LAN/Wi-Fi IPv4 address could not be confirmed")
        } else {
            logger.i("[VNC] • Android host IPv4 (${host.interfaceName}): ${host.address}")
        }

        when {
            runtime.settings.localhostOnly -> {
                logger.i("[VNC] • Android host TCP $port: ${confirmed(runtime.androidHostPortListening)}")
                logger.w("[VNC] ! Direct LAN VNC is disabled because Localhost only is enabled")
            }
            runtime.androidHostPortListening && host != null -> {
                logger.i("[VNC] • Android host TCP $port: confirmed listening")
                logger.i("[VNC] • LAN endpoint: ${host.address}:$port")
            }
            runtime.androidHostPortListening -> {
                logger.i("[VNC] • Android host TCP $port: confirmed listening")
                logger.w("[VNC] ! No Android LAN address was confirmed for a direct LAN endpoint")
            }
            else -> {
                logger.i("[VNC] • Android host TCP $port: not confirmed")
                if (runtime.netMode == "host") {
                    logger.w("[VNC] ! Host networking is configured, but this TCP listener was not confirmed on Android")
                } else {
                    logger.w("[VNC] ! Direct LAN access needs a real DroidSpaces host-port publication")
                }
            }
        }

        logger.i(LogLayout.SPACER)
        logger.i("[VNC] USB / ADB")
        logger.i("[VNC] • PC local port: $effectiveLocalPort")
        if (runtime.androidHostPortListening) {
            logger.i("[VNC] • Android target: 127.0.0.1:$port")
            logger.i("[VNC] • Run on the PC:")
            logger.i("[VNC]   adb forward tcp:$effectiveLocalPort tcp:$port")
            logger.i("[VNC] • Then connect the VNC client to:")
            logger.i("[VNC]   127.0.0.1:$effectiveLocalPort")
            logger.i("[VNC] • The Manager does not create the PC-side ADB mapping automatically")
        } else {
            logger.w("[VNC] ! Android-side VNC TCP $port is not confirmed, so no exact ADB target is advertised")
            logger.i("[VNC] • When DroidSpaces publishes VNC on Android as <hostPort>, run:")
            logger.i("[VNC]   adb forward tcp:$effectiveLocalPort tcp:<hostPort>")
            logger.i("[VNC] • Then connect the VNC client to:")
            logger.i("[VNC]   127.0.0.1:$effectiveLocalPort")
        }

        logger.i(LogLayout.SPACER)
        logger.i("[VNC] Authentication")
        if (password != null) {
            logger.i("[VNC] • Mode: VNC password")
            logger.i("[VNC] • Password: $password")
            logger.i("[VNC] • Plaintext lifetime: this in-memory active-session log only")
        } else {
            logger.i("[VNC] • Mode: None")
            logger.i("[VNC] • Password: none")
            logger.w("[VNC] ! Any client that can reach this VNC port may connect")
        }

        logger.i(LogLayout.SPACER)
        logger.i("[VNC] Advanced settings")
        val overrides = effectiveNonDefaultSettings(runtime.settings, passwordEnabled = password != null)
        if (overrides.isEmpty()) {
            logger.i("[VNC] • Overrides: none")
        } else {
            overrides.forEach { value -> logger.i("[VNC] • $value") }
        }

        logger.i(LogLayout.SPACER)
        logger.i(ACTIVE_SUMMARY_END)
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
        logger.i(LogLayout.SPACER)
        logger.i("[VNC] USB / ADB recovery")
        if (onlyIfTroubleshooting) {
            logger.w("[VNC] ! A previous PC-side ADB mapping may need to be removed before retrying")
        } else {
            logger.w("[VNC] ! Recreate a PC-side ADB mapping only after the VNC server is ready")
        }
        logger.i("[VNC] • Remove the old PC mapping:")
        logger.i("[VNC]   adb forward --remove tcp:$effectiveLocalPort")
        logger.i("[VNC] • Start VNC again and wait for the ready confirmation")
        logger.i("[VNC] • If Android host TCP $port is published and listening, recreate it with:")
        logger.i("[VNC]   adb forward tcp:$effectiveLocalPort tcp:$port")
        logger.i("[VNC] • USB client endpoint:")
        logger.i("[VNC]   127.0.0.1:$effectiveLocalPort")
        logger.w("[VNC] ! Removing an ADB forward changes only the PC-side mapping; it does not stop TigerVNC")
    }

    private data class RuntimeConnectionSnapshot(
        val netMode: String?,
        val containerIpv4: List<String>,
        val preferredHost: HostIpv4?,
        val androidHostPortListening: Boolean,
        val settings: VncLaunchSettings
    )

    private data class HostIpv4(
        val interfaceName: String,
        val address: String
    )

    private fun preferredHostAddress(excludedAddresses: Set<String>): HostIpv4? {
        val addresses = androidHostIpv4Addresses()
            .filterNot { it.address in excludedAddresses }
        if (addresses.isEmpty()) return null

        return addresses.minWithOrNull(
            compareBy<HostIpv4> { lanPriority(it) }
                .thenBy { it.interfaceName }
                .thenBy { it.address }
        )
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
            iface.startsWith("rmnet") -> 3
            iface.startsWith("br") || iface.startsWith("veth") || iface.startsWith("docker") -> 8
            ip.startsWith("192.168.") -> 4
            ip.startsWith("10.") -> 5
            isPrivate172(ip) -> 6
            else -> 7
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

    private fun effectiveNonDefaultSettings(
        settings: VncLaunchSettings,
        passwordEnabled: Boolean
    ): List<String> {
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
        if (settings.securityTypes != defaults.securityTypes) {
            out += if (passwordEnabled) {
                "SecurityTypes: ${settings.securityTypes}"
            } else {
                "SecurityTypes: ${settings.securityTypes} (saved; passwordless Start uses None)"
            }
        }
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

    private fun confirmed(value: Boolean): String = if (value) "confirmed listening" else "not confirmed"

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
