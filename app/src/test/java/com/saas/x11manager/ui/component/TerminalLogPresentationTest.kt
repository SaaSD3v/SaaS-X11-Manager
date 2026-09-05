package com.saas.x11manager.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLogPresentationTest {

    @Test
    fun simpleViewKeepsSemanticContextAndHidesTechnicalContext() {
        val logs = listOf(
            4 to "--- Graphic Access Start ---",
            4 to "[CTX] Access method: Integrated X11",
            4 to "[CTX] Session: IceWM",
            4 to "[CTX] Graphic user: SaaS",
            4 to "[CTX] Selected runtime: /data/local/tmp/saas-x11/display-0",
            4 to "[+] Integrated X11 ready on Monitor 1 (:0)"
        )

        val simple = presentTerminalLogs(logs, showDetails = false).map { it.second }

        assertTrue(simple.contains("[SESSION] Starting graphical access"))
        assertTrue(simple.contains("[SESSION] • Access: Integrated X11"))
        assertTrue(simple.contains("[SESSION] • Desktop: IceWM"))
        assertTrue(simple.contains("[USER] • Desktop user: SaaS"))
        assertFalse(simple.any { it.contains("Selected runtime") })
        assertTrue(simple.contains("[X11] ✓ Integrated X11 ready on Monitor 1 (:0)"))
    }

    @Test
    fun detailsViewIsLossless() {
        val logs = listOf(
            4 to "[CTX] Runtime: /tmp/runtime",
            5 to "[PA-NAT-PROBE] Server String: tcp:172.28.0.1:4715"
        )

        assertEquals(logs, presentTerminalLogs(logs, showDetails = true))
    }

    @Test
    fun recoveredTransportWarningsDisappearAfterSuccessfulOutcome() {
        val logs = listOf(
            5 to "[!] X11 transport socket setup service returned 1",
            5 to "[!] Container X11 transport socket was not visible during the prerequisite check",
            4 to "[+] IceWM X11 session active on Monitor 1 (:0)",
            5 to "[!] Port 4713 could not be bound or verified on 172.28.0.1; selecting another automatically",
            4 to "[+] Audio ready (AAudio_sink, tcp:172.28.0.1:4715)"
        )

        val simple = presentTerminalLogs(logs, showDetails = false).map { it.second }

        assertFalse(simple.any { it.contains("socket setup service returned") })
        assertFalse(simple.any { it.contains("socket was not visible") })
        assertFalse(simple.any { it.contains("Port 4713") })
        assertTrue(simple.any { it == "[SESSION] ✓ IceWM X11 session active on Monitor 1 (:0)" })
        assertTrue(simple.any { it == "[AUDIO] ✓ Audio ready (AAudio_sink, tcp:172.28.0.1:4715)" })
    }

    @Test
    fun droidSpacesStartupBlockStaysOutOfSimpleViewAndLifecycleRemainsClear() {
        val raw = """
            Welcome to Droidspaces v6.5.0 !
            WARNING: PRIVILEGED MODE ACTIVE - DEVICE SECURITY COMPROMISED
            [!] Your kernel (3.18) is below recommended 4.14 - some functions might be unstable.
            [!] Using legacy Cgroup V1 hierarchy (forced by --force-cgroupv1)
            Container: SaaS (RUNNING)
              OS: Debian GNU/Linux 13 (trixie)
              NAT IP: 172.28.12.12
            [+] Container 'SaaS' is running in background.
        """.trimIndent()
        val logs = listOf(
            4 to "[*] Starting container...",
            4 to raw,
            4 to "[+] Container runtime active (PID=47588)"
        )

        val simple = presentTerminalLogs(logs, showDetails = false).map { it.second }

        assertEquals(
            listOf(
                "[CONTAINER] Starting container",
                "[CONTAINER] ✓ Container started"
            ),
            simple
        )
        assertFalse(simple.any { it.contains("Droidspaces") })
        assertFalse(simple.any { it.contains("PRIVILEGED MODE ACTIVE") })
        assertFalse(simple.any { it.contains("kernel (3.18)") })
        assertFalse(simple.any { it.contains("Cgroup V1") })
        assertFalse(simple.any { it.contains("Debian GNU/Linux") })
        assertFalse(simple.any { it.contains("172.28.12.12") })
        assertEquals(logs, presentTerminalLogs(logs, showDetails = true))
    }

    @Test
    fun graphicInstallationSimpleViewShowsOnlyLifecycleAndSteps() {
        val droidSpaces = """
            Welcome to Droidspaces v6.5.0 !
            WARNING: PRIVILEGED MODE ACTIVE - DEVICE SECURITY COMPROMISED
            Container: alpine-host (RUNNING)
              OS: Alpine Linux v3.23
        """.trimIndent()
        val generatedLauncherCommand = """
            root@alpine-host: mkdir -p '/usr/local/bin' && printf '%s' '#!/bin/sh
            X11_SOCKET=
            for candidate in /tmp/.X11-unix/X*; do
                [ -S "$candidate" ] || continue
            done
            ' > '/usr/local/bin/x11-session.sh' && chmod 755 '/usr/local/bin/x11-session.sh'
        """.trimIndent()
        val logs = listOf(
            4 to "--- Installing Graphic Session: IceWM ---",
            4 to "[+] Checking container runtime",
            4 to "[*] Container is stopped; starting it temporarily for installation...",
            4 to droidSpaces,
            4 to "[+] Container command channel ready (PID=51405)",
            4 to "[+] OK",
            4 to "[+] Preparing Alpine community repository",
            4 to "root@alpine-host: apk update",
            4 to "[+] Refreshing package index",
            4 to "v3.23.5-169-g5db2badfbc9 [https://dl-cdn.alpinelinux.org/alpine/v3.23/main]",
            4 to "[+] Installing IceWM packages",
            4 to "( 1/86) Installing libxau (1.0.12-r0)",
            4 to "[+] Validating IceWM session command",
            4 to "root@alpine-host: command -v icewm-session",
            4 to "/usr/bin/icewm-session",
            4 to "[+] Configuring openrc startup",
            4 to "[+] Writing X11 session launcher",
            4 to generatedLauncherCommand,
            4 to "[+] Saving Package Platform / Init System / Graphic Session",
            4 to "[+] Saved package platform, init system and graphic session atomically",
            4 to "[+] IceWM setup completed",
            4 to "[*] Restoring original stopped container state...",
            4 to "[+] Stopping container 'alpine-host' (PID 51405)...",
            4 to "[+] Container 'alpine-host' stopped.",
            4 to "[+] Container restored to stopped state",
            4 to "[+] IceWM installation completed successfully",
            4 to "[+] Protocol: X11",
            4 to "[+] Access method: Integrated X11",
            4 to "[+] Use Start X11 below to launch this container now"
        )

        val simple = presentTerminalLogs(logs, showDetails = false).map { it.second }

        assertTrue(simple.contains("[INSTALL] Installing IceWM"))
        assertTrue(simple.contains("[CONTAINER] Checking container runtime"))
        assertTrue(simple.contains("[CONTAINER] Starting container temporarily"))
        assertTrue(simple.contains("[CONTAINER] ✓ Container ready"))
        assertTrue(simple.contains("[INSTALL] Preparing Alpine community repository"))
        assertTrue(simple.contains("[INSTALL] Refreshing package index"))
        assertTrue(simple.contains("[INSTALL] Installing IceWM packages"))
        assertTrue(simple.contains("[INSTALL] Validating IceWM session command"))
        assertTrue(simple.contains("[INSTALL] Configuring OpenRC startup"))
        assertTrue(simple.contains("[INSTALL] Writing X11 session launcher"))
        assertTrue(simple.contains("[INSTALL] ✓ Session configuration saved"))
        assertTrue(simple.contains("[INSTALL] ✓ IceWM setup completed"))
        assertTrue(simple.contains("[CONTAINER] Restoring original container state"))
        assertTrue(simple.contains("[CONTAINER] Stopping temporary container"))
        assertTrue(simple.contains("[CONTAINER] ✓ Original stopped state restored"))
        assertTrue(simple.contains("[INSTALL] ✓ IceWM installation completed successfully"))
        assertTrue(simple.contains("[INSTALL] • Protocol: X11"))
        assertTrue(simple.contains("[INSTALL] • Access method: Integrated X11"))
        assertTrue(simple.contains("[INSTALL] ✓ Ready to start"))

        assertFalse(simple.any { it.contains("root@alpine-host") })
        assertFalse(simple.any { it.contains("X11_SOCKET") })
        assertFalse(simple.any { it.contains("libxau") })
        assertFalse(simple.any { it.contains("dl-cdn.alpinelinux.org") })
        assertFalse(simple.any { it.contains("PRIVILEGED MODE ACTIVE") })
        assertFalse(simple.any { it == "[MANAGER] ✓ OK" })
        assertEquals(logs, presentTerminalLogs(logs, showDetails = true))
    }

    @Test
    fun successfulAudioPortFallbackDoesNotPolluteSimpleView() {
        val logs = listOf(
            5 to "[!] Port 4713 could not load an authenticated listener on 127.0.0.1; selecting another audio port automatically",
            4 to "[+] PulseAudio listener module loaded: id=44 endpoint=127.0.0.1:4714",
            5 to "[!] Container audio client configuration failed for tcp:127.0.0.1:4714",
            5 to "[!] Listener loaded successfully but the container client could not be configured",
            5 to "[!] Graphical startup will continue",
            4 to "[+] Integrated X11 ready on Monitor 2 (:1)"
        )

        val simple = presentTerminalLogs(logs, showDetails = false).map { it.second }

        assertFalse(simple.any { it.contains("Port 4713") })
        assertFalse(simple.any { it.contains("Graphical startup will continue") })
        assertTrue(simple.contains("[AUDIO] ! Container audio client configuration failed for tcp:127.0.0.1:4714"))
        assertTrue(simple.contains("[AUDIO] ! Listener loaded successfully but the container client could not be configured"))
        assertTrue(simple.contains("[X11] ✓ Integrated X11 ready on Monitor 2 (:1)"))
    }
}
