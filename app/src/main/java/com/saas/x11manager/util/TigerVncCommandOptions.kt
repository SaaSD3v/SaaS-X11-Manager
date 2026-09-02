package com.saas.x11manager.util

/**
 * Pure TigerVNC argument policy.
 *
 * The builder returns argv tokens, never a shell fragment. VncServerManager
 * shell-quotes every token before it is passed through DroidSpaces, which keeps
 * advanced options from becoming a command-injection surface.
 */
object TigerVncCommandOptions {
    private val reservedExtraNames = setOf(
        "rfbport",
        "password",
        "passwordfile",
        "rfbauth",
        "display"
    )

    fun validateExtraArguments(value: String): String? {
        val lines = value.lineSequence().toList()
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.length > 1024) {
                return "Extra TigerVNC argument ${index + 1} is longer than 1024 characters."
            }
            if (line.any { it == '\u0000' || it == '\r' || it == '\n' || it.code < 0x20 && it != '\t' }) {
                return "Extra TigerVNC argument ${index + 1} contains control characters."
            }
            if (!line.startsWith("-")) {
                return "Extra TigerVNC argument ${index + 1} must start with '-'."
            }
            val name = line
                .removePrefix("--")
                .removePrefix("-")
                .substringBefore('=')
                .trim()
                .lowercase()
            if (name in reservedExtraNames) {
                return "Extra TigerVNC argument '$name' is managed by SaaS X11 Manager."
            }
        }
        return null
    }

    fun extraArguments(value: String): List<String> {
        require(validateExtraArguments(value) == null) {
            validateExtraArguments(value) ?: "Invalid TigerVNC extra arguments"
        }
        return value.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }

    fun standalone(
        settings: VncLaunchSettings,
        displayNumber: Int,
        port: Int,
        passwordFile: String
    ): List<String> {
        require(VncSettings.validateLaunchSettings(settings) == null) {
            VncSettings.validateLaunchSettings(settings) ?: "Invalid TigerVNC settings"
        }
        require(VncSettings.isValidPort(port))
        require(displayNumber in 1..99)

        val out = mutableListOf<String>()
        out += ":$displayNumber"
        out += listOf("-geometry", VncSettings.normalizeGeometry(settings.geometry))
        out += listOf("-depth", settings.depth.trim())
        out += listOf("-rfbport", port.toString())
        out += listOf("-localhost", if (settings.localhostOnly) "yes" else "no")
        out += listOf("-SecurityTypes", resolvedStandaloneSecurityTypes(settings))
        out += listOf("-rfbauth", passwordFile)

        if (settings.alwaysShared) {
            out += "-AlwaysShared"
        } else {
            out += "-AlwaysShared=0"
        }

        appendCommon(out, settings)
        appendStandaloneOnly(out, settings)
        out += extraArguments(settings.extraArguments)
        return out
    }

    fun mirror(
        settings: VncLaunchSettings,
        displayName: String,
        port: Int,
        passwordFile: String
    ): List<String> {
        require(VncSettings.validateLaunchSettings(settings) == null) {
            VncSettings.validateLaunchSettings(settings) ?: "Invalid TigerVNC settings"
        }
        require(VncSettings.isValidPort(port))
        require(Regex("""^:\d+$""").matches(displayName))

        val out = mutableListOf<String>()
        out += listOf("-display", displayName)
        out += listOf("-rfbport", port.toString())
        out += "-PasswordFile=$passwordFile"
        out += "-localhost=${if (settings.localhostOnly) "yes" else "no"}"
        out += "-AlwaysShared=${if (settings.alwaysShared) "1" else "0"}"

        if (!settings.securityTypes.equals(VncSettings.SECURITY_TYPES_AUTO, ignoreCase = true)) {
            out += "-SecurityTypes=${settings.securityTypes.trim()}"
        }

        appendCommon(out, settings)
        appendMirrorOnly(out, settings)
        out += extraArguments(settings.extraArguments)
        return out
    }

    private fun resolvedStandaloneSecurityTypes(settings: VncLaunchSettings): String =
        if (settings.securityTypes.equals(VncSettings.SECURITY_TYPES_AUTO, ignoreCase = true)) {
            "VncAuth"
        } else {
            settings.securityTypes.trim()
        }

    private fun appendCommon(out: MutableList<String>, s: VncLaunchSettings) {
        if (!s.useIPv4) out += "-UseIPv4=0"
        if (!s.useIPv6) out += "-UseIPv6=0"
        if (s.interfaceAddress.isNotBlank()) out += "-interface=${s.interfaceAddress.trim()}"
        if (s.desktopName.isNotBlank()) out += "-desktop=${s.desktopName}"
        if (s.neverShared) out += "-NeverShared=1"
        if (!s.disconnectClients) out += "-DisconnectClients=0"

        if (!s.acceptKeyEvents) out += "-AcceptKeyEvents=0"
        if (!s.acceptPointerEvents) out += "-AcceptPointerEvents=0"
        if (!s.acceptSetDesktopSize) out += "-AcceptSetDesktopSize=0"
        if (!s.acceptCutText) out += "-AcceptCutText=0"
        if (!s.sendCutText) out += "-SendCutText=0"
        if (!s.sendPrimary) out += "-SendPrimary=0"
        if (!s.setPrimary) out += "-SetPrimary=0"
        if (s.maxCutText.trim() != "262144") out += "-MaxCutText=${s.maxCutText.trim()}"
        if (s.rawKeyboard) out += "-RawKeyboard=1"
        if (s.remapKeys.isNotBlank()) out += "-RemapKeys=${s.remapKeys}"
        if (s.protocol33) out += "-Protocol3.3=1"

        if (!s.useBlacklist) out += "-UseBlacklist=0"
        if (s.blacklistThreshold.trim() != "5") {
            out += "-BlacklistThreshold=${s.blacklistThreshold.trim()}"
        }
        if (s.blacklistTimeout.trim() != "10") {
            out += "-BlacklistTimeout=${s.blacklistTimeout.trim()}"
        }
        if (s.queryConnect) out += "-QueryConnect=1"
        if (s.queryConnectTimeout.trim() != "10") {
            out += "-QueryConnectTimeout=${s.queryConnectTimeout.trim()}"
        }
        if (s.requireUsername) out += "-RequireUsername=1"
        if (s.pamService.trim() != "vnc" && s.pamService.isNotBlank()) {
            out += "-PAMService=${s.pamService.trim()}"
        }
        if (s.plainUsers.isNotBlank()) out += "-PlainUsers=${s.plainUsers.trim()}"
        if (s.gnuTlsPriority.isNotBlank()) out += "-GnuTLSPriority=${s.gnuTlsPriority}"
        if (s.x509Cert.isNotBlank()) out += "-X509Cert=${s.x509Cert.trim()}"
        if (s.x509Key.isNotBlank()) out += "-X509Key=${s.x509Key.trim()}"
        if (s.rsaKey.isNotBlank()) out += "-RSAKey=${s.rsaKey.trim()}"

        if (s.idleTimeout.trim() != "0") out += "-IdleTimeout=${s.idleTimeout.trim()}"
        if (s.maxConnectionTime.trim() != "0") {
            out += "-MaxConnectionTime=${s.maxConnectionTime.trim()}"
        }
        if (s.maxDisconnectionTime.trim() != "0") {
            out += "-MaxDisconnectionTime=${s.maxDisconnectionTime.trim()}"
        }
        if (s.maxIdleTime.trim() != "0") out += "-MaxIdleTime=${s.maxIdleTime.trim()}"
        if (s.frameRate.trim() != "60") out += "-FrameRate=${s.frameRate.trim()}"
        if (s.compareFb.trim() != "2") out += "-CompareFB=${s.compareFb.trim()}"
        if (!s.improvedHextile) out += "-ImprovedHextile=0"
        if (s.logSpec.isNotBlank()) out += "-Log=${s.logSpec}"

        if (s.rfbUnixPath.isNotBlank()) {
            out += "-rfbunixpath=${s.rfbUnixPath.trim()}"
            if (s.rfbUnixMode.isNotBlank() && s.rfbUnixMode.trim() != "0600") {
                out += "-rfbunixmode=${s.rfbUnixMode.trim()}"
            }
        }
    }

    private fun appendStandaloneOnly(out: MutableList<String>, s: VncLaunchSettings) {
        if (s.pixelFormat.isNotBlank()) {
            out += listOf("-pixelformat", s.pixelFormat.trim())
        }
        if (s.renderNode.isNotBlank()) {
            out += listOf("-rendernode", s.renderNode.trim())
        }
        if (s.avoidShiftNumLock) out += "-AvoidShiftNumLock=1"
        if (s.allowOverride.isNotBlank()) out += "-AllowOverride=${s.allowOverride}"
    }

    private fun appendMirrorOnly(out: MutableList<String>, s: VncLaunchSettings) {
        if (s.mirrorGeometry.isNotBlank()) {
            out += "-Geometry=${s.mirrorGeometry.trim().lowercase().replace(" ", "")}"
        }
        if (s.hostsFile.isNotBlank()) out += "-HostsFile=${s.hostsFile.trim()}"
        if (s.maxProcessorUsage.trim() != "35") {
            out += "-MaxProcessorUsage=${s.maxProcessorUsage.trim()}"
        }
        if (s.pollingCycle.trim() != "30") {
            out += "-PollingCycle=${s.pollingCycle.trim()}"
        }
        if (!s.useShm) out += "-UseSHM=0"
    }
}
