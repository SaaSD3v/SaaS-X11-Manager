package com.saas.x11manager.util

import android.content.pm.PackageManager
import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Uses the already prepared single Manager PulseAudio core.
 *
 * HOST keeps the physically validated PulseAudioFixManager path.
 * NAT loads the TCP listener through the real Termux RunCommandService, verifies
 * the loaded module through the private UNIX control socket, and verifies the
 * TCP data path from the running DroidSpaces container (the actual client).
 *
 * This class never starts/stops/restarts PulseAudio, DroidSpaces, X11 or VNC.
 */
object PulseAudioDataPathTransport {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val RUN_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_SERVICE = "com.termux.app.RunCommandService"
    private const val RUN_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val RUN_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val RUN_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_SH = "$TERMUX_PREFIX/bin/sh"

    private const val STATE = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val COMMANDS = "$STATE/run-command-datapath"
    private const val COOKIE = "$STATE/transport.cookie"
    private const val CONTROL = "$STATE/control.sock"
    private const val POLICY_STATE = "$STATE/termux-external-apps.state"
    private const val POLICY_BACKUP = "$STATE/termux.properties.before-run-command.bak"

    private const val BASE_PORT = 4713
    private const val MAX_PORT_SHIFT = 64
    private const val MANAGED = "SaaS X11 Manager Audio Configuration"
    private const val BEGIN = "# BEGIN $MANAGED"
    private const val END = "# END $MANAGED"

    private data class TermuxOwner(val uid: Int, val gid: Int)
    private data class Listener(val port: Int, val moduleId: Int, val sink: String, val createdNow: Boolean)

    suspend fun finalizeAfterContainerReady(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext true

        val info = ContainerManager.getContainerInfo(containerName)
            ?: return@withContext fail(logger, "Container $containerName was not found")
        val mode = info.netMode.trim().lowercase()

        if (mode == "host") {
            return@withContext PulseAudioFixManager.finalizeAfterContainerReady(containerName, logger)?.success ?: true
        }
        if (mode != "nat") {
            return@withContext fail(logger, "Audio configuration supports net_mode=host and net_mode=nat only (current: ${info.netMode})")
        }
        if (!info.isRunning) {
            return@withContext fail(logger, "Container $containerName is not running; audio client was not changed")
        }

        val owner = termuxOwner()
            ?: return@withContext fail(logger, "Termux owner could not be resolved")
        if (!ensureRunPermission(logger)) {
            return@withContext fail(logger, "Termux RUN_COMMAND permission is unavailable")
        }
        if (!ensureExternalAppsPolicy(owner, logger)) {
            return@withContext fail(logger, "Termux allow-external-apps policy is unavailable")
        }

        logger?.i("[CTX] Audio control executor: root am startservice -> Termux RunCommandService")
        logger?.i("[CTX] Listener verifier: DroidSpaces container data path")
        logger?.i("[CTX] RUN_COMMAND permission: granted")

        val sink = verifyCore(owner, logger)
            ?: return@withContext fail(logger, "Manager audio core is not reachable from the real Termux context")
        val endpoint = discoverNatGateway(info, logger)
            ?: return@withContext fail(logger, "Automatic NAT gateway discovery failed")
        val cookieOctal = cookieOctal(owner, logger)
            ?: return@withContext fail(logger, "Could not prepare the private PulseAudio cookie for the container")

        logger?.i("[CTX] Audio net_mode: nat")
        logger?.i("[CTX] Audio host endpoint: $endpoint (port selected automatically)")

        val maxPort = (BASE_PORT + MAX_PORT_SHIFT).coerceAtMost(65535)
        for (port in BASE_PORT..maxPort) {
            val reservedBy = configuredPortForwardOwner(port)
            if (reservedBy != null) {
                if (port == BASE_PORT) {
                    logger?.w("[!] Port $port is reserved by DroidSpaces TCP port-forward in $reservedBy; selecting another audio port automatically")
                }
                continue
            }

            val listener = loadOrReuseListener(owner, endpoint, port, sink, logger)
            if (listener == null) {
                if (port == BASE_PORT) {
                    logger?.w("[!] Port $port could not load an authenticated listener on $endpoint; selecting another automatically")
                }
                continue
            }

            val server = "tcp:$endpoint:$port"
            val configured = verifyContainerClient(containerName, server) ||
                installContainerClient(containerName, server, cookieOctal, logger)

            if (configured && verifyContainerClientDetailed(containerName, server, sink, logger)) {
                if (port != BASE_PORT) logger?.i("[+] Selected audio port: $port")
                logger?.i("[+] Authenticated PulseAudio listener ready on $endpoint:$port")
                FixSettings.setPulseAudioApplied(context, containerName, true)
                logger?.i("[+] Audio ready (${listener.sink}, $server)")
                return@withContext true
            }

            logger?.w("[!] Container could not reach $server; trying another audio port")
            if (listener.createdNow) unloadListener(owner, listener.moduleId, logger)
        }

        fail(logger, "Could not create a container-reachable authenticated audio listener")
    }

    private suspend fun fail(logger: ContainerLogger?, message: String): Boolean {
        logger?.w("[!] $message")
        logger?.w("[!] Graphical startup will continue")
        return false
    }

    private suspend fun verifyCore(owner: TermuxOwner, logger: ContainerLogger?): String? {
        val body = """
            CONTROL=${q(CONTROL)}; COOKIE=${q(COOKIE)}; unix="unix:${'$'}CONTROL"
            info=${'$'}(PULSE_SERVER="${'$'}unix" PULSE_COOKIE="${'$'}COOKIE" pactl info 2>&1) || {
                printf '%s\n' "${'$'}info" >&2; echo 'ERR|CORE|connect'; exit 1;
            }
            sinks=${'$'}(PULSE_SERVER="${'$'}unix" PULSE_COOKIE="${'$'}COOKIE" pactl list short sinks 2>&1) || {
                printf '%s\n' "${'$'}sinks" >&2; echo 'ERR|CORE|sinks'; exit 2;
            }
            if printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]AAudio_sink[[:space:]]'; then sink=AAudio_sink
            elif printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]OpenSL_ES_sink[[:space:]]'; then sink=OpenSL_ES_sink
            else printf '%s\n' "${'$'}sinks" >&2; echo 'ERR|CORE|sink'; exit 3; fi
            printf 'OK|CORE|%s\n' "${'$'}sink"
        """.trimIndent()
        val result = runTermux(owner, "core-probe", body, 4000, logger) ?: return null
        return result.takeIf { it.startsWith("OK|CORE|") }
            ?.substringAfter("OK|CORE|")
            ?.takeIf { it in setOf("AAudio_sink", "OpenSL_ES_sink") }
    }

    private suspend fun loadOrReuseListener(
        owner: TermuxOwner,
        ip: String,
        port: Int,
        expectedSink: String,
        logger: ContainerLogger?
    ): Listener? {
        if (!hostOwnsIpv4(ip)) return null
        val body = """
            IP=${q(ip)}; PORT=${q(port.toString())}; CONTROL=${q(CONTROL)}; COOKIE=${q(COOKIE)}; EXPECTED=${q(expectedSink)}
            unix="unix:${'$'}CONTROL"
            modules=${'$'}(PULSE_SERVER="${'$'}unix" PULSE_COOKIE="${'$'}COOKIE" pactl list short modules 2>&1) || {
                printf '%s\n' "${'$'}modules" >&2; echo 'ERR|MODULES|list'; exit 1;
            }
            existing=${'$'}(printf '%s\n' "${'$'}modules" | awk -v ip="${'$'}IP" -v port="${'$'}PORT" '
                ${'$'}2 == "module-native-protocol-tcp" && index($0, "listen=" ip) && index($0, "port=" port) { print ${'$'}1; exit }
            ')
            case "${'$'}existing" in
                [0-9]*) printf 'OK|REUSE|%s|%s\n' "${'$'}existing" "${'$'}EXPECTED"; exit 0 ;;
            esac

            err=${q("$COMMANDS/.listener-load")}.${'$'}${'$'}.${'$'}PORT.err
            : > "${'$'}err"
            id=${'$'}(PULSE_SERVER="${'$'}unix" PULSE_COOKIE="${'$'}COOKIE" pactl load-module module-native-protocol-tcp \
                "listen=${'$'}IP" "port=${'$'}PORT" "auth-cookie=${'$'}COOKIE" 2>"${'$'}err")
            rc=${'$'}?
            if [ "${'$'}rc" -ne 0 ]; then
                msg=${'$'}(tr '\n' ' ' < "${'$'}err" 2>/dev/null | sed 's/[[:space:]][[:space:]]*/ /g' | cut -c1-300)
                rm -f "${'$'}err" 2>/dev/null || true
                printf 'ERR|LOAD|%s|%s\n' "${'$'}rc" "${'$'}msg"
                exit 4
            fi
            rm -f "${'$'}err" 2>/dev/null || true
            case "${'$'}id" in ''|*[!0-9]*) printf 'ERR|LOAD|id:%s\n' "${'$'}id"; exit 5 ;; esac

            modules=${'$'}(PULSE_SERVER="${'$'}unix" PULSE_COOKIE="${'$'}COOKIE" pactl list short modules 2>&1) || {
                printf '%s\n' "${'$'}modules" >&2; printf 'ERR|VERIFY|%s|list\n' "${'$'}id"; exit 6;
            }
            printf '%s\n' "${'$'}modules" | awk -v id="${'$'}id" -v ip="${'$'}IP" -v port="${'$'}PORT" '
                ${'$'}1 == id && ${'$'}2 == "module-native-protocol-tcp" && index($0, "listen=" ip) && index($0, "port=" port) { ok=1 }
                END { exit(ok ? 0 : 1) }
            ' || {
                PULSE_SERVER="${'$'}unix" PULSE_COOKIE="${'$'}COOKIE" pactl unload-module "${'$'}id" >/dev/null 2>&1 || true
                printf 'ERR|VERIFY|%s|missing\n' "${'$'}id"; exit 7;
            }
            printf 'OK|CREATED|%s|%s\n' "${'$'}id" "${'$'}EXPECTED"
        """.trimIndent()

        val result = runTermux(owner, "listener-${ip.replace('.', '_')}-$port", body, 3500, logger)
            ?: return null
        val parts = result.split('|', limit = 5)
        return when (parts.getOrNull(1)) {
            "REUSE", "CREATED" -> {
                val id = parts.getOrNull(2)?.toIntOrNull() ?: return null
                val sink = parts.getOrNull(3).orEmpty()
                if (sink != expectedSink) return null
                val created = parts[1] == "CREATED"
                if (created) logger?.i("[+] PulseAudio listener module loaded: id=$id endpoint=$ip:$port")
                else logger?.i("[+] Reusing PulseAudio listener module $id on $ip:$port")
                Listener(port, id, sink, created)
            }
            else -> {
                logger?.w("[TERMUX] listener $ip:$port -> $result")
                null
            }
        }
    }

    private suspend fun unloadListener(owner: TermuxOwner, moduleId: Int, logger: ContainerLogger?) {
        val body = """
            CONTROL=${q(CONTROL)}; COOKIE=${q(COOKIE)}; ID=${q(moduleId.toString())}
            PULSE_SERVER="unix:${'$'}CONTROL" PULSE_COOKIE="${'$'}COOKIE" pactl unload-module "${'$'}ID" >/dev/null 2>&1 || true
            printf 'OK|UNLOAD|%s\n' "${'$'}ID"
        """.trimIndent()
        runTermux(owner, "unload-$moduleId", body, 2000, logger)
    }

    private suspend fun cookieOctal(owner: TermuxOwner, logger: ContainerLogger?): String? {
        val body = """
            COOKIE=${q(COOKIE)}
            [ -f "${'$'}COOKIE" ] || { echo 'ERR|COOKIE|missing'; exit 1; }
            [ "${'$'}(wc -c < "${'$'}COOKIE" 2>/dev/null | tr -d ' ')" = 256 ] || { echo 'ERR|COOKIE|size'; exit 2; }
            octal=${'$'}(od -An -v -tu1 "${'$'}COOKIE" 2>/dev/null | awk '{ for (i=1; i<=NF; i++) printf "\\\\0%03o", ${'$'}i }')
            [ -n "${'$'}octal" ] || { echo 'ERR|COOKIE|encode'; exit 3; }
            printf 'OK|COOKIE|%s\n' "${'$'}octal"
        """.trimIndent()
        val result = runTermux(owner, "cookie", body, 3000, logger) ?: return null
        return result.takeIf { it.startsWith("OK|COOKIE|") }
            ?.substringAfter("OK|COOKIE|")
            ?.takeIf { it.isNotBlank() }
    }

    private fun termuxOwner(): TermuxOwner? {
        val command = """
            test -x ${q(TERMUX_SH)} || exit 1
            uid=${'$'}(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${q(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val r = Shell.cmd(command).exec()
            if (!r.isSuccess) return null
            val p = r.out.firstOrNull()?.trim()?.split('|') ?: return null
            val uid = p.getOrNull(0)?.toIntOrNull() ?: return null
            val gid = p.getOrNull(1)?.toIntOrNull() ?: return null
            if (uid > 0 && gid > 0) TermuxOwner(uid, gid) else null
        } catch (_: Exception) { null }
    }

    private suspend fun ensureRunPermission(logger: ContainerLogger?): Boolean {
        val context = X11Application.instance
        if (context.checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED) return true
        val result = try { Shell.cmd("pm grant ${q(context.packageName)} ${q(RUN_PERMISSION)} 2>&1").exec() } catch (_: Exception) { null }
        if (context.checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED) return true
        result?.out?.filter { it.isNotBlank() }?.take(4)?.forEach { logger?.w("[RUN_COMMAND] $it") }
        result?.err?.filter { it.isNotBlank() }?.take(4)?.forEach { logger?.w("[RUN_COMMAND] $it") }
        return false
    }

    private suspend fun ensureExternalAppsPolicy(owner: TermuxOwner, logger: ContainerLogger?): Boolean {
        val propsDir = "$TERMUX_HOME/.termux"
        val props = "$propsDir/termux.properties"
        val command = """
            uid=${owner.uid}; gid=${owner.gid}; props=${q(props)}; dir=${q(propsDir)}
            mkdir -p ${q(STATE)} ${q(COMMANDS)} "${'$'}dir" || exit 10
            chown "${'$'}uid:${'$'}gid" ${q(STATE)} ${q(COMMANDS)} "${'$'}dir" 2>/dev/null || true
            chmod 700 ${q(STATE)} ${q(COMMANDS)} "${'$'}dir" 2>/dev/null || true
            if [ ! -f ${q(POLICY_STATE)} ]; then
                if [ -f "${'$'}props" ]; then
                    cp -p "${'$'}props" ${q(POLICY_BACKUP)} || exit 11
                    printf 'had_file=1\n' > ${q(POLICY_STATE)} || exit 12
                else
                    printf 'had_file=0\n' > ${q(POLICY_STATE)} || exit 13
                fi
                chown "${'$'}uid:${'$'}gid" ${q(POLICY_STATE)} ${q(POLICY_BACKUP)} 2>/dev/null || true
                chmod 600 ${q(POLICY_STATE)} ${q(POLICY_BACKUP)} 2>/dev/null || true
            fi
            if ! grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*${'$'}' "${'$'}props" 2>/dev/null; then
                tmp="${'$'}props.saas-audio.${'$'}${'$'}"; : > "${'$'}tmp" || exit 14; found=0
                if [ -f "${'$'}props" ]; then
                    while IFS= read -r line || [ -n "${'$'}line" ]; do
                        case "${'$'}line" in
                            allow-external-apps=*)
                                if [ "${'$'}found" -eq 0 ]; then printf '%s\n' 'allow-external-apps=true' >> "${'$'}tmp"; found=1; fi ;;
                            *) printf '%s\n' "${'$'}line" >> "${'$'}tmp" ;;
                        esac
                    done < "${'$'}props"
                fi
                [ "${'$'}found" -eq 1 ] || printf '%s\n' 'allow-external-apps=true' >> "${'$'}tmp"
                mv -f "${'$'}tmp" "${'$'}props" || exit 15
                chown "${'$'}uid:${'$'}gid" "${'$'}props" 2>/dev/null || true
                chmod 600 "${'$'}props" 2>/dev/null || true
                restorecon -F "${'$'}props" >/dev/null 2>&1 || true
            fi
            am broadcast --user 0 -a com.termux.app.reload_style com.termux >/dev/null 2>&1 || exit 16
            grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*${'$'}' "${'$'}props" || exit 17
        """.trimIndent()
        val r = try { Shell.cmd(command).exec() } catch (_: Exception) { null }
        if (r?.isSuccess == true) return true
        r?.out?.filter { it.isNotBlank() }?.take(4)?.forEach { logger?.w("[TERMUX_POLICY] $it") }
        r?.err?.filter { it.isNotBlank() }?.take(4)?.forEach { logger?.w("[TERMUX_POLICY] $it") }
        return false
    }

    private fun prepareStateOwnership(owner: TermuxOwner) {
        val command = "mkdir -p ${q(STATE)} ${q(COMMANDS)}; chown -R ${owner.uid}:${owner.gid} ${q(STATE)} 2>/dev/null || true; chmod 700 ${q(STATE)} ${q(COMMANDS)} 2>/dev/null || true; restorecon -RF ${q(STATE)} >/dev/null 2>&1 || true"
        try { Shell.cmd(command).exec() } catch (_: Exception) { }
    }

    private suspend fun runTermux(
        owner: TermuxOwner,
        label: String,
        body: String,
        timeoutMs: Long,
        logger: ContainerLogger?
    ): String? {
        val token = "$label-${System.nanoTime()}"
        val launcher = "$COMMANDS/$token.sh"
        val resultFile = "$COMMANDS/$token.result"
        val launcherBody = """
            #!$TERMUX_SH
            export HOME=${q(TERMUX_HOME)}
            export PREFIX=${q(TERMUX_PREFIX)}
            export PATH=${q("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")}
            export TMPDIR=${q("$TERMUX_PREFIX/tmp")}
            exec >${q(resultFile)} 2>&1
            $body
        """.trimIndent() + "\n"

        prepareStateOwnership(owner)
        val prepare = """
            mkdir -p ${q(COMMANDS)} || exit 21
            rm -f ${q(launcher)} ${q(resultFile)} 2>/dev/null || true
            printf '%s' ${q(launcherBody)} > ${q(launcher)} || exit 22
            chown ${owner.uid}:${owner.gid} ${q(COMMANDS)} ${q(launcher)} || exit 23
            chmod 700 ${q(COMMANDS)} ${q(launcher)} || exit 24
            restorecon -F ${q(launcher)} >/dev/null 2>&1 || true
        """.trimIndent()
        val prepared = try { Shell.cmd(prepare).exec() } catch (_: Exception) { null }
        if (prepared?.isSuccess != true) {
            logger?.w("[!] RUN_COMMAND launcher preparation failed")
            prepared?.out?.filter { it.isNotBlank() }?.takeLast(4)?.forEach { logger?.w("[RUN_COMMAND] $it") }
            prepared?.err?.filter { it.isNotBlank() }?.takeLast(4)?.forEach { logger?.w("[RUN_COMMAND] $it") }
            return null
        }

        val component = "$TERMUX_PACKAGE/$RUN_SERVICE"
        val start = """
            am startservice --user 0 -n ${q(component)} \
                -a ${q(RUN_ACTION)} \
                --es ${q(RUN_PATH)} ${q(launcher)} \
                --es ${q(RUN_WORKDIR)} ${q(TERMUX_HOME)} \
                --ez ${q(RUN_BACKGROUND)} true
        """.trimIndent()
        val started = try { Shell.cmd(start).exec() } catch (_: Exception) { null }
        if (started?.isSuccess != true) {
            logger?.w("[!] RUN_COMMAND root am startservice failed")
            started?.out?.filter { it.isNotBlank() }?.takeLast(6)?.forEach { logger?.w("[RUN_COMMAND] $it") }
            started?.err?.filter { it.isNotBlank() }?.takeLast(6)?.forEach { logger?.w("[RUN_COMMAND] $it") }
            try { Shell.cmd("rm -f ${q(launcher)} ${q(resultFile)} 2>/dev/null || true").exec() } catch (_: Exception) { }
            return null
        }

        val loops = (timeoutMs / 100L).coerceAtLeast(1).toInt()
        repeat(loops) {
            val r = try { Shell.cmd("test -s ${q(resultFile)} && cat ${q(resultFile)} || exit 1").exec() } catch (_: Exception) { null }
            if (r?.isSuccess == true && r.out.isNotEmpty()) {
                val lines = r.out.map { it.trim() }.filter { it.isNotEmpty() }
                val result = lines.lastOrNull { it.startsWith("OK|") || it.startsWith("ERR|") } ?: lines.last()
                lines.filterNot { it == result }.takeLast(8).forEach { logger?.w("[TERMUX] $it") }
                try { Shell.cmd("rm -f ${q(launcher)} ${q(resultFile)} 2>/dev/null || true").exec() } catch (_: Exception) { }
                return result
            }
            delay(100)
        }
        logger?.w("[!] RUN_COMMAND timed out waiting for Termux result: $label")
        started.out.filter { it.isNotBlank() }.takeLast(4).forEach { logger?.w("[RUN_COMMAND] $it") }
        started.err.filter { it.isNotBlank() }.takeLast(4).forEach { logger?.w("[RUN_COMMAND] $it") }
        try { Shell.cmd("rm -f ${q(launcher)} ${q(resultFile)} 2>/dev/null || true").exec() } catch (_: Exception) { }
        return null
    }

    private suspend fun discoverNatGateway(info: ContainerInfo, logger: ContainerLogger?): String? {
        val pid = info.pid ?: return null
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            pid=$pid; gw=''
            if [ -x ${q(busybox)} ]; then
                gw=${'$'}(${q(busybox)} nsenter -t "${'$'}pid" -n ${q(busybox)} ip -4 route show default 2>/dev/null | sed -n 's/^default via \([0-9.][0-9.]*\).*/\1/p' | sed -n '1p')
            fi
            if [ -z "${'$'}gw" ]; then
                hex=${'$'}(while read ifc dst g rest; do [ "${'$'}dst" = 00000000 ] || continue; printf '%s\n' "${'$'}g"; break; done < "/proc/${'$'}pid/net/route" 2>/dev/null)
                case "${'$'}hex" in ????????)
                    b1=${'$'}(printf '%s' "${'$'}hex" | cut -c7-8); b2=${'$'}(printf '%s' "${'$'}hex" | cut -c5-6)
                    b3=${'$'}(printf '%s' "${'$'}hex" | cut -c3-4); b4=${'$'}(printf '%s' "${'$'}hex" | cut -c1-2)
                    gw=${'$'}(printf '%d.%d.%d.%d' "0x${'$'}b1" "0x${'$'}b2" "0x${'$'}b3" "0x${'$'}b4" 2>/dev/null || true) ;;
                esac
            fi
            printf '%s\n' "${'$'}gw"
        """.trimIndent()
        val discovered = try { Shell.cmd(command).exec().out.firstOrNull()?.trim().orEmpty() } catch (_: Exception) { "" }
        return when {
            validIpv4(discovered) && hostOwnsIpv4(discovered) -> discovered
            hostOwnsIpv4("172.28.0.1") -> {
                logger?.w("[!] Live NAT route was not readable; using verified DroidSpaces gateway 172.28.0.1")
                "172.28.0.1"
            }
            else -> null
        }
    }

    private fun hostOwnsIpv4(ip: String): Boolean {
        if (!validIpv4(ip)) return false
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            ip=${q(ip)}
            if [ -x /system/bin/ip ]; then out=${'$'}(/system/bin/ip -4 -o addr show 2>/dev/null)
            elif [ -x ${q(busybox)} ]; then out=${'$'}(${q(busybox)} ip -4 -o addr show 2>/dev/null)
            else out=${'$'}(ip -4 -o addr show 2>/dev/null || true); fi
            printf '%s\n' "${'$'}out" | grep -Eq "[[:space:]]inet[[:space:]]${'$'}ip/"
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun validIpv4(value: String): Boolean {
        val p = value.split('.')
        return p.size == 4 && p.all { it.toIntOrNull() in 0..255 }
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
                    case "${'$'}start:${'$'}end" in *[!0-9:]*|'':*) : ;; *)
                        if [ "${'$'}wanted" -ge "${'$'}start" ] 2>/dev/null && [ "${'$'}wanted" -le "${'$'}end" ] 2>/dev/null; then printf '%s\n' "${'$'}name"; exit 0; fi ;;
                    esac
                    IFS=,
                done
                IFS=${'$'}oldifs
            done
            exit 1
        """.trimIndent()
        return try {
            val r = Shell.cmd(command).exec()
            if (r.isSuccess) r.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } else null
        } catch (_: Exception) { null }
    }

    private fun verifyContainerClient(containerName: String, server: String): Boolean {
        val payload = """
            server=${q(server)}; cookie=/root/.config/pulse/saas-audio.cookie
            command -v pactl >/dev/null 2>&1 || exit 70
            [ -f "${'$'}cookie" ] || exit 71
            [ "${'$'}(wc -c < "${'$'}cookie" 2>/dev/null | tr -d ' ')" = 256 ] || exit 72
            info=${'$'}(PULSE_SERVER="${'$'}server" PULSE_COOKIE="${'$'}cookie" pactl info 2>/dev/null) || exit 73
            printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${'$'}server" || exit 74
            printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$'
        """.trimIndent()
        val command = "${Constants.DS_BINARY_PATH} --name=${q(containerName)} run /bin/sh -lc ${q(payload)}"
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private suspend fun verifyContainerClientDetailed(
        containerName: String,
        server: String,
        expectedSink: String,
        logger: ContainerLogger?
    ): Boolean {
        val payload = """
            server=${q(server)}; cookie=/root/.config/pulse/saas-audio.cookie; expected=${q(expectedSink)}
            info=${'$'}(PULSE_SERVER="${'$'}server" PULSE_COOKIE="${'$'}cookie" pactl info 2>&1) || { printf '%s\n' "${'$'}info"; exit 81; }
            printf '%s\n' "${'$'}info" | grep -E '^(Server String|Server Version|Default Sink|Default Source):' || true
            printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${'$'}server" || exit 82
            printf '%s\n' "${'$'}info" | grep -Fq "Default Sink: ${'$'}expected" || exit 83
        """.trimIndent()
        val command = "${Constants.DS_BINARY_PATH} --name=${q(containerName)} run /bin/sh -lc ${q(payload)}"
        return try {
            val r = Shell.cmd(command).exec()
            r.out.filter { it.isNotBlank() }.takeLast(8).forEach { logger?.i(it) }
            r.err.filter { it.isNotBlank() }.takeLast(8).forEach { logger?.w("[CONTAINER] $it") }
            r.isSuccess
        } catch (_: Exception) { false }
    }

    private suspend fun installContainerClient(
        containerName: String,
        server: String,
        octal: String,
        logger: ContainerLogger?
    ): Boolean {
        val payload = """
            set -u
            SERVER=${q(server)}; COOKIE_ESCAPED=${q(octal)}
            need=0; command -v pactl >/dev/null 2>&1 || need=1; command -v speaker-test >/dev/null 2>&1 || need=1
            if [ "${'$'}need" -eq 1 ]; then
                if command -v apt-get >/dev/null 2>&1; then
                    echo __APT__; DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1 || true
                    DEBIAN_FRONTEND=noninteractive apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils >/dev/null 2>&1 || true
                elif command -v apk >/dev/null 2>&1; then
                    echo __APK__; apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse >/dev/null 2>&1 || true
                fi
            fi
            command -v pactl >/dev/null 2>&1 || exit 60
            mkdir -p /root/.config/pulse /etc/profile.d || exit 61
            cookie=/root/.config/pulse/saas-audio.cookie
            printf '%b' "${'$'}COOKIE_ESCAPED" > "${'$'}cookie" || exit 62
            [ "${'$'}(wc -c < "${'$'}cookie" | tr -d ' ')" = 256 ] || exit 63
            chmod 600 "${'$'}cookie" 2>/dev/null || true
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
            info=${'$'}(PULSE_SERVER="${'$'}SERVER" PULSE_COOKIE="${'$'}cookie" pactl info 2>&1) || { printf '%s\n' "${'$'}info" >&2; exit 65; }
            printf '%s\n' "${'$'}info" | grep -E '^(Server String|Server Version|Default Sink|Default Source):' || true
            printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${'$'}SERVER" || exit 66
            printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$' || exit 67
            echo __READY__
        """.trimIndent()
        val command = "${Constants.DS_BINARY_PATH} --name=${q(containerName)} run /bin/sh -lc ${q(payload)}"
        return try {
            val r = Shell.cmd(command).exec()
            r.out.forEach { line ->
                when {
                    line.trim() == "__APT__" -> logger?.i("[*] Installing missing Debian/Ubuntu audio clients...")
                    line.trim() == "__APK__" -> logger?.i("[*] Installing missing Alpine audio clients...")
                    line.startsWith("Server String:") || line.startsWith("Server Version:") || line.startsWith("Default Sink:") || line.startsWith("Default Source:") -> logger?.i(line)
                }
            }
            r.err.filter { it.isNotBlank() }.take(8).forEach { logger?.w("[CONTAINER] $it") }
            r.isSuccess && r.out.any { it.trim() == "__READY__" }
        } catch (_: Exception) { false }
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
