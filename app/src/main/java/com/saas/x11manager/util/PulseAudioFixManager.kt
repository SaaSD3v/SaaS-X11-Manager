package com.saas.x11manager.util

import android.content.Context
import android.util.Base64
import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Result returned after applying/removing the PulseAudio fix at graphical start. */
data class PulseAudioFixResult(
    val success: Boolean,
    val message: String,
    val details: List<String> = emptyList()
)

/**
 * Android wrapper around the audited SaaS DroidSpaces Audio Auto helper.
 *
 * The Fixes screen only stores the user's desired state. This manager does not
 * run from the settings switch. SessionAccessManager calls reconcileForStart()
 * only after the user presses Start X11, Start VNC or Start Both.
 */
object PulseAudioFixManager {
    private const val ASSET_DIR = "saas-audio"
    private const val SCRIPT_VERSION = "3.0.2"
    private const val SCRIPT_SHA256 = "55cd6d74323a76fe44a07ac5c8777ff843ab13427b57cec686a3a9262c919278"
    private const val SCRIPT_SIZE = 69_523
    private const val SCRIPT_BASE64_SIZE = 92_700
    private const val SCRIPT_PARTS = 5

    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val MANAGED_DIR = "$TERMUX_HOME/.local/share/saas-x11-manager"
    private const val MANAGED_SCRIPT = "$MANAGED_DIR/SaaS-DroidSpaces-Audio-Auto.sh"

    /**
     * Reconciles the stored switch state at the moment a graphical start was
     * explicitly requested.
     *
     * Enabled: stage/verify the helper, install only missing requirements and
     * apply/verify the bridge before the graphical session starts.
     *
     * Disabled after a previous successful apply: remove only the integration
     * owned by this fix before the graphical session starts.
     *
     * Audio failures are returned to the caller but remain non-fatal to X11/VNC.
     */
    suspend fun reconcileForStart(
        containerName: String,
        logger: ContainerLogger? = null
    ): PulseAudioFixResult? = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        val requested = FixSettings.isPulseAudioEnabled(context, containerName)
        val previouslyApplied = FixSettings.isPulseAudioApplied(context, containerName)

        if (!requested && !previouslyApplied) {
            return@withContext null
        }

        logger?.i("--- Optional Fixes ---")

        if (!requested) {
            logger?.i("[FIX] PulseAudio fix is disabled; removing the previously applied integration before graphical start...")
            val staged = stageHelper(context)
            if (!staged.success) {
                logger?.w("[FIX] ${staged.message}")
                return@withContext staged
            }

            val removed = runHelper(
                containerName = containerName,
                arguments = listOf("--container", containerName, "--uninstall"),
                quiet = false,
                logger = logger
            )
            if (!removed.success) {
                logger?.w("[FIX] PulseAudio cleanup failed; graphical startup will continue")
                return@withContext removed
            }

            if (!FixSettings.setPulseAudioApplied(context, containerName, false)) {
                logger?.w("[FIX] Cleanup succeeded, but applied-state persistence failed; cleanup will be retried on the next start")
                return@withContext PulseAudioFixResult(
                    success = false,
                    message = "PulseAudio integration was removed, but its applied-state marker could not be saved.",
                    details = removed.details
                )
            }

            logger?.i("[FIX] PulseAudio fix removed")
            return@withContext PulseAudioFixResult(
                success = true,
                message = "PulseAudio fix removed before graphical start.",
                details = removed.details
            )
        }

        logger?.i("[FIX] PulseAudio fix selected; applying it now because graphical start was requested...")
        val staged = stageHelper(context)
        if (!staged.success) {
            staged.details.forEach { logger?.e("[FIX] $it") }
            logger?.w("[FIX] PulseAudio preparation failed; graphical startup will continue")
            return@withContext staged
        }

        val applied = runHelper(
            containerName = containerName,
            arguments = listOf("--container", containerName, "--mode", "auto", "--restore-state"),
            quiet = false,
            logger = logger
        )
        if (!applied.success) {
            logger?.w("[FIX] PulseAudio fix failed; graphical startup will continue")
            logger?.w("[FIX] If Magisk requests Termux root access, grant it and press Start again")
            return@withContext applied
        }

        if (!FixSettings.setPulseAudioApplied(context, containerName, true)) {
            if (!previouslyApplied) {
                logger?.w("[FIX] Applied-state could not be saved; rolling back the newly applied integration")
                runHelper(
                    containerName = containerName,
                    arguments = listOf("--container", containerName, "--uninstall"),
                    quiet = true,
                    logger = logger
                )
            }
            return@withContext PulseAudioFixResult(
                success = false,
                message = "PulseAudio setup completed, but the Manager could not persist its applied-state marker.",
                details = applied.details
            )
        }

        logger?.i("[FIX] PulseAudio bridge ready")
        PulseAudioFixResult(
            success = true,
            message = "PulseAudio fix applied and verified for this graphical start.",
            details = applied.details
        )
    }

    private data class TermuxRuntime(val uid: Int)

    /**
     * Reconstructs the audited helper from small APK assets, validates its exact
     * decoded size and SHA-256, then copies it into Termux with Termux ownership.
     * Any missing, extra, truncated or reordered payload part fails closed.
     */
    private fun stageHelper(context: Context): PulseAudioFixResult {
        val runtime = detectTermuxRuntime()
            ?: return PulseAudioFixResult(
                success = false,
                message = "Termux was not detected.",
                details = listOf(
                    "Termux must be installed at /data/data/com.termux before the PulseAudio fix can be applied."
                )
            )

        val expectedParts = (0 until SCRIPT_PARTS).map { index ->
            "part${index.toString().padStart(2, '0')}.b64"
        }
        val availableParts = try {
            context.assets.list(ASSET_DIR)
                ?.filter { it.matches(Regex("part\\d{2}\\.b64")) }
                ?.sorted()
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (availableParts != expectedParts) {
            return PulseAudioFixResult(
                success = false,
                message = "Bundled PulseAudio helper payload is incomplete.",
                details = listOf(
                    "Expected: ${expectedParts.joinToString()}",
                    "Found: ${availableParts.joinToString().ifEmpty { "none" }}"
                )
            )
        }

        val encoded = StringBuilder(SCRIPT_BASE64_SIZE)
        try {
            expectedParts.forEach { part ->
                context.assets.open("$ASSET_DIR/$part").bufferedReader(Charsets.US_ASCII).useLines { lines ->
                    lines.forEach { line ->
                        line.forEach { char ->
                            if (!char.isWhitespace()) encoded.append(char)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return PulseAudioFixResult(
                success = false,
                message = "Bundled PulseAudio helper could not be reconstructed.",
                details = listOf(e.message ?: e.javaClass.simpleName)
            )
        }

        if (encoded.length != SCRIPT_BASE64_SIZE) {
            return PulseAudioFixResult(
                success = false,
                message = "Bundled PulseAudio helper payload has an unexpected size.",
                details = listOf("Expected base64 bytes: $SCRIPT_BASE64_SIZE", "Found: ${encoded.length}")
            )
        }

        val decoded = try {
            Base64.decode(encoded.toString(), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return PulseAudioFixResult(
                success = false,
                message = "Bundled PulseAudio helper payload is not valid base64.",
                details = listOf(e.message ?: e.javaClass.simpleName)
            )
        }
        if (decoded.size != SCRIPT_SIZE) {
            return PulseAudioFixResult(
                success = false,
                message = "Bundled PulseAudio helper decoded to an unexpected size.",
                details = listOf("Expected: $SCRIPT_SIZE bytes", "Found: ${decoded.size} bytes")
            )
        }

        val cacheScript = File(context.cacheDir, "saas-droidspaces-audio-auto-$SCRIPT_VERSION.sh")
        try {
            cacheScript.outputStream().use { it.write(decoded) }
        } catch (e: Exception) {
            return PulseAudioFixResult(
                success = false,
                message = "Bundled PulseAudio helper could not be written to the app cache.",
                details = listOf(e.message ?: e.javaClass.simpleName)
            )
        }

        val digest = sha256(cacheScript)
        if (!digest.equals(SCRIPT_SHA256, ignoreCase = true)) {
            cacheScript.delete()
            return PulseAudioFixResult(
                success = false,
                message = "Bundled PulseAudio helper failed its integrity check.",
                details = listOf("Expected $SCRIPT_SHA256", "Found ${digest.ifEmpty { "unavailable" }}")
            )
        }

        val installCommand =
            "mkdir -p ${shellQuote(MANAGED_DIR)} && " +
                "cp ${shellQuote(cacheScript.absolutePath)} ${shellQuote(MANAGED_SCRIPT)} && " +
                "chown ${runtime.uid}:${runtime.uid} ${shellQuote(MANAGED_DIR)} ${shellQuote(MANAGED_SCRIPT)} && " +
                "chmod 700 ${shellQuote(MANAGED_DIR)} ${shellQuote(MANAGED_SCRIPT)} && " +
                "test -x ${shellQuote(MANAGED_SCRIPT)}"
        val install = try {
            Shell.cmd(installCommand).exec()
        } catch (e: Exception) {
            cacheScript.delete()
            return PulseAudioFixResult(
                success = false,
                message = "Could not stage the PulseAudio helper in Termux.",
                details = listOf(e.message ?: e.javaClass.simpleName)
            )
        }
        cacheScript.delete()

        if (!install.isSuccess) {
            return PulseAudioFixResult(
                success = false,
                message = "Could not stage the PulseAudio helper in Termux.",
                details = (install.out + install.err).filter { it.isNotBlank() }.takeLast(12)
            )
        }

        return PulseAudioFixResult(
            success = true,
            message = "PulseAudio helper staged and SHA-256 verified."
        )
    }

    private suspend fun runHelper(
        containerName: String,
        arguments: List<String>,
        quiet: Boolean,
        logger: ContainerLogger?
    ): PulseAudioFixResult {
        val runtime = detectTermuxRuntime()
            ?: return PulseAudioFixResult(false, "Termux disappeared while applying the PulseAudio fix.")

        val args = buildList {
            addAll(arguments)
            if (quiet) add("--quiet")
        }.joinToString(" ") { shellQuote(it) }

        val termuxCommand = buildString {
            append("export HOME=").append(shellQuote(TERMUX_HOME)).append("; ")
            append("export PREFIX=").append(shellQuote(TERMUX_PREFIX)).append("; ")
            append("export TMPDIR=").append(shellQuote("$TERMUX_PREFIX/tmp")).append("; ")
            append("export PATH=").append(shellQuote("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")).append("; ")
            append("exec ").append(shellQuote("$TERMUX_PREFIX/bin/sh")).append(' ')
            append(shellQuote(MANAGED_SCRIPT)).append(' ').append(args)
        }
        val rootCommand = "su ${runtime.uid} -c ${shellQuote(termuxCommand)}"

        val result = try {
            Shell.cmd(rootCommand).exec()
        } catch (e: Exception) {
            return PulseAudioFixResult(
                success = false,
                message = "PulseAudio helper could not be started.",
                details = listOf(e.message ?: e.javaClass.simpleName)
            )
        }

        val lines = (result.out + result.err).filter { it.isNotBlank() }
        if (logger != null) {
            lines.forEach { line ->
                when {
                    line.startsWith("[-]") -> logger.e("[AUDIO] $line")
                    line.startsWith("[!]") -> logger.w("[AUDIO] $line")
                    !quiet -> logger.i("[AUDIO] $line")
                }
            }
        }

        if (!result.isSuccess) {
            val meaningful = lines.takeLast(16)
            val rootHint = if (meaningful.any {
                    it.contains("Root access", ignoreCase = true) ||
                        it.contains("su", ignoreCase = true)
                }
            ) {
                " Grant Termux root permission in Magisk, then press Start again. PulseAudio itself still runs as the normal Termux user."
            } else {
                ""
            }
            return PulseAudioFixResult(
                success = false,
                message = "PulseAudio fix failed for $containerName.$rootHint",
                details = meaningful
            )
        }

        return PulseAudioFixResult(
            success = true,
            message = "PulseAudio helper completed successfully for $containerName.",
            details = lines.takeLast(16)
        )
    }

    private fun detectTermuxRuntime(): TermuxRuntime? {
        val command =
            "test -x ${shellQuote("$TERMUX_PREFIX/bin/pkg")} && " +
                "test -x ${shellQuote("$TERMUX_PREFIX/bin/sh")} && " +
                "uid=\$(stat -c '%u' ${shellQuote(TERMUX_HOME)} 2>/dev/null || toybox stat -c '%u' ${shellQuote(TERMUX_HOME)} 2>/dev/null); " +
                "case \"\$uid\" in ''|*[!0-9]*) exit 1 ;; esac; printf '%s\\n' \"\$uid\""
        val result = try {
            Shell.cmd(command).exec()
        } catch (_: Exception) {
            return null
        }
        if (!result.isSuccess) return null
        val uid = result.out.firstOrNull()?.trim()?.toIntOrNull() ?: return null
        if (uid <= 0) return null
        return TermuxRuntime(uid)
    }

    private fun sha256(file: File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
