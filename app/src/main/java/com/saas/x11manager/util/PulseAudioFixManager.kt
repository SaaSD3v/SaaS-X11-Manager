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
 *
 * The embedded helper remains byte-for-byte v3.0.2 FINAL and is verified first.
 * For Manager execution only, its two root entry points are then replaced by a
 * deterministic file-RPC adapter. The helper still runs as the actual Termux
 * UID, while root/DroidSpaces commands execute inside the Manager's already-
 * authorized libsu shell. Termux therefore does not need a second Magisk grant.
 */
object PulseAudioFixManager {
    private const val ASSET_DIR = "saas-audio"
    private const val SCRIPT_VERSION = "3.0.2"
    private const val SCRIPT_SHA256 = "55cd6d74323a76fe44a07ac5c8777ff843ab13427b57cec686a3a9262c919278"
    private const val SCRIPT_SIZE = 69_523
    private const val SCRIPT_BASE64_SIZE = 92_700
    private const val SCRIPT_PARTS = 5

    private const val MANAGER_SCRIPT_SHA256 = "93caccafaa46ce0d51fe52d0ec2007aba4a45db2bc7d069f2d311c4bff8dbce2"
    private const val MANAGER_SCRIPT_SIZE = 70_724

    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val MANAGED_DIR = "$TERMUX_HOME/.local/share/saas-x11-manager"
    private const val MANAGED_SCRIPT = "$MANAGED_DIR/SaaS-DroidSpaces-Audio-Auto.sh"
    private const val ROOT_RPC_BASE = "/data/local/tmp/saas-x11manager-audio-rpc"

    private val ORIGINAL_ROOT_EXEC = """
        root_exec() { su -c "@1"; }
    """.trimIndent().replace('@', '$')

    private val MANAGER_ROOT_EXEC = """
        root_exec() {
            if [ -n "@{SAAS_AUDIO_ROOT_RPC_DIR:-}" ]; then
                rpc_dir="@SAAS_AUDIO_ROOT_RPC_DIR"
                rpc_seq="@{SAAS_AUDIO_ROOT_RPC_SEQ:-0}"
                rpc_seq=@((rpc_seq + 1))
                SAAS_AUDIO_ROOT_RPC_SEQ="@rpc_seq"
                export SAAS_AUDIO_ROOT_RPC_SEQ
                rpc_base="@rpc_dir/req.@$.@rpc_seq"
                printf '%s\n' "@1" > "@rpc_base.cmd" || return 125
                : > "@rpc_base.ready" || { rm -f "@rpc_base.cmd" 2>/dev/null || true; return 125; }
                rpc_wait=0
                while [ ! -f "@rpc_base.status" ]; do
                    rpc_wait=@((rpc_wait + 1))
                    if [ "@rpc_wait" -ge 6000 ]; then
                        rm -f "@rpc_base.cmd" "@rpc_base.ready" "@rpc_base.out" "@rpc_base.err" 2>/dev/null || true
                        return 124
                    fi
                    sleep 0.05
                done
                [ -f "@rpc_base.out" ] && cat "@rpc_base.out"
                [ -f "@rpc_base.err" ] && cat "@rpc_base.err" >&2
                rpc_status="@(sed -n '1p' "@rpc_base.status" 2>/dev/null || printf '125')"
                case "@rpc_status" in ''|*[!0-9]*) rpc_status=125;; esac
                rm -f "@rpc_base.cmd" "@rpc_base.ready" "@rpc_base.out" "@rpc_base.err" "@rpc_base.status" 2>/dev/null || true
                return "@rpc_status"
            fi
            su -c "@1"
        }
    """.trimIndent().replace('@', '$')

    private val ORIGINAL_REQUIRE_ROOT = """
        require_root_access() {
            uid="@(su -c 'id -u' 2>/dev/null | sed -n '1p' || true)"
            [ "@uid" = "0" ] || die "Root access through su was not granted"
        }
    """.trimIndent().replace('@', '$')

    private val MANAGER_REQUIRE_ROOT = """
        require_root_access() {
            uid="@(root_exec 'id -u' 2>/dev/null | sed -n '1p' || true)"
            [ "@uid" = "0" ] || die "Root access was not available"
        }
    """.trimIndent().replace('@', '$')

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
        logger?.i("[FIX] Root operations are delegated to the Manager root shell; Termux does not need a separate Magisk grant")
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

        val originalDigest = sha256(decoded)
        if (!originalDigest.equals(SCRIPT_SHA256, ignoreCase = true)) {
            return PulseAudioFixResult(
                success = false,
                message = "Bundled PulseAudio helper failed its source integrity check.",
                details = listOf("Expected $SCRIPT_SHA256", "Found ${originalDigest.ifEmpty { "unavailable" }}")
            )
        }

        val adapted = adaptForManager(decoded)
            ?: return PulseAudioFixResult(
                success = false,
                message = "Bundled PulseAudio helper could not be adapted for Manager root transport.",
                details = listOf("The expected v$SCRIPT_VERSION root entry points were not found exactly once.")
            )

        if (adapted.size != MANAGER_SCRIPT_SIZE) {
            return PulseAudioFixResult(
                success = false,
                message = "Manager-adapted PulseAudio helper has an unexpected size.",
                details = listOf("Expected: $MANAGER_SCRIPT_SIZE bytes", "Found: ${adapted.size} bytes")
            )
        }
        val adaptedDigest = sha256(adapted)
        if (!adaptedDigest.equals(MANAGER_SCRIPT_SHA256, ignoreCase = true)) {
            return PulseAudioFixResult(
                success = false,
                message = "Manager-adapted PulseAudio helper failed its integrity check.",
                details = listOf("Expected $MANAGER_SCRIPT_SHA256", "Found ${adaptedDigest.ifEmpty { "unavailable" }}")
            )
        }

        val cacheScript = File(context.cacheDir, "saas-droidspaces-audio-auto-$SCRIPT_VERSION-manager.sh")
        try {
            cacheScript.outputStream().use { it.write(adapted) }
        } catch (e: Exception) {
            return PulseAudioFixResult(
                success = false,
                message = "PulseAudio helper could not be written to the app cache.",
                details = listOf(e.message ?: e.javaClass.simpleName)
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
            message = "PulseAudio helper staged; source and Manager adapter SHA-256 verified."
        )
    }

    private fun adaptForManager(original: ByteArray): ByteArray? {
        val source = original.toString(Charsets.UTF_8)
        if (
            source.indexOf(ORIGINAL_ROOT_EXEC) < 0 ||
            source.indexOf(ORIGINAL_ROOT_EXEC) != source.lastIndexOf(ORIGINAL_ROOT_EXEC) ||
            source.indexOf(ORIGINAL_REQUIRE_ROOT) < 0 ||
            source.indexOf(ORIGINAL_REQUIRE_ROOT) != source.lastIndexOf(ORIGINAL_REQUIRE_ROOT)
        ) return null

        return source
            .replace(ORIGINAL_ROOT_EXEC, MANAGER_ROOT_EXEC)
            .replace(ORIGINAL_REQUIRE_ROOT, MANAGER_REQUIRE_ROOT)
            .toByteArray(Charsets.UTF_8)
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

        val rpcDir = "$ROOT_RPC_BASE/${runtime.uid}-${System.currentTimeMillis()}-${System.nanoTime()}"
        val termuxCommand = buildString {
            append("export HOME=").append(shellQuote(TERMUX_HOME)).append("; ")
            append("export PREFIX=").append(shellQuote(TERMUX_PREFIX)).append("; ")
            append("export TMPDIR=").append(shellQuote("$TERMUX_PREFIX/tmp")).append("; ")
            append("export PATH=").append(shellQuote("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")).append("; ")
            append("export SAAS_AUDIO_ROOT_RPC_DIR=").append(shellQuote(rpcDir)).append("; ")
            append("exec ").append(shellQuote("$TERMUX_PREFIX/bin/sh")).append(' ')
            append(shellQuote(MANAGED_SCRIPT)).append(' ').append(args)
        }

        val result = try {
            Shell.cmd(buildRootBrokerCommand(runtime, rpcDir, termuxCommand)).exec()
        } catch (e: Exception) {
            return PulseAudioFixResult(
                success = false,
                message = "PulseAudio helper could not be started through the Manager root broker.",
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
            return PulseAudioFixResult(
                success = false,
                message = "PulseAudio fix failed for $containerName. Root was delegated through the Manager; Termux does not need a separate Magisk root grant.",
                details = lines.takeLast(16)
            )
        }

        return PulseAudioFixResult(
            success = true,
            message = "PulseAudio helper completed successfully for $containerName.",
            details = lines.takeLast(16)
        )
    }

    private fun buildRootBrokerCommand(
        runtime: TermuxRuntime,
        rpcDir: String,
        termuxCommand: String
    ): String = """
        rpc=%RPC%
        uid=%UID%
        rm -rf "@rpc" 2>/dev/null || true
        mkdir -p "@rpc" || exit 120
        chown "@uid:@uid" "@rpc" || exit 121
        chmod 700 "@rpc" || exit 122

        broker() {
            while [ ! -f "@rpc/stop" ]; do
                matched=0
                for ready in "@rpc"/req.*.ready; do
                    [ -f "@ready" ] || continue
                    base="@{ready%.ready}"
                    mv "@ready" "@base.running" 2>/dev/null || continue
                    /system/bin/sh "@base.cmd" >"@base.out" 2>"@base.err"
                    cmd_rc=@?
                    chmod 644 "@base.out" "@base.err" 2>/dev/null || true
                    printf '%s\n' "@cmd_rc" >"@base.status.tmp"
                    chmod 644 "@base.status.tmp" 2>/dev/null || true
                    mv "@base.status.tmp" "@base.status"
                    rm -f "@base.running" 2>/dev/null || true
                    matched=1
                done
                [ "@matched" -eq 1 ] || sleep 0.05
            done
        }

        broker &
        broker_pid=@!
        su "@uid" -c %TERMUX%
        helper_rc=@?
        : > "@rpc/stop"
        kill "@broker_pid" 2>/dev/null || true
        wait "@broker_pid" 2>/dev/null || true
        rm -rf "@rpc" 2>/dev/null || true
        exit "@helper_rc"
    """.trimIndent()
        .replace("%RPC%", shellQuote(rpcDir))
        .replace("%UID%", runtime.uid.toString())
        .replace("%TERMUX%", shellQuote(termuxCommand))
        .replace('@', '$')

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

    private fun sha256(bytes: ByteArray): String = try {
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
