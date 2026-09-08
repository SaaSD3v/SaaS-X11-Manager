package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAccessPolicyTest {

    @Test
    fun onlyIntegratedX11AndVncAreSelectable() {
        assertEquals(
            listOf(SessionAccessMode.INTEGRATED_X11, SessionAccessMode.VNC),
            RuntimeAccessPolicy.selectableModes
        )
        assertFalse(RuntimeAccessPolicy.selectableModes.contains(SessionAccessMode.BOTH))
    }

    @Test
    fun legacyBothIsMigratedToIntegratedX11BeforeRuntimeStart() {
        assertEquals(
            SessionAccessMode.INTEGRATED_X11,
            RuntimeAccessPolicy.normalize(SessionAccessMode.BOTH)
        )
        assertTrue(RuntimeAccessPolicy.normalize(SessionAccessMode.VNC).requiresVnc)
    }
}
