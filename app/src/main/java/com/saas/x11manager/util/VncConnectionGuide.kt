package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell

/**
 * Final user-facing connection hint printed after a successful VNC start.
 *
 * The VNC server itself is managed by [VncServerManager]. This helper only
 * explains the two practical client paths: direct LAN access and USB/ADB
 * forwarding from a computer.
 */
object VncConnectionGuide {
    suspend fun logAfterSuccessfulStart(
        containerName: String,
        port: Int,
        password: String?,
        logger: ContainerLogger?
    ) {
        if (logger == null) return

        logger.i("")
        logger.i("--- VNC Quick Connection Guide ---")

        val netMode = ContainerManager.getContainerInfo(containerName)?.netMode?.lowercase()
        val preferredLan = preferredLanAddress()
        if (preferredLan == null) {
            logger.w("[LAN] Android LAN/Wi-Fi IPv4 address could not be detected")
        } else if (netMode == "host") {
            logger.i("[LAN] Recommended network address: $preferredLan:$port")
            logger.i("[LAN] Use this address from a VNC client on the same reachable network")
        } else {
            logger.i("[LAN] Android network address: $preferredLan:$port")
            logger.w(
                "[LAN] Container network mode is ${netMode ?: "unknown"}; direct LAN access may require DroidSpaces port forwarding"
            )
        }

        if (password != null) {
            logger.w("[AUTH] VNC password entered for this start: $password")
            logger.w("[AUTH] This password is intentionally visible in this terminal log")
        } else {
            logger.i("[AUTH] VNC password: already configured inside the container")
            logger.i("[AUTH] The Manager does not store the plaintext password, so it cannot display it on later starts")
        }

        logger.i("")
        logger.i("[USB/ADB] USB access is also possible with adb forward")
        logger.i("[USB/ADB] Use adb forward (PC -> Android), not adb reverse")
        logger.i("[USB/ADB] Example using the default VNC port ${VncSettings.DEFAULT_PORT}:")
        logger.i("[USB/ADB]   adb forward tcp:${VncSettings.DEFAULT_PORT} tcp:${VncSettings.DEFAULT_PORT}")
        logger.i("[USB/ADB]   Then connect the PC VNC client to 127.0.0.1:${VncSettings.DEFAULT_PORT}")
        logger.i("[USB/ADB] The ${VncSettings.DEFAULT_PORT} command above is only an example using the default port")

        if (port != VncSettings.DEFAULT_PORT) {
            logger.i("[USB/ADB] Your currently configured VNC port is $port, so use:")
            logger.i("[USB/ADB]   adb forward tcp:$port tcp:$port")
            logger.i("[USB/ADB]   Then connect the PC VNC client to 127.0.0.1:$port")
        }

        logger.i("")
        logger.i("[+] VNC connection information ready")
    }

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
}
