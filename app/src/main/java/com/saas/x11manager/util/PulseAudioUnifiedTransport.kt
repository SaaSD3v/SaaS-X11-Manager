package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HOST/NAT data-path finalizer for the single Manager-owned PulseAudio core.
 *
 * PulseAudioFixManager owns the daemon lifecycle and prepares exactly one
 * AAudio/OpenSL ES core with a private UNIX control socket and private cookie.
 *
 * This object never launches another PulseAudio daemon and never uses Termux
 * RunCommandService. It controls the already-running daemon only through the
 * private UNIX socket using the resolved Termux UID. The actual TCP data path is
 * verified from the running DroidSpaces container, never by a host TCP
 * self-probe.
 *
 * Lifecycle ownership remains external: this class never starts/stops/restarts
 * DroidSpaces containers, X11, or VNC.
 */
object PulseAudioUnifiedTransport {
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_SH = "$TERMUX_PREFIX/bin/sh"

    private const val STATE = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val COOKIE = "$STATE/transport.cookie"
    private const val CONTROL = "$STATE/control.sock"
    private const val PULSE_LOG = "$STATE/pulseaudio.log"

    private const val BASE_PORT = 4713
    private const val MAX_PORT_SHIFT = 64

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
            return@withContext fail(
                logger,
                "Container $containerName is not running; audio client was not changed"
            )
        }

        val owner = termuxOwner()
            ?: return@withContext fail(logger, "Termux owner could not be resolved")

        logger?.i("[CTX] Audio control executor: private UNIX socket via Termux UID ${owner.uid}")
        logger?.i("[CTX] TCP self-probe from Manager: disabled")
        logger?.i("[CTX] Listener verifier: PulseAudio module table + DroidSpaces container data path")

        val sink = verifyCore(owner, logger)
            ?: return@withContext fail(
                logger,
                "Manager audio core is not reachable through the private UNIX control socket"
            )

        val endpoint = when (mode) {
            "host" -> "127.0.0.1"
            else -> discoverNatGateway(info, logger)
                ?: return@withContext fail(logger, "Automatic NAT gateway discovery failed")
        }

        val cookieOctal = cookieOctal(owner, logger)
            ?: return@withContext fail(
                logger,
                "Could not prepare the private PulseAudio cookie for the container"
            )

        logger?.i("[CTX] Audio net_mode: $mode")
        logger?.i("[CTX] Audio host endpoint: $endpoint (port selected automatically)")

        val maxPort = (BASE_PORT + MAX_PORT_SHIFT).coerceAtMost(65535)
        var firstLoadFailureLogged = false

        for (port in BASE_PORT..maxPort) {
            val reservedBy = if (mode == "nat") configuredPortForwardOwner(port) else null
            if (reservedBy != null) {
                if (port == BASE_PORT) {
                    logger?.w(
                        "[!] Port $port is reserved by DroidSpaces TCP port-forward in $reservedBy; " +
                            "selecting another audio port automatically"
                    )
                }
                continue
            }

            val listener = loadOrReuseListener(
                owner = owner,
                ip = endpoint,
                port = port,
                expectedSink = sink,
                logger = logger,
                emitFullFailure = !firstLoadFailureLogged
            )

            if (listener == null) {
                firstLoadFailureLogged = true
                if (port == BASE_PORT) {
                    logger?.w(
                        "[!] Port $port could not load an authenticated listener on $endpoint; " +
                            "selecting another audio port automatically"
                    )
                }
                continue
            }

            val server = "tcp:$endpoint:$port"
            val alreadyConfigured = verifyContainerClient(containerName, server)
            val configured = alreadyConfigured ||
                installContainerClient(containerName, server, cookieOctal, logger)

            if (!configured) {
                logger?.w("[!] Container audio client configuration failed for $server")
                if (listener.createdNow) unloadListener(owner, listener.moduleId, logger)
                logCoreDiagnostics(owner, logger)
                return@withContext fail(
                    logger,
                    "Listener loaded successfully but the container client could not be configured"
                )
            }

            if (!verifyContainerClientDetailed(containerName, server, sink, logger)) {
                logger?.w(
                    "[!] Listener module ${listener.moduleId} exists, but the container could not " +
                        "reach/authenticate $server"
                )
                if (listener.createdNow) unloadListener(owner, listener.moduleId, logger)
                logCoreDiagnostics(owner, logger)
                return@withContext fail(
                    logger,
                    "PulseAudio listener was loaded but the DroidSpaces container data path failed"
                )
            }

            if (alreadyConfigured) logger?.i("[+] Persistent container audio client already configured")
            if (port != BASE_PORT) logger?.i("[+] Selected audio port: $port")
            logger?.i("[+] Authenticated PulseAudio listener ready on $endpoint:$port")
            FixSettings.setPulseAudioApplied(context, containerName, true)
            logger?.i("[+] Audio ready (${listener.sink}, $server)")
            return@withContext true
        }

        logCoreDiagnostics(owner, logger)
        fail(logger, "Could not create an authenticated PulseAudio listener in $BASE_PORT-$maxPort")
    }

    private suspend fun fail(logger: ContainerLogger?, message: String): Boolean {
        logger?.w("[!] $message")
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
            else result.out.firstOrNull()?.trim()?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let(::TermuxOwner)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Run a command as the Termux UID but keep all PulseAudio control on the
     * private UNIX socket. The command is executed in a subshell so explicit
     * `exit` calls do not suppress the synthetic exit-code marker.
     */
    private fun execAsTermux(owner: TermuxOwner, command: String): DirectResult {
        val marker = "__SAAS_DIRECT_RC__"
        val wrapped = """
            export HOME=${q(TERMUX_HOME)}
            export PREFIX=${q(TERMUX_PREFIX)}
            export TMPDIR=${q("$TERMUX_PREFIX/tmp")}
            export PATH=${q("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")}
            (
            $command
            )
            rc=${'$'}?
            printf '%s%s\n' ${q(marker)} "${'$'}rc"
            exit 0
        """.trimIndent()

        return try {
            val shellResult = Shell.cmd("su ${owner.uid} -c ${q(wrapped)}").exec()
            val rawOut = shellResult.out.toList()
            val markerLine = rawOut.lastOrNull { it.trim().startsWith(marker) }?.trim()
            val exitCode = markerLine?.removePrefix(marker)?.toIntOrNull()
                ?: if (shellResult.isSuccess) 0 else 255
            val out = rawOut.filterNot { it.trim().startsWith(marker) }
            DirectResult(exitCode, out, shellResult.err.toList())
        } catch (e: Exception) {
            DirectResult(255, emptyList(), listOf(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun unixPactl(owner: TermuxOwner, arguments: String): DirectResult =
        execAsTermux(
            owner,
            "PULSE_SERVER=${q("unix:$CONTROL")} " +
                "PULSE_COOKIE=${q(COOKIE)} pactl $arguments"
        )

    private suspend fun verifyCore(owner: TermuxOwner, logger: ContainerLogger?): String? {
        val info = unixPactl(owner, "info")
        if (info.exitCode != 0) {
            logger?.w("[PA-CORE] pactl info exit=${info.exitCode}")
            logLines(logger, "[PA-CORE][stdout]", info.stdout)
            logLines(logger, "[PA-CORE][stderr]", info.stderr)
            logCoreDiagnostics(owner, logger)
            return null
        }

        val sinks = unixPactl(owner, "list short sinks")
        if (sinks.exitCode != 0) {
            logger?.w("[PA-CORE] pactl list short sinks exit=${sinks.exitCode}")
            logLines(logger, "[PA-CORE][stderr]", sinks.stderr)
            return null
        }

        val sink = when {
            sinks.stdout.any { Regex("""\sAAudio_sink\s""").containsMatchIn(" $it ") } ->
                "AAudio_sink"
            sinks.stdout.any { Regex("""\sOpenSL_ES_sink\s""").containsMatchIn(" $it ") } ->
                "OpenSL_ES_sink"
            else -> null
        } ?: run {
            logger?.w("[PA-CORE] No Android audio sink found")
            logLines(logger, "[PA-CORE][sinks]", sinks.stdout)
            return null
        }

        val setDefault = unixPactl(owner, "set-default-sink ${q(sink)}")
        if (setDefault.exitCode != 0) {
            logger?.w("[PA-CORE] Could not set default sink $sink")
            logLines(logger, "[PA-CORE][stderr]", setDefault.stderr)
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
        if (!hostOwnsIpv4(ip)) {
            if (emitFullFailure) logger?.w("[PA-LOAD] Android host does not own endpoint $ip")
            return null
        }

        val before = unixPactl(owner, "list short modules")
        if (before.exitCode != 0) {
            if (emitFullFailure) {
                logger?.w("[PA-LOAD] Could not list PulseAudio modules before listener load")
                logLines(logger, "[PA-LOAD][stderr]", before.stderr)
            }
            return null
        }

        findListenerModule(before.stdout, ip, port)?.let { existing ->
            logger?.i("[+] Reusing PulseAudio listener module $existing on $ip:$port")
            return Listener(port, existing, expectedSink, createdNow = false)
        }

        val arguments =
            "load-module module-native-protocol-tcp " +
                "${q("listen=$ip")} ${q("port=$port")} ${q("auth-cookie=$COOKIE")}"

        logger?.i("[PA-LOAD] endpoint=$ip:$port")
        val load = unixPactl(owner, arguments)
        logger?.i("[PA-LOAD] exit=${load.exitCode}")
        if (load.stdout.isNotEmpty()) {
            logger?.i("[PA-LOAD] stdout=${load.stdout.joinToString(" ").take(400)}")
        }
        if (load.stderr.isNotEmpty()) {
            logger?.w("[PA-LOAD] stderr=${load.stderr.joinToString(" ").take(400)}")
        }

        val id = load.stdout.asSequence()
            .map { it.trim() }
            .firstOrNull { it.matches(Regex("""\d+""")) }
            ?.toIntOrNull()

        if (load.exitCode != 0 || id == null) {
            if (emitFullFailure) logCoreDiagnostics(owner, logger)
            return null
        }

        val after = unixPactl(owner, "list short modules")
        if (after.exitCode != 0) {
            logger?.w("[PA-MODULE] Could not verify module id=$id through UNIX control socket")
            unloadListener(owner, id, logger)
            if (emitFullFailure) logCoreDiagnostics(owner, logger)
            return null
        }

        val verified = findListenerModule(after.stdout, ip, port, requiredId = id)
        if (verified != id) {
            logger?.w("[PA-MODULE] id=$id was returned but is missing from the server module table")
            logLines(logger, "[PA-MODULE]", after.stdout.filter { it.contains("module-native-protocol-tcp") })
            unloadListener(owner, id, logger)
            if (emitFullFailure) logCoreDiagnostics(owner, logger)
            return null
        }

        val moduleLine = after.stdout.firstOrNull { line ->
            val fields = line.trim().split(Regex("""\s+"""), limit = 3)
            fields.getOrNull(0) == id.toString() &&
                fields.getOrNull(1) == "module-native-protocol-tcp"
        }
        if (moduleLine != null) logger?.i("[PA-MODULE] $moduleLine")
        logger?.i("[+] PulseAudio listener module loaded: id=$id endpoint=$ip:$port")

        return Listener(port, id, expectedSink, createdNow = true)
    }

    private fun findListenerModule(
        lines: List<String>,
        ip: String,
        port: Int,
        requiredId: Int? = null
    ): Int? {
        for (line in lines) {
            val fields = line.trim().split(Regex("""\s+"""), limit = 3)
            val id = fields.getOrNull(0)?.toIntOrNull() ?: continue
            if (requiredId != null && id != requiredId) continue
            if (fields.getOrNull(1) != "module-native-protocol-tcp") continue
            val args = fields.getOrNull(2).orEmpty()
            val tokens = args.split(Regex("""\s+""")).toSet()
            if ("listen=$ip" in tokens && "port=$port" in tokens) return id
        }
        return null
    }

    private suspend fun unloadListener(owner: TermuxOwner, moduleId: Int, logger: ContainerLogger?) {
        val result = unixPactl(owner, "unload-module ${q(moduleId.toString())}")
        if (result.exitCode != 0) {
            logger?.w("[PA-UNLOAD] module=$moduleId exit=${result.exitCode}")
            logLines(logger, "[PA-UNLOAD][stderr]", result.stderr)
        }
    }

    private suspend fun cookieOctal(owner: TermuxOwner, logger: ContainerLogger?): String? {
        val result = execAsTermux(
            owner,
            """
                [ -f ${q(COOKIE)} ] || exit 1
                [ "${'$'}(wc -c < ${q(COOKIE)} 2>/dev/null | tr -d ' ')" = 256 ] || exit 2
                od -An -v -tu1 ${q(COOKIE)} 2>/dev/null |
                    awk '{ for (i=1; i<=NF; i++) printf "\\\\0%03o", ${'$'}i }'
            """.trimIndent()
        )
        if (result.exitCode != 0) {
            logger?.w("[PA-COOKIE] encode exit=${result.exitCode}")
            logLines(logger, "[PA-COOKIE][stderr]", result.stderr)
            return null
        }
        return result.stdout.joinToString("").trim().takeIf { it.isNotEmpty() }
    }

    private suspend fun logCoreDiagnostics(owner: TermuxOwner, logger: ContainerLogger?) {
        val modules = unixPactl(owner, "list short modules")
        modules.stdout.filter { it.contains("module-native-protocol-tcp") }
            .takeLast(12)
            .forEach { logger?.w("[PA-DIAG][module] $it") }

        val result = execAsTermux(
            owner,
            "tail -n 80 ${q(PULSE_LOG)} 2>/dev/null || true"
        )
        result.stdout.filter { it.isNotBlank() }
            .takeLast(80)
            .forEach { logger?.w("[PA-DIAG][log] $it") }
    }

    private suspend fun logLines(
        logger: ContainerLogger?,
        prefix: String,
        lines: List<String>,
        max: Int = 12
    ) {
        lines.filter { it.isNotBlank() }
            .takeLast(max)
            .forEach { logger?.w("$prefix $it") }
    }

    private suspend fun discoverNatGateway(
        info: ContainerInfo,
        logger: ContainerLogger?
    ): String? {
        val pid = info.pid ?: return null
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            pid=$pid
            gw=''
            if [ -x ${q(busybox)} ]; then
                gw=${'$'}(${q(busybox)} nsenter -t "${'$'}pid" -n ${q(busybox)} ip -4 route show default 2>/dev/null |
                    sed -n 's/^default via \([0-9.][0-9.]*\).*/\1/p' | sed -n '1p')
            fi
            if [ -z "${'$'}gw" ]; then
                hex=${'$'}(while read ifc dst g rest; do
                    [ "${'$'}dst" = 00000000 ] || continue
                    printf '%s\n' "${'$'}g"
                    break
                done < "/proc/${'$'}pid/net/route" 2>/dev/null)
                case "${'$'}hex" in
                    ????????)
                        b1=${'$'}(printf '%s' "${'$'}hex" | cut -c7-8)
                        b2=${'$'}(printf '%s' "${'$'}hex" | cut -c5-6)
                        b3=${'$'}(printf '%s' "${'$'}hex" | cut -c3-4)
                        b4=${'$'}(printf '%s' "${'$'}hex" | cut -c1-2)
                        gw=${'$'}(printf '%d.%d.%d.%d' "0x${'$'}b1" "0x${'$'}b2" "0x${'$'}b3" "0x${'$'}b4" 2>/dev/null || true)
                        ;;
                esac
            fi
            printf '%s\n' "${'$'}gw"
        """.trimIndent()

        val discovered = try {
            Shell.cmd(command).exec().out.firstOrNull()?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }

        return when {
            validIpv4(discovered) && hostOwnsIpv4(discovered) -> discovered
            hostOwnsIpv4("172.28.0.1") -> {
                logger?.w(
                    "[!] Live NAT route was not readable; using verified DroidSpaces gateway 172.28.0.1"
                )
                "172.28.0.1"
            }
            else -> null
        }
    }

    private fun hostOwnsIpv4(ip: String): Boolean {
        if (!validIpv4(ip)) return false
        if (ip == "127.0.0.1") return true
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            ip=${q(ip)}
            if [ -x /system/bin/ip ]; then
                out=${'$'}(/system/bin/ip -4 -o addr show 2>/dev/null)
            elif [ -x ${q(busybox)} ]; then
                out=${'$'}(${q(busybox)} ip -4 -o addr show 2>/dev/null)
            else
                out=${'$'}(ip -4 -o addr show 2>/dev/null || true)
            fi
            printf '%s\n' "${'$'}out" | grep -Eq "[[:space:]]inet[[:space:]]${'$'}ip/"
        """.trimIndent()
        return try {
            Shell.cmd(command).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun validIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
    }

    private fun configuredPortForwardOwner(port: Int): String? {
        val command = """
            wanted=$port
            for cfg in ${q(Constants.CONTAINERS_DIR)}/*/${q(Constants.CONFIG_FILE)}; do
                [ -f "${'$'}cfg" ] || continue
                net=${'$'}(sed -n 's/^net_mode=//p' "${'$'}cfg" | tail -n 1)
                [ "${'$'}net" = nat ] || continue
                name=${'$'}(sed -n 's/^name=//p' "${'$'}cfg" | sed -n '1p')
                [ -n "${'$'}name" ] || { d=${'$'}{cfg%/${Constants.CONFIG_FILE}}; name=${'$'}{d##*/}; }
                value=${'$'}(sed -n 's/^port_forwards=//p' "${'$'}cfg" | sed -n '1p')
                oldifs=${'$'}IFS
                IFS=,
                for tok in ${'$'}value; do
                    IFS=${'$'}oldifs
                    tok=${'$'}(printf '%s' "${'$'}tok" | tr -d '[:space:]')
                    [ -n "${'$'}tok" ] || { IFS=,; continue; }
                    case "${'$'}tok" in
                        */*) proto=${'$'}{tok##*/}; body=${'$'}{tok%/*} ;;
                        *) proto=tcp; body=${'$'}tok ;;
                    esac
                    [ "${'$'}proto" = tcp ] || { IFS=,; continue; }
                    host=${'$'}{body%%:*}
                    case "${'$'}host" in
                        *-*) start=${'$'}{host%-*}; end=${'$'}{host#*-} ;;
                        *) start=${'$'}host; end=${'$'}host ;;
                    esac
                    case "${'$'}start:${'$'}end" in
                        *[!0-9:]*|'':*) : ;;
                        *)
                            if [ "${'$'}wanted" -ge "${'$'}start" ] 2>/dev/null &&
                               [ "${'$'}wanted" -le "${'$'}end" ] 2>/dev/null; then
                                printf '%s\n' "${'$'}name"
                                exit 0
                            fi
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
            if (result.isSuccess) {
                result.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun verifyContainerClient(containerName: String, server: String): Boolean {
        val payload = """
            server=${q(server)}
            cookie=/root/.config/pulse/saas-audio.cookie
            command -v pactl >/dev/null 2>&1 || exit 70
            [ -f "${'$'}cookie" ] || exit 71
            [ "${'$'}(wc -c < "${'$'}cookie" 2>/dev/null | tr -d ' ')" = 256 ] || exit 72
            info=${'$'}(PULSE_SERVER="${'$'}server" PULSE_COOKIE="${'$'}cookie" pactl info 2>/dev/null) || exit 73
            printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${'$'}server" || exit 74
            printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$'
        """.trimIndent()
        val command =
            "${Constants.DS_BINARY_PATH} --name=${q(containerName)} run /bin/sh -lc ${q(payload)}"
        return try {
            Shell.cmd(command).exec().isSuccess
        } catch (_: Exception) {
            false
        }
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
            info=${'$'}(PULSE_SERVER="${'$'}server" PULSE_COOKIE="${'$'}cookie" pactl info 2>&1) || {
                printf '%s\n' "${'$'}info"
                exit 82
            }
            printf '%s\n' "${'$'}info" | grep -E '^(Server String|Server Version|Default Sink|Default Source):' || true
            printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${'$'}server" || exit 83
            printf '%s\n' "${'$'}info" | grep -Fq "Default Sink: ${'$'}expected" || exit 84
        """.trimIndent()

        val command =
            "${Constants.DS_BINARY_PATH} --name=${q(containerName)} run /bin/sh -lc ${q(payload)}"
        return try {
            val result = Shell.cmd(command).exec()
            result.out.filter { it.isNotBlank() }.takeLast(12).forEach { logger?.i(it) }
            result.err.filter { it.isNotBlank() }.takeLast(12)
                .forEach { logger?.w("[CONTAINER] $it") }
            if (!result.isSuccess) {
                logger?.w("[CONTAINER] pactl verification failed for $server")
            }
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
        val payload = """
            set -u
            SERVER=${q(server)}
            COOKIE_ESCAPED=${q(octal)}

            need=0
            command -v pactl >/dev/null 2>&1 || need=1
            command -v speaker-test >/dev/null 2>&1 || need=1

            if [ "${'$'}need" -eq 1 ]; then
                if command -v apt-get >/dev/null 2>&1; then
                    echo __APT__
                    DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1 || true
                    DEBIAN_FRONTEND=noninteractive apt-get install -y \
                        pulseaudio-utils libasound2-plugins alsa-utils >/dev/null 2>&1 || true
                elif command -v apk >/dev/null 2>&1; then
                    echo __APK__
                    apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse >/dev/null 2>&1 || true
                fi
            fi

            command -v pactl >/dev/null 2>&1 || exit 60
            mkdir -p /root/.config/pulse /etc/profile.d || exit 61

            cookie=/root/.config/pulse/saas-audio.cookie
            printf '%b' "${'$'}COOKIE_ESCAPED" > "${'$'}cookie" || exit 62
            [ "${'$'}(wc -c < "${'$'}cookie" | tr -d ' ')" = 256 ] || exit 63
            chmod 600 "${'$'}cookie" 2>/dev/null || true

            client=/root/.config/pulse/client.conf
            if [ -f "${'$'}client" ] &&
               ! grep -Fq ${q(MANAGED)} "${'$'}client" 2>/dev/null &&
               [ ! -e "${'$'}client.saas-x11-manager.bak" ]; then
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
            if command -v aplay >/dev/null 2>&1 &&
               PULSE_SERVER="${'$'}SERVER" PULSE_COOKIE="${'$'}cookie" aplay -L 2>/dev/null |
                    grep -q '^pulse'; then
                if [ -f "${'$'}asound" ] &&
                   ! grep -Fq ${q(MANAGED)} "${'$'}asound" 2>/dev/null &&
                   [ ! -e "${'$'}asound.saas-x11-manager.bak" ]; then
                    cp -p "${'$'}asound" "${'$'}asound.saas-x11-manager.bak" || true
                fi
                cat > "${'$'}asound" <<EOF_ASOUND
$BEGIN
pcm.!default { type pulse }
ctl.!default { type pulse }
$END
EOF_ASOUND
            fi

            info=${'$'}(PULSE_SERVER="${'$'}SERVER" PULSE_COOKIE="${'$'}cookie" pactl info 2>&1) || {
                printf '%s\n' "${'$'}info" >&2
                exit 65
            }
            printf '%s\n' "${'$'}info" |
                grep -E '^(Server String|Server Version|Default Sink|Default Source):' || true
            printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${'$'}SERVER" || exit 66
            printf '%s\n' "${'$'}info" |
                grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$' || exit 67
            echo __READY__
        """.trimIndent()

        val command =
            "${Constants.DS_BINARY_PATH} --name=${q(containerName)} run /bin/sh -lc ${q(payload)}"

        return try {
            val result = Shell.cmd(command).exec()
            result.out.forEach { line ->
                when {
                    line.trim() == "__APT__" ->
                        logger?.i("[*] Installing missing Debian/Ubuntu audio clients...")
                    line.trim() == "__APK__" ->
                        logger?.i("[*] Installing missing Alpine audio clients...")
                    line.startsWith("Server String:") ||
                        line.startsWith("Server Version:") ||
                        line.startsWith("Default Sink:") ||
                        line.startsWith("Default Source:") -> logger?.i(line)
                }
            }
            result.err.filter { it.isNotBlank() }.take(12)
                .forEach { logger?.w("[CONTAINER] $it") }
            result.isSuccess && result.out.any { it.trim() == "__READY__" }
        } catch (e: Exception) {
            logger?.w("[CONTAINER] ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
