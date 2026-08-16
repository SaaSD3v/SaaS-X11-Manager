package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerConfigManagerTest {

    private val x11Bind = "${Constants.X11_SOCK_DIR}:/usr/.X11-unix"

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

        val updated = ContainerConfigManager.buildManualX11Config(original)

        assertEquals("# keep this comment", updated[0])
        assertEquals("name=demo", updated[1])
        assertEquals("hostname=demo-host", updated[2])
        assertEquals("enable_termux_x11=0", updated[3])
        assertEquals(
            "bind_mounts=/host/data:/mnt/data,/host/cache:/cache,$x11Bind",
            updated[4]
        )
        assertEquals("net_mode=nat", updated[5])
    }

    @Test
    fun replacesOnlyBindTargetingContainerX11Socket() {
        val original = listOf(
            "enable_termux_x11=0",
            "bind_mounts=/old/socket:/usr/.X11-unix:ro,/host/data:/mnt/data"
        )

        val updated = ContainerConfigManager.buildManualX11Config(original)
        val bindLine = updated.single { it.startsWith("bind_mounts=") }

        assertFalse(bindLine.contains("/old/socket:/usr/.X11-unix"))
        assertTrue(bindLine.contains("/host/data:/mnt/data"))
        assertTrue(bindLine.contains(x11Bind))
    }

    @Test
    fun addsRequiredKeysWhenTheyAreMissing() {
        val original = listOf(
            "name=demo",
            "hostname=demo-host"
        )

        val updated = ContainerConfigManager.buildManualX11Config(original)

        assertEquals("name=demo", updated[0])
        assertEquals("hostname=demo-host", updated[1])
        assertEquals("enable_termux_x11=0", updated[2])
        assertEquals("bind_mounts=$x11Bind", updated[3])
    }

    @Test
    fun removesDuplicateManualX11BindEntries() {
        val original = listOf(
            "enable_termux_x11=0",
            "bind_mounts=$x11Bind,$x11Bind,/host/data:/mnt/data"
        )

        val updated = ContainerConfigManager.buildManualX11Config(original)
        val bindLine = updated.single { it.startsWith("bind_mounts=") }
        val x11Occurrences = bindLine.split(',').count { it == x11Bind }

        assertEquals(1, x11Occurrences)
        assertTrue(bindLine.contains("/host/data:/mnt/data"))
    }

    @Test
    fun mutationIsIdempotent() {
        val original = listOf(
            "name=demo",
            "enable_termux_x11=1",
            "bind_mounts=/host/data:/mnt/data"
        )

        val once = ContainerConfigManager.buildManualX11Config(original)
        val twice = ContainerConfigManager.buildManualX11Config(once)

        assertEquals(once, twice)
    }
}
