package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.saas.x11manager.audio.NativeAudioRuntime
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PulseAudioFixResult(
    val success: Boolean,
    val message: String,
    val details: List<String> = emptyList()
)

/**
 * Native-only Android audio integration for the experimental branch.
 *
 * The host PulseAudio core is packaged inside the SaaS X11 Manager APK and runs
 * as the Manager app UID. There is deliberately no Termux runtime provider,
 * package installation, foreign UID execution or OEM-specific preload path.
 *
 * Container semantics remain the validated X11APP contract:
 * - DroidSpaces native PulseAudio is disabled while this integration is enabled;
 * - HOST/NAT clients use an authenticated 256-byte PulseAudio cookie;
 * - the graphical/container lifecycle remains owned by its normal managers;
 * - audio failure never restarts or kills the Linux container or X11/VNC.
 */
object PulseAudioFixManager {
    private const val MANAGED = "SaaS X11 Manager Audio Configuration"
    private const val LEGACY_MANAGED = "SaaS X11 Manager PulseAudio Fix"
    private const val SCRIPT_MANAGED = "SaaS DroidSpaces Audio HostNAT"
    private const val NETLAB_MANAGED = "SaaS DroidSpaces Audio NetLab"
    private const val SCRIPT_LEGACY_MANAGED = "SaaS DroidSpaces Audio Auto"

    suspend fun prepareBeforeGraphicalStart(
        containerName: String,
        logger: ContainerLogger? = null
    ): PulseAudioFixResult? = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        val requested = FixSettings.isPulseAudioEnabled(context, containerName)
        val previouslyApplied = FixSettings.isPulseAudioApplied(context, containerName)
        if (!requested && !previouslyApplied) return@withContext null

        logger?.i("--- Native Audio Configuration ---")
        logger?.i("[AUDIO][NATIVE] Runtime owner: SaaS X11 Manager")
        logger?.i("[AUDIO][NATIVE] External Termux dependency: none")

        val info = ContainerManager.getContainerInfo(containerName)
            ?: return@withContext failure(logger, "Container $containerName was not found")

        if (!requested) {
            val restored = restoreContainerConfig(context, info)
            val cleaned = removeOfflineClientFiles(info)
            NativeAudioRuntime.release(containerName, logger)
            if (restored && cleaned) {
                FixSettings.clearPulseAudioRuntimeState(context, containerName)
                logger?.i("[AUDIO][NATIVE] ✓ Audio configuration disabled and previous container state restored")
                return@withContext PulseAudioFixResult(true, "Native audio configuration disabled")
            }
            return@withContext failure(logger, "Audio cleanup was not fully completed")
        }

        val mode = info.netMode.trim().lowercase()
        if (mode !in setOf("host", "nat")) {
            return@withContext failure(
                logger,
                "Native audio supports net_mode=host and net_mode=nat only (current: ${info.netMode})"
            )
        }

        val original = FixSettings.getPulseAudioOriginalState(context, containerName)
            ?: readPulseState(info.configPath)
        if (original !in setOf("ABSENT", "ON", "OFF")) {
            return@withContext failure(logger, "Could not read the original DroidSpaces PulseAudio setting")
        }
        if (FixSettings.getPulseAudioOriginalState(context, containerName) == null &&
            !FixSettings.setPulseAudioOriginalState(context, containerName, original)
        ) {
            return@withContext failure(logger, "Could not save the original DroidSpaces PulseAudio setting")
        }

        // The embedded runtime is the only audio host in this branch. Prevent a
        // future DroidSpaces start from launching its separate native bridge.
        if (!setPulseState(info.configPath, enabled = false)) {
            return@withContext failure(logger, "Could not disable DroidSpaces native PulseAudio for future starts")
        }

        if (!FixSettings.setPulseAudioApplied(context, containerName, true)) {
            restoreContainerConfig(context, info)
            return@withContext failure(logger, "Could not save native audio configuration state")
        }

        if (!NativeAudioRuntime.ensureCore(containerName, logger)) {
            restoreContainerConfig(context, info)
            FixSettings.clearPulseAudioRuntimeState(context, containerName)
            return@withContext failure(logger, "Embedded Android audio core could not be started")
        }

        logger?.i("[AUDIO][NATIVE] ✓ Embedded host core prepared")
        PulseAudioFixResult(true, "Embedded Android audio core ready")
    }

    /** Called after the normal X11/VNC path has made the container runnable. */
    suspend fun finalizeAfterContainerReady(
        containerName: String,
        logger: ContainerLogger? = null
    ): PulseAudioFixResult? = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext null

        val info = ContainerManager.getContainerInfo(containerName)
            ?: return@withContext failure(logger, "Container $containerName was not found")
        if (!info.isRunning) {
            return@withContext failure(logger, "Container $containerName is not running; audio client was not changed")
        }

        val ready = NativeAudioRuntime.configureContainer(containerName, logger)
        if (!ready) return@withContext PulseAudioFixResult(false, "Native audio transport was not confirmed")

        FixSettings.setPulseAudioApplied(context, containerName, true)
        PulseAudioFixResult(true, "Native audio ready")
    }

    private suspend fun failure(logger: ContainerLogger?, message: String): PulseAudioFixResult {
        logger?.w("[AUDIO][NATIVE] ✗ $message")
        logger?.w("[AUDIO] Graphical startup will continue")
        return PulseAudioFixResult(false, message)
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
            [ -f "${'$'}cfg" ] || exit 50
            tmp="${'$'}cfg.saas-x11-audio.${'$'}${'$'}"
            : > "${'$'}tmp" || exit 51
            found=0
            while IFS= read -r line || [ -n "${'$'}line" ]; do
                case "${'$'}line" in
                    enable_pulseaudio=*)
                        if [ "${'$'}found" -eq 0 ]; then
                            printf 'enable_pulseaudio=%s\n' '$value' >> "${'$'}tmp" || exit 52
                            found=1
                        fi ;;
                    *) printf '%s\n' "${'$'}line" >> "${'$'}tmp" || exit 52 ;;
                esac
            done < "${'$'}cfg"
            [ "${'$'}found" -eq 1 ] || printf 'enable_pulseaudio=%s\n' '$value' >> "${'$'}tmp"
            chmod ${'$'}(stat -c '%a' "${'$'}cfg" 2>/dev/null || printf '600') "${'$'}tmp" 2>/dev/null || true
            mv "${'$'}tmp" "${'$'}cfg"
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun removePulseState(configPath: String): Boolean {
        val cfg = shellQuote(configPath)
        val command = """
            cfg=$cfg
            [ -f "${'$'}cfg" ] || exit 50
            tmp="${'$'}cfg.saas-x11-audio.${'$'}${'$'}"
            : > "${'$'}tmp" || exit 51
            while IFS= read -r line || [ -n "${'$'}line" ]; do
                case "${'$'}line" in enable_pulseaudio=*) : ;; *) printf '%s\n' "${'$'}line" >> "${'$'}tmp" || exit 52 ;; esac
            done < "${'$'}cfg"
            chmod ${'$'}(stat -c '%a' "${'$'}cfg" 2>/dev/null || printf '600') "${'$'}tmp" 2>/dev/null || true
            mv "${'$'}tmp" "${'$'}cfg"
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun restoreContainerConfig(context: android.content.Context, info: ContainerInfo): Boolean =
        when (FixSettings.getPulseAudioOriginalState(context, info.name)) {
            "ABSENT" -> removePulseState(info.configPath)
            "ON" -> setPulseState(info.configPath, true)
            "OFF" -> setPulseState(info.configPath, false)
            null -> true
            else -> false
        }

    private fun removeOfflineClientFiles(info: ContainerInfo): Boolean =
        RootfsAccessor.use(info.rootfsPath, "audio_cleanup_${info.name}") { root ->
            val client = "$root/root/.config/pulse/client.conf"
            val cookie = "$root/root/.config/pulse/saas-audio.cookie"
            val asound = "$root/etc/asound.conf"
            val profile = "$root/etc/profile.d/saas-x11-audio.sh"
            val scriptProfile = "$root/etc/profile.d/saas-droidspaces-audio.sh"
            val oldProfile = "$root/etc/profile.d/android-audio.sh"
            val dropIn = "$root/etc/systemd/system/x11-session.service.d/90-saas-audio.conf"
            val oldDropIn = "$root/etc/systemd/system/x11-session.service.d/audio.conf"
            val openRc = "$root/etc/conf.d/x11-session"

            val command = buildString {
                append(PulseAudioClientConfig.cleanup(root))
                append(restoreManagedFileCommand(client, listOf(
                    "$client.saas-x11-manager.bak", "$client.saas-hostnat.bak",
                    "$client.saas-netlab.bak", "$client.saas-audio.bak"
                )))
                append(restoreManagedFileCommand(asound, listOf(
                    "$asound.saas-x11-manager.bak", "$asound.saas-hostnat.bak",
                    "$asound.saas-netlab.bak", "$asound.saas-audio.bak"
                )))
                append(removeIfManagedCommand(profile))
                append(removeIfManagedCommand(scriptProfile))
                append(removeIfManagedCommand(oldProfile))
                append(removeIfManagedCommand(dropIn))
                append(removeIfManagedCommand(oldDropIn))
                append("rm -f ${shellQuote(cookie)} 2>/dev/null || true; ")
                append("if [ -f ${shellQuote(openRc)} ]; then ")
                append("sed '/^# BEGIN $LEGACY_MANAGED${'$'}/,/^# END $LEGACY_MANAGED${'$'}/d; ")
                append("/^# BEGIN $MANAGED${'$'}/,/^# END $MANAGED${'$'}/d; ")
                append("/^# BEGIN $SCRIPT_LEGACY_MANAGED${'$'}/,/^# END $SCRIPT_LEGACY_MANAGED${'$'}/d; ")
                append("/^# BEGIN $NETLAB_MANAGED${'$'}/,/^# END $NETLAB_MANAGED${'$'}/d' ${shellQuote(openRc)} > ${shellQuote("$openRc.tmp")} && ")
                append("mv ${shellQuote("$openRc.tmp")} ${shellQuote(openRc)}; fi; true")
            }
            try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
        } ?: false

    private fun restoreManagedFileCommand(path: String, backups: List<String>): String {
        val quoted = shellQuote(path)
        val backupLoop = backups.joinToString(" ") { shellQuote(it) }
        return "if [ -f $quoted ] && ${managedCheck(quoted)}; then restored=0; for b in $backupLoop; do if [ -f \"\$b\" ]; then mv \"\$b\" $quoted; restored=1; break; fi; done; [ \"\$restored\" -eq 1 ] || rm -f $quoted; fi; "
    }

    private fun removeIfManagedCommand(path: String): String {
        val quoted = shellQuote(path)
        return "if [ -f $quoted ] && ${managedCheck(quoted)}; then rm -f $quoted; fi; "
    }

    private fun managedCheck(quotedPath: String): String =
        "{ grep -Fq ${shellQuote(MANAGED)} $quotedPath 2>/dev/null || " +
            "grep -Fq ${shellQuote(LEGACY_MANAGED)} $quotedPath 2>/dev/null || " +
            "grep -Fq ${shellQuote(SCRIPT_MANAGED)} $quotedPath 2>/dev/null || " +
            "grep -Fq ${shellQuote(NETLAB_MANAGED)} $quotedPath 2>/dev/null || " +
            "grep -Fq ${shellQuote(SCRIPT_LEGACY_MANAGED)} $quotedPath 2>/dev/null; }"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
