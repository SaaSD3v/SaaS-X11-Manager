package com.saas.x11manager.util

import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class GraphicSessionInstallStep(
    val title: String,
    val command: String
)

/**
 * Installs and provisions a graphical session inside an already-running container.
 * The selected session is configured for the user-selected init backend, but it
 * is never launched by this installer; the init service launches it on boot.
 */
object GraphicSessionInstaller {

    internal fun stepsFor(plan: GraphicSessionInstallPlan): List<GraphicSessionInstallStep> {
        if (plan.platform != ContainerPlatform.ALPINE || plan.session != GraphicSession.OPENBOX) {
            return emptyList()
        }

        return listOf(
            GraphicSessionInstallStep(
                title = "Validating Alpine environment",
                command = "test -f /etc/alpine-release && command -v apk"
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
            ),
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

    internal fun startupStepsFor(
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

        InitSystem.SYSTEMD -> listOf(
            GraphicSessionInstallStep(
                title = "Validating systemd",
                command = "command -v systemctl"
            ),
            GraphicSessionInstallStep(
                title = "Installing bash for systemd session",
                command = "apk add bash"
            ),
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

    suspend fun install(
        containerName: String,
        platform: ContainerPlatform,
        session: GraphicSession,
        initSystem: InitSystem,
        cacheDir: File,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        logger?.i("--- Installing Graphic Session: ${session.label} ---")
        logger?.i("")

        val plan = GraphicSessionInstallPlans.forSelection(platform, session)
        if (plan == null) {
            logger?.e("[-] FAIL")
            logger?.e("[-] ${session.label} installer is not enabled for ${platform.label}")
            return@withContext false
        }

        val installSteps = stepsFor(plan)
        if (installSteps.isEmpty()) {
            logger?.e("[-] FAIL")
            logger?.e("[-] Installer workflow is not implemented for this selection")
            return@withContext false
        }

        logger?.i("[+] Checking container runtime")
        val (runtimeStatus, _) = ContainerManager.getContainerRuntimeStatePublic(containerName)
        if (runtimeStatus != ContainerStatus.RUNNING) {
            logger?.e("[-] FAIL")
            logger?.e("[-] Container must be running before installing ${session.label}")
            return@withContext false
        }
        logger?.i("[+] OK")
        logger?.i("")

        for (step in installSteps) {
            if (!runStep(containerName, step, logger)) {
                logger?.e("[-] ${session.label} installation aborted")
                return@withContext false
            }
        }

        logger?.i("[+] Configuring ${initSystem.name.lowercase()} startup")
        logger?.i("")
        for (step in startupStepsFor(initSystem, session)) {
            if (!runStep(containerName, step, logger)) {
                logger?.e("[-] ${session.label} startup configuration aborted")
                return@withContext false
            }
        }

        logger?.i("[+] Saving Init System")
        logger?.i("init_system=${initSystem.name.lowercase()}")
        val initSaved = ContainerSettingsManager.setInitSystem(
            containerName = containerName,
            initSystem = initSystem,
            cacheDir = cacheDir
        )
        if (!initSaved) {
            logger?.e("[-] FAIL")
            logger?.e("[-] Could not persist Init System")
            return@withContext false
        }
        logger?.i("[+] OK")
        logger?.i("")

        logger?.i("[+] Saving Graphic Session")
        logger?.i("graphic_session=${session.name.lowercase()}")
        val sessionSaved = ContainerSettingsManager.setGraphicSession(
            containerName = containerName,
            graphicSession = session,
            cacheDir = cacheDir
        )
        if (!sessionSaved) {
            logger?.e("[-] FAIL")
            logger?.e("[-] Could not persist Graphic Session")
            return@withContext false
        }
        logger?.i("[+] OK")
        logger?.i("")
        logger?.i("[+] ${session.label} setup completed")
        true
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
