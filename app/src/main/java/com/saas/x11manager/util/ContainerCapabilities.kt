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

    private const val PACKAGE_MARKER = "@@SAAS_PACKAGE="
    private const val OPENRC_MARKER = "@@SAAS_OPENRC="
    private const val SYSTEMD_MARKER = "@@SAAS_SYSTEMD="
    private const val OS_RELEASE_BEGIN = "@@SAAS_OS_RELEASE_BEGIN@@"
    private const val OS_RELEASE_END = "@@SAAS_OS_RELEASE_END@@"

    internal data class RuntimeProbe(
        val platform: ContainerPlatform?,
        val osReleaseLines: List<String>,
        val hasOpenRc: Boolean,
        val hasSystemd: Boolean
    )

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
            logger?.i("[+] Detecting container capabilities")
            val probe = captureRuntimeProbe(containerName)
            if (probe.platform == null) {
                logger?.e("[-] No supported package manager found (apk or apt/dpkg)")
            } else {
                logger?.i(
                    if (probe.platform == ContainerPlatform.ALPINE) {
                        "[+] Detected apk package platform"
                    } else {
                        "[+] Detected Debian/Ubuntu .deb package platform"
                    }
                )
            }

            fromProbeResults(
                platform = probe.platform,
                osReleaseLines = probe.osReleaseLines,
                hasOpenRc = probe.hasOpenRc,
                hasSystemd = probe.hasSystemd
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

    /**
     * Parse the stable marker protocol emitted by [runtimeProbeCommand]. Keeping
     * this pure makes the capability contract testable without a rooted Android
     * runtime or a specific distro version.
     */
    internal fun parseRuntimeProbe(lines: List<String>): RuntimeProbe {
        var packageKind: String? = null
        var hasOpenRc = false
        var hasSystemd = false
        var inOsRelease = false
        val osRelease = mutableListOf<String>()

        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line == OS_RELEASE_BEGIN -> inOsRelease = true
                line == OS_RELEASE_END -> inOsRelease = false
                inOsRelease -> osRelease.add(line)
                line.startsWith(PACKAGE_MARKER) ->
                    packageKind = line.removePrefix(PACKAGE_MARKER).removeSuffix("@@").trim()
                line.startsWith(OPENRC_MARKER) ->
                    hasOpenRc = line.removePrefix(OPENRC_MARKER).removeSuffix("@@").trim() == "1"
                line.startsWith(SYSTEMD_MARKER) ->
                    hasSystemd = line.removePrefix(SYSTEMD_MARKER).removeSuffix("@@").trim() == "1"
            }
        }

        val platform = when (packageKind) {
            "apk" -> ContainerPlatform.ALPINE
            "deb" -> ContainerPlatform.UBUNTU
            else -> null
        }

        return RuntimeProbe(
            platform = platform,
            osReleaseLines = osRelease,
            hasOpenRc = hasOpenRc,
            hasSystemd = hasSystemd
        )
    }

    private fun captureRuntimeProbe(containerName: String): RuntimeProbe {
        val command = runtimeProbeCommand()
        val hostCommand =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run sh -c ${shellQuote(command)} 2>/dev/null"
        val lines = try {
            val result = Shell.cmd(hostCommand).exec()
            if (result.isSuccess) result.out else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return parseRuntimeProbe(lines)
    }

    internal fun runtimeProbeCommand(): String = listOf(
        "if command -v apk >/dev/null 2>&1; then echo '$PACKAGE_MARKER" + "apk@@'; " +
            "elif command -v apt-get >/dev/null 2>&1 && command -v dpkg >/dev/null 2>&1; " +
            "then echo '$PACKAGE_MARKER" + "deb@@'; else echo '$PACKAGE_MARKER" + "none@@'; fi",
        "if command -v openrc-run >/dev/null 2>&1 || test -x /sbin/openrc-run; " +
            "then echo '$OPENRC_MARKER" + "1@@'; else echo '$OPENRC_MARKER" + "0@@'; fi",
        "if command -v systemctl >/dev/null 2>&1 && test -d /etc/systemd/system; " +
            "then echo '$SYSTEMD_MARKER" + "1@@'; else echo '$SYSTEMD_MARKER" + "0@@'; fi",
        "echo '$OS_RELEASE_BEGIN'",
        "cat /etc/os-release 2>/dev/null || true",
        "echo '$OS_RELEASE_END'"
    ).joinToString("; ")

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
