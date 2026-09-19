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

/** Execute X11/Alpine shell templates; Android commands are never run here. */
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
