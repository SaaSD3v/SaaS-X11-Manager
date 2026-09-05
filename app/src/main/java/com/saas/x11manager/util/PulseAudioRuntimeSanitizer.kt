package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One-time/runtime ownership guard for the physically validated v3.2 HOST+NAT
 * audio architecture.
 *
 * The audio transports intentionally keep one long-lived Termux PulseAudio core.
 * That makes ownership validation important: a control socket can continue to
 * answer `pactl info` even after an old build or a separately-run v3.2 script left
 * another Android-audio core alive. Two AAudio/OpenSL ES cores can then contend
 * only when a real stream begins, producing the misleading state where the sink
 * probes as ready but media playback stalls.
 *
 * This guard never scans arbitrary processes and never touches container/X11/VNC
 * lifecycle. It only stops processes positively identified by our own pid/state
 * files, exact Termux UID, process start token, owner marker, control socket and
 * authentication-cookie path.
 */
internal object PulseAudioRuntimeSanitizer {
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val MANAGER_STATE = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val MANAGER_PID = "$MANAGER_STATE/pulseaudio.pid"
    private const val MANAGER_SOCKET = "$MANAGER_STATE/control.sock"
    private const val MANAGER_COOKIE = "$MANAGER_STATE/transport.cookie"
    private const val MANAGER_OWNER = "SaaS X11 Manager Audio Configuration"

    private const val SCRIPT_HOSTNAT = "$TERMUX_HOME/.saas-droidspaces-audio-hostnat/host"
    private const val SCRIPT_HOSTNAT_OWNER = "SaaS DroidSpaces Audio HostNAT"
    private const val SCRIPT_NETLAB = "$TERMUX_HOME/.saas-droidspaces-audio-netlab/host"
    private const val SCRIPT_NETLAB_OWNER = "SaaS DroidSpaces Audio NetLab"

    private const val GENERATION_FILE = "$MANAGER_STATE/runtime-generation"

    // Bump only when an APK must force one clean recreation of the Manager core.
    // The previous builds reused a control-socket-only health check indefinitely.
    internal const val RUNTIME_GENERATION = "v3.2-physical-baseline-reset-1"

    suspend fun prepare(
        containerName: String,
        logger: ContainerLogger? = null
    ) = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName) &&
            !FixSettings.isPulseAudioApplied(context, containerName)
        ) {
            return@withContext
        }

        val uid = termuxUid() ?: return@withContext

        var competingCoreStopped = false
        if (stopOwnedCore(
                uid = uid,
                baseDir = SCRIPT_HOSTNAT,
                owner = SCRIPT_HOSTNAT_OWNER
            )
        ) {
            competingCoreStopped = true
            logger?.i("[*] Retired the separately-run v3.2 audio core before Manager takeover")
        }
        if (stopOwnedCore(
                uid = uid,
                baseDir = SCRIPT_NETLAB,
                owner = SCRIPT_NETLAB_OWNER
            )
        ) {
            competingCoreStopped = true
            logger?.i("[*] Retired the previous NetLab audio core before Manager takeover")
        }

        val generation = readGeneration()
        var managerCoreStopped = false
        if (generation != RUNTIME_GENERATION) {
            managerCoreStopped = stopOwnedManagerCore(uid)
            if (managerCoreStopped) {
                logger?.i("[*] Recreating the Manager audio core from the validated v3.2 baseline")
            }
            writeGeneration()
        }

        if (competingCoreStopped || managerCoreStopped) {
            // Give Android audio services a short deterministic hand-off window before
            // PulseAudioFixManager starts/reuses the single surviving core.
            delay(300)
        }
    }

    internal fun generationMatches(value: String?): Boolean =
        value?.trim() == RUNTIME_GENERATION

    private fun termuxUid(): Int? {
        val command =
            "uid=\$(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || " +
                "toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null); " +
                "case \"\$uid\" in ''|*[!0-9]*) exit 1 ;; esac; printf '%s\\n' \"\$uid\""
        return try {
            val result = Shell.cmd(command).exec()
            if (result.isSuccess) {
                result.out.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readGeneration(): String? = try {
        Shell.cmd("cat ${q(GENERATION_FILE)} 2>/dev/null").exec()
            .out
            .firstOrNull()
            ?.trim()
    } catch (_: Exception) {
        null
    }

    private fun writeGeneration() {
        try {
            Shell.cmd(
                "mkdir -p ${q(MANAGER_STATE)} && " +
                    "printf '%s\\n' ${q(RUNTIME_GENERATION)} > ${q(GENERATION_FILE)} && " +
                    "chmod 600 ${q(GENERATION_FILE)}"
            ).exec()
        } catch (_: Exception) {
            // Failure to persist the marker is safe: the next start simply attempts
            // the same strongly-owned refresh again.
        }
    }

    private fun stopOwnedManagerCore(uid: Int): Boolean {
        val command = ownedStopCommand(
            uid = uid,
            pidFile = MANAGER_PID,
            owner = MANAGER_OWNER,
            controlSocket = MANAGER_SOCKET,
            cookie = MANAGER_COOKIE
        )
        val stopped = execBoolean(command)
        if (stopped) {
            try {
                Shell.cmd(
                    "rm -f ${q(MANAGER_PID)} ${q(MANAGER_SOCKET)} 2>/dev/null || true"
                ).exec()
            } catch (_: Exception) {
            }
        }
        return stopped
    }

    private fun stopOwnedCore(uid: Int, baseDir: String, owner: String): Boolean {
        val pidFile = "$baseDir/pulseaudio.pid"
        val controlSocket = "$baseDir/control.sock"
        val cookie = "$baseDir/transport.cookie"
        val stopped = execBoolean(
            ownedStopCommand(
                uid = uid,
                pidFile = pidFile,
                owner = owner,
                controlSocket = controlSocket,
                cookie = cookie
            )
        )
        if (stopped) {
            try {
                Shell.cmd("rm -f ${q(controlSocket)} 2>/dev/null || true").exec()
            } catch (_: Exception) {
            }
        }
        return stopped
    }

    /**
     * Exit 0 only when an owned live process was positively identified and stopped.
     * Exit 1 for missing/stale/foreign state so callers never kill an unknown PID.
     */
    private fun ownedStopCommand(
        uid: Int,
        pidFile: String,
        owner: String,
        controlSocket: String,
        cookie: String
    ): String = """
        pf=${q(pidFile)}
        [ -f "${'$'}pf" ] || exit 1
        pid=${'$'}(sed -n 's/^pid=//p' "${'$'}pf" 2>/dev/null | sed -n '1p')
        [ -n "${'$'}pid" ] || pid=${'$'}(sed -n '1p' "${'$'}pf" 2>/dev/null || true)
        start=${'$'}(sed -n 's/^start=//p' "${'$'}pf" 2>/dev/null | sed -n '1p')
        state_owner=${'$'}(sed -n 's/^owner=//p' "${'$'}pf" 2>/dev/null | sed -n '1p')
        case "${'$'}pid" in ''|*[!0-9]*) exit 1 ;; esac
        [ "${'$'}state_owner" = ${q(owner)} ] || exit 1
        kill -0 "${'$'}pid" 2>/dev/null || exit 1
        [ -r "/proc/${'$'}pid/status" ] && [ -r "/proc/${'$'}pid/cmdline" ] || exit 1
        actual_uid=${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/${'$'}pid/status" | sed -n '1p')
        [ "${'$'}actual_uid" = "$uid" ] || exit 1
        if [ -n "${'$'}start" ] && [ -r "/proc/${'$'}pid/stat" ]; then
            actual_start=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}pid/stat" 2>/dev/null || true)
            [ -n "${'$'}actual_start" ] && [ "${'$'}actual_start" = "${'$'}start" ] || exit 1
        fi
        cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true)
        case " ${'$'}cmd " in *pulseaudio*) : ;; *) exit 1 ;; esac
        case " ${'$'}cmd " in *module-native-protocol-unix*) : ;; *) exit 1 ;; esac
        case " ${'$'}cmd " in *socket=$controlSocket*) : ;; *) exit 1 ;; esac
        case " ${'$'}cmd " in *auth-cookie=$cookie*) : ;; *) exit 1 ;; esac
        kill "${'$'}pid" 2>/dev/null || exit 1
        i=0
        while [ "${'$'}i" -lt 30 ] && kill -0 "${'$'}pid" 2>/dev/null; do
            i=${'$'}((i + 1)); sleep 0.1
        done
        if kill -0 "${'$'}pid" 2>/dev/null; then
            kill -9 "${'$'}pid" 2>/dev/null || exit 1
        fi
        exit 0
    """.trimIndent()

    private fun execBoolean(command: String): Boolean = try {
        Shell.cmd(command).exec().isSuccess
    } catch (_: Exception) {
        false
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
