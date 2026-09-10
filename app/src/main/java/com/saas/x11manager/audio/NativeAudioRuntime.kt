package com.saas.x11manager.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.saas.x11manager.X11Application
import com.saas.x11manager.util.ContainerInfo
import com.saas.x11manager.util.ContainerLogger
import com.saas.x11manager.util.ContainerManager
import com.saas.x11manager.util.Constants
import com.saas.x11manager.util.FixSettings
import com.saas.x11manager.util.PulseAudioClientConfig
import com.saas.x11manager.util.PulseAudioContainerCommand
import com.saas.x11manager.util.PulseAudioUnifiedTransport
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** App-private locations for the embedded audio runtime. No package/data path is hardcoded. */
internal data class NativeAudioLayout(
    val stateDir: File,
    val pulseHome: File,
    val configDir: File,
    val runtimeDir: File,
    val pulseStateDir: File,
    val cookie: File,
    val controlSocket: File,
    val clientConfig: File,
    val logFile: File,
    val consumersFile: File,
    val consumersLock: File,
    val nativeLibDir: File,
) {
    companion object {
        fun from(context: Context): NativeAudioLayout {
            val state = File(context.noBackupFilesDir, "native-audio")
            val home = File(state, "pulse-home")
            val config = File(home, ".config/pulse")
            return NativeAudioLayout(
                stateDir = state,
                pulseHome = home,
                configDir = config,
                runtimeDir = File(state, "runtime"),
                pulseStateDir = File(state, "state"),
                cookie = File(state, "transport.cookie"),
                controlSocket = File(state, "control.sock"),
                clientConfig = File(config, "client.conf"),
                logFile = File(state, "pulseaudio.log"),
                consumersFile = File(state, "consumers.tsv"),
                consumersLock = File(state, "consumers.lock"),
                nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
            )
        }
    }
}

internal data class NativeAudioConsumer(
    val containerName: String,
    val server: String,
    val moduleId: Int,
)

/** Cross-process registry shared by the Manager process and :audio service. */
internal object NativeAudioConsumerStore {
    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    private inline fun <T> locked(context: Context, block: (NativeAudioLayout) -> T): T {
        val layout = NativeAudioLayout.from(context)
        layout.stateDir.mkdirs()
        RandomAccessFile(layout.consumersLock, "rw").channel.use { channel ->
            val fileLock = channel.lock()
            try {
                return block(layout)
            } finally {
                fileLock.release()
            }
        }
    }

    private fun readUnlocked(layout: NativeAudioLayout): MutableList<NativeAudioConsumer> {
        if (!layout.consumersFile.isFile) return mutableListOf()
        return layout.consumersFile.readLines().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size != 3) return@mapNotNull null
            val name = decode(fields[0]) ?: return@mapNotNull null
            val module = fields[2].toIntOrNull() ?: return@mapNotNull null
            NativeAudioConsumer(name, fields[1], module)
        }.toMutableList()
    }

    private fun writeUnlocked(layout: NativeAudioLayout, consumers: Collection<NativeAudioConsumer>) {
        layout.stateDir.mkdirs()
        val tmp = File(layout.stateDir, "consumers.tsv.tmp.${android.os.Process.myPid()}")
        tmp.writeText(
            consumers.distinctBy { it.containerName }.joinToString(separator = "\n", postfix = if (consumers.isEmpty()) "" else "\n") {
                "${encode(it.containerName)}\t${it.server}\t${it.moduleId}"
            }
        )
        if (!tmp.renameTo(layout.consumersFile)) {
            layout.consumersFile.writeBytes(tmp.readBytes())
            tmp.delete()
        }
    }

    fun snapshot(context: Context): List<NativeAudioConsumer> = locked(context) { readUnlocked(it) }

    fun put(context: Context, consumer: NativeAudioConsumer) = locked(context) { layout ->
        val current = readUnlocked(layout)
        current.removeAll { it.containerName == consumer.containerName }
        current += consumer
        writeUnlocked(layout, current)
    }

    fun remove(context: Context, containerName: String): Pair<NativeAudioConsumer?, List<NativeAudioConsumer>> =
        locked(context) { layout ->
            val current = readUnlocked(layout)
            val removed = current.firstOrNull { it.containerName == containerName }
            current.removeAll { it.containerName == containerName }
            writeUnlocked(layout, current)
            removed to current.toList()
        }

    fun replace(context: Context, consumers: Collection<NativeAudioConsumer>) = locked(context) { layout ->
        writeUnlocked(layout, consumers)
    }
}

/**
 * Single native audio provider for the experimental branch.
 *
 * Host core: packaged PulseAudio 17 Android ELF -> AAudio/OpenSL ES.
 * Container transport: authenticated PulseAudio TCP using the same validated
 * client payload and DroidSpaces bounded-argv command path as X11APP.
 */
object NativeAudioRuntime {
    private const val PULSE_EXEC = "libsaas_pulseaudio_exec.so"
    private const val PACTL_EXEC = "libsaas_pactl_exec.so"
    private const val PACAT_EXEC = "libsaas_pacat_exec.so"
    private const val MODULE_AAUDIO = "module-aaudio-sink.so"
    private const val MODULE_SLES = "module-sles-sink.so"
    private const val MODULE_UNIX = "module-native-protocol-unix.so"
    private const val MODULE_TCP = "module-native-protocol-tcp.so"
    private const val BASE_PORT = 4713
    private const val MAX_PORT_SHIFT = 64

    private val runtimeMutex = Mutex()

    private data class CommandResult(
        val code: Int,
        val stdout: List<String>,
        val stderr: List<String>,
        val timedOut: Boolean = false,
    )

    private data class Listener(
        val moduleId: Int,
        val server: String,
        val createdNow: Boolean,
    )

    private data class Core(val sink: String)

    internal fun prepareStateForService(context: Context): Boolean = try {
        val layout = NativeAudioLayout.from(context)
        listOf(layout.stateDir, layout.pulseHome, layout.configDir, layout.runtimeDir, layout.pulseStateDir)
            .forEach { dir ->
                if (!dir.exists() && !dir.mkdirs()) return false
                dir.setReadable(true, true)
                dir.setWritable(true, true)
                dir.setExecutable(true, true)
            }
        layout.clientConfig.writeText("autospawn = no\nenable-shm = no\n")
        layout.clientConfig.setReadable(true, true)
        layout.clientConfig.setWritable(true, true)
        ensureCookie(layout)
        true
    } catch (_: Exception) {
        false
    }

    private fun ensureCookie(layout: NativeAudioLayout) {
        if (layout.cookie.isFile && layout.cookie.length() == 256L) return
        val bytes = ByteArray(256)
        SecureRandom().nextBytes(bytes)
        val tmp = File(layout.stateDir, "transport.cookie.tmp.${android.os.Process.myPid()}")
        tmp.writeBytes(bytes)
        tmp.setReadable(true, true)
        tmp.setWritable(true, true)
        if (!tmp.renameTo(layout.cookie)) {
            layout.cookie.writeBytes(bytes)
            tmp.delete()
        }
        check(layout.cookie.length() == 256L) { "Native audio cookie must contain exactly 256 bytes" }
    }

    private fun requiredRuntimeFiles(layout: NativeAudioLayout): List<File> = listOf(
        PULSE_EXEC, PACTL_EXEC, PACAT_EXEC,
        MODULE_AAUDIO, MODULE_SLES, MODULE_UNIX, MODULE_TCP,
    ).map { File(layout.nativeLibDir, it) }

    private fun runtimeAvailable(layout: NativeAudioLayout): Boolean =
        requiredRuntimeFiles(layout).all { it.isFile && it.canRead() }

    internal fun nativeEnvironment(context: Context): MutableMap<String, String> {
        val layout = NativeAudioLayout.from(context)
        return mutableMapOf(
            "HOME" to layout.pulseHome.absolutePath,
            "XDG_CONFIG_HOME" to File(layout.pulseHome, ".config").absolutePath,
            "PULSE_CONFIG" to File(layout.configDir, "daemon.conf").absolutePath,
            "PULSE_CONFIG_PATH" to layout.configDir.absolutePath,
            "PULSE_RUNTIME_PATH" to layout.runtimeDir.absolutePath,
            "PULSE_STATE_PATH" to layout.pulseStateDir.absolutePath,
            "PULSE_CLIENTCONFIG" to layout.clientConfig.absolutePath,
            "PULSE_COOKIE" to layout.cookie.absolutePath,
            "TMPDIR" to layout.runtimeDir.absolutePath,
            "LD_LIBRARY_PATH" to layout.nativeLibDir.absolutePath,
            "LC_ALL" to "C",
        )
    }

    private fun executeNative(
        context: Context,
        executableName: String,
        arguments: List<String>,
        control: Boolean = false,
        timeoutSeconds: Long = 6,
        stdin: ByteArray? = null,
    ): CommandResult {
        val layout = NativeAudioLayout.from(context)
        val executable = File(layout.nativeLibDir, executableName)
        if (!executable.isFile) return CommandResult(127, emptyList(), listOf("Missing ${executable.absolutePath}"))

        return try {
            val process = ProcessBuilder(listOf(executable.absolutePath) + arguments)
                .redirectErrorStream(false)
                .apply {
                    environment().putAll(nativeEnvironment(context))
                    if (control) {
                        environment()["PULSE_SERVER"] = "unix:${layout.controlSocket.absolutePath}"
                        environment()["PULSE_COOKIE"] = layout.cookie.absolutePath
                    }
                }
                .start()

            if (stdin != null) {
                process.outputStream.use { it.write(stdin) }
            } else {
                process.outputStream.close()
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                return CommandResult(124, emptyList(), listOf("native command timed out"), timedOut = true)
            }
            val stdout = process.inputStream.bufferedReader().readLines()
            val stderr = process.errorStream.bufferedReader().readLines()
            CommandResult(process.exitValue(), stdout, stderr)
        } catch (e: Exception) {
            CommandResult(126, emptyList(), listOf(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun pactl(context: Context, vararg arguments: String): CommandResult =
        executeNative(context, PACTL_EXEC, arguments.toList(), control = true)

    private fun verifyCore(context: Context, logger: ContainerLogger?): Core? {
        val info = pactl(context, "info")
        if (info.code != 0) return null
        val sinks = pactl(context, "list", "short", "sinks")
        if (sinks.code != 0) return null
        val sink = when {
            sinks.stdout.any { it.split(Regex("\\s+")).contains("AAudio_sink") } -> "AAudio_sink"
            sinks.stdout.any { it.split(Regex("\\s+")).contains("OpenSL_ES_sink") } -> "OpenSL_ES_sink"
            else -> return null
        }
        val default = pactl(context, "set-default-sink", sink)
        if (default.code != 0) return null
        logger?.i("[AUDIO][NATIVE] ✓ Embedded PulseAudio core responds ($sink)")
        return Core(sink)
    }

    private fun finitePlaybackProbe(context: Context): Boolean {
        val pcm = ByteArray(48_000)
        val result = executeNative(
            context = context,
            executableName = PACAT_EXEC,
            arguments = listOf(
                "--playback", "--raw", "--format=s16le", "--rate=48000",
                "--channels=2", "--latency-msec=50",
            ),
            control = true,
            timeoutSeconds = 6,
            stdin = pcm,
        )
        return result.code == 0
    }

    private fun requestService(context: Context, action: String, backend: String? = null) {
        val intent = Intent(context, NativeAudioRuntimeService::class.java).setAction(action)
        if (backend != null) intent.putExtra(NativeAudioRuntimeService.EXTRA_BACKEND, backend)
        when (action) {
            NativeAudioRuntimeService.ACTION_START,
            NativeAudioRuntimeService.ACTION_RESTART -> ContextCompat.startForegroundService(context, intent)
            else -> context.startService(intent)
        }
    }

    suspend fun ensureCore(
        containerName: String,
        logger: ContainerLogger? = null,
    ): Boolean = runtimeMutex.withLock {
        withContext(Dispatchers.IO) {
            val context = X11Application.instance
            val layout = NativeAudioLayout.from(context)
            logger?.i("[AUDIO][NATIVE] Provider: embedded PulseAudio runtime owned by SaaS X11 Manager")
            logger?.i("[AUDIO][NATIVE] External Termux runtime: not used")

            if (!prepareStateForService(context)) {
                logger?.w("[AUDIO][NATIVE] ✗ Could not prepare app-private audio state")
                return@withContext false
            }
            if (!runtimeAvailable(layout)) {
                logger?.w("[AUDIO][NATIVE] ✗ Embedded PulseAudio files are missing for this ABI")
                requiredRuntimeFiles(layout).filterNot { it.isFile }.forEach {
                    logger?.w("[AUDIO][NATIVE] Missing: ${it.name}")
                }
                return@withContext false
            }

            verifyCore(context, null)?.let { core ->
                if (finitePlaybackProbe(context)) {
                    logger?.i("[AUDIO][NATIVE] ✓ Existing ${core.sink} core passed finite PCM playback")
                    return@withContext true
                }
                logger?.w("[AUDIO][NATIVE] Control channel is alive but PCM did not drain; restarting embedded core")
            }

            val attempts = listOf(
                NativeAudioRuntimeService.BACKEND_AAUDIO to "AAudio",
                NativeAudioRuntimeService.BACKEND_SLES to "OpenSL ES",
            )
            attempts.forEachIndexed { index, (backend, label) ->
                logger?.i("[AUDIO][NATIVE] Starting embedded PulseAudio with $label backend")
                requestService(
                    context,
                    if (index == 0) NativeAudioRuntimeService.ACTION_RESTART else NativeAudioRuntimeService.ACTION_RESTART,
                    backend,
                )
                repeat(50) {
                    delay(200)
                    val core = verifyCore(context, null)
                    if (core != null) {
                        if (finitePlaybackProbe(context)) {
                            logger?.i("[AUDIO][NATIVE] ✓ $label backend ready; finite PCM stream drained")
                            return@withContext true
                        }
                        logger?.w("[AUDIO][NATIVE] $label accepts control commands but cannot drain PCM")
                        return@repeat
                    }
                }
            }

            logger?.w("[AUDIO][NATIVE] ✗ Neither embedded Android audio backend passed playback verification")
            layout.logFile.takeIf { it.isFile }?.readLines()?.takeLast(30)?.forEach {
                if (it.isNotBlank()) logger?.w("[AUDIO][NATIVE][PA] $it")
            }
            false
        }
    }

    suspend fun configureContainer(
        containerName: String,
        logger: ContainerLogger? = null,
    ): Boolean = runtimeMutex.withLock {
        withContext(Dispatchers.IO) {
            val context = X11Application.instance
            if (!FixSettings.isPulseAudioEnabled(context, containerName)) return@withContext true
            val info = ContainerManager.getContainerInfo(containerName)
                ?: return@withContext fail(logger, "Container $containerName was not found")
            if (!info.isRunning) return@withContext fail(logger, "Container $containerName is not running")
            if (!ensureCoreUnlocked(containerName, logger)) return@withContext false

            val endpoint = resolveEndpoint(info, logger)
                ?: return@withContext fail(logger, "Could not discover a safe HOST/NAT audio endpoint")
            logger?.i("[AUDIO][NATIVE] Network: ${info.netMode.trim().lowercase()}")
            logger?.i("[AUDIO][NATIVE] Host endpoint: $endpoint (port selected dynamically)")

            val listener = selectListener(context, endpoint, logger)
                ?: return@withContext fail(logger, "Could not create an authenticated native audio listener")

            val layout = NativeAudioLayout.from(context)
            val cookie = layout.cookie.readBytes()
            if (cookie.size != 256) {
                if (listener.createdNow) unloadListener(context, listener.moduleId, logger)
                return@withContext fail(logger, "Embedded PulseAudio cookie is invalid")
            }
            val octal = cookie.joinToString(separator = "") { byte ->
                "\\0%03o".format(byte.toInt() and 0xff)
            }

            val payload = PulseAudioUnifiedTransport.buildContainerPayload(listener.server, octal)
            val command = PulseAudioContainerCommand.build(containerName, payload)
            val result = try { Shell.cmd(command).exec() } catch (e: Exception) {
                if (listener.createdNow) unloadListener(context, listener.moduleId, logger)
                return@withContext fail(logger, "DroidSpaces audio command failed: ${e.message ?: e.javaClass.simpleName}")
            }

            result.out.forEach { line ->
                when {
                    line.trim() == "__APT__" -> logger?.i("[AUDIO][NATIVE] Installing missing Debian/Ubuntu audio clients")
                    line.trim() == "__APK__" -> logger?.i("[AUDIO][NATIVE] Installing missing Alpine audio clients")
                    line.startsWith("Server String:") || line.startsWith("Server Version:") ||
                        line.startsWith("Default Sink:") || line.startsWith("Default Source:") -> logger?.i("[AUDIO][NATIVE] $line")
                }
            }
            result.err.filter { it.isNotBlank() && !PulseAudioClientConfig.isFailureMarker(it) }
                .takeLast(12).forEach { logger?.w("[AUDIO][NATIVE][CONTAINER] $it") }

            val ready = result.isSuccess && result.out.any { it.trim() == "__READY__" }
            if (!ready) {
                val failure = PulseAudioClientConfig.failureSummary(result.code, result.out + result.err)
                if (listener.createdNow) unloadListener(context, listener.moduleId, logger)
                return@withContext fail(logger, "Container audio proof failed: $failure")
            }

            NativeAudioConsumerStore.put(
                context,
                NativeAudioConsumer(containerName, listener.server, listener.moduleId),
            )
            logger?.i("[AUDIO][NATIVE] ✓ Consumer registered: $containerName")
            logger?.i("[AUDIO][NATIVE] ✓ Audio ready (${listener.server})")
            true
        }
    }

    // Called while runtimeMutex is already held by configureContainer().
    private suspend fun ensureCoreUnlocked(containerName: String, logger: ContainerLogger?): Boolean {
        val context = X11Application.instance
        val layout = NativeAudioLayout.from(context)
        if (!prepareStateForService(context) || !runtimeAvailable(layout)) {
            logger?.w("[AUDIO][NATIVE] Embedded runtime unavailable for $containerName")
            return false
        }
        verifyCore(context, null)?.let {
            if (finitePlaybackProbe(context)) return true
        }
        val attempts = listOf(
            NativeAudioRuntimeService.BACKEND_AAUDIO to "AAudio",
            NativeAudioRuntimeService.BACKEND_SLES to "OpenSL ES",
        )
        for ((backend, label) in attempts) {
            logger?.i("[AUDIO][NATIVE] Starting $label backend")
            requestService(context, NativeAudioRuntimeService.ACTION_RESTART, backend)
            repeat(50) {
                delay(200)
                if (verifyCore(context, null) != null && finitePlaybackProbe(context)) {
                    logger?.i("[AUDIO][NATIVE] ✓ $label backend verified")
                    return true
                }
            }
        }
        return false
    }

    suspend fun release(
        containerName: String,
        logger: ContainerLogger? = null,
    ) = runtimeMutex.withLock {
        withContext(Dispatchers.IO) {
            val context = X11Application.instance
            val (removed, remaining) = NativeAudioConsumerStore.remove(context, containerName)
            if (removed != null && remaining.none { it.server == removed.server }) {
                unloadListener(context, removed.moduleId, logger)
            }
            if (removed != null) logger?.i("[AUDIO][NATIVE] Released consumer: $containerName")
            if (remaining.isEmpty()) {
                logger?.i("[AUDIO][NATIVE] No active audio consumers; stopping embedded core")
                requestService(context, NativeAudioRuntimeService.ACTION_STOP_IF_IDLE)
            }
        }
    }

    suspend fun releaseAll(logger: ContainerLogger? = null) = runtimeMutex.withLock {
        withContext(Dispatchers.IO) {
            val context = X11Application.instance
            NativeAudioConsumerStore.replace(context, emptyList())
            logger?.i("[AUDIO][NATIVE] All audio consumers released")
            requestService(context, NativeAudioRuntimeService.ACTION_STOP_FORCE)
        }
    }

    internal suspend fun pruneConsumers(context: Context): Int = withContext(Dispatchers.IO) {
        val current = NativeAudioConsumerStore.snapshot(context)
        if (current.isEmpty()) return@withContext 0
        val running = ContainerManager.listContainers().associateBy { it.name }
        val kept = current.filter { consumer ->
            running[consumer.containerName]?.isRunning == true &&
                FixSettings.isPulseAudioEnabled(context, consumer.containerName)
        }
        if (kept.size != current.size) NativeAudioConsumerStore.replace(context, kept)
        kept.size
    }

    private suspend fun fail(logger: ContainerLogger?, message: String): Boolean {
        logger?.w("[AUDIO][NATIVE] ✗ $message")
        logger?.w("[AUDIO] Graphical startup will continue")
        return false
    }

    private suspend fun resolveEndpoint(info: ContainerInfo, logger: ContainerLogger?): String? =
        when (info.netMode.trim().lowercase()) {
            "host" -> "127.0.0.1"
            "nat" -> discoverNatGateway(info, logger)
            else -> null
        }

    /** Live route only: the native test branch deliberately has no fixed NAT gateway fallback. */
    private fun discoverNatGateway(info: ContainerInfo, logger: ContainerLogger?): String? {
        val pid = info.pid ?: return null
        val busybox = "${Constants.DS_BASE_DIR}/bin/busybox"
        val command = """
            pid=$pid
            gw=''
            if [ -x ${q(busybox)} ]; then
                gw=${'$'}(${q(busybox)} nsenter -t "${'$'}pid" -n ${q(busybox)} ip -4 route show default 2>/dev/null |
                    sed -n 's/^default via \([0-9.][0-9.]*\).*/\1/p' | sed -n '1p')
            fi
            if [ -z "${'$'}gw" ]; then
                hex=${'$'}(while read ifc dst g rest; do [ "${'$'}dst" = 00000000 ] || continue; printf '%s\n' "${'$'}g"; break; done < "/proc/${'$'}pid/net/route" 2>/dev/null)
                case "${'$'}hex" in
                    ????????)
                        b1=${'$'}(printf '%s' "${'$'}hex" | cut -c7-8); b2=${'$'}(printf '%s' "${'$'}hex" | cut -c5-6)
                        b3=${'$'}(printf '%s' "${'$'}hex" | cut -c3-4); b4=${'$'}(printf '%s' "${'$'}hex" | cut -c1-2)
                        gw=${'$'}(printf '%d.%d.%d.%d' "0x${'$'}b1" "0x${'$'}b2" "0x${'$'}b3" "0x${'$'}b4" 2>/dev/null || true)
                        ;;
                esac
            fi
            printf '%s\n' "${'$'}gw"
        """.trimIndent()
        val gateway = try { Shell.cmd(command).exec().out.firstOrNull()?.trim().orEmpty() } catch (_: Exception) { "" }
        if (!usableIpv4(gateway)) {
            logger?.w("[AUDIO][NATIVE] NAT default gateway was not readable; refusing a hardcoded fallback")
            return null
        }
        logger?.i("[AUDIO][NATIVE] NAT gateway discovered from live container route: $gateway")
        return gateway
    }

    private fun usableIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } &&
            value != "0.0.0.0" && value != "255.255.255.255"
    }

    private fun configuredPortForwardOwner(port: Int): String? {
        val command = """
            wanted=$port
            for cfg in ${q(Constants.CONTAINERS_DIR)}/*/${q(Constants.CONFIG_FILE)}; do
                [ -f "${'$'}cfg" ] || continue
                net=${'$'}(sed -n 's/^net_mode=//p' "${'$'}cfg" | tail -n 1)
                [ "${'$'}net" = nat ] || continue
                name=${'$'}(sed -n 's/^name=//p' "${'$'}cfg" | sed -n '1p')
                [ -n "${'$'}name" ] || { d=${'$'}{cfg%/${Constants.CONFIG_FILE}}; name=${'$'}{d##*/}; }
                value=${'$'}(sed -n 's/^port_forwards=//p' "${'$'}cfg" | sed -n '1p')
                oldifs=${'$'}IFS; IFS=,
                for tok in ${'$'}value; do
                    IFS=${'$'}oldifs
                    tok=${'$'}(printf '%s' "${'$'}tok" | tr -d '[:space:]')
                    case "${'$'}tok" in */*) proto=${'$'}{tok##*/}; body=${'$'}{tok%/*} ;; *) proto=tcp; body=${'$'}tok ;; esac
                    [ "${'$'}proto" = tcp ] || { IFS=,; continue; }
                    host=${'$'}{body%%:*}
                    case "${'$'}host" in *-*) start=${'$'}{host%-*}; end=${'$'}{host#*-} ;; *) start=${'$'}host; end=${'$'}host ;; esac
                    case "${'$'}start:${'$'}end" in *[!0-9:]*|'':*) : ;; *)
                        if [ "${'$'}wanted" -ge "${'$'}start" ] 2>/dev/null && [ "${'$'}wanted" -le "${'$'}end" ] 2>/dev/null; then printf '%s\n' "${'$'}name"; exit 0; fi ;;
                    esac
                    IFS=,
                done
                IFS=${'$'}oldifs
            done
            exit 1
        """.trimIndent()
        return try {
            val result = Shell.cmd(command).exec()
            if (result.isSuccess) result.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } else null
        } catch (_: Exception) { null }
    }

    private fun findListener(lines: List<String>, ip: String, port: Int): Int? {
        for (line in lines) {
            val fields = line.trim().split(Regex("\\s+"), limit = 3)
            val id = fields.getOrNull(0)?.toIntOrNull() ?: continue
            if (fields.getOrNull(1) != "module-native-protocol-tcp") continue
            val args = fields.getOrNull(2).orEmpty().split(Regex("\\s+")).toSet()
            if ("listen=$ip" in args && "port=$port" in args) return id
        }
        return null
    }

    private fun selectListener(context: Context, ip: String, logger: ContainerLogger?): Listener? {
        val layout = NativeAudioLayout.from(context)
        val max = (BASE_PORT + MAX_PORT_SHIFT).coerceAtMost(65535)
        for (port in BASE_PORT..max) {
            configuredPortForwardOwner(port)?.let { owner ->
                if (port == BASE_PORT) logger?.w("[AUDIO][NATIVE] Port $port reserved by DroidSpaces forward in $owner; selecting another")
                return@let
            } ?: run {
                val modules = pactl(context, "list", "short", "modules")
                if (modules.code != 0) return null
                findListener(modules.stdout, ip, port)?.let { existing ->
                    logger?.i("[AUDIO][NATIVE] Reusing authenticated listener module $existing on $ip:$port")
                    return Listener(existing, "tcp:$ip:$port", createdNow = false)
                }

                val load = pactl(
                    context,
                    "load-module", "module-native-protocol-tcp",
                    "listen=$ip", "port=$port", "auth-cookie=${layout.cookie.absolutePath}",
                )
                val id = load.stdout.firstOrNull { it.trim().all(Char::isDigit) }?.trim()?.toIntOrNull()
                if (load.code == 0 && id != null) {
                    val after = pactl(context, "list", "short", "modules")
                    if (after.code == 0 && findListener(after.stdout, ip, port) == id) {
                        logger?.i("[AUDIO][NATIVE] ✓ Listener module $id ready on $ip:$port")
                        return Listener(id, "tcp:$ip:$port", createdNow = true)
                    }
                    unloadListener(context, id, logger)
                }
                if (port == BASE_PORT) logger?.w("[AUDIO][NATIVE] Port $port unavailable; selecting another automatically")
            }
        }
        return null
    }

    private fun unloadListener(context: Context, moduleId: Int, logger: ContainerLogger?) {
        val result = pactl(context, "unload-module", moduleId.toString())
        if (result.code == 0) logger?.i("[AUDIO][NATIVE] Listener module $moduleId unloaded")
        else logger?.w("[AUDIO][NATIVE] Listener module $moduleId could not be unloaded cleanly")
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
