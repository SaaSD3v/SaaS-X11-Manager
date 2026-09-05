package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerConfigManagerTest {

    private val fixedBind = "${Constants.X11_SOCK_DIR}:/usr/.X11-unix"

    @Test
    fun preservesUnrelatedConfigAndAddsFixedX0Bind() {
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
        assertEquals("enable_termux_x11=0", updated[3])
        assertEquals(
            "bind_mounts=/host/data:/mnt/data,/host/cache:/cache,$fixedBind",
            updated[4]
        )
        assertEquals("net_mode=nat", updated[5])
    }

    @Test
    fun replacesEveryExistingX11SocketBindWithFixedManagerBind() {
        val original = listOf(
            "enable_termux_x11=0",
            "bind_mounts=/old/socket:/usr/.X11-unix:ro,/host/data:/mnt/data"
        )

        val updated = ContainerConfigManager.buildManualX11Config(original)
        val bindLine = updated.single { it.startsWith("bind_mounts=") }

        assertFalse(bindLine.contains("/old/socket:/usr/.X11-unix"))
        assertTrue(bindLine.contains("/host/data:/mnt/data"))
        assertTrue(bindLine.contains(fixedBind))
    }

    @Test
    fun addsRequiredKeysWhenMissing() {
        val updated = ContainerConfigManager.buildManualX11Config(
            listOf("name=demo", "hostname=demo-host")
        )

        assertEquals("enable_termux_x11=0", updated[2])
        assertEquals("bind_mounts=$fixedBind", updated[3])
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

    @Test
    fun fixedManagerBindIsRecognizedWithoutDisplayParsing() {
        assertTrue(
            ContainerConfigManager.usesManagedX11(
                "/host/data:/mnt/data,$fixedBind:rw"
            )
        )
        assertFalse(
            ContainerConfigManager.usesManagedX11(
                "/old/socket:/usr/.X11-unix,/host/data:/mnt/data"
            )
        )
    }

    @Test
    fun anyLiveX11SocketBindCanBeDetectedFailClosed() {
        assertTrue(ContainerConfigManager.hasAnyX11SocketBind("/whatever:/usr/.X11-unix"))
        assertFalse(ContainerConfigManager.hasAnyX11SocketBind("/host/data:/mnt/data"))
    }
}
