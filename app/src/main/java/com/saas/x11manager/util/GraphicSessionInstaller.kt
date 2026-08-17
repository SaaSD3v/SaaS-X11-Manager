package com.saas.x11manager.util

import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

internal data class GraphicSessionInstallStep(
    val title: String,
    val command: String
)

private data class ContainerOperationLease(
    val restoreStoppedState: Boolean
)

/**
 * Installs, provisions and verifies a graphical session inside a container.
 * Package mechanics are selected by runtime capability (apk or apt/dpkg), not
 * by pinned distro versions. The user-selected init backend remains independent
 * and is only validated/provisioned after package setup succeeds.
 */
object GraphicSessionInstaller {

    internal fun stepsFor(plan: GraphicSessionInstallPlan): List<GraphicSessionInstallStep> {
        if (plan.session != GraphicSession.OPENBOX) return emptyList()

        val packageSteps = when (plan.platform) {
            ContainerPlatform.ALPINE -> listOf(
                GraphicSessionInstallStep(
                    title = "Validating Alpine package manager",
                    command = "command -v apk >/dev/null"
                ),
                GraphicSessionInstallStep(
                    title = "Refreshing package index",
                    command = "apk update"
                ),
                GraphicSessionInstallStep(
                    title = "Installing Openbox",
                    command = "apk add openbox"
                ),
                GraphicSessionInstallStep(
                    title = "Installing terminal",
                    command = "apk add xterm"
                ),
                GraphicSessionInstallStep(
                    title = "Installing fonts",
                    command = "apk add font-terminus"
                )
            )

            ContainerPlatform.UBUNTU -> listOf(
                GraphicSessionInstallStep(
                    title = "Validating Debian package manager",
                    command = "command -v apt-get >/dev/null && command -v dpkg >/dev/null"
                ),
                GraphicSessionInstallStep(
                    title = "Refreshing package index",
                    command = "DEBIAN_FRONTEND=noninteractive apt-get update"
                ),
                GraphicSessionInstallStep(
                    title = "Installing Openbox",
                    command = "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openbox"
                ),
                GraphicSessionInstallStep(
                    title = "Installing terminal",
                    command = "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends xterm"
                ),
                GraphicSessionInstallStep(
                    title = "Installing fonts",
                    command = "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends fonts-terminus"
                )
            )
        }

        return packageSteps + listOf(
            GraphicSessionInstallStep(
                title = "Creating Openbox configuration directory",
                command = "mkdir -p /root/.config/openbox"
            ),
            GraphicSessionInstallStep(
                title = "Installing default Openbox rc.xml",
                command = "[ -f /root/.config/openbox/rc.xml ] || " +
                    "cp /etc/xdg/openbox/rc.xml /root/.config/openbox/rc.xml"
            ),
            GraphicSessionInstallStep(
                title = "Installing default Openbox menu.xml",
                command = "[ -f /root/.config/openbox/menu.xml ] || " +
                    "cp /etc/xdg/openbox/menu.xml /root/.config/openbox/menu.xml"
            ),
            GraphicSessionInstallStep(
                title = "Validating Openbox configuration",
                command = "test -f /root/.config/openbox/rc.xml && " +
                    "test -f /root/.config/openbox/menu.xml"
            ),
            GraphicSessionInstallStep(
                title = "Validating Openbox session command",
                command = "command -v ${plan.verificationCommand}"
            )
        )
    }

    /** Backward-compatible Alpine path used by existing tests/callers. */
    internal fun startupStepsFor(
        initSystem: InitSystem,
        session: GraphicSession
    ): List<GraphicSessionInstallStep> =
        startupStepsFor(ContainerPlatform.ALPINE, initSystem, session)

    internal fun startupStepsFor(
        platform: ContainerPlatform,
        initSystem: InitSystem,
        session: GraphicSession
    ): List<GraphicSessionInstallStep> = when (initSystem) {
        InitSystem.OPENRC -> listOf(
            GraphicSessionInstallStep(
                title = "Validating OpenRC",
                command = "test -x /sbin/openrc-run"
            ),
            GraphicSessionInstallStep(
                title = "Writing X11 session launcher",
                command = writeFileCommand(
                    "/usr/local/bin/x11-session.sh",
                    GraphicSessionInitFiles.sessionScript(session, "/bin/sh"),
                    "755"
                )
            ),
            GraphicSessionInstallStep(
                title = "Writing OpenRC X11 socket service",
                command = writeFileCommand(
                    "/etc/init.d/x11-setup",
                    GraphicSessionInitFiles.openRcSetupService(),
                    "755"
                )
            ),
            GraphicSessionInstallStep(
                title = "Writing OpenRC graphic session service",
                command = writeFileCommand(
                    "/etc/init.d/x11-session",
                    GraphicSessionInitFiles.openRcSessionService(session),
                    "755"
                )
            ),
            GraphicSessionInstallStep(
                title = "Enabling OpenRC X11 services",
                command = "rm -f " +
                    "/etc/init.d/x11-xfce /etc/runlevels/default/x11-xfce " +
                    "/etc/systemd/system/x11-xfce.service /etc/systemd/system/x11-session.service " +
                    "/etc/systemd/system/setup-x11-socket.service " +
                    "/etc/systemd/system/graphical.target.wants/x11-xfce.service " +
                    "/etc/systemd/system/graphical.target.wants/x11-session.service " +
                    "/etc/systemd/system/multi-user.target.wants/setup-x11-socket.service 2>/dev/null; " +
                    "mkdir -p /etc/runlevels/default && " +
                    "ln -sf /etc/init.d/x11-setup /etc/runlevels/default/x11-setup && " +
                    "ln -sf /etc/init.d/x11-session /etc/runlevels/default/x11-session"
            ),
            GraphicSessionInstallStep(
                title = "Validating OpenRC X11 startup",
                command = "test -x /usr/local/bin/x11-session.sh && " +
                    "test -x /etc/init.d/x11-setup && " +
                    "test -x /etc/init.d/x11-session && " +
                    "test -L /etc/runlevels/default/x11-setup && " +
                    "test -L /etc/runlevels/default/x11-session"
            )
        )

        InitSystem.SYSTEMD -> {
            val bashStep = when (platform) {
                ContainerPlatform.ALPINE -> GraphicSessionInstallStep(
                    title = "Installing bash for systemd session",
                    command = "apk add bash"
                )

                ContainerPlatform.UBUNTU -> GraphicSessionInstallStep(
                    title = "Validating bash for systemd session",
                    command = "command -v bash >/dev/null"
                )
            }

            listOf(
                GraphicSessionInstallStep(
                    title = "Validating systemd",
                    command = "command -v systemctl >/dev/null"
                ),
                bashStep,
                GraphicSessionInstallStep(
                    title = "Writing X11 session launcher",
                    command = writeFileCommand(
                        "/usr/local/bin/x11-session.sh",
                        GraphicSessionInitFiles.sessionScript(session, "/bin/bash"),
                        "755"
                    )
                ),
                GraphicSessionInstallStep(
                    title = "Writing systemd X11 socket unit",
                    command = writeFileCommand(
                        "/etc/systemd/system/setup-x11-socket.service",
                        GraphicSessionInitFiles.systemdSocketService(),
                        "644"
                    )
                ),
                GraphicSessionInstallStep(
                    title = "Writing systemd graphic session unit",
                    command = writeFileCommand(
                        "/etc/systemd/system/x11-session.service",
                        GraphicSessionInitFiles.systemdSessionService(session),
                        "644"
                    )
                ),
                GraphicSessionInstallStep(
                    title = "Enabling systemd X11 services",
                    command = "rm -f " +
                        "/etc/init.d/x11-setup /etc/init.d/x11-xfce /etc/init.d/x11-session " +
                        "/etc/runlevels/default/x11-setup /etc/runlevels/default/x11-xfce " +
                        "/etc/runlevels/default/x11-session /etc/systemd/system/x11-xfce.service " +
                        "/etc/systemd/system/graphical.target.wants/x11-xfce.service 2>/dev/null; " +
                        "mkdir -p /etc/systemd/system/multi-user.target.wants " +
                        "/etc/systemd/system/graphical.target.wants && " +
                        "ln -sf /etc/systemd/system/setup-x11-socket.service " +
                        "/etc/systemd/system/multi-user.target.wants/setup-x11-socket.service && " +
                        "ln -sf /etc/systemd/system/x11-session.service " +
                        "/etc/systemd/system/graphical.target.wants/x11-session.service"
                ),
                GraphicSessionInstallStep(
                    title = "Validating systemd X11 startup",
                    command = "test -x /usr/local/bin/x11-session.sh && " +
                        "test -f /etc/systemd/system/setup-x11-socket.service && " +
                        "test -f /etc/systemd/system/x11-session.service && " +
                        "test -L /etc/systemd/system/multi-user.target.wants/setup-x11-socket.service && " +
                        "test -L /etc/systemd/system/graphical.target.wants/x11-session.service"
                )
            )
        }
    }

    internal fun verificationStepsFor(
        platform: ContainerPlatform,
        session: GraphicSession,
        initSystem: InitSystem
    ): List<GraphicSessionInstallStep> {
        if (session != GraphicSession.OPENBOX) return emptyList()

        val packageChecks = when (platform) {
            ContainerPlatform.ALPINE -> listOf(
                GraphicSessionInstallStep(
                    title = "Checking Alpine package manager",
                    command = "command -v apk >/dev/null"
                ),
                GraphicSessionInstallStep(
                    title = "Checking Openbox packages",
                    command = "apk info -e openbox >/dev/null && " +
                        "apk info -e xterm >/dev/null && " +
                        "apk info -e font-terminus >/dev/null"
                )
            )

            ContainerPlatform.UBUNTU -> listOf(
                GraphicSessionInstallStep(
                    title = "Checking Debian package manager",
                    command = "command -v apt-get >/dev/null && command -v dpkg >/dev/null"
                ),
                GraphicSessionInstallStep(
                    title = "Checking Openbox packages",
                    command = "dpkg -s openbox >/dev/null 2>&1 && " +
                        "dpkg -s xterm >/dev/null 2>&1 && " +
                        "dpkg -s fonts-terminus >/dev/null 2>&1"
                )
            )
        }

        val common = listOf(
            GraphicSessionInstallStep(
                title = "Checking Openbox session command",
                command = "command -v openbox-session"
            ),
            GraphicSessionInstallStep(
                title = "Checking Openbox configuration",
                command = "test -f /root/.config/openbox/rc.xml && " +
                    "test -f /root/.config/openbox/menu.xml"
            ),
            GraphicSessionInstallStep(
                title = "Checking X11 session launcher",
                command = "test -x /usr/local/bin/x11-session.sh && " +
                    "grep -Fqx 'exec openbox-session' /usr/local/bin/x11-session.sh"
            )
        )

        val initChecks = when (initSystem) {
            InitSystem.OPENRC -> listOf(
                GraphicSessionInstallStep(
                    title = "Checking OpenRC X11 startup",
                    command = "test -x /sbin/openrc-run && " +
                        "test -x /etc/init.d/x11-setup && " +
                        "test -x /etc/init.d/x11-session && " +
                        "test -L /etc/runlevels/default/x11-setup && " +
                        "test -L /etc/runlevels/default/x11-session"
                )
            )

            InitSystem.SYSTEMD -> listOf(
                GraphicSessionInstallStep(
                    title = "Checking systemd X11 startup",
                    command = "command -v systemctl >/dev/null && command -v bash >/dev/null && " +
                        "test -f /etc/systemd/system/setup-x11-socket.service && " +
                        "test -f /etc/systemd/system/x11-session.service && " +
                        "test -L /etc/systemd/system/multi-user.target.wants/setup-x11-socket.service && " +
                        "test -L /etc/systemd/system/graphical.target.wants/x11-session.service"
                )
            )
        }

        return packageChecks + common + initChecks
    }

    suspend fun install(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        initSystem: InitSystem,
        cacheDir: File,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        logger?.i("--- Installing Graphic Session: ${session.label} ---")
        logger?.i("")

        val lease = ensureContainerReady(containerName, logger, "installation")
            ?: return@withContext false

        try {
            val resolvedPlatform = resolvePlatform(containerName, platform, logger)
                ?: return@withContext false

            val plan = GraphicSessionInstallPlans.forSelection(resolvedPlatform, session)
            if (plan == null) {
                logger?.e("[-] FAIL")
                logger?.e("[-] ${session.label} installer is not enabled for ${resolvedPlatform.label}")
                return@withContext false
            }

            val installSteps = stepsFor(plan)
            if (installSteps.isEmpty()) {
                logger?.e("[-] FAIL")
                logger?.e("[-] Installer workflow is not implemented for this selection")
                return@withContext false
            }

            for (step in installSteps) {
                if (!runStep(containerName, step, logger)) {
                    logger?.e("[-] ${session.label} installation aborted")
                    return@withContext false
                }
            }

            logger?.i("[+] Configuring ${initSystem.name.lowercase()} startup")
            logger?.i("")
            for (step in startupStepsFor(resolvedPlatform, initSystem, session)) {
                if (!runStep(containerName, step, logger)) {
                    logger?.e("[-] ${session.label} startup configuration aborted")
                    return@withContext false
                }
            }

            logger?.i("[+] Saving Package Platform")
            logger?.i("platform=${if (resolvedPlatform == ContainerPlatform.ALPINE) "alpine" else "ubuntu"}")
            if (!ContainerSettingsManager.setPlatform(containerName, resolvedPlatform, cacheDir)) {
                logger?.e("[-] FAIL")
                logger?.e("[-] Could not persist Package Platform")
                return@withContext false
            }
            logger?.i("[+] OK")
            logger?.i("")

            logger?.i("[+] Saving Init System")
            logger?.i("init_system=${initSystem.name.lowercase()}")
            if (!ContainerSettingsManager.setInitSystem(containerName, initSystem, cacheDir)) {
                logger?.e("[-] FAIL")
                logger?.e("[-] Could not persist Init System")
                return@withContext false
            }
            logger?.i("[+] OK")
            logger?.i("")

            logger?.i("[+] Saving Graphic Session")
            logger?.i("graphic_session=${session.name.lowercase()}")
            if (!ContainerSettingsManager.setGraphicSession(containerName, session, cacheDir)) {
                logger?.e("[-] FAIL")
                logger?.e("[-] Could not persist Graphic Session")
                return@withContext false
            }
            logger?.i("[+] OK")
            logger?.i("")
            logger?.i("[+] ${session.label} setup completed")
            true
        } finally {
            releaseContainer(containerName, lease, logger)
        }
    }

    suspend fun verify(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        initSystem: InitSystem,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        logger?.i("--- Verifying Graphic Session: ${session.label} ---")
        logger?.i("")

        val lease = ensureContainerReady(containerName, logger, "verification")
            ?: return@withContext false

        try {
            val resolvedPlatform = resolvePlatform(containerName, platform, logger)
                ?: return@withContext false
            val checks = verificationStepsFor(resolvedPlatform, session, initSystem)
            if (checks.isEmpty()) {
                logger?.e("[-] FAIL")
                logger?.e("[-] Verification is not enabled for ${session.label} on ${resolvedPlatform.label}")
                return@withContext false
            }

            for (step in checks) {
                if (!runStep(containerName, step, logger)) {
                    logger?.e("[-] ${session.label} verification failed")
                    return@withContext false
                }
            }

            logger?.i("[+] ${session.label} verification completed")
            true
        } finally {
            releaseContainer(containerName, lease, logger)
        }
    }

    private suspend fun resolvePlatform(
        containerName: String,
        requestedPlatform: ContainerPlatform?,
        logger: ContainerLogger?
    ): ContainerPlatform? {
        if (requestedPlatform != null) return requestedPlatform

        logger?.i("[+] Detecting package platform")
        val detected = when {
            probeContainerShell(containerName, "command -v apk >/dev/null") -> ContainerPlatform.ALPINE
            probeContainerShell(
                containerName,
                "command -v apt-get >/dev/null && command -v dpkg >/dev/null"
            ) -> ContainerPlatform.UBUNTU
            else -> null
        }

        if (detected == null) {
            logger?.e("[-] FAIL")
            logger?.e("[-] No supported package manager found (apk or apt/dpkg)")
            logger?.i("")
            return null
        }

        logger?.i(
            when (detected) {
                ContainerPlatform.ALPINE -> "[+] Detected apk package platform"
                ContainerPlatform.UBUNTU -> "[+] Detected Debian/Ubuntu .deb package platform"
            }
        )
        logger?.i("[+] OK")
        logger?.i("")
        return detected
    }

    private fun probeContainerShell(containerName: String, command: String): Boolean {
        val hostCommand =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run sh -c ${shellQuote(command)} 2>/dev/null"
        return try {
            Shell.cmd(hostCommand).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun ensureContainerReady(
        containerName: String,
        logger: ContainerLogger?,
        purpose: String
    ): ContainerOperationLease? {
        logger?.i("[+] Checking container runtime")
        val (initialStatus, initialPid) = ContainerManager.getContainerRuntimeStatePublic(containerName)
        val restoreStoppedState = initialStatus == ContainerStatus.STOPPED

        when (initialStatus) {
            ContainerStatus.RUNNING -> {
                logger?.i("[+] Container already running${if (initialPid != null) " (PID=$initialPid)" else ""}")
            }

            ContainerStatus.STOPPED -> {
                logger?.i("[*] Container is stopped; starting it temporarily for $purpose...")
                val started = ContainerManager.startContainer(containerName, logger)
                if (!started) {
                    val (statusAfterStart, _) = ContainerManager.getContainerRuntimeStatePublic(containerName)
                    if (statusAfterStart == ContainerStatus.STOPPED) {
                        logger?.e("[-] FAIL")
                        logger?.e("[-] Could not start container for $purpose")
                        return null
                    }
                    logger?.w("[!] Start command was inconclusive; checking command readiness")
                }
            }

            ContainerStatus.UNKNOWN -> {
                if (probeContainerCommandReady(containerName)) {
                    logger?.i("[+] Container command channel already ready")
                    logger?.i("")
                    return ContainerOperationLease(restoreStoppedState = false)
                }

                logger?.w("[!] Container runtime status is unknown; requesting start before $purpose")
                val started = ContainerManager.startContainer(containerName, logger)
                if (!started) {
                    logger?.w("[!] Start command was inconclusive; checking command readiness")
                }
            }
        }

        logger?.i("[*] Waiting for container command readiness (15s)...")
        if (!waitForContainerCommandReady(containerName)) {
            logger?.e("[-] FAIL")
            logger?.e("[-] Container did not become ready for $purpose commands")
            if (restoreStoppedState) {
                releaseContainer(
                    containerName,
                    ContainerOperationLease(restoreStoppedState = true),
                    logger
                )
            }
            return null
        }

        val (_, pid) = ContainerManager.getContainerRuntimeStatePublic(containerName)
        logger?.i("[+] Container command channel ready${if (pid != null) " (PID=$pid)" else ""}")
        logger?.i("[+] OK")
        logger?.i("")
        return ContainerOperationLease(restoreStoppedState = restoreStoppedState)
    }

    private suspend fun releaseContainer(
        containerName: String,
        lease: ContainerOperationLease,
        logger: ContainerLogger?
    ) {
        if (!lease.restoreStoppedState) return

        logger?.i("")
        logger?.i("[*] Restoring original stopped container state...")
        val stopAccepted = ContainerManager.stopContainer(containerName, logger)
        val (statusAfterStop, _) = ContainerManager.getContainerRuntimeStatePublic(containerName)
        if (stopAccepted || statusAfterStop == ContainerStatus.STOPPED) {
            logger?.i("[+] Container restored to stopped state")
        } else {
            logger?.w("[!] Could not confirm container returned to stopped state")
        }
    }

    private suspend fun waitForContainerCommandReady(
        containerName: String,
        timeoutMillis: Long = 15_000L,
        pollIntervalMillis: Long = 1_000L
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (probeContainerCommandReady(containerName)) return true
            if (System.nanoTime() >= deadline) return false
            delay(pollIntervalMillis)
        }
    }

    private fun probeContainerCommandReady(containerName: String): Boolean {
        val marker = "__SAAS_INSTALL_READY__"
        val hostCommand =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run " +
                shellQuote("echo $marker") + " 2>/dev/null"
        return try {
            val result = Shell.cmd(hostCommand).exec()
            result.isSuccess && result.out.any { it.contains(marker) }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun runStep(
        containerName: String,
        step: GraphicSessionInstallStep,
        logger: ContainerLogger?
    ): Boolean {
        logger?.i("[+] ${step.title}")
        logger?.i("root@$containerName: ${step.command}")

        val hostCommand =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run sh -c ${shellQuote(step.command)}"

        val result = if (logger == null) {
            Shell.cmd(hostCommand).exec()
        } else {
            val stdout = object : CallbackList<String>() {
                override fun onAddElement(line: String) {
                    logger.logImmediate(Log.INFO, line)
                }
            }
            val stderr = object : CallbackList<String>() {
                override fun onAddElement(line: String) {
                    logger.logImmediate(Log.WARN, line)
                }
            }
            Shell.cmd(hostCommand).to(stdout, stderr).exec()
        }

        if (!result.isSuccess) {
            logger?.e("[-] FAIL (exit ${result.code})")
            logger?.i("")
            return false
        }

        logger?.i("[+] OK")
        logger?.i("")
        return true
    }

    private fun writeFileCommand(path: String, content: String, mode: String): String {
        val parent = path.substringBeforeLast('/')
        return "mkdir -p ${shellQuote(parent)} && " +
            "printf '%s' ${shellQuote(content)} > ${shellQuote(path)} && " +
            "chmod $mode ${shellQuote(path)}"
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
