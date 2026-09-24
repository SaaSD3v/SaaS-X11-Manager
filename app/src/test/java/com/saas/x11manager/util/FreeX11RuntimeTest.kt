package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FreeX11RuntimeTest {

    @Test
    fun exportCommandUsesTheAssignedDisplayVerbatim() {
        assertEquals("export DISPLAY=:0", FreeX11Runtime.exportCommand(":0"))
        assertEquals("export DISPLAY=:3", FreeX11Runtime.exportCommand(":3"))
        assertEquals("export DISPLAY=:12", FreeX11Runtime.exportCommand(":12"))
        assertEquals("unset DISPLAY", FreeX11Runtime.unsetCommand())
        assertEquals("unset DISPLAY; export DISPLAY=:3", FreeX11Runtime.replaceCommand(":3"))
    }
}
