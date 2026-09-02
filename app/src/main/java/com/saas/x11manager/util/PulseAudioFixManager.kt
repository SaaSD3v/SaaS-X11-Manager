package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class PulseAudioFixResult(
    val success: Boolean,
    val message: String,
    val details: List<String> = emptyList()
)

/**
 * Optional PulseAudio compatibility fix.
 *
 * The Manager owns the container lifecycle. This class never starts, stops or
 * restarts a DroidSpaces container. Before graphical start it only prepares the
 * Termux PulseAudio configuration, container.config and persistent client files.
 * After the normal X11/VNC path has started the container, it verifies the native
 * DroidSpaces socket and installs missing Debian/Ubuntu or Alpine client packages.
 */
object PulseAudioFixManager {
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val HOST_SOCKET = "$TERMUX_PREFIX/tmp/.pulse-socket"
    private const val HOST_SERVER = "unix:$HOST_SOCKET"
    private const val MANAGED = "SaaS X11 Manager PulseAudio Fix"
    private const val BEGIN = "# BEGIN $MANAGED"
    private const val END = "# END $MANAGED"

    private data class TermuxRuntime(val uid: Int)

    suspend fun prepareBeforeGraphicalStart(
        containerName: String,
        logger: ContainerLogger? = null
    ): PulseAudioFixResult? = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        val requested = FixSettings.isPulseAudioEnabled(context, containerName)
        val previouslyApplied = FixSettings.isPulseAudioApplied(context, containerName)

        if (!requested && !previouslyApplied) return@withContext null

        logger?.i("--- PulseAudio Fix ---")

        val info = ContainerManager.getContainerInfo(containerName)
            ?: return@withContext failure(logger, "Container $containerName was not found")

        if (!requested) {
            val restored = restoreContainerConfig(context, info)
            val cleaned = configureOfflineClientFiles(info, enabled = false)
            if (restored && cleaned) {
                FixSettings.clearPulseAudioRuntimeState(context, containerName)
                logger?.i("[+] PulseAudio fix disabled")
                return@withContext PulseAudioFixResult(true, "PulseAudio fix disabled")
            }
            return@withContext failure(logger, "PulseAudio fix cleanup was not fully completed")
        }

        logger?.i("[*] Preparing Android audio...")
        val runtime = detectTermuxRuntime()
            ?: return@withContext failure(logger, "Termux was not detected")

        if (!prepareTermuxHost(runtime)) {
            return@withContext failure(logger, "Termux PulseAudio preparation failed")
        }

        val original = FixSettings.getPulseAudioOriginalState(context, containerName)
            ?: readPulseState(info.configPath).also { state ->
                FixSettings.setPulseAudioOriginalState(context, containerName, state)
            }

        if (original !in setOf("ABSENT", "ON", "OFF")) {
            return@withContext failure(logger, "Could not read the original PulseAudio container setting")
        }

        if (!setPulseState(info.configPath, enabled = true)) {
            return@withContext failure(logger, "Could not enable DroidSpaces PulseAudio for $containerName")
        }

        if (!configureOfflineClientFiles(info, enabled = true)) {
            restoreContainerConfig(context, info)
            return@withContext failure(logger, "Could not prepare container audio client configuration")
        }

        if (!FixSettings.setPulseAudioApplied(context, containerName, true)) {
            restoreContainerConfig(context, info)
            configureOfflineClientFiles(info, enabled = false)
            return@withContext failure(logger, "Could not save PulseAudio fix state")
        }

        if (info.isRunning && original != "ON") {
            logger?.w("[!] PulseAudio will become active after the container is restarted")
        } else {
            logger?.i("[+] PulseAudio configuration prepared")
        }
        PulseAudioFixResult(true, "PulseAudio configuration prepared")
    }

    /** Called only after the normal Manager start path has made the container runnable. */
    suspend fun finalizeAfterContainerReady(
        containerName: String,
        logger: ContainerLogger? = null
    ): PulseAudioFixResult? = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext null

        val runtime = detectTermuxRuntime()
            ?: return@withContext failure(logger, "Termux was not detected")

        if (!waitForHostSocket(runtime)) {
            return@withContext failure(logger, "DroidSpaces PulseAudio socket was not created")
        }

        val sink = selectAndroidSink(runtime)
            ?: return@withContext failure(logger, "No Android PulseAudio sink became available")

        if (!waitForContainerBridge(containerName)) {
            return@withContext failure(logger, "DroidSpaces did not expose /tmp/.pulse-socket inside $containerName")
        }

        if (!installAndVerifyContainerClients(containerName, logger)) {
            return@withContext failure(logger, "Container PulseAudio client setup failed")
        }

        logger?.i("[+] PulseAudio ready ($sink)")
        PulseAudioFixResult(true, "PulseAudio ready ($sink)")
    }

    private suspend fun failure(logger: ContainerLogger?, message: String): PulseAudioFixResult {
        logger?.w("[!] $message")
        logger?.w("[!] Graphical startup will continue")
        return PulseAudioFixResult(false, message)
    }

    private fun detectTermuxRuntime(): TermuxRuntime? {
        val command =
            "test -x ${shellQuote("$TERMUX_PREFIX/bin/pkg")} && " +
                "test -x ${shellQuote("$TERMUX_PREFIX/bin/sh")} && " +
                "uid=\$(stat -c '%u' ${shellQuote(TERMUX_HOME)} 2>/dev/null || " +
                "toybox stat -c '%u' ${shellQuote(TERMUX_HOME)} 2>/dev/null); " +
                "case \"\$uid\" in ''|*[!0-9]*) exit 1 ;; esac; printf '%s\\n' \"\$uid\""
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let(::TermuxRuntime)
        } catch (_: Exception) {
            null
        }
    }

    private fun runAsTermux(runtime: TermuxRuntime, command: String): Boolean {
        val wrapped = buildString {
            append("export HOME=").append(shellQuote(TERMUX_HOME)).append("; ")
            append("export PREFIX=").append(shellQuote(TERMUX_PREFIX)).append("; ")
            append("export TMPDIR=").append(shellQuote("$TERMUX_PREFIX/tmp")).append("; ")
            append("export PATH=").append(shellQuote("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")).append("; ")
            append(command)
        }
        return try {
            Shell.cmd("su ${runtime.uid} -c ${shellQuote(wrapped)}").exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun prepareTermuxHost(runtime: TermuxRuntime): Boolean {
        val pa = "$TERMUX_PREFIX/etc/pulse/default.pa"
        val backup = "$pa.saas-x11-manager.bak"
        val script = """
            set -u
            if ! command -v pulseaudio >/dev/null 2>&1 || ! command -v pactl >/dev/null 2>&1; then
                pkg install -y pulseaudio >/dev/null 2>&1 || exit 20
            fi
            if command -v dpkg >/dev/null 2>&1 && ! dpkg -s libandroid-stub >/dev/null 2>&1; then
                pkg install -y libandroid-stub >/dev/null 2>&1 || true
            fi
            pa=${shellQuote(pa)}
            backup=${shellQuote(backup)}
            [ -f "${'$'}pa" ] || exit 21
            [ -f "${'$'}backup" ] || cp -p "${'$'}pa" "${'$'}backup" || exit 22
            tmp="${'$'}pa.saas-x11.${'$'}${'$'}"
            : > "${'$'}tmp" || exit 23
            have_aaudio=0
            while IFS= read -r line || [ -n "${'$'}line" ]; do
                trimmed=${'$'}(printf '%s\n' "${'$'}line" | sed 's/^[[:space:]]*//')
                case "${'$'}trimmed" in
                    load-module\ module-aaudio-sink|load-module\ module-aaudio-sink\ *)
                        have_aaudio=1
                        printf '%s\n' "${'$'}line" >> "${'$'}tmp"
                        ;;
                    load-module\ module-sles-sink|load-module\ module-sles-sink\ *|load-module\ module-console-kit|load-module\ module-console-kit\ *|load-module\ module-suspend-on-idle|load-module\ module-suspend-on-idle\ *)
                        printf '# %s disabled: %s\n' ${shellQuote(MANAGED)} "${'$'}line" >> "${'$'}tmp"
                        ;;
                    *) printf '%s\n' "${'$'}line" >> "${'$'}tmp" ;;
                esac
            done < "${'$'}pa"
            if [ "${'$'}have_aaudio" -eq 0 ]; then
                printf '\n%s\n%s\n%s\n' ${shellQuote(BEGIN)} 'load-module module-aaudio-sink' ${shellQuote(END)} >> "${'$'}tmp"
            fi
            chmod ${'$'}(stat -c '%a' "${'$'}pa" 2>/dev/null || printf '644') "${'$'}tmp" 2>/dev/null || true
            mv "${'$'}tmp" "${'$'}pa" || exit 24
            exit 0
        """.trimIndent()
        return runAsTermux(runtime, script)
    }

    private fun readPulseState(configPath: String): String {
        val cfg = shellQuote(configPath)
        val command =
            "v=\$(sed -n 's/^enable_pulseaudio=//p' $cfg 2>/dev/null | tail -n 1); " +
                "if [ -z \"\$v\" ]; then printf ABSENT; " +
                "else case \"\$v\" in 1|true|yes|on) printf ON ;; *) printf OFF ;; esac; fi"
        return try {
            val result = Shell.cmd(command).exec()
            if (result.isSuccess) result.out.joinToString("").trim() else "UNKNOWN"
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    private fun setPulseState(configPath: String, enabled: Boolean): Boolean {
        val cfg = shellQuote(configPath)
        val value = if (enabled) "1" else "0"
        val command = """
            cfg=$cfg
            [ -f "${'$'}cfg" ] || exit 30
            tmp="${'$'}cfg.saas-x11.${'$'}${'$'}"
            : > "${'$'}tmp" || exit 31
            found=0
            while IFS= read -r line || [ -n "${'$'}line" ]; do
                case "${'$'}line" in
                    enable_pulseaudio=*)
                        if [ "${'$'}found" -eq 0 ]; then
                            printf 'enable_pulseaudio=%s\n' '$value' >> "${'$'}tmp" || exit 32
                            found=1
                        fi
                        ;;
                    *) printf '%s\n' "${'$'}line" >> "${'$'}tmp" || exit 32 ;;
                esac
            done < "${'$'}cfg"
            [ "${'$'}found" -eq 1 ] || printf 'enable_pulseaudio=%s\n' '$value' >> "${'$'}tmp"
            chmod ${'$'}(stat -c '%a' "${'$'}cfg" 2>/dev/null || printf '600') "${'$'}tmp" 2>/dev/null || true
            mv "${'$'}tmp" "${'$'}cfg"
        """.trimIndent()
        return try {
            Shell.cmd(command).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun removePulseState(configPath: String): Boolean {
        val cfg = shellQuote(configPath)
        val command = """
            cfg=$cfg
            [ -f "${'$'}cfg" ] || exit 30
            tmp="${'$'}cfg.saas-x11.${'$'}${'$'}"
            : > "${'$'}tmp" || exit 31
            while IFS= read -r line || [ -n "${'$'}line" ]; do
                case "${'$'}line" in enable_pulseaudio=*) : ;; *) printf '%s\n' "${'$'}line" >> "${'$'}tmp" || exit 32 ;; esac
            done < "${'$'}cfg"
            chmod ${'$'}(stat -c '%a' "${'$'}cfg" 2>/dev/null || printf '600') "${'$'}tmp" 2>/dev/null || true
            mv "${'$'}tmp" "${'$'}cfg"
        """.trimIndent()
        return try {
            Shell.cmd(command).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun restoreContainerConfig(context: android.content.Context, info: ContainerInfo): Boolean {
        return when (FixSettings.getPulseAudioOriginalState(context, info.name)) {
            "ABSENT" -> removePulseState(info.configPath)
            "ON" -> setPulseState(info.configPath, true)
            "OFF" -> setPulseState(info.configPath, false)
            null -> true
            else -> false
        }
    }

    private fun configureOfflineClientFiles(info: ContainerInfo, enabled: Boolean): Boolean {
        return RootfsAccessor.use(info.rootfsPath, "audio_${info.name}") { root ->
            if (enabled) installOfflineClientFiles(root, info.initSystem)
            else removeOfflineClientFiles(root)
        } ?: false
    }

    private fun installOfflineClientFiles(root: String, initSystem: InitSystem): Boolean {
        val qRoot = shellQuote(root)
        val profile = "$root/etc/profile.d/saas-x11-audio.sh"
        val client = "$root/root/.config/pulse/client.conf"
        val asound = "$root/etc/asound.conf"
        val dropIn = "$root/etc/systemd/system/x11-session.service.d/90-saas-audio.conf"
        val openRc = "$root/etc/conf.d/x11-session"
        val clientText = "$BEGIN\ndefault-server = unix:/tmp/.pulse-socket\nautospawn = no\n$END\n"
        val asoundText = "$BEGIN\npcm.!default { type pulse }\nctl.!default { type pulse }\n$END\n"
        val profileText = "$BEGIN\nexport PULSE_SERVER=unix:/tmp/.pulse-socket\n$END\n"
        val dropInText = "$BEGIN\n[Service]\nEnvironment=PULSE_SERVER=unix:/tmp/.pulse-socket\n$END\n"

        val openRcCommand = if (initSystem == InitSystem.OPENRC) {
            "mkdir -p ${shellQuote("$root/etc/conf.d")}; " +
                "f=${shellQuote(openRc)}; touch \"\$f\"; " +
                "sed '/^# BEGIN $MANAGED\$/,/^# END $MANAGED\$/d' \"\$f\" > \"\$f.tmp\" && mv \"\$f.tmp\" \"\$f\"; " +
                "printf '%s\\n' ${shellQuote(profileText.trimEnd())} >> \"\$f\";"
        } else ""

        val systemdCommand = if (initSystem == InitSystem.SYSTEMD) {
            "mkdir -p ${shellQuote("$root/etc/systemd/system/x11-session.service.d")} && " +
                "printf '%s' ${shellQuote(dropInText)} > ${shellQuote(dropIn)} && "
        } else "rm -f ${shellQuote(dropIn)} 2>/dev/null; "

        val command =
            "test -d $qRoot && " +
                "mkdir -p ${shellQuote("$root/root/.config/pulse")} ${shellQuote("$root/etc/profile.d")} && " +
                backupCommand(client) + backupCommand(asound) +
                "printf '%s' ${shellQuote(clientText)} > ${shellQuote(client)} && " +
                "printf '%s' ${shellQuote(asoundText)} > ${shellQuote(asound)} && " +
                "printf '%s' ${shellQuote(profileText)} > ${shellQuote(profile)} && " +
                systemdCommand + openRcCommand + "true"
        return try {
            Shell.cmd(command).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun backupCommand(path: String): String {
        val q = shellQuote(path)
        val b = shellQuote("$path.saas-x11-manager.bak")
        return "if [ -f $q ] && ! grep -Fq ${shellQuote(MANAGED)} $q 2>/dev/null && [ ! -f $b ]; then cp -p $q $b || exit 40; fi; "
    }

    private fun removeOfflineClientFiles(root: String): Boolean {
        val client = "$root/root/.config/pulse/client.conf"
        val asound = "$root/etc/asound.conf"
        val profile = "$root/etc/profile.d/saas-x11-audio.sh"
        val dropIn = "$root/etc/systemd/system/x11-session.service.d/90-saas-audio.conf"
        val openRc = "$root/etc/conf.d/x11-session"
        val command =
            restoreManagedFileCommand(client) +
                restoreManagedFileCommand(asound) +
                "rm -f ${shellQuote(profile)} ${shellQuote(dropIn)} 2>/dev/null; " +
                "if [ -f ${shellQuote(openRc)} ]; then " +
                "sed '/^# BEGIN $MANAGED\$/,/^# END $MANAGED\$/d' ${shellQuote(openRc)} > ${shellQuote("$openRc.tmp")} && " +
                "mv ${shellQuote("$openRc.tmp")} ${shellQuote(openRc)}; fi; true"
        return try {
            Shell.cmd(command).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun restoreManagedFileCommand(path: String): String {
        val q = shellQuote(path)
        val b = shellQuote("$path.saas-x11-manager.bak")
        return "if [ -f $q ] && grep -Fq ${shellQuote(MANAGED)} $q 2>/dev/null; then if [ -f $b ]; then mv $b $q; else rm -f $q; fi; fi; "
    }

    private suspend fun waitForHostSocket(runtime: TermuxRuntime): Boolean {
        repeat(40) {
            val ok = runAsTermux(
                runtime,
                "PULSE_SERVER=${shellQuote(HOST_SERVER)} pactl info >/dev/null 2>&1"
            )
            if (ok) return true
            delay(250)
        }
        return false
    }

    private fun selectAndroidSink(runtime: TermuxRuntime): String? {
        val command =
            "sinks=\$(PULSE_SERVER=${shellQuote(HOST_SERVER)} pactl list short sinks 2>/dev/null) || exit 1; " +
                "if printf '%s\\n' \"\$sinks\" | grep -q '[[:space:]]AAudio_sink[[:space:]]'; then sink=AAudio_sink; " +
                "elif printf '%s\\n' \"\$sinks\" | grep -q '[[:space:]]OpenSL_ES_sink[[:space:]]'; then sink=OpenSL_ES_sink; " +
                "else exit 2; fi; " +
                "PULSE_SERVER=${shellQuote(HOST_SERVER)} pactl set-default-sink \"\$sink\" >/dev/null 2>&1 || exit 3; printf '%s\\n' \"\$sink\""
        return try {
            val wrapped = buildString {
                append("export HOME=").append(shellQuote(TERMUX_HOME)).append("; ")
                append("export PREFIX=").append(shellQuote(TERMUX_PREFIX)).append("; ")
                append("export PATH=").append(shellQuote("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")).append("; ")
                append(command)
            }
            val result = Shell.cmd("su ${runtime.uid} -c ${shellQuote(wrapped)}").exec()
            if (result.isSuccess) result.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun waitForContainerBridge(containerName: String): Boolean {
        repeat(30) {
            val command =
                "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run sh -c " +
                    shellQuote(
                        "test -S /tmp/.pulse-socket && " +
                            "{ [ ! -f /run/droidspaces.env ] || grep -Fq 'PULSE_SERVER=unix:/tmp/.pulse-socket' /run/droidspaces.env; }"
                    ) + " 2>/dev/null"
            val ok = try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
            if (ok) return true
            delay(200)
        }
        return false
    }

    private suspend fun installAndVerifyContainerClients(
        containerName: String,
        logger: ContainerLogger?
    ): Boolean {
        val payload = """
            set -u
            need=0
            command -v pactl >/dev/null 2>&1 || need=1
            command -v speaker-test >/dev/null 2>&1 || need=1
            if [ "${'$'}need" -eq 1 ]; then
                if command -v apt-get >/dev/null 2>&1; then
                    printf '%s\n' __SAAS_AUDIO_APT__
                    DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1 || exit 50
                    DEBIAN_FRONTEND=noninteractive apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils >/dev/null 2>&1 || exit 51
                elif command -v apk >/dev/null 2>&1; then
                    printf '%s\n' __SAAS_AUDIO_APK__
                    apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse >/dev/null 2>&1 || exit 52
                else
                    exit 53
                fi
            fi
            PULSE_SERVER=unix:/tmp/.pulse-socket pactl info >/dev/null 2>&1 || exit 54
            printf '%s\n' __SAAS_AUDIO_READY__
        """.trimIndent()
        val command =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run sh -c ${shellQuote(payload)}"
        return try {
            val result = Shell.cmd(command).exec()
            result.out.forEach { line ->
                when (line.trim()) {
                    "__SAAS_AUDIO_APT__" -> logger?.i("[*] Installing missing Debian/Ubuntu audio clients...")
                    "__SAAS_AUDIO_APK__" -> logger?.i("[*] Installing missing Alpine audio clients...")
                }
            }
            result.isSuccess && result.out.any { it.trim() == "__SAAS_AUDIO_READY__" }
        } catch (_: Exception) {
            false
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
