package com.saas.x11manager.util

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticLogLayoutPolicyTest {

    @Test
    fun majorLifecycleSectionsReceiveOneBlankRowWithoutLeadingNoise() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.INFO, "--- Graphic Access Start ---"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Access method: VNC"))
            addAll(reducer.reduce(Log.INFO, "--- Audio Configuration ---"))
            addAll(reducer.reduce(Log.INFO, "[+] Audio ready (AAudio_sink, tcp:127.0.0.1:4713)"))
            addAll(reducer.reduce(Log.INFO, "--- Starting External TigerVNC Session ---"))
        }.map { it.second }

        assertEquals("[SESSION] Starting graphical access", output.first())
        assertTrue(output.windowed(3).any {
            it == listOf("[SESSION] • Access: VNC", "", "[AUDIO] Preparing Android audio")
        })
        assertTrue(output.windowed(3).any {
            it == listOf(
                "[AUDIO] ✓ Audio ready (AAudio_sink, tcp:127.0.0.1:4713)",
                "",
                "[VNC] Starting standalone VNC"
            )
        })
        assertFalse(output.zipWithNext().any { (a, b) -> a.isEmpty() && b.isEmpty() })
    }

    @Test
    fun explicitSemanticSpacerCreatesExactlyOneVisualRow() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.INFO, "[VNC] Connection"))
            addAll(reducer.reduce(Log.INFO, LogLayout.SPACER))
            addAll(reducer.reduce(Log.INFO, LogLayout.SPACER))
            addAll(reducer.reduce(Log.INFO, "[VNC] USB / ADB"))
        }.map { it.second }

        assertEquals(
            listOf("[VNC] Connection", "", "[VNC] USB / ADB"),
            output
        )
        assertFalse(output.any { it.contains("SAAS_LOG_SPACER") })
    }

    @Test
    fun rawBlankLinesDoNotCreateArbitraryLayoutButErrorsGetTheirOwnBlock() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.INFO, "[CONTAINER] Starting container"))
            addAll(reducer.reduce(Log.INFO, ""))
            addAll(reducer.reduce(Log.INFO, "   "))
            addAll(reducer.reduce(Log.ERROR, "[-] Container failed to start"))
            addAll(reducer.reduce(Log.ERROR, "[-] Container command channel unavailable"))
        }.map { it.second }

        assertEquals("[CONTAINER] Starting container", output.first())
        assertTrue(output.contains(""))
        assertFalse(output.zipWithNext().any { (a, b) -> a.isEmpty() && b.isEmpty() })
        assertTrue(output.contains("[CONTAINER] ✗ Container failed to start"))
        assertTrue(output.contains("[CONTAINER] ✗ Container command channel unavailable"))
    }

    @Test
    fun installationAlsoUsesSectionSpacingWithoutPackageManagerNoise() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.INFO, "--- Installing Graphic Session: IceWM ---"))
            addAll(reducer.reduce(Log.INFO, "[+] Checking container runtime"))
            addAll(reducer.reduce(Log.INFO, "Get:1 https://example.invalid stable InRelease"))
            addAll(reducer.reduce(Log.INFO, "--- Verifying Graphic Session: IceWM ---"))
        }.map { it.second }

        assertEquals("[INSTALL] Installing IceWM", output.first())
        assertTrue(output.contains("[CONTAINER] Checking container runtime"))
        assertTrue(output.contains(""))
        assertTrue(output.contains("[INSTALL] Verifying IceWM"))
        assertFalse(output.any { it.contains("example.invalid") })
    }
}
