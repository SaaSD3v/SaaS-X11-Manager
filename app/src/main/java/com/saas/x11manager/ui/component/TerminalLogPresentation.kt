package com.saas.x11manager.ui.component

import com.saas.x11manager.util.AnsiColorParser

/**
 * Presentation-only view of the diagnostic log stream.
 *
 * Runtime code keeps emitting the full lossless log because it is invaluable for
 * support. The normal terminal view instead shows semantic checkpoints and
 * actionable warnings. The Details view returns the original records unchanged.
 * No X11, container, package, VNC or audio behavior is changed here.
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
    val audioListenerReady = plain.any {
        it.contains("PulseAudio listener module loaded", ignoreCase = true) ||
            it.contains("Authenticated PulseAudio listener ready", ignoreCase = true)
    }

    var installLikeMode = false
    val presented = buildList {
        logs.forEach { (level, original) ->
            val plainMessage = AnsiColorParser.stripAnsi(original).trim()
            if (plainMessage.isEmpty()) return@forEach

            when {
                plainMessage.startsWith("--- Installing Graphic Session:") -> {
                    installLikeMode = true
                    val session = plainMessage
                        .removePrefix("--- Installing Graphic Session:")
                        .removeSuffix("---")
                        .trim()
                    add(level to "[INSTALL] Installing $session")
                    return@forEach
                }

                plainMessage.startsWith("--- Verifying Graphic Session:") -> {
                    installLikeMode = true
                    val session = plainMessage
                        .removePrefix("--- Verifying Graphic Session:")
                        .removeSuffix("---")
                        .trim()
                    add(level to "[INSTALL] Verifying $session")
                    return@forEach
                }

                plainMessage == "--- Start X11 ---" ||
                    plainMessage == "--- Graphic Access Start ---" -> {
                    installLikeMode = false
                }
            }

            if (installLikeMode) {
                presentInstallMessage(level, plainMessage)?.let(::add)
                return@forEach
            }

            // DroidSpaces owns this block. Do not reinterpret, shorten or duplicate
            // its banner, feature list or warnings in the concise Manager view.
            // The complete original block remains available unchanged in Details.
            if (looksLikeDroidSpacesStartBlock(plainMessage)) return@forEach

            if (plainMessage.contains('\n')) {
                plainMessage.lineSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() }
                    .forEach { line ->
                        presentRuntimeMessage(
                            level = level,
                            message = line,
                            x11Ready = x11Ready,
                            sessionReady = sessionReady,
                            audioReady = audioReady,
                            audioListenerReady = audioListenerReady
                        )?.let(::add)
                    }
                return@forEach
            }

            presentRuntimeMessage(
                level = level,
                message = plainMessage,
                x11Ready = x11Ready,
                sessionReady = sessionReady,
                audioReady = audioReady,
                audioListenerReady = audioListenerReady
            )?.let(::add)
        }
    }

    return presented.fold(mutableListOf<Pair<Int, String>>()) { output, entry ->
        if (output.lastOrNull()?.second != entry.second) output.add(entry)
        output
    }
}

private fun presentInstallMessage(level: Int, message: String): Pair<Int, String>? {
    if (message.startsWith("---") && message.endsWith("---")) return null
    if (looksLikeDroidSpacesStartBlock(message)) return null

    // A runStep command can contain an entire generated shell script. Never split
    // or render that command in Simple; it remains byte-for-byte available in Details.
    if (message.startsWith("root@") || message.startsWith("# ")) return null

    if (message.contains('\n')) {
        val meaningful = message.lineSequence()
            .map(String::trim)
            .firstOrNull { line ->
                line.isNotEmpty() &&
                    !looksLikePackageManagerNoise(line) &&
                    !looksLikeGeneratedScriptLine(line)
            }
        return meaningful?.let { presentInstallMessage(level, it) }
    }

    val body = removeLegacyMarker(message)
    if (body.isEmpty() || body.equals("OK", ignoreCase = true)) return null
    if (looksLikePackageManagerNoise(message) || looksLikeGeneratedScriptLine(message)) return null
    if (looksLikeCommandOutput(body)) return null

    return when {
        body.equals("Checking container runtime", ignoreCase = true) ->
            level to "[CONTAINER] Checking container runtime"

        body.startsWith("Container is stopped; starting it temporarily for", ignoreCase = true) ->
            level to "[CONTAINER] Starting container temporarily"

        body.startsWith("Container already running", ignoreCase = true) ->
            level to "[CONTAINER] ✓ Container already running"

        body.startsWith("Container command channel ready", ignoreCase = true) ->
            level to "[CONTAINER] ✓ Container ready"

        body.startsWith("Waiting for container command readiness", ignoreCase = true) -> null

        body.startsWith("Restoring original stopped container state", ignoreCase = true) ->
            level to "[CONTAINER] Restoring original container state"

        body.startsWith("Stopping container '", ignoreCase = true) ->
            level to "[CONTAINER] Stopping temporary container"

        body.startsWith("Waiting for graceful shutdown", ignoreCase = true) -> null

        body.contains("stopped.", ignoreCase = true) && body.startsWith("Container '") -> null

        body.equals("Container restored to stopped state", ignoreCase = true) ->
            level to "[CONTAINER] ✓ Original stopped state restored"

        body.startsWith("Container already stopped after", ignoreCase = true) ||
            body.equals("Container stopped", ignoreCase = true) -> null

        body.startsWith("Configuring ", ignoreCase = true) &&
            body.endsWith(" startup", ignoreCase = true) ->
            level to "[INSTALL] ${normalizeInstallTitle(body)}"

        body.startsWith("Preparing ", ignoreCase = true) ||
            body.startsWith("Refreshing ", ignoreCase = true) ||
            body.startsWith("Installing ", ignoreCase = true) ||
            body.startsWith("Validating ", ignoreCase = true) ||
            body.startsWith("Checking ", ignoreCase = true) ||
            body.startsWith("Writing ", ignoreCase = true) ||
            body.startsWith("Enabling ", ignoreCase = true) ||
            body.startsWith("Creating ", ignoreCase = true) ||
            body.startsWith("Saving ", ignoreCase = true) ->
            level to "[INSTALL] ${normalizeInstallTitle(body)}"

        body.startsWith("Saved package platform", ignoreCase = true) ->
            level to "[INSTALL] ✓ Session configuration saved"

        body.endsWith(" setup completed", ignoreCase = true) ||
            body.endsWith(" installation completed successfully", ignoreCase = true) ||
            body.endsWith(" verification completed", ignoreCase = true) ->
            level to "[INSTALL] ✓ $body"

        body.contains("installation aborted", ignoreCase = true) ||
            body.contains("startup configuration aborted", ignoreCase = true) ||
            body.contains("verification failed", ignoreCase = true) ->
            level to "[INSTALL] ✗ $body"

        body.startsWith("FAIL", ignoreCase = true) ->
            level to "[INSTALL] ✗ ${body.replaceFirst("FAIL", "Step failed", ignoreCase = true)}"

        body.startsWith("Protocol:", ignoreCase = true) ->
            level to "[INSTALL] • $body"

        body.startsWith("Access method:", ignoreCase = true) ->
            level to "[INSTALL] • $body"

        body.startsWith("Use Start X11", ignoreCase = true) ->
            level to "[INSTALL] ✓ Ready to start"

        message.startsWith("[!]") -> level to "[INSTALL] ! $body"
        message.startsWith("[-]") || message.startsWith("Error:", ignoreCase = true) ->
            level to "[INSTALL] ✗ $body"

        else -> null
    }
}

private fun presentRuntimeMessage(
    level: Int,
    message: String,
    x11Ready: Boolean,
    sessionReady: Boolean,
    audioReady: Boolean,
    audioListenerReady: Boolean
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

    if (body.startsWith("Starting container", ignoreCase = true)) {
        return level to "[CONTAINER] Starting container"
    }
    if (body.startsWith("Container runtime active", ignoreCase = true)) {
        return level to "[CONTAINER] ✓ Container started"
    }

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
    ) return null

    // A failed preferred port is not a user-visible failure once another listener
    // was loaded successfully, even if the later container-side audio setup fails.
    if (
        audioListenerReady &&
        (
            body.startsWith("Port 4713 could not be bound", ignoreCase = true) ||
                body.startsWith("Port 4713 could not load", ignoreCase = true) ||
                body.startsWith("Selected audio port:", ignoreCase = true)
            )
    ) return null

    if (
        audioReady &&
        (
            body.startsWith("Authenticated PulseAudio listener ready", ignoreCase = true) ||
                body.startsWith("NAT audio transport verified from inside the container", ignoreCase = true)
            )
    ) return null

    if (
        x11Ready &&
        (
            body.startsWith("Stale runtime artifacts cleared", ignoreCase = true) ||
                body.startsWith("Runtime directory ready:", ignoreCase = true) ||
                body.startsWith("Shared XKB cache ready", ignoreCase = true) ||
                body.equals("Graphical startup will continue", ignoreCase = true)
            )
    ) return null

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
        "Reading existing container configuration",
        "Container config read",
        "Writing updated configuration atomically",
        "Atomic container config update complete",
        "Integrated X11 container config already ready",
        "Integrated X11 container config ready",
        "Container X11 configuration confirmed",
        "Inspecting existing server state",
        "Cleaning stale socket/lock artifacts",
        "Preparing isolated runtime directory",
        "Launching integrated X11 app_process",
        "Waiting up to ",
        "X11 socket:",
        "Confirming container runtime",
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
        value.matches(Regex("^\\(\\s*\\d+/\\d+\\) .+")) ||
        value.startsWith("Executing ") ||
        value.startsWith("OK: ") ||
        value.matches(Regex("^v\\d+\\..*https?://.*"))
}

private fun looksLikeGeneratedScriptLine(message: String): Boolean {
    val value = message.trim()
    return value.startsWith("X11_SOCKET=") ||
        value.startsWith("SESSION_") ||
        value.startsWith("requested_") ||
        value.startsWith("export ") ||
        value.startsWith("if [") ||
        value.startsWith("elif ") ||
        value == "else" ||
        value == "fi" ||
        value == "done" ||
        value.startsWith("case ") ||
        value == "esac" ||
        value.startsWith("for ") ||
        value.startsWith("exec ") ||
        value.startsWith("mkdir ") ||
        value.startsWith("chmod ") ||
        value.startsWith("chown ") ||
        value.startsWith("mount ") ||
        value.startsWith("umount ") ||
        value.startsWith("description=") ||
        value.startsWith("command=") ||
        value.startsWith("command_") ||
        value.startsWith("pidfile=") ||
        value.startsWith("stopgroup=") ||
        value.startsWith("depend()") ||
        value.startsWith("start()") ||
        value.startsWith("stop()") ||
        value.startsWith("ebegin ") ||
        value.startsWith("eend ") ||
        value.startsWith("eerror ") ||
        value.startsWith("return ") ||
        value.startsWith("rc=")
}

private fun looksLikeCommandOutput(body: String): Boolean =
    body.startsWith("/usr/bin/") ||
        body.startsWith("/usr/local/bin/") ||
        body.startsWith("https://") ||
        body.startsWith("http://")

private fun normalizeInstallTitle(body: String): String =
    body.replace("openrc", "OpenRC", ignoreCase = true)
        .replace("systemd", "systemd", ignoreCase = true)

private fun looksLikeDroidSpacesStartBlock(message: String): Boolean =
    message.contains("Welcome to Droidspaces", ignoreCase = true) &&
        message.lineSequence().any { it.trimStart().startsWith("Container:") }

private fun removeLegacyMarker(message: String): String = when {
    message.startsWith("[+]") -> message.removePrefix("[+]").trim()
    message.startsWith("[*]") -> message.removePrefix("[*]").trim()
    message.startsWith("[!]") -> message.removePrefix("[!]").trim()
    message.startsWith("[-]") -> message.removePrefix("[-]").trim()
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
    ) return message

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
            value.contains("aaudio") || value.contains("opensl") ||
            (value.contains("listener") && value.contains("container client")) ||
            (value.startsWith("port ") && value.contains("bound")) -> "AUDIO"
        value.contains("vnc") -> "VNC"
        value.contains("install") || value.contains("package") || value.contains("repository") -> "INSTALL"
        value.contains("user") || value.contains("account") -> "USER"
        value.contains("session") || value.contains("icewm") || value.contains("xfce") ||
            value.contains("lxqt") || value.contains("openbox") || value.contains("desktop") -> "SESSION"
        value.contains("x11") || value.contains("display") || value.contains("monitor") ||
            value.contains("xkb") -> "X11"
        value.contains("container") || value.contains("droidspaces") ||
            value.contains("command channel") -> "CONTAINER"
        else -> "MANAGER"
    }
}
