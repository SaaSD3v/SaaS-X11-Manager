package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Bounded host readiness gate for the NAT PulseAudio listener.
 *
 * This deliberately avoids scanning /proc/[pid]/fd for every Android process.
 * On older kernels that global walk can take an unbounded amount of time and
 * stall the graphical-start finalizer. We only inspect /proc/net/tcp plus the
 * small fd table of the current Manager PulseAudio PID.
 *
 * If 172.28.0.1:4713 is occupied by anything other than the current Manager
 * core, readiness fails closed and leaves that process untouched.
 */
object PulseAudioNatHostReadiness {
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val MANAGER_STATE = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val MANAGER_PID_FILE = "$MANAGER_STATE/pulseaudio.pid"
    private const val NAT_GATEWAY = "172.28.0.1"
    private const val NAT_PORT = 4713

    // /proc/net/tcp stores IPv4 octets little-endian. 172.28.0.1:4713.
    private const val PROC_LOCAL = "01001CAC:1269"

    private data class ListenerSocket(
        val inode: String,
        val uid: Int?
    )

    suspend fun prepare(logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        val termuxUid = termuxUid()
        if (termuxUid == null) {
            logger?.w("[PA-NAT-HOST] Termux UID could not be resolved")
            return@withContext false
        }

        var gatewayReady = false
        for (attempt in 0 until 30) {
            if (hostHasGateway()) {
                gatewayReady = true
                break
            }
            delay(100)
        }
        if (!gatewayReady) {
            logger?.w("[PA-NAT-HOST] $NAT_GATEWAY is not present on the Android host")
            return@withContext false
        }
        logger?.i("[PA-NAT-HOST] Android owns $NAT_GATEWAY")

        val currentPid = currentManagerCorePid()
        logger?.i("[PA-NAT-HOST] current Manager PulseAudio PID=${currentPid ?: "unknown"}")

        // Give a just-terminated previous Manager core a short bounded grace
        // period before testing the TCP table. This replaces the old global
        // /proc fd walk and cannot block indefinitely.
        delay(250)

        val listener = listenerSocket()
        if (listener == null) {
            logger?.i("[PA-NAT-HOST] $NAT_GATEWAY:$NAT_PORT is free before listener load")
            return@withContext true
        }

        logger?.w(
            "[PA-NAT-HOST] $NAT_GATEWAY:$NAT_PORT already LISTENs " +
                "inode=${listener.inode} uid=${listener.uid ?: "unknown"}"
        )

        if (currentPid != null && processOwnsSocket(currentPid, listener.inode)) {
            logger?.i("[PA-NAT-HOST] Listener belongs to the current Manager audio core PID=$currentPid")
            return@withContext true
        }

        if (listener.uid != null && listener.uid != termuxUid) {
            logger?.w(
                "[PA-NAT-HOST] Listener UID ${listener.uid} is not the Termux UID $termuxUid; " +
                    "it will not be modified"
            )
        } else {
            logger?.w(
                "[PA-NAT-HOST] Listener is not owned by the current Manager core; " +
                    "global process scanning is intentionally disabled and nothing will be killed"
            )
        }
        false
    }

    private fun termuxUid(): Int? {
        val command = """
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid" in ''|*[!0-9]*) exit 1 ;; esac
            printf '%s\n' "${'$'}uid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null else result.out.firstOrNull()?.trim()?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun hostHasGateway(): Boolean {
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            if [ -x /system/bin/ip ]; then
                /system/bin/ip -4 -o addr show 2>/dev/null
            elif [ -x ${q(busybox)} ]; then
                ${q(busybox)} ip -4 -o addr show 2>/dev/null
            else
                ip -4 -o addr show 2>/dev/null || true
            fi | grep -Eq '[[:space:]]inet[[:space:]]${NAT_GATEWAY.replace(".", "\\.")}/'
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun currentManagerCorePid(): Int? {
        val command = """
            pf=${q(MANAGER_PID_FILE)}
            [ -f "${'$'}pf" ] || exit 1
            pid=${'$'}(sed -n 's/^pid=//p' "${'$'}pf" 2>/dev/null | sed -n '1p')
            case "${'$'}pid" in ''|*[!0-9]*) exit 1 ;; esac
            kill -0 "${'$'}pid" 2>/dev/null || exit 1
            printf '%s\n' "${'$'}pid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null else result.out.firstOrNull()?.trim()?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun listenerSocket(): ListenerSocket? {
        val command = """
            while read sl local remote state tx tr retr uid timeout ino rest; do
                [ "${'$'}local" = ${q(PROC_LOCAL)} ] || continue
                [ "${'$'}state" = 0A ] || continue
                printf '%s|%s\n' "${'$'}ino" "${'$'}uid"
                exit 0
            done < /proc/net/tcp 2>/dev/null
            exit 1
        """.trimIndent()

        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) return null
            val parts = result.out.firstOrNull()?.trim().orEmpty().split('|', limit = 2)
            val inode = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            ListenerSocket(
                inode = inode,
                uid = parts.getOrNull(1)?.toIntOrNull()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun processOwnsSocket(pid: Int, inode: String): Boolean {
        val command = """
            [ -d /proc/$pid/fd ] || exit 1
            for fd in /proc/$pid/fd/*; do
                [ -e "${'$'}fd" ] || continue
                link=${'$'}(readlink "${'$'}fd" 2>/dev/null || true)
                [ "${'$'}link" = ${q("socket:[$inode]")} ] && exit 0
            done
            exit 1
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
