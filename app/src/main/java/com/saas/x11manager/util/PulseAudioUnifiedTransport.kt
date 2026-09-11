package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Final HOST/NAT data-path adapter for the single Manager-owned PulseAudio core.
 *
 * A finite authenticated PCM stream is the authoritative data-path proof.
 * `pactl info` remains useful control-plane diagnostics, but a transient recheck
 * after successful PCM playback must not invalidate a working transport.
 *
 * This object never starts another PulseAudio daemon and never owns container,
 * X11, VNC, or graphical-session lifecycle.
 */
object PulseAudioUnifiedTransport {
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_SH = "$TERMUX_PREFIX/bin/sh"

    private const val STATE = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val COOKIE = "$STATE/transport.cookie"
    private const val CONTROL = "$STATE/control.sock"
    private const val CORE_PID_FILE = "$STATE/pulseaudio.pid"
    private const val PULSE_LOG = "$STATE/pulseaudio.log"

    private const val BASE_PORT = 4713
    private const val MAX_PORT_SHIFT = 64
    private const val DROIDSPACES_NAT_GATEWAY = "172.28.0.1"

    private const val MANAGED = "SaaS X11 Manager Audio Configuration"
    private const val BEGIN = "# BEGIN $MANAGED"
    private const val END = "# END $MANAGED"

    private data class TermuxOwner(val uid: Int)
    private data class DirectResult(
        val exitCode: Int,
        val stdout: List<String>,
        val stderr: List<String>
    )
    private data class Listener(
        val port: Int,
        val moduleId: Int,
        val sink: String,
        val createdNow: Boolean
    )

    suspend fun finalizeAfterContainerReady(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val context = com.saas.x11manager.X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext true

        val info = ContainerManager.getContainerInfo(containerName)
            ?: return@withContext fail(logger, "Container $containerName was not found")
        val mode = info.netMode.trim().lowercase()
        if (mode != "host" && mode != "nat") {
            return@withContext fail(
                logger,
                "Audio configuration supports net_mode=host and net_mode=nat only (current: ${info.netMode})"
            )
        }
        if (!info.isRunning) {
            return@withContext fail(logger, "Container $containerName is not running; audio client was not changed")
        }

        val owner = termuxOwner() ?: return@withContext fail(logger, "Termux owner could not be resolved")
        logger?.i("[CTX] Audio control executor: private UNIX socket via Termux UID ${owner.uid}")
        logger?.i("[CTX] TCP self-probe from Manager: disabled")
        logger?.i("[CTX] Listener verifier: PulseAudio module table + DroidSpaces container data path")

        val sink = verifyCore(owner, logger)
            ?: return@withContext fail(logger, "Manager audio core is not reachable through the private UNIX control socket")
        val endpoint = when (mode) {
            "host" -> "127.0.0.1"
            else -> resolveNatEndpoint(info, logger)
        } ?: return@withContext fail(logger, "Automatic NAT audio endpoint discovery failed")
        val cookieOctal = cookieOctal(owner, logger)
            ?: return@withContext fail(logger, "Could not prepare the private PulseAudio cookie for the container")

        logger?.i("[CTX] Audio net_mode: $mode")
        logger?.i("[CTX] Audio host endpoint: $endpoint (port selected automatically)")
        if (mode == "nat") logNatEndpointDiagnostics(endpoint, logger)

        val maxPort = (BASE_PORT + MAX_PORT_SHIFT).coerceAtMost(65535)
        var firstLoadFailureLogged = false
        for (port in BASE_PORT..maxPort) {
            val reservedBy = if (mode == "nat") configuredPortForwardOwner(port) else null
            if (reservedBy != null) {
                if (port == BASE_PORT) logger?.w("[!] Port $port is reserved by DroidSpaces TCP port-forward in $reservedBy; selecting another audio port automatically")
                continue
            }

            val listener = loadOrReuseListener(owner, endpoint, port, sink, logger, !firstLoadFailureLogged)
            if (listener == null) {
                firstLoadFailureLogged = true
                if (port == BASE_PORT) logger?.w("[!] Port $port could not load an authenticated listener on $endpoint; selecting another audio port automatically")
                continue
            }

            val server = "tcp:$endpoint:$port"
            if (!installContainerClient(containerName, server, cookieOctal, logger)) {
                if (listener.createdNow) unloadListener(owner, listener.moduleId, logger)
                logCoreDiagnostics(owner, logger)
                return@withContext false
            }

            // installContainerClient has already completed a finite authenticated
            // pacat stream. This second pactl is diagnostic only and is deliberately
            // unable to unload a listener whose PCM path just succeeded.
            if (!verifyContainerClientDetailed(containerName, server, sink, logger)) {
                logger?.w("[AUDIO] ! Post-PCM pactl control-plane recheck was not confirmed; verified PCM transport is retained")
            }

            if (port != BASE_PORT) logger?.i("[+] Selected audio port: $port")
            logger?.i("[+] Authenticated PulseAudio listener ready on $endpoint:$port")
            FixSettings.setPulseAudioApplied(context, containerName, true)
            logger?.i("[+] Audio ready (${listener.sink}, $server)")
            return@withContext true
        }

        logCoreDiagnostics(owner, logger)
        fail(logger, "Could not create an authenticated PulseAudio listener on $endpoint using ports $BASE_PORT-$maxPort")
    }

    private suspend fun fail(logger: ContainerLogger?, message: String): Boolean {
        logger?.w("[AUDIO] ✗ $message")
        logger?.w("[!] Graphical startup will continue")
        return false
    }

    private fun termuxOwner(): TermuxOwner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid" in ''|*[!0-9]*) exit 2 ;; esac
            printf '%s\n' "${'$'}uid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let(::TermuxOwner)
        } catch (_: Exception) { null }
    }

    internal const val DIRECT_RC_MARKER = "__SAAS_DIRECT_RC__"

    internal fun buildTermuxCommand(command: String): String = """
            export LC_ALL=C
            export PULSE_CLIENTCONFIG=${q("$STATE/pulse-home/.config/pulse/client.conf")}
            export HOME=${q(TERMUX_HOME)}
            export PREFIX=${q(TERMUX_PREFIX)}
            export TMPDIR=${q("$TERMUX_PREFIX/tmp")}
            export PATH=${q("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")}
            (
            $command
            )
            rc=${'$'}?
            printf '\n%s%s\n' ${q(DIRECT_RC_MARKER)} "${'$'}rc"
            exit 0
        """.trimIndent()

    private fun execAsTermux(owner: TermuxOwner, command: String): DirectResult {
        val marker = DIRECT_RC_MARKER
        val wrapped = buildTermuxCommand(command)
        return try {
            val shellResult = Shell.cmd("su ${owner.uid} -c ${q(wrapped)}").exec()
            val rawOut = shellResult.out.toList()
            val markerLine = rawOut.lastOrNull { it.trim().startsWith(marker) }?.trim()
            val exitCode = markerLine?.removePrefix(marker)?.toIntOrNull() ?: if (shellResult.isSuccess) 0 else 255
            DirectResult(exitCode, rawOut.filterNot { it.trim().startsWith(marker) }, shellResult.err.toList())
        } catch (e: Exception) { DirectResult(255, emptyList(), listOf(e.message ?: e.javaClass.simpleName)) }
    }

    private fun unixPactl(owner: TermuxOwner, arguments: String): DirectResult =
        execAsTermux(owner, "PULSE_SERVER=${q("unix:$CONTROL")} PULSE_COOKIE=${q(COOKIE)} timeout 5 pactl $arguments")

    private suspend fun verifyCore(owner: TermuxOwner, logger: ContainerLogger?): String? {
        val info = unixPactl(owner, "info")
        if (info.exitCode != 0) {
            logger?.w("[PA-CORE] timeout 5 pactl info exit=${info.exitCode}")
            logLines(logger, "[PA-CORE][stdout]", info.stdout)
            logLines(logger, "[PA-CORE][stderr]", info.stderr)
            logCoreDiagnostics(owner, logger)
            return null
        }
        val sinks = unixPactl(owner, "list short sinks")
        if (sinks.exitCode != 0) {
            logger?.w("[PA-CORE] timeout 5 pactl list short sinks exit=${sinks.exitCode}")
            logLines(logger, "[PA-CORE][stderr]", sinks.stderr)
            return null
        }
        val sink = when {
            sinks.stdout.any { Regex("""\sAAudio_sink\s""").containsMatchIn(" $it ") } -> "AAudio_sink"
            sinks.stdout.any { Regex("""\sOpenSL_ES_sink\s""").containsMatchIn(" $it ") } -> "OpenSL_ES_sink"
            else -> null
        } ?: run {
            logger?.w("[PA-CORE] No Android audio sink found")
            logLines(logger, "[PA-CORE][sinks]", sinks.stdout)
            return null
        }
        val setDefault = unixPactl(owner, "set-default-sink ${q(sink)}")
        if (setDefault.exitCode != 0) {
            logger?.w("[PA-CORE] Could not set default sink $sink")
            return null
        }
        logger?.i("[+] Manager audio core ready ($sink, private UNIX control socket)")
        return sink
    }

    private suspend fun loadOrReuseListener(
        owner: TermuxOwner,
        ip: String,
        port: Int,
        expectedSink: String,
        logger: ContainerLogger?,
        emitFullFailure: Boolean
    ): Listener? {
        val before = unixPactl(owner, "list short modules")
        if (before.exitCode != 0) {
            if (emitFullFailure) logLines(logger, "[PA-LOAD][stderr]", before.stderr)
            return null
        }
        findListenerModule(before.stdout, ip, port)?.let {
            logger?.i("[+] Reusing PulseAudio listener module $it on $ip:$port")
            return Listener(port, it, expectedSink, false)
        }
        val load = unixPactl(owner, "load-module module-native-protocol-tcp ${q("listen=$ip")} ${q("port=$port")} ${q("auth-cookie=$COOKIE")}")
        val id = load.stdout.asSequence().map { it.trim() }.firstOrNull { it.matches(Regex("""\d+""")) }?.toIntOrNull()
        if (load.exitCode != 0 || id == null) {
            if (emitFullFailure) logCoreDiagnostics(owner, logger)
            return null
        }
        val after = unixPactl(owner, "list short modules")
        if (after.exitCode != 0 || findListenerModule(after.stdout, ip, port, id) != id) {
            unloadListener(owner, id, logger)
            if (emitFullFailure) logCoreDiagnostics(owner, logger)
            return null
        }
        logger?.i("[+] PulseAudio listener module loaded: id=$id endpoint=$ip:$port")
        return Listener(port, id, expectedSink, true)
    }

    private fun findListenerModule(lines: List<String>, ip: String, port: Int, requiredId: Int? = null): Int? {
        for (line in lines) {
            val fields = line.trim().split(Regex("""\s+"""), limit = 3)
            val id = fields.getOrNull(0)?.toIntOrNull() ?: continue
            if (requiredId != null && id != requiredId) continue
            if (fields.getOrNull(1) != "module-native-protocol-tcp") continue
            val tokens = fields.getOrNull(2).orEmpty().split(Regex("""\s+""")).toSet()
            if ("listen=$ip" in tokens && "port=$port" in tokens) return id
        }
        return null
    }

    private suspend fun unloadListener(owner: TermuxOwner, moduleId: Int, logger: ContainerLogger?) {
        val result = unixPactl(owner, "unload-module ${q(moduleId.toString())}")
        if (result.exitCode != 0) logger?.w("[PA-UNLOAD] module=$moduleId exit=${result.exitCode}")
    }

    private suspend fun cookieOctal(owner: TermuxOwner, logger: ContainerLogger?): String? {
        val result = execAsTermux(owner, PulseAudioCookieTransport.encodeCommand(COOKIE))
        if (result.exitCode != 0) {
            logLines(logger, "[PA-COOKIE][stderr]", result.stderr)
            return null
        }
        return PulseAudioCookieTransport.fromOutput(result.stdout)
    }

    private suspend fun logCoreDiagnostics(owner: TermuxOwner, logger: ContainerLogger?) {
        val modules = unixPactl(owner, "list short modules")
        modules.stdout.filter { it.contains("module-native-protocol-tcp") }.takeLast(12)
            .forEach { logger?.w("[PA-DIAG][module] $it") }
        val result = execAsTermux(owner, "tail -n 80 ${q(PULSE_LOG)} 2>/dev/null || true")
        result.stdout.filter { it.isNotBlank() }.takeLast(80).forEach { logger?.w("[PA-DIAG][log] $it") }
    }

    private suspend fun logLines(logger: ContainerLogger?, prefix: String, lines: List<String>, max: Int = 12) {
        lines.filter { it.isNotBlank() }.takeLast(max).forEach { logger?.w("$prefix $it") }
    }

    private suspend fun resolveNatEndpoint(info: ContainerInfo, logger: ContainerLogger?): String? {
        val discovered = discoverContainerDefaultGateway(info)
        if (usableIpv4(discovered)) {
            logger?.i("[CTX] NAT route gateway: $discovered")
            return discovered
        }
        logger?.w("[!] Live NAT route was not readable; using DroidSpaces v6.5.0 gateway $DROIDSPACES_NAT_GATEWAY")
        return DROIDSPACES_NAT_GATEWAY
    }

    private fun discoverContainerDefaultGateway(info: ContainerInfo): String {
        val pid = info.pid ?: return ""
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            pid=$pid
            gw=''
            if [ -x ${q(busybox)} ]; then
                gw=${'$'}(${q(busybox)} nsenter -t "${'$'}pid" -n ${q(busybox)} ip -4 route show default 2>/dev/null |
                    sed -n 's/^default via \([0-9.][0-9.]*\).*/\1/p' | sed -n '1p')
            fi
            if [ -z "${'$'}gw" ]; then
                hex=${'$'}(while read ifc dst g rest; do [ "${'$'}dst" = 00000000 ] || continue; printf '%s\n' "${'$'}g"; break; done < "/proc/${'$'}pid/net/route" 2>/dev/null)
                case "${'$'}hex" in
                    ????????)
                        b1=${'$'}(printf '%s' "${'$'}hex" | cut -c7-8); b2=${'$'}(printf '%s' "${'$'}hex" | cut -c5-6)
                        b3=${'$'}(printf '%s' "${'$'}hex" | cut -c3-4); b4=${'$'}(printf '%s' "${'$'}hex" | cut -c1-2)
                        gw=${'$'}(printf '%d.%d.%d.%d' "0x${'$'}b1" "0x${'$'}b2" "0x${'$'}b3" "0x${'$'}b4" 2>/dev/null || true) ;;
                esac
            fi
            printf '%s\n' "${'$'}gw"
        """.trimIndent()
        return try { Shell.cmd(command).exec().out.firstOrNull()?.trim().orEmpty() } catch (_: Exception) { "" }
    }

    private suspend fun logNatEndpointDiagnostics(endpoint: String, logger: ContainerLogger?) {
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            endpoint=${q(endpoint)}
            found=''
            if [ -x /system/bin/ip ]; then found=${'$'}(/system/bin/ip -4 -o addr show 2>/dev/null | grep -F " inet ${'$'}endpoint/" | sed -n '1p' || true); fi
            if [ -z "${'$'}found" ] && [ -x ${q(busybox)} ]; then found=${'$'}(${q(busybox)} ip -4 -o addr show 2>/dev/null | grep -F " inet ${'$'}endpoint/" | sed -n '1p' || true); fi
            core_pid=${'$'}(sed -n 's/^pid=//p' ${q(CORE_PID_FILE)} 2>/dev/null | sed -n '1p')
            printf 'ADDR=%s\nCORE_PID=%s\n' "${'$'}found" "${'$'}core_pid"
        """.trimIndent()
        val result = try { Shell.cmd(command).exec() } catch (_: Exception) { null }
        val lines = result?.out.orEmpty()
        val address = lines.firstOrNull { it.startsWith("ADDR=") }?.removePrefix("ADDR=").orEmpty()
        val corePid = lines.firstOrNull { it.startsWith("CORE_PID=") }?.removePrefix("CORE_PID=").orEmpty()
        if (address.isNotBlank()) logger?.i("[CTX] Android endpoint observation: $address")
        else logger?.w("[!] Android address enumeration did not show $endpoint; the exact PulseAudio bind will be authoritative")
        if (corePid.isNotBlank()) logger?.i("[CTX] PulseAudio core PID: $corePid")
    }

    private fun usableIpv4(value: String): Boolean = validIpv4(value) && value != "0.0.0.0" && value != "255.255.255.255"
    private fun validIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
    }

    private fun configuredPortForwardOwner(port: Int): String? {
        val command = """
            wanted=$port
            for cfg in ${q(Constants.CONTAINERS_DIR)}/*/${q(Constants.CONFIG_FILE)}; do
                [ -f "${'$'}cfg" ] || continue
                net=${'$'}(sed -n 's/^net_mode=//p' "${'$'}cfg" | tail -n 1); [ "${'$'}net" = nat ] || continue
                name=${'$'}(sed -n 's/^name=//p' "${'$'}cfg" | sed -n '1p'); [ -n "${'$'}name" ] || { d=${'$'}{cfg%/${Constants.CONFIG_FILE}}; name=${'$'}{d##*/}; }
                value=${'$'}(sed -n 's/^port_forwards=//p' "${'$'}cfg" | sed -n '1p'); oldifs=${'$'}IFS; IFS=,
                for tok in ${'$'}value; do
                    IFS=${'$'}oldifs; tok=${'$'}(printf '%s' "${'$'}tok" | tr -d '[:space:]'); [ -n "${'$'}tok" ] || { IFS=,; continue; }
                    case "${'$'}tok" in */*) proto=${'$'}{tok##*/}; body=${'$'}{tok%/*} ;; *) proto=tcp; body=${'$'}tok ;; esac
                    [ "${'$'}proto" = tcp ] || { IFS=,; continue; }; host=${'$'}{body%%:*}
                    case "${'$'}host" in *-*) start=${'$'}{host%-*}; end=${'$'}{host#*-} ;; *) start=${'$'}host; end=${'$'}host ;; esac
                    case "${'$'}start:${'$'}end" in *[!0-9:]*|'':*) : ;; *) if [ "${'$'}wanted" -ge "${'$'}start" ] 2>/dev/null && [ "${'$'}wanted" -le "${'$'}end" ] 2>/dev/null; then printf '%s\n' "${'$'}name"; exit 0; fi ;; esac
                    IFS=,
                done
                IFS=${'$'}oldifs
            done
            exit 1
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (result.isSuccess) result.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } else null
        } catch (_: Exception) { null }
    }

    private suspend fun verifyContainerClientDetailed(
        containerName: String,
        server: String,
        expectedSink: String,
        logger: ContainerLogger?
    ): Boolean {
        val payload = """
            server=${q(server)}
            cookie=/root/.config/pulse/saas-audio.cookie
            expected=${q(expectedSink)}
            command -v pactl >/dev/null 2>&1 || { echo 'pactl missing'; exit 80; }
            [ -f "${'$'}cookie" ] || { echo 'cookie missing'; exit 81; }
            info=${'$'}(PULSE_SERVER="${'$'}server" PULSE_COOKIE="${'$'}cookie" timeout 5 pactl info 2>&1) || { printf '%s\n' "${'$'}info"; exit 82; }
            printf '%s\n' "${'$'}info" | grep -E '^(Server String|Server Version|Default Sink|Default Source):' || true
            printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${'$'}server" || exit 83
            printf '%s\n' "${'$'}info" | grep -Fq "Default Sink: ${'$'}expected" || exit 84
        """.trimIndent()
        val command = PulseAudioContainerCommand.build(containerName, payload)
        return try {
            val result = Shell.cmd(command).exec()
            result.out.filter { it.isNotBlank() }.takeLast(12).forEach { logger?.i(it) }
            result.err.filter { it.isNotBlank() }.takeLast(12).forEach { logger?.w("[CONTAINER] $it") }
            result.isSuccess
        } catch (e: Exception) {
            logger?.w("[CONTAINER] ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun installContainerClient(
        containerName: String,
        server: String,
        octal: String,
        logger: ContainerLogger?
    ): Boolean {
        val command = PulseAudioContainerCommand.build(containerName, buildContainerPayload(server, octal))
        return try {
            val result = Shell.cmd(command).exec()
            result.out.forEach { line ->
                when {
                    line.trim() == "__APT__" -> logger?.i("[*] Installing missing Debian/Ubuntu audio clients...")
                    line.trim() == "__APK__" -> logger?.i("[*] Installing missing Alpine audio clients...")
                    line.startsWith("Server String:") || line.startsWith("Server Version:") || line.startsWith("Default Sink:") || line.startsWith("Default Source:") -> logger?.i(line)
                }
            }
            result.err.filter { it.isNotBlank() && !PulseAudioClientConfig.isFailureMarker(it) }.take(12).forEach { line ->
                if (PulseAudioClientConfig.isWarningMarker(line)) logger?.w("[AUDIO] ! pactl control-plane probe was not confirmed after retries; verified PCM remains authoritative")
                else logger?.w("[CONTAINER] $line")
            }
            val ready = result.isSuccess && result.out.any { it.trim() == "__READY__" }
            if (!ready) {
                val failure = PulseAudioClientConfig.failureSummary(result.code, result.out + result.err)
                logger?.w("[AUDIO] ✗ Client setup failed for $server: $failure")
            }
            ready
        } catch (e: Exception) {
            logger?.w("[AUDIO] ✗ Container audio command failed: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    internal fun buildContainerPayload(server: String, octal: String): String = """
            set -u
            ${PulseAudioClientConfig.failureTrap()}
            SERVER=${q(server)}
            COOKIE_ESCAPED=${q(octal)}

            need=0
            command -v pactl >/dev/null 2>&1 || need=1
            command -v pacat >/dev/null 2>&1 || need=1
            command -v speaker-test >/dev/null 2>&1 || need=1
            if [ "${'$'}need" -eq 1 ]; then
                if command -v apt-get >/dev/null 2>&1; then
                    echo __APT__
                    DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1 || true
                    DEBIAN_FRONTEND=noninteractive apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils >/dev/null 2>&1 || true
                elif command -v apk >/dev/null 2>&1; then
                    echo __APK__
                    apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse >/dev/null 2>&1 || true
                fi
            fi
            command -v pactl >/dev/null 2>&1 || exit 60
            command -v pacat >/dev/null 2>&1 || exit 60
            mkdir -p /root/.config/pulse /etc/profile.d || exit 61

            saas_audio_step=cookie
            cookie=/root/.config/pulse/saas-audio.cookie
            printf '%b' "${'$'}COOKIE_ESCAPED" > "${'$'}cookie" || exit 62
            [ "${'$'}(wc -c < "${'$'}cookie" | tr -d ' ')" = 256 ] || exit 63
            chmod 600 "${'$'}cookie" 2>/dev/null || true

            saas_audio_step=client-config
            client=/root/.config/pulse/client.conf
            if [ -f "${'$'}client" ] && ! grep -Fq ${q(MANAGED)} "${'$'}client" 2>/dev/null && [ ! -e "${'$'}client.saas-x11-manager.bak" ]; then
                cp -p "${'$'}client" "${'$'}client.saas-x11-manager.bak" || exit 64
            fi
            cat > "${'$'}client" <<EOF_CLIENT
$BEGIN
default-server = $server
cookie-file = /root/.config/pulse/saas-audio.cookie
autospawn = no
enable-shm = no
$END
EOF_CLIENT
            cat > /etc/profile.d/saas-x11-audio.sh <<EOF_PROFILE
$BEGIN
export PULSE_SERVER=$server
export PULSE_COOKIE=/root/.config/pulse/saas-audio.cookie
$END
EOF_PROFILE
            chmod 644 /etc/profile.d/saas-x11-audio.sh 2>/dev/null || true

            asound=/etc/asound.conf
            if command -v aplay >/dev/null 2>&1 && PULSE_SERVER="${'$'}SERVER" PULSE_COOKIE="${'$'}cookie" aplay -L 2>/dev/null | grep -q '^pulse'; then
                if [ -f "${'$'}asound" ] && ! grep -Fq ${q(MANAGED)} "${'$'}asound" 2>/dev/null && [ ! -e "${'$'}asound.saas-x11-manager.bak" ]; then
                    cp -p "${'$'}asound" "${'$'}asound.saas-x11-manager.bak" || true
                fi
                cat > "${'$'}asound" <<EOF_ASOUND
$BEGIN
pcm.!default { type pulse }
ctl.!default { type pulse }
$END
EOF_ASOUND
            fi

            ${PulseAudioClientConfig.install(server)}

            # PCM above is authoritative. Retry the control-plane snapshot but
            # leave the verified data path valid if pactl is transiently late.
            info=''; info_ok=0; info_try=0
            while [ "${'$'}info_try" -lt 3 ]; do
                info=${'$'}(PULSE_SERVER="${'$'}SERVER" PULSE_COOKIE="${'$'}cookie" timeout 5 pactl info 2>&1) && {
                    printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${'$'}SERVER" &&
                    printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$' && info_ok=1
                }
                [ "${'$'}info_ok" -eq 1 ] && break
                info_try=${'$'}((info_try + 1)); [ "${'$'}info_try" -lt 3 ] && sleep 1
            done
            if [ "${'$'}info_ok" -eq 1 ]; then
                printf '%s\n' "${'$'}info" | grep -E '^(Server String|Server Version|Default Sink|Default Source):' || true
            else
                printf '__SAAS_AUDIO_WARNING__:post-pcm-control-probe\n' >&2
            fi
            echo __READY__
        """.trimIndent()

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
