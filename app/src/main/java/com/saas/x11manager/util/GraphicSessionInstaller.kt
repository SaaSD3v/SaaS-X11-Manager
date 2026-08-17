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
 * Installs graphical session packages inside an already-running container.
 *
 * Installation and session launch are intentionally separate: this object only
 * installs/configures/verifies the selected session and persists the selection.
 * The init backend is responsible for launching x11-session.sh on container boot.
 */
object GraphicSessionInstaller {

    internal fun stepsFor(plan: GraphicSessionInstallPlan): List<GraphicSessionInstallStep> {
        if (plan.platform != ContainerPlatform.ALPINE || plan.session != GraphicSession.OPENBOX) {
            return emptyList()
        }

        return listOf(
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

    suspend fun install(
        containerName: String,
        platform: ContainerPlatform,
        session: GraphicSession,
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

        val steps = stepsFor(plan)
        if (steps.isEmpty()) {
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

        for (step in steps) {
            if (!runStep(containerName, step, logger)) {
                logger?.e("[-] ${session.label} installation aborted")
                return@withContext false
            }
        }

        logger?.i("[+] Saving Graphic Session")
        logger?.i("graphic_session=${session.name.lowercase()}")
        val saved = ContainerSettingsManager.setGraphicSession(
            containerName = containerName,
            graphicSession = session,
            cacheDir = cacheDir
        )
        if (!saved) {
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
