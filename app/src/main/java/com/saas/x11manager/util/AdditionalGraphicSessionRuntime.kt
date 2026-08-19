package com.saas.x11manager.util

import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.delay

internal data class AdditionalContainerLease(
    val restoreStoppedState: Boolean
)

internal object AdditionalGraphicSessionRuntime {

    suspend fun resolvePlatform(
        containerName: String,
        requestedPlatform: ContainerPlatform?,
        logger: ContainerLogger?
    ): ContainerPlatform? {
        if (requestedPlatform != null) return requestedPlatform

        logger?.i("[+] Detecting package platform")
        val detected = when {
            probeShell(containerName, "command -v apk >/dev/null") -> ContainerPlatform.ALPINE
            probeShell(
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
            if (detected == ContainerPlatform.ALPINE) {
                "[+] Detected apk package platform"
            } else {
                "[+] Detected Debian/Ubuntu .deb package platform"
            }
        )
        logger?.i("[+] OK")
        logger?.i("")
        return detected
    }

    suspend fun ensureReady(
        containerName: String,
        logger: ContainerLogger?,
        purpose: String
    ): AdditionalContainerLease? {
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
                if (probeCommandReady(containerName)) {
                    logger?.i("[+] Container command channel already ready")
                    logger?.i("")
                    return AdditionalContainerLease(restoreStoppedState = false)
                }

                logger?.w("[!] Container runtime status is unknown; requesting start before $purpose")
                val started = ContainerManager.startContainer(containerName, logger)
                if (!started) {
                    logger?.w("[!] Start command was inconclusive; checking command readiness")
                }
            }
        }

        logger?.i("[*] Waiting for container command readiness (15s)...")
        if (!waitForCommandReady(containerName)) {
            logger?.e("[-] FAIL")
            logger?.e("[-] Container did not become ready for $purpose commands")
            if (restoreStoppedState) {
                release(containerName, AdditionalContainerLease(true), logger)
            }
            return null
        }

        val (_, pid) = ContainerManager.getContainerRuntimeStatePublic(containerName)
        logger?.i("[+] Container command channel ready${if (pid != null) " (PID=$pid)" else ""}")
        logger?.i("[+] OK")
        logger?.i("")
        return AdditionalContainerLease(restoreStoppedState)
    }

    suspend fun release(
        containerName: String,
        lease: AdditionalContainerLease,
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

    internal fun isRedundantStandalonePackageAvailabilityStep(
        step: GraphicSessionInstallStep
    ): Boolean {
        val command = step.command.trimStart()
        return step.title == "Validating Alpine package manager" ||
            step.title == "Validating Debian package manager" ||
            step.title == "Checking required apt repository" ||
            step.title == "Checking APT transaction safety" ||
            step.title == "Checking apk transaction safety" ||
            command.startsWith("apk search -e ") ||
            (command.startsWith("LC_ALL=C apt-cache policy ") && command.contains("Candidate:"))
    }

    suspend fun runStep(
        containerName: String,
        step: GraphicSessionInstallStep,
        logger: ContainerLogger?
    ): Boolean {
        // Package-family detection already happens before the install plan is selected.
        // Runtime installation now lets apk/apt resolve repositories and dependencies
        // directly instead of repeating package-manager probes, repository availability
        // checks or transaction simulations before the real install command. The CI
        // compatibility matrix remains responsible for catalog-level safety research.
        if (isRedundantStandalonePackageAvailabilityStep(step)) return true

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

    private fun probeShell(containerName: String, command: String): Boolean {
        val hostCommand =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run sh -c ${shellQuote(command)} 2>/dev/null"
        return try {
            Shell.cmd(hostCommand).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun waitForCommandReady(
        containerName: String,
        timeoutMillis: Long = 15_000L,
        pollIntervalMillis: Long = 1_000L
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (probeCommandReady(containerName)) return true
            if (System.nanoTime() >= deadline) return false
            delay(pollIntervalMillis)
        }
    }

    private fun probeCommandReady(containerName: String): Boolean {
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
