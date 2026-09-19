package com.saas.x11manager.util

import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.ProtocolFamily
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.net.SocketException
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Execute the shipped shell templates; Android commands are never run here. */
class AudioAndAlpineRuntimeTest {
    // Android's compile-time stubs omit the JDK UNIX socket API. The unit tests
    // run on JDK 17, so reach that API reflectively without changing app APIs.
    private fun unixListener(path: File): ServerSocketChannel {
        val channel = try {
            ServerSocketChannel::class.java.getMethod("open", ProtocolFamily::class.java)
                .invoke(null, StandardProtocolFamily.UNIX) as ServerSocketChannel
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
        try {
            val address = Class.forName("java.net.UnixDomainSocketAddress")
                .getMethod("of", Path::class.java).invoke(null, path.toPath()) as SocketAddress
            channel.bind(address)
            return channel
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("saas-runtime-").toFile()
        val bin = File(root, "bin").apply { mkdirs() }
        fun file(path: String, text: String = ""): File = File(root, path.removePrefix("/")).apply {
            parentFile.mkdirs()
            writeText(text)
        }
        fun command(name: String, text: String) = File(bin, name).apply {
            writeText("#!/bin/sh\n$text\n")
            setExecutable(true)
        }
        fun mapped(script: String): String = script
            .replace("/etc/", "${root.path}/etc/")
            .replace("/root", "${root.path}/root")
            .replace("/usr/", "${root.path}/usr/")
            .replace("/tmp/.X11-unix", "${root.path}/tmp/.X11-unix")
            .replace("/tmp/runtime-root", "${root.path}/tmp/runtime-root")
            .replace("/run/x11-session.pid", "${root.path}/run/x11-session.pid")
        fun run(script: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
            val process = ProcessBuilder(System.getenv("SAAS_TEST_SHELL") ?: "/bin/sh", "-c", script)
                .redirectErrorStream(true).apply {
                    environment()["PATH"] = "${bin.path}:${System.getenv("PATH") }"
                    environment().putAll(env)
                }.start()
            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("Shell template exceeded its deadline")
            }
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        }
        override fun close() { root.deleteRecursively() }
    }

    @Test fun switchingHostNatHostReplacesStaleProfilesAndConfiguresTheGraphicalUser() {
        Fixture().use { f ->
            val userHome = File(f.root, "home/desktop").path
            f.file("etc/passwd", "root:x:0:0::${f.root}/root:/bin/sh\ndesktop:x:1000:1000::$userHome:/bin/sh\n")
            f.file("etc/saas-x11-manager/session-user", "user=desktop\ncreate=0\n")
            f.file("home/desktop/.config/pulse/client.conf", "original-user-config\n")
            val cookie = ByteArray(256) { it.toByte() }
            f.file("root/.config/pulse/saas-audio.cookie").writeBytes(cookie)
            // UID changes are represented at this boundary; all file writes,
            // profile evaluation and client endpoint selection run normally.
            f.command("chown", "exit 0")
            f.command("pacat", "cat >/dev/null")
            f.command("id", "case \"\$1:\$2\" in -u:desktop|-g:desktop) echo 1000 ;; *) echo 0 ;; esac")
            f.command("su", "for arg do last=\$arg; done\nHOME='$userHome' USER=desktop /bin/sh -c \"\$last\"")
            f.command("pactl", """
                [ -z "${'$'}{PULSE_SERVER:-}" ] || exit 40
                [ -r "${'$'}PULSE_COOKIE" ] || exit 41
                [ "${'$'}(wc -c < "${'$'}PULSE_COOKIE")" -eq 256 ] || exit 42
                endpoint=${'$'}(sed -n 's/^default-server = //p' "${'$'}PULSE_CLIENTCONFIG")
                printf 'Server String: %s\nDefault Sink: AAudio_sink\n' "${'$'}endpoint"
            """.trimIndent())
            for (server in listOf("tcp:127.0.0.1:4713", "tcp:172.28.0.1:4714", "tcp:127.0.0.1:4715")) {
                f.file("etc/profile.d/saas-droidspaces-audio.sh", "# SaaS DroidSpaces Audio HostNAT\nexport PULSE_SERVER=old\n")
                val result = f.run(f.mapped(PulseAudioClientConfig.install(server)), mapOf("PULSE_SERVER" to "unix:/stale-native"))
                assertEquals(result.second, 0, result.first)
                assertTrue(result.second.contains("Server String: $server"))
                assertArrayEquals(cookie, File(userHome, ".config/pulse/saas-audio.cookie").readBytes())
                assertFalse(File(f.root, "etc/profile.d/saas-droidspaces-audio.sh").exists())
                assertTrue(File(userHome, ".config/pulse/client.conf").readText().contains(server))
            }
            assertEquals("original-user-config\n", File(userHome, ".config/pulse/client.conf.saas-x11-manager.bak").readText())
        }
    }

    @Test fun playbackMustDrainEvenWhenTheControlChannelWouldRespond() {
        Fixture().use { f ->
            f.command("pacat", "bytes=\$(wc -c); [ \"\$bytes\" -eq 48000 ]")
            val command = PulseAudioClientConfig.playbackProbe("unix:/control", "/cookie", "/client")
            assertEquals(0, f.run(command).first)
            f.command("pacat", "exec sleep 10")
            assertEquals("A stalled sink must fail the finite stream probe", 124, f.run(command).first)
        }
    }

    @Test fun completeHostAndNatPayloadsPreserveBinaryCookieAndFinishTheirProof() {
        val server = "tcp:172.28.0.1:4714"
        val cookie = ByteArray(256) { it.toByte() }
        val octal = cookie.joinToString("") { "\\0%03o".format(it.toInt() and 255) }
        val payloads = listOf(
            PulseAudioUnifiedTransport.buildContainerPayload(server, octal),
            PulseAudioNatScriptTransport.buildContainerPayload(server, octal)
        )
        for (payload in payloads) Fixture().use { f ->
            f.command("chown", "exit 0")
            f.command("id", "echo 0")
            f.command("speaker-test", "exit 0")
            f.command("aplay", "echo pulse")
            f.command("pacat", "cat >/dev/null")
            f.command("pactl", "printf 'Server String: $server\\nDefault Sink: AAudio_sink\\n'")
            val result = f.run(f.mapped(payload))
            assertEquals(result.second, 0, result.first)
            assertTrue(result.second, result.second.contains("__SAAS_AUDIO_PCM_DRAINED__"))
            assertTrue(result.second, result.second.contains("__READY__") || result.second.contains("__SAAS_AUDIO_TRANSPORT_READY__"))
            assertArrayEquals(cookie, File(f.root, "root/.config/pulse/saas-audio.cookie").readBytes())
        }
    }

    @Test fun isolatedMonitorWinsOverOtherSocketsVisibleInTmp() {
        for (number in listOf(0)) Fixture().use { f ->
            val source = File(f.root, "usr/.X11-unix").apply { mkdirs() }
            val target = File(f.root, "tmp/.X11-unix").apply { mkdirs() }
            val first = try {
                unixListener(File(source, "X$number"))
            } catch (e: SocketException) {
                if (!e.message.orEmpty().contains("Operation not permitted")) throw e
                assumeNoException("This runtime forbids AF_UNIX sockets; CI runs this fixture", e)
                return
            }
            first.use { unixListener(File(target, "X99")).use {
                f.command("icewm-session", "printf 'DESKTOP_DISPLAY=%s\\n' \"\$DISPLAY\"")
                val result = f.run(f.mapped(GraphicSessionInitFiles.rootSessionScript(GraphicSession.ICEWM, "/bin/sh")))
                assertEquals(result.second, 0, result.first)
                assertTrue(result.second, result.second.contains("DESKTOP_DISPLAY=:$number"))
            } }
        }
    }

    @Test fun crashedOpenRcStateIsClearedOnlyWhenItsProcessHasExited() {
        Fixture().use { f ->
            val events = File(f.root, "events")
            f.command("rc-service", "case \"\$2\" in status) exit \"\$TEST_STATUS\" ;; zap) echo zap >> '${events.path}' ;; esac")
            val script = f.mapped(X11SessionCommands.recoverCrashedOpenRc()) + "true\n"
            f.file("run/x11-session.pid", "2147483647\n")
            assertEquals(0, f.run(script, mapOf("TEST_STATUS" to "32")).first)
            assertEquals("zap\n", events.readText())
            events.delete()
            assertEquals(0, f.run(script, mapOf("TEST_STATUS" to "3")).first)
            assertFalse(events.exists())
            val livePid = "printf '%s\\n' \"\$\$\" > '${f.root}/run/x11-session.pid'\n"
            assertEquals(0, f.run(livePid + script, mapOf("TEST_STATUS" to "32")).first)
            assertFalse("Never clear state for a live PID", events.exists())
        }
    }

    @Test fun staleSocketMountIsRepairedAndCorrectMountIsReused() {
        Fixture().use { f ->
            val source = File(f.root, "usr/.X11-unix").apply { mkdirs() }
            val target = File(f.root, "tmp/.X11-unix").apply { mkdirs() }
            val events = File(f.root, "mounts")
            f.command("mount", "rmdir \"\$3\" && ln -s \"\$2\" \"\$3\" && echo bind >> '${events.path}'")
            repeat(2) {
                val result = f.run(f.mapped(X11SessionCommands.socketSetup()))
                assertEquals(result.second, 0, result.first)
                assertTrue(Files.isSameFile(source.toPath(), target.toPath()))
            }
            assertEquals("bind\n", events.readText())
        }
    }
}
