package com.saas.x11manager.util

import android.content.Context
import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class VirGLFixResult(
    val success: Boolean,
    val message: String
)

/**
 * Manager-owned VirGL transport.
 *
 * DroidSpaces remains responsible for the Linux container. The Manager owns the
 * Android/Termux renderer process, its private vtest socket, the stable host ->
 * guest directory bind and the guest environment. Native DroidSpaces VirGL is
 * disabled for future starts while this integration is active.
 *
 * The transport is deliberately independent from X11/audio lifecycle: a VirGL
 * failure removes the managed guest environment and graphical startup continues
 * with the distro's normal software renderer.
 */
object VirGLFixManager {
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val VIRGL_BIN = "$TERMUX_PREFIX/bin/virgl_test_server_android"

    private const val HOST_STATE_DIR = "$TERMUX_HOME/.saas-x11-manager/virgl"
    private const val HOST_RUNTIME_DIR = VirGLContainerConfig.HOST_RUNTIME_DIR
    private const val HOST_SOCKET = VirGLContainerConfig.HOST_SOCKET
    private const val HOST_PID_FILE = "$HOST_STATE_DIR/virgl.pid"
    private const val HOST_LOG_FILE = "$HOST_STATE_DIR/virgl.log"
    private const val OWNER = "SaaS X11 Manager VirGL"

    private const val GUEST_PROFILE = "/etc/profile.d/saas-x11-virgl.sh"
    private const val GUEST_SYSTEMD_DROPIN =
        "/etc/systemd/system/x11-session.service.d/80-saas-virgl.conf"
    private const val GUEST_OPENRC_CONF = "/etc/conf.d/x11-session"
    private const val BEGIN = "# BEGIN $OWNER"
    private const val END = "# END $OWNER"

    private data class TermuxRuntime(val uid: Int)
    private data class HostLease(val pid: Int, val startTime: String)

    suspend fun prepareBeforeGraphicalStart(
        containerName: String,
        logger: ContainerLogger? = null
    ): VirGLFixResult? = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        val requested = FixSettings.isVirGLEnabled(context, containerName)
        val previouslyApplied = FixSettings.isVirGLApplied(context, containerName)
        if (!requested && !previouslyApplied) return@withContext null

        logger?.i("--- VirGL Configuration ---")
        val info = ContainerManager.getContainerInfo(containerName)
            ?: return@withContext failure(logger, "Container $containerName was not found")

        if (!requested) {
            if (info.isRunning) {
                cleanupGuestLive(containerName)
                return@withContext failure(
                    logger,
                    "VirGL is disabled, but the container must stop once before its host bind/native setting can be restored"
                )
            }

            val original = originalState(context, containerName)
            val restored = original != null &&
                VirGLContainerConfig.restore(info, original, logger)
            val cleaned = cleanupGuestOffline(info)
            if (restored && cleaned) {
                FixSettings.clearVirGLRuntimeState(context, containerName)
                logger?.i("[+] Manager VirGL disabled for $containerName")
                return@withContext VirGLFixResult(true, "VirGL configuration disabled")
            }
            return@withContext failure(logger, "VirGL cleanup was not fully completed")
        }

        val config = readConfig(info.configPath)
            ?: return@withContext failure(logger, "Could not read container configuration")
        val analysis = VirGLContainerConfig.analyze(config)
        if (analysis.conflictingGuestBind) {
            return@withContext failure(
                logger,
                "A foreign bind already owns ${VirGLContainerConfig.GUEST_RUNTIME_DIR}"
            )
        }

        if (FixSettings.getVirGLOriginalState(context, containerName) == null) {
            val original = analysis.nativeState
            if (original == VirGLContainerConfig.NativeState.UNKNOWN ||
                !FixSettings.setVirGLOriginalState(context, containerName, original.name)
            ) {
                return@withContext failure(logger, "Could not save original DroidSpaces VirGL state")
            }
        }

        val savedOriginal = originalState(context, containerName)
            ?: return@withContext failure(logger, "Saved DroidSpaces VirGL state is unavailable")

        if (info.isRunning && !analysis.hasManagedBind) {
            return@withContext failure(
                logger,
                "VirGL was enabled after $containerName started; stop it once so the private socket directory can be mounted"
            )
        }

        val runtime = detectTermuxRuntime()
            ?: return@withContext failure(logger, "Termux was not detected")
        val host = ensureHostRuntime(runtime, logger)
            ?: return@withContext failure(
                logger,
                "Manager-owned VirGL renderer could not be started; container graphics were left unchanged"
            )

        if (!info.isRunning) {
            if (!VirGLContainerConfig.apply(info, logger)) {
                return@withContext failure(
                    logger,
                    "Could not apply the private VirGL bridge to container.config"
                )
            }
            if (!installGuestOffline(info)) {
                VirGLContainerConfig.restore(info, savedOriginal, logger)
                cleanupGuestOffline(info)
                return@withContext failure(
                    logger,
                    "Could not provision VirGL environment before container boot; configuration was rolled back"
                )
            }
        }

        if (!FixSettings.setVirGLApplied(context, containerName, true)) {
            if (!info.isRunning) {
                VirGLContainerConfig.restore(info, savedOriginal, logger)
                cleanupGuestOffline(info)
            }
            return@withContext failure(logger, "Could not save Manager VirGL state")
        }

        logger?.i("[+] VirGL host renderer ready (PID=${host.pid})")
        logger?.i("[+] Private vtest socket: $HOST_SOCKET")
        VirGLFixResult(true, "VirGL host renderer ready")
    }

    suspend fun finalizeAfterContainerReady(
        containerName: String,
        logger: ContainerLogger? = null
    ): VirGLFixResult? = withContext(Dispatchers.IO) {
        val context = X11Application.instance
        if (!FixSettings.isVirGLEnabled(context, containerName)) return@withContext null

        val info = ContainerManager.getContainerInfo(containerName)
            ?: return@withContext failure(logger, "Container $containerName was not found")
        if (!info.isRunning) {
            return@withContext failure(logger, "Container is not running; VirGL guest transport was not changed")
        }

        val runtime = detectTermuxRuntime()
            ?: return@withContext fallback(logger, containerName, "Termux was not detected")
        val host = ensureHostRuntime(runtime, logger)
            ?: return@withContext fallback(logger, containerName, "VirGL host renderer is unavailable")

        if (!guestSocketVisible(containerName)) {
            return@withContext fallback(
                logger,
                containerName,
                "Private VirGL socket is not visible at ${VirGLContainerConfig.GUEST_SOCKET}; stop the container once if this fix was just enabled"
            )
        }

        if (!installGuestEnvironment(containerName, logger)) {
            return@withContext fallback(logger, containerName, "Could not install VirGL guest environment")
        }

        when (probeGuestRenderer(containerName)) {
            GuestProbe.VIRGL -> logger?.i("[+] Guest OpenGL renderer confirmed through VirGL/virpipe")
            GuestProbe.UNAVAILABLE ->
                logger?.i("[CTX] glxinfo is not installed; socket and persistent virpipe environment are ready")
            GuestProbe.FAILED ->
                return@withContext fallback(
                    logger,
                    containerName,
                    "A real guest GL probe rejected VirGL; reverting this session to software rendering"
                )
        }

        logger?.i("[+] VirGL ready for $containerName (host PID=${host.pid})")
        VirGLFixResult(true, "VirGL transport ready")
    }

    private enum class GuestProbe { VIRGL, UNAVAILABLE, FAILED }

    private suspend fun fallback(
        logger: ContainerLogger?,
        containerName: String,
        message: String
    ): VirGLFixResult {
        cleanupGuestLive(containerName)
        return failure(logger, message)
    }

    private suspend fun failure(
        logger: ContainerLogger?,
        message: String
    ): VirGLFixResult {
        logger?.w("[VIRGL] ! $message")
        logger?.w("[VIRGL] ! Graphical startup will continue with the normal renderer")
        return VirGLFixResult(false, message)
    }

    private fun originalState(
        context: Context,
        containerName: String
    ): VirGLContainerConfig.NativeState? =
        FixSettings.getVirGLOriginalState(context, containerName)
            ?.let { saved ->
                VirGLContainerConfig.NativeState.values().firstOrNull {
                    it.name == saved
                }
            }

    private fun readConfig(configPath: String): List<String>? = try {
        val result = Shell.cmd("cat ${q(configPath)} 2>/dev/null").exec()
        if (result.isSuccess && result.out.isNotEmpty()) result.out.toList() else null
    } catch (_: Exception) {
        null
    }

    private fun detectTermuxRuntime(): TermuxRuntime? {
        val command =
            "test -x ${q("$TERMUX_PREFIX/bin/sh")} && " +
                "uid=\$(stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null || " +
                "toybox stat -c '%u' ${q(TERMUX_HOME)} 2>/dev/null); " +
                "case \"\$uid\" in ''|*[!0-9]*) exit 1 ;; esac; printf '%s\\n' \"\$uid\""
        return try {
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) null
            else result.out.firstOrNull()?.trim()?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let(::TermuxRuntime)
        } catch (_: Exception) {
            null
        }
    }

    private fun prepareHostState(runtime: TermuxRuntime): Boolean {
        val managerDir = "$TERMUX_HOME/.saas-x11-manager"
        val command = """
            for dir in ${q(managerDir)} ${q(HOST_STATE_DIR)} ${q(HOST_RUNTIME_DIR)}; do
                [ ! -L "${'$'}dir" ] || exit 20
                mkdir -p "${'$'}dir" || exit 21
                owner=${'$'}(stat -c '%u' "${'$'}dir" 2>/dev/null || toybox stat -c '%u' "${'$'}dir" 2>/dev/null) || exit 22
                case "${'$'}owner" in 0|${runtime.uid}) ;; *) exit 23 ;; esac
                chown ${runtime.uid}:${runtime.uid} "${'$'}dir" || exit 24
            done
            chmod 700 ${q(managerDir)} ${q(HOST_STATE_DIR)} || exit 25
            chmod 1777 ${q(HOST_RUNTIME_DIR)} || exit 26
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun supportsPrivateSocket(): Boolean = try {
        val result = Shell.cmd(
            "${q(VIRGL_BIN)} --help 2>&1 | grep -Fq -- '--socket-path'"
        ).exec()
        result.isSuccess
    } catch (_: Exception) {
        false
    }

    /**
     * Package installation is deliberately kept out of graphical startup.
     * The normal DroidSpaces Termux setup owns dependencies; Start either
     * finds a usable renderer immediately or fails fast.
     */
    private suspend fun ensureVirGLPackage(logger: ContainerLogger?): Boolean {
        val present = try {
            Shell.cmd("test -x ${q(VIRGL_BIN)}").exec().isSuccess
        } catch (_: Exception) {
            false
        }
        if (!present) {
            logger?.w("[VIRGL] ! virgl_test_server_android is not installed in Termux")
            logger?.w("[VIRGL] ! Install DroidSpaces Termux dependencies before enabling 3D acceleration")
            return false
        }
        if (!supportsPrivateSocket()) {
            logger?.w("[VIRGL] ! Installed virglrenderer-android has no --socket-path support")
            return false
        }
        logger?.i("[VIRGL] ✓ Termux VirGL renderer binary is available")
        return true
    }
    private fun processStartTime(pid: Int): String? = try {
        val result = Shell.cmd("cat /proc/$pid/stat 2>/dev/null").exec()
        if (!result.isSuccess) null
        else X11SessionManager.parseProcStartTime(result.out.joinToString(" "))
    } catch (_: Exception) {
        null
    }

    private fun readLease(): HostLease? {
        return try {
            val result = Shell.cmd("cat ${q(HOST_PID_FILE)} 2>/dev/null").exec()
            if (!result.isSuccess) return null

            val values = result.out.mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
            if (values["owner"] != OWNER) return null

            val pid = values["pid"]?.toIntOrNull()?.takeIf { it > 1 } ?: return null
            val start = values["start"]?.takeIf(String::isNotBlank) ?: return null
            HostLease(pid, start)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeLease(runtime: TermuxRuntime, lease: HostLease): Boolean = try {
        @Suppress("UNUSED_VARIABLE")
        val termuxUid = runtime.uid
        val temp = "$HOST_PID_FILE.tmp.${android.os.Process.myPid()}"
        val result = Shell.cmd(
            "printf '%s\\n' ${q("owner=$OWNER")} ${q("pid=${lease.pid}")} " +
                "${q("start=${lease.startTime}")} > ${q(temp)} && " +
                "chown 0:0 ${q(temp)} && chmod 600 ${q(temp)} && " +
                "mv -f ${q(temp)} ${q(HOST_PID_FILE)}"
        ).exec()
        result.isSuccess
    } catch (_: Exception) {
        false
    }

    private fun ownedIdentity(runtime: TermuxRuntime, lease: HostLease): Boolean {
        if (processStartTime(lease.pid) != lease.startTime) return false
        val command = """
            pid=${lease.pid}
            [ -r "/proc/${'$'}pid/status" ] && [ -r "/proc/${'$'}pid/cmdline" ] || exit 1
            uid=${'$'}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/${'$'}pid/status" | sed -n '1p')
            [ "${'$'}uid" = 0 ] || exit 1
            cmd=${'$'}(tr '\\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true)
            case " ${'$'}cmd " in
                *${q(VIRGL_BIN)}*--socket-path*${q(HOST_SOCKET)}*) exit 0 ;;
                *) exit 1 ;;
            esac
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }
    private fun kernelSocketLive(path: String): Boolean = try {
        val result = Shell.cmd("cat /proc/net/unix 2>/dev/null").exec()
        result.isSuccess &&
            (UnixSocketTableParser.findInode(result.out, path) != null ||
                UnixSocketTableParser.findInode(result.out, "@$path") != null)
    } catch (_: Exception) {
        false
    }

    private fun ownedHostReady(runtime: TermuxRuntime): HostLease? {
        val lease = readLease() ?: return null
        if (!ownedIdentity(runtime, lease)) return null
        val socketFile = try {
            Shell.cmd("test -S ${q(HOST_SOCKET)}").exec().isSuccess
        } catch (_: Exception) {
            false
        }
        return lease.takeIf { socketFile && kernelSocketLive(HOST_SOCKET) }
    }

    private suspend fun stopOwnedHost(runtime: TermuxRuntime): Boolean {
        val lease = readLease() ?: run {
            if (!kernelSocketLive(HOST_SOCKET)) {
                Shell.cmd("rm -f ${q(HOST_SOCKET)} ${q(HOST_PID_FILE)} 2>/dev/null || true").exec()
                return true
            }
            return false
        }
        if (!ownedIdentity(runtime, lease)) {
            if (!kernelSocketLive(HOST_SOCKET)) {
                Shell.cmd("rm -f ${q(HOST_SOCKET)} ${q(HOST_PID_FILE)} 2>/dev/null || true").exec()
                return true
            }
            return false
        }

        Shell.cmd("kill ${lease.pid} 2>/dev/null || true").exec()
        delay(200)
        if (processStartTime(lease.pid) == lease.startTime) {
            Shell.cmd("kill -9 ${lease.pid} 2>/dev/null || true").exec()
            delay(50)
        }
        if (processStartTime(lease.pid) == lease.startTime) return false

        if (!kernelSocketLive(HOST_SOCKET)) {
            Shell.cmd("rm -f ${q(HOST_SOCKET)} ${q(HOST_PID_FILE)} 2>/dev/null || true").exec()
        }
        return true
    }

    private suspend fun startHost(runtime: TermuxRuntime, logger: ContainerLogger?): HostLease? {
        val command = """
            : > ${q(HOST_LOG_FILE)} || exit 40
            rm -f ${q(HOST_SOCKET)} 2>/dev/null || true

            (
                trap '' HUP INT QUIT PIPE
                echo -1000 > /proc/self/oom_score_adj 2>/dev/null || true

                before=${'$'}(cat /proc/self/attr/current 2>/dev/null || true)
                printf '%s' 'u:r:droidspacesd:s0' > /proc/self/attr/current 2>/dev/null || true
                after=${'$'}(cat /proc/self/attr/current 2>/dev/null || true)
                printf '[VirGL] uid=%s context_before=%s context_after=%s\\n' \
                    "${'$'}(id -u)" "${'$'}before" "${'$'}after" >> ${q(HOST_LOG_FILE)}

                export HOME=${q(TERMUX_HOME)}
                export PREFIX=${q(TERMUX_PREFIX)}
                export TMPDIR=${q("$TERMUX_PREFIX/tmp")}
                export PATH=${q("$TERMUX_PREFIX/bin:/system/bin:/system/xbin")}
                exec ${q(VIRGL_BIN)} --socket-path ${q(HOST_SOCKET)}
            ) >>${q(HOST_LOG_FILE)} 2>&1 </dev/null &

            printf '%s\\n' "${'$'}!"
        """.trimIndent()

        val result = try {
            Shell.cmd(command).exec()
        } catch (e: Exception) {
            logger?.w("[VIRGL] ! Renderer launch failed: ${e.message ?: e.javaClass.simpleName}")
            return null
        }
        if (!result.isSuccess) {
            result.err.filter(String::isNotBlank).takeLast(8).forEach {
                logger?.w("[VIRGL] ! $it")
            }
            return null
        }

        val pid = result.out.asReversed().firstNotNullOfOrNull {
            it.trim().toIntOrNull()?.takeIf { value -> value > 1 }
        } ?: return null
        val start = processStartTime(pid) ?: return null
        val lease = HostLease(pid, start)
        if (!writeLease(runtime, lease)) {
            Shell.cmd("kill $pid 2>/dev/null || true").exec()
            return null
        }
        return lease
    }
    private fun waitForHostSocket(lease: HostLease): Boolean {
        val path = q(HOST_SOCKET)
        val needle = q(" $HOST_SOCKET")
        val result = try {
            Shell.cmd(
                "i=0; while [ \"${'$'}i\" -lt 20 ]; do " +
                    "[ -S $path ] && grep -Fq $needle /proc/net/unix 2>/dev/null && exit 0; " +
                    "kill -0 ${lease.pid} 2>/dev/null || exit 2; " +
                    "i=${'$'}((i + 1)); sleep 0.1; done; exit 1"
            ).exec()
        } catch (_: Exception) {
            return false
        }
        if (result.isSuccess) {
            try {
                Shell.cmd("chmod 666 ${q(HOST_SOCKET)} 2>/dev/null || true").exec()
            } catch (_: Exception) {
            }
        }
        return result.isSuccess
    }
    private suspend fun ensureHostRuntime(
        runtime: TermuxRuntime,
        logger: ContainerLogger?
    ): HostLease? {
        val totalStarted = System.nanoTime()
        logger?.i("[VIRGL] • Preflight: conventional DroidSpaces/Termux renderer")
        if (!ensureVirGLPackage(logger)) return null
        if (!prepareHostState(runtime)) return null

        ownedHostReady(runtime)?.let {
            logger?.i("[VIRGL] ✓ Reusing renderer PID=${it.pid} in ${(System.nanoTime() - totalStarted) / 1_000_000L}ms")
            return it
        }

        if (kernelSocketLive(HOST_SOCKET) && readLease() == null) {
            logger?.w("[!] Private VirGL socket is live without a Manager lease; refusing to start a second renderer")
            return null
        }

        if (!stopOwnedHost(runtime)) {
            logger?.w("[!] Existing VirGL renderer ownership could not be confirmed")
            return null
        }

        logger?.i("[VIRGL] • Launching renderer as root with DroidSpaces SELinux context")
        val launchStarted = System.nanoTime()
        val lease = startHost(runtime, logger) ?: return null
        if (!waitForHostSocket(lease) || !ownedIdentity(runtime, lease)) {
            logger?.w("[VIRGL] ! Renderer did not publish its private vtest socket within 2000ms")
            stopOwnedHost(runtime)
            tailHostLog(logger)
            return null
        }

        logger?.i("[VIRGL] ✓ Private vtest socket is live in ${(System.nanoTime() - launchStarted) / 1_000_000L}ms")
        logger?.i("[VIRGL] • Host preparation time: ${(System.nanoTime() - totalStarted) / 1_000_000L}ms")
        return lease
    }

    private suspend fun tailHostLog(logger: ContainerLogger?) {
        if (logger == null) return
        val result = try {
            Shell.cmd("tail -n 30 ${q(HOST_LOG_FILE)} 2>/dev/null || true").exec()
        } catch (_: Exception) {
            return
        }
        result.out.filter(String::isNotBlank).forEach { logger.w("[VIRGL] $it") }
    }

    private fun guestSocketVisible(containerName: String): Boolean {
        val payload =
            "test -S ${q(VirGLContainerConfig.GUEST_SOCKET)} && " +
                "printf '%s\\n' __SAAS_VIRGL_SOCKET_READY__"
        val result = runContainer(containerName, payload)
        return result.isSuccess &&
            result.out.any { it.trim() == "__SAAS_VIRGL_SOCKET_READY__" }
    }

    private fun guestEnvironmentPayload(install: Boolean): String {
        val guestSocket = VirGLContainerConfig.GUEST_SOCKET
        if (!install) {
            return """
                profile=${q(GUEST_PROFILE)}
                dropin=${q(GUEST_SYSTEMD_DROPIN)}
                conf=${q(GUEST_OPENRC_CONF)}
                rm -f "${'$'}profile" "${'$'}dropin" 2>/dev/null || true
                if [ -f "${'$'}conf" ]; then
                    sed '/^# BEGIN SaaS X11 Manager VirGL${'$'}/,/^# END SaaS X11 Manager VirGL${'$'}/d' "${'$'}conf" > "${'$'}conf.saas-virgl.tmp" &&
                        mv "${'$'}conf.saas-virgl.tmp" "${'$'}conf"
                fi
                command -v systemctl >/dev/null 2>&1 && systemctl daemon-reload >/dev/null 2>&1 || true
            """.trimIndent()
        }

        return """
            test -S ${q(guestSocket)} || exit 70
            mkdir -p /etc/profile.d || exit 71
            cat > ${q(GUEST_PROFILE)} <<'EOF_SAAS_VIRGL_PROFILE'
            $BEGIN
            export GALLIUM_DRIVER=virpipe
            export VTEST_SOCKET_NAME=$guestSocket
            $END
            EOF_SAAS_VIRGL_PROFILE
            chmod 644 ${q(GUEST_PROFILE)} 2>/dev/null || true

            if command -v systemctl >/dev/null 2>&1 && [ -f /etc/systemd/system/x11-session.service ]; then
                mkdir -p /etc/systemd/system/x11-session.service.d || exit 72
                cat > ${q(GUEST_SYSTEMD_DROPIN)} <<'EOF_SAAS_VIRGL_SYSTEMD'
            [Service]
            Environment=GALLIUM_DRIVER=virpipe
            Environment=VTEST_SOCKET_NAME=$guestSocket
            EOF_SAAS_VIRGL_SYSTEMD
                systemctl daemon-reload >/dev/null 2>&1 || true
            fi

            if [ -x /etc/init.d/x11-session ]; then
                mkdir -p /etc/conf.d || exit 73
                conf=${q(GUEST_OPENRC_CONF)}
                [ -f "${'$'}conf" ] || : > "${'$'}conf"
                sed '/^# BEGIN SaaS X11 Manager VirGL${'$'}/,/^# END SaaS X11 Manager VirGL${'$'}/d' "${'$'}conf" > "${'$'}conf.saas-virgl.tmp" || exit 74
                cat >> "${'$'}conf.saas-virgl.tmp" <<'EOF_SAAS_VIRGL_OPENRC'
            $BEGIN
            export GALLIUM_DRIVER=virpipe
            export VTEST_SOCKET_NAME=$guestSocket
            $END
            EOF_SAAS_VIRGL_OPENRC
                mv "${'$'}conf.saas-virgl.tmp" "${'$'}conf"
            fi
            printf '%s\n' __SAAS_VIRGL_ENV_READY__
        """.trimIndent()
    }

    private suspend fun installGuestEnvironment(
        containerName: String,
        logger: ContainerLogger?
    ): Boolean {
        val result = runContainer(containerName, guestEnvironmentPayload(install = true))
        if (!result.isSuccess) {
            result.err.filter(String::isNotBlank).takeLast(10).forEach {
                logger?.w("[VIRGL] $it")
            }
            return false
        }
        return result.out.any { it.trim() == "__SAAS_VIRGL_ENV_READY__" }
    }

    private suspend fun cleanupGuestLive(containerName: String): Boolean {
        val info = ContainerManager.getContainerInfo(containerName) ?: return false
        if (!info.isRunning) return true
        return runContainer(containerName, guestEnvironmentPayload(install = false)).isSuccess
    }

    private fun installGuestOffline(info: ContainerInfo): Boolean =
        RootfsAccessor.use(info.rootfsPath, "virgl_prepare_${info.name}") { root ->
            val profile = "$root$GUEST_PROFILE"
            val dropin = "$root$GUEST_SYSTEMD_DROPIN"
            val systemdService = "$root/etc/systemd/system/x11-session.service"
            val openRcInit = "$root/etc/init.d/x11-session"
            val openRc = "$root$GUEST_OPENRC_CONF"
            val guestSocket = VirGLContainerConfig.GUEST_SOCKET
            val command = """
                mkdir -p ${q("$root/etc/profile.d")} || exit 61
                cat > ${q(profile)} <<'EOF_SAAS_VIRGL_PROFILE'
                $BEGIN
                export GALLIUM_DRIVER=virpipe
                export VTEST_SOCKET_NAME=$guestSocket
                $END
                EOF_SAAS_VIRGL_PROFILE
                chmod 644 ${q(profile)} 2>/dev/null || true

                if [ -f ${q(systemdService)} ]; then
                    mkdir -p ${q("$root/etc/systemd/system/x11-session.service.d")} || exit 62
                    cat > ${q(dropin)} <<'EOF_SAAS_VIRGL_SYSTEMD'
                [Service]
                Environment=GALLIUM_DRIVER=virpipe
                Environment=VTEST_SOCKET_NAME=$guestSocket
                EOF_SAAS_VIRGL_SYSTEMD
                fi

                if [ -x ${q(openRcInit)} ]; then
                    mkdir -p ${q("$root/etc/conf.d")} || exit 63
                    [ -f ${q(openRc)} ] || : > ${q(openRc)}
                    sed '/^# BEGIN SaaS X11 Manager VirGL$/ ,/^# END SaaS X11 Manager VirGL$/d' ${q(openRc)} > ${q("$openRc.saas-virgl.tmp")} || exit 64
                    cat >> ${q("$openRc.saas-virgl.tmp")} <<'EOF_SAAS_VIRGL_OPENRC'
                $BEGIN
                export GALLIUM_DRIVER=virpipe
                export VTEST_SOCKET_NAME=$guestSocket
                $END
                EOF_SAAS_VIRGL_OPENRC
                    mv ${q("$openRc.saas-virgl.tmp")} ${q(openRc)}
                fi
                true
            """.trimIndent()
                .replace("VirGL$/ ,", "VirGL$/,")

            try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
        } ?: false

    private fun cleanupGuestOffline(info: ContainerInfo): Boolean =
        RootfsAccessor.use(info.rootfsPath, "virgl_cleanup_${info.name}") { root ->
            val profile = "$root$GUEST_PROFILE"
            val dropin = "$root$GUEST_SYSTEMD_DROPIN"
            val openRc = "$root$GUEST_OPENRC_CONF"
            val command = """
                rm -f ${q(profile)} ${q(dropin)} 2>/dev/null || true
                if [ -f ${q(openRc)} ]; then
                    sed '/^# BEGIN SaaS X11 Manager VirGL$/,/^# END SaaS X11 Manager VirGL$/d' ${q(openRc)} > ${q("$openRc.saas-virgl.tmp")} &&
                        mv ${q("$openRc.saas-virgl.tmp")} ${q(openRc)}
                fi
                true
            """.trimIndent()
            try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
        } ?: false

    private fun probeGuestRenderer(containerName: String): GuestProbe {
        val socket = VirGLContainerConfig.GUEST_SOCKET
        val payload = """
            command -v glxinfo >/dev/null 2>&1 || { printf '%s\n' __SAAS_VIRGL_PROBE_UNAVAILABLE__; exit 0; }
            ${X11SessionCommands.socketSetup()}
            out=${'$'}(DISPLAY=:0 GALLIUM_DRIVER=virpipe VTEST_SOCKET_NAME=${q(socket)} timeout 8 glxinfo -B 2>&1) || {
                printf '%s\n' "${'$'}out" >&2
                exit 80
            }
            printf '%s\n' "${'$'}out" | grep -Ei 'OpenGL renderer string:.*(virgl|virpipe)' >/dev/null 2>&1 || {
                printf '%s\n' "${'$'}out" >&2
                exit 81
            }
            printf '%s\n' __SAAS_VIRGL_RENDERER_READY__
        """.trimIndent()
        val result = runContainer(containerName, payload)
        return when {
            result.out.any { it.trim() == "__SAAS_VIRGL_RENDERER_READY__" } -> GuestProbe.VIRGL
            result.isSuccess &&
                result.out.any { it.trim() == "__SAAS_VIRGL_PROBE_UNAVAILABLE__" } -> GuestProbe.UNAVAILABLE
            else -> GuestProbe.FAILED
        }
    }

    private fun runContainer(containerName: String, payload: String) = Shell.cmd(
        "${Constants.DS_BINARY_PATH} --name=${q(containerName)} run " +
            "sh -c ${q(payload)}"
    ).exec()

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
