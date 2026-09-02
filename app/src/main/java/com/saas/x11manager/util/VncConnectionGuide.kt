package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        val (netMode, preferredLan) = withContext(Dispatchers.IO) {
            ContainerManager.getContainerInfo(containerName)?.netMode?.lowercase() to preferredLanAddress()
        }

        logger.i("")
        logger.i("--- VNC Quick Connection Guide ---")

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

        logAdbForwardRestartRecovery(port, logger, onlyIfTroubleshooting = false)

        logger.i("")
        logger.i("[+] VNC connection information ready")
    }

    /**
     * Explains the exact stop/restart ordering for a PC-side adb forward.
     *
     * Removing the forward clears only the host-PC ADB mapping; it does not stop
     * TigerVNC. The mapping should be recreated only after the container/VNC
     * server is listening again. This avoids stale local forwards during restart
     * troubleshooting and uses the actual per-container VNC port, never a fixed
     * 5901 assumption.
     */
    suspend fun logAdbForwardRestartRecovery(
        port: Int,
        logger: ContainerLogger?,
        onlyIfTroubleshooting: Boolean = true
    ) {
        if (logger == null) return
        logger.i("")
        if (onlyIfTroubleshooting) {
            logger.w("[USB/ADB] If this VNC start failed with a port-occupied error and you previously created an adb forward:")
        } else {
            logger.w("[USB/ADB] Important for a later container/VNC stop -> start while using USB forwarding:")
        }
        logger.i("[USB/ADB]   1) On the PC, remove the old local mapping first:")
        logger.i("[USB/ADB]      adb forward --remove tcp:$port")
        logger.i("[USB/ADB]   2) Start the container/VNC again and wait until the Manager reports VNC ready")
        logger.i("[USB/ADB]   3) Only then recreate the mapping:")
        logger.i("[USB/ADB]      adb forward tcp:$port tcp:$port")
        logger.i("[USB/ADB]   4) Connect the PC VNC client to 127.0.0.1:$port")
        logger.w("[USB/ADB] The --remove command removes only the PC-side ADB forward; it does not kill the VNC server")
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
