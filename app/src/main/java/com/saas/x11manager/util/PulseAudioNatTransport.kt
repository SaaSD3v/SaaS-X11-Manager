package com.saas.x11manager.util

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Single NAT audio transport.
 *
 * The process that owns the TCP socket is created by Termux itself through
 * RunCommandService. This mirrors the standalone v3.2 shell proof instead of
 * trying to imitate Termux with `su <uid>`.
 *
 * Lifecycle contract: this object never starts/stops/restarts a DroidSpaces
 * container, X11, a desktop session or VNC. It may replace only the
 * Manager-owned PulseAudio process.
 */
object PulseAudioNatTransport {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val RUN_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_SERVICE = "com.termux.app.RunCommandService"
    private const val RUN_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val RUN_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val RUN_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val RUN_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_SH = "$TERMUX_PREFIX/bin/sh"
    private const val INET_GID = 3003

    private const val BASE_PORT = 4713
    private const val MAX_PORT_SHIFT = 8
    private const val MANAGED = "SaaS X11 Manager Audio Configuration"
    private const val BEGIN = "# BEGIN $MANAGED"
    private const val END = "# END $MANAGED"

    private const val STATE = "$TERMUX_HOME/.saas-x11-manager/audio"
    private const val COMMANDS = "$STATE/run-command-v2"
    private const val PULSE_HOME = "$STATE/pulse-home"
    private const val PULSE_CONFIG = "$PULSE_HOME/.config/pulse"
    private const val PULSE_RUNTIME = "$STATE/runtime"
    private const val PULSE_STATE = "$STATE/state"
    private const val COOKIE = "$STATE/transport.cookie"
    private const val COOKIE_OCTAL = "$STATE/transport.cookie.octal"
    private const val CONTROL = "$STATE/control.sock"
    private const val PID_FILE = "$STATE/pulseaudio.pid"
    private const val LOG_FILE = "$STATE/pulseaudio.log"
    private const val POLICY_STATE = "$STATE/termux-external-apps.state"
    private const val POLICY_BACKUP = "$STATE/termux.properties.before-run-command.bak"

    private data class TermuxOwner(val uid: Int, val gid: Int)
    private data class Core(val pid: Int, val sink: String, val groups: String)
    private data class Listener(val port: Int, val sink: String)
    private data class RunResult(val line: String, val diagnostics: List<String> = emptyList())

    suspend fun prepareBeforeGraphicalStart(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext true
        val info = ContainerManager.getContainerInfo(containerName) ?: return@withContext false
        if (info.netMode.trim().lowercase() != "nat") return@withContext true

        val owner = termuxOwner() ?: return@withContext fail(logger, "Termux owner could not be resolved")
        if (!ensureRunPermission(logger)) return@withContext fail(logger, "Termux RUN_COMMAND permission is unavailable")
        if (!ensureExternalAppsPolicy(owner, logger)) return@withContext fail(logger, "Termux allow-external-apps policy is unavailable")

        logger?.i("[CTX] NAT audio executor: Termux RunCommandService")
        logger?.i("[CTX] RUN_COMMAND permission: granted")

        val core = ensureCore(owner, logger) ?: return@withContext fail(logger, "Termux could not start the NAT audio core")
        if (!hasGroup(core.groups, INET_GID)) {
            logger?.w("[!] Termux PulseAudio groups: ${core.groups}")
            return@withContext fail(logger, "Real Termux PulseAudio is missing inet/$INET_GID")
        }

        logger?.i("[+] Real Termux PulseAudio ready (${core.sink}, inet/$INET_GID)")
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

        val owner = termuxOwner() ?: return@withContext fail(logger, "Termux owner could not be resolved")
        if (!ensureRunPermission(logger)) return@withContext fail(logger, "Termux RUN_COMMAND permission is unavailable")
        if (!ensureExternalAppsPolicy(owner, logger)) return@withContext fail(logger, "Termux allow-external-apps policy is unavailable")

        val core = ensureCore(owner, logger) ?: return@withContext fail(logger, "Termux NAT audio core is unavailable")
        if (!hasGroup(core.groups, INET_GID)) return@withContext fail(logger, "Real Termux PulseAudio is missing inet/$INET_GID")

        val ip = discoverNatGateway(info, logger) ?: return@withContext fail(logger, "NAT gateway discovery failed")
        logger?.i("[CTX] Audio net_mode: nat")
        logger?.i("[CTX] Audio host endpoint: $ip")

        val ports = candidatePorts()
        if (ports.isEmpty()) return@withContext fail(logger, "No safe NAT audio port is available")
        val listener = ensureListener(owner, ip, ports, logger)
            ?: return@withContext fail(logger, "Authenticated NAT audio listener could not be created")
        val server = "tcp:$ip:${listener.port}"

        if (!verifyContainerClient(containerName, server) && !installContainerClient(containerName, server, logger)) {
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
            if (p.size != 2) return null
            TermuxOwner(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun ensureRunPermission(logger: ContainerLogger?): Boolean {
        val context = X11Application.instance
        if (context.checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED) return true

        val pkg = context.packageName
        val result = try { Shell.cmd("pm grant ${q(pkg)} ${q(RUN_PERMISSION)} 2>&1").exec() } catch (_: Exception) { null }
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

    private fun readCore(): Core? {
        val command = """
            pf=${q(PID_FILE)}; [ -f "${'$'}pf" ] || exit 1
            pid=${'$'}(sed -n 's/^pid=//p' "${'$'}pf" | sed -n '1p')
            owner=${'$'}(sed -n 's/^owner=//p' "${'$'}pf" | sed -n '1p')
            executor=${'$'}(sed -n 's/^executor=//p' "${'$'}pf" | sed -n '1p')
            sink=${'$'}(sed -n 's/^sink=//p' "${'$'}pf" | sed -n '1p')
            case "${'$'}pid" in ''|*[!0-9]*) exit 2 ;; esac
            [ "${'$'}owner" = ${q(MANAGED)} ] || exit 3
            [ "${'$'}executor" = termux-run-command-v2 ] || exit 4
            kill -0 "${'$'}pid" 2>/dev/null || exit 5
            groups=${'$'}(sed -n 's/^Groups:[[:space:]]*//p' "/proc/${'$'}pid/status" | sed -n '1p')
            printf '%s|%s|%s\n' "${'$'}pid" "${'$'}sink" "${'$'}groups"
        """.trimIndent()
        return try {
            val r = Shell.cmd(command).exec(); if (!r.isSuccess) return null
            val p = r.out.firstOrNull()?.trim()?.split('|', limit = 3) ?: return null
            val pid = p.getOrNull(0)?.toIntOrNull() ?: return null
            val sink = p.getOrNull(1) ?: return null
            if (sink !in setOf("AAudio_sink", "OpenSL_ES_sink")) return null
            Core(pid, sink, p.getOrNull(2).orEmpty())
        } catch (_: Exception) { null }
    }

    private suspend fun ensureCore(owner: TermuxOwner, logger: ContainerLogger?): Core? {
        readCore()?.let { if (hasGroup(it.groups, INET_GID)) return it }

        logger?.i("[*] Starting Manager PulseAudio through Termux...")
        val result = runTermuxScript(owner, "core", coreScript(), timeoutMs = 6500, logger = logger) ?: return null
        if (!result.line.startsWith("OK|CORE|")) {
            logger?.w("[!] Termux core result: ${result.line}")
            result.diagnostics.forEach { logger?.w("[RUN_COMMAND] $it") }
            return null
        }
        val p = result.line.split('|', limit = 6)
        val pid = p.getOrNull(2)?.toIntOrNull() ?: return null
        val sink = p.getOrNull(3) ?: return null
        val groups = p.getOrNull(5).orEmpty()
        if (sink !in setOf("AAudio_sink", "OpenSL_ES_sink")) return null
        return Core(pid, sink, groups)
    }

    private fun coreScript(): String = """
        set -u
        export HOME=${q(TERMUX_HOME)} PREFIX=${q(TERMUX_PREFIX)} TMPDIR=${q("$TERMUX_PREFIX/tmp")}
        export PATH=${q("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")}
        STATE=${q(STATE)}; CONFIG=${q(PULSE_CONFIG)}; RUNTIME=${q(PULSE_RUNTIME)}; PST=${q(PULSE_STATE)}
        COOKIE=${q(COOKIE)}; CONTROL=${q(CONTROL)}; PIDF=${q(PID_FILE)}; LOG=${q(LOG_FILE)}
        mkdir -p "${'$'}CONFIG" "${'$'}RUNTIME" "${'$'}PST" || { echo 'ERR|CORE|mkdir'; exit 1; }
        chmod 700 "${'$'}STATE" ${q(PULSE_HOME)} ${q("$PULSE_HOME/.config")} "${'$'}CONFIG" "${'$'}RUNTIME" "${'$'}PST" 2>/dev/null || true
        : > "${'$'}CONFIG/daemon.conf"
        printf '%s\n' 'autospawn = no' 'enable-shm = no' > "${'$'}CONFIG/client.conf"
        if [ ! -f "${'$'}COOKIE" ] || [ "${'$'}(wc -c < "${'$'}COOKIE" 2>/dev/null | tr -d ' ')" != 256 ]; then
            umask 077; dd if=/dev/urandom of="${'$'}COOKIE" bs=256 count=1 2>/dev/null || { echo 'ERR|CORE|cookie'; exit 2; }
        fi
        chmod 600 "${'$'}COOKIE" "${'$'}CONFIG/daemon.conf" "${'$'}CONFIG/client.conf" 2>/dev/null || true

        if [ -f "${'$'}PIDF" ]; then
            p=${'$'}(sed -n 's/^pid=//p' "${'$'}PIDF" 2>/dev/null | sed -n '1p'); o=${'$'}(sed -n 's/^owner=//p' "${'$'}PIDF" 2>/dev/null | sed -n '1p')
            case "${'$'}p" in ''|*[!0-9]*) : ;; *)
                if [ "${'$'}o" = ${q(MANAGED)} ] && kill -0 "${'$'}p" 2>/dev/null && [ -r "/proc/${'$'}p/cmdline" ]; then
                    cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}p/cmdline" 2>/dev/null || true)
                    case "${'$'}cmd" in *pulseaudio*${CONTROL}*) kill "${'$'}p" 2>/dev/null || true ;; esac
                fi ;;
            esac
        fi
        i=0; while [ -S "${'$'}CONTROL" ] && [ "${'$'}i" -lt 20 ]; do sleep .05; i=${'$'}((i+1)); done
        rm -f "${'$'}CONTROL" "${'$'}PIDF" 2>/dev/null || true

        PENV="HOME=${PULSE_HOME} XDG_CONFIG_HOME=${PULSE_HOME}/.config PULSE_CONFIG=${PULSE_CONFIG}/daemon.conf PULSE_CONFIG_PATH=${PULSE_CONFIG} PULSE_RUNTIME_PATH=${PULSE_RUNTIME} PULSE_STATE_PATH=${PULSE_STATE} PULSE_CLIENTCONFIG=${PULSE_CONFIG}/client.conf PULSE_COOKIE=${COOKIE}"
        start_one() {
            module="${'$'}1"; sink="${'$'}2"; : > "${'$'}LOG"
            eval "${'$'}PENV" nohup pulseaudio -n --daemonize=no --exit-idle-time=-1 --use-pid-file=false \
                -L "${'$'}module" -L ${q("module-native-protocol-unix socket=$CONTROL auth-cookie=$COOKIE")} \
                </dev/null >"${'$'}LOG" 2>&1 &
            p=${'$'}!; j=0
            while [ "${'$'}j" -lt 35 ]; do
                if PULSE_SERVER="unix:${CONTROL}" PULSE_COOKIE="${COOKIE}" pactl info 2>/dev/null | grep -Fq "Default Sink: ${'$'}sink"; then
                    groups=${'$'}(sed -n 's/^Groups:[[:space:]]*//p' "/proc/${'$'}p/status" 2>/dev/null | sed -n '1p')
                    uid=${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/${'$'}p/status" 2>/dev/null | sed -n '1p')
                    { printf 'pid=%s\n' "${'$'}p"; printf 'owner=%s\n' ${q(MANAGED)}; printf 'executor=termux-run-command-v2\n'; printf 'sink=%s\n' "${'$'}sink"; } > "${'$'}PIDF"
                    od -An -v -tu1 "${'$'}COOKIE" 2>/dev/null | awk '{ for (i=1; i<=NF; i++) printf "\\\\0%03o", ${'$'}i }' > ${q(COOKIE_OCTAL)}
                    chmod 600 "${'$'}PIDF" ${q(COOKIE_OCTAL)} 2>/dev/null || true
                    printf 'OK|CORE|%s|%s|%s|%s\n' "${'$'}p" "${'$'}sink" "${'$'}uid" "${'$'}groups"
                    return 0
                fi
                kill -0 "${'$'}p" 2>/dev/null || break
                sleep .1; j=${'$'}((j+1))
            done
            kill "${'$'}p" 2>/dev/null || true; sleep .1; kill -9 "${'$'}p" 2>/dev/null || true
            rm -f "${'$'}CONTROL" 2>/dev/null || true
            return 1
        }
        start_one module-aaudio-sink AAudio_sink || start_one module-sles-sink OpenSL_ES_sink || { tail -n 8 "${'$'}LOG" 2>/dev/null || true; echo 'ERR|CORE|sink'; exit 3; }
    """.trimIndent()

    private suspend fun ensureListener(
        owner: TermuxOwner,
        ip: String,
        ports: List<Int>,
        logger: ContainerLogger?
    ): Listener? {
        val result = runTermuxScript(owner, "listener", listenerScript(ip, ports), timeoutMs = 4500, logger = logger) ?: return null
        if (!result.line.startsWith("OK|LISTENER|")) {
            logger?.w("[!] Termux listener result: ${result.line}")
            result.diagnostics.forEach { logger?.w("[RUN_COMMAND] $it") }
            return null
        }
        val p = result.line.split('|', limit = 5)
        val port = p.getOrNull(2)?.toIntOrNull() ?: return null
        val sink = p.getOrNull(3) ?: return null
        if (port !in ports || sink !in setOf("AAudio_sink", "OpenSL_ES_sink")) return null
        logger?.i("[+] Authenticated PulseAudio listener ready on $ip:$port")
        return Listener(port, sink)
    }

    private fun listenerScript(ip: String, ports: List<Int>): String = """
        set -u
        export HOME=${q(TERMUX_HOME)} PREFIX=${q(TERMUX_PREFIX)} TMPDIR=${q("$TERMUX_PREFIX/tmp")}
        export PATH=${q("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")}
        COOKIE=${q(COOKIE)}; CONTROL=${q(CONTROL)}; unix="unix:${CONTROL}"; ip=${q(ip)}
        sinks=${'$'}(PULSE_SERVER="${'$'}unix" PULSE_COOKIE="${COOKIE}" pactl list short sinks 2>/dev/null) || { echo 'ERR|LISTENER|core'; exit 1; }
        if printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]AAudio_sink[[:space:]]'; then sink=AAudio_sink
        elif printf '%s\n' "${'$'}sinks" | grep -q '[[:space:]]OpenSL_ES_sink[[:space:]]'; then sink=OpenSL_ES_sink
        else echo 'ERR|LISTENER|sink'; exit 2; fi
        for port in ${ports.joinToString(" ")}; do
            tcp="tcp:${'$'}ip:${'$'}port"
            if PULSE_SERVER="${'$'}tcp" PULSE_COOKIE="${COOKIE}" pactl info 2>/dev/null | grep -Fq "Default Sink: ${'$'}sink"; then
                printf 'OK|LISTENER|%s|%s|reuse\n' "${'$'}port" "${'$'}sink"; exit 0
            fi
            id=${'$'}(PULSE_SERVER="${'$'}unix" PULSE_COOKIE="${COOKIE}" pactl load-module module-native-protocol-tcp "listen=${'$'}ip" "port=${'$'}port" "auth-cookie=${COOKIE}" 2>/dev/null || true)
            case "${'$'}id" in ''|*[!0-9]*) continue ;; esac
            j=0
            while [ "${'$'}j" -lt 8 ]; do
                if PULSE_SERVER="${'$'}tcp" PULSE_COOKIE="${COOKIE}" pactl info 2>/dev/null | grep -Fq "Default Sink: ${'$'}sink"; then
                    printf 'OK|LISTENER|%s|%s|new\n' "${'$'}port" "${'$'}sink"; exit 0
                fi
                sleep .1; j=${'$'}((j+1))
            done
            PULSE_SERVER="${'$'}unix" PULSE_COOKIE="${COOKIE}" pactl unload-module "${'$'}id" >/dev/null 2>&1 || true
        done
        echo 'ERR|LISTENER|bind'; exit 3
    """.trimIndent()

    private suspend fun runTermuxScript(
        owner: TermuxOwner,
        label: String,
        body: String,
        timeoutMs: Long,
        logger: ContainerLogger?
    ): RunResult? {
        val token = "$label-${System.nanoTime()}"
        val script = "$COMMANDS/$token.sh"
        val resultFile = "$COMMANDS/$token.result"
        val scriptText = "#!$TERMUX_SH\nexec >${q(resultFile)} 2>&1\n$body\n"

        val prepare = """
            mkdir -p ${q(COMMANDS)} || exit 1
            rm -f ${q(script)} ${q(resultFile)} 2>/dev/null || true
            printf '%s' ${q(scriptText)} > ${q(script)} || exit 2
            chown ${owner.uid}:${owner.gid} ${q(COMMANDS)} ${q(script)} 2>/dev/null || true
            chmod 700 ${q(COMMANDS)}; chmod 600 ${q(script)} || exit 3
            restorecon -RF ${q(COMMANDS)} >/dev/null 2>&1 || true
        """.trimIndent()
        val prepared = try { Shell.cmd(prepare).exec() } catch (_: Exception) { null }
        if (prepared?.isSuccess != true) return RunResult("ERR|DISPATCH|prepare", shellDiagnostics(script, resultFile))

        val context = X11Application.instance
        val intent = Intent(RUN_ACTION).apply {
            component = ComponentName(TERMUX_PACKAGE, RUN_SERVICE)
            putExtra(RUN_PATH, TERMUX_SH)
            putExtra(RUN_ARGS, arrayOf(script))
            putExtra(RUN_WORKDIR, TERMUX_HOME)
            putExtra(RUN_BACKGROUND, true)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (t: Throwable) {
            logger?.w("[!] RUN_COMMAND start failed: ${t.javaClass.simpleName}: ${t.message ?: "no message"}")
            return RunResult("ERR|DISPATCH|${t.javaClass.simpleName}", shellDiagnostics(script, resultFile))
        }

        val loops = (timeoutMs / 100L).coerceAtLeast(1).toInt()
        repeat(loops) {
            val check = try { Shell.cmd("test -s ${q(resultFile)} && cat ${q(resultFile)} || exit 1").exec() } catch (_: Exception) { null }
            if (check?.isSuccess == true && check.out.isNotEmpty()) {
                val lines = check.out.map { it.trim() }.filter { it.isNotEmpty() }
                val line = lines.lastOrNull { it.startsWith("OK|") || it.startsWith("ERR|") } ?: lines.last()
                Shell.cmd("rm -f ${q(script)} ${q(resultFile)} 2>/dev/null || true").exec()
                return RunResult(line)
            }
            delay(100)
        }

        val diag = shellDiagnostics(script, resultFile)
        Shell.cmd("rm -f ${q(script)} 2>/dev/null || true").exec()
        return RunResult("ERR|DISPATCH|timeout", diag)
    }

    private fun shellDiagnostics(script: String, resultFile: String): List<String> {
        val command = """
            printf 'permission='; dumpsys package ${q(X11Application.instance.packageName)} 2>/dev/null | grep -F ${q(RUN_PERMISSION)} | head -n 2 || true
            printf 'service='; dumpsys package ${q(TERMUX_PACKAGE)} 2>/dev/null | grep -F 'RunCommandService' | head -n 2 || true
            ls -lZ ${q(script)} ${q(resultFile)} 2>/dev/null || true
            logcat -d -t 120 2>/dev/null | grep -E 'RunCommandService|TermuxPluginUtils|Permission Denial|RUN_COMMAND' | tail -n 8 || true
        """.trimIndent()
        return try { Shell.cmd(command).exec().out.filter { it.isNotBlank() }.takeLast(12) } catch (_: Exception) { emptyList() }
    }

    private fun candidatePorts(): List<Int> {
        val max = (BASE_PORT + MAX_PORT_SHIFT).coerceAtMost(65535)
        return (BASE_PORT..max).filter { configuredPortForwardOwner(it) == null }
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
            val r = Shell.cmd(command).exec(); if (r.isSuccess) r.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } else null
        } catch (_: Exception) { null }
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
                    b1=${'$'}(printf '%s' "${'$'}hex" | cut -c7-8); b2=${'$'}(printf '%s' "${'$'}hex" | cut -c5-6); b3=${'$'}(printf '%s' "${'$'}hex" | cut -c3-4); b4=${'$'}(printf '%s' "${'$'}hex" | cut -c1-2)
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

    private fun verifyContainerClient(containerName: String, server: String): Boolean {
        val payload = """
            server=${q(server)}; cookie=/root/.config/pulse/saas-audio.cookie
            command -v pactl >/dev/null 2>&1 || exit 70
            [ -f "${'$'}cookie" ] || exit 71
            info=${'$'}(PULSE_SERVER="${'$'}server" PULSE_COOKIE="${'$'}cookie" pactl info 2>/dev/null) || exit 72
            printf '%s\n' "${'$'}info" | grep -Fq "Server String: ${'$'}server" || exit 73
            printf '%s\n' "${'$'}info" | grep -Eq '^Default Sink: (AAudio_sink|OpenSL_ES_sink)$'
        """.trimIndent()
        val command = "${Constants.DS_BINARY_PATH} --name=${q(containerName)} run /bin/sh -lc ${q(payload)}"
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private suspend fun installContainerClient(containerName: String, server: String, logger: ContainerLogger?): Boolean {
        val octal = try {
            val r = Shell.cmd("cat ${q(COOKIE_OCTAL)} 2>/dev/null").exec(); if (r.isSuccess) r.out.joinToString("").trim() else ""
        } catch (_: Exception) { "" }
        if (octal.isBlank()) return false

        val payload = clientPayload(server, octal)
        val command = "${Constants.DS_BINARY_PATH} --name=${q(containerName)} run /bin/sh -lc ${q(payload)}"
        return try {
            val r = Shell.cmd(command).exec()
            r.out.forEach { line ->
                when {
                    line.trim() == "__APT__" -> logger?.i("[*] Installing missing Debian audio clients...")
                    line.trim() == "__APK__" -> logger?.i("[*] Installing missing Alpine audio clients...")
                    line.startsWith("Server String:") || line.startsWith("Server Version:") || line.startsWith("Default Sink:") || line.startsWith("Default Source:") -> logger?.i(line)
                }
            }
            r.err.filter { it.isNotBlank() }.take(8).forEach { logger?.w(it) }
            r.isSuccess && r.out.any { it.trim() == "__READY__" }
        } catch (_: Exception) { false }
    }

    private fun clientPayload(server: String, octal: String): String = """
        set -u
        SERVER=${q(server)}; COOKIE_ESCAPED=${q(octal)}
        need=0; command -v pactl >/dev/null 2>&1 || need=1; command -v speaker-test >/dev/null 2>&1 || need=1
        if [ "${'$'}need" -eq 1 ]; then
            if command -v apt-get >/dev/null 2>&1; then
                echo __APT__; DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1 || true; DEBIAN_FRONTEND=noninteractive apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils >/dev/null 2>&1 || true
            elif command -v apk >/dev/null 2>&1; then
                echo __APK__; apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse >/dev/null 2>&1 || true
            fi
        fi
        command -v pactl >/dev/null 2>&1 || exit 60
        mkdir -p /root/.config/pulse /etc/profile.d || exit 61
        cookie=/root/.config/pulse/saas-audio.cookie; printf '%b' "${'$'}COOKIE_ESCAPED" > "${'$'}cookie" || exit 62
        [ "${'$'}(wc -c < "${'$'}cookie" | tr -d ' ')" = 256 ] || exit 63; chmod 600 "${'$'}cookie" 2>/dev/null || true
        client=/root/.config/pulse/client.conf
        if [ -f "${'$'}client" ] && ! grep -Fq ${q(MANAGED)} "${'$'}client" 2>/dev/null && [ ! -e "${'$'}client.saas-x11-manager.bak" ]; then cp -p "${'$'}client" "${'$'}client.saas-x11-manager.bak" || exit 64; fi
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
            if [ -f "${'$'}asound" ] && ! grep -Fq ${q(MANAGED)} "${'$'}asound" 2>/dev/null && [ ! -e "${'$'}asound.saas-x11-manager.bak" ]; then cp -p "${'$'}asound" "${'$'}asound.saas-x11-manager.bak" || true; fi
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
        echo __READY__
    """.trimIndent()

    private fun hasGroup(groups: String, gid: Int): Boolean = groups.trim().split(Regex("\\s+")).any { it.toIntOrNull() == gid }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
