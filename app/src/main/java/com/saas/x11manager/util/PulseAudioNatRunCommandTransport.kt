package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * NAT audio transport executed by Termux itself through its official
 * com.termux.RUN_COMMAND service.
 *
 * Why this exists:
 * Android assigns supplementary app groups (notably inet/3003) when the
 * Termux process is created by Android. Recreating only its UID through `su`
 * is not equivalent on older Android/root stacks. The standalone v3.2 script
 * worked because PulseAudio was born inside the real Termux app context.
 *
 * This transport reproduces that exact property without owning container or
 * graphical lifecycle. Root is used only to prepare the managed Termux script,
 * invoke RunCommandService, inspect the handshake and configure DroidSpaces.
 */
object PulseAudioNatRunCommandTransport {
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_RUN_SERVICE = "com.termux/com.termux.app.RunCommandService"
    private const val INET_GID = 3003
    private const val BASE_PORT = 4713
    private const val MAX_PORT_SHIFT = 12

    private const val MANAGED = "SaaS X11 Manager Audio Configuration"
    private const val BEGIN = "# BEGIN $MANAGED"
    private const val END = "# END $MANAGED"

    private const val STATE = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val PULSE_HOME = "$STATE/pulse-home"
    private const val PULSE_CONFIG = "$PULSE_HOME/.config/pulse"
    private const val PULSE_RUNTIME = "$STATE/runtime"
    private const val PULSE_STATE = "$STATE/state"
    private const val COOKIE = "$STATE/transport.cookie"
    private const val COOKIE_OCTAL = "$STATE/transport.cookie.octal"
    private const val CONTROL = "$STATE/control.sock"
    private const val LISTENERS = "$STATE/listeners"
    private const val PID_FILE = "$STATE/pulseaudio.pid"
    private const val LOG_FILE = "$STATE/pulseaudio.log"
    private const val COMMANDS = "$STATE/run-command"
    private const val EXTERNAL_STATE = "$STATE/termux-external-apps.state"
    private const val EXTERNAL_BACKUP = "$STATE/termux.properties.before-run-command.bak"

    private data class TermuxOwner(val uid: Int, val gid: Int)
    private data class Core(val sink: String, val pid: Int, val groups: String)
    private data class Listener(val port: Int, val sink: String)

    suspend fun prepareBeforeGraphicalStart(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext true
        val info = ContainerManager.getContainerInfo(containerName) ?: return@withContext false
        if (info.netMode.trim().lowercase() != "nat") return@withContext true

        val owner = termuxOwner() ?: return@withContext fail(logger, "Termux app owner could not be resolved")
        if (!ensureRunCommandPolicy(owner)) {
            return@withContext fail(logger, "Termux RUN_COMMAND policy could not be enabled")
        }

        val core = ensureCore(owner, logger) ?: return@withContext fail(
            logger,
            "Termux could not start the Manager audio core through RUN_COMMAND"
        )
        if (!groupListHas(core.groups, INET_GID)) {
            return@withContext fail(logger, "Termux RUN_COMMAND process did not inherit inet/$INET_GID")
        }

        logger?.i("[CTX] NAT audio executor: Termux RunCommandService")
        logger?.i("[+] Manager audio core running in real Termux context (inet/$INET_GID)")
        logger?.i("[+] Host audio core ready (${core.sink}, private UNIX control socket)")
        true
    }

    suspend fun finalizeAfterContainerReady(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext true
        val info = ContainerManager.getContainerInfo(containerName) ?: return@withContext false
        if (info.netMode.trim().lowercase() != "nat") return@withContext true
        if (!info.isRunning) return@withContext fail(logger, "Container $containerName is not running")

        val owner = termuxOwner() ?: return@withContext fail(logger, "Termux app owner could not be resolved")
        if (!ensureRunCommandPolicy(owner)) return@withContext fail(logger, "Termux RUN_COMMAND policy is unavailable")

        val core = ensureCore(owner, logger) ?: return@withContext fail(logger, "Termux audio core is unavailable")
        if (!groupListHas(core.groups, INET_GID)) {
            return@withContext fail(logger, "Termux audio core is missing inet/$INET_GID")
        }

        val ip = discoverNatGateway(info, logger) ?: return@withContext fail(logger, "NAT gateway discovery failed")
        logger?.i("[CTX] Audio net_mode: nat")
        logger?.i("[CTX] Audio host endpoint: $ip (port selected automatically)")

        val candidates = candidatePorts()
        if (candidates.isEmpty()) return@withContext fail(logger, "No NAT audio port is available")
        val listener = ensureListener(owner, ip, candidates, logger)
            ?: return@withContext fail(logger, "Authenticated NAT listener could not be created")
        val server = "tcp:$ip:${listener.port}"

        if (!verifyContainerClient(containerName, server) &&
            !installAndVerifyContainerClient(containerName, server, logger)
        ) {
            return@withContext fail(logger, "Container NAT audio client configuration failed")
        }

        FixSettings.setPulseAudioApplied(context, containerName, true)
        logger?.i("[+] Audio ready (${listener.sink}, $server)")
        true
    }

    private suspend fun fail(logger: ContainerLogger?, message: String): Boolean {
        logger?.w("[!] $message")
        logger?.w("[!] NAT audio failed fast; graphical startup will continue")
        return false
    }

    private fun termuxOwner(): TermuxOwner? {
        val command = """
            test -x ${quote("$TERMUX_PREFIX/bin/sh")} || exit 1
            uid=${'$'}(stat -c '%u' ${quote(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${quote(TERMUX_HOME)} 2>/dev/null)
            gid=${'$'}(stat -c '%g' ${quote(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%g' ${quote(TERMUX_HOME)} 2>/dev/null)
            case "${'$'}uid:${'$'}gid" in *[!0-9:]*) exit 2 ;; esac
            printf '%s|%s\n' "${'$'}uid" "${'$'}gid"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) return null
            val parts = result.out.firstOrNull()?.trim()?.split('|') ?: return null
            if (parts.size != 2) return null
            val uid = parts[0].toIntOrNull() ?: return null
            val gid = parts[1].toIntOrNull() ?: return null
            if (uid <= 0 || gid <= 0) null else TermuxOwner(uid, gid)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * RunCommandService requires allow-external-apps=true even for root senders.
     * Keep a one-time backup/state marker before changing the Termux property.
     * No Termux process is force-stopped; settings are reloaded with the same
     * broadcast used by termux-reload-settings.
     */
    private fun ensureRunCommandPolicy(owner: TermuxOwner): Boolean {
        val props = "$TERMUX_HOME/.termux/termux.properties"
        val propsDir = "$TERMUX_HOME/.termux"
        val command = """
            uid=${owner.uid}; gid=${owner.gid}
            state=${quote(STATE)}; props=${quote(props)}; dir=${quote(propsDir)}
            mkdir -p "${'$'}state" "${'$'}dir" ${quote(COMMANDS)} || exit 10
            chown "${'$'}uid:${'$'}gid" "${'$'}state" "${'$'}dir" ${quote(COMMANDS)} 2>/dev/null || true
            chmod 700 "${'$'}state" "${'$'}dir" ${quote(COMMANDS)} 2>/dev/null || true

            if [ ! -f ${quote(EXTERNAL_STATE)} ]; then
                if [ -f "${'$'}props" ]; then
                    cp -p "${'$'}props" ${quote(EXTERNAL_BACKUP)} || exit 11
                    printf 'had_file=1\npath=%s\n' "${'$'}props" > ${quote(EXTERNAL_STATE)} || exit 12
                    chown "${'$'}uid:${'$'}gid" ${quote(EXTERNAL_BACKUP)} ${quote(EXTERNAL_STATE)} 2>/dev/null || true
                    chmod 600 ${quote(EXTERNAL_BACKUP)} ${quote(EXTERNAL_STATE)} 2>/dev/null || true
                else
                    printf 'had_file=0\npath=%s\n' "${'$'}props" > ${quote(EXTERNAL_STATE)} || exit 13
                    chown "${'$'}uid:${'$'}gid" ${quote(EXTERNAL_STATE)} 2>/dev/null || true
                    chmod 600 ${quote(EXTERNAL_STATE)} 2>/dev/null || true
                fi
            fi

            if ! grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*${'$'}' "${'$'}props" 2>/dev/null; then
                tmp="${'$'}props.saas-audio.${'$'}${'$'}"
                : > "${'$'}tmp" || exit 14
                found=0
                if [ -f "${'$'}props" ]; then
                    while IFS= read -r line || [ -n "${'$'}line" ]; do
                        case "${'$'}line" in
                            allow-external-apps=*|allow-external-apps\ =*)
                                if [ "${'$'}found" -eq 0 ]; then printf '%s\n' 'allow-external-apps=true' >> "${'$'}tmp"; found=1; fi
                                ;;
                            *) printf '%s\n' "${'$'}line" >> "${'$'}tmp" ;;
                        esac
                    done < "${'$'}props"
                fi
                [ "${'$'}found" -eq 1 ] || printf '%s\n' 'allow-external-apps=true' >> "${'$'}tmp"
                mv -f "${'$'}tmp" "${'$'}props" || exit 15
                chown "${'$'}uid:${'$'}gid" "${'$'}props" 2>/dev/null || true
                chmod 600 "${'$'}props" 2>/dev/null || true
            fi

            am broadcast --user 0 -a com.termux.app.reload_style com.termux >/dev/null 2>&1 || true
            grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*${'$'}' "${'$'}props"
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private suspend fun ensureCore(owner: TermuxOwner, logger: ContainerLogger?): Core? {
        // A core created by this executor can be reused without touching it.
        readRunCommandCore()?.let { existing ->
            if (groupListHas(existing.groups, INET_GID) && probeUnixCoreRoot(existing.sink)) {
                return existing
            }
        }

        logger?.i("[*] Starting Manager PulseAudio from the real Termux app context...")
        logger?.i("[CTX] Only Manager PulseAudio may be replaced; container and X11/VNC are unchanged")

        val result = runInTermux(owner, "start-core", buildStartCoreScript()) ?: return null
        if (!result.startsWith("OK|")) {
            logger?.w("[!] Termux core handshake: $result")
            logPulseTail(logger)
            return null
        }
        val parts = result.split('|', limit = 5)
        if (parts.size < 5) return null
        val pid = parts[1].toIntOrNull() ?: return null
        val sink = parts[2]
        val groups = parts[4]
        if (sink !in setOf("AAudio_sink", "OpenSL_ES_sink")) return null
        val core = Core(sink, pid, groups)
        if (!groupListHas(groups, INET_GID)) {
            logger?.w("[!] Real Termux PulseAudio groups: $groups")
            return null
        }
        return core
    }

    private fun readRunCommandCore(): Core? {
        val command = """
            pf=${quote(PID_FILE)}; [ -f "${'$'}pf" ] || exit 1
            pid=${'$'}(sed -n 's/^pid=//p' "${'$'}pf" | sed -n '1p')
            owner=${'$'}(sed -n 's/^owner=//p' "${'$'}pf" | sed -n '1p')
            executor=${'$'}(sed -n 's/^executor=//p' "${'$'}pf" | sed -n '1p')
            sink=${'$'}(sed -n 's/^sink=//p' "${'$'}pf" | sed -n '1p')
            case "${'$'}pid" in ''|*[!0-9]*) exit 2 ;; esac
            [ "${'$'}owner" = ${quote(MANAGED)} ] || exit 3
            [ "${'$'}executor" = termux-run-command ] || exit 4
            kill -0 "${'$'}pid" 2>/dev/null || exit 5
            uid=${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/${'$'}pid/status" | sed -n '1p')
            groups=${'$'}(sed -n 's/^Groups:[[:space:]]*//p' "/proc/${'$'}pid/status" | sed -n '1p')
            printf '%s|%s|%s|%s\n' "${'$'}pid" "${'$'}sink" "${'$'}uid" "${'$'}groups"
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) return null
            val p = result.out.firstOrNull()?.trim()?.split('|', limit = 4) ?: return null
            if (p.size != 4) return null
            val pid = p[0].toIntOrNull() ?: return null
            val sink = p[1]
            if (sink !in setOf("AAudio_sink", "OpenSL_ES_sink")) return null
            Core(sink, pid, p[3])
        } catch (_: Exception) {
            null
        }
    }

    private fun probeUnixCoreRoot(expectedSink: String): Boolean {
        val command = """
            export HOME=${quote(TERMUX_HOME)} PREFIX=${quote(TERMUX_PREFIX)} TMPDIR=${quote("$TERMUX_PREFIX/tmp")}
            export PATH=${quote("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")}
            PULSE_SERVER=${quote("unix:$CONTROL")} PULSE_COOKIE=${quote(COOKIE)} \
                pactl info 2>/dev/null | grep -Fq ${quote("Default Sink: $expectedSink")}
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun buildStartCoreScript(): String = """
        #!$TERMUX_PREFIX/bin/sh
        set -u
        export HOME=${quote(TERMUX_HOME)} PREFIX=${quote(TERMUX_PREFIX)} TMPDIR=${quote("$TERMUX_PREFIX/tmp")}
        export PATH=${quote("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")}
        STATE=${quote(STATE)}; CONFIG=${quote(PULSE_CONFIG)}; RUNTIME=${quote(PULSE_RUNTIME)}; PST=${quote(PULSE_STATE)}
        COOKIE=${quote(COOKIE)}; CONTROL=${quote(CONTROL)}; PIDF=${quote(PID_FILE)}; LOG=${quote(LOG_FILE)}
        mkdir -p "${'$'}CONFIG" "${'$'}RUNTIME" "${'$'}PST" ${quote(LISTENERS)} || { printf '%s\n' 'ERR|mkdir'; exit 1; }
        chmod 700 "${'$'}STATE" ${quote(PULSE_HOME)} ${quote("$PULSE_HOME/.config")} "${'$'}CONFIG" "${'$'}RUNTIME" "${'$'}PST" ${quote(LISTENERS)} 2>/dev/null || true
        : > "${'$'}CONFIG/daemon.conf"
        printf '%s\n' 'autospawn = no' 'enable-shm = no' > "${'$'}CONFIG/client.conf"
        if [ ! -f "${'$'}COOKIE" ] || [ "${'$'}(wc -c < "${'$'}COOKIE" 2>/dev/null | tr -d ' ')" != 256 ]; then
            umask 077; dd if=/dev/urandom of="${'$'}COOKIE" bs=256 count=1 2>/dev/null || { printf '%s\n' 'ERR|cookie'; exit 2; }
        fi
        chmod 600 "${'$'}COOKIE" "${'$'}CONFIG/daemon.conf" "${'$'}CONFIG/client.conf" 2>/dev/null || true

        old_sink=''
        if PULSE_SERVER="unix:${CONTROL}" PULSE_COOKIE="${COOKIE}" pactl info >/dev/null 2>&1; then
            sinks=${'$'}(PULSE_SERVER="unix:${CONTROL}" PULSE_COOKIE="${COOKIE}" pactl list short sinks 2>/dev/null || true)
            if printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]AAudio_sink[[:space:]]'; then old_sink=AAudio_sink
            elif printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]OpenSL_ES_sink[[:space:]]'; then old_sink=OpenSL_ES_sink; fi
        fi

        if [ -f "${'$'}PIDF" ]; then
            p=${'$'}(sed -n 's/^pid=//p' "${'$'}PIDF" 2>/dev/null | sed -n '1p'); o=${'$'}(sed -n 's/^owner=//p' "${'$'}PIDF" 2>/dev/null | sed -n '1p')
            case "${'$'}p" in ''|*[!0-9]*) : ;; *)
                if [ "${'$'}o" = ${quote(MANAGED)} ] && kill -0 "${'$'}p" 2>/dev/null && [ -r "/proc/${'$'}p/cmdline" ]; then
                    c=${'$'}(tr '\000' ' ' < "/proc/${'$'}p/cmdline" 2>/dev/null || true)
                    case "${'$'}c" in *pulseaudio*${CONTROL}*) kill "${'$'}p" 2>/dev/null || true ;; esac
                fi
                ;;
            esac
        fi
        i=0; while [ -S "${'$'}CONTROL" ] && [ "${'$'}i" -lt 20 ]; do sleep .05; i=${'$'}((i+1)); done
        rm -f "${'$'}CONTROL" "${'$'}PIDF" 2>/dev/null || true

        PENV="HOME=${PULSE_HOME} XDG_CONFIG_HOME=${PULSE_HOME}/.config PULSE_CONFIG=${PULSE_CONFIG}/daemon.conf PULSE_CONFIG_PATH=${PULSE_CONFIG} PULSE_RUNTIME_PATH=${PULSE_RUNTIME} PULSE_STATE_PATH=${PULSE_STATE} PULSE_CLIENTCONFIG=${PULSE_CONFIG}/client.conf PULSE_COOKIE=${COOKIE}"

        start_one() {
            module="${'$'}1"; sink="${'$'}2"
            : > "${'$'}LOG"
            eval "${'$'}PENV" nohup pulseaudio -n --daemonize=no --exit-idle-time=-1 --use-pid-file=false \
                -L "${'$'}module" \
                -L ${quote("module-native-protocol-unix socket=$CONTROL auth-cookie=$COOKIE")} \
                </dev/null >"${'$'}LOG" 2>&1 &
            p=${'$'}!
            j=0
            while [ "${'$'}j" -lt 35 ]; do
                if PULSE_SERVER="unix:${CONTROL}" PULSE_COOKIE="${COOKIE}" pactl info 2>/dev/null | grep -Fq "Default Sink: ${'$'}sink"; then
                    groups=${'$'}(sed -n 's/^Groups:[[:space:]]*//p' "/proc/${'$'}p/status" 2>/dev/null | sed -n '1p')
                    uid=${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/${'$'}p/status" 2>/dev/null | sed -n '1p')
                    { printf 'pid=%s\n' "${'$'}p"; printf 'owner=%s\n' ${quote(MANAGED)}; printf 'executor=termux-run-command\n'; printf 'sink=%s\n' "${'$'}sink"; } > "${'$'}PIDF"
                    chmod 600 "${'$'}PIDF" 2>/dev/null || true
                    od -An -v -tu1 "${'$'}COOKIE" 2>/dev/null | awk '{ for (i=1; i<=NF; i++) printf "\\\\0%03o", ${'$'}i }' > ${quote(COOKIE_OCTAL)}
                    chmod 600 ${quote(COOKIE_OCTAL)} 2>/dev/null || true
                    printf 'OK|%s|%s|%s|%s\n' "${'$'}p" "${'$'}sink" "${'$'}uid" "${'$'}groups"
                    return 0
                fi
                kill -0 "${'$'}p" 2>/dev/null || break
                sleep .1; j=${'$'}((j+1))
            done
            kill "${'$'}p" 2>/dev/null || true; sleep .1; kill -9 "${'$'}p" 2>/dev/null || true
            rm -f "${'$'}CONTROL" 2>/dev/null || true
            return 1
        }

        if [ "${'$'}old_sink" = OpenSL_ES_sink ]; then
            start_one module-sles-sink OpenSL_ES_sink || start_one module-aaudio-sink AAudio_sink || { tail -n 8 "${'$'}LOG" >&2; printf '%s\n' 'ERR|core'; exit 3; }
        else
            start_one module-aaudio-sink AAudio_sink || start_one module-sles-sink OpenSL_ES_sink || { tail -n 8 "${'$'}LOG" >&2; printf '%s\n' 'ERR|core'; exit 3; }
        fi
    """.trimIndent()

    private suspend fun ensureListener(
        owner: TermuxOwner,
        ip: String,
        ports: List<Int>,
        logger: ContainerLogger?
    ): Listener? {
        val portsText = ports.joinToString(" ")
        val result = runInTermux(owner, "listener", buildListenerScript(ip, portsText), timeoutMs = 5000) ?: return null
        if (!result.startsWith("OK|")) {
            logger?.w("[!] Termux listener handshake: $result")
            logPulseTail(logger)
            return null
        }
        val parts = result.split('|', limit = 4)
        if (parts.size < 4) return null
        val port = parts[1].toIntOrNull() ?: return null
        val sink = parts[2]
        if (port !in ports || sink !in setOf("AAudio_sink", "OpenSL_ES_sink")) return null
        if (port != BASE_PORT) logger?.i("[+] Selected audio port: $port")
        logger?.i("[+] Authenticated PulseAudio listener ready on $ip:$port")
        return Listener(port, sink)
    }

    private fun buildListenerScript(ip: String, ports: String): String = """
        #!$TERMUX_PREFIX/bin/sh
        set -u
        export HOME=${quote(TERMUX_HOME)} PREFIX=${quote(TERMUX_PREFIX)} TMPDIR=${quote("$TERMUX_PREFIX/tmp")}
        export PATH=${quote("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")}
        COOKIE=${quote(COOKIE)}; CONTROL=${quote(CONTROL)}; LISTENERS=${quote(LISTENERS)}
        server_unix="unix:${CONTROL}"
        mkdir -p "${'$'}LISTENERS" || { printf '%s\n' 'ERR|mkdir'; exit 1; }
        info=${'$'}(PULSE_SERVER="${'$'}server_unix" PULSE_COOKIE="${COOKIE}" pactl info 2>/dev/null) || { printf '%s\n' 'ERR|core'; exit 2; }
        sinks=${'$'}(PULSE_SERVER="${'$'}server_unix" PULSE_COOKIE="${COOKIE}" pactl list short sinks 2>/dev/null || true)
        if printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]AAudio_sink[[:space:]]'; then sink=AAudio_sink
        elif printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]OpenSL_ES_sink[[:space:]]'; then sink=OpenSL_ES_sink
        else printf '%s\n' 'ERR|sink'; exit 3; fi

        ip=${quote(ip)}
        for port in $ports; do
            tcp="tcp:${'$'}ip:${'$'}port"
            if PULSE_SERVER="${'$'}tcp" PULSE_COOKIE="${COOKIE}" pactl info 2>/dev/null | grep -Fq "Default Sink: ${'$'}sink"; then
                printf 'OK|%s|%s|reuse\n' "${'$'}port" "${'$'}sink"; exit 0
            fi
            id=${'$'}(PULSE_SERVER="${'$'}server_unix" PULSE_COOKIE="${COOKIE}" pactl load-module module-native-protocol-tcp "listen=${'$'}ip" "port=${'$'}port" "auth-cookie=${COOKIE}" 2>/dev/null || true)
            case "${'$'}id" in ''|*[!0-9]*) continue ;; esac
            j=0
            while [ "${'$'}j" -lt 8 ]; do
                if PULSE_SERVER="${'$'}tcp" PULSE_COOKIE="${COOKIE}" pactl info 2>/dev/null | grep -Fq "Default Sink: ${'$'}sink"; then
                    sf="${'$'}LISTENERS/${'$'}(printf '%s_%s' "${'$'}ip" "${'$'}port" | tr -c 'A-Za-z0-9._-' '_').state"
                    printf 'id=%s\nip=%s\nport=%s\nowner=%s\n' "${'$'}id" "${'$'}ip" "${'$'}port" ${quote(MANAGED)} > "${'$'}sf"
                    chmod 600 "${'$'}sf" 2>/dev/null || true
                    printf 'OK|%s|%s|new\n' "${'$'}port" "${'$'}sink"; exit 0
                fi
                sleep .1; j=${'$'}((j+1))
            done
            PULSE_SERVER="${'$'}server_unix" PULSE_COOKIE="${COOKIE}" pactl unload-module "${'$'}id" >/dev/null 2>&1 || true
        done
        printf '%s\n' 'ERR|listener'
        exit 4
    """.trimIndent()

    private suspend fun runInTermux(
        owner: TermuxOwner,
        label: String,
        scriptBody: String,
        timeoutMs: Long = 6000
    ): String? {
        val token = "${label}-${System.nanoTime()}"
        val script = "$COMMANDS/$token.sh"
        val resultFile = "$COMMANDS/$token.result"
        val wrapper = scriptBody + "\nrc=\$?\n"
        val prepare = """
            mkdir -p ${quote(COMMANDS)} || exit 1
            rm -f ${quote(resultFile)} ${quote(script)} 2>/dev/null || true
            printf '%s' ${quote(wrapper)} > ${quote(script)} || exit 2
            chown ${owner.uid}:${owner.gid} ${quote(COMMANDS)} ${quote(script)} 2>/dev/null || true
            chmod 700 ${quote(COMMANDS)} ${quote(script)} || exit 3
        """.trimIndent()
        try {
            if (!Shell.cmd(prepare).exec().isSuccess) return null

            // The command script writes its meaningful status to stdout. Redirect
            // it in a tiny Termux-owned launcher so root does not need PendingIntent
            // result APIs and older Termux builds remain supported.
            val launcher = "$COMMANDS/$token-launcher.sh"
            val launcherBody = """
                #!$TERMUX_PREFIX/bin/sh
                ${quote(script)} > ${quote(resultFile)} 2>&1
                chmod 600 ${quote(resultFile)} 2>/dev/null || true
            """.trimIndent() + "\n"
            val writeLauncher = "printf '%s' ${quote(launcherBody)} > ${quote(launcher)} && chown ${owner.uid}:${owner.gid} ${quote(launcher)} && chmod 700 ${quote(launcher)}"
            if (!Shell.cmd(writeLauncher).exec().isSuccess) return null

            val start = """
                am startservice --user 0 -n ${quote(TERMUX_RUN_SERVICE)} \
                    -a com.termux.RUN_COMMAND \
                    --es com.termux.RUN_COMMAND_PATH ${quote(launcher)} \
                    --es com.termux.RUN_COMMAND_WORKDIR ${quote(TERMUX_HOME)} \
                    --ez com.termux.RUN_COMMAND_BACKGROUND true
            """.trimIndent()
            val started = Shell.cmd(start).exec()
            if (!started.isSuccess) return null

            val loops = (timeoutMs / 100L).coerceAtLeast(1).toInt()
            repeat(loops) {
                val check = Shell.cmd("test -s ${quote(resultFile)} && cat ${quote(resultFile)} || exit 1").exec()
                if (check.isSuccess && check.out.isNotEmpty()) {
                    val lines = check.out.map { it.trim() }.filter { it.isNotEmpty() }
                    val meaningful = lines.lastOrNull { it.startsWith("OK|") || it.startsWith("ERR|") }
                        ?: lines.lastOrNull()
                    Shell.cmd("rm -f ${quote(script)} ${quote(launcher)} ${quote(resultFile)} 2>/dev/null || true").exec()
                    return meaningful
                }
                delay(100)
            }
            Shell.cmd("rm -f ${quote(script)} ${quote(launcher)} 2>/dev/null || true").exec()
            return null
        } catch (_: Exception) {
            return null
        }
    }

    private suspend fun discoverNatGateway(info: ContainerInfo, logger: ContainerLogger?): String? {
        val pid = info.pid ?: return null
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            pid=$pid; gw=''
            if [ -x ${quote(busybox)} ]; then
                gw=${'$'}(${quote(busybox)} nsenter -t "${'$'}pid" -n ${quote(busybox)} ip -4 route show default 2>/dev/null | sed -n 's/^default via \([0-9.][0-9.]*\).*/\1/p' | sed -n '1p')
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
                logger?.w("[!] Live NAT route was not readable; using verified DroidSpaces host gateway 172.28.0.1")
                "172.28.0.1"
            }
            else -> null
        }
    }

    private fun candidatePorts(): List<Int> {
        val max = (BASE_PORT + MAX_PORT_SHIFT).coerceAtMost(65535)
        return (BASE_PORT..max).filter { configuredPortForwardOwner(it) == null }
    }

    private fun configuredPortForwardOwner(port: Int): String? {
        val command = """
            wanted=$port
            for cfg in ${quote(Constants.CONTAINERS_DIR)}/*/${quote(Constants.CONFIG_FILE)}; do
                [ -f "${'$'}cfg" ] || continue
                net=${'$'}(sed -n 's/^net_mode=//p' "${'$'}cfg" | tail -n 1); [ "${'$'}net" = nat ] || continue
                name=${'$'}(sed -n 's/^name=//p' "${'$'}cfg" | sed -n '1p'); [ -n "${'$'}name" ] || { d=${'$'}{cfg%/${Constants.CONFIG_FILE}}; name=${'$'}{d##*/}; }
                value=${'$'}(sed -n 's/^port_forwards=//p' "${'$'}cfg" | sed -n '1p')
                oldifs=${'$'}IFS; IFS=,
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
            val result = Shell.cmd(command).exec()
            if (result.isSuccess) result.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } else null
        } catch (_: Exception) { null }
    }

    private fun verifyContainerClient(containerName: String, server: String): Boolean {
        val payload = """
            server=${quote(server)}; cookie=/root/.config/pulse/saas-audio.cookie
            command -v pactl >/dev/null 2>&1 || exit 70
            [ -f "${'$'}cookie" ] || exit 71
            [ "${'$'}(wc -c < "${'$'}cookie" 2>/dev/null | tr -d ' ')" = 256 ] || exit 72
            info=${'$'}(PULSE_SERVER="${'$'}server" PULSE_COOKIE="${'$'}cookie" pactl info 2>/dev/null) || exit 73
            printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${'$'}server" || exit 74
            printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$'
        """.trimIndent()
        val command = "${Constants.DS_BINARY_PATH} --name=${quote(containerName)} run /bin/sh -lc ${quote(payload)}"
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private suspend fun installAndVerifyContainerClient(
        containerName: String,
        server: String,
        logger: ContainerLogger?
    ): Boolean {
        val octal = try {
            val result = Shell.cmd("cat ${quote(COOKIE_OCTAL)} 2>/dev/null").exec()
            if (result.isSuccess) result.out.joinToString("").trim() else ""
        } catch (_: Exception) { "" }
        if (octal.isBlank()) return false

        val payload = buildClientPayload(server, octal)
        val command = "${Constants.DS_BINARY_PATH} --name=${quote(containerName)} run /bin/sh -lc ${quote(payload)}"
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
        } catch (_: Exception) { false }
    }

    private fun buildClientPayload(server: String, octal: String): String = """
        set -u
        SERVER=${quote(server)}; COOKIE_ESCAPED=${quote(octal)}; MANAGED=${quote(MANAGED)}
        need=0; command -v pactl >/dev/null 2>&1 || need=1; command -v speaker-test >/dev/null 2>&1 || need=1
        if [ "${'$'}need" -eq 1 ]; then
            if command -v apt-get >/dev/null 2>&1; then
                printf '%s\n' __SAAS_AUDIO_APT__; DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1 || true
                DEBIAN_FRONTEND=noninteractive apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils >/dev/null 2>&1 || true
            elif command -v apk >/dev/null 2>&1; then
                printf '%s\n' __SAAS_AUDIO_APK__; apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse >/dev/null 2>&1 || true
            fi
        fi
        command -v pactl >/dev/null 2>&1 || exit 60
        mkdir -p /root/.config/pulse /etc/profile.d || exit 61
        cookie=/root/.config/pulse/saas-audio.cookie
        printf '%b' "${'$'}COOKIE_ESCAPED" > "${'$'}cookie" || exit 62
        [ "${'$'}(wc -c < "${'$'}cookie" | tr -d ' ')" = 256 ] || exit 63; chmod 600 "${'$'}cookie" 2>/dev/null || true

        client=/root/.config/pulse/client.conf
        if [ -f "${'$'}client" ] && ! grep -Fq "${'$'}MANAGED" "${'$'}client" 2>/dev/null && [ ! -e "${'$'}client.saas-x11-manager.bak" ]; then cp -p "${'$'}client" "${'$'}client.saas-x11-manager.bak" || exit 64; fi
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

        asound=/etc/asound.conf
        if command -v aplay >/dev/null 2>&1 && PULSE_SERVER="${'$'}SERVER" PULSE_COOKIE="${'$'}cookie" aplay -L 2>/dev/null | grep -q '^pulse'; then
            if [ -f "${'$'}asound" ] && ! grep -Fq "${'$'}MANAGED" "${'$'}asound" 2>/dev/null && [ ! -e "${'$'}asound.saas-x11-manager.bak" ]; then cp -p "${'$'}asound" "${'$'}asound.saas-x11-manager.bak" || true; fi
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

    private fun groupListHas(groups: String, gid: Int): Boolean =
        groups.trim().split(Regex("\\s+")).any { it.toIntOrNull() == gid }

    private fun validIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
    }

    private fun hostOwnsIpv4(ip: String): Boolean {
        if (!validIpv4(ip)) return false
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            ip=${quote(ip)}
            if [ -x /system/bin/ip ]; then out=${'$'}(/system/bin/ip -4 -o addr show 2>/dev/null)
            elif [ -x ${quote(busybox)} ]; then out=${'$'}(${quote(busybox)} ip -4 -o addr show 2>/dev/null)
            else out=${'$'}(ip -4 -o addr show 2>/dev/null || true); fi
            printf '%s\n' "${'$'}out" | grep -Eq "[[:space:]]inet[[:space:]]${'$'}ip/"
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private suspend fun logPulseTail(logger: ContainerLogger?) {
        val lines = try {
            val result = Shell.cmd("tail -n 12 ${quote(LOG_FILE)} 2>/dev/null || true").exec()
            result.out
        } catch (_: Exception) { emptyList() }
        lines.filter { it.isNotBlank() }.forEach { logger?.w("[PA] $it") }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
