package com.saas.x11manager.util

import org.junit.Assert.*
import org.junit.Test

class FixedX11OwnershipTest {
    private fun container(name: String, source: String = Constants.X11_SOCK_DIR,
                          status: ContainerStatus = ContainerStatus.RUNNING) =
        ContainerInfo(name, "/rootfs/$name", "/config/$name",
            bindMounts = "$source:/usr/.X11-unix", status = status)

    @Test fun foreignSocketDoesNotOwnOrBlockTheFixedServer() {
        val foreign = container("foreign", "/external/x11")
        assertFalse(FixedX11Ownership.ownsServer(foreign))
        assertNull(FixedX11Ownership.otherOwner(listOf(foreign), "target"))
        assertFalse(FixedX11Ownership.canReleaseAfterStop(foreign, emptyList()))
    }

    @Test fun anotherRunningManagedContainerKeepsItsReservation() {
        val first = container("first")
        val second = container("second")
        assertEquals("second", FixedX11Ownership.otherOwner(listOf(first, second), "first"))
        assertFalse(FixedX11Ownership.canReleaseAfterStop(first, listOf(second)))
    }

    @Test fun lastStoppedOwnerReleasesTheServerWithoutTouchingForeignClients() {
        val first = container("first")
        val remaining = listOf(first.copy(status = ContainerStatus.STOPPED), container("foreign", "/external/x11"))
        assertTrue(FixedX11Ownership.canReleaseAfterStop(first, remaining))
        assertNull(FixedX11Ownership.otherOwner(remaining, "next"))
    }

    @Test fun unknownOrPreviouslyStoppedContainerCannotReleaseTheServer() {
        assertFalse(FixedX11Ownership.canReleaseAfterStop(null, emptyList()))
        assertFalse(FixedX11Ownership.canReleaseAfterStop(container("stopped", status = ContainerStatus.STOPPED), emptyList()))
    }
}
