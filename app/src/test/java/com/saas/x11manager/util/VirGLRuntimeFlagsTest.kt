package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirGLRuntimeFlagsTest {
    @Test
    fun helpDetectionExposesOnlyManagerSafeOptionalFlags() {
        val help = """
            --socket-path <path>
            --multi-clients
            --use-glx
            --use-egl-surfaceless
            --use-gles
            --no-fork
            --no-loop-or-fork
            --no-virgl
        """.trimIndent()
        val detected = VirGLRuntimeFlags.fromHelp(help)

        assertEquals(VirGLRuntimeFlags.all.toSet(), detected)
        assertFalse(VirGLRuntimeFlags.arguments(detected).contains("--socket-path"))
        assertFalse(VirGLRuntimeFlags.arguments(detected).contains("--multi-clients"))
        assertFalse(VirGLRuntimeFlags.arguments(detected).contains("--no-virgl"))
    }

    @Test
    fun glxAndEglModesCannotBeSelectedTogether() {
        var selected = emptySet<VirGLRuntimeFlag>()
        selected = VirGLRuntimeFlags.withToggled(selected, VirGLRuntimeFlag.USE_EGL_SURFACELESS, true)
        selected = VirGLRuntimeFlags.withToggled(selected, VirGLRuntimeFlag.USE_GLES, true)
        selected = VirGLRuntimeFlags.withToggled(selected, VirGLRuntimeFlag.USE_GLX, true)

        assertTrue(VirGLRuntimeFlag.USE_GLX in selected)
        assertFalse(VirGLRuntimeFlag.USE_EGL_SURFACELESS in selected)
        assertFalse(VirGLRuntimeFlag.USE_GLES in selected)
    }

    @Test
    fun processModeTogglesStayUnambiguous() {
        var selected = setOf(VirGLRuntimeFlag.NO_FORK)
        selected = VirGLRuntimeFlags.withToggled(selected, VirGLRuntimeFlag.NO_LOOP_OR_FORK, true)

        assertTrue(VirGLRuntimeFlag.NO_LOOP_OR_FORK in selected)
        assertFalse(VirGLRuntimeFlag.NO_FORK in selected)
    }
}
