package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * NAT audio path used while end-to-end APK validation is being completed.
 *
 * The host-side listener path is now physically proven on the APK test device:
 * module-native-protocol-tcp bound to 172.28.0.1:4713, authenticated with the
 * Manager cookie, and returned AAudio_sink through a TCP pactl probe.
 *
 * This object therefore owns the NAT-only sequence after graphical startup:
 * 1. ensure/probe that exact authenticated listener;
 * 2. install the same cookie and PulseAudio client configuration in the already
 *    running DroidSpaces container;
 * 3. verify the real container -> 172.28.0.1:4713 -> AAudio_sink data path.
 *
 * It never starts/stops/restarts PulseAudio, the container, X11, VNC, or the
 * graphical session. HOST continues to use PulseAudioUnifiedTransport.
 */
object PulseAudioNatPreflight {
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_SH = "$TERMUX_PREFIX/bin/sh"

    private const val STATE = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val COOKIE = "$STATE/transport.cookie"
    private const val CONTROL = "$STATE/control.sock"
    private const val CLIENT_CONFIG = "$STATE/pulse-home/.config/pulse/client.conf"
    private const val LISTENERS = "$STATE/listeners"

    private const val NAT_GATEWAY = "172.28.0.1"
    private const val BASE_PORT = 4713
    private const val SERVER = "tcp:$NAT_GATEWAY:$BASE_PORT"

    private const val MANAGED = "SaaS X11 Manager Audio Configuration"
    private const val LEGACY_MANAGED = "SaaS X11 Manager PulseAudio Fix"
    private const val SCRIPT_MANAGED = "SaaS DroidSpaces Audio HostNAT"
    private const val NETLAB_MANAGED = "SaaS DroidSpaces Audio NetLab"
    private const val SCRIPT_LEGACY_MANAGED = "SaaS DroidSpaces Audio Auto"
    private const val BEGIN = "# BEGIN $MANAGED"
    private const val END = "# END $MANAGED"

    private data class CommandResult(
        val exitCode: Int,
        val stdout: List<String>,
        val stderr: List<String>
    )

    suspend fun finalizeAfterContainerReady(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val info = ContainerManager.getContainerInfo(containerName)
        if (info == null) {
            return@withContext fail(logger, "Container $containerName was not found")
        }
        if (!info.isRunning) {
            return@withContext fail(logger, "Container $containerName is not running; NAT audio client was not changed")
        }
        if (info.netMode.trim().lowercase() != "nat") {
            return@withContext fail(logger, "NAT audio finalizer received net_mode=${info.netMode}")
        }

        if (!ensureBaseListener(logger)) {
            return@withContext fail(logger, "Authenticated NAT listener is not ready on $SERVER")
        }

        val uid = termuxUid()
            ?: return@withContext fail(logger, "Termux UID could not be resolved for NAT client setup")
        val cookieEscaped = cookieEscaped(uid)
            ?: return@withContext fail(logger, "Manager PulseAudio cookie could not be serialized for the container")

        logger?.i("[CTX] NAT listener already validated; configuring container client directly")
        logger?.i("[CTX] NAT container server: $SERVER")

        val payload = buildContainerPayload(cookieEscaped)
        val command =
            "${Constants.DS_BINARY_PATH} --name=${q(containerName)} run /bin/sh -lc ${q(payload)}"

        val result = try {
            Shell.cmd(command).exec()
        } catch (e: Exception) {
            logger?.w("[PA-NAT-CONTAINER] ${e.message ?: e.javaClass.simpleName}")
            return@withContext fail(logger, "Container NAT audio client command failed")
        }

        result.out.forEach { line ->
            when {
                line.trim() == "__SAAS_NAT_AUDIO_APT__" ->
                    logger?.i("[*] Installing missing Debian/Ubuntu audio clients...")
                line.trim() == "__SAAS_NAT_AUDIO_APK__" ->
                    logger?.i("[*] Installing missing Alpine audio clients...")
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
            result.out.any { it.trim() == "__SAAS_NAT_AUDIO_READY__" }
        if (!ready) {
            logger?.w("[PA-NAT-CONTAINER] exit=${result.code}")
            return@withContext fail(
                logger,
                "NAT listener is ready, but the DroidSpaces container could not verify $SERVER"
            )
        }

        val sink = result.out.asSequence()
            .map { it.trim() }
            .firstOrNull { it == "Default Sink: AAudio_sink" || it == "Default Sink: OpenSL_ES_sink" }
            ?.removePrefix("Default Sink: ")
            ?: "Android sink"

        FixSettings.setPulseAudioApplied(
            com.saas.x11manager.X11Application.instance,
            containerName,
            true
        )
        logger?.i("[+] NAT container audio client verified through $SERVER")
        logger?.i("[+] Audio ready ($sink, $SERVER)")
        true
    }

    suspend fun ensureBaseListener(logger: ContainerLogger? = null): Boolean =
        withContext(Dispatchers.IO) {
            val uid = termuxUid()
            if (uid == null) {
                logger?.w("[PA-NAT-PREFLIGHT] Termux UID could not be resolved")
                return@withContext false
            }

            logger?.i("[PA-NAT-PREFLIGHT] endpoint=$NAT_GATEWAY:$BASE_PORT")
            logger?.i("[PA-NAT-PREFLIGHT] client config=$CLIENT_CONFIG")

            val existing = endpointInfo(uid)
            if (existing.exitCode == 0 && hasAndroidSink(existing.stdout)) {
                logger?.i("[+] NAT base listener already responds with an Android sink")
                return@withContext true
            }

            val load = controlPactl(
                uid,
                "load-module module-native-protocol-tcp " +
                    "${q("listen=$NAT_GATEWAY")} ${q("port=$BASE_PORT")} ${q("auth-cookie=$COOKIE")}"
            )
            logger?.i("[PA-NAT-LOAD] exit=${load.exitCode}")
            if (load.stdout.isNotEmpty()) {
                logger?.i("[PA-NAT-LOAD] stdout=${load.stdout.joinToString(" ").take(500)}")
            }
            if (load.stderr.isNotEmpty()) {
                logger?.w("[PA-NAT-LOAD] stderr=${load.stderr.joinToString(" ").take(500)}")
            }

            val moduleId = load.stdout.asSequence()
                .map { it.trim() }
                .firstOrNull { it.matches(Regex("""\d+""")) }
                ?.toIntOrNull()

            if (load.exitCode != 0 || moduleId == null) {
                logger?.w("[PA-NAT-PREFLIGHT] Listener load did not return a usable module id")
                return@withContext false
            }

            logger?.i("[PA-NAT-PREFLIGHT] loaded module id=$moduleId")
            repeat(20) {
                val probe = endpointInfo(uid)
                if (probe.exitCode == 0 && hasAndroidSink(probe.stdout)) {
                    persistListenerState(uid, moduleId)
                    probe.stdout
                        .filter { it.startsWith("Server String:") || it.startsWith("Default Sink:") }
                        .forEach { logger?.i("[PA-NAT-PROBE] $it") }
                    logger?.i("[+] NAT authenticated listener preflight ready on $NAT_GATEWAY:$BASE_PORT")
                    return@withContext true
                }
                delay(100)
            }

            val failedProbe = endpointInfo(uid)
            logger?.w("[PA-NAT-PROBE] exit=${failedProbe.exitCode}")
            if (failedProbe.stdout.isNotEmpty()) {
                logger?.w("[PA-NAT-PROBE] stdout=${failedProbe.stdout.joinToString(" ").take(500)}")
            }
            if (failedProbe.stderr.isNotEmpty()) {
                logger?.w("[PA-NAT-PROBE] stderr=${failedProbe.stderr.joinToString(" ").take(500)}")
            }

            // Safe without module enumeration: this id was returned by the
            // immediately preceding successful load in this invocation.
            val unload = controlPactl(uid, "unload-module $moduleId")
            logger?.i("[PA-NAT-UNLOAD] module=$moduleId exit=${unload.exitCode}")
            false
        }

    private suspend fun fail(logger: ContainerLogger?, message: String): Boolean {
        logger?.w("[!] $message")
        logger?.w("[!] Graphical startup will continue")
        return false
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

    private fun controlPactl(uid: Int, arguments: String): CommandResult =
        execAsTermux(
            uid,
            "PULSE_SERVER=${q("unix:$CONTROL")} " +
                "PULSE_COOKIE=${q(COOKIE)} " +
                "PULSE_CLIENTCONFIG=${q(CLIENT_CONFIG)} " +
                "pactl $arguments"
        )

    private fun endpointInfo(uid: Int): CommandResult =
        execAsTermux(
            uid,
            "PULSE_SERVER=${q(SERVER)} " +
                "PULSE_COOKIE=${q(COOKIE)} " +
                "PULSE_CLIENTCONFIG=${q(CLIENT_CONFIG)} pactl info"
        )

    private fun cookieEscaped(uid: Int): String? {
        val result = execAsTermux(
            uid,
            "od -An -v -tu1 ${q(COOKIE)} 2>/dev/null | " +
                "awk '{ for (i=1; i<=NF; i++) printf \"\\\\0%03o\", ${'$'}i }'"
        )
        if (result.exitCode != 0) return null
        return result.stdout.joinToString("").trim().takeIf { it.isNotEmpty() }
    }

    private fun persistListenerState(uid: Int, moduleId: Int) {
        val stateFile = "$LISTENERS/${NAT_GATEWAY}_${BASE_PORT}.state"
        execAsTermux(
            uid,
            "mkdir -p ${q(LISTENERS)} && " +
                "printf 'id=%s\\nip=%s\\nport=%s\\nowner=%s\\n' " +
                "${q(moduleId.toString())} ${q(NAT_GATEWAY)} ${q(BASE_PORT.toString())} ${q(MANAGED)} " +
                "> ${q(stateFile)} && chmod 600 ${q(stateFile)}"
        )
    }

    private fun buildContainerPayload(cookieEscaped: String): String = """
        set -u
        SERVER=${q(SERVER)}
        COOKIE_ESCAPED=${q(cookieEscaped)}
        MANAGED=${q(MANAGED)}
        LEGACY=${q(LEGACY_MANAGED)}
        SCRIPT=${q(SCRIPT_MANAGED)}
        NETLAB=${q(NETLAB_MANAGED)}
        SCRIPT_LEGACY=${q(SCRIPT_LEGACY_MANAGED)}

        need=0
        command -v pactl >/dev/null 2>&1 || need=1
        command -v speaker-test >/dev/null 2>&1 || need=1
        if [ "${'$'}need" -eq 1 ]; then
            if command -v apt-get >/dev/null 2>&1; then
                printf '%s\n' __SAAS_NAT_AUDIO_APT__
                DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1 || true
                DEBIAN_FRONTEND=noninteractive apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils >/dev/null 2>&1 || true
            elif command -v apk >/dev/null 2>&1; then
                printf '%s\n' __SAAS_NAT_AUDIO_APK__
                apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse >/dev/null 2>&1 || true
            fi
        fi
        command -v pactl >/dev/null 2>&1 || exit 60

        is_managed() {
            t="${'$'}1"
            [ -f "${'$'}t" ] || return 1
            grep -Fq "${'$'}MANAGED" "${'$'}t" 2>/dev/null || \
                grep -Fq "${'$'}LEGACY" "${'$'}t" 2>/dev/null || \
                grep -Fq "${'$'}SCRIPT" "${'$'}t" 2>/dev/null || \
                grep -Fq "${'$'}NETLAB" "${'$'}t" 2>/dev/null || \
                grep -Fq "${'$'}SCRIPT_LEGACY" "${'$'}t" 2>/dev/null
        }
        backup_unmanaged() {
            t="${'$'}1"; b="${'$'}2"
            [ -f "${'$'}t" ] || return 0
            is_managed "${'$'}t" && return 0
            [ -e "${'$'}b" ] || cp -p "${'$'}t" "${'$'}b" || exit 61
        }

        mkdir -p /root/.config/pulse /etc/profile.d || exit 62
        cookie=/root/.config/pulse/saas-audio.cookie
        printf '%b' "${'$'}COOKIE_ESCAPED" > "${'$'}cookie" || exit 63
        [ "${'$'}(wc -c < "${'$'}cookie" 2>/dev/null | tr -d ' ')" = 256 ] || exit 64
        chmod 600 "${'$'}cookie" 2>/dev/null || true

        client=/root/.config/pulse/client.conf
        backup_unmanaged "${'$'}client" "${'$'}client.saas-x11-manager.bak"
        cat > "${'$'}client" <<EOF_CLIENT
        $BEGIN
        default-server = $SERVER
        cookie-file = /root/.config/pulse/saas-audio.cookie
        autospawn = no
        enable-shm = no
        $END
        EOF_CLIENT
        chmod 600 "${'$'}client" 2>/dev/null || true

        profile=/etc/profile.d/saas-x11-audio.sh
        backup_unmanaged "${'$'}profile" "${'$'}profile.saas-x11-manager.bak"
        cat > "${'$'}profile" <<EOF_PROFILE
        $BEGIN
        export PULSE_SERVER=$SERVER
        export PULSE_COOKIE=/root/.config/pulse/saas-audio.cookie
        $END
        EOF_PROFILE
        chmod 644 "${'$'}profile" 2>/dev/null || true

        asound=/etc/asound.conf
        if command -v aplay >/dev/null 2>&1 && aplay -L 2>/dev/null | grep -q '^pulse'; then
            backup_unmanaged "${'$'}asound" "${'$'}asound.saas-x11-manager.bak"
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
        printf '%s\n' "${'$'}info" | grep -E '^(Server String|Server Version|Default Sink|Default Source):' || true
        printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${'$'}SERVER" || exit 66
        printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$' || exit 67
        printf '%s\n' __SAAS_NAT_AUDIO_READY__
    """.trimIndent()

    private fun hasAndroidSink(lines: List<String>): Boolean =
        lines.any {
            it.trim() == "Default Sink: AAudio_sink" ||
                it.trim() == "Default Sink: OpenSL_ES_sink"
        }

    private fun execAsTermux(uid: Int, command: String): CommandResult {
        val marker = "__SAAS_NAT_PREFLIGHT_RC__"
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
            val shellResult = Shell.cmd("su $uid -c ${q(wrapped)}").exec()
            val rawOut = shellResult.out.toList()
            val markerLine = rawOut.lastOrNull { it.trim().startsWith(marker) }?.trim()
            val exitCode = markerLine?.removePrefix(marker)?.toIntOrNull()
                ?: if (shellResult.isSuccess) 0 else 255
            CommandResult(
                exitCode = exitCode,
                stdout = rawOut.filterNot { it.trim().startsWith(marker) },
                stderr = shellResult.err.toList()
            )
        } catch (e: Exception) {
            CommandResult(255, emptyList(), listOf(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
