package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedX11RuntimeTest {

    @Test
    fun stoppingOwnerDoesNotMaskAnotherRunningOwnerOfTheSameDisplay() {
        val slot = X11DisplaySlot(2)
        fun owner(name: String, status: ContainerStatus = ContainerStatus.RUNNING) = ContainerInfo(
            name = name,
            rootfsPath = "/containers/$name/rootfs",
            configPath = "/containers/$name/container.config",
            bindMounts = "${slot.socketDir}:/usr/.X11-unix",
            status = status
        )
        val target = owner("stopping")
        val other = owner("remaining")
        val stopped = owner("stopped", ContainerStatus.STOPPED)

        listOf(listOf(target, other, stopped), listOf(stopped, other, target)).forEach { snapshot ->
            assertEquals(
                mapOf(2 to "remaining"),
                X11SessionManager.runningAssignments(snapshot, excludingContainer = "stopping")
            )
        }
        assertTrue(
            X11SessionManager.runningAssignments(
                listOf(target, stopped), excludingContainer = "stopping"
            ).isEmpty()
        )
    }

    @Test
    fun duplicateDisplayOwnersAreExplicitAndNeverCollapsedToOneOwner() {
        val slot = X11DisplaySlot(2)
        fun owner(name: String) = ContainerInfo(
            name = name,
            rootfsPath = "/containers/$name/rootfs",
            configPath = "/containers/$name/container.config",
            bindMounts = "${slot.socketDir}:/usr/.X11-unix",
            status = ContainerStatus.RUNNING
        )

        val containers = listOf(owner("zeta"), owner("alpha"))
        assertEquals(
            mapOf(2 to listOf("alpha", "zeta")),
            X11SessionManager.runningAssignmentGroups(containers)
        )
        assertTrue(X11SessionManager.runningAssignments(containers).isEmpty())
        assertEquals(
            mapOf(2 to "zeta"),
            X11SessionManager.runningAssignments(
                containers,
                excludingContainer = "alpha"
            )
        )
    }

    @Test
    fun batchedRuntimeProbeRequiresMatchingKernelSocketAndFilesystemSocket() {
        val slot = X11DisplaySlot(2)
        val markers = listOf("__SAAS_X11_PIDS__=42 42 invalid -1 73", "__SAAS_X11_SOCKET__=1")
        fun socketRow(path: String, inode: String = "12345") =
            "0000000000000000: 00000002 00000000 00010000 0001 01 $inode $path"

        listOf(slot.socketFile, "@${slot.socketFile}").forEach { path ->
            val runtime = X11SessionManager.parseServerRuntime(slot, markers + socketRow(path))
            assertEquals(listOf(42, 73), runtime.pids)
            assertTrue(runtime.liveSocket)
        }
        listOf(
            markers,
            markers + socketRow(X11DisplaySlot(3).socketFile),
            markers + socketRow(slot.socketFile, inode = "invalid"),
            listOf("__SAAS_X11_SOCKET__=0") + socketRow(slot.socketFile),
            emptyList()
        ).forEach { lines ->
            assertFalse(X11SessionManager.parseServerRuntime(slot, lines).liveSocket)
        }
    }

    @Test
    fun multiMonitorSnapshotParsesAllSlotsAndRequiresTheirOwnKernelSocket() {
        val slot0 = X11DisplaySlot(0)
        val slot2 = X11DisplaySlot(2)
        fun socketRow(path: String, inode: String) =
            "0000000000000000: 00000002 00000000 00010000 0001 01 $inode $path"

        val runtime = X11SessionManager.parseRuntimeSnapshot(
            listOf(
                "__SAAS_X11_SLOT__=0|1|101",
                "__SAAS_X11_SLOT__=2|1|202 203",
                "__SAAS_X11_SLOT__=4|0|404",
                socketRow(slot0.socketFile, "10001"),
                socketRow(X11DisplaySlot(3).socketFile, "10003")
            )
        )

        assertEquals(setOf(0, 2, 4), runtime.probes.keys)
        assertEquals(setOf(0, 2), runtime.socketFiles)
        assertEquals(listOf(101), runtime.probes.getValue(0).pids)
        assertTrue(runtime.probes.getValue(0).liveSocket)
        assertEquals(listOf(202, 203), runtime.probes.getValue(2).pids)
        assertFalse(runtime.probes.getValue(2).liveSocket)
        assertFalse(runtime.probes.getValue(4).liveSocket)
    }

    @Test
    fun integratedServerCommandUsesExplicitIsolatedMonitorRuntimeAndSharedXkbCache() {
        val slot = X11DisplaySlot(3)
        val command = X11SessionManager.buildIntegratedServerCommand(
            "/data/app/~~demo/com.saas.x11manager/base.apk",
            slot
        )

        assertTrue(command.contains("CLASSPATH='/data/app/~~demo/com.saas.x11manager/base.apk'"))
        assertTrue(command.contains("TMPDIR='${slot.runtimeDir}'"))
        assertTrue(command.contains("XKB_CONFIG_ROOT='${Constants.INTEGRATED_X11_XKB_DIR}'"))
        assertTrue(command.contains("--nice-name=${slot.processName}"))
        assertTrue(command.contains("com.termux.x11.CmdEntryPoint ${slot.displayName}"))
        assertTrue(command.contains(">'${slot.logFile}'"))
        assertFalse(command.contains("loader.apk"))
        assertFalse(command.contains("am start -n com.termux.x11"))
    }

    @Test
    fun integratedRuntimeDoesNotExposeStandaloneDisplayLaunch() {
        val methodNames = X11SessionManager::class.java.declaredMethods.map { it.name }
        assertFalse(methodNames.contains("openIntegratedDisplay"))
    }

    @Test
    fun integratedRuntimeExposesMonitorAwareLifecycleAndReconciliation() {
        val methodNames = X11SessionManager::class.java.declaredMethods.map { it.name }

        assertTrue(methodNames.contains("getServerStatus"))
        assertTrue(methodNames.contains("getServerPid"))
        assertTrue(methodNames.contains("getMonitors"))
        assertTrue(methodNames.contains("getDisplayForContainer"))
        assertTrue(methodNames.contains("stopX11Session"))
        assertTrue(methodNames.contains("reconcileRuntimeState"))
        assertFalse(methodNames.contains("getLoaderStatus"))
        assertFalse(methodNames.contains("getLoaderPid"))
        assertFalse(methodNames.contains("startLoader"))
        assertFalse(methodNames.contains("stopLoader"))
        assertTrue(X11ServerStatus.entries.containsAll(listOf(X11ServerStatus.Running, X11ServerStatus.Stopped)))
    }

    @Test
    fun displaySelectionReusesLowestActiveUnownedMonitorBeforeCreatingAnother() {
        assertEquals(0, X11SessionManager.selectDisplaySlot(emptyList()).number)
        assertEquals(2, X11SessionManager.selectDisplaySlot(listOf(0, 1, 4)).number)
        assertEquals(1, X11SessionManager.selectDisplaySlot(listOf(0, 2, 3, 4)).number)

        // :0 is free, but :2 is already an active unowned monitor: adopt :2.
        assertEquals(
            2,
            X11SessionManager.selectDisplaySlot(
                runningAssignedDisplayNumbers = listOf(1, 3),
                reusableActiveDisplayNumbers = listOf(5, 2, 4)
            ).number
        )

        // Active :1 is already owned, so the next reusable active monitor is :3.
        assertEquals(
            3,
            X11SessionManager.selectDisplaySlot(
                runningAssignedDisplayNumbers = listOf(1),
                reusableActiveDisplayNumbers = listOf(1, 3)
            ).number
        )
    }

    @Test
    fun monitorRuntimeAndXkbCacheRemainManagerOwned() {
        val slot = X11DisplaySlot(5)

        assertTrue(slot.socketDir.startsWith(Constants.INTEGRATED_X11_RUNTIME_DIR))
        assertTrue(slot.socketFile.endsWith("/.X11-unix/X5"))
        assertTrue(Constants.INTEGRATED_X11_XKB_DIR.startsWith(Constants.INTEGRATED_X11_RUNTIME_DIR))
        assertFalse(slot.socketDir.contains("/data/data/com.termux"))
        assertFalse(Constants.INTEGRATED_X11_XKB_DIR.contains("/data/data/com.termux"))
    }
}
