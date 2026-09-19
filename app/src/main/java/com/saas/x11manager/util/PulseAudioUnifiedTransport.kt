package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Final HOST/NAT data-path adapter for the single Manager-owned PulseAudio core.
 *
 * Validation status:
 * - HOST is the physically validated APK baseline.
 * - NAT uses the DroidSpaces runtime topology and remains experimental until
 *   the complete APK path is physically verified on-device.
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

    // DroidSpaces v6.5.0 uses this address in both full-bridge and bridgeless NAT.
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
            else -> resolveNatEndpoint(info, logger)
        } ?: return@withContext fail(logger, "Automatic NAT audio endpoint discovery failed")

        val cookieOctal = cookieOctal(owner, logger)
            ?: return@withContext fail(
                logger,
                "Could not prepare the private PulseAudio cookie for the container"
            )

        logger?.i("[CTX] Audio net_mode: $mode")
        logger?.i("[CTX] Audio host endpoint: $endpoint (port selected automatically)")
        if (mode == "nat") {
            logger?.i("[CTX] NAT transport status: experimental until physical APK verification")
            logNatEndpointDiagnostics(endpoint, logger)
        }

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
        fail(
            logger,
            "Could not create an authenticated PulseAudio listener on $endpoint " +
                "using ports $BASE_PORT-$maxPort"
        )
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
     * Run as the Termux UID while keeping control on the already-running core's
     * private UNIX socket. Changing UID does not intentionally enter a network
     * namespace here.
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
            logLines(
                logger,
                "[PA-MODULE]",
                after.stdout.filter { it.contains("module-native-protocol-tcp") }
            )
            unloadListener(owner, id, logger)
            if (emitFullFailure) logCoreDiagnostics(owner, logger)
            return null
        }

        after.stdout.firstOrNull { line ->
            val fields = line.trim().split(Regex("""\s+"""), limit = 3)
            fields.getOrNull(0) == id.toString() &&
                fields.getOrNull(1) == "module-native-protocol-tcp"
        }?.let { logger?.i("[PA-MODULE] $it") }

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
            val tokens = fields.getOrNull(2).orEmpty().split(Regex("""\s+""")).toSet()
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

    /**
     * DroidSpaces v6.5.0 defines 172.28.0.1 as the NAT gateway in both:
     * - full bridge: ds-br0 owns 172.28.0.1/16;
     * - bridgeless fallback: ds-v<PID> owns 172.28.0.1/32.
     *
     * Prefer the live container route so future compatible DroidSpaces layouts
     * can still work. If route inspection is unavailable, use the v6.5.0
     * canonical gateway. Host address enumeration is intentionally not a gate:
     * the exact PulseAudio bind is the authoritative local-address test.
     */
    private suspend fun resolveNatEndpoint(
        info: ContainerInfo,
        logger: ContainerLogger?
    ): String? {
        val discovered = discoverContainerDefaultGateway(info)
        if (usableIpv4(discovered)) {
            logger?.i("[CTX] NAT route gateway: $discovered")
            if (discovered != DROIDSPACES_NAT_GATEWAY) {
                logger?.w(
                    "[!] NAT gateway differs from DroidSpaces v6.5.0 canonical " +
                        "$DROIDSPACES_NAT_GATEWAY; using the live route value"
                )
            }
            return discovered
        }

        logger?.w(
            "[!] Live NAT route was not readable; using DroidSpaces v6.5.0 " +
                "gateway $DROIDSPACES_NAT_GATEWAY"
        )
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

        return try {
            Shell.cmd(command).exec().out.firstOrNull()?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /** Diagnostic only. Never blocks listener creation. */
    private suspend fun logNatEndpointDiagnostics(
        endpoint: String,
        logger: ContainerLogger?
    ) {
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            endpoint=${q(endpoint)}
            found=''
            if [ -x /system/bin/ip ]; then
                found=${'$'}(/system/bin/ip -4 -o addr show 2>/dev/null |
                    grep -F " inet ${'$'}endpoint/" | sed -n '1p' || true)
            fi
            if [ -z "${'$'}found" ] && [ -x ${q(busybox)} ]; then
                found=${'$'}(${q(busybox)} ip -4 -o addr show 2>/dev/null |
                    grep -F " inet ${'$'}endpoint/" | sed -n '1p' || true)
            fi
            if [ -z "${'$'}found" ] && command -v ip >/dev/null 2>&1; then
                found=${'$'}(ip -4 -o addr show 2>/dev/null |
                    grep -F " inet ${'$'}endpoint/" | sed -n '1p' || true)
            fi

            core_pid=${'$'}(sed -n 's/^pid=//p' ${q(CORE_PID_FILE)} 2>/dev/null | sed -n '1p')
            case "${'$'}core_pid" in ''|*[!0-9]*) core_pid='' ;; esac
            host_net=${'$'}(readlink /proc/1/ns/net 2>/dev/null || true)
            core_net=''
            [ -n "${'$'}core_pid" ] && core_net=${'$'}(readlink "/proc/${'$'}core_pid/ns/net" 2>/dev/null || true)
            nonlocal=${'$'}(cat /proc/sys/net/ipv4/ip_nonlocal_bind 2>/dev/null || printf '?')

            printf 'ADDR=%s\n' "${'$'}found"
            printf 'HOST_NETNS=%s\n' "${'$'}host_net"
            printf 'CORE_PID=%s\n' "${'$'}core_pid"
            printf 'CORE_NETNS=%s\n' "${'$'}core_net"
            printf 'IP_NONLOCAL_BIND=%s\n' "${'$'}nonlocal"
        """.trimIndent()

        val result = try {
            Shell.cmd(command).exec()
        } catch (_: Exception) {
            null
        }

        val lines = result?.out.orEmpty()
        val address = lines.firstOrNull { it.startsWith("ADDR=") }
            ?.removePrefix("ADDR=")
            .orEmpty()
        val hostNet = lines.firstOrNull { it.startsWith("HOST_NETNS=") }
            ?.removePrefix("HOST_NETNS=")
            .orEmpty()
        val corePid = lines.firstOrNull { it.startsWith("CORE_PID=") }
            ?.removePrefix("CORE_PID=")
            .orEmpty()
        val coreNet = lines.firstOrNull { it.startsWith("CORE_NETNS=") }
            ?.removePrefix("CORE_NETNS=")
            .orEmpty()
        val nonlocal = lines.firstOrNull { it.startsWith("IP_NONLOCAL_BIND=") }
            ?.removePrefix("IP_NONLOCAL_BIND=")
            .orEmpty()

        if (address.isNotBlank()) {
            logger?.i("[CTX] Android endpoint observation: $address")
        } else {
            logger?.w(
                "[!] Android address enumeration did not show $endpoint; " +
                    "the exact PulseAudio bind will be authoritative"
            )
        }

        if (corePid.isNotBlank()) logger?.i("[CTX] PulseAudio core PID: $corePid")
        if (hostNet.isNotBlank() || coreNet.isNotBlank()) {
            logger?.i(
                "[CTX] Network namespace: host=${hostNet.ifBlank { "unknown" }} " +
                    "core=${coreNet.ifBlank { "unknown" }}"
            )
        }
        if (nonlocal.isNotBlank()) logger?.i("[CTX] IPv4 ip_nonlocal_bind: $nonlocal")
    }

    private fun usableIpv4(value: String): Boolean {
        if (!validIpv4(value)) return false
        return value != "0.0.0.0" && value != "255.255.255.255"
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
