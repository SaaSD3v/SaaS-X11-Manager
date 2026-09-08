package com.saas.x11manager.util

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Tests the production encoder and client payloads, including real libpulse. */
class PulseAudioTransportRuntimeTest {
    private data class Result(val code: Int, val out: String, val err: String) {
        val details get() = "exit=$code\n$out\n$err"
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("saas-pulse-").toFile()
        val bin = File(root, "bin").apply { mkdirs() }
        private var sequence = 0
        fun file(path: String, content: String = "") = File(root, path).apply {
            parentFile.mkdirs()
            writeText(content)
        }
        fun command(name: String, body: String) = file("bin/$name", "#!/bin/sh\n$body\n")
            .apply { setExecutable(true) }
        fun mapped(script: String): String = script
            .replace("/etc/", "${root.path}/etc/")
            .replace("/root", "${root.path}/root")
        fun run(script: String, env: Map<String, String> = emptyMap()): Result {
            val id = sequence++
            val out = File(root, "output-$id")
            val err = File(root, "error-$id")
            val process = ProcessBuilder(System.getenv("SAAS_TEST_SHELL") ?: "/bin/sh", "-c", script)
                .redirectOutput(out).redirectError(err).apply {
                    environment()["LC_ALL"] = "C"
                    environment()["PATH"] = "${bin.path}:${System.getenv("PATH") }"
                    environment().putAll(env)
                }.start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("Audio command exceeded its deadline")
            }
            return Result(process.exitValue(), out.readText(), err.readText())
        }
        override fun close() { root.deleteRecursively() }
    }

    private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun encode(f: Fixture, cookie: File): String {
        // Execute the actual HOST command envelope as well as its encoder.
        // Only replace Termux's PATH with the test machine's command directory.
        val command = PulseAudioUnifiedTransport.buildTermuxCommand(
            PulseAudioCookieTransport.encodeCommand(cookie.path)
        ).lineSequence().joinToString("\n") {
            if (it.trimStart().startsWith("export PATH=")) "export PATH=${q(System.getenv("PATH"))}" else it
        }
        val result = f.run(command)
        assertEquals(result.details, 0, result.code)
        val lines = result.out.lines()
        val marker = PulseAudioUnifiedTransport.DIRECT_RC_MARKER
        assertTrue("Control status must occupy its own line", lines.contains(marker + "0"))
        return requireNotNull(PulseAudioCookieTransport.fromOutput(lines.filterNot { it.startsWith(marker) }))
    }

    @Test fun encoderPreservesEveryByteAcrossShellQuotingAndRejectsDoubleEscaping() {
        Fixture().use { f ->
            val bytes = ByteArray(256) { it.toByte() }
            val cookie = f.file("cookie with ' quotes").apply { writeBytes(bytes) }
            val serialized = encode(f, cookie)
            assertEquals(1280, serialized.length)
            val target = File(f.root, "decoded")
            val result = f.run("printf '%b' ${q(serialized)} > ${q(target.path)}")
            assertEquals(result.details, 0, result.code)
            assertArrayEquals(bytes, target.readBytes())

            // This is the exact pre-fix failure: two backslashes reach printf %b.
            val broken = serialized.replace("\\", "\\\\")
            assertNull(PulseAudioCookieTransport.fromOutput(listOf(broken)))
            assertEquals(0, f.run("printf '%b' ${q(broken)} > ${q(target.path)}").code)
            assertEquals(1280L, target.length())
            assertNull(PulseAudioCookieTransport.fromOutput(listOf(serialized.dropLast(1))))
            assertNull(PulseAudioCookieTransport.fromOutput(listOf("banner", serialized)))
        }
    }

    @Test fun invalidCookieFilesFailBeforeProducingCredentials() {
        Fixture().use { f ->
            val cookie = f.file("cookie")
            for (size in listOf(0, 255, 257)) {
                cookie.writeBytes(ByteArray(size))
                val result = f.run(PulseAudioCookieTransport.encodeCommand(cookie.path))
                assertNotEquals(0, result.code)
                assertNull(PulseAudioCookieTransport.fromOutput(result.out.lines()))
            }
        }
    }

    @Test fun clientFailureReportsTheCookieStageWithoutExposingTheCookie() {
        Fixture().use { f ->
            for (name in listOf("pactl", "pacat", "speaker-test")) f.command(name, "exit 0")
            f.command("id", "echo 0")
            val broken = "\\\\0000".repeat(256)
            for (payload in listOf(
                PulseAudioUnifiedTransport.buildContainerPayload("tcp:127.0.0.1:4713", broken),
                PulseAudioNatScriptTransport.buildContainerPayload("tcp:127.0.0.2:4713", broken)
            )) {
                val result = f.run(f.mapped(payload))
                assertNotEquals(0, result.code)
                val summary = PulseAudioClientConfig.failureSummary(result.code, (result.out + result.err).lines())
                assertTrue(summary, summary.startsWith("authentication cookie transfer"))
                assertFalse(summary.contains(broken))
                assertFalse(result.out.contains("__READY__"))
                assertFalse(result.out.contains("__SAAS_AUDIO_TRANSPORT_READY__"))
            }
        }
    }

    @Test(timeout = 90000) fun realPulseAudioAuthenticatesBothPayloadsAndDrainsPulseAndAlsaStreams() {
        // Required by CI. Local Android-only environments can run the encoder
        // tests without installing the Linux PulseAudio daemon.
        assumeTrue("Enable the real PulseAudio fixture with SAAS_PULSE_INTEGRATION_REQUIRED=1",
            System.getenv("SAAS_PULSE_INTEGRATION_REQUIRED") == "1")
        Fixture().use { f ->
            for (tool in listOf("pulseaudio", "pactl", "pacat", "aplay", "speaker-test")) {
                val available = f.run("command -v $tool")
                assertEquals("Required integration dependency $tool: ${available.details}", 0, available.code)
            }
            val bytes = ByteArray(256) { it.toByte() }
            val cookie = f.file("core/cookie").apply { writeBytes(bytes) }
            val config = f.file("core/client.conf", "autospawn = no\nenable-shm = no\n")
            val runtime = File(f.root, "core/runtime").apply { mkdirs() }
            assertEquals(0, f.run("chmod 700 ${q(runtime.path)}").code)
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val host = "tcp:127.0.0.1:$port"
            val alternate = "tcp:127.0.0.2:$port"
            val log = File(f.root, "daemon.log")
            // One real core with two exact-address, cookie-authenticated TCP
            // listeners. The null sink replaces only Android's physical device.
            val daemon = ProcessBuilder("pulseaudio", "-n", "--daemonize=no", "--use-pid-file=no",
                "--exit-idle-time=-1", "--disallow-exit", "--disable-shm",
                "-L", "module-null-sink sink_name=AAudio_sink",
                "-L", "module-native-protocol-tcp listen=127.0.0.1 port=$port auth-cookie=${cookie.path}",
                "-L", "module-native-protocol-tcp listen=127.0.0.2 port=$port auth-cookie=${cookie.path}")
                .redirectErrorStream(true).redirectOutput(log).apply {
                    environment()["HOME"] = File(f.root, "core").path
                    environment()["XDG_RUNTIME_DIR"] = runtime.path
                    environment()["PULSE_RUNTIME_PATH"] = runtime.path
                    environment()["PULSE_STATE_PATH"] = runtime.path
                    environment()["LC_ALL"] = "C"
                }.start()
            try {
                val coreEnv = mapOf("PULSE_SERVER" to host, "PULSE_COOKIE" to cookie.path,
                    "PULSE_CLIENTCONFIG" to config.path)
                var ready = false
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline && daemon.isAlive) {
                    if (f.run("timeout 1 pactl info", coreEnv).code == 0) { ready = true; break }
                    Thread.sleep(100)
                }
                assertTrue("Real PulseAudio did not become ready:\n${log.readText()}", ready)
                val encoded = encode(f, cookie)
                // Only the root/namespace boundary is represented here. No pactl,
                // pacat, ALSA plugin or PulseAudio protocol operation is mocked.
                f.command("id", "echo 0")
                val ds = f.command("droidspaces", "[ \"\$1\" = '--name=audio fixture' ] || exit 40\n[ \"\$2\" = run ] || exit 41\nshift 2\nexec \"\$@\"")
                val modes = listOf(host to false, alternate to true, host to false)
                for ((server, nat) in modes) {
                    val payload = if (nat) PulseAudioNatScriptTransport.buildContainerPayload(server, encoded)
                        else PulseAudioUnifiedTransport.buildContainerPayload(server, encoded)
                    val body = "export PATH=${q(f.bin.path)}:\$PATH\n" + f.mapped(payload)
                    val result = f.run("${q(ds.path)} '--name=audio fixture' run /bin/sh -lc ${q(body)}")
                    assertEquals(result.details + "\n" + log.readText(), 0, result.code)
                    assertTrue(result.details, result.out.contains("__SAAS_AUDIO_PCM_DRAINED__"))
                    assertTrue(result.details, result.out.contains(if (nat) "__SAAS_AUDIO_TRANSPORT_READY__" else "__READY__"))
                    assertArrayEquals(bytes, File(f.root, "root/.config/pulse/saas-audio.cookie").readBytes())

                    val profile = f.mapped(". /etc/profile.d/saas-x11-audio.sh\n")
                    val userEnv = mapOf("HOME" to File(f.root, "root").path, "PULSE_SERVER" to "tcp:127.0.0.9:1")
                    val userInfo = f.run(profile + "timeout 3 pactl info", userEnv)
                    assertEquals(userInfo.details, 0, userInfo.code)
                    assertTrue(userInfo.out, userInfo.out.contains("Server String: $server"))
                    val alsa = f.run(profile + "dd if=/dev/zero bs=9600 count=5 2>/dev/null | timeout 5 aplay -q -D default -t raw -f S16_LE -r 48000 -c 2",
                        userEnv + ("ALSA_CONFIG_PATH" to File(f.root, "etc/asound.conf").path))
                    assertEquals(alsa.details, 0, alsa.code)
                }
                val wrongCookie = f.file("wrong-cookie").apply { writeBytes(ByteArray(256) { 42 }) }
                val denied = f.run("timeout 3 pactl info", coreEnv + ("PULSE_COOKIE" to wrongCookie.path))
                assertNotEquals("Wrong cookies must never authenticate", 0, denied.code)
                assertTrue(denied.details, denied.err.contains("Access denied", ignoreCase = true))
            } finally {
                daemon.destroy()
                if (!daemon.waitFor(3, TimeUnit.SECONDS)) {
                    daemon.destroyForcibly()
                    daemon.waitFor(3, TimeUnit.SECONDS)
                }
            }
        }
    }
}
