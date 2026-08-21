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
        logger?.i("[*] Resolving package platform")
        logger?.i("[CTX] Container: $containerName")
        if (requestedPlatform != null) {
            logger?.i("[CTX] Platform source: user/profile selection")
            logger?.i("[+] Package platform: ${requestedPlatform.label}")
            logger?.i("")
            return requestedPlatform
        }

        logger?.i("[CTX] Platform source: runtime capability probe")
        logger?.i("[*] Probing apk and apt/dpkg availability...")
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
            logger?.i("[CTX] Platform probe result: unsupported/unknown")
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
        logger?.i("[CTX] Resolved platform: ${detected.label}")
        logger?.i("[+] OK")
        logger?.i("")
        return detected
    }

    suspend fun ensureReady(
        containerName: String,
        logger: ContainerLogger?,
        purpose: String
    ): AdditionalContainerLease? {
        logger?.i("--- Container Runtime Preparation ---")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Purpose: $purpose")
        logger?.i("[*] Checking container runtime")
        val (initialStatus, initialPid) = ContainerManager.getContainerRuntimeStatePublic(containerName)
        val restoreStoppedState = initialStatus == ContainerStatus.STOPPED
        logger?.i(
            "[CTX] Initial runtime: ${runtimeSummary(initialStatus, initialPid)}"
        )
        logger?.i(
            "[CTX] Restore policy: " +
                if (restoreStoppedState) "return container to STOPPED" else "leave runtime state unchanged"
        )

        when (initialStatus) {
            ContainerStatus.RUNNING -> {
                logger?.i("[+] Container already running${if (initialPid != null) " (PID=$initialPid)" else ""}")
            }

            ContainerStatus.STOPPED -> {
                logger?.i("[*] Container is stopped; starting it temporarily for $purpose...")
                val started = ContainerManager.startContainer(containerName, logger)
                logger?.i("[CTX] Start request accepted: $started")
                if (!started) {
                    val (statusAfterStart, pidAfterStart) =
                        ContainerManager.getContainerRuntimeStatePublic(containerName)
                    logger?.i(
                        "[CTX] Runtime after inconclusive start: " +
                            runtimeSummary(statusAfterStart, pidAfterStart)
                    )
                    if (statusAfterStart == ContainerStatus.STOPPED) {
                        logger?.e("[-] FAIL")
                        logger?.e("[-] Could not start container for $purpose")
                        return null
                    }
                    logger?.w("[!] Start command was inconclusive; checking command readiness")
                }
            }

            ContainerStatus.UNKNOWN -> {
                logger?.w("[!] Initial runtime state is UNKNOWN")
                if (probeCommandReady(containerName)) {
                    logger?.i("[+] Container command channel already ready")
                    logger?.i("[CTX] Runtime lease: existing command-ready container")
                    logger?.i("")
                    return AdditionalContainerLease(restoreStoppedState = false)
                }

                logger?.w("[!] Container runtime status is unknown; requesting start before $purpose")
                val started = ContainerManager.startContainer(containerName, logger)
                logger?.i("[CTX] Start request accepted: $started")
                if (!started) {
                    logger?.w("[!] Start command was inconclusive; checking command readiness")
                }
            }
        }

        logger?.i("[*] Waiting for container command readiness (15s)...")
        val readyStartedAt = System.nanoTime()
        if (!waitForCommandReady(containerName)) {
            val elapsedMs = (System.nanoTime() - readyStartedAt) / 1_000_000L
            val (finalStatus, finalPid) = ContainerManager.getContainerRuntimeStatePublic(containerName)
            logger?.e("[-] FAIL")
            logger?.e("[-] Container did not become ready for $purpose commands")
            logger?.i("[CTX] Readiness wait: ${elapsedMs}ms")
            logger?.i("[CTX] Runtime at timeout: ${runtimeSummary(finalStatus, finalPid)}")
            if (restoreStoppedState) {
                release(containerName, AdditionalContainerLease(true), logger)
            }
            return null
        }

        val readyElapsedMs = (System.nanoTime() - readyStartedAt) / 1_000_000L
        val (finalStatus, pid) = ContainerManager.getContainerRuntimeStatePublic(containerName)
        logger?.i("[+] Container command channel ready${if (pid != null) " (PID=$pid)" else ""}")
        logger?.i("[CTX] Readiness wait: ${readyElapsedMs}ms")
        logger?.i("[CTX] Runtime after readiness: ${runtimeSummary(finalStatus, pid)}")
        logger?.i("[+] OK")
        logger?.i("")
        return AdditionalContainerLease(restoreStoppedState)
    }

    suspend fun release(
        containerName: String,
        lease: AdditionalContainerLease,
        logger: ContainerLogger?
    ) {
        if (!lease.restoreStoppedState) {
            val (status, pid) = ContainerManager.getContainerRuntimeStatePublic(containerName)
            logger?.i("[CTX] Container state restore: not required")
            logger?.i("[CTX] Runtime left as: ${runtimeSummary(status, pid)}")
            return
        }

        logger?.i("")
        logger?.i("--- Container Runtime Restore ---")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Target runtime: STOPPED")
        logger?.i("[*] Restoring original stopped container state...")
        val stopAccepted = ContainerManager.stopContainer(containerName, logger)
        val (statusAfterStop, pidAfterStop) = ContainerManager.getContainerRuntimeStatePublic(containerName)
        logger?.i("[CTX] Stop request accepted: $stopAccepted")
        logger?.i("[CTX] Runtime after restore: ${runtimeSummary(statusAfterStop, pidAfterStop)}")
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
        if (isRedundantStandalonePackageAvailabilityStep(step)) {
            logger?.i("[*] Skipping redundant preflight: ${step.title}")
            logger?.i("[CTX] Reason: capability/package resolution is handled by the real transaction")
            logger?.i("")
            return true
        }

        logger?.i("[+] ${step.title}")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("root@$containerName: ${step.command}")

        val hostCommand =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run sh -c ${shellQuote(step.command)}"
        val startedAt = System.nanoTime()

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

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        logger?.i("[CTX] Exit code: ${result.code}")
        logger?.i("[CTX] Duration: ${elapsedMs}ms")

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

    private fun runtimeSummary(status: ContainerStatus, pid: Int?): String =
        buildString {
            append(status.name)
            if (pid != null) append(" (PID=").append(pid).append(')')
        }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
