package com.saas.x11manager.util

import android.util.Log

/**
 * Reduces the legacy diagnostic stream to a concise lifecycle log for users.
 *
 * Raw package-manager output, generated scripts, DroidSpaces banners, deep [CTX]
 * diagnostics and PulseAudio probe chatter are discarded before they reach Compose.
 * Important start/stop/delete ownership transitions are deliberately retained so a
 * blocking operation never looks idle or blank while real work is happening.
 */
internal class ConciseLogReducer {
    private var installMode = false
    private var insideDroidSpacesBlock = false
    private var lastMessage: String? = null

    fun reduce(level: Int, original: String): List<Pair<Int, String>> {
        val message = AnsiColorParser.stripAnsi(original).trim()
        if (message.isEmpty()) return emptyList()

        val entries = when {
            message.startsWith("--- Installing Graphic Session:") -> {
                installMode = true
                val session = message
                    .removePrefix("--- Installing Graphic Session:")
                    .removeSuffix("---")
                    .trim()
                listOf(level to "[INSTALL] Installing $session")
            }

            message.startsWith("--- Verifying Graphic Session:") -> {
                installMode = true
                val session = message
                    .removePrefix("--- Verifying Graphic Session:")
                    .removeSuffix("---")
                    .trim()
                listOf(level to "[INSTALL] Verifying $session")
            }

            else -> {
                if (message == "--- Start X11 ---" || message == "--- Graphic Access Start ---") {
                    installMode = false
                }
                if (installMode) reduceInstall(level, message) else reduceRuntime(level, message)
            }
        }

        return entries.filter { (_, text) ->
            if (text == lastMessage) {
                false
            } else {
                lastMessage = text
                true
            }
        }
    }

    private fun reduceInstall(level: Int, message: String): List<Pair<Int, String>> {
        if (message.startsWith("---") && message.endsWith("---")) return emptyList()
        if (looksLikeDroidSpacesBlock(message)) return emptyList()
        if (message.startsWith("root@") || message.startsWith("# ")) return emptyList()

        if (message.contains('\n')) {
            return message.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() }
                .flatMap { reduceInstall(level, it).asSequence() }
                .toList()
        }

        val body = removeLegacyMarker(message)
        if (body.isEmpty() || body.equals("OK", ignoreCase = true)) return emptyList()
        if (looksLikePackageManagerNoise(message) || looksLikeGeneratedScriptLine(message)) return emptyList()
        if (looksLikeCommandOutput(body)) return emptyList()

        val entry = when {
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
                Log.ERROR to "[INSTALL] ✗ $body"

            body.startsWith("FAIL", ignoreCase = true) ->
                Log.ERROR to "[INSTALL] ✗ Step failed"

            body.startsWith("Protocol:", ignoreCase = true) ->
                level to "[INSTALL] • $body"

            body.startsWith("Access method:", ignoreCase = true) ->
                level to "[INSTALL] • $body"

            body.startsWith("Use Start X11", ignoreCase = true) ->
                level to "[INSTALL] ✓ Ready to start"

            message.startsWith("[!]") -> Log.WARN to "[INSTALL] ! $body"
            message.startsWith("[-]") || message.startsWith("Error:", ignoreCase = true) ->
                Log.ERROR to "[INSTALL] ✗ $body"

            else -> null
        }
        return listOfNotNull(entry)
    }

    private fun reduceRuntime(level: Int, message: String): List<Pair<Int, String>> {
        // An aggregated DroidSpaces banner/result is one opaque raw command block.
        // Discard it as a whole; caller-owned lifecycle checkpoints are logged next.
        if (looksLikeDroidSpacesBlock(message)) {
            insideDroidSpacesBlock = false
            return emptyList()
        }

        if (message.contains('\n')) {
            val entries = message.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() }
                .flatMap { reduceRuntime(level, it).asSequence() }
                .toList()

            // A multi-line Shell result is self-contained. Never let a banner flag
            // created inside that one result suppress unrelated Manager logs later.
            insideDroidSpacesBlock = false
            return entries
        }

        if (message.startsWith("Welcome to Droidspaces", ignoreCase = true)) {
            insideDroidSpacesBlock = true
            return emptyList()
        }

        if (insideDroidSpacesBlock) {
            val bannerBody = removeLegacyMarker(message)
            val endOfBanner = bannerBody.startsWith("Use 'su -c \"droidspaces", ignoreCase = true)
            val managerBoundary = message.startsWith("---") ||
                message.startsWith("[CTX]") ||
                startsWithSemanticComponent(message)

            when {
                endOfBanner -> {
                    insideDroidSpacesBlock = false
                    return emptyList()
                }

                message.startsWith("[*] Confirming container runtime", ignoreCase = true) ||
                    message.startsWith("[+] Container runtime active", ignoreCase = true) -> {
                    insideDroidSpacesBlock = false
                }

                managerBoundary -> {
                    insideDroidSpacesBlock = false
                    // Continue reducing this first Manager-owned line normally.
                }

                else -> return emptyList()
            }
        }

        contextSummary(level, message)?.let { return listOf(it) }

        if (message.startsWith("[PA-")) return emptyList()
        sectionSummary(message)?.let { return listOf(level to it) }
        if (message.startsWith("---") && message.endsWith("---")) return emptyList()

        val body = removeLegacyMarker(message)

        if (body.startsWith("Starting container", ignoreCase = true)) {
            return listOf(level to "[CONTAINER] Starting container")
        }
        if (body.startsWith("Container runtime active", ignoreCase = true)) {
            insideDroidSpacesBlock = false
            return listOf(level to "[CONTAINER] ✓ Container started")
        }

        // Recovered/intermediate conditions are never retained. The caller emits
        // one final success or failure after the retry/verification path finishes.
        if (body.startsWith("X11 transport socket setup service returned", ignoreCase = true) ||
            body.startsWith("Container X11 transport socket was not visible during the prerequisite check", ignoreCase = true) ||
            body.startsWith("Port 4713 could not be bound", ignoreCase = true) ||
            body.startsWith("Port 4713 could not load", ignoreCase = true) ||
            body.startsWith("Selected audio port:", ignoreCase = true) ||
            body.equals("Graphical startup will continue", ignoreCase = true) ||
            (body.contains("is ready, but", ignoreCase = true) &&
                body.contains("could not be confirmed active", ignoreCase = true)) ||
            body.contains("is ready, but the configured graphic session is not active", ignoreCase = true) ||
            body.equals("Graphic session diagnostics:", ignoreCase = true)) {
            return emptyList()
        }

        // Noise must be rejected before the generic lifecycle-warning fallback.
        if (looksLikePackageManagerNoise(message) || looksLikeDroidSpacesLine(body)) return emptyList()

        runtimeLifecycleSummary(level, message, body)?.let { return listOf(it) }

        if (isRoutineDetail(body)) return emptyList()
        if (!isAllowedRuntimeEvent(message, body)) return emptyList()

        return listOf(level to formatSemanticMessage(message))
    }

    /** Selected [CTX] values become compact, stable lifecycle facts. */
    private fun contextSummary(level: Int, message: String): Pair<Int, String>? {
        if (!message.startsWith("[CTX]")) return null
        val value = message.substringAfter(':', missingDelimiterValue = "").trim()

        return when {
            message.startsWith("[CTX] Access method:") ->
                level to "[SESSION] • Access: $value"

            message.startsWith("[CTX] Session:") ->
                level to "[SESSION] • Desktop: $value"

            message.startsWith("[CTX] Graphic user:") ->
                level to "[USER] • Desktop user: $value"

            message.startsWith("[CTX] Container:") ->
                level to "[CONTAINER] • Container: $value"

            message.startsWith("[CTX] Monitor:") ->
                level to "[X11] • Monitor: $value"

            message.startsWith("[CTX] Display:") || message.startsWith("[CTX] Host display:") ->
                level to "[X11] • Display: $value"

            message.startsWith("[CTX] Assigned display before stop:") ->
                level to "[X11] • Assigned display: $value"

            message.startsWith("[CTX] Container owner:") ->
                level to "[X11] • Owner: $value"

            message.startsWith("[CTX] Container lifecycle:") ->
                level to "[CONTAINER] • Lifecycle: $value"

            message.startsWith("[CTX] Live PIDs before stop:") ->
                level to "[X11] • Server PID(s): $value"

            message.startsWith("[CTX] Socket file before stop:") ->
                level to "[X11] • Socket before stop: $value"

            message.startsWith("[CTX] Live PIDs after stop:") -> {
                if (value.equals("none", ignoreCase = true)) {
                    level to "[X11] ✓ Server process stopped"
                } else {
                    Log.WARN to "[X11] ! Server process still present: $value"
                }
            }

            message.startsWith("[CTX] Socket after stop:") -> {
                if (value.equals("absent", ignoreCase = true)) {
                    level to "[X11] ✓ Socket removed"
                } else {
                    Log.WARN to "[X11] ! Socket still present: $value"
                }
            }

            message.startsWith("[CTX] Runtime policy:") ->
                level to "[X11] • Cleanup: $value"

            message.startsWith("[CTX] Stop duration:") ->
                level to "[X11] • Stop time: $value"

            message.startsWith("[CTX] Server readiness:") ->
                level to "[X11] • Server ready in: $value"

            message.startsWith("[CTX] Total session start duration:") ->
                level to "[SESSION] • Start time: $value"

            else -> null
        }
    }

    /**
     * Converts useful pre/post checkpoints that used to be discarded by the
     * whitelist into stable component-tagged lines. This is intentionally small:
     * it exposes ownership, progress and verification, not shell implementation.
     */
    private fun runtimeLifecycleSummary(
        level: Int,
        message: String,
        body: String
    ): Pair<Int, String>? {
        return when {
            body.startsWith("Preparing container X11 config", ignoreCase = true) ->
                level to "[X11] Preparing container socket mapping"

            body.startsWith("Container X11 configuration confirmed", ignoreCase = true) ->
                level to "[X11] ✓ Container socket mapping ready"

            body.startsWith("Launching integrated X11 app_process", ignoreCase = true) ->
                level to "[X11] Launching embedded X11 server"

            body.startsWith("Waiting up to ", ignoreCase = true) &&
                body.contains("X11 process and socket", ignoreCase = true) ->
                level to "[X11] Waiting for X11 process and socket"

            body.startsWith("Confirming container runtime", ignoreCase = true) ->
                level to "[CONTAINER] Confirming runtime state"

            body.startsWith("Waiting for container command readiness", ignoreCase = true) ->
                level to "[CONTAINER] Waiting for command channel"

            body.startsWith("Container command channel ready", ignoreCase = true) ->
                level to "[CONTAINER] ✓ Command channel ready"

            body.startsWith("Synchronizing configured graphic session", ignoreCase = true) ->
                level to "[SESSION] Starting configured graphical session"

            body.startsWith("Expected container X11 transport socket is visible", ignoreCase = true) ->
                level to "[X11] ✓ Container X11 socket visible"

            body.startsWith("Container completed a real X11 client handshake", ignoreCase = true) ->
                level to "[X11] ✓ X11 client handshake confirmed"

            body.startsWith("Graphic session service confirmed active", ignoreCase = true) ->
                level to "[SESSION] ✓ Graphic service active"

            body.startsWith("Stopping ", ignoreCase = true) &&
                body.contains("graphic session only", ignoreCase = true) ->
                level to "[SESSION] ${body.removeSuffix("...")}"

            body.startsWith("Graphic session stopped; container remains running", ignoreCase = true) ->
                level to "[SESSION] ✓ Graphic session stopped; container remains running"

            body.startsWith("No managed graphic session process needs to be stopped", ignoreCase = true) ->
                level to "[SESSION] ✓ No managed graphical process was running"

            body.startsWith("Sending SIGKILL to server PIDs", ignoreCase = true) ->
                level to "[X11] Terminating X11 server process"

            body.startsWith("Removing all monitor runtime artifacts", ignoreCase = true) ->
                level to "[X11] Removing monitor runtime; container bind stays reserved"

            body.startsWith("Removing complete monitor runtime directory", ignoreCase = true) ->
                level to "[X11] Removing monitor runtime directory"

            body.startsWith("Container stop confirmed", ignoreCase = true) ->
                level to "[CONTAINER] ✓ Container stopped"

            body.startsWith("Released Monitor ", ignoreCase = true) ->
                level to "[X11] ✓ $body"

            body.startsWith("X11 runtime cleanup verified", ignoreCase = true) ->
                level to "[X11] ✓ Runtime cleanup verified"

            body.startsWith("Monitor ", ignoreCase = true) &&
                body.endsWith(" inactive", ignoreCase = true) ->
                level to "[X11] ✓ $body"

            body.startsWith("Container '", ignoreCase = true) &&
                body.contains("was left running", ignoreCase = true) ->
                level to "[CONTAINER] ✓ $body"

            body.startsWith("Empty monitor bind anchor retained", ignoreCase = true) ->
                level to "[X11] ✓ Empty bind anchor retained for running container"

            body.contains("runtime fully released", ignoreCase = true) ->
                level to "[X11] ✓ $body"

            body.startsWith("Monitor removed from the monitor list", ignoreCase = true) ->
                level to "[X11] ✓ Monitor removed from list"

            body.startsWith("Timed-out X11 runtime cleaned", ignoreCase = true) ->
                level to "[X11] ✓ Timed-out runtime cleaned"

            body.startsWith("Rolled back Monitor ", ignoreCase = true) ->
                level to "[X11] ✓ $body"

            // Lifecycle warnings are valuable after transient/recovered cases and
            // DroidSpaces/package-manager noise have already been removed above.
            message.startsWith("[!]") ->
                Log.WARN to formatSemanticMessage(message)

            else -> null
        }
    }

    /**
     * Runtime logging remains whitelist-based for ordinary raw lines. Unknown
     * informational output is diagnostic noise; hard errors and tagged events stay.
     */
    private fun isAllowedRuntimeEvent(message: String, body: String): Boolean {
        if (startsWithSemanticComponent(message)) return true

        if (message.startsWith("[-]") || message.startsWith("Error:", ignoreCase = true)) return true

        if (!message.startsWith("[+]")) return false

        return body.startsWith("Manager audio core already ready", ignoreCase = true) ||
            body.startsWith("Host audio core ready", ignoreCase = true) ||
            body.startsWith("Manager audio core ready", ignoreCase = true) ||
            body.startsWith("Audio configuration disabled", ignoreCase = true) ||
            body.startsWith("Audio ready (", ignoreCase = true) ||
            (body.startsWith("Monitor ", ignoreCase = true) && body.contains(" ready", ignoreCase = true)) ||
            body.contains(" session active on Monitor ", ignoreCase = true) ||
            body.startsWith("Integrated X11 session started", ignoreCase = true) ||
            body.startsWith("Integrated X11 ready", ignoreCase = true) ||
            body.contains("VNC", ignoreCase = true)
    }

    private fun startsWithSemanticComponent(message: String): Boolean =
        message.startsWith("[X11]") || message.startsWith("[SESSION]") ||
            message.startsWith("[USER]") || message.startsWith("[AUDIO]") ||
            message.startsWith("[VNC]") || message.startsWith("[CONTAINER]") ||
            message.startsWith("[INSTALL]") || message.startsWith("[MANAGER]")

    private fun sectionSummary(message: String): String? = when (message) {
        "--- Graphic Access Start ---" -> "[SESSION] Starting graphical access"
        "--- Audio Configuration ---" -> "[AUDIO] Preparing Android audio"
        "--- Starting Integrated X11 Session ---" -> "[X11] Starting Integrated X11"
        "--- Integrated X11 Server Start ---" -> "[X11] Starting monitor server"
        "--- Graphic Session Synchronization ---" -> "[SESSION] Synchronizing graphical session"
        "--- Graphic Session Stop ---" -> "[SESSION] Stopping graphical session"
        "--- Stopping X11 monitor ---" -> "[X11] Stop requested for monitor"
        "--- Integrated X11 Server Stop ---" -> "[X11] Stopping monitor server"
        "--- Stopping Container X11 Session ---" -> "[CONTAINER] Stopping container and releasing X11"
        "--- Deleting X11 monitor ---" -> "[X11] Deleting monitor"
        "--- Integrated X11 Server Rollback ---" -> "[X11] Rolling back monitor server"
        "--- X11 Runtime Reconciliation ---" -> "[X11] Checking runtime ownership"
        "--- Stopping All ---" -> "[MANAGER] Stopping all managed sessions"
        "--- Starting X11 monitor ---" -> "[X11] Start requested for monitor"
        "--- Starting External TigerVNC Session ---" -> "[VNC] Starting standalone VNC"
        "--- Starting TigerVNC Mirror ---" -> "[VNC] Starting X11 VNC mirror"
        else -> null
    }

    private fun isRoutineDetail(body: String): Boolean {
        val prefixes = listOf(
            "Saved access method:", "Saved VNC port:", "User policy:",
            "User-aware graphical session launcher ready", "Assigned Monitor ",
            "Preparing container X11 config", "Reading existing container configuration",
            "Container config read", "Writing updated configuration atomically",
            "Atomic container config update complete", "Integrated X11 container config already ready",
            "Integrated X11 container config ready", "Container X11 configuration confirmed",
            "Inspecting existing server state", "Cleaning stale socket/lock artifacts",
            "Preparing isolated runtime directory", "Stale runtime artifacts cleared",
            "Runtime directory ready:", "Shared XKB cache ready", "Launching integrated X11 app_process",
            "Waiting up to ", "X11 socket:", "Confirming container runtime",
            "Waiting for container command readiness", "Container command channel ready",
            "Synchronizing configured graphic session", "Ensuring ", "Graphic session backend:",
            "Graphic session service was inactive; start requested",
            "Graphic session service confirmed active", "Expected container X11 transport socket is visible",
            "Configuring and verifying the PulseAudio client inside",
            "Authenticated PulseAudio listener ready", "NAT audio transport verified from inside the container"
        )
        if (prefixes.any { body.startsWith(it, ignoreCase = true) }) return true
        return body.startsWith("Monitor:", ignoreCase = true) ||
            body.startsWith("X11 display:", ignoreCase = true) ||
            body.contains(" duration:", ignoreCase = true) ||
            body.contains(" exit code:", ignoreCase = true)
    }

    private fun looksLikePackageManagerNoise(message: String): Boolean {
        val value = message.trimStart()
        return value.startsWith("Get:") || value.startsWith("Hit:") || value.startsWith("Ign:") ||
            value.startsWith("Fetched ") || value.startsWith("Reading package lists") ||
            value.startsWith("Building dependency tree") || value.startsWith("Reading state information") ||
            value.startsWith("Selecting previously unselected package") || value.startsWith("Preparing to unpack") ||
            value.startsWith("Unpacking ") || value.startsWith("Setting up ") ||
            value.startsWith("Processing triggers for") || value.startsWith("debconf:") ||
            value.startsWith("fetch https://") || value.matches(Regex("^\\(\\s*\\d+/\\d+\\) .+")) ||
            value.startsWith("Executing ") || value.startsWith("OK: ") ||
            value.matches(Regex("^v\\d+\\..*https?://.*"))
    }

    private fun looksLikeGeneratedScriptLine(message: String): Boolean {
        val value = message.trim()
        return value.startsWith("X11_SOCKET=") || value.startsWith("SESSION_") ||
            value.startsWith("requested_") || value.startsWith("export ") ||
            value.startsWith("if [") || value.startsWith("elif ") || value == "else" || value == "fi" ||
            value == "done" || value.startsWith("case ") || value == "esac" || value.startsWith("for ") ||
            value.startsWith("exec ") || value.startsWith("mkdir ") || value.startsWith("chmod ") ||
            value.startsWith("chown ") || value.startsWith("mount ") || value.startsWith("umount ") ||
            value.startsWith("description=") || value.startsWith("command=") || value.startsWith("command_") ||
            value.startsWith("pidfile=") || value.startsWith("stopgroup=") || value.startsWith("depend()") ||
            value.startsWith("start()") || value.startsWith("stop()") || value.startsWith("ebegin ") ||
            value.startsWith("eend ") || value.startsWith("eerror ") || value.startsWith("return ") ||
            value.startsWith("rc=")
    }

    private fun looksLikeCommandOutput(body: String): Boolean =
        body.startsWith("/usr/bin/") || body.startsWith("/usr/local/bin/") ||
            body.startsWith("https://") || body.startsWith("http://")

    private fun normalizeInstallTitle(body: String): String =
        body.replace("openrc", "OpenRC", ignoreCase = true)
            .replace("systemd", "systemd", ignoreCase = true)

    private fun looksLikeDroidSpacesBlock(message: String): Boolean =
        message.contains("Welcome to Droidspaces", ignoreCase = true) &&
            message.lineSequence().any { it.trimStart().startsWith("Container:") }

    private fun looksLikeDroidSpacesLine(body: String): Boolean {
        val value = body.trim()
        return value.startsWith("WARNING: PRIVILEGED MODE ACTIVE", ignoreCase = true) ||
            value.startsWith("Your kernel (", ignoreCase = true) ||
            value.startsWith("Using legacy Cgroup", ignoreCase = true) ||
            value.startsWith("Host: Android", ignoreCase = true) ||
            value.startsWith("Container:", ignoreCase = true) ||
            value == "Features:" ||
            value.startsWith("Networking:", ignoreCase = true) ||
            value.startsWith("NAT IP:", ignoreCase = true) ||
            value.startsWith("Android storage:", ignoreCase = true) ||
            value.startsWith("HW access:", ignoreCase = true) ||
            value.startsWith("SELinux:", ignoreCase = true) ||
            value.startsWith("Force Cgroup V1:", ignoreCase = true) ||
            value.startsWith("User namespaces:", ignoreCase = true) ||
            value.startsWith("Privileged mode:", ignoreCase = true) ||
            value.startsWith("Bind mounts:", ignoreCase = true) ||
            value.startsWith("Use 'su -c \"droidspaces", ignoreCase = true)
    }

    private fun removeLegacyMarker(message: String): String = when {
        message.startsWith("[+]") -> message.removePrefix("[+]").trim()
        message.startsWith("[*]") -> message.removePrefix("[*]").trim()
        message.startsWith("[!]") -> message.removePrefix("[!]").trim()
        message.startsWith("[-]") -> message.removePrefix("[-]").trim()
        else -> message.trim()
    }

    private fun formatSemanticMessage(message: String): String {
        if (startsWithSemanticComponent(message)) return message

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
            value.contains("pulseaudio") || value.contains("audio") || value.contains("aaudio") ||
                value.contains("opensl") || (value.contains("listener") && value.contains("container client")) ||
                (value.startsWith("port ") && value.contains("bound")) -> "AUDIO"
            value.contains("vnc") -> "VNC"
            value.contains("install") || value.contains("package") || value.contains("repository") -> "INSTALL"
            value.contains("user") || value.contains("account") -> "USER"
            value.contains("session") || value.contains("icewm") || value.contains("xfce") ||
                value.contains("lxqt") || value.contains("openbox") || value.contains("desktop") -> "SESSION"
            value.contains("x11") || value.contains("display") || value.contains("monitor") || value.contains("xkb") -> "X11"
            value.contains("container") || value.contains("droidspaces") || value.contains("command channel") -> "CONTAINER"
            else -> "MANAGER"
        }
    }
}
