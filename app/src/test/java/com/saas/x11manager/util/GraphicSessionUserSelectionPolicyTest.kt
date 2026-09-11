package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionUserSelectionPolicyTest {
    @Test
    fun `next start selection is consumed exactly once`() {
        val container = "selection-once-test"
        val selection = GraphicSessionUserSelection("desktop")

        GraphicSessionUserManager.selectForNextStart(container, selection)
        assertEquals(selection, GraphicSessionUserManager.selectedForNextStart(container))
        assertTrue(GraphicSessionUserManager.consumePreparedSelection(container, selection))
        assertNull(GraphicSessionUserManager.selectedForNextStart(container))
    }

    @Test
    fun `newer concurrent selection survives stale consume`() {
        val container = "selection-race-test"
        val first = GraphicSessionUserSelection("first")
        val newer = GraphicSessionUserSelection("newer")

        GraphicSessionUserManager.selectForNextStart(container, first)
        GraphicSessionUserManager.selectForNextStart(container, newer)

        assertFalse(GraphicSessionUserManager.consumePreparedSelection(container, first))
        assertEquals(newer, GraphicSessionUserManager.selectedForNextStart(container))
        assertTrue(GraphicSessionUserManager.consumePreparedSelection(container, newer))
    }
}
