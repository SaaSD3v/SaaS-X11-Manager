package com.saas.x11manager.util

import android.util.Log
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConciseLogReducerTest {

    @Test
    fun runtimeStoresOnlySemanticMessages() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.INFO, "--- Graphic Access Start ---"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Access method: Integrated X11"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Session: IceWM"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Graphic user: SaaS"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Runtime: /data/local/tmp/saas-x11"))
            addAll(reducer.reduce(Log.INFO, "[+] Integrated X11 session started on :0"))
            addAll(reducer.reduce(Log.INFO, "[+] Integrated X11 ready on :0"))
        }.map { it.second }

        assertTrue(output.contains("[SESSION] Starting graphical access"))
        assertTrue(output.contains("[SESSION] • Access: Integrated X11"))
        assertTrue(output.contains("[SESSION] • Desktop: IceWM"))
        assertTrue(output.contains("[USER] • Desktop user: SaaS"))
        assertTrue(output.contains("[X11] ✓ Integrated X11 session started on :0"))
        assertTrue(output.contains("[X11] ✓ Integrated X11 ready on :0"))
        assertFalse(output.any { it.contains("Runtime:") })
    }

    @Test
    fun droidSpacesAndPackageManagerNoiseAreDiscardedBeforeStorage() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.INFO, "[*] Starting container..."))
            addAll(reducer.reduce(Log.INFO, "Welcome to Droidspaces v6.5.0 !"))
            addAll(reducer.reduce(Log.WARN, "WARNING: PRIVILEGED MODE ACTIVE - DEVICE SECURITY COMPROMISED"))
            addAll(reducer.reduce(Log.WARN, "[!] Your kernel (3.18) is below recommended 4.14"))
            addAll(reducer.reduce(Log.INFO, "Container: SaaS (RUNNING)"))
            addAll(reducer.reduce(Log.INFO, "[+] Container runtime active (PID=1234)"))
            addAll(reducer.reduce(Log.INFO, "Get:1 https://example.invalid stable InRelease"))
            addAll(reducer.reduce(Log.INFO, "( 1/86) Installing libxau (1.0.12-r0)"))
        }.map { it.second }

        assertTrue(output.contains("[CONTAINER] Starting container"))
        assertTrue(output.contains("[CONTAINER] ✓ Container started"))
        assertFalse(output.any { it.contains("Droidspaces") })
        assertFalse(output.any { it.contains("PRIVILEGED") })
        assertFalse(output.any { it.contains("kernel (3.18)") })
        assertFalse(output.any { it.contains("libxau") })
        assertFalse(output.any { it.contains("example.invalid") })
    }

    @Test
    fun installationKeepsOnlyLifecycleAndNamedSteps() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.INFO, "--- Installing Graphic Session: IceWM ---"))
            addAll(reducer.reduce(Log.INFO, "[+] Checking container runtime"))
            addAll(reducer.reduce(Log.INFO, "[*] Container is stopped; starting it temporarily for installation..."))
            addAll(reducer.reduce(Log.INFO, "[+] Preparing Alpine community repository"))
            addAll(reducer.reduce(Log.INFO, "root@alpine-host: apk update"))
            addAll(reducer.reduce(Log.INFO, "[+] Refreshing package index"))
            addAll(reducer.reduce(Log.INFO, "[+] Installing IceWM packages"))
            addAll(reducer.reduce(Log.INFO, "( 1/86) Installing libxau (1.0.12-r0)"))
            addAll(reducer.reduce(Log.INFO, "[+] IceWM installation completed successfully"))
        }.map { it.second }

        assertTrue(output.contains("[INSTALL] Installing IceWM"))
        assertTrue(output.contains("[CONTAINER] Checking container runtime"))
        assertTrue(output.contains("[CONTAINER] Starting container temporarily"))
        assertTrue(output.contains("[INSTALL] Preparing Alpine community repository"))
        assertTrue(output.contains("[INSTALL] Refreshing package index"))
        assertTrue(output.contains("[INSTALL] Installing IceWM packages"))
        assertTrue(output.contains("[INSTALL] ✓ IceWM installation completed successfully"))
        assertFalse(output.any { it.contains("root@alpine-host") })
        assertFalse(output.any { it.contains("libxau") })
    }

    @Test
    fun transientRecoveredWarningsAreNotStored() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.WARN, "[!] X11 transport socket setup service returned 1"))
            addAll(reducer.reduce(Log.WARN, "[!] Container X11 transport socket was not visible during the prerequisite check"))
            addAll(reducer.reduce(Log.WARN, "[!] Port 4713 could not load an authenticated listener on 127.0.0.1; selecting another audio port automatically"))
            addAll(reducer.reduce(Log.INFO, "[+] Audio ready (AAudio_sink, tcp:127.0.0.1:4714)"))
        }.map { it.second }

        assertFalse(output.any { it.contains("socket setup service") })
        assertFalse(output.any { it.contains("socket was not visible") })
        assertFalse(output.any { it.contains("Port 4713") })
        assertTrue(output.contains("[AUDIO] ✓ Audio ready (AAudio_sink, tcp:127.0.0.1:4714)"))
    }

    @Test
    fun graphicalRetryShowsOnlyFinalFailure() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.WARN, "[!] :0 is ready, but IceWM could not be confirmed active (exit 1)"))
            addAll(reducer.reduce(Log.WARN, "[!] :0 is ready, but the configured graphic session is not active"))
            addAll(reducer.reduce(Log.ERROR, "[-] IceWM did not become active on :0"))
        }.map { it.second }

        assertFalse(output.any { it.contains("could not be confirmed active") })
        assertFalse(output.any { it.contains("configured graphic session is not active") })
        assertTrue(output.contains("[SESSION] ✗ IceWM did not become active on :0"))
    }
}
