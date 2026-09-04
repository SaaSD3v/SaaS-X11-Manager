package com.saas.x11manager.util

import android.content.Intent
import android.content.pm.PackageManager
import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Makes the Termux RUN_COMMAND bridge usable before graphical startup.
 *
 * Termux caches termux.properties in TermuxAppSharedProperties. Merely writing
 * allow-external-apps=true is not enough when the Termux app process is already
 * alive in the background: its RunCommandService may keep seeing the old cached
 * value and silently reject the command. This preflight therefore performs a
 * one-time cache refresh before the Manager-owned PulseAudio core is prepared,
 * then proves command execution with a Termux-owned file handshake while the
 * Manager is still foreground.
 */
object TermuxRunCommandPreflight {
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
    private const val COMMANDS = "$STATE/run-command-preflight"
    private const val POLICY_STATE = "$STATE/termux-external-apps.state"
    private const val POLICY_BACKUP = "$STATE/termux.properties.before-run-command.bak"
    private const val POLICY_CACHE_MARKER = "$STATE/termux-external-apps.cache-v2"

    private data class TermuxOwner(val uid: Int, val gid: Int)

    suspend fun prepareBeforeAudioCore(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext true

        val owner = termuxOwner()
        if (owner == null) {
            logger?.w("[!] Termux RUN_COMMAND preflight: Termux owner could not be resolved")
            return@withContext false
        }

        if (!ensureRunPermission(logger)) {
            logger?.w("[!] Termux RUN_COMMAND preflight: permission unavailable")
            return@withContext false
        }

        if (!prepareExternalAppsPolicy(owner, logger)) {
            logger?.w("[!] Termux RUN_COMMAND preflight: allow-external-apps could not be activated")
            return@withContext false
        }

        val probe = runForegroundProbe(owner, logger)
        if (probe != "OK|BRIDGE|ready") {
            logger?.w("[!] Termux RUN_COMMAND preflight failed before graphical startup")
            return@withContext false
        }

        logger?.i("[+] Termux RUN_COMMAND bridge ready before audio core")
        true
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
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) return null
            val parts = result.out.firstOrNull()?.trim()?.split('|') ?: return null
            val uid = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val gid = parts.getOrNull(1)?.toIntOrNull() ?: return null
            if (uid > 0 && gid > 0) TermuxOwner(uid, gid) else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun ensureRunPermission(logger: ContainerLogger?): Boolean {
        val context = X11Application.instance
        if (context.checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED) return true
        val result = try {
            Shell.cmd("pm grant ${q(context.packageName)} ${q(RUN_PERMISSION)} 2>&1").exec()
        } catch (_: Exception) {
            null
        }
        if (context.checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED) return true
        result?.out?.filter { it.isNotBlank() }?.takeLast(4)?.forEach { logger?.w("[RUN_COMMAND] $it") }
        result?.err?.filter { it.isNotBlank() }?.takeLast(4)?.forEach { logger?.w("[RUN_COMMAND] $it") }
        return false
    }

    /**
     * Writes allow-external-apps=true and invalidates the already-running
     * com.termux Java process exactly when needed. Killing only the exact
     * Android app process avoids package force-stop state and does not target
     * independent Termux UID processes such as the Manager PulseAudio daemon.
     * The cache-v2 marker makes this a one-time migration unless the property
     * is later changed away from true.
     */
    private suspend fun prepareExternalAppsPolicy(
        owner: TermuxOwner,
        logger: ContainerLogger?
    ): Boolean {
        val propsDir = "$TERMUX_HOME/.termux"
        val props = "$propsDir/termux.properties"
        val command = """
            uid=${owner.uid}; gid=${owner.gid}; props=${q(props)}; dir=${q(propsDir)}; refresh=0
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
                refresh=1
            fi

            [ -f ${q(POLICY_CACHE_MARKER)} ] || refresh=1

            if [ "${'$'}refresh" -eq 1 ]; then
                # Termux caches TermuxAppSharedProperties in its Java process.
                # Kill only the exact app process so the next explicit service
                # start reloads termux.properties from disk. Do not force-stop
                # the package and do not kill other same-UID processes.
                for p in /proc/[0-9]*; do
                    [ -r "${'$'}p/cmdline" ] || continue
                    cmd=${'$'}(tr '\000' '\n' < "${'$'}p/cmdline" 2>/dev/null | sed -n '1p')
                    [ "${'$'}cmd" = ${q(TERMUX_PACKAGE)} ] || continue
                    pid=${'$'}{p##*/}
                    kill -9 "${'$'}pid" 2>/dev/null || true
                done
                i=0
                while [ "${'$'}i" -lt 30 ]; do
                    live=0
                    for p in /proc/[0-9]*; do
                        [ -r "${'$'}p/cmdline" ] || continue
                        cmd=${'$'}(tr '\000' '\n' < "${'$'}p/cmdline" 2>/dev/null | sed -n '1p')
                        [ "${'$'}cmd" = ${q(TERMUX_PACKAGE)} ] && { live=1; break; }
                    done
                    [ "${'$'}live" -eq 0 ] && break
                    sleep 0.1
                    i=${'$'}((i + 1))
                done
                : > ${q(POLICY_CACHE_MARKER)} || exit 16
                chown "${'$'}uid:${'$'}gid" ${q(POLICY_CACHE_MARKER)} 2>/dev/null || true
                chmod 600 ${q(POLICY_CACHE_MARKER)} 2>/dev/null || true
                printf '%s\n' REFRESHED
            else
                printf '%s\n' READY
            fi

            grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*${'$'}' "${'$'}props" || exit 17
        """.trimIndent()

        val result = try { Shell.cmd(command).exec() } catch (_: Exception) { null }
        if (result?.isSuccess == true) {
            if (result.out.any { it.trim() == "REFRESHED" }) {
                logger?.i("[*] Refreshed Termux allow-external-apps cache before audio startup")
            }
            return true
        }
        result?.out?.filter { it.isNotBlank() }?.takeLast(8)?.forEach { logger?.w("[TERMUX_POLICY] $it") }
        result?.err?.filter { it.isNotBlank() }?.takeLast(8)?.forEach { logger?.w("[TERMUX_POLICY] $it") }
        return false
    }

    private suspend fun runForegroundProbe(
        owner: TermuxOwner,
        logger: ContainerLogger?
    ): String? {
        val token = "bridge-preflight-${System.nanoTime()}"
        val launcher = "$COMMANDS/$token.sh"
        val resultFile = "$COMMANDS/$token.result"
        val launcherBody = """
            #!$TERMUX_SH
            export HOME=${q(TERMUX_HOME)}
            export PREFIX=${q(TERMUX_PREFIX)}
            export PATH=${q("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")}
            export TMPDIR=${q("$TERMUX_PREFIX/tmp")}
            exec >${q(resultFile)} 2>&1
            printf 'OK|BRIDGE|ready\n'
        """.trimIndent() + "\n"

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
            logger?.w("[!] Termux RUN_COMMAND preflight launcher preparation failed")
            return null
        }

        val context = X11Application.instance
        var dispatched = false
        try {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, RUN_SERVICE)
                action = RUN_ACTION
                putExtra(RUN_PATH, launcher)
                putExtra(RUN_WORKDIR, TERMUX_HOME)
                putExtra(RUN_BACKGROUND, true)
            }
            context.startService(intent)
            dispatched = true
            logger?.i("[CTX] Termux bridge preflight dispatcher: foreground app context")
        } catch (e: Exception) {
            logger?.w("[!] Foreground RUN_COMMAND dispatch failed: ${e.javaClass.simpleName}; using root am fallback")
        }

        if (!dispatched) {
            val component = "$TERMUX_PACKAGE/$RUN_SERVICE"
            val start = """
                am startservice --user 0 -n ${q(component)} \
                    -a ${q(RUN_ACTION)} \
                    --es ${q(RUN_PATH)} ${q(launcher)} \
                    --es ${q(RUN_WORKDIR)} ${q(TERMUX_HOME)} \
                    --ez ${q(RUN_BACKGROUND)} true
            """.trimIndent()
            try { Shell.cmd(start).exec() } catch (_: Exception) { null }
            logger?.i("[CTX] Termux bridge preflight dispatcher: root am fallback")
        }

        repeat(60) {
            val r = try {
                Shell.cmd("test -s ${q(resultFile)} && cat ${q(resultFile)} || exit 1").exec()
            } catch (_: Exception) {
                null
            }
            if (r?.isSuccess == true && r.out.isNotEmpty()) {
                val result = r.out.map { it.trim() }.lastOrNull { it.startsWith("OK|") || it.startsWith("ERR|") }
                try { Shell.cmd("rm -f ${q(launcher)} ${q(resultFile)} 2>/dev/null || true").exec() } catch (_: Exception) { }
                return result
            }
            delay(100)
        }

        logger?.w("[!] Termux RUN_COMMAND bridge preflight timed out")
        try {
            val logs = Shell.cmd("logcat -d -t 120 2>/dev/null | grep -E 'RunCommandService|TermuxPluginUtils|allow-external-apps' | tail -n 12").exec()
            logs.out.filter { it.isNotBlank() }.forEach { logger?.w("[TERMUX_LOG] $it") }
        } catch (_: Exception) { }
        try { Shell.cmd("rm -f ${q(launcher)} ${q(resultFile)} 2>/dev/null || true").exec() } catch (_: Exception) { }
        return null
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
