package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Narrow NAT listener preflight used while the APK NAT path is being physically
 * validated. It mirrors the already validated v3.2 Termux listener environment
 * exactly, but never owns the PulseAudio daemon or any container/X11 lifecycle.
 *
 * A successfully verified base listener is intentionally left loaded so the
 * normal PulseAudioFixManager finalizer can reuse it and continue with the
 * container-side persistent client configuration. A listener that fails its
 * authenticated endpoint probe is unloaded directly by the module id returned
 * by pactl; cleanup never depends on `pactl list modules`.
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
    private const val MANAGED = "SaaS X11 Manager Audio Configuration"

    private data class CommandResult(
        val exitCode: Int,
        val stdout: List<String>,
        val stderr: List<String>
    )

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
            "PULSE_SERVER=${q("tcp:$NAT_GATEWAY:$BASE_PORT")} " +
                "PULSE_COOKIE=${q(COOKIE)} " +
                "PULSE_CLIENTCONFIG=${q(CLIENT_CONFIG)} pactl info"
        )

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

    private fun hasAndroidSink(lines: List<String>): Boolean =
        lines.any { it.trim() == "Default Sink: AAudio_sink" || it.trim() == "Default Sink: OpenSL_ES_sink" }

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
