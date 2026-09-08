package com.saas.x11manager.util

import android.util.Log
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConciseLogReducerTest {

    @Test
    fun runtimeStoresOnlySemanticMessagesForFixedDisplay() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.INFO, "--- Graphic Access Start ---"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Access method: Integrated X11"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Session: IceWM"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Graphic user: SaaS"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Selected runtime: /data/local/tmp/saas-x11"))
            addAll(reducer.reduce(Log.INFO, "[+] Monitor 1 (:0) ready (PID=1234)"))
            addAll(reducer.reduce(Log.INFO, "[+] Integrated X11 ready on Monitor 1 (:0)"))
        }.map { it.second }

        assertTrue(output.contains("[SESSION] Starting graphical access"))
        assertTrue(output.contains("[SESSION] • Access: Integrated X11"))
        assertTrue(output.contains("[SESSION] • Desktop: IceWM"))
        assertTrue(output.contains("[USER] • Desktop user: SaaS"))
        assertTrue(output.contains("[X11] ✓ Monitor 1 (:0) ready (PID=1234)"))
        assertTrue(output.contains("[X11] ✓ Integrated X11 ready on Monitor 1 (:0)"))
        assertFalse(output.any { it.contains("Selected runtime") })
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
    fun graphicalRetryShowsOnlyFinalFailureOnFixedDisplay() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.WARN, "[!] Monitor 1 (:0) is ready, but IceWM could not be confirmed active (exit 1)"))
            addAll(reducer.reduce(Log.WARN, "[!] Monitor 1 (:0) is ready, but the configured graphic session is not active"))
            addAll(reducer.reduce(Log.ERROR, "[-] IceWM did not become active on Monitor 1 (:0)"))
        }.map { it.second }

        assertFalse(output.any { it.contains("could not be confirmed active") })
        assertFalse(output.any { it.contains("configured graphic session is not active") })
        assertTrue(output.contains("[SESSION] ✗ IceWM did not become active on Monitor 1 (:0)"))
    }

    @Test
    fun monitorStopKeepsImmediateProgressOwnershipAndCleanupVerification() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.INFO, "--- Stopping X11 monitor ---"))
            addAll(reducer.reduce(Log.INFO, "--- Graphic Session Stop ---"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Container: alpine"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Container lifecycle: remains RUNNING"))
            addAll(reducer.reduce(Log.INFO, "[*] Stopping IceWM graphic session only..."))
            addAll(reducer.reduce(Log.INFO, "[+] Graphic session stopped; container remains running"))
            addAll(reducer.reduce(Log.INFO, "--- Integrated X11 Server Stop ---"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Monitor: 1"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Display: :0"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Live PIDs before stop: 4242"))
            addAll(reducer.reduce(Log.INFO, "[*] Sending SIGKILL to server PIDs: 4242"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Live PIDs after stop: none"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Socket after stop: absent"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Stop duration: 83ms"))
            addAll(reducer.reduce(Log.INFO, "[+] Monitor 1 (:0) inactive"))
            addAll(reducer.reduce(Log.INFO, "[+] X11 runtime cleanup verified"))
            addAll(reducer.reduce(Log.INFO, "[+] Container 'alpine' was left running"))
        }.map { it.second }

        assertTrue(output.first() == "[X11] Stop requested for monitor")
        assertTrue(output.contains("[SESSION] Stopping graphical session"))
        assertTrue(output.contains("[CONTAINER] • Container: alpine"))
        assertTrue(output.contains("[CONTAINER] • Lifecycle: remains RUNNING"))
        assertTrue(output.contains("[SESSION] Stopping IceWM graphic session only"))
        assertTrue(output.contains("[SESSION] ✓ Graphic session stopped; container remains running"))
        assertTrue(output.contains("[X11] Stopping monitor server"))
        assertTrue(output.contains("[X11] • Monitor: 1"))
        assertTrue(output.contains("[X11] • Display: :0"))
        assertTrue(output.contains("[X11] • Server PID(s): 4242"))
        assertTrue(output.contains("[X11] Terminating X11 server process"))
        assertTrue(output.contains("[X11] ✓ Server process stopped"))
        assertTrue(output.contains("[X11] ✓ Socket removed"))
        assertTrue(output.contains("[X11] • Stop time: 83ms"))
        assertTrue(output.contains("[X11] ✓ Monitor 1 (:0) inactive"))
        assertTrue(output.contains("[X11] ✓ Runtime cleanup verified"))
        assertTrue(output.contains("[CONTAINER] ✓ Container 'alpine' was left running"))
    }

    @Test
    fun droidSpacesBannerStateNeverSuppressesFollowingManagerLogs() {
        val reducer = ConciseLogReducer()

        val aggregated = reducer.reduce(
            Log.INFO,
            "Welcome to Droidspaces v6.5.0 !\nContainer: alpine (RUNNING)\nUse 'su -c \"droidspaces show\"' for status"
        )
        assertTrue(aggregated.isEmpty())

        val afterAggregated = reducer.reduce(Log.INFO, "--- Integrated X11 Server Stop ---")
            .map { it.second }
        assertTrue(afterAggregated.contains("[X11] Stopping monitor server"))

        reducer.reduce(Log.INFO, "Welcome to Droidspaces v6.5.0 !")
        reducer.reduce(Log.INFO, "Container: alpine (RUNNING)")
        reducer.reduce(Log.INFO, "Use 'su -c \"droidspaces show\"' for status")
        val afterStreamed = reducer.reduce(Log.INFO, "--- Stopping X11 monitor ---")
            .map { it.second }

        assertTrue(afterStreamed.contains("[X11] Stop requested for monitor"))
    }
}
