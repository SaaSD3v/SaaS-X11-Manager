package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerConfigManagerTest {

    private val defaultSlot = X11DisplaySlot(0)
    private val defaultBind = "${defaultSlot.socketDir}:/usr/.X11-unix"

    @Test
    fun preservesUnrelatedConfigAndExistingBinds() {
        val original = listOf(
            "# keep this comment",
            "name=demo",
            "hostname=demo-host",
            "enable_termux_x11=1",
            "bind_mounts=/host/data:/mnt/data,/host/cache:/cache",
            "net_mode=nat"
        )

        val updated = ContainerConfigManager.buildManualX11Config(original, defaultSlot)

        assertEquals("# keep this comment", updated[0])
        assertEquals("name=demo", updated[1])
        assertEquals("hostname=demo-host", updated[2])
        assertEquals("enable_termux_x11=0", updated[3])
        assertEquals(
            "bind_mounts=/host/data:/mnt/data,/host/cache:/cache,$defaultBind",
            updated[4]
        )
        assertEquals("net_mode=nat", updated[5])
    }

    @Test
    fun replacesOnlyBindTargetingContainerX11Socket() {
        val slot = X11DisplaySlot(2)
        val requiredBind = "${slot.socketDir}:/usr/.X11-unix"
        val original = listOf(
            "enable_termux_x11=0",
            "bind_mounts=/old/socket:/usr/.X11-unix:ro,/host/data:/mnt/data"
        )

        val updated = ContainerConfigManager.buildManualX11Config(original, slot)
        val bindLine = updated.single { it.startsWith("bind_mounts=") }

        assertFalse(bindLine.contains("/old/socket:/usr/.X11-unix"))
        assertTrue(bindLine.contains("/host/data:/mnt/data"))
        assertTrue(bindLine.contains(requiredBind))
    }

    @Test
    fun addsRequiredKeysWhenTheyAreMissing() {
        val original = listOf(
            "name=demo",
            "hostname=demo-host"
        )

        val updated = ContainerConfigManager.buildManualX11Config(original, defaultSlot)

        assertEquals("name=demo", updated[0])
        assertEquals("hostname=demo-host", updated[1])
        assertEquals("enable_termux_x11=0", updated[2])
        assertEquals("bind_mounts=$defaultBind", updated[3])
    }

    @Test
    fun removesDuplicateManualX11BindEntries() {
        val original = listOf(
            "enable_termux_x11=0",
            "bind_mounts=$defaultBind,$defaultBind,/host/data:/mnt/data"
        )

        val updated = ContainerConfigManager.buildManualX11Config(original, defaultSlot)
        val bindLine = updated.single { it.startsWith("bind_mounts=") }
        val x11Occurrences = bindLine.split(',').count { it == defaultBind }

        assertEquals(1, x11Occurrences)
        assertTrue(bindLine.contains("/host/data:/mnt/data"))
    }

    @Test
    fun mutationIsIdempotentForSameSlot() {
        val slot = X11DisplaySlot(4)
        val original = listOf(
            "name=demo",
            "enable_termux_x11=1",
            "bind_mounts=/host/data:/mnt/data"
        )

        val once = ContainerConfigManager.buildManualX11Config(original, slot)
        val twice = ContainerConfigManager.buildManualX11Config(once, slot)

        assertEquals(once, twice)
    }

    @Test
    fun movingContainerToAnotherSlotReplacesOldDisplayBind() {
        val monitorFour = X11DisplaySlot(3)
        val monitorTwo = X11DisplaySlot(1)
        val original = ContainerConfigManager.buildManualX11Config(
            listOf("name=demo", "bind_mounts=/host/data:/mnt/data"),
            monitorFour
        )

        val moved = ContainerConfigManager.buildManualX11Config(original, monitorTwo)
        val bindLine = moved.single { it.startsWith("bind_mounts=") }

        assertFalse(bindLine.contains(monitorFour.socketDir))
        assertTrue(bindLine.contains(monitorTwo.socketDir))
        assertTrue(bindLine.contains("/host/data:/mnt/data"))
    }

    @Test
    fun discoversDisplaySlotFromManagerOwnedBind() {
        val slot = ContainerConfigManager.displaySlotFromBindMounts(
            "/host/data:/mnt/data,${X11DisplaySlot(6).socketDir}:/usr/.X11-unix:ro"
        )

        assertEquals(6, slot?.number)
        assertEquals(7, slot?.monitorNumber)
    }

    @Test
    fun ignoresNonManagerX11BindWhenDiscoveringSlot() {
        val slot = ContainerConfigManager.displaySlotFromBindMounts(
            "/old/socket:/usr/.X11-unix,/host/data:/mnt/data"
        )

        assertEquals(null, slot)
    }
}
