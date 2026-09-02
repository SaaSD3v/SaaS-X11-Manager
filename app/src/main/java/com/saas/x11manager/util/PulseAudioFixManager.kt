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
 * Manager-owned Android audio for DroidSpaces HOST containers.
 *
 * Architecture:
 * 1. Before the normal graphical start, ensure a private Termux PulseAudio
 *    runtime is serving AAudio/OpenSL ES on 127.0.0.1:4713.
 * 2. Persist enable_pulseaudio=0 so future DroidSpaces starts do not launch the
 *    broken native socket bridge in parallel with the Manager runtime.
 * 3. After the normal Manager flow has made the container runnable, configure
 *    that already-running container as a PulseAudio TCP client and verify it.
 *
 * This object never starts, stops or restarts a DroidSpaces container or X11.
 * NAT transport is intentionally not implemented here yet.
 */
object PulseAudioFixManager {
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val AUDIO_HOST = "127.0.0.1"
    private const val AUDIO_PORT = 4713
    private const val HOST_SERVER = "tcp:$AUDIO_HOST:$AUDIO_PORT"

    private const val MANAGED = "SaaS X11 Manager Audio Configuration"
    private const val LEGACY_MANAGED = "SaaS X11 Manager PulseAudio Fix"
    private const val BEGIN = "# BEGIN $MANAGED"
    private const val END = "# END $MANAGED"

    private const val HOST_STATE_DIR = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val HOST_PULSE_HOME = "$HOST_STATE_DIR/host-pulse-home"
    private const val HOST_PULSE_CONFIG = "$HOST_PULSE_HOME/.config/pulse"
    private const val HOST_PULSE_RUNTIME = "$HOST_STATE_DIR/host-pulse-runtime"
    private const val HOST_PULSE_STATE = "$HOST_STATE_DIR/host-pulse-state"
    private const val HOST_PID_FILE = "$HOST_STATE_DIR/pulseaudio.pid"
    private const val HOST_LOG_FILE = "$HOST_STATE_DIR/pulseaudio.log"

    private data class TermuxRuntime(val uid: Int)
    private data class HostRuntime(val sink: String)

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

        if (!isHostNetwork(info)) {
            return@withContext failure(
                logger,
                "Audio configuration currently supports net_mode=host only (current: ${info.netMode})"
            )
        }

        val runtime = detectTermuxRuntime()
            ?: return@withContext failure(logger, "Termux was not detected")

        val original = FixSettings.getPulseAudioOriginalState(context, containerName)
            ?: readPulseState(info.configPath).also { state ->
                FixSettings.setPulseAudioOriginalState(context, containerName, state)
            }
        if (original !in setOf("ABSENT", "ON", "OFF")) {
            return@withContext failure(logger, "Could not read the original DroidSpaces PulseAudio setting")
        }

        // The Manager transport is independent from DroidSpaces native audio.
        // This only affects future container starts; it never restarts a running container.
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
            return@withContext failure(logger, "Manager-owned Android audio runtime could not be started")
        }

        logger?.i("[+] Host audio runtime ready (${host.sink}, $HOST_SERVER)")
        PulseAudioFixResult(true, "Host audio runtime ready (${host.sink})")
    }

    /**
     * Called only after the normal X11/VNC path has made the container runnable.
     * It configures the existing runtime as a TCP client; no lifecycle operation
     * is performed by the audio manager.
     */
    suspend fun finalizeAfterContainerReady(
        containerName: String,
        logger: ContainerLogger? = null
    ): PulseAudioFixResult? = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext null

        val info = ContainerManager.getContainerInfo(containerName)
            ?: return@withContext failure(logger, "Container $containerName was not found")
        if (!isHostNetwork(info)) {
            return@withContext failure(
                logger,
                "Audio configuration currently supports net_mode=host only (current: ${info.netMode})"
            )
        }
        if (!info.isRunning) {
            return@withContext failure(logger, "Container $containerName is not running; audio client was not changed")
        }

        val runtime = detectTermuxRuntime()
            ?: return@withContext failure(logger, "Termux was not detected")
        val host = ensureManagerHostRuntime(runtime, logger)
            ?: return@withContext failure(logger, "Manager-owned Android audio runtime is unavailable")

        if (!verifyContainerClient(containerName)) {
            if (!installAndVerifyContainerClient(containerName, logger)) {
                return@withContext failure(logger, "Container audio client configuration failed")
            }
        } else {
            logger?.i("[+] Persistent container audio client already configured")
        }

        FixSettings.setPulseAudioApplied(context, containerName, true)
        logger?.i("[+] Audio ready (${host.sink}, $HOST_SERVER)")
        PulseAudioFixResult(true, "Audio ready (${host.sink})")
    }

    private fun isHostNetwork(info: ContainerInfo): Boolean =
        info.netMode.trim().lowercase() == "host"

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

    private suspend fun ensureTermuxPackages(
        runtime: TermuxRuntime,
        logger: ContainerLogger?
    ): Boolean {
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

        // Optional compatibility package used by Android audio backends on some builds.
        runAsTermux(
            runtime,
            "if command -v dpkg >/dev/null 2>&1 && ! dpkg -s libandroid-stub >/dev/null 2>&1; then " +
                "pkg install -y libandroid-stub >/dev/null 2>&1 || " +
                "{ pkg install -y x11-repo >/dev/null 2>&1 || true; pkg install -y libandroid-stub >/dev/null 2>&1 || true; }; fi"
        )
        return true
    }

    private fun prepareIsolatedPulseHome(runtime: TermuxRuntime): Boolean {
        val daemonConf = "$HOST_PULSE_CONFIG/daemon.conf"
        val clientConf = "$HOST_PULSE_CONFIG/client.conf"
        val command = """
            mkdir -p ${shellQuote(HOST_PULSE_CONFIG)} ${shellQuote(HOST_PULSE_RUNTIME)} ${shellQuote(HOST_PULSE_STATE)} || exit 20
            chmod 700 ${shellQuote(HOST_PULSE_HOME)} ${shellQuote("$HOST_PULSE_HOME/.config")} ${shellQuote(HOST_PULSE_CONFIG)} ${shellQuote(HOST_PULSE_RUNTIME)} ${shellQuote(HOST_PULSE_STATE)} 2>/dev/null || true
            : > ${shellQuote(daemonConf)} || exit 21
            printf '%s\n' 'autospawn = no' > ${shellQuote(clientConf)} || exit 22
            chmod 600 ${shellQuote(daemonConf)} ${shellQuote(clientConf)} 2>/dev/null || true
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
        append("PULSE_CLIENTCONFIG=").append(shellQuote("$HOST_PULSE_CONFIG/client.conf"))
    }

    private fun probeTcpAndroidSink(runtime: TermuxRuntime): String? {
        val command = """
            server=${shellQuote(HOST_SERVER)}
            PULSE_SERVER="${'$'}server" pactl info >/dev/null 2>&1 || exit 30
            sinks=${'$'}(PULSE_SERVER="${'$'}server" pactl list short sinks 2>/dev/null) || exit 31
            if printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]AAudio_sink[[:space:]]'; then sink=AAudio_sink
            elif printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]OpenSL_ES_sink[[:space:]]'; then sink=OpenSL_ES_sink
            else exit 32
            fi
            PULSE_SERVER="${'$'}server" pactl set-default-sink "${'$'}sink" >/dev/null 2>&1 || exit 33
            PULSE_SERVER="${'$'}server" pactl info 2>/dev/null | grep -Fq "Default Sink: ${'$'}sink" || exit 34
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
                    *pulseaudio*module-native-protocol-unix*.pulse-socket*)
                        kill "${'$'}pid" 2>/dev/null || true
                        ;;
                esac
            done
        """.trimIndent()
        try { Shell.cmd(command).exec() } catch (_: Exception) { }
    }

    private fun stopOwnedHostPulse(runtime: TermuxRuntime) {
        val command = """
            pf=${shellQuote(HOST_PID_FILE)}
            [ -f "${'$'}pf" ] || exit 0
            pid=${'$'}(sed -n '1p' "${'$'}pf" 2>/dev/null || true)
            case "${'$'}pid" in ''|*[!0-9]*) rm -f "${'$'}pf"; exit 0 ;; esac
            if kill -0 "${'$'}pid" 2>/dev/null && [ -r "/proc/${'$'}pid/cmdline" ]; then
                cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true)
                case "${'$'}cmd" in
                    *pulseaudio*module-native-protocol-tcp*listen=$AUDIO_HOST*port=$AUDIO_PORT*)
                        kill "${'$'}pid" 2>/dev/null || true
                        ;;
                esac
            fi
            rm -f "${'$'}pf" 2>/dev/null || true
        """.trimIndent()
        runAsTermux(runtime, command)
    }

    private fun startHostBackend(runtime: TermuxRuntime, module: String): Boolean {
        val preload = if (module == "module-sles-sink") samsungSlesPreload() else null
        val env = isolatedPulseEnvironment()
        val ld = preload?.let { "LD_PRELOAD=${shellQuote(it)} " } ?: ""
        val command = """
            mkdir -p ${shellQuote(HOST_STATE_DIR)} || exit 40
            : > ${shellQuote(HOST_LOG_FILE)} || exit 41
            $env $ld nohup pulseaudio -n --daemonize=no --exit-idle-time=-1 --use-pid-file=false \
                -L ${shellQuote(module)} \
                -L ${shellQuote("module-native-protocol-tcp listen=$AUDIO_HOST port=$AUDIO_PORT auth-anonymous=1")} \
                </dev/null >${shellQuote(HOST_LOG_FILE)} 2>&1 &
            pid=${'$'}!
            printf '%s\n' "${'$'}pid" > ${shellQuote(HOST_PID_FILE)} || { kill "${'$'}pid" 2>/dev/null || true; exit 42; }
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

    private suspend fun ensureManagerHostRuntime(
        runtime: TermuxRuntime,
        logger: ContainerLogger?
    ): HostRuntime? {
        probeTcpAndroidSink(runtime)?.let { sink ->
            logger?.i("[+] Manager audio runtime already ready at $HOST_SERVER")
            return HostRuntime(sink)
        }

        if (!ensureTermuxPackages(runtime, logger)) return null
        if (!prepareIsolatedPulseHome(runtime)) return null

        // The broken DroidSpaces native UNIX-socket daemon may own the Android
        // audio device. Stop that host process only; never touch a container/X11.
        stopDroidSpacesNativeHostPulse(runtime)
        delay(300)

        // If a non-working listener still occupies the port, fail closed instead
        // of killing an unrelated process.
        val portBusy = runAsTermux(
            runtime,
            "PULSE_SERVER=${shellQuote(HOST_SERVER)} pactl info >/dev/null 2>&1"
        )
        if (portBusy) {
            logger?.w("[!] Port $AUDIO_PORT already has a PulseAudio server without a usable Android sink")
            return null
        }

        stopOwnedHostPulse(runtime)

        val modules = listOf("module-aaudio-sink", "module-sles-sink")
        for (module in modules) {
            logger?.i("[*] Starting manager-owned PulseAudio with $module...")
            if (!startHostBackend(runtime, module)) continue

            repeat(50) {
                val sink = probeTcpAndroidSink(runtime)
                if (sink != null) {
                    logger?.i("[+] Manager TCP audio ready at $HOST_SERVER using $module")
                    return HostRuntime(sink)
                }
                delay(200)
            }

            stopOwnedHostPulse(runtime)
            logger?.w("[!] $module did not produce a working Android audio runtime")
        }

        val tail = runAsTermuxOutput(runtime, "tail -n 30 ${shellQuote(HOST_LOG_FILE)} 2>/dev/null || true")
        tail.filter { it.isNotBlank() }.forEach { logger?.w(it) }
        return null
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

    private fun verifyContainerClient(containerName: String): Boolean {
        val payload = """
            server=${shellQuote(HOST_SERVER)}
            command -v pactl >/dev/null 2>&1 || exit 70
            info=${'$'}(PULSE_SERVER="${'$'}server" pactl info 2>/dev/null) || exit 71
            printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${HOST_SERVER}" || exit 72
            printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$' || exit 73
        """.trimIndent()
        val command =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run /bin/sh -lc ${shellQuote(payload)}"
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private suspend fun installAndVerifyContainerClient(
        containerName: String,
        logger: ContainerLogger?
    ): Boolean {
        val payload = buildClientPayload()
        val command =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run /bin/sh -lc ${shellQuote(payload)}"
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

    private fun buildClientPayload(): String = """
        set -u
        SERVER=${shellQuote(HOST_SERVER)}
        MANAGED=${shellQuote(MANAGED)}
        LEGACY=${shellQuote(LEGACY_MANAGED)}

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

        backup_unmanaged() {
            t="${'$'}1"; b="${'$'}2"
            [ -f "${'$'}t" ] || return 0
            if grep -Fq "${'$'}MANAGED" "${'$'}t" 2>/dev/null || grep -Fq "${'$'}LEGACY" "${'$'}t" 2>/dev/null; then return 0; fi
            [ -e "${'$'}b" ] || cp -p "${'$'}t" "${'$'}b" || exit 61
        }

        mkdir -p /root/.config/pulse /etc/profile.d || exit 62
        client=/root/.config/pulse/client.conf
        backup_unmanaged "${'$'}client" "${'$'}client.saas-x11-manager.bak"
        cat > "${'$'}client" <<'EOF_CLIENT'
        $BEGIN
        default-server = $HOST_SERVER
        autospawn = no
        $END
        EOF_CLIENT

        profile=/etc/profile.d/saas-x11-audio.sh
        cat > "${'$'}profile" <<'EOF_PROFILE'
        $BEGIN
        export PULSE_SERVER=$HOST_SERVER
        $END
        EOF_PROFILE
        chmod 644 "${'$'}profile" 2>/dev/null || true

        # Remove integration created by the retired native-socket implementation.
        for old in /etc/systemd/system/x11-session.service.d/90-saas-audio.conf /etc/systemd/system/x11-session.service.d/audio.conf; do
            if [ -f "${'$'}old" ] && { grep -Fq "${'$'}MANAGED" "${'$'}old" 2>/dev/null || grep -Fq "${'$'}LEGACY" "${'$'}old" 2>/dev/null; }; then rm -f "${'$'}old"; fi
        done
        old_profile=/etc/profile.d/android-audio.sh
        if [ -f "${'$'}old_profile" ] && { grep -Fq "${'$'}MANAGED" "${'$'}old_profile" 2>/dev/null || grep -Fq "${'$'}LEGACY" "${'$'}old_profile" 2>/dev/null; }; then rm -f "${'$'}old_profile"; fi
        if [ -f /etc/conf.d/x11-session ]; then
            sed '/^# BEGIN SaaS X11 Manager PulseAudio Fix$/,/^# END SaaS X11 Manager PulseAudio Fix$/d; /^# BEGIN SaaS X11 Manager Audio Configuration$/,/^# END SaaS X11 Manager Audio Configuration$/d' /etc/conf.d/x11-session > /etc/conf.d/x11-session.saas-audio.tmp && mv /etc/conf.d/x11-session.saas-audio.tmp /etc/conf.d/x11-session
        fi

        asound=/etc/asound.conf
        if command -v aplay >/dev/null 2>&1 && PULSE_SERVER="${'$'}SERVER" aplay -L 2>/dev/null | grep -q '^pulse'; then
            backup_unmanaged "${'$'}asound" "${'$'}asound.saas-x11-manager.bak"
            cat > "${'$'}asound" <<'EOF_ASOUND'
        $BEGIN
        pcm.!default { type pulse }
        ctl.!default { type pulse }
        $END
        EOF_ASOUND
        fi

        info=${'$'}(PULSE_SERVER="${'$'}SERVER" pactl info 2>&1) || { printf '%s\n' "${'$'}info" >&2; exit 63; }
        printf '%s\n' "${'$'}info" | grep -E '^(Server String|Server Version|Default Sink|Default Source):' || true
        printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$' || exit 64
        printf '%s\n' __SAAS_AUDIO_READY__
    """.trimIndent()

    private fun removeOfflineClientFiles(info: ContainerInfo): Boolean =
        RootfsAccessor.use(info.rootfsPath, "audio_cleanup_${info.name}") { root ->
            val client = "$root/root/.config/pulse/client.conf"
            val asound = "$root/etc/asound.conf"
            val profile = "$root/etc/profile.d/saas-x11-audio.sh"
            val oldProfile = "$root/etc/profile.d/android-audio.sh"
            val dropIn = "$root/etc/systemd/system/x11-session.service.d/90-saas-audio.conf"
            val oldDropIn = "$root/etc/systemd/system/x11-session.service.d/audio.conf"
            val openRc = "$root/etc/conf.d/x11-session"

            val command = buildString {
                append(restoreManagedFileCommand(client))
                append(restoreManagedFileCommand(asound))
                append(removeIfManagedCommand(profile))
                append(removeIfManagedCommand(oldProfile))
                append(removeIfManagedCommand(dropIn))
                append(removeIfManagedCommand(oldDropIn))
                append("if [ -f ${shellQuote(openRc)} ]; then ")
                append("sed '/^# BEGIN $LEGACY_MANAGED${'$'}/,/^# END $LEGACY_MANAGED${'$'}/d; ")
                append("/^# BEGIN $MANAGED${'$'}/,/^# END $MANAGED${'$'}/d' ${shellQuote(openRc)} > ${shellQuote("$openRc.tmp")} && ")
                append("mv ${shellQuote("$openRc.tmp")} ${shellQuote(openRc)}; fi; true")
            }
            try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
        } ?: false

    private fun restoreManagedFileCommand(path: String): String {
        val q = shellQuote(path)
        val backup = shellQuote("$path.saas-x11-manager.bak")
        val managedCheck =
            "{ grep -Fq ${shellQuote(MANAGED)} $q 2>/dev/null || grep -Fq ${shellQuote(LEGACY_MANAGED)} $q 2>/dev/null; }"
        return "if [ -f $q ] && $managedCheck; then if [ -f $backup ]; then mv $backup $q; else rm -f $q; fi; fi; "
    }

    private fun removeIfManagedCommand(path: String): String {
        val q = shellQuote(path)
        return "if [ -f $q ] && { grep -Fq ${shellQuote(MANAGED)} $q 2>/dev/null || grep -Fq ${shellQuote(LEGACY_MANAGED)} $q 2>/dev/null; }; then rm -f $q; fi; "
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
