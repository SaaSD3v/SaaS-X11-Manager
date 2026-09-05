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
}
