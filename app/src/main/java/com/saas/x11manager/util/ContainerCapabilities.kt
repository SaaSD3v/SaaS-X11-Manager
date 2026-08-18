package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell

/** Runtime facts discovered from the container itself, never from a fixed distro version. */
data class ContainerCapabilities(
    val platform: ContainerPlatform?,
    val distribution: ContainerDistribution,
    val availableInitSystems: Set<InitSystem>
) {
    fun supports(initSystem: InitSystem): Boolean = initSystem in availableInitSystems
}

internal object ContainerCapabilitiesDetector {

    suspend fun detect(
        containerName: String,
        logger: ContainerLogger? = null
    ): ContainerCapabilities? {
        val lease = AdditionalGraphicSessionRuntime.ensureReady(
            containerName = containerName,
            logger = logger,
            purpose = "capability detection"
        ) ?: return null

        return try {
            val platform = AdditionalGraphicSessionRuntime.resolvePlatform(
                containerName = containerName,
                requestedPlatform = null,
                logger = logger
            )
            val osRelease = captureShell(
                containerName,
                "cat /etc/os-release 2>/dev/null"
            )
            val hasOpenRc = probeShell(
                containerName,
                "command -v openrc-run >/dev/null 2>&1 || test -x /sbin/openrc-run"
            )
            val hasSystemd = probeShell(
                containerName,
                "command -v systemctl >/dev/null 2>&1 && test -d /etc/systemd/system"
            )

            fromProbeResults(
                platform = platform,
                osReleaseLines = osRelease,
                hasOpenRc = hasOpenRc,
                hasSystemd = hasSystemd
            )
        } finally {
            AdditionalGraphicSessionRuntime.release(containerName, lease, logger)
        }
    }

    internal fun fromProbeResults(
        platform: ContainerPlatform?,
        osReleaseLines: List<String>,
        hasOpenRc: Boolean,
        hasSystemd: Boolean
    ): ContainerCapabilities = ContainerCapabilities(
        platform = platform,
        distribution = ContainerDistributionParser.parse(osReleaseLines),
        availableInitSystems = buildSet {
            if (hasOpenRc) add(InitSystem.OPENRC)
            if (hasSystemd) add(InitSystem.SYSTEMD)
        }
    )

    private fun captureShell(containerName: String, command: String): List<String> {
        val hostCommand =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run sh -c ${shellQuote(command)} 2>/dev/null"
        return try {
            val result = Shell.cmd(hostCommand).exec()
            if (result.isSuccess) result.out else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
