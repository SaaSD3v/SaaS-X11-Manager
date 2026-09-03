package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class PulseAudioFixResult(
    val success: Boolean,
    val message: String,
    val details: List<String> = emptyList()
)

/**
 * Manager-owned Android audio for DroidSpaces HOST and NAT containers.
 *
 * Physically validated transport baseline:
 * - HOST -> 127.0.0.1:<dynamic port>
 * - NAT  -> discovered Android-side NAT gateway:<dynamic port>
 * - both -> authenticated TCP listener -> private UNIX PulseAudio core
 *           -> AAudio/OpenSL ES -> Android audio
 *
 * Lifecycle contract:
 * - prepareBeforeGraphicalStart() only prepares the host audio core and saves
 *   enable_pulseaudio=0 for future DroidSpaces starts.
 * - finalizeAfterContainerReady() resolves the live network endpoint, selects a
 *   safe port, exposes an authenticated listener and configures the already
 *   running container client.
 * - this object never starts/stops/restarts a container or X11/VNC.
 */
object PulseAudioFixManager {
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val BASE_AUDIO_PORT = 4713
    private const val MAX_PORT_SHIFT = 64

    private const val MANAGED = "SaaS X11 Manager Audio Configuration"
    private const val LEGACY_MANAGED = "SaaS X11 Manager PulseAudio Fix"
    private const val SCRIPT_MANAGED = "SaaS DroidSpaces Audio HostNAT"
    private const val NETLAB_MANAGED = "SaaS DroidSpaces Audio NetLab"
    private const val SCRIPT_LEGACY_MANAGED = "SaaS DroidSpaces Audio Auto"
    private const val BEGIN = "# BEGIN $MANAGED"
    private const val END = "# END $MANAGED"

    private const val HOST_STATE_DIR = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val HOST_PULSE_HOME = "$HOST_STATE_DIR/pulse-home"
    private const val HOST_PULSE_CONFIG = "$HOST_PULSE_HOME/.config/pulse"
    private const val HOST_PULSE_RUNTIME = "$HOST_STATE_DIR/runtime"
    private const val HOST_PULSE_STATE = "$HOST_STATE_DIR/state"
    private const val HOST_COOKIE = "$HOST_STATE_DIR/transport.cookie"
    private const val HOST_CONTROL_SOCKET = "$HOST_STATE_DIR/control.sock"
    private const val HOST_LISTENERS_DIR = "$HOST_STATE_DIR/listeners"
    private const val HOST_PID_FILE = "$HOST_STATE_DIR/pulseaudio.pid"
    private const val HOST_LOG_FILE = "$HOST_STATE_DIR/pulseaudio.log"

    private const val SCRIPT_HOSTNAT_DIR = "$TERMUX_HOME/.saas-droidspaces-audio-hostnat"
    private const val SCRIPT_NETLAB_DIR = "$TERMUX_HOME/.saas-droidspaces-audio-netlab"
    private const val OLD_MANAGER_PULSE_HOME = "$HOST_STATE_DIR/host-pulse-home"

    private data class TermuxRuntime(val uid: Int)
    private data class HostRuntime(val sink: String)
    private data class Transport(val serverIp: String, val port: Int, val sink: String) {
        val server: String get() = "tcp:$serverIp:$port"
    }

    suspend fun prepareBeforeGraphicalStart(
        containerName: String,
        logger: ContainerLogger? = null
    ): PulseAudioFixResult? = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        val requested = FixSettings.isPulseAudioEnabled(context, containerName)
        val previouslyApplied = FixSettings.isPulseAudioApplied(context, containerName)
        if (!requested && !previouslyApplied) return@withContext null

        logger?.i("--- Audio Configuration ---")

        val info = ContainerManager.getContainerInfo(containerName)
            ?: return@withContext failure(logger, "Container $containerName was not found")

        if (!requested) {
            val restored = restoreContainerConfig(context, info)
            val cleaned = removeOfflineClientFiles(info)
            if (restored && cleaned) {
                FixSettings.clearPulseAudioRuntimeState(context, containerName)
                logger?.i("[+] Audio configuration disabled for $containerName")
                return@withContext PulseAudioFixResult(true, "Audio configuration disabled")
            }
            return@withContext failure(logger, "Audio configuration cleanup was not fully completed")
        }

        if (!isSupportedNetwork(info)) {
            return@withContext failure(
                logger,
                "Audio configuration supports net_mode=host and net_mode=nat only (current: ${info.netMode})"
            )
        }

        val runtime = detectTermuxRuntime()
            ?: return@withContext failure(logger, "Termux was not detected")

        val original = FixSettings.getPulseAudioOriginalState(context, containerName)
            ?: migratedOriginalPulseState(runtime, containerName)
            ?: readPulseState(info.configPath)
        if (original !in setOf("ABSENT", "ON", "OFF")) {
            return@withContext failure(logger, "Could not read the original DroidSpaces PulseAudio setting")
        }
        if (FixSettings.getPulseAudioOriginalState(context, containerName) == null &&
            !FixSettings.setPulseAudioOriginalState(context, containerName, original)
        ) {
            return@withContext failure(logger, "Could not save the original DroidSpaces PulseAudio setting")
        }

        // The Manager transport is independent from DroidSpaces native audio.
        // This only affects future container starts; no runtime lifecycle action is performed here.
        if (!setPulseState(info.configPath, enabled = false)) {
            return@withContext failure(logger, "Could not disable DroidSpaces native PulseAudio for future starts")
        }

        if (!FixSettings.setPulseAudioApplied(context, containerName, true)) {
            restoreContainerConfig(context, info)
            return@withContext failure(logger, "Could not save audio configuration state")
        }

        val host = ensureManagerHostRuntime(runtime, logger)
        if (host == null) {
            restoreContainerConfig(context, info)
            FixSettings.clearPulseAudioRuntimeState(context, containerName)
            return@withContext failure(logger, "Manager-owned Android audio core could not be started")
        }

        logger?.i("[+] Host audio core ready (${host.sink}, private UNIX control socket)")
        PulseAudioFixResult(true, "Host audio core ready (${host.sink})")
    }

    /**
     * Called only after the normal X11/VNC path has made the container runnable.
     * Resolves HOST/NAT transport from the live runtime and configures the
     * existing container as a persistent authenticated PulseAudio client.
     */
    suspend fun finalizeAfterContainerReady(
        containerName: String,
        logger: ContainerLogger? = null
    ): PulseAudioFixResult? = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext null

        val info = ContainerManager.getContainerInfo(containerName)
            ?: return@withContext failure(logger, "Container $containerName was not found")
        if (!isSupportedNetwork(info)) {
            return@withContext failure(
                logger,
                "Audio configuration supports net_mode=host and net_mode=nat only (current: ${info.netMode})"
            )
        }
        if (!info.isRunning) {
            return@withContext failure(logger, "Container $containerName is not running; audio client was not changed")
        }

        val runtime = detectTermuxRuntime()
            ?: return@withContext failure(logger, "Termux was not detected")
        val host = ensureManagerHostRuntime(runtime, logger)
            ?: return@withContext failure(logger, "Manager-owned Android audio core is unavailable")

        val endpoint = resolveEndpoint(info, logger)
            ?: return@withContext failure(logger, "Automatic HOST/NAT audio endpoint discovery failed")
        logger?.i("[CTX] Audio net_mode: ${normalizedNetwork(info)}")
        logger?.i("[CTX] Audio host endpoint: $endpoint (port selected automatically)")

        val port = selectAndEnsureListener(runtime, endpoint, logger)
            ?: return@withContext failure(logger, "Could not create a usable authenticated audio listener")
        val transport = Transport(endpoint, port, host.sink)

        if (!verifyContainerClient(containerName, transport.server)) {
            if (!installAndVerifyContainerClient(runtime, containerName, transport.server, logger)) {
                return@withContext failure(logger, "Container audio client configuration failed")
            }
        } else {
            logger?.i("[+] Persistent container audio client already configured")
        }

        FixSettings.setPulseAudioApplied(context, containerName, true)
        logger?.i("[+] Audio ready (${transport.sink}, ${transport.server})")
        PulseAudioFixResult(true, "Audio ready (${transport.sink}, ${transport.server})")
    }

    private fun normalizedNetwork(info: ContainerInfo): String = info.netMode.trim().lowercase()
    private fun isSupportedNetwork(info: ContainerInfo): Boolean = normalizedNetwork(info) in setOf("host", "nat")

    private suspend fun failure(logger: ContainerLogger?, message: String): PulseAudioFixResult {
        logger?.w("[!] $message")
        logger?.w("[!] Graphical startup will continue")
        return PulseAudioFixResult(false, message)
    }

    private fun detectTermuxRuntime(): TermuxRuntime? {
        val command =
            "test -x ${shellQuote("$TERMUX_PREFIX/bin/pkg")} && " +
                "test -x ${shellQuote("$TERMUX_PREFIX/bin/sh")} && " +
                "uid=\$(stat -c '%u' ${shellQuote(TERMUX_HOME)} 2>/dev/null || " +
                "toybox stat -c '%u' ${shellQuote(TERMUX_HOME)} 2>/dev/null); " +
                "case \"\$uid\" in ''|*[!0-9]*) exit 1 ;; esac; printf '%s\\n' \"\$uid\""
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let(::TermuxRuntime)
        } catch (_: Exception) {
            null
        }
    }

    private fun wrapTermuxCommand(command: String): String = buildString {
        append("export HOME=").append(shellQuote(TERMUX_HOME)).append("; ")
        append("export PREFIX=").append(shellQuote(TERMUX_PREFIX)).append("; ")
        append("export TMPDIR=").append(shellQuote("$TERMUX_PREFIX/tmp")).append("; ")
        append("export PATH=").append(shellQuote("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")).append("; ")
        append(command)
    }

    private fun runAsTermux(runtime: TermuxRuntime, command: String): Boolean = try {
        Shell.cmd("su ${runtime.uid} -c ${shellQuote(wrapTermuxCommand(command))}").exec().isSuccess
    } catch (_: Exception) {
        false
    }

    private fun runAsTermuxOutput(runtime: TermuxRuntime, command: String): List<String> = try {
        val result = Shell.cmd("su ${runtime.uid} -c ${shellQuote(wrapTermuxCommand(command))}").exec()
        if (result.isSuccess) result.out else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun ensureTermuxPackages(runtime: TermuxRuntime, logger: ContainerLogger?): Boolean {
        if (runAsTermux(runtime, "command -v pulseaudio >/dev/null 2>&1 && command -v pactl >/dev/null 2>&1")) {
            return true
        }
        logger?.i("[*] Installing Termux PulseAudio...")
        val installed = runAsTermux(
            runtime,
            "pkg install -y pulseaudio >/dev/null 2>&1 || " +
                "{ pkg update -y >/dev/null 2>&1 && pkg install -y pulseaudio >/dev/null 2>&1; }"
        )
        if (!installed) return false

        runAsTermux(
            runtime,
            "if command -v dpkg >/dev/null 2>&1 && ! dpkg -s libandroid-stub >/dev/null 2>&1; then " +
                "pkg install -y libandroid-stub >/dev/null 2>&1 || " +
                "{ pkg install -y x11-repo >/dev/null 2>&1 || true; pkg install -y libandroid-stub >/dev/null 2>&1 || true; }; fi"
        )
        return true
    }

    private fun prepareHostState(runtime: TermuxRuntime): Boolean {
        val daemonConf = "$HOST_PULSE_CONFIG/daemon.conf"
        val clientConf = "$HOST_PULSE_CONFIG/client.conf"
        val command = """
            mkdir -p ${shellQuote(HOST_PULSE_CONFIG)} ${shellQuote(HOST_PULSE_RUNTIME)} ${shellQuote(HOST_PULSE_STATE)} ${shellQuote(HOST_LISTENERS_DIR)} || exit 20
            chmod 700 ${shellQuote(HOST_STATE_DIR)} ${shellQuote(HOST_PULSE_HOME)} ${shellQuote("$HOST_PULSE_HOME/.config")} ${shellQuote(HOST_PULSE_CONFIG)} ${shellQuote(HOST_PULSE_RUNTIME)} ${shellQuote(HOST_PULSE_STATE)} ${shellQuote(HOST_LISTENERS_DIR)} 2>/dev/null || true
            : > ${shellQuote(daemonConf)} || exit 21
            printf '%s\n' 'autospawn = no' 'enable-shm = no' > ${shellQuote(clientConf)} || exit 22
            chmod 600 ${shellQuote(daemonConf)} ${shellQuote(clientConf)} 2>/dev/null || true

            cookie=${shellQuote(HOST_COOKIE)}
            if [ ! -f "${'$'}cookie" ] || [ "${'$'}(wc -c < "${'$'}cookie" 2>/dev/null | tr -d ' ')" != 256 ]; then
                for old in ${shellQuote("$SCRIPT_HOSTNAT_DIR/host/transport.cookie")} ${shellQuote("$SCRIPT_NETLAB_DIR/host/transport.cookie")}; do
                    if [ -f "${'$'}old" ] && [ "${'$'}(wc -c < "${'$'}old" 2>/dev/null | tr -d ' ')" = 256 ]; then
                        cp -p "${'$'}old" "${'$'}cookie" 2>/dev/null || true
                        break
                    fi
                done
            fi
            if [ ! -f "${'$'}cookie" ] || [ "${'$'}(wc -c < "${'$'}cookie" 2>/dev/null | tr -d ' ')" != 256 ]; then
                umask 077
                dd if=/dev/urandom of="${'$'}cookie" bs=256 count=1 2>/dev/null || exit 23
            fi
            [ "${'$'}(wc -c < "${'$'}cookie" 2>/dev/null | tr -d ' ')" = 256 ] || exit 24
            chmod 600 "${'$'}cookie" 2>/dev/null || true
        """.trimIndent()
        return runAsTermux(runtime, command)
    }

    private fun isolatedPulseEnvironment(): String = buildString {
        append("HOME=").append(shellQuote(HOST_PULSE_HOME)).append(' ')
        append("XDG_CONFIG_HOME=").append(shellQuote("$HOST_PULSE_HOME/.config")).append(' ')
        append("PULSE_CONFIG=").append(shellQuote("$HOST_PULSE_CONFIG/daemon.conf")).append(' ')
        append("PULSE_CONFIG_PATH=").append(shellQuote(HOST_PULSE_CONFIG)).append(' ')
        append("PULSE_RUNTIME_PATH=").append(shellQuote(HOST_PULSE_RUNTIME)).append(' ')
        append("PULSE_STATE_PATH=").append(shellQuote(HOST_PULSE_STATE)).append(' ')
        append("PULSE_CLIENTCONFIG=").append(shellQuote("$HOST_PULSE_CONFIG/client.conf")).append(' ')
        append("PULSE_COOKIE=").append(shellQuote(HOST_COOKIE))
    }

    private fun hostPactl(runtime: TermuxRuntime, arguments: String): List<String> =
        runAsTermuxOutput(
            runtime,
            "PULSE_SERVER=${shellQuote("unix:$HOST_CONTROL_SOCKET")} " +
                "PULSE_COOKIE=${shellQuote(HOST_COOKIE)} pactl $arguments"
        )

    private fun probeCoreAndroidSink(runtime: TermuxRuntime): String? {
        val command = """
            server=${shellQuote("unix:$HOST_CONTROL_SOCKET")}
            PULSE_SERVER="${'$'}server" PULSE_COOKIE=${shellQuote(HOST_COOKIE)} pactl info >/dev/null 2>&1 || exit 30
            sinks=${'$'}(PULSE_SERVER="${'$'}server" PULSE_COOKIE=${shellQuote(HOST_COOKIE)} pactl list short sinks 2>/dev/null) || exit 31
            if printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]AAudio_sink[[:space:]]'; then sink=AAudio_sink
            elif printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]OpenSL_ES_sink[[:space:]]'; then sink=OpenSL_ES_sink
            else exit 32
            fi
            PULSE_SERVER="${'$'}server" PULSE_COOKIE=${shellQuote(HOST_COOKIE)} pactl set-default-sink "${'$'}sink" >/dev/null 2>&1 || exit 33
            PULSE_SERVER="${'$'}server" PULSE_COOKIE=${shellQuote(HOST_COOKIE)} pactl info 2>/dev/null | grep -Fq "Default Sink: ${'$'}sink" || exit 34
            printf '%s\n' "${'$'}sink"
        """.trimIndent()
        return runAsTermuxOutput(runtime, command)
            .firstOrNull { it.trim() == "AAudio_sink" || it.trim() == "OpenSL_ES_sink" }
            ?.trim()
    }

    private fun stopDroidSpacesNativeHostPulse(runtime: TermuxRuntime) {
        val command = """
            target_uid=${runtime.uid}
            for cmdf in /proc/[0-9]*/cmdline; do
                [ -r "${'$'}cmdf" ] || continue
                pid=${'$'}{cmdf#/proc/}; pid=${'$'}{pid%/cmdline}
                case "${'$'}pid" in ''|*[!0-9]*) continue ;; esac
                status=/proc/${'$'}pid/status
                [ -r "${'$'}status" ] || continue
                uid=${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "${'$'}status" | sed -n '1p')
                [ "${'$'}uid" = "${'$'}target_uid" ] || continue
                cmd=${'$'}(tr '\000' ' ' < "${'$'}cmdf" 2>/dev/null || true)
                case "${'$'}cmd" in
                    *pulseaudio*module-native-protocol-unix*.pulse-socket*) kill "${'$'}pid" 2>/dev/null || true ;;
                esac
            done
        """.trimIndent()
        try { Shell.cmd(command).exec() } catch (_: Exception) { }
    }

    private fun stopExternalScriptCore(runtime: TermuxRuntime, baseDir: String, label: String, logger: ContainerLogger?) {
        val pidFile = "$baseDir/host/pulseaudio.pid"
        val socket = "$baseDir/host/control.sock"
        val command = """
            pf=${shellQuote(pidFile)}
            [ -f "${'$'}pf" ] || exit 0
            pid=${'$'}(sed -n 's/^pid=//p' "${'$'}pf" 2>/dev/null | sed -n '1p')
            [ -n "${'$'}pid" ] || pid=${'$'}(sed -n '1p' "${'$'}pf" 2>/dev/null || true)
            case "${'$'}pid" in ''|*[!0-9]*) exit 0 ;; esac
            kill -0 "${'$'}pid" 2>/dev/null || exit 0
            [ -r "/proc/${'$'}pid/status" ] && [ -r "/proc/${'$'}pid/cmdline" ] || exit 0
            uid=${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/${'$'}pid/status" | sed -n '1p')
            [ "${'$'}uid" = ${runtime.uid} ] || exit 0
            cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true)
            case "${'$'}cmd" in *pulseaudio*module-native-protocol-unix*socket=${socket}*) kill "${'$'}pid" 2>/dev/null || true; printf '%s\n' "${'$'}pid" ;; esac
        """.trimIndent()
        val stopped = try { Shell.cmd(command).exec().out.firstOrNull()?.trim() } catch (_: Exception) { null }
        if (!stopped.isNullOrBlank()) logger?.i("[*] Migrating from $label audio core (PID $stopped)")
    }

    private fun stopPreviousManagerTcpRuntime(runtime: TermuxRuntime, logger: ContainerLogger?) {
        val command = """
            pf=${shellQuote(HOST_PID_FILE)}
            [ -f "${'$'}pf" ] || exit 0
            pid=${'$'}(sed -n '1p' "${'$'}pf" 2>/dev/null || true)
            case "${'$'}pid" in ''|*[!0-9]*) exit 0 ;; esac
            kill -0 "${'$'}pid" 2>/dev/null || exit 0
            [ -r "/proc/${'$'}pid/status" ] && [ -r "/proc/${'$'}pid/cmdline" ] && [ -r "/proc/${'$'}pid/environ" ] || exit 0
            uid=${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/${'$'}pid/status" | sed -n '1p')
            [ "${'$'}uid" = ${runtime.uid} ] || exit 0
            cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true)
            env=${'$'}(tr '\000' '\n' < "/proc/${'$'}pid/environ" 2>/dev/null || true)
            case "${'$'}cmd" in *pulseaudio*module-native-protocol-tcp*listen=127.0.0.1*port=4713*) : ;; *) exit 0 ;; esac
            printf '%s\n' "${'$'}env" | grep -Fq ${shellQuote("HOME=$OLD_MANAGER_PULSE_HOME")} || exit 0
            kill "${'$'}pid" 2>/dev/null || true
            printf '%s\n' "${'$'}pid"
        """.trimIndent()
        val stopped = try { Shell.cmd(command).exec().out.firstOrNull()?.trim() } catch (_: Exception) { null }
        if (!stopped.isNullOrBlank()) logger?.i("[*] Migrating previous HOST-only Manager audio runtime (PID $stopped)")
    }

    private fun stopOwnedHostPulse(runtime: TermuxRuntime) {
        val command = """
            pf=${shellQuote(HOST_PID_FILE)}
            [ -f "${'$'}pf" ] || exit 0
            pid=${'$'}(sed -n 's/^pid=//p' "${'$'}pf" 2>/dev/null | sed -n '1p')
            case "${'$'}pid" in ''|*[!0-9]*) rm -f "${'$'}pf"; exit 0 ;; esac
            if kill -0 "${'$'}pid" 2>/dev/null && [ -r "/proc/${'$'}pid/status" ] && [ -r "/proc/${'$'}pid/cmdline" ]; then
                uid=${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/${'$'}pid/status" | sed -n '1p')
                cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true)
                if [ "${'$'}uid" = ${runtime.uid} ]; then
                    case "${'$'}cmd" in *pulseaudio*module-native-protocol-unix*socket=${HOST_CONTROL_SOCKET}*auth-cookie=${HOST_COOKIE}*) kill "${'$'}pid" 2>/dev/null || true ;; esac
                fi
            fi
            rm -f "${'$'}pf" 2>/dev/null || true
            rm -f ${shellQuote(HOST_CONTROL_SOCKET)} 2>/dev/null || true
        """.trimIndent()
        try { Shell.cmd(command).exec() } catch (_: Exception) { }
    }

    private fun startHostBackend(runtime: TermuxRuntime, module: String): Boolean {
        val preload = if (module == "module-sles-sink") samsungSlesPreload() else null
        val env = isolatedPulseEnvironment()
        val ld = preload?.let { "LD_PRELOAD=${shellQuote(it)} " } ?: ""
        val command = """
            mkdir -p ${shellQuote(HOST_STATE_DIR)} || exit 40
            : > ${shellQuote(HOST_LOG_FILE)} || exit 41
            rm -f ${shellQuote(HOST_CONTROL_SOCKET)} 2>/dev/null || true
            $env $ld nohup pulseaudio -n --daemonize=no --exit-idle-time=-1 --use-pid-file=false \
                -L ${shellQuote(module)} \
                -L ${shellQuote("module-native-protocol-unix socket=$HOST_CONTROL_SOCKET auth-cookie=$HOST_COOKIE")} \
                </dev/null >${shellQuote(HOST_LOG_FILE)} 2>&1 &
            pid=${'$'}!
            start=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}pid/stat" 2>/dev/null || true)
            {
                printf 'pid=%s\n' "${'$'}pid"
                printf 'start=%s\n' "${'$'}start"
                printf 'owner=%s\n' ${shellQuote(MANAGED)}
            } > ${shellQuote(HOST_PID_FILE)} || { kill "${'$'}pid" 2>/dev/null || true; exit 42; }
            chmod 600 ${shellQuote(HOST_PID_FILE)} 2>/dev/null || true
        """.trimIndent()
        return runAsTermux(runtime, command)
    }

    private fun samsungSlesPreload(): String? {
        val manufacturer = try {
            Shell.cmd("getprop ro.product.manufacturer 2>/dev/null").exec().out.firstOrNull()?.trim()?.lowercase()
        } catch (_: Exception) {
            null
        }
        if (manufacturer?.contains("samsung") != true) return null
        return listOf("/system/lib64/libskcodec.so", "/system/lib/libskcodec.so")
            .firstOrNull { path ->
                try { Shell.cmd("test -r ${shellQuote(path)}").exec().isSuccess } catch (_: Exception) { false }
            }
    }

    private suspend fun ensureManagerHostRuntime(runtime: TermuxRuntime, logger: ContainerLogger?): HostRuntime? {
        if (!ensureTermuxPackages(runtime, logger)) return null
        if (!prepareHostState(runtime)) return null

        probeCoreAndroidSink(runtime)?.let { sink ->
            logger?.i("[+] Manager audio core already ready on private UNIX control socket")
            return HostRuntime(sink)
        }

        stopExternalScriptCore(runtime, SCRIPT_HOSTNAT_DIR, "v3.2 HOST+NAT script", logger)
        stopExternalScriptCore(runtime, SCRIPT_NETLAB_DIR, "v3.1 NetLab script", logger)
        stopPreviousManagerTcpRuntime(runtime, logger)
        stopOwnedHostPulse(runtime)
        stopDroidSpacesNativeHostPulse(runtime)
        delay(300)

        val modules = listOf("module-aaudio-sink", "module-sles-sink")
        for (module in modules) {
            logger?.i("[*] Starting manager-owned PulseAudio core with $module...")
            if (!startHostBackend(runtime, module)) continue

            repeat(50) {
                val sink = probeCoreAndroidSink(runtime)
                if (sink != null) {
                    logger?.i("[+] Manager audio core ready using $module")
                    return HostRuntime(sink)
                }
                delay(200)
            }

            stopOwnedHostPulse(runtime)
            logger?.w("[!] $module did not produce a working Android audio core")
        }

        val tail = runAsTermuxOutput(runtime, "tail -n 30 ${shellQuote(HOST_LOG_FILE)} 2>/dev/null || true")
        tail.filter { it.isNotBlank() }.forEach { logger?.w(it) }
        return null
    }

    private fun migratedOriginalPulseState(runtime: TermuxRuntime, containerName: String): String? {
        val command = """
            name=${shellQuote(containerName)}
            key=${'$'}(printf '%s' "${'$'}name" | tr -c 'A-Za-z0-9._-' '_' | cut -c1-80)
            sum=${'$'}(printf '%s' "${'$'}name" | cksum 2>/dev/null | sed -n 's/[[:space:]].*//p')
            [ -n "${'$'}sum" ] || exit 1
            for base in ${shellQuote(SCRIPT_HOSTNAT_DIR)} ${shellQuote(SCRIPT_NETLAB_DIR)}; do
                sf="${'$'}base/containers/${'$'}key.${'$'}sum.state"
                [ -f "${'$'}sf" ] || continue
                value=${'$'}(sed -n 's/^original_pulse=//p' "${'$'}sf" | sed -n '1p')
                case "${'$'}value" in ON|OFF|ABSENT) printf '%s\n' "${'$'}value"; exit 0 ;; esac
            done
            exit 1
        """.trimIndent()
        return runAsTermuxOutput(runtime, command).firstOrNull()?.trim()?.takeIf { it in setOf("ON", "OFF", "ABSENT") }
    }

    private fun readPulseState(configPath: String): String {
        val cfg = shellQuote(configPath)
        val command =
            "v=\$(sed -n 's/^enable_pulseaudio=//p' $cfg 2>/dev/null | tail -n 1); " +
                "if [ -z \"\$v\" ]; then printf ABSENT; " +
                "else case \"\$v\" in 1|true|yes|on) printf ON ;; *) printf OFF ;; esac; fi"
        return try {
            val result = Shell.cmd(command).exec()
            if (result.isSuccess) result.out.joinToString("").trim() else "UNKNOWN"
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    private fun setPulseState(configPath: String, enabled: Boolean): Boolean {
        val cfg = shellQuote(configPath)
        val value = if (enabled) "1" else "0"
        val command = """
            cfg=$cfg
            [ -f "${'$'}cfg" ] || exit 50
            tmp="${'$'}cfg.saas-x11-audio.${'$'}${'$'}"
            : > "${'$'}tmp" || exit 51
            found=0
            while IFS= read -r line || [ -n "${'$'}line" ]; do
                case "${'$'}line" in
                    enable_pulseaudio=*)
                        if [ "${'$'}found" -eq 0 ]; then
                            printf 'enable_pulseaudio=%s\n' '$value' >> "${'$'}tmp" || exit 52
                            found=1
                        fi
                        ;;
                    *) printf '%s\n' "${'$'}line" >> "${'$'}tmp" || exit 52 ;;
                esac
            done < "${'$'}cfg"
            [ "${'$'}found" -eq 1 ] || printf 'enable_pulseaudio=%s\n' '$value' >> "${'$'}tmp"
            chmod ${'$'}(stat -c '%a' "${'$'}cfg" 2>/dev/null || printf '600') "${'$'}tmp" 2>/dev/null || true
            mv "${'$'}tmp" "${'$'}cfg"
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun removePulseState(configPath: String): Boolean {
        val cfg = shellQuote(configPath)
        val command = """
            cfg=$cfg
            [ -f "${'$'}cfg" ] || exit 50
            tmp="${'$'}cfg.saas-x11-audio.${'$'}${'$'}"
            : > "${'$'}tmp" || exit 51
            while IFS= read -r line || [ -n "${'$'}line" ]; do
                case "${'$'}line" in enable_pulseaudio=*) : ;; *) printf '%s\n' "${'$'}line" >> "${'$'}tmp" || exit 52 ;; esac
            done < "${'$'}cfg"
            chmod ${'$'}(stat -c '%a' "${'$'}cfg" 2>/dev/null || printf '600') "${'$'}tmp" 2>/dev/null || true
            mv "${'$'}tmp" "${'$'}cfg"
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun restoreContainerConfig(context: android.content.Context, info: ContainerInfo): Boolean =
        when (FixSettings.getPulseAudioOriginalState(context, info.name)) {
            "ABSENT" -> removePulseState(info.configPath)
            "ON" -> setPulseState(info.configPath, true)
            "OFF" -> setPulseState(info.configPath, false)
            null -> true
            else -> false
        }

    private fun resolveEndpoint(info: ContainerInfo, logger: ContainerLogger?): String? = when (normalizedNetwork(info)) {
        "host" -> "127.0.0.1"
        "nat" -> discoverNatGateway(info, logger)
        else -> null
    }

    private fun discoverNatGateway(info: ContainerInfo, logger: ContainerLogger?): String? {
        val pid = info.pid ?: return null
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            pid=$pid
            gw=''
            if [ -x ${shellQuote(busybox)} ]; then
                gw=${'$'}(${shellQuote(busybox)} nsenter -t "${'$'}pid" -n ${shellQuote(busybox)} ip -4 route show default 2>/dev/null | sed -n 's/^default via \([0-9.][0-9.]*\).*/\1/p' | sed -n '1p')
            fi
            if [ -z "${'$'}gw" ]; then
                hex=${'$'}(while read ifc dst g rest; do [ "${'$'}dst" = 00000000 ] || continue; printf '%s\n' "${'$'}g"; break; done < "/proc/${'$'}pid/net/route" 2>/dev/null)
                case "${'$'}hex" in
                    ????????)
                        b1=${'$'}(printf '%s' "${'$'}hex" | cut -c7-8); b2=${'$'}(printf '%s' "${'$'}hex" | cut -c5-6)
                        b3=${'$'}(printf '%s' "${'$'}hex" | cut -c3-4); b4=${'$'}(printf '%s' "${'$'}hex" | cut -c1-2)
                        gw=${'$'}(printf '%d.%d.%d.%d' "0x${'$'}b1" "0x${'$'}b2" "0x${'$'}b3" "0x${'$'}b4" 2>/dev/null || true)
                        ;;
                esac
            fi
            printf '%s\n' "${'$'}gw"
        """.trimIndent()
        val discovered = try { Shell.cmd(command).exec().out.firstOrNull()?.trim().orEmpty() } catch (_: Exception) { "" }
        val candidate = when {
            isValidIpv4(discovered) && hostOwnsIpv4(discovered) -> discovered
            hostOwnsIpv4("172.28.0.1") -> {
                logger?.w("[!] Live NAT default route was not readable; using verified DroidSpaces host gateway 172.28.0.1")
                "172.28.0.1"
            }
            else -> null
        }
        return candidate
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
    }

    private fun hostOwnsIpv4(ip: String): Boolean {
        if (!isValidIpv4(ip)) return false
        val command = """
            ip=${shellQuote(ip)}
            if [ -x /system/bin/ip ]; then out=${'$'}(/system/bin/ip -4 -o addr show 2>/dev/null)
            elif [ -x ${shellQuote("${Constants.DS_BASE_DIR}/bin/busybox")} ]; then out=${'$'}(${shellQuote("${Constants.DS_BASE_DIR}/bin/busybox")} ip -4 -o addr show 2>/dev/null)
            else out=${'$'}(ip -4 -o addr show 2>/dev/null || true); fi
            printf '%s\n' "${'$'}out" | grep -Eq "[[:space:]]inet[[:space:]]${'$'}ip/"
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun configuredPortForwardOwner(port: Int): String? {
        val command = """
            wanted=$port
            for cfg in ${shellQuote(Constants.CONTAINERS_DIR)}/*/${shellQuote(Constants.CONFIG_FILE)}; do
                [ -f "${'$'}cfg" ] || continue
                net=${'$'}(sed -n 's/^net_mode=//p' "${'$'}cfg" | tail -n 1)
                [ "${'$'}net" = nat ] || continue
                name=${'$'}(sed -n 's/^name=//p' "${'$'}cfg" | sed -n '1p')
                [ -n "${'$'}name" ] || { d=${'$'}{cfg%/${Constants.CONFIG_FILE}}; name=${'$'}{d##*/}; }
                value=${'$'}(sed -n 's/^port_forwards=//p' "${'$'}cfg" | sed -n '1p')
                oldifs=${'$'}IFS; IFS=,
                for tok in ${'$'}value; do
                    IFS=${'$'}oldifs
                    tok=${'$'}(printf '%s' "${'$'}tok" | tr -d '[:space:]')
                    [ -n "${'$'}tok" ] || { IFS=,; continue; }
                    case "${'$'}tok" in */*) proto=${'$'}{tok##*/}; body=${'$'}{tok%/*} ;; *) proto=tcp; body=${'$'}tok ;; esac
                    [ "${'$'}proto" = tcp ] || { IFS=,; continue; }
                    host=${'$'}{body%%:*}
                    case "${'$'}host" in *-*) start=${'$'}{host%-*}; end=${'$'}{host#*-} ;; *) start=${'$'}host; end=${'$'}host ;; esac
                    case "${'$'}start:${'$'}end" in *[!0-9:]*|'':*) : ;; *)
                        if [ "${'$'}wanted" -ge "${'$'}start" ] 2>/dev/null && [ "${'$'}wanted" -le "${'$'}end" ] 2>/dev/null; then printf '%s\n' "${'$'}name"; exit 0; fi
                        ;;
                    esac
                    IFS=,
                done
                IFS=${'$'}oldifs
            done
            exit 1
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (result.isSuccess) result.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun endpointServer(ip: String, port: Int): String = "tcp:$ip:$port"

    private fun probeEndpointAndroidSink(runtime: TermuxRuntime, ip: String, port: Int): Boolean {
        val server = endpointServer(ip, port)
        val command = """
            info=${'$'}(PULSE_SERVER=${shellQuote(server)} PULSE_COOKIE=${shellQuote(HOST_COOKIE)} pactl info 2>/dev/null) || exit 80
            printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$'
        """.trimIndent()
        return runAsTermux(runtime, command)
    }

    private fun listenerStateFile(ip: String, port: Int): String {
        val key = "${ip}_$port".replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$HOST_LISTENERS_DIR/$key.state"
    }

    private fun unloadTrackedListener(runtime: TermuxRuntime, ip: String, port: Int) {
        val state = listenerStateFile(ip, port)
        val command = """
            sf=${shellQuote(state)}
            [ -f "${'$'}sf" ] || exit 0
            id=${'$'}(sed -n 's/^id=//p' "${'$'}sf" | sed -n '1p')
            owner=${'$'}(sed -n 's/^owner=//p' "${'$'}sf" | sed -n '1p')
            case "${'$'}id" in ''|*[!0-9]*) rm -f "${'$'}sf"; exit 0 ;; esac
            [ "${'$'}owner" = ${shellQuote(MANAGED)} ] || { rm -f "${'$'}sf"; exit 0; }
            modules=${'$'}(PULSE_SERVER=${shellQuote("unix:$HOST_CONTROL_SOCKET")} PULSE_COOKIE=${shellQuote(HOST_COOKIE)} pactl list short modules 2>/dev/null || true)
            printf '%s\n' "${'$'}modules" | grep -Eq "^${'$'}id[[:space:]]+module-native-protocol-tcp[[:space:]].*listen=$ip([[:space:]]|${'$'}).*port=$port([[:space:]]|${'$'})" || { rm -f "${'$'}sf"; exit 0; }
            PULSE_SERVER=${shellQuote("unix:$HOST_CONTROL_SOCKET")} PULSE_COOKIE=${shellQuote(HOST_COOKIE)} pactl unload-module "${'$'}id" >/dev/null 2>&1 || true
            rm -f "${'$'}sf" 2>/dev/null || true
        """.trimIndent()
        runAsTermux(runtime, command)
    }

    private fun ensureDirectListener(runtime: TermuxRuntime, ip: String, port: Int, logger: ContainerLogger?): Boolean {
        if (!hostOwnsIpv4(ip)) return false
        if (probeEndpointAndroidSink(runtime, ip, port)) {
            logger?.i("[+] Reusing authenticated PulseAudio listener on $ip:$port")
            return true
        }

        unloadTrackedListener(runtime, ip, port)
        val args = "load-module module-native-protocol-tcp " +
            shellQuote("listen=$ip") + " " + shellQuote("port=$port") + " " + shellQuote("auth-cookie=$HOST_COOKIE")
        val id = hostPactl(runtime, args).firstOrNull()?.trim()?.toIntOrNull() ?: return false
        val state = listenerStateFile(ip, port)
        runAsTermux(
            runtime,
            "mkdir -p ${shellQuote(HOST_LISTENERS_DIR)} && " +
                "printf 'id=%s\\nip=%s\\nport=%s\\nowner=%s\\n' '$id' ${shellQuote(ip)} '$port' ${shellQuote(MANAGED)} > ${shellQuote(state)} && chmod 600 ${shellQuote(state)}"
        )

        repeat(20) {
            if (probeEndpointAndroidSink(runtime, ip, port)) {
                logger?.i("[+] Authenticated PulseAudio listener ready on $ip:$port")
                return true
            }
            Thread.sleep(100)
        }
        unloadTrackedListener(runtime, ip, port)
        return false
    }

    private fun selectAndEnsureListener(runtime: TermuxRuntime, ip: String, logger: ContainerLogger?): Int? {
        val maxPort = (BASE_AUDIO_PORT + MAX_PORT_SHIFT).coerceAtMost(65535)
        for (port in BASE_AUDIO_PORT..maxPort) {
            val owner = configuredPortForwardOwner(port)
            if (owner != null) {
                if (port == BASE_AUDIO_PORT) {
                    logger?.w("[!] Port $port is reserved by DroidSpaces TCP port-forward in $owner; selecting another audio port automatically")
                }
                continue
            }
            if (ensureDirectListener(runtime, ip, port, logger)) {
                if (port != BASE_AUDIO_PORT) logger?.i("[+] Selected audio port: $port")
                return port
            }
            if (port == BASE_AUDIO_PORT) {
                logger?.w("[!] Port $port could not be bound or verified on $ip; selecting another automatically")
            }
        }
        return null
    }

    private fun cookieEscaped(runtime: TermuxRuntime): String? {
        val command = "od -An -v -tu1 ${shellQuote(HOST_COOKIE)} 2>/dev/null | awk '{ for (i=1; i<=NF; i++) printf \"\\\\0%03o\", ${'$'}i }'"
        return runAsTermuxOutput(runtime, command).joinToString("").trim().takeIf { it.isNotEmpty() }
    }

    private fun verifyContainerClient(containerName: String, server: String): Boolean {
        val payload = """
            server=${shellQuote(server)}
            cookie=/root/.config/pulse/saas-audio.cookie
            command -v pactl >/dev/null 2>&1 || exit 70
            [ -f "${'$'}cookie" ] || exit 71
            [ "${'$'}(wc -c < "${'$'}cookie" 2>/dev/null | tr -d ' ')" = 256 ] || exit 72
            client=/root/.config/pulse/client.conf
            [ -f "${'$'}client" ] || exit 73
            grep -Fq ${shellQuote("default-server = $server")} "${'$'}client" || exit 74
            grep -Fq 'cookie-file = /root/.config/pulse/saas-audio.cookie' "${'$'}client" || exit 75
            info=${'$'}(PULSE_SERVER="${'$'}server" PULSE_COOKIE="${'$'}cookie" pactl info 2>/dev/null) || exit 76
            printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${'$'}server" || exit 77
            printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$' || exit 78
        """.trimIndent()
        val command = "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run /bin/sh -lc ${shellQuote(payload)}"
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private suspend fun installAndVerifyContainerClient(
        runtime: TermuxRuntime,
        containerName: String,
        server: String,
        logger: ContainerLogger?
    ): Boolean {
        val escaped = cookieEscaped(runtime) ?: return false
        val payload = buildClientPayload(server, escaped)
        val command = "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run /bin/sh -lc ${shellQuote(payload)}"
        return try {
            val result = Shell.cmd(command).exec()
            result.out.forEach { line ->
                when {
                    line.trim() == "__SAAS_AUDIO_APT__" -> logger?.i("[*] Installing missing Debian/Ubuntu audio clients...")
                    line.trim() == "__SAAS_AUDIO_APK__" -> logger?.i("[*] Installing missing Alpine audio clients...")
                    line.startsWith("Server String:") || line.startsWith("Server Version:") ||
                        line.startsWith("Default Sink:") || line.startsWith("Default Source:") -> logger?.i(line)
                }
            }
            result.err.filter { it.isNotBlank() }.forEach { logger?.w(it) }
            result.isSuccess && result.out.any { it.trim() == "__SAAS_AUDIO_READY__" }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildClientPayload(server: String, cookieEscaped: String): String = """
        set -u
        SERVER=${shellQuote(server)}
        COOKIE_ESCAPED=${shellQuote(cookieEscaped)}
        MANAGED=${shellQuote(MANAGED)}
        LEGACY=${shellQuote(LEGACY_MANAGED)}
        SCRIPT=${shellQuote(SCRIPT_MANAGED)}
        NETLAB=${shellQuote(NETLAB_MANAGED)}
        SCRIPT_LEGACY=${shellQuote(SCRIPT_LEGACY_MANAGED)}

        need=0
        command -v pactl >/dev/null 2>&1 || need=1
        command -v speaker-test >/dev/null 2>&1 || need=1
        if [ "${'$'}need" -eq 1 ]; then
            if command -v apt-get >/dev/null 2>&1; then
                printf '%s\n' __SAAS_AUDIO_APT__
                DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1 || true
                DEBIAN_FRONTEND=noninteractive apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils >/dev/null 2>&1 || true
            elif command -v apk >/dev/null 2>&1; then
                printf '%s\n' __SAAS_AUDIO_APK__
                apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse >/dev/null 2>&1 || true
            fi
        fi
        command -v pactl >/dev/null 2>&1 || exit 60

        is_managed() {
            t="${'$'}1"; [ -f "${'$'}t" ] || return 1
            grep -Fq "${'$'}MANAGED" "${'$'}t" 2>/dev/null || grep -Fq "${'$'}LEGACY" "${'$'}t" 2>/dev/null || \
                grep -Fq "${'$'}SCRIPT" "${'$'}t" 2>/dev/null || grep -Fq "${'$'}NETLAB" "${'$'}t" 2>/dev/null || \
                grep -Fq "${'$'}SCRIPT_LEGACY" "${'$'}t" 2>/dev/null
        }
        backup_unmanaged() {
            t="${'$'}1"; b="${'$'}2"; shift 2
            [ -f "${'$'}t" ] || return 0
            if is_managed "${'$'}t"; then
                if [ ! -e "${'$'}b" ]; then
                    for old in "${'$'}@"; do [ -f "${'$'}old" ] && { cp -p "${'$'}old" "${'$'}b" || true; break; }; done
                fi
                return 0
            fi
            [ -e "${'$'}b" ] || cp -p "${'$'}t" "${'$'}b" || exit 61
        }

        mkdir -p /root/.config/pulse /etc/profile.d || exit 62
        cookie=/root/.config/pulse/saas-audio.cookie
        printf '%b' "${'$'}COOKIE_ESCAPED" > "${'$'}cookie" || exit 63
        [ "${'$'}(wc -c < "${'$'}cookie" | tr -d ' ')" = 256 ] || exit 64
        chmod 600 "${'$'}cookie" 2>/dev/null || true

        client=/root/.config/pulse/client.conf
        backup_unmanaged "${'$'}client" "${'$'}client.saas-x11-manager.bak" \
            "${'$'}client.saas-hostnat.bak" "${'$'}client.saas-netlab.bak" "${'$'}client.saas-audio.bak"
        cat > "${'$'}client" <<EOF_CLIENT
        $BEGIN
        default-server = $server
        cookie-file = /root/.config/pulse/saas-audio.cookie
        autospawn = no
        enable-shm = no
        $END
        EOF_CLIENT

        profile=/etc/profile.d/saas-x11-audio.sh
        cat > "${'$'}profile" <<EOF_PROFILE
        $BEGIN
        export PULSE_SERVER=$server
        export PULSE_COOKIE=/root/.config/pulse/saas-audio.cookie
        $END
        EOF_PROFILE
        chmod 644 "${'$'}profile" 2>/dev/null || true

        old_script_profile=/etc/profile.d/saas-droidspaces-audio.sh
        is_managed "${'$'}old_script_profile" && rm -f "${'$'}old_script_profile" 2>/dev/null || true
        old_profile=/etc/profile.d/android-audio.sh
        is_managed "${'$'}old_profile" && rm -f "${'$'}old_profile" 2>/dev/null || true

        for old in /etc/systemd/system/x11-session.service.d/90-saas-audio.conf /etc/systemd/system/x11-session.service.d/audio.conf; do
            is_managed "${'$'}old" && rm -f "${'$'}old" 2>/dev/null || true
        done
        if [ -f /etc/conf.d/x11-session ]; then
            sed '/^# BEGIN SaaS X11 Manager PulseAudio Fix$/,/^# END SaaS X11 Manager PulseAudio Fix$/d; \
                 /^# BEGIN SaaS X11 Manager Audio Configuration$/,/^# END SaaS X11 Manager Audio Configuration$/d; \
                 /^# BEGIN SaaS DroidSpaces Audio Auto$/,/^# END SaaS DroidSpaces Audio Auto$/d; \
                 /^# BEGIN SaaS DroidSpaces Audio NetLab$/,/^# END SaaS DroidSpaces Audio NetLab$/d' \
                /etc/conf.d/x11-session > /etc/conf.d/x11-session.saas-audio.tmp && mv /etc/conf.d/x11-session.saas-audio.tmp /etc/conf.d/x11-session
        fi

        asound=/etc/asound.conf
        if command -v aplay >/dev/null 2>&1 && PULSE_SERVER="${'$'}SERVER" PULSE_COOKIE="${'$'}cookie" aplay -L 2>/dev/null | grep -q '^pulse'; then
            backup_unmanaged "${'$'}asound" "${'$'}asound.saas-x11-manager.bak" \
                "${'$'}asound.saas-hostnat.bak" "${'$'}asound.saas-netlab.bak" "${'$'}asound.saas-audio.bak"
            cat > "${'$'}asound" <<EOF_ASOUND
        $BEGIN
        pcm.!default { type pulse }
        ctl.!default { type pulse }
        $END
        EOF_ASOUND
        fi

        info=${'$'}(PULSE_SERVER="${'$'}SERVER" PULSE_COOKIE="${'$'}cookie" pactl info 2>&1) || { printf '%s\n' "${'$'}info" >&2; exit 65; }
        printf '%s\n' "${'$'}info" | grep -E '^(Server String|Server Version|Default Sink|Default Source):' || true
        printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$' || exit 66
        printf '%s\n' __SAAS_AUDIO_READY__
    """.trimIndent()

    private fun removeOfflineClientFiles(info: ContainerInfo): Boolean =
        RootfsAccessor.use(info.rootfsPath, "audio_cleanup_${info.name}") { root ->
            val client = "$root/root/.config/pulse/client.conf"
            val cookie = "$root/root/.config/pulse/saas-audio.cookie"
            val asound = "$root/etc/asound.conf"
            val profile = "$root/etc/profile.d/saas-x11-audio.sh"
            val scriptProfile = "$root/etc/profile.d/saas-droidspaces-audio.sh"
            val oldProfile = "$root/etc/profile.d/android-audio.sh"
            val dropIn = "$root/etc/systemd/system/x11-session.service.d/90-saas-audio.conf"
            val oldDropIn = "$root/etc/systemd/system/x11-session.service.d/audio.conf"
            val openRc = "$root/etc/conf.d/x11-session"

            val command = buildString {
                append(restoreManagedFileCommand(client, listOf(
                    "$client.saas-x11-manager.bak",
                    "$client.saas-hostnat.bak",
                    "$client.saas-netlab.bak",
                    "$client.saas-audio.bak"
                )))
                append(restoreManagedFileCommand(asound, listOf(
                    "$asound.saas-x11-manager.bak",
                    "$asound.saas-hostnat.bak",
                    "$asound.saas-netlab.bak",
                    "$asound.saas-audio.bak"
                )))
                append(removeIfManagedCommand(profile))
                append(removeIfManagedCommand(scriptProfile))
                append(removeIfManagedCommand(oldProfile))
                append(removeIfManagedCommand(dropIn))
                append(removeIfManagedCommand(oldDropIn))
                append("rm -f ${shellQuote(cookie)} 2>/dev/null || true; ")
                append("if [ -f ${shellQuote(openRc)} ]; then ")
                append("sed '/^# BEGIN $LEGACY_MANAGED${'$'}/,/^# END $LEGACY_MANAGED${'$'}/d; ")
                append("/^# BEGIN $MANAGED${'$'}/,/^# END $MANAGED${'$'}/d; ")
                append("/^# BEGIN $SCRIPT_LEGACY_MANAGED${'$'}/,/^# END $SCRIPT_LEGACY_MANAGED${'$'}/d; ")
                append("/^# BEGIN $NETLAB_MANAGED${'$'}/,/^# END $NETLAB_MANAGED${'$'}/d' ${shellQuote(openRc)} > ${shellQuote("$openRc.tmp")} && ")
                append("mv ${shellQuote("$openRc.tmp")} ${shellQuote(openRc)}; fi; true")
            }
            try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
        } ?: false

    private fun restoreManagedFileCommand(path: String, backups: List<String>): String {
        val q = shellQuote(path)
        val managedCheck = managedCheck(q)
        val backupLoop = backups.joinToString(" ") { shellQuote(it) }
        return "if [ -f $q ] && $managedCheck; then restored=0; for b in $backupLoop; do if [ -f \"\$b\" ]; then mv \"\$b\" $q; restored=1; break; fi; done; [ \"\$restored\" -eq 1 ] || rm -f $q; fi; "
    }

    private fun removeIfManagedCommand(path: String): String {
        val q = shellQuote(path)
        return "if [ -f $q ] && ${managedCheck(q)}; then rm -f $q; fi; "
    }

    private fun managedCheck(quotedPath: String): String =
        "{ grep -Fq ${shellQuote(MANAGED)} $quotedPath 2>/dev/null || " +
            "grep -Fq ${shellQuote(LEGACY_MANAGED)} $quotedPath 2>/dev/null || " +
            "grep -Fq ${shellQuote(SCRIPT_MANAGED)} $quotedPath 2>/dev/null || " +
            "grep -Fq ${shellQuote(NETLAB_MANAGED)} $quotedPath 2>/dev/null || " +
            "grep -Fq ${shellQuote(SCRIPT_LEGACY_MANAGED)} $quotedPath 2>/dev/null; }"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
