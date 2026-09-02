package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class PulseAudioFixPolicyTest {

    private fun projectFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile || candidate.isDirectory) return candidate
            current = current.parentFile
        }
        error("Could not locate project path: $relativePath")
    }

    private fun source(relativePath: String): String = projectFile(relativePath).readText()

    private fun embeddedHelper(): ByteArray {
        val dir = projectFile("app/src/main/assets/saas-audio")
        val parts = dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.matches(Regex("part\\d{2}\\.b64")) }
            .sortedBy { it.name }
        assertEquals(listOf("part00.b64", "part01.b64", "part02.b64", "part03.b64", "part04.b64"), parts.map { it.name })
        val encoded = parts.joinToString(separator = "") { it.readText().filterNot(Char::isWhitespace) }
        assertEquals(92_700, encoded.length)
        return Base64.getDecoder().decode(encoded)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun managerAdaptedHelper(): ByteArray {
        val originalRootExec = """
            root_exec() { su -c "@1"; }
        """.trimIndent().replace('@', '$')
        val managerRootExec = """
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
        val originalRequireRoot = """
            require_root_access() {
                uid="@(su -c 'id -u' 2>/dev/null | sed -n '1p' || true)"
                [ "@uid" = "0" ] || die "Root access through su was not granted"
            }
        """.trimIndent().replace('@', '$')
        val managerRequireRoot = """
            require_root_access() {
                uid="@(root_exec 'id -u' 2>/dev/null | sed -n '1p' || true)"
                [ "@uid" = "0" ] || die "Root access was not available"
            }
        """.trimIndent().replace('@', '$')

        val source = embeddedHelper().toString(Charsets.UTF_8)
        assertEquals(1, Regex(Regex.escape(originalRootExec)).findAll(source).count())
        assertEquals(1, Regex(Regex.escape(originalRequireRoot)).findAll(source).count())
        return source
            .replace(originalRootExec, managerRootExec)
            .replace(originalRequireRoot, managerRequireRoot)
            .toByteArray(Charsets.UTF_8)
    }

    @Test
    fun fixIsOptInAndUsesARealFullScreenDestination() {
        val settings = source("app/src/main/java/com/saas/x11manager/util/FixSettings.kt")
        val card = source("app/src/main/java/com/saas/x11manager/ui/component/ContainerCard.kt")
        val home = source("app/src/main/java/com/saas/x11manager/ui/screen/HomeScreen.kt")
        val fixes = source("app/src/main/java/com/saas/x11manager/ui/screen/FixesDialog.kt")
        val navigation = source("app/src/main/java/com/saas/x11manager/ui/navigation/AppNavigation.kt")

        assertTrue(settings.contains("getBoolean(PULSEAUDIO_PREFIX + containerName, false)"))
        assertTrue(card.contains("val onFixes: () -> Unit = {}"))
        val generalIndex = card.indexOf("label = \"General settings\"")
        val fixesIndex = card.indexOf("label = \"Fixes\"")
        assertTrue(generalIndex >= 0)
        assertTrue(fixesIndex > generalIndex)

        assertTrue(home.contains("onOpenFixes: (String) -> Unit"))
        assertTrue(home.contains("onOpenFixes(container.name)"))
        assertTrue(navigation.contains("FixesScreen("))
        assertTrue(navigation.contains("fixesScreenContainer"))
        assertTrue(fixes.contains("internal fun FixesScreen("))
        assertTrue(fixes.contains("Scaffold("))
        assertTrue(fixes.contains("TopAppBar("))
        assertTrue(fixes.contains("LinearProgressIndicator"))
        assertFalse(fixes.contains("AlertDialog("))
    }

    @Test
    fun exactAuditedHelperIsEmbeddedThenDeterministicallyAdapted() {
        val helper = embeddedHelper()
        val adapted = managerAdaptedHelper()
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertEquals(69_523, helper.size)
        assertEquals(
            "55cd6d74323a76fe44a07ac5c8777ff843ab13427b57cec686a3a9262c919278",
            sha256(helper)
        )
        assertEquals(70_724, adapted.size)
        assertEquals(
            "93caccafaa46ce0d51fe52d0ec2007aba4a45db2bc7d069f2d311c4bff8dbce2",
            sha256(adapted)
        )
        assertTrue(manager.contains("SCRIPT_SHA256 = \"55cd6d74323a76fe44a07ac5c8777ff843ab13427b57cec686a3a9262c919278\""))
        assertTrue(manager.contains("MANAGER_SCRIPT_SHA256 = \"93caccafaa46ce0d51fe52d0ec2007aba4a45db2bc7d069f2d311c4bff8dbce2\""))
        assertTrue(manager.contains("availableParts != expectedParts"))
        assertTrue(manager.contains("adaptForManager(decoded)"))
    }

    @Test
    fun managerRootBrokerRemovesSeparateTermuxMagiskGrantRequirement() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")
        val fixes = source("app/src/main/java/com/saas/x11manager/ui/screen/FixesDialog.kt")

        assertTrue(manager.contains("SAAS_AUDIO_ROOT_RPC_DIR"))
        assertTrue(manager.contains("/system/bin/sh \"@base.cmd\""))
        assertTrue(manager.contains("su \"@uid\" -c %TERMUX%"))
        assertTrue(manager.contains("Termux does not need a separate Magisk root grant"))
        assertFalse(manager.contains("Magisk may need root permission for Termux"))
        assertTrue(fixes.contains("does not need a separate Magisk root grant"))
    }

    @Test
    fun helperKeepsNativeFirstSafeFallbackAndDistroDetection() {
        val script = embeddedHelper().toString(Charsets.UTF_8)
        val sessionAccess = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")

        assertTrue(script.contains("/tmp/.pulse-socket"))
        assertTrue(script.contains("module-aaudio-sink"))
        assertTrue(script.contains("module-sles-sink"))
        assertTrue(script.contains("apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils"))
        assertTrue(script.contains("apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse"))
        assertTrue(script.contains("LISTEN=\"127.0.0.1\""))
        assertFalse(script.contains("listen=0.0.0.0"))
        assertTrue(script.contains("--restore-state"))
        assertTrue(script.contains("--uninstall"))
        assertTrue(sessionAccess.contains("PulseAudioFixManager.ensureIfEnabled"))
    }

    @Test
    fun standaloneAndManagerAdaptedHelpersPassPosixShellSyntaxCheck() {
        listOf(
            "standalone" to embeddedHelper(),
            "manager-adapted" to managerAdaptedHelper()
        ).forEach { (label, helper) ->
            val temp = File.createTempFile("saas-audio-helper-$label", ".sh")
            try {
                temp.writeBytes(helper)
                val process = ProcessBuilder("sh", "-n", temp.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val exit = process.waitFor()
                assertEquals("$label sh -n failed: $output", 0, exit)
            } finally {
                temp.delete()
            }
        }
    }
}
