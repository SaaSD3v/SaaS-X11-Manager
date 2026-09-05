package com.saas.x11manager.ui.component

import com.saas.x11manager.util.AnsiColorParser

/**
 * Presentation-only view of the diagnostic log stream.
 *
 * Runtime code keeps emitting the full lossless log because it is invaluable for
 * support. The normal terminal view instead shows semantic checkpoints and
 * actionable warnings. The Details view returns the original records unchanged.
 * No X11, container, VNC or audio behavior is changed here.
 */
internal fun presentTerminalLogs(
    logs: List<Pair<Int, String>>,
    showDetails: Boolean
): List<Pair<Int, String>> {
    if (showDetails) return logs

    val plain = logs.map { AnsiColorParser.stripAnsi(it.second) }
    val x11Ready = plain.any {
        it.contains("Integrated X11 ready", ignoreCase = true) ||
            it.contains("Integrated X11 session started", ignoreCase = true)
    }
    val sessionReady = plain.any {
        it.contains(" session active on ", ignoreCase = true) ||
            it.contains("Graphic session confirmed: yes", ignoreCase = true)
    }
    val audioReady = plain.any {
        it.contains("Audio ready (", ignoreCase = true) ||
            it.contains("audio transport verified", ignoreCase = true)
    }

    val presented = buildList {
        logs.forEach { (level, original) ->
            val plainMessage = AnsiColorParser.stripAnsi(original).trim()
            if (plainMessage.isEmpty()) return@forEach

            if (looksLikeDroidSpacesStartBlock(plainMessage)) {
                addAll(summarizeDroidSpacesStart(level, plainMessage))
                return@forEach
            }

            if (plainMessage.contains('\n')) {
                plainMessage.lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach { line ->
                        presentOne(level, line, x11Ready, sessionReady, audioReady)?.let(::add)
                    }
                return@forEach
            }

            presentOne(level, plainMessage, x11Ready, sessionReady, audioReady)?.let(::add)
        }
    }

    return presented.fold(mutableListOf()) { output, entry ->
        if (output.lastOrNull()?.second != entry.second) output.add(entry)
        output
    }
}

private fun presentOne(
    level: Int,
    message: String,
    x11Ready: Boolean,
    sessionReady: Boolean,
    audioReady: Boolean
): Pair<Int, String>? {
    if (message.startsWith("[CTX]")) {
        return when {
            message.startsWith("[CTX] Access method:") ->
                level to "[SESSION] • Access: ${message.substringAfter(':').trim()}"
            message.startsWith("[CTX] Session:") ->
                level to "[SESSION] • Desktop: ${message.substringAfter(':').trim()}"
            message.startsWith("[CTX] Graphic user:") ->
                level to "[USER] • Desktop user: ${message.substringAfter(':').trim()}"
            else -> null
        }
    }

    if (message.startsWith("[PA-")) return null

    sectionSummary(message)?.let { return level to it }
    if (message.startsWith("---") && message.endsWith("---")) return null

    val body = removeLegacyMarker(message)

    if (isRoutineDetail(body)) return null
    if (looksLikePackageManagerNoise(message)) return null

    if (
        sessionReady &&
        (
            body.startsWith("X11 transport socket setup service returned", ignoreCase = true) ||
                body.startsWith(
                    "Container X11 transport socket was not visible during the prerequisite check",
                    ignoreCase = true
                )
            )
    ) {
        return null
    }

    if (
        audioReady &&
        (
            body.startsWith("Port 4713 could not be bound", ignoreCase = true) ||
                body.startsWith("Selected audio port:", ignoreCase = true) ||
                body.startsWith("Authenticated PulseAudio listener ready", ignoreCase = true) ||
                body.startsWith("NAT audio transport verified from inside the container", ignoreCase = true)
            )
    ) {
        return null
    }

    if (
        x11Ready &&
        (
            body.startsWith("Stale runtime artifacts cleared", ignoreCase = true) ||
                body.startsWith("Runtime directory ready:", ignoreCase = true) ||
                body.startsWith("Shared XKB cache ready", ignoreCase = true)
            )
    ) {
        return null
    }

    return level to formatSemanticMessage(message)
}

private fun sectionSummary(message: String): String? = when (message) {
    "--- Graphic Access Start ---" -> "[SESSION] Starting graphical access"
    "--- Audio Configuration ---" -> "[AUDIO] Preparing Android audio"
    "--- Starting Integrated X11 Session ---" -> "[X11] Starting Integrated X11"
    "--- Graphic Session Stop ---" -> "[SESSION] Stopping graphical session"
    else -> null
}

private fun isRoutineDetail(body: String): Boolean {
    val prefixes = listOf(
        "Saved access method:",
        "Saved VNC port:",
        "User policy:",
        "User-aware graphical session launcher ready",
        "Assigned Monitor ",
        "Preparing container X11 config",
        "Container config read",
        "Integrated X11 container config already ready",
        "Container X11 configuration confirmed",
        "Inspecting existing server state",
        "Cleaning stale socket/lock artifacts",
        "Preparing isolated runtime directory",
        "Launching integrated X11 app_process",
        "Waiting up to ",
        "X11 socket:",
        "Starting container",
        "Confirming container runtime",
        "Container runtime active",
        "Waiting for container command readiness",
        "Container command channel ready",
        "Synchronizing configured graphic session",
        "Ensuring ",
        "Graphic session backend:",
        "Graphic session service was inactive; start requested",
        "Graphic session service confirmed active",
        "Expected container X11 transport socket is visible",
        "Configuring and verifying the PulseAudio client inside"
    )
    if (prefixes.any { body.startsWith(it, ignoreCase = true) }) return true

    return body.startsWith("Monitor:", ignoreCase = true) ||
        body.startsWith("X11 display:", ignoreCase = true) ||
        body.contains(" duration:", ignoreCase = true) ||
        body.contains(" exit code:", ignoreCase = true)
}

private fun looksLikePackageManagerNoise(message: String): Boolean {
    val value = message.trimStart()
    return value.startsWith("Get:") ||
        value.startsWith("Hit:") ||
        value.startsWith("Ign:") ||
        value.startsWith("Fetched ") ||
        value.startsWith("Reading package lists") ||
        value.startsWith("Building dependency tree") ||
        value.startsWith("Reading state information") ||
        value.startsWith("Selecting previously unselected package") ||
        value.startsWith("Preparing to unpack") ||
        value.startsWith("Unpacking ") ||
        value.startsWith("Setting up ") ||
        value.startsWith("Processing triggers for") ||
        value.startsWith("debconf:") ||
        value.startsWith("fetch https://") ||
        value.matches(Regex("^\\(\\d+/\\d+\\) .+")) ||
        value.startsWith("Executing ") ||
        value.startsWith("OK: ")
}

private fun looksLikeDroidSpacesStartBlock(message: String): Boolean =
    message.contains("Welcome to Droidspaces", ignoreCase = true) &&
        message.lineSequence().any { it.trimStart().startsWith("Container:") }

private fun summarizeDroidSpacesStart(
    level: Int,
    message: String
): List<Pair<Int, String>> {
    val lines = message.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val containerLine = lines.firstOrNull { it.startsWith("Container:") }
    val os = lines.firstOrNull { it.startsWith("OS:") }?.substringAfter(':')?.trim()
    val natIp = lines.firstOrNull { it.startsWith("NAT IP:") }?.substringAfter(':')?.trim()

    val result = mutableListOf<Pair<Int, String>>()
    if (containerLine != null) {
        val name = containerLine.substringAfter("Container:").substringBefore("(").trim()
        val status = containerLine.substringAfter('(', "").substringBefore(')', "").trim()
        val details = buildList {
            os?.takeIf(String::isNotEmpty)?.let(::add)
            natIp?.takeIf(String::isNotEmpty)?.let { add("NAT $it") }
        }.joinToString(" · ")
        val statusText = if (status.equals("RUNNING", ignoreCase = true)) "running" else status.lowercase()
        result += level to buildString {
            append("[CONTAINER] ✓ $name")
            if (statusText.isNotEmpty()) append(" $statusText")
            if (details.isNotEmpty()) append(" · $details")
        }
    }

    lines
        .filter {
            it.startsWith("WARNING:", ignoreCase = true) ||
                it.startsWith("[!]")
        }
        .map { it.removePrefix("[!]").removePrefix("WARNING:").trim() }
        .filter(String::isNotEmpty)
        .distinct()
        .forEach { warning -> result += level to "[CONTAINER] ! $warning" }

    return result
}

private fun removeLegacyMarker(message: String): String = when {
    message.startsWith("[+]" ) -> message.removePrefix("[+]").trim()
    message.startsWith("[*]" ) -> message.removePrefix("[*]").trim()
    message.startsWith("[!]" ) -> message.removePrefix("[!]").trim()
    message.startsWith("[-]" ) -> message.removePrefix("[-]").trim()
    else -> message.trim()
}

private fun formatSemanticMessage(message: String): String {
    if (
        message.startsWith("[X11]") ||
        message.startsWith("[SESSION]") ||
        message.startsWith("[USER]") ||
        message.startsWith("[AUDIO]") ||
        message.startsWith("[VNC]") ||
        message.startsWith("[CONTAINER]") ||
        message.startsWith("[INSTALL]") ||
        message.startsWith("[MANAGER]")
    ) {
        return message
    }

    val marker = when {
        message.startsWith("[+]") -> "✓"
        message.startsWith("[*]") -> "•"
        message.startsWith("[!]") -> "!"
        message.startsWith("[-]") -> "✗"
        message.startsWith("Error:", ignoreCase = true) -> "✗"
        else -> "•"
    }
    val body = removeLegacyMarker(message)
    return "[${inferComponent(body)}] $marker $body"
}

private fun inferComponent(body: String): String {
    val value = body.lowercase()
    return when {
        value.contains("pulseaudio") || value.contains("audio") ||
            value.contains("aaudio") || value.contains("opensl") -> "AUDIO"
        value.contains("vnc") -> "VNC"
        value.contains("user") || value.contains("account") -> "USER"
        value.contains("session") || value.contains("icewm") || value.contains("xfce") ||
            value.contains("lxqt") || value.contains("openbox") || value.contains("desktop") -> "SESSION"
        value.contains("x11") || value.contains("display") || value.contains("monitor") ||
            value.contains("xkb") -> "X11"
        value.contains("container") || value.contains("droidspaces") ||
            value.contains("command channel") -> "CONTAINER"
        value.contains("install") || value.contains("package") || value.contains("repository") -> "INSTALL"
        else -> "MANAGER"
    }
}
