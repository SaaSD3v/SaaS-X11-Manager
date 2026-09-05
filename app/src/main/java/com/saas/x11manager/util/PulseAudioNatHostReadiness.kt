package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Read-only/targeted host readiness gate for the NAT PulseAudio listener.
 *
 * It waits for DroidSpaces' canonical 172.28.0.1 endpoint and detects an
 * existing 172.28.0.1:4713 listener before PulseAudioNatPreflight tries to load
 * module-native-protocol-tcp. It may terminate a listener owner only when that
 * process is positively proven to be a stale X11 Manager PulseAudio core:
 * same Termux UID, pulseaudio cmdline, Manager-private HOME, and not the PID in
 * the current Manager core pid file. Unrelated listeners are never modified.
 */
object PulseAudioNatHostReadiness {
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val MANAGER_STATE = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val MANAGER_PULSE_HOME = "$MANAGER_STATE/pulse-home"
    private const val MANAGER_PID_FILE = "$MANAGER_STATE/pulseaudio.pid"
    private const val NAT_GATEWAY = "172.28.0.1"
    private const val NAT_PORT = 4713

    // /proc/net/tcp stores IPv4 octets little-endian. 172.28.0.1:4713.
    private const val PROC_LOCAL = "01001CAC:1269"

    private data class ListenerOwner(
        val inode: String,
        val pid: Int?,
        val uid: Int?,
        val command: String
    )

    suspend fun prepare(logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        val termuxUid = termuxUid()
        if (termuxUid == null) {
            logger?.w("[PA-NAT-HOST] Termux UID could not be resolved")
            return@withContext false
        }

        var gatewayReady = false
        repeat(30) {
            if (hostHasGateway()) {
                gatewayReady = true
                return@repeat
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

        val owner = listenerOwner()
        if (owner == null) {
            logger?.i("[PA-NAT-HOST] $NAT_GATEWAY:$NAT_PORT is free before listener load")
            return@withContext true
        }

        logger?.w(
            "[PA-NAT-HOST] $NAT_GATEWAY:$NAT_PORT already LISTENs " +
                "inode=${owner.inode} pid=${owner.pid ?: "unknown"} uid=${owner.uid ?: "unknown"}"
        )
        if (owner.command.isNotBlank()) {
            logger?.w("[PA-NAT-HOST] listener cmd=${owner.command.take(300)}")
        }

        if (owner.pid != null && owner.pid == currentPid) {
            logger?.i("[PA-NAT-HOST] Listener belongs to the current Manager audio core")
            return@withContext true
        }

        val stalePid = owner.pid
        if (stalePid == null || !isOwnedStaleManagerCore(stalePid, termuxUid, currentPid)) {
            logger?.w("[PA-NAT-HOST] Existing listener is not proven stale Manager state; it will not be modified")
            return@withContext false
        }

        logger?.w("[PA-NAT-HOST] Releasing stale Manager PulseAudio core PID=$stalePid")
        try {
            Shell.cmd("kill $stalePid 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }

        repeat(40) {
            val live = pidAlive(stalePid)
            val stillListening = listenerOwner()?.pid == stalePid
            if (!live && !stillListening) {
                logger?.i("[PA-NAT-HOST] Stale Manager listener released")
                return@withContext true
            }
            delay(100)
        }

        logger?.w("[PA-NAT-HOST] Stale Manager core did not release $NAT_GATEWAY:$NAT_PORT")
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

    private fun listenerOwner(): ListenerOwner? {
        val command = """
            inode=''
            while read sl local remote state tx tr retr uid timeout ino rest; do
                [ "${'$'}local" = ${q(PROC_LOCAL)} ] || continue
                [ "${'$'}state" = 0A ] || continue
                inode="${'$'}ino"
                break
            done < /proc/net/tcp 2>/dev/null
            [ -n "${'$'}inode" ] || exit 1

            owner_pid=''; owner_uid=''; owner_cmd=''
            for fd in /proc/[0-9]*/fd/*; do
                [ -e "${'$'}fd" ] || continue
                link=${'$'}(readlink "${'$'}fd" 2>/dev/null || true)
                [ "${'$'}link" = "socket:[${'$'}inode]" ] || continue
                p=${'$'}{fd#/proc/}; p=${'$'}{p%%/*}
                case "${'$'}p" in ''|*[!0-9]*) continue ;; esac
                owner_pid="${'$'}p"
                owner_uid=${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/${'$'}p/status" 2>/dev/null | sed -n '1p')
                owner_cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}p/cmdline" 2>/dev/null || true)
                break
            done
            printf '%s|%s|%s|%s\n' "${'$'}inode" "${'$'}owner_pid" "${'$'}owner_uid" "${'$'}owner_cmd"
        """.trimIndent()

        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) return null
            val line = result.out.firstOrNull()?.trim().orEmpty()
            val parts = line.split('|', limit = 4)
            if (parts.isEmpty() || parts[0].isBlank()) null
            else ListenerOwner(
                inode = parts[0],
                pid = parts.getOrNull(1)?.toIntOrNull(),
                uid = parts.getOrNull(2)?.toIntOrNull(),
                command = parts.getOrNull(3).orEmpty()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isOwnedStaleManagerCore(pid: Int, termuxUid: Int, currentPid: Int?): Boolean {
        if (pid == currentPid) return false
        val command = """
            pid=$pid
            [ -r "/proc/${'$'}pid/status" ] || exit 1
            [ -r "/proc/${'$'}pid/cmdline" ] || exit 1
            [ -r "/proc/${'$'}pid/environ" ] || exit 1
            uid=${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/${'$'}pid/status" | sed -n '1p')
            [ "${'$'}uid" = $termuxUid ] || exit 2
            cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true)
            case "${'$'}cmd" in *pulseaudio*) : ;; *) exit 3 ;; esac
            tr '\000' '\n' < "/proc/${'$'}pid/environ" 2>/dev/null | grep -Fxq ${q("HOME=$MANAGER_PULSE_HOME")} || exit 4
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun pidAlive(pid: Int): Boolean = try {
        Shell.cmd("kill -0 $pid 2>/dev/null").exec().isSuccess
    } catch (_: Exception) {
        false
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
