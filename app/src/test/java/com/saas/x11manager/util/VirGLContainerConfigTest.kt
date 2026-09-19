package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirGLContainerConfigTest {

    @Test
    fun analysisReadsNativeStateAndExactManagerBind() {
        val lines = listOf(
            "enable_virgl=1",
            "bind_mounts=/data:/mnt/data,${VirGLContainerConfig.requiredBind}"
        )

        val analysis = VirGLContainerConfig.analyze(lines)

        assertEquals(VirGLContainerConfig.NativeState.ON, analysis.nativeState)
        assertTrue(analysis.hasManagedBind)
        assertFalse(analysis.conflictingGuestBind)
    }

    @Test
    fun applyDisablesNativeVirglAndPreservesUnrelatedBinds() {
        val original = listOf(
            "name=demo",
            "enable_virgl=1",
            "bind_mounts=/host/data:/mnt/data,/x11:/usr/.X11-unix"
        )

        val updated = VirGLContainerConfig.buildAppliedConfig(original)!!
        val binds = updated.single { it.startsWith("bind_mounts=") }

        assertTrue(updated.contains("enable_virgl=0"))
        assertTrue(binds.contains("/host/data:/mnt/data"))
        assertTrue(binds.contains("/x11:/usr/.X11-unix"))
        assertTrue(binds.contains(VirGLContainerConfig.requiredBind))
    }

    @Test
    fun applyIsIdempotent() {
        val original = listOf(
            "enable_virgl=0",
            "bind_mounts=${VirGLContainerConfig.requiredBind}"
        )

        val once = VirGLContainerConfig.buildAppliedConfig(original)!!
        val twice = VirGLContainerConfig.buildAppliedConfig(once)!!

        assertEquals(once, twice)
    }

    @Test
    fun foreignGuestAnchorFailsClosed() {
        val original = listOf(
            "enable_virgl=0",
            "bind_mounts=/someone/else:${VirGLContainerConfig.GUEST_RUNTIME_DIR}"
        )

        assertNull(VirGLContainerConfig.buildAppliedConfig(original))
    }

    @Test
    fun restoreRemovesOnlyManagerBindAndRestoresNativeOn() {
        val applied = listOf(
            "name=demo",
            "enable_virgl=0",
            "bind_mounts=/data:/mnt/data,${VirGLContainerConfig.requiredBind},/x11:/usr/.X11-unix"
        )

        val restored = VirGLContainerConfig.buildRestoredConfig(
            applied,
            VirGLContainerConfig.NativeState.ON
        )
        val binds = restored.single { it.startsWith("bind_mounts=") }

        assertTrue(restored.contains("enable_virgl=1"))
        assertFalse(binds.contains(VirGLContainerConfig.requiredBind))
        assertTrue(binds.contains("/data:/mnt/data"))
        assertTrue(binds.contains("/x11:/usr/.X11-unix"))
    }

    @Test
    fun restoreAbsentRemovesInjectedNativeKey() {
        val applied = listOf(
            "enable_virgl=0",
            "bind_mounts=${VirGLContainerConfig.requiredBind}"
        )

        val restored = VirGLContainerConfig.buildRestoredConfig(
            applied,
            VirGLContainerConfig.NativeState.ABSENT
        )

        assertFalse(restored.any { it.startsWith("enable_virgl=") })
        assertFalse(restored.joinToString("\n").contains(VirGLContainerConfig.requiredBind))
    }
}
