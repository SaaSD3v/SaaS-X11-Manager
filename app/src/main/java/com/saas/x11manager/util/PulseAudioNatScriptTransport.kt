package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * NAT PulseAudio transport ported from the physically validated
 * SaaS-DroidSpaces-Audio-Auto v3.2.0 HOST+NAT shell implementation.
 *
 * This class deliberately stays small and deterministic:
 * - one already-prepared Manager PulseAudio core;
 * - live DroidSpaces NAT gateway discovery (172.28.0.1 verified fallback);
 * - exact-address authenticated TCP listener;
 * - automatic port selection from 4713 through 4777;
 * - exact 256-byte cookie copied into the running container;
 * - real container-side `pactl info` verification.
 *
 * It never starts/stops/restarts the container, X11, VNC, desktop, or the
 * Manager PulseAudio core. It does not scan Android /proc process fd tables.
 */
object PulseAudioNatScriptTransport {
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_SH = "$TERMUX_PREFIX/bin/sh"

    private const val STATE_DIR = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val CONTROL_SOCKET = "$STATE_DIR/control.sock"
    private const val COOKIE = "$STATE_DIR/transport.cookie"
    private const val CLIENT_CONFIG = "$STATE_DIR/pulse-home/.config/pulse/client.conf"

    private const val BASE_PORT = 4713
    private const val MAX_PORT_SHIFT = 64
    private const val VERIFIED_NAT_GATEWAY = "172.28.0.1"

    private const val MANAGED = "SaaS X11 Manager Audio Configuration"
    private const val SCRIPT_MANAGED = "SaaS DroidSpaces Audio HostNAT"
    private const val NETLAB_MANAGED = "SaaS DroidSpaces Audio NetLab"
    private const val LEGACY_MANAGED = "SaaS DroidSpaces Audio Auto"

    private data class CommandResult(
        val code: Int,
        val out: List<String>,
        val err: List<String>
    ) {
        val success: Boolean get() = code == 0
    }

    private data class Listener(
        val ip: String,
        val port: Int,
        val moduleId: Int?
    ) {
        val server: String get() = "tcp:$ip:$port"
    }

    suspend fun finalizeAfterContainerReady(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val info = ContainerManager.getContainerInfo(containerName)
            ?: return@withContext fail(logger, "Container $containerName was not found")

        if (!info.isRunning) {
            return@withContext fail(
                logger,
                "Container $containerName is not running; NAT audio was not changed"
            )
        }
        if (info.netMode.trim().lowercase() != "nat") {
            return@withContext fail(logger, "NAT transport received net_mode=${info.netMode}")
        }

        val uid = termuxUid()
            ?: return@withContext fail(logger, "Termux UID could not be resolved")

        if (!controlCoreHasAndroidSink(uid)) {
            return@withContext fail(
                logger,
                "Manager PulseAudio core is not available on its private control socket"
            )
        }

        val endpoint = resolveNatEndpoint(info)
            ?: return@withContext fail(logger, "Could not resolve the Android-side NAT gateway")

        if (!hostOwnsIpv4(endpoint)) {
            return@withContext fail(logger, "Android host does not own NAT endpoint $endpoint")
        }

        logger?.i("[CTX] NAT audio transport: validated v3.2 script parity")
        logger?.i("[CTX] NAT host endpoint: $endpoint (automatic port selection)")

        val listener = selectAndEnsureListener(uid, endpoint, logger)
            ?: return@withContext fail(
                logger,
                "Could not create a usable authenticated listener in $BASE_PORT-${BASE_PORT + MAX_PORT_SHIFT}"
            )

        val cookieEscaped = serializeCookie(uid)
            ?: return@withContext fail(logger, "Manager PulseAudio cookie could not be serialized")

        val payload = buildContainerPayload(listener.server, cookieEscaped)
        val command =
            "${Constants.DS_BINARY_PATH} --name=${q(containerName)} run /bin/sh -lc ${q(payload)}"

        logger?.i("[*] Configuring and verifying the PulseAudio client inside $containerName...")

        val result = try {
            Shell.cmd(command).exec()
        } catch (e: Exception) {
            logger?.w("[PA-NAT-CONTAINER] ${e.message ?: e.javaClass.simpleName}")
            return@withContext fail(logger, "DroidSpaces container audio command failed")
        }

        result.out.forEach { line ->
            when {
                line.trim() == "__SAAS_AUDIO_APT__" ->
                    logger?.i("[*] Installing Debian/Ubuntu audio client packages...")

                line.trim() == "__SAAS_AUDIO_APK__" ->
                    logger?.i("[*] Installing Alpine audio client packages...")

                line.startsWith("Server String:") ||
                    line.startsWith("Server Version:") ||
                    line.startsWith("Default Sink:") ||
                    line.startsWith("Default Source:") ->
                    logger?.i("[PA-NAT-CONTAINER] $line")
            }
        }
        result.err.filter { it.isNotBlank() }.forEach {
            logger?.w("[PA-NAT-CONTAINER][stderr] $it")
        }

        val ready = result.isSuccess &&
            result.out.any { it.trim() == "__SAAS_AUDIO_TRANSPORT_READY__" }
        if (!ready) {
            logger?.w("[PA-NAT-CONTAINER] exit=${result.code}")
            return@withContext fail(logger, "Container could not verify ${listener.server}")
        }

        val sink = result.out.asSequence()
            .map { it.trim() }
            .firstOrNull {
                it == "Default Sink: AAudio_sink" || it == "Default Sink: OpenSL_ES_sink"
            }
            ?.removePrefix("Default Sink: ")
            ?: "Android sink"

        FixSettings.setPulseAudioApplied(X11Application.instance, containerName, true)
        logger?.i("[+] NAT audio transport verified from inside the container")
        logger?.i("[+] Audio ready ($sink, ${listener.server})")
        true
    }

    private suspend fun selectAndEnsureListener(
        uid: Int,
        ip: String,
        logger: ContainerLogger?
    ): Listener? {
        for (port in BASE_PORT..(BASE_PORT + MAX_PORT_SHIFT)) {
            val forwardOwner = configuredPortForwardOwner(port)
            if (forwardOwner != null) {
                if (port == BASE_PORT) {
                    logger?.w(
                        "[!] Port $port is reserved by DroidSpaces TCP port-forward in " +
                            "$forwardOwner; selecting another automatically"
                    )
                }
                continue
            }

            val existing = endpointInfo(uid, ip, port)
            if (hasAndroidSink(existing)) {
                existing.out
                    .filter { it.startsWith("Server String:") || it.startsWith("Default Sink:") }
                    .forEach { logger?.i("[PA-NAT-PROBE] $it") }
                logger?.i("[+] Reusing authenticated PulseAudio listener on $ip:$port")
                return Listener(ip, port, moduleId = null)
            }

            val load = controlPactl(
                uid,
                "load-module module-native-protocol-tcp " +
                    "${q("listen=$ip")} ${q("port=$port")} ${q("auth-cookie=$COOKIE")}"
            )
            val moduleId = load.out.asSequence()
                .map { it.trim() }
                .firstOrNull { it.matches(Regex("""\d+""")) }
                ?.toIntOrNull()

            if (!load.success || moduleId == null) {
                if (port == BASE_PORT) {
                    logger?.w(
                        "[!] Port $port could not be bound or verified on $ip; " +
                            "selecting another automatically"
                    )
                    load.err.filter { it.isNotBlank() }.forEach {
                        logger?.w("[PA-NAT-LOAD] $it")
                    }
                }
                continue
            }

            var verified: CommandResult? = null
            repeat(20) {
                val probe = endpointInfo(uid, ip, port)
                if (hasAndroidSink(probe)) {
                    verified = probe
                    return@repeat
                }
                delay(100)
            }

            val probe = verified
            if (probe != null) {
                probe.out
                    .filter { it.startsWith("Server String:") || it.startsWith("Default Sink:") }
                    .forEach { logger?.i("[PA-NAT-PROBE] $it") }
                if (port != BASE_PORT) logger?.i("[+] Selected audio port: $port")
                logger?.i("[+] Authenticated PulseAudio listener ready on $ip:$port")
                return Listener(ip, port, moduleId)
            }

            // The id belongs to the module created immediately above, so cleanup
            // does not need module enumeration or process ownership discovery.
            controlPactl(uid, "unload-module $moduleId")
        }
        return null
    }

    private fun controlCoreHasAndroidSink(uid: Int): Boolean {
        val info = controlPactl(uid, "info")
        if (!info.success) return false
        val sinks = controlPactl(uid, "list short sinks")
        return sinks.success && sinks.out.any { line ->
            line.contains("\tAAudio_sink\t") ||
                line.contains(" AAudio_sink ") ||
                line.contains("\tOpenSL_ES_sink\t") ||
                line.contains(" OpenSL_ES_sink ")
        }
    }

    private fun controlPactl(uid: Int, arguments: String): CommandResult =
        execAsTermux(
            uid,
            "PULSE_SERVER=${q("unix:$CONTROL_SOCKET")} " +
                "PULSE_COOKIE=${q(COOKIE)} " +
                "PULSE_CLIENTCONFIG=${q(CLIENT_CONFIG)} " +
                "pactl $arguments"
        )

    private fun endpointInfo(uid: Int, ip: String, port: Int): CommandResult =
        execAsTermux(
            uid,
            "PULSE_SERVER=${q("tcp:$ip:$port")} " +
                "PULSE_COOKIE=${q(COOKIE)} " +
                "PULSE_CLIENTCONFIG=${q(CLIENT_CONFIG)} pactl info"
        )

    private fun hasAndroidSink(result: CommandResult): Boolean =
        result.success && result.out.any {
            when (it.trim()) {
                "Default Sink: AAudio_sink", "Default Sink: OpenSL_ES_sink" -> true
                else -> false
            }
        }

    private fun serializeCookie(uid: Int): String? {
        val result = execAsTermux(
            uid,
            "test \"\$(wc -c < ${q(COOKIE)} 2>/dev/null | tr -d ' ')\" = 256 || exit 2; " +
                "od -An -v -tu1 ${q(COOKIE)} 2>/dev/null | " +
                "awk '{ for (i=1; i<=NF; i++) printf \"\\\\0%03o\", ${'$'}i }'"
        )
        if (!result.success) return null
        return result.out.joinToString("").trim().takeIf { it.isNotEmpty() }
    }

    private fun resolveNatEndpoint(info: ContainerInfo): String? {
        val pid = info.pid
        if (pid != null) {
            val result = try {
                Shell.cmd(
                    "while read ifc dst gw rest; do " +
                        "[ \"\$dst\" = 00000000 ] || continue; " +
                        "printf '%s\\n' \"\$gw\"; break; " +
                        "done < /proc/$pid/net/route 2>/dev/null"
                ).exec()
            } catch (_: Exception) {
                null
            }
            val hex = result?.out?.firstOrNull()?.trim().orEmpty()
            val discovered = hexGatewayToIpv4(hex)
            if (discovered != null && hostOwnsIpv4(discovered)) return discovered
        }

        return VERIFIED_NAT_GATEWAY.takeIf(::hostOwnsIpv4)
    }

    private fun hexGatewayToIpv4(hex: String): String? {
        if (!hex.matches(Regex("""[0-9A-Fa-f]{8}"""))) return null
        return try {
            val a = hex.substring(6, 8).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            val c = hex.substring(2, 4).toInt(16)
            val d = hex.substring(0, 2).toInt(16)
            "$a.$b.$c.$d"
        } catch (_: Exception) {
            null
        }
    }

    private fun hostOwnsIpv4(ip: String): Boolean {
        if (!validIpv4(ip)) return false
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            if [ -x /system/bin/ip ]; then
                /system/bin/ip -4 -o addr show 2>/dev/null
            elif [ -x ${q(busybox)} ]; then
                ${q(busybox)} ip -4 -o addr show 2>/dev/null
            else
                ip -4 -o addr show 2>/dev/null || true
            fi | grep -Fq ${q(" $ip/")}
        """.trimIndent()
        return try {
            Shell.cmd(command).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun validIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull() in 0..255 }
    }

    private fun configuredPortForwardOwner(port: Int): String? {
        val command = """
            wanted=$port
            for cfg in ${q(Constants.CONTAINERS_DIR)}/*/${Constants.CONFIG_FILE}; do
                [ -f "${'$'}cfg" ] || continue
                net=${'$'}(sed -n 's/^net_mode=//p' "${'$'}cfg" | tail -n 1)
                [ "${'$'}net" = nat ] || continue
                name=${'$'}(sed -n 's/^name=//p' "${'$'}cfg" | sed -n '1p')
                [ -n "${'$'}name" ] || {
                    dir=${'$'}{cfg%/${Constants.CONFIG_FILE}}
                    name=${'$'}{dir##*/}
                }
                value=${'$'}(sed -n 's/^port_forwards=//p' "${'$'}cfg" | sed -n '1p')
                oldifs=${'$'}IFS
                IFS=,
                for token in ${'$'}value; do
                    IFS=${'$'}oldifs
                    token=${'$'}(printf '%s' "${'$'}token" | tr -d '[:space:]')
                    [ -n "${'$'}token" ] || { IFS=,; continue; }
                    case "${'$'}token" in
                        */*) proto=${'$'}{token##*/}; body=${'$'}{token%/*} ;;
                        *) proto=tcp; body=${'$'}token ;;
                    esac
                    [ "${'$'}proto" = tcp ] || { IFS=,; continue; }
                    host=${'$'}{body%%:*}
                    case "${'$'}host" in
                        *-*) first=${'$'}{host%-*}; last=${'$'}{host#*-} ;;
                        *) first=${'$'}host; last=${'$'}host ;;
                    esac
                    case "${'$'}first:${'$'}last" in
                        *[!0-9:]*|'':*) : ;;
                        *)
                            if [ "${'$'}wanted" -ge "${'$'}first" ] 2>/dev/null && \
                               [ "${'$'}wanted" -le "${'$'}last" ] 2>/dev/null; then
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

    private fun termuxUid(): Int? {
        val command =
            "test -x ${q(TERMUX_SH)} || exit 1; " +
                "uid=\$(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || " +
                "toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null); " +
                "case \"\$uid\" in ''|*[!0-9]*) exit 2 ;; esac; " +
                "printf '%s\\n' \"\$uid\""

        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun execAsTermux(uid: Int, command: String): CommandResult {
        val wrapped = buildString {
            append("export HOME=").append(q(TERMUX_HOME)).append("; ")
            append("export PREFIX=").append(q(TERMUX_PREFIX)).append("; ")
            append("export TMPDIR=").append(q("$TERMUX_PREFIX/tmp")).append("; ")
            append("export PATH=").append(q("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")).append("; ")
            append(command)
        }

        return try {
            val result = Shell.cmd("su $uid -c ${q(wrapped)}").exec()
            CommandResult(result.code, result.out.toList(), result.err.toList())
        } catch (e: Exception) {
            CommandResult(255, emptyList(), listOf(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun buildContainerPayload(server: String, cookieEscaped: String): String = """
        set -u
        SERVER=${q(server)}
        COOKIE_ESCAPED=${q(cookieEscaped)}
        MANAGED=${q(MANAGED)}
        SCRIPT=${q(SCRIPT_MANAGED)}
        NETLAB=${q(NETLAB_MANAGED)}
        LEGACY=${q(LEGACY_MANAGED)}

        say() { printf '%s\n' "${'$'}*"; }
        warn() { printf '[!] %s\n' "${'$'}*" >&2; }
        die() { printf '[-] %s\n' "${'$'}*" >&2; exit 1; }

        [ "${'$'}(id -u)" -eq 0 ] || die 'container setup is not running as root'

        need=0
        command -v pactl >/dev/null 2>&1 || need=1
        command -v speaker-test >/dev/null 2>&1 || need=1
        if [ "${'$'}need" -eq 1 ]; then
            if command -v apt-get >/dev/null 2>&1; then
                printf '%s\n' __SAAS_AUDIO_APT__
                DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1 || true
                DEBIAN_FRONTEND=noninteractive apt-get install -y \
                    pulseaudio-utils libasound2-plugins alsa-utils >/dev/null 2>&1 || true
            elif command -v apk >/dev/null 2>&1; then
                printf '%s\n' __SAAS_AUDIO_APK__
                apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse \
                    >/dev/null 2>&1 || true
            fi
        fi
        command -v pactl >/dev/null 2>&1 || die 'pactl is unavailable; cannot verify the transport'

        backup_unmanaged() {
            target="${'$'}1"; backup="${'$'}2"; old1="${'$'}{3:-}"; old2="${'$'}{4:-}"
            [ -f "${'$'}target" ] || return 0
            if grep -Fq "${'$'}MANAGED" "${'$'}target" 2>/dev/null || \
               grep -Fq "${'$'}SCRIPT" "${'$'}target" 2>/dev/null || \
               grep -Fq "${'$'}NETLAB" "${'$'}target" 2>/dev/null || \
               grep -Fq "${'$'}LEGACY" "${'$'}target" 2>/dev/null; then
                if [ ! -e "${'$'}backup" ]; then
                    [ -n "${'$'}old1" ] && [ -f "${'$'}old1" ] && \
                        cp -p "${'$'}old1" "${'$'}backup" || true
                    [ -e "${'$'}backup" ] || {
                        [ -n "${'$'}old2" ] && [ -f "${'$'}old2" ] && \
                            cp -p "${'$'}old2" "${'$'}backup" || true
                    }
                fi
                return 0
            fi
            [ -e "${'$'}backup" ] || cp -p "${'$'}target" "${'$'}backup" || \
                die "could not back up ${'$'}target"
        }

        mkdir -p /root/.config/pulse /etc/profile.d || \
            die 'cannot create PulseAudio config directories'

        cookie=/root/.config/pulse/saas-audio.cookie
        printf '%b' "${'$'}COOKIE_ESCAPED" > "${'$'}cookie" || \
            die 'cannot write PulseAudio cookie'
        [ "${'$'}(wc -c < "${'$'}cookie" | tr -d ' ')" = 256 ] || \
            die 'PulseAudio cookie has invalid length'
        chmod 600 "${'$'}cookie" 2>/dev/null || true

        client=/root/.config/pulse/client.conf
        backup_unmanaged "${'$'}client" "${'$'}client.saas-hostnat.bak" \
            "${'$'}client.saas-netlab.bak" "${'$'}client.saas-audio.bak"
        cat > "${'$'}client" <<EOF_CLIENT
        # $MANAGED
        default-server = $server
        cookie-file = /root/.config/pulse/saas-audio.cookie
        autospawn = no
        enable-shm = no
        EOF_CLIENT
        chmod 600 "${'$'}client" 2>/dev/null || true

        profile=/etc/profile.d/saas-droidspaces-audio.sh
        cat > "${'$'}profile" <<EOF_PROFILE
        # $MANAGED
        export PULSE_SERVER=$server
        export PULSE_COOKIE=/root/.config/pulse/saas-audio.cookie
        EOF_PROFILE
        chmod 644 "${'$'}profile" 2>/dev/null || true

        asound=/etc/asound.conf
        if command -v aplay >/dev/null 2>&1 && \
           PULSE_SERVER="${'$'}SERVER" PULSE_COOKIE="${'$'}cookie" \
           aplay -L 2>/dev/null | grep -q '^pulse'; then
            backup_unmanaged "${'$'}asound" "${'$'}asound.saas-hostnat.bak" \
                "${'$'}asound.saas-netlab.bak" "${'$'}asound.saas-audio.bak"
            cat > "${'$'}asound" <<EOF_ASOUND
        # $MANAGED
        pcm.!default { type pulse }
        ctl.!default { type pulse }
        EOF_ASOUND
        else
            warn 'ALSA pulse plugin not detected; PulseAudio clients are still supported'
        fi

        # Remove integration left by retired implementations. The working v3.2
        # architecture intentionally keeps audio independent from X11 lifecycle.
        for old in \
            /etc/systemd/system/x11-session.service.d/90-saas-audio.conf \
            /etc/systemd/system/x11-session.service.d/audio.conf; do
            if [ -f "${'$'}old" ] && {
                grep -Fq "${'$'}MANAGED" "${'$'}old" 2>/dev/null || \
                grep -Fq "${'$'}SCRIPT" "${'$'}old" 2>/dev/null || \
                grep -Fq "${'$'}NETLAB" "${'$'}old" 2>/dev/null || \
                grep -Fq "${'$'}LEGACY" "${'$'}old" 2>/dev/null
            }; then
                rm -f "${'$'}old"
            fi
        done

        if [ -f /etc/conf.d/x11-session ]; then
            sed '/^# BEGIN SaaS DroidSpaces Audio Auto$/,/^# END SaaS DroidSpaces Audio Auto$/d; /^# BEGIN SaaS DroidSpaces Audio NetLab$/,/^# END SaaS DroidSpaces Audio NetLab$/d' \
                /etc/conf.d/x11-session > /etc/conf.d/x11-session.saas-audio.tmp && \
                mv /etc/conf.d/x11-session.saas-audio.tmp /etc/conf.d/x11-session
        fi

        info="${'$'}(PULSE_SERVER="${'$'}SERVER" PULSE_COOKIE="${'$'}cookie" pactl info 2>&1)" || {
            printf '%s\n' "${'$'}info" >&2
            die "pactl cannot reach ${'$'}SERVER"
        }
        printf '%s\n' "${'$'}info" | \
            grep -E '^(Server String|Server Version|Default Sink|Default Source):' || true
        printf '%s\n' "${'$'}info" | \
            grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$' || \
            die 'remote server has no verified Android sink'

        printf '%s\n' __SAAS_AUDIO_TRANSPORT_READY__
    """.trimIndent()

    private suspend fun fail(logger: ContainerLogger?, message: String): Boolean {
        logger?.w("[!] $message")
        logger?.w("[!] Graphical startup will continue")
        return false
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
