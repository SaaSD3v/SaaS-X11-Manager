package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * NAT compatibility path for Android/Termux runtimes where PulseAudio can
 * create its private UNIX control socket normally but refuses to add a TCP
 * native-protocol listener with pactl after the daemon is already running.
 *
 * The fallback rebuilds only the Manager-owned PulseAudio process and preloads
 * the authenticated NAT listener on the pulseaudio command line. Container and
 * graphical lifecycle remain owned by the normal X11/VNC flow.
 */
object PulseAudioNatStartupFallback {
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    private const val MANAGED = "SaaS X11 Manager Audio Configuration"
    private const val BASE_AUDIO_PORT = 4713
    private const val MAX_PORT_SHIFT = 64

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

    private data class TermuxRuntime(val uid: Int)
    private data class Listener(val ip: String, val port: Int)

    suspend fun ensureBeforeFinalize(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext true

        val info = ContainerManager.getContainerInfo(containerName) ?: return@withContext false
        if (info.netMode.trim().lowercase() != "nat") return@withContext true
        if (!info.isRunning) return@withContext false

        val runtime = detectTermuxRuntime() ?: return@withContext false
        val sink = probeCoreAndroidSink(runtime) ?: return@withContext false
        val endpoint = discoverNatGateway(info, logger) ?: return@withContext false

        for (port in BASE_AUDIO_PORT..maxAudioPort()) {
            if (configuredPortForwardOwner(port) != null) continue
            if (probeEndpointAndroidSink(runtime, endpoint, port)) return@withContext true
        }

        val existing = currentManagerListeners(runtime)
        var announced = false

        for (port in BASE_AUDIO_PORT..maxAudioPort()) {
            val owner = configuredPortForwardOwner(port)
            if (owner != null) {
                if (port == BASE_AUDIO_PORT) {
                    logger?.w("[!] Port $port is reserved by DroidSpaces TCP port-forward in $owner; NAT audio bootstrap will use another port")
                }
                continue
            }

            if (!announced) {
                logger?.i("[*] Preparing NAT audio listener through PulseAudio startup-safe path...")
                logger?.i("[CTX] Only the Manager audio core may be restarted; container and X11/VNC stay unchanged")
                announced = true
            }

            val target = Listener(endpoint, port)
            val listeners = (existing + target).distinct()
            if (!restartOwnedCore(runtime, sink, listeners)) {
                if (port == BASE_AUDIO_PORT) {
                    logger?.w("[!] PulseAudio could not preload NAT listener on $endpoint:$port; selecting another automatically")
                }
                continue
            }

            val ready = waitForCoreAndEndpoint(runtime, sink, target)
            if (ready) {
                rewriteListenerStates(runtime, listeners)
                logger?.i("[+] Startup-safe authenticated PulseAudio listener ready on $endpoint:$port")
                return@withContext true
            }

            logPulseTail(runtime, logger)
            stopOwnedCore(runtime)
            if (port == BASE_AUDIO_PORT) {
                logger?.w("[!] NAT listener $endpoint:$port was not reachable after core startup; selecting another automatically")
            }
        }

        if (restartOwnedCore(runtime, sink, existing)) {
            waitForCore(runtime, sink)
            rewriteListenerStates(runtime, existing)
        }
        false
    }

    private fun maxAudioPort(): Int = (BASE_AUDIO_PORT + MAX_PORT_SHIFT).coerceAtMost(65535)

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

    private fun privatePulseClientEnvironment(): String = buildString {
        append("PULSE_SERVER=").append(shellQuote("unix:$HOST_CONTROL_SOCKET")).append(' ')
        append("PULSE_COOKIE=").append(shellQuote(HOST_COOKIE)).append(' ')
        append("PULSE_CLIENTCONFIG=").append(shellQuote("$HOST_PULSE_CONFIG/client.conf"))
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

    private fun probeCoreAndroidSink(runtime: TermuxRuntime): String? {
        val env = privatePulseClientEnvironment()
        val command = """
            $env pactl info >/dev/null 2>&1 || exit 30
            sinks=${'$'}($env pactl list short sinks 2>/dev/null) || exit 31
            if printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]AAudio_sink[[:space:]]'; then sink=AAudio_sink
            elif printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]OpenSL_ES_sink[[:space:]]'; then sink=OpenSL_ES_sink
            else exit 32
            fi
            $env pactl set-default-sink "${'$'}sink" >/dev/null 2>&1 || exit 33
            printf '%s\n' "${'$'}sink"
        """.trimIndent()
        return runAsTermuxOutput(runtime, command)
            .firstOrNull { it.trim() == "AAudio_sink" || it.trim() == "OpenSL_ES_sink" }
            ?.trim()
    }

    private fun probeEndpointAndroidSink(runtime: TermuxRuntime, ip: String, port: Int): Boolean {
        val command = """
            server=${shellQuote("tcp:$ip:$port")}
            info=${'$'}(PULSE_SERVER="${'$'}server" PULSE_COOKIE=${shellQuote(HOST_COOKIE)} PULSE_CLIENTCONFIG=${shellQuote("$HOST_PULSE_CONFIG/client.conf")} pactl info 2>/dev/null) || exit 80
            printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$'
        """.trimIndent()
        return runAsTermux(runtime, command)
    }

    private suspend fun waitForCore(runtime: TermuxRuntime, expectedSink: String): Boolean {
        repeat(50) {
            if (probeCoreAndroidSink(runtime) == expectedSink) return true
            delay(200)
        }
        return false
    }

    private suspend fun waitForCoreAndEndpoint(runtime: TermuxRuntime, expectedSink: String, target: Listener): Boolean {
        repeat(50) {
            if (probeCoreAndroidSink(runtime) == expectedSink && probeEndpointAndroidSink(runtime, target.ip, target.port)) return true
            delay(200)
        }
        return false
    }

    private suspend fun discoverNatGateway(info: ContainerInfo, logger: ContainerLogger?): String? {
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
        return when {
            isValidIpv4(discovered) && hostOwnsIpv4(discovered) -> discovered
            hostOwnsIpv4("172.28.0.1") -> {
                logger?.w("[!] Live NAT route was not readable during audio bootstrap; using verified DroidSpaces host gateway 172.28.0.1")
                "172.28.0.1"
            }
            else -> null
        }
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

    private fun currentManagerListeners(runtime: TermuxRuntime): List<Listener> {
        val env = privatePulseClientEnvironment()
        return runAsTermuxOutput(runtime, "$env pactl list short modules 2>/dev/null")
            .mapNotNull(::parseListenerModule)
            .filter { it.ip != "0.0.0.0" && hostOwnsIpv4(it.ip) }
            .distinct()
    }

    private fun parseListenerModule(line: String): Listener? {
        val parts = line.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 3 || parts[1] != "module-native-protocol-tcp") return null
        val args = parts[2].split(Regex("\\s+"))
        val ip = args.firstOrNull { it.startsWith("listen=") }?.substringAfter('=') ?: return null
        val port = args.firstOrNull { it.startsWith("port=") }?.substringAfter('=')?.toIntOrNull() ?: return null
        val cookie = args.firstOrNull { it.startsWith("auth-cookie=") }?.substringAfter('=') ?: return null
        if (cookie != HOST_COOKIE || !isValidIpv4(ip) || port !in 1..65535) return null
        return Listener(ip, port)
    }

    private fun stopOwnedCore(runtime: TermuxRuntime): Boolean {
        val command = """
            pf=${shellQuote(HOST_PID_FILE)}
            [ -f "${'$'}pf" ] || exit 0
            pid=${'$'}(sed -n 's/^pid=//p' "${'$'}pf" 2>/dev/null | sed -n '1p')
            owner=${'$'}(sed -n 's/^owner=//p' "${'$'}pf" 2>/dev/null | sed -n '1p')
            case "${'$'}pid" in ''|*[!0-9]*) exit 90 ;; esac
            [ "${'$'}owner" = ${shellQuote(MANAGED)} ] || exit 91
            kill -0 "${'$'}pid" 2>/dev/null || { rm -f "${'$'}pf" ${shellQuote(HOST_CONTROL_SOCKET)}; exit 0; }
            [ -r "/proc/${'$'}pid/status" ] && [ -r "/proc/${'$'}pid/cmdline" ] || exit 92
            uid=${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/${'$'}pid/status" | sed -n '1p')
            [ "${'$'}uid" = ${runtime.uid} ] || exit 93
            cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true)
            case "${'$'}cmd" in
                *pulseaudio*module-native-protocol-unix*socket=${HOST_CONTROL_SOCKET}*auth-cookie=${HOST_COOKIE}*) : ;;
                *) exit 94 ;;
            esac
            kill "${'$'}pid" 2>/dev/null || exit 95
            i=0
            while kill -0 "${'$'}pid" 2>/dev/null && [ "${'$'}i" -lt 30 ]; do sleep 0.1; i=${'$'}((i+1)); done
            kill -0 "${'$'}pid" 2>/dev/null && kill -9 "${'$'}pid" 2>/dev/null || true
            rm -f "${'$'}pf" ${shellQuote(HOST_CONTROL_SOCKET)} 2>/dev/null || true
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun restartOwnedCore(runtime: TermuxRuntime, expectedSink: String, listeners: List<Listener>): Boolean {
        if (!stopOwnedCore(runtime)) return false
        val module = when (expectedSink) {
            "AAudio_sink" -> "module-aaudio-sink"
            "OpenSL_ES_sink" -> "module-sles-sink"
            else -> return false
        }
        val preload = if (module == "module-sles-sink") samsungSlesPreload() else null
        val ld = preload?.let { "LD_PRELOAD=${shellQuote(it)} " } ?: ""
        val tcpModules = listeners.distinct().joinToString(" \\\n                ") { listener ->
            "-L ${shellQuote("module-native-protocol-tcp listen=${listener.ip} port=${listener.port} auth-cookie=$HOST_COOKIE")}"
        }
        val env = isolatedPulseEnvironment()
        val command = """
            mkdir -p ${shellQuote(HOST_STATE_DIR)} ${shellQuote(HOST_LISTENERS_DIR)} || exit 100
            : > ${shellQuote(HOST_LOG_FILE)} || exit 101
            rm -f ${shellQuote(HOST_CONTROL_SOCKET)} 2>/dev/null || true
            $env $ld nohup pulseaudio -n --daemonize=no --exit-idle-time=-1 --use-pid-file=false \
                -L ${shellQuote(module)} \
                -L ${shellQuote("module-native-protocol-unix socket=$HOST_CONTROL_SOCKET auth-cookie=$HOST_COOKIE")} \
                $tcpModules \
                </dev/null >${shellQuote(HOST_LOG_FILE)} 2>&1 &
            pid=${'$'}!
            start=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}pid/stat" 2>/dev/null || true)
            {
                printf 'pid=%s\n' "${'$'}pid"
                printf 'start=%s\n' "${'$'}start"
                printf 'owner=%s\n' ${shellQuote(MANAGED)}
            } > ${shellQuote(HOST_PID_FILE)} || { kill "${'$'}pid" 2>/dev/null || true; exit 102; }
            chmod 600 ${shellQuote(HOST_PID_FILE)} 2>/dev/null || true
        """.trimIndent()
        return runAsTermux(runtime, command)
    }

    private fun rewriteListenerStates(runtime: TermuxRuntime, expected: List<Listener>) {
        val env = privatePulseClientEnvironment()
        val modules = runAsTermuxOutput(runtime, "$env pactl list short modules 2>/dev/null")
        runAsTermux(runtime, "mkdir -p ${shellQuote(HOST_LISTENERS_DIR)} && rm -f ${shellQuote(HOST_LISTENERS_DIR)}/*.state 2>/dev/null || true")

        expected.distinct().forEach { listener ->
            val id = modules.firstNotNullOfOrNull { line ->
                val parsed = parseListenerModule(line)
                if (parsed == listener) line.trim().split(Regex("\\s+"), limit = 2).firstOrNull()?.toIntOrNull() else null
            } ?: return@forEach
            val state = listenerStateFile(listener)
            runAsTermux(runtime, "printf 'id=%s\\nip=%s\\nport=%s\\nowner=%s\\n' '$id' ${shellQuote(listener.ip)} '${listener.port}' ${shellQuote(MANAGED)} > ${shellQuote(state)} && chmod 600 ${shellQuote(state)}")
        }
    }

    private fun listenerStateFile(listener: Listener): String {
        val key = "${listener.ip}_${listener.port}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$HOST_LISTENERS_DIR/$key.state"
    }

    private fun samsungSlesPreload(): String? {
        val manufacturer = try { Shell.cmd("getprop ro.product.manufacturer 2>/dev/null").exec().out.firstOrNull()?.trim()?.lowercase() } catch (_: Exception) { null }
        if (manufacturer?.contains("samsung") != true) return null
        return listOf("/system/lib64/libskcodec.so", "/system/lib/libskcodec.so").firstOrNull { path ->
            try { Shell.cmd("test -r ${shellQuote(path)}").exec().isSuccess } catch (_: Exception) { false }
        }
    }

    private suspend fun logPulseTail(runtime: TermuxRuntime, logger: ContainerLogger?) {
        val lines = runAsTermuxOutput(runtime, "tail -n 12 ${shellQuote(HOST_LOG_FILE)} 2>/dev/null || true")
        lines.filter { it.isNotBlank() }.forEach { logger?.w("[PA] $it") }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
