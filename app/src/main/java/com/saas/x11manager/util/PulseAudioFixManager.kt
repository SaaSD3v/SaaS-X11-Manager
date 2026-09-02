package com.saas.x11manager.util

import android.content.Context
import android.util.Base64
import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Result returned to the Fixes UI after applying/removing the PulseAudio fix. */
data class PulseAudioFixResult(
    val success: Boolean,
    val message: String,
    val details: List<String> = emptyList()
)

/**
 * Android wrapper around the audited SaaS DroidSpaces Audio Auto helper.
 *
 * The embedded asset remains byte-for-byte v3.0.2 FINAL and is verified before
 * use. For Manager execution only, two root entry points are then replaced by a
 * tiny deterministic file-RPC adapter. The helper itself still runs as the real
 * Termux UID, while DroidSpaces/root commands are executed by the Manager's
 * already-authorized libsu root shell. This means enabling the fix does NOT ask
 * the user to grant a second Magisk root permission to Termux.
 */
object PulseAudioFixManager {
    private const val ASSET_DIR = "saas-audio"
    private const val SCRIPT_VERSION = "3.0.2"

    // Original audited standalone helper, reconstructed from APK assets.
    private const val SCRIPT_SHA256 = "55cd6d74323a76fe44a07ac5c8777ff843ab13427b57cec686a3a9262c919278"
    private const val SCRIPT_SIZE = 69_523
    private const val SCRIPT_BASE64_SIZE = 92_700
    private const val SCRIPT_PARTS = 5

    // Same audited helper after applying only the Manager root transport adapter.
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

    suspend fun enable(
        containerName: String,
        logger: ContainerLogger? = null
    ): PulseAudioFixResult = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        logger?.i("--- PulseAudio Fix ---")
        logger?.i("[FIX] Enabling PulseAudio fix for $containerName")

        val staged = stageHelper(context)
        if (!staged.success) {
            staged.details.forEach { logger?.e("[FIX] $it") }
            return@withContext staged
        }

        val run = runHelper(
            containerName = containerName,
            arguments = listOf("--container", containerName, "--mode", "auto", "--restore-state"),
            quiet = false,
            logger = logger
        )
        if (!run.success) return@withContext run

        if (!FixSettings.setPulseAudioEnabled(context, containerName, true)) {
            logger?.e("[FIX] Audio setup succeeded, but Android could not persist the enabled state; rolling it back")
            runHelper(
                containerName = containerName,
                arguments = listOf("--container", containerName, "--uninstall"),
                quiet = true,
                logger = logger
            )
            return@withContext PulseAudioFixResult(
                success = false,
                message = "Could not save the PulseAudio fix state. The applied integration was rolled back."
            )
        }

        logger?.i("[FIX] PulseAudio fix enabled")
        PulseAudioFixResult(
            success = true,
            message = "PulseAudio fix enabled. Native DroidSpaces audio is preferred; loopback TCP is used only as a safe fallback.",
            details = run.details
        )
    }

    suspend fun disable(
        containerName: String,
        logger: ContainerLogger? = null
    ): PulseAudioFixResult = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        logger?.i("--- PulseAudio Fix ---")
        logger?.i("[FIX] Disabling PulseAudio fix for $containerName")

        val staged = stageHelper(context)
        if (!staged.success) {
            staged.details.forEach { logger?.e("[FIX] $it") }
            return@withContext staged
        }

        val run = runHelper(
            containerName = containerName,
            arguments = listOf("--container", containerName, "--uninstall"),
            quiet = false,
            logger = logger
        )
        if (!run.success) return@withContext run

        if (!FixSettings.setPulseAudioEnabled(context, containerName, false)) {
            logger?.e("[FIX] Integration was removed, but Android could not persist the disabled state")
            return@withContext PulseAudioFixResult(
                success = false,
                message = "PulseAudio integration was removed, but the Manager could not save the disabled state.",
                details = run.details
            )
        }

        logger?.i("[FIX] PulseAudio fix disabled")
        PulseAudioFixResult(
            success = true,
            message = "PulseAudio fix disabled and the helper restored the configuration it previously owned.",
            details = run.details
        )
    }

    /**
     * Re-validates an already-enabled fix before a graphical session starts.
     * A failure is deliberately non-fatal for X11/VNC; it is logged and the
     * graphical session is still allowed to start.
     */
    suspend fun ensureIfEnabled(
        containerName: String,
        logger: ContainerLogger? = null
    ): PulseAudioFixResult? = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isPulseAudioEnabled(context, containerName)) {
            return@withContext null
        }

        logger?.i("[FIX] PulseAudio fix is enabled; validating audio bridge before graphical start...")
        val staged = stageHelper(context)
        if (!staged.success) {
            logger?.w("[FIX] ${staged.message}")
            return@withContext staged
        }

        val run = runHelper(
            containerName = containerName,
            arguments = listOf("--container", containerName, "--mode", "auto", "--restore-state"),
            quiet = true,
            logger = logger
        )
        if (run.success) {
            logger?.i("[FIX] PulseAudio bridge ready")
        } else {
            logger?.w("[FIX] PulseAudio validation failed; graphical startup will continue")
            logger?.w("[FIX] Open Fixes and toggle PulseAudio fix off/on to retry interactively")
        }
        run
    }

    private data class TermuxRuntime(val uid: Int)

    /**
     * Reconstruct the exact audited helper, verify it, apply the deterministic
     * Manager-only root transport adapter, verify the adapted result as well,
     * then stage it with Termux ownership.
     */
    private fun stageHelper(context: Context): PulseAudioFixResult {
        val runtime = detectTermuxRuntime()
            ?: return PulseAudioFixResult(
                success = false,
                message = "Termux was not detected.",
                details = listOf(
                    "Termux must be installed at /data/data/com.termux before the PulseAudio fix can be enabled."
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

        val rootCommand = buildRootBrokerCommand(
            runtime = runtime,
            rpcDir = rpcDir,
            termuxCommand = termuxCommand
        )

        val result = try {
            Shell.cmd(rootCommand).exec()
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
            val meaningful = lines.takeLast(16)
            return PulseAudioFixResult(
                success = false,
                message = "PulseAudio fix failed for $containerName. The Manager root broker was used, so Termux does not need a separate Magisk root grant.",
                details = meaningful
            )
        }

        return PulseAudioFixResult(
            success = true,
            message = "PulseAudio helper completed successfully for $containerName.",
            details = lines.takeLast(16)
        )
    }

    /**
     * Root stays in the already-authorized Manager/libsu shell. The child helper
     * is deliberately dropped to the Termux UID. Whenever that helper needs a
     * root operation it writes one trusted command file into the private RPC
     * directory; this root broker executes it and atomically publishes status.
     */
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
