package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XkbRepositoryTest {

    @Test
    fun runningContainerUsesProcRootInsteadOfSecondMount() {
        assertEquals(
            XkbRootStrategy.LIVE_PROC_ROOT,
            XkbRepository.selectRootStrategy(
                status = ContainerStatus.RUNNING,
                pid = 4242,
                rootfsIsDirectory = false
            )
        )
    }

    @Test
    fun stoppedImageUsesReadOnlyTemporaryMount() {
        assertEquals(
            XkbRootStrategy.READ_ONLY_MOUNT,
            XkbRepository.selectRootStrategy(
                status = ContainerStatus.STOPPED,
                pid = null,
                rootfsIsDirectory = false
            )
        )
        assertTrue(
            RootfsAccessor.buildMountCommand(
                rootfsArg = "'/rootfs.img'",
                mountPointArg = "'/mnt/xkb'",
                isFile = true,
                readOnly = true
            ).contains("mount -o loop,ro")
        )
    }

    @Test
    fun unknownImageStateRefusesPotentialConcurrentMount() {
        assertEquals(
            XkbRootStrategy.REFUSE_UNCERTAIN_MOUNT,
            XkbRepository.selectRootStrategy(
                status = ContainerStatus.UNKNOWN,
                pid = null,
                rootfsIsDirectory = false
            )
        )
    }

    @Test
    fun directoryRootfsNeedsNoMountEvenWhenRuntimeStateIsUnknown() {
        assertEquals(
            XkbRootStrategy.DIRECT_DIRECTORY,
            XkbRepository.selectRootStrategy(
                status = ContainerStatus.UNKNOWN,
                pid = null,
                rootfsIsDirectory = true
            )
        )
    }

    @Test
    fun writableRootfsMountBehaviorRemainsAvailableForEditors() {
        assertTrue(
            RootfsAccessor.buildMountCommand(
                rootfsArg = "'/rootfs.img'",
                mountPointArg = "'/mnt/edit'",
                isFile = true,
                readOnly = false
            ).contains("mount -o loop,rw")
        )
    }
}
