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

        val runtime = detectTermuxRuntime()
            ?: return@withContext failure(logger, "Termux was not detected")
        if (!prepareHostState(runtime)) {
            return@withContext failure(logger, "Could not prepare the private VirGL host runtime")
        }

        if (info.isRunning && !analysis.hasManagedBind) {
            return@withContext failure(
                logger,
                "VirGL was enabled after $containerName started; stop it once so the private socket directory can be mounted"
            )
        }

        if (!info.isRunning && !VirGLContainerConfig.apply(info, logger)) {
            return@withContext failure(logger, "Could not apply the private VirGL bridge to container.config")
        }

        if (!FixSettings.setVirGLApplied(context, containerName, true)) {
            return@withContext failure(logger, "Could not save Manager VirGL state")
        }

        val host = ensureHostRuntime(runtime, logger)
        if (host == null) {
            return@withContext failure(logger, "Manager-owned VirGL renderer could not be started")
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
                [ ! -L "${KD}dir" ] || exit 20
                mkdir -p "${KD}dir" || exit 21
                owner=${KD}(stat -c '%u' "${KD}dir" 2>/dev/null || toybox stat -c '%u' "${KD}dir" 2>/dev/null) || exit 22
                case "${KD}owner" in 0|${runtime.uid}) ;; *) exit 23 ;; esac
                chown ${runtime.uid}:${runtime.uid} "${KD}dir" || exit 24
            done
            chmod 700 ${q(managerDir)} ${q(HOST_STATE_DIR)} || exit 25
            chmod 1777 ${q(HOST_RUNTIME_DIR)} || exit 26
        """.trimIndent()
        return try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
    }

    private fun runAsTermux(
        runtime: TermuxRuntime,
        command: String
    ) = Shell.cmd(
        "su ${runtime.uid} -c " + q(
            "export LC_ALL=C; " +
                "export HOME=$TERMUX_HOME; " +
                "export PREFIX=$TERMUX_PREFIX; " +
                "export TMPDIR=$TERMUX_PREFIX/tmp; " +
                "export PATH=$TERMUX_PREFIX/bin:/system/bin:/system/xbin; " +
                command
        )
    ).exec()

    private fun ensureVirGLPackage(runtime: TermuxRuntime, logger: ContainerLogger?): Boolean {
        try {
            if (runAsTermux(runtime, "test -x ${q(VIRGL_BIN)}").isSuccess) return true
        } catch (_: Exception) {
        }

        logger?.i("[*] Installing Termux virglrenderer-android...")
        val command =
            "pkg install -y x11-repo >/dev/null 2>&1 || true; " +
                "pkg install -y virglrenderer-android >/dev/null 2>&1 || " +
                "{ pkg update -y >/dev/null 2>&1 && " +
                "pkg install -y virglrenderer-android >/dev/null 2>&1; }; " +
                "test -x ${q(VIRGL_BIN)}"
        return try { runAsTermux(runtime, command).isSuccess } catch (_: Exception) { false }
    }

    private fun processStartTime(pid: Int): String? = try {
        val result = Shell.cmd("cat /proc/$pid/stat 2>/dev/null").exec()
        if (!result.isSuccess) null
        else X11SessionManager.parseProcStartTime(result.out.joinToString(" "))
    } catch (_: Exception) {
        null
    }

    private fun readLease(): HostLease? = try {
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

    private fun writeLease(runtime: TermuxRuntime, lease: HostLease): Boolean = try {
        val temp = "$HOST_PID_FILE.tmp.${android.os.Process.myPid()}"
        val result = Shell.cmd(
            "printf '%s\\n' ${q("owner=$OWNER")} ${q("pid=${lease.pid}")} " +
                "${q("start=${lease.startTime}")} > ${q(temp)} && " +
                "chown ${runtime.uid}:${runtime.uid} ${q(temp)} && chmod 600 ${q(temp)} && " +
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
            [ -r "/proc/${KD}pid/status" ] && [ -r "/proc/${KD}pid/cmdline" ] || exit 1
            uid=${KD}(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/${KD}pid/status" | sed -n '1p')
            [ "${KD}uid" = ${runtime.uid} ] || exit 1
            cmd=${KD}(tr '\000' ' ' < "/proc/${KD}pid/cmdline" 2>/dev/null || true)
            case " ${KD}cmd " in
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
        if (!ownedIdentity(runtime, lease)) return false

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

    private fun startHost(runtime: TermuxRuntime): HostLease? {
        val result = try {
            runAsTermux(
                runtime,
                ": > ${q(HOST_LOG_FILE)} || exit 40; " +
                    "rm -f ${q(HOST_SOCKET)} 2>/dev/null || true; " +
                    "nohup ${q(VIRGL_BIN)} --socket-path ${q(HOST_SOCKET)} " +
                    "</dev/null >${q(HOST_LOG_FILE)} 2>&1 & printf '%s\\n' ${KD}!"
            )
        } catch (_: Exception) {
            return null
        }
        if (!result.isSuccess) return null

        val pid = result.out.asReversed().firstNotNullOfOrNull {
            it.trim().toIntOrNull()?.takeIf { value -> value > 1 }
        } ?: return null
        val start = processStartTime(pid) ?: return null
        val lease = HostLease(pid, start)
        if (!writeLease(runtime, lease)) {
            Shell.cmd("kill ${KD}pid 2>/dev/null || true").exec()
            return null
        }
        return lease
    }

    private fun waitForHostSocket(lease: HostLease): Boolean {
        val path = q(HOST_SOCKET)
        val needle = q(" $HOST_SOCKET")
        val result = try {
            Shell.cmd(
                "i=0; while [ \"${KD}i\" -lt 50 ]; do " +
                    "[ -S ${KD}path ] && grep -Fq ${KD}needle /proc/net/unix 2>/dev/null && exit 0; " +
                    "kill -0 ${lease.pid} 2>/dev/null || exit 2; " +
                    "i=${KD}((i + 1)); sleep 0.1; done; exit 1"
            ).exec()
        } catch (_: Exception) {
            return false
        }
        if (result.isSuccess) {
            try { Shell.cmd("chmod 666 ${q(HOST_SOCKET)} 2>/dev/null || true").exec() } catch (_: Exception) { }
        }
        return result.isSuccess
    }

    private suspend fun ensureHostRuntime(
        runtime: TermuxRuntime,
        logger: ContainerLogger?
    ): HostLease? {
        if (!ensureVirGLPackage(runtime, logger)) return null
        if (!prepareHostState(runtime)) return null

        ownedHostReady(runtime)?.let {
            logger?.i("[+] Reusing Manager-owned VirGL renderer (PID=${it.pid})")
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

        logger?.i("[*] Starting Manager-owned virgl_test_server_android...")
        val lease = startHost(runtime) ?: return null
        if (!waitForHostSocket(lease) || !ownedIdentity(runtime, lease)) {
            logger?.w("[!] VirGL renderer did not publish its private vtest socket")
            stopOwnedHost(runtime)
            tailHostLog(logger)
            return null
        }

        logger?.i("[+] Private VirGL vtest socket is live")
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
                rm -f "${KD}profile" "${KD}dropin" 2>/dev/null || true
                if [ -f "${KD}conf" ]; then
                    sed '/^# BEGIN SaaS X11 Manager VirGL${KD}/,/^# END SaaS X11 Manager VirGL${KD}/d' "${KD}conf" > "${KD}conf.saas-virgl.tmp" &&
                        mv "${KD}conf.saas-virgl.tmp" "${KD}conf"
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
                [ -f "${KD}conf" ] || : > "${KD}conf"
                sed '/^# BEGIN SaaS X11 Manager VirGL${KD}/,/^# END SaaS X11 Manager VirGL${KD}/d' "${KD}conf" > "${KD}conf.saas-virgl.tmp" || exit 74
                cat >> "${KD}conf.saas-virgl.tmp" <<'EOF_SAAS_VIRGL_OPENRC'
            $BEGIN
            export GALLIUM_DRIVER=virpipe
            export VTEST_SOCKET_NAME=$guestSocket
            $END
            EOF_SAAS_VIRGL_OPENRC
                mv "${KD}conf.saas-virgl.tmp" "${KD}conf"
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

    private fun cleanupGuestOffline(info: ContainerInfo): Boolean =
        RootfsAccessor.use(info.rootfsPath, "virgl_cleanup_${info.name}") { root ->
            val profile = "$root$GUEST_PROFILE"
            val dropin = "$root$GUEST_SYSTEMD_DROPIN"
            val openRc = "$root$GUEST_OPENRC_CONF"
            val command = """
                rm -f ${q(profile)} ${q(dropin)} 2>/dev/null || true
                if [ -f ${q(openRc)} ]; then
                    sed '/^# BEGIN SaaS X11 Manager VirGL$/ ,/^# END SaaS X11 Manager VirGL$/d' ${q(openRc)} > ${q("$openRc.saas-virgl.tmp")} &&
                        mv ${q("$openRc.saas-virgl.tmp")} ${q(openRc)}
                fi
                true
            """.trimIndent()
                .replace("VirGL$/ ,", "VirGL$/ ,")
            try { Shell.cmd(command).exec().isSuccess } catch (_: Exception) { false }
        } ?: false

    private fun probeGuestRenderer(containerName: String): GuestProbe {
        val socket = VirGLContainerConfig.GUEST_SOCKET
        val payload = """
            command -v glxinfo >/dev/null 2>&1 || { printf '%s\n' __SAAS_VIRGL_PROBE_UNAVAILABLE__; exit 0; }
            ${X11SessionCommands.socketSetup()}
            out=${KD}(DISPLAY=:0 GALLIUM_DRIVER=virpipe VTEST_SOCKET_NAME=${q(socket)} timeout 8 glxinfo -B 2>&1) || {
                printf '%s\n' "${KD}out" >&2
                exit 80
            }
            printf '%s\n' "${KD}out" | grep -Ei 'OpenGL renderer string:.*(virgl|virpipe)' >/dev/null 2>&1 || {
                printf '%s\n' "${KD}out" >&2
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
