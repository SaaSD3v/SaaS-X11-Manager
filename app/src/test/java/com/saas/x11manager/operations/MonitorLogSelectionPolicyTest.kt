package com.saas.x11manager.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorLogSelectionPolicyTest {
    private fun candidate(
        area: OperationArea,
        target: String,
        updatedAt: Long,
        available: Boolean = true
    ) = MonitorLogCandidate(OperationOwner(area, target), available, updatedAt)

    @Test
    fun `empty or unavailable candidates do not create fake monitor history`() {
        assertNull(MonitorLogSelectionPolicy.select("0", "debian", emptyList()))
        assertNull(
            MonitorLogSelectionPolicy.select(
                "0",
                "debian",
                listOf(candidate(OperationArea.MONITOR, "0", 10, available = false))
            )
        )
    }

    @Test
    fun `selected display never borrows another monitor log`() {
        val selected = MonitorLogSelectionPolicy.select(
            "1",
            null,
            listOf(
                candidate(OperationArea.MONITOR, "0", 100),
                candidate(OperationArea.MONITOR, "2", 200)
            )
        )
        assertNull(selected)
    }

    @Test
    fun `home start log is eligible only for the container owning the display`() {
        val selected = MonitorLogSelectionPolicy.select(
            "0",
            "debian",
            listOf(
                candidate(OperationArea.HOME, "alpine", 500),
                candidate(OperationArea.HOME, "debian", 100)
            )
        )
        assertEquals(OperationOwner(OperationArea.HOME, "debian"), selected)
    }

    @Test
    fun `newest relevant lifecycle log wins`() {
        val home = candidate(OperationArea.HOME, "debian", 200)
        val monitor = candidate(OperationArea.MONITOR, "0", 300)
        assertEquals(
            OperationOwner(OperationArea.MONITOR, "0"),
            MonitorLogSelectionPolicy.select("0", "debian", listOf(home, monitor))
        )

        val newerHome = candidate(OperationArea.HOME, "debian", 400)
        assertEquals(
            OperationOwner(OperationArea.HOME, "debian"),
            MonitorLogSelectionPolicy.select("0", "debian", listOf(monitor, newerHome))
        )
    }

    @Test
    fun `monitor owner wins deterministic timestamp ties`() {
        val selected = MonitorLogSelectionPolicy.select(
            "0",
            "debian",
            listOf(
                candidate(OperationArea.HOME, "debian", 100),
                candidate(OperationArea.MONITOR, "0", 100)
            )
        )
        assertEquals(OperationOwner(OperationArea.MONITOR, "0"), selected)
    }
}
