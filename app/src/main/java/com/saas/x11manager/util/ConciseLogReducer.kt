package com.saas.x11manager.util

import android.util.Log

/**
 * Reduces the legacy diagnostic stream to the small semantic log shown to users.
 *
 * This runs before ViewModel storage, so raw package-manager output, generated
 * scripts, [CTX] diagnostics, PulseAudio probes and the DroidSpaces banner never
 * accumulate in Compose state. There is intentionally no secondary/details log.
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
                if (installMode) {
                    reduceInstall(level, message)
                } else {
                    reduceRuntime(level, message)
                }
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
        if (looksLikeDroidSpacesBlock(message)) return emptyList()

        if (message.contains('\n')) {
            return message.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() }
                .flatMap { reduceRuntime(level, it).asSequence() }
                .toList()
        }

        if (message.startsWith("Welcome to Droidspaces", ignoreCase = true)) {
            insideDroidSpacesBlock = true
            return emptyList()
        }
        if (insideDroidSpacesBlock) {
            if (message.startsWith("[*] Confirming container runtime", ignoreCase = true) ||
                message.startsWith("[+] Container runtime active", ignoreCase = true)) {
                insideDroidSpacesBlock = false
            } else {
                return emptyList()
            }
        }

        if (message.startsWith("[CTX]")) {
            val entry = when {
                message.startsWith("[CTX] Access method:") ->
                    level to "[SESSION] • Access: ${message.substringAfter(':').trim()}"
                message.startsWith("[CTX] Session:") ->
                    level to "[SESSION] • Desktop: ${message.substringAfter(':').trim()}"
                message.startsWith("[CTX] Graphic user:") ->
                    level to "[USER] • Desktop user: ${message.substringAfter(':').trim()}"
                else -> null
            }
            return listOfNotNull(entry)
        }

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

        if (isRoutineDetail(body) || looksLikePackageManagerNoise(message)) return emptyList()

        // These are recovered/intermediate conditions. Final success/failure is
        // already logged separately and is the only thing useful in the compact UI.
        if (body.startsWith("X11 transport socket setup service returned", ignoreCase = true) ||
            body.startsWith("Container X11 transport socket was not visible during the prerequisite check", ignoreCase = true) ||
            body.startsWith("Port 4713 could not be bound", ignoreCase = true) ||
            body.startsWith("Port 4713 could not load", ignoreCase = true) ||
            body.startsWith("Selected audio port:", ignoreCase = true) ||
            body.equals("Graphical startup will continue", ignoreCase = true)) {
            return emptyList()
        }

        if (looksLikeDroidSpacesLine(body)) return emptyList()

        return listOf(level to formatSemanticMessage(message))
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
        if (message.startsWith("[X11]") || message.startsWith("[SESSION]") ||
            message.startsWith("[USER]") || message.startsWith("[AUDIO]") ||
            message.startsWith("[VNC]") || message.startsWith("[CONTAINER]") ||
            message.startsWith("[INSTALL]") || message.startsWith("[MANAGER]")) return message

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
