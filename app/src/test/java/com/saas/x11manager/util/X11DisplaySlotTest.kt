package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class X11DisplaySlotTest {

    @Test
    fun slotExposesHumanMonitorNumberAndIsolatedRuntimePaths() {
        val slot = X11DisplaySlot(2)

        assertEquals(3, slot.monitorNumber)
        assertEquals(":2", slot.displayName)
        assertEquals("saas-x11-2", slot.processName)
        assertEquals("${Constants.INTEGRATED_X11_RUNTIME_DIR}/display-2", slot.runtimeDir)
        assertEquals("${slot.runtimeDir}/.X11-unix", slot.socketDir)
        assertEquals("${slot.socketDir}/X2", slot.socketFile)
        assertEquals("${slot.runtimeDir}/.X2-lock", slot.lockFile)
        assertEquals("${slot.runtimeDir}/server.log", slot.logFile)
        assertEquals("Monitor 3 (:2)", slot.describe())
    }

    @Test
    fun allocatorAlwaysUsesLowestAvailableDisplayNumber() {
        assertEquals(0, X11DisplayAllocator.firstFree(emptyList()).number)
        assertEquals(1, X11DisplayAllocator.firstFree(listOf(0)).number)
        assertEquals(2, X11DisplayAllocator.firstFree(listOf(0, 1, 3, 4)).number)
        assertEquals(0, X11DisplayAllocator.firstFree(listOf(4, 7)).number)
    }

    @Test
    fun allocatorIgnoresInvalidNegativeEntriesAndDuplicates() {
        val slot = X11DisplayAllocator.firstFree(listOf(-1, -10, 0, 0, 2, 2))
        assertEquals(1, slot.number)
        assertTrue(slot.monitorNumber > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun slotRejectsNegativeDisplayNumber() {
        X11DisplaySlot(-1)
    }
}
