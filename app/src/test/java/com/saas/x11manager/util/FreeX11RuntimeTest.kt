package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FreeX11RuntimeTest {

    @Test
    fun exportCommandIsAlwaysFixedX0ForTheOnlyVariant() {
        assertEquals("export DISPLAY=:0", FreeX11Runtime.exportCommand(":0"))
        assertEquals("unset DISPLAY", FreeX11Runtime.unsetCommand())
        assertEquals("unset DISPLAY; export DISPLAY=:0", FreeX11Runtime.replaceCommand(":0"))
    }
}
