package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * NAT transport intentionally kept in parity with the physically validated
 * SaaS-DroidSpaces-Audio-Auto v3.2.0 HOST+NAT shell implementation.
 *
 * Contract:
 * - the Manager-owned PulseAudio core is prepared before graphical startup;
 * - this class never starts/stops/restarts the container, X11, VNC, or desktop;
 * - the Android NAT endpoint is resolved from the live container route, with
 *   verified 172.28.0.1 fallback;
 * - listeners bind only to the exact Android endpoint and always use the
 *   private 256-byte Manager cookie;
 * - ports are tried from 4713 through 4777, matching the validated script;
 * - the real container must verify the remote Android sink with pactl info.
 */
object PulseAudioNatScriptTransport {
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_SH = "$TERMUX_PREFIX/bin/sh"

    private const val STATE = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val COOKIE = "$STATE/transport.cookie"
    private const val CONTROL = "$STATE/control.sock"
    private const val CLIENT_CONFIG = "$STATE/pulse-home/.config/pulse/client.conf"
    private const val LISTENERS = "$STATE/listeners"

    private const val BASE_PORT = 4713
    private const val MAX_PORT_SHIFT = 64
    private const val VERIFIED_FALLBACK_GATEWAY = "172.28.0.1"

    private const val MANAGED = "SaaS X11 Manager Audio Configuration"
    private const val SCRIPT_MANAGED = "SaaS DroidSpaces Audio HostNAT"
    private const val NETLAB_MANAGED = "SaaS DroidSpaces Audio NetLab"
    private const val SCRIPT_LEGACY_MANAGED = "SaaS DroidSpaces Audio Auto"

    private data class CommandResult(
        val exitCode: Int,
        val stdout: List<String>,
        val stderr: List<String>
    )

    private data class Listener(
        val ip: String,
        val port: Int,
        val moduleId: Int,
        val reused: Boolean
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
            return@withContext fail(logger, "Container $containerName is not running; NAT audio was not changed")
        }
        if (info.netMode.trim().lowercase() != "nat") {
            return@withContext fail(logger, "NAT transport received net_mode=${info.netMode}")
        }

        val uid = termuxUid()
            ?: return@withContext fail(logger, "Termux UID could not be resolved")
        val endpoint = resolveNatEndpoint(info, logger)
            ?: return@withContext fail(logger, "Could not resolve the Android-side NAT gateway")

        logger?.i("[CTX] NAT audio transport: validated v3.2 script parity")
        logger?.i("[CTX] NAT host endpoint: $endpoint (automatic port selection)")

        val listener = selectAndEnsureListener(uid, endpoint, logger)
            ?: return@withContext fail(
                logger,
                "Could not create a usable authenticated listener on $endpoint:$BASE_PORT-${BASE_PORT + MAX_PORT_SHIFT}"
            )

        val cookieEscaped = cookieEscaped(uid)
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
        result.err.filter { it.isNotBlank() }
            .forEach { logger?.w("[PA-NAT-CONTAINER][stderr] $it") }

        val ready = result.isSuccess &&
            result.out.any { it.trim() == "__SAAS_AUDIO_TRANSPORT_READY__" }
        if (!ready) {
            logger?.w("[PA-NAT-CONTAINER] exit=${result.code}")
            return@withContext fail(
                logger,
                "Container could not verify ${listener.server}"
            )
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
        if (!hostOwnsIpv4(ip)) {
            logger?.w("[!] Android host does not own NAT endpoint $ip")
            return null
        }

        for (port in BASE_PORT..(BASE_PORT + MAX_PORT_SHIFT)) {
            if (configuredPortForwardOwner(port) != null) {
                if (port == BASE_PORT) {
                    logger?.w("[!] Port $port is reserved by a DroidSpaces TCP port-forward; selecting another automatically")
                }
                continue
            }

            endpointInfo(uid, ip, port).takeIf(::hasAndroidSink)?.let { probe ->
                probe.stdout
                    .filter { it.startsWith("Server String:") || it.startsWith("Default Sink:") }
                    .forEach { logger?.i("[PA-NAT-PROBE] $it") }
                logger?.i("[+] Reusing authenticated PulseAudio listener on $ip:$port")
                return Listener(ip, port, moduleId = -1, reused = true)
            }

            unloadListenerState(uid, ip, port)

            val load = controlPactl(
                uid,
                "load-module module-native-protocol-tcp " +
                    "${q("listen=$ip")} ${q("port=$port")} ${q("auth-cookie=$COOKIE")}"
            )
            val moduleId = load.stdout.asSequence()
                .map { it.trim() }
                .firstOrNull { it.matches(Regex("""\d+""")) }
                ?.toIntOrNull()

            if (load.exitCode != 0 || moduleId == null) {
                if (port == BASE_PORT) {
                    logger?.w("[!] Port $port could not be bound or verified on $ip; selecting another automatically")
                    if (load.stderr.isNotEmpty()) {
                        logger?.w("[PA-NAT-LOAD] ${load.stderr.joinToString(" ").take(500)}")
                    }
                }
                continue
            }

            persistListenerState(uid, ip, port, moduleId)
            repeat(20) {
                val probe = endpointInfo(uid, ip, port)
                if (hasAndroidSink(probe)) {
                    probe.stdout
                        .filter { it.startsWith("Server String:") || it.startsWith("Default Sink:") }
                        .forEach { logger?.i("[PA-NAT-PROBE] $it") }
                    if (port != BASE_PORT) logger?.i("[+] Selected audio port: $port")
                    logger?.i("[+] Authenticated PulseAudio listener ready on $ip:$port")
                    return Listener(ip, port, moduleId, reused = false)
                }
                delay(100)
            }

            // This module id came from this exact load attempt, so direct cleanup
            // is deterministic and does not require any /proc ownership scan.
            controlPactl(uid, "unload-module $moduleId")
            removeListenerState(uid, ip, port)
        }

        return null
    }

    private fun hasAndroidSink(result: CommandResult): Boolean =
        result.exitCode == 0 && result.stdout.any {
            val line = it.trim()
            line == "Default Sink: AAudio_sink" || line == "Default Sink: OpenSL_ES_sink"
        }

    private fun controlPactl(uid: Int, arguments: String): CommandResult =
        execAsTermux(
            uid,
            "PULSE_SERVER=${q("unix:$CONTROL")} " +
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

    private fun unloadListenerState(uid: Int, ip: String, port: Int) {
        val state = listenerStateFile(ip, port)
        val command = """
            sf=${q(state)}
            [ -f "${'$'}sf" ] || exit 0
            id=${'$'}(sed -n 's/^id=//p' "${'$'}sf" | sed -n '1p')
            owner=${'$'}(sed -n 's/^owner=//p' "${'$'}sf" | sed -n '1p')
            sip=${'$'}(sed -n 's/^ip=//p' "${'$'}sf" | sed -n '1p')
            sport=${'$'}(sed -n 's/^port=//p' "${'$'}sf" | sed -n '1p')
            case "${'$'}id" in ''|*[!0-9]*) rm -f "${'$'}sf"; exit 0 ;; esac
            [ "${'$'}owner" = ${q(MANAGED)} ] || { rm -f "${'$'}sf"; exit 0; }
            [ "${'$'}sip" = ${q(ip)} ] && [ "${'$'}sport" = ${q(port.toString())} ] || { rm -f "${'$'}sf"; exit 0; }
            modules=${'$'}(PULSE_SERVER=${q("unix:$CONTROL")} PULSE_COOKIE=${q(COOKIE)} PULSE_CLIENTCONFIG=${q(CLIENT_CONFIG)} pactl list short modules 2>/dev/null || true)
            while read -r mid mname margs; do
                [ "${'$'}mid" = "${'$'}id" ] || continue
                [ "${'$'}mname" = module-native-protocol-tcp ] || continue
                case " ${'$'}margs " in *" listen=$ip "*) : ;; *) continue ;; esac
                case " ${'$'}margs " in *" port=$port "*)
                    PULSE_SERVER=${q("unix:$CONTROL")} PULSE_COOKIE=${q(COOKIE)} PULSE_CLIENTCONFIG=${q(CLIENT_CONFIG)} pactl unload-module "${'$'}id" >/dev/null 2>&1 || true
                    ;;
                esac
                break
            done <<EOF_MODULES
            ${'$'}modules
            EOF_MODULES
            rm -f "${'$'}sf" 2>/dev/null || true
        """.trimIndent()
        execAsTermux(uid, command)
    }

    private fun persistListenerState(uid: Int, ip: String, port: Int, moduleId: Int) {
        val state = listenerStateFile(ip, port)
        execAsTermux(
            uid,
            "mkdir -p ${q(LISTENERS)} && " +
                "printf 'id=%s\\nip=%s\\nport=%s\\nowner=%s\\n' " +
                "${q(moduleId.toString())} ${q(ip)} ${q(port.toString())} ${q(MANAGED)} " +
                "> ${q(state)} && chmod 600 ${q(state)}"
        )
    }

    private fun removeListenerState(uid: Int, ip: String, port: Int) {
        execAsTermux(uid, "rm -f ${q(listenerStateFile(ip, port))} 2>/dev/null || true")
    }

    private fun listenerStateFile(ip: String, port: Int): String {
        val key = "${ip}_$port".replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$LISTENERS/$key.state"
    }

    private fun cookieEscaped(uid: Int): String? {
        val result = execAsTermux(
            uid,
            "od -An -v -tu1 ${q(COOKIE)} 2>/dev/null | " +
                "awk '{ for (i=1; i<=NF; i++) printf \"\\\\0%03o\", ${'$'}i }'"
        )
        if (result.exitCode != 0) return null
        return result.stdout.joinToString("").trim().takeIf { it.isNotEmpty() }
    }

    private fun resolveNatEndpoint(info: ContainerInfo, logger: ContainerLogger?): String? {
        val pid = info.pid
        if (pid != null) {
            val hexResult = try {
                Shell.cmd(
                    "while read ifc dst gw rest; do " +
                        "[ \"\$dst\" = 00000000 ] || continue; " +
                        "printf '%s\\n' \"\$gw\"; break; " +
                        "done < /proc/$pid/net/route 2>/dev/null"
                ).exec()
            } catch (_: Exception) {
                null
            }
            val hex = hexResult?.out?.firstOrNull()?.trim().orEmpty()
            hexGatewayToIpv4(hex)?.takeIf(::hostOwnsIpv4)?.let { return it }
        }

        if (hostOwnsIpv4(VERIFIED_FALLBACK_GATEWAY)) {
            logger?.w("[!] Live NAT default route was not readable; using verified DroidSpaces host gateway $VERIFIED_FALLBACK_GATEWAY")
            return VERIFIED_FALLBACK_GATEWAY
        }
        return null
    }

    private fun hexGatewayToIpv4(hex: String): String? {
        if (!hex.matches(Regex("""[0-9A-Fa-f]{8}"""))) return null
        return try {
            val b1 = hex.substring(6, 8).toInt(16)
            val b2 = hex.substring(4, 6).toInt(16)
            val b3 = hex.substring(2, 4).toInt(16)
            val b4 = hex.substring(0, 2).toInt(16)
            "$b1.$b2.$b3.$b4"
        } catch (_: Exception) {
            null
        }
    }

    private fun hostOwnsIpv4(ip: String): Boolean {
        if (!isValidIpv4(ip)) return false
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
            printf '%s\n' "${'$'}out" | while read -r n ifn fam cidr rest; do
                case "${'$'}cidr" in "${'$'}ip/"*) exit 0 ;; esac
            done
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
    }

    private fun configuredPortForwardOwner(port: Int): String? {
        val command = """
            wanted=$port
            for cfg in ${q(Constants.CONTAINERS_DIR)}/*/${Constants.CONFIG_FILE}; do
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
                        if [ "${'$'}wanted" -ge "${'$'}start" ] 2>/dev/null && [ "${'$'}wanted" -le "${'$'}end" ] 2>/dev/null; then
                            printf '%s\n' "${'$'}name"; exit 0
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
            if (result.isSuccess) result.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun termuxUid(): Int? {
        val command =
            "test -x ${q(TERMUX_SH)} || exit 1; " +
                "uid=\$(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || " +
                "toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null); " +
                "case \"\$uid\" in ''|*[!0-9]*) exit 2 ;; esac; printf '%s\\n' \"\$uid\""
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
        LEGACY=${q(SCRIPT_LEGACY_MANAGED)}

        say() { printf '%s\n' "${'$'}*"; }
        die() { printf '[-] %s\n' "${'$'}*" >&2; exit 1; }
        [ "${'$'}(id -u)" -eq 0 ] || die 'container setup is not running as root'

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
        command -v pactl >/dev/null 2>&1 || die 'pactl is unavailable; cannot verify the transport'

        backup_unmanaged() {
            t="${'$'}1"; b="${'$'}2"; old1="${'$'}{3:-}"; old2="${'$'}{4:-}"
            [ -f "${'$'}t" ] || return 0
            if grep -Fq "${'$'}MANAGED" "${'$'}t" 2>/dev/null || \
               grep -Fq "${'$'}SCRIPT" "${'$'}t" 2>/dev/null || \
               grep -Fq "${'$'}NETLAB" "${'$'}t" 2>/dev/null || \
               grep -Fq "${'$'}LEGACY" "${'$'}t" 2>/dev/null; then
                if [ ! -e "${'$'}b" ]; then
                    [ -n "${'$'}old1" ] && [ -f "${'$'}old1" ] && cp -p "${'$'}old1" "${'$'}b" || true
                    [ -e "${'$'}b" ] || { [ -n "${'$'}old2" ] && [ -f "${'$'}old2" ] && cp -p "${'$'}old2" "${'$'}b" || true; }
                fi
                return 0
            fi
            [ -e "${'$'}b" ] || cp -p "${'$'}t" "${'$'}b" || die "could not back up ${'$'}t"
        }

        mkdir -p /root/.config/pulse /etc/profile.d || die 'cannot create PulseAudio config directories'
        cookie=/root/.config/pulse/saas-audio.cookie
        printf '%b' "${'$'}COOKIE_ESCAPED" > "${'$'}cookie" || die 'cannot write PulseAudio cookie'
        [ "${'$'}(wc -c < "${'$'}cookie" | tr -d ' ')" = 256 ] || die 'PulseAudio cookie has invalid length'
        chmod 600 "${'$'}cookie" 2>/dev/null || true

        client=/root/.config/pulse/client.conf
        backup_unmanaged "${'$'}client" "${'$'}client.saas-hostnat.bak" "${'$'}client.saas-netlab.bak" "${'$'}client.saas-audio.bak"
        cat > "${'$'}client" <<EOF_PULSE_CLIENT
        # $MANAGED
        default-server = $server
        cookie-file = /root/.config/pulse/saas-audio.cookie
        autospawn = no
        enable-shm = no
        EOF_PULSE_CLIENT

        profile=/etc/profile.d/saas-droidspaces-audio.sh
        cat > "${'$'}profile" <<EOF_PROFILE
        # $MANAGED
        export PULSE_SERVER=$server
        export PULSE_COOKIE=/root/.config/pulse/saas-audio.cookie
        EOF_PROFILE
        chmod 644 "${'$'}profile" 2>/dev/null || true

        asound=/etc/asound.conf
        if command -v aplay >/dev/null 2>&1 && PULSE_SERVER="${'$'}SERVER" PULSE_COOKIE="${'$'}cookie" aplay -L 2>/dev/null | grep -q '^pulse'; then
            backup_unmanaged "${'$'}asound" "${'$'}asound.saas-hostnat.bak" "${'$'}asound.saas-netlab.bak" "${'$'}asound.saas-audio.bak"
            cat > "${'$'}asound" <<EOF_ASOUND
        # $MANAGED
        pcm.!default { type pulse }
        ctl.!default { type pulse }
        EOF_ASOUND
        fi

        for old in /etc/systemd/system/x11-session.service.d/90-saas-audio.conf /etc/systemd/system/x11-session.service.d/audio.conf; do
            if [ -f "${'$'}old" ] && { grep -Fq "${'$'}MANAGED" "${'$'}old" 2>/dev/null || grep -Fq "${'$'}SCRIPT" "${'$'}old" 2>/dev/null || grep -Fq "${'$'}NETLAB" "${'$'}old" 2>/dev/null || grep -Fq "${'$'}LEGACY" "${'$'}old" 2>/dev/null; }; then
                rm -f "${'$'}old"
            fi
        done
        if [ -f /etc/conf.d/x11-session ]; then
            sed '/^# BEGIN SaaS DroidSpaces Audio Auto$/,/^# END SaaS DroidSpaces Audio Auto$/d; /^# BEGIN SaaS DroidSpaces Audio NetLab$/,/^# END SaaS DroidSpaces Audio NetLab$/d' /etc/conf.d/x11-session > /etc/conf.d/x11-session.saas-audio.tmp && mv /etc/conf.d/x11-session.saas-audio.tmp /etc/conf.d/x11-session
        fi

        info="${'$'}(PULSE_SERVER="${'$'}SERVER" PULSE_COOKIE="${'$'}cookie" pactl info 2>&1)" || { printf '%s\n' "${'$'}info" >&2; die "pactl cannot reach ${'$'}SERVER"; }
        printf '%s\n' "${'$'}info" | grep -E '^(Server String|Server Version|Default Sink|Default Source):' || true
        printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$' || die 'remote server has no verified Android sink'
        printf '%s\n' __SAAS_AUDIO_TRANSPORT_READY__
    """.trimIndent()

    private suspend fun fail(logger: ContainerLogger?, message: String): Boolean {
        logger?.w("[!] $message")
        logger?.w("[!] Graphical startup will continue")
        return false
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
