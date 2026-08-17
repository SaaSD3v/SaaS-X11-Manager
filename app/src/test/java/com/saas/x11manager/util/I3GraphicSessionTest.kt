package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class I3GraphicSessionTest {
    @Test
    fun plansUsePlatformSpecificOfficialPackages() {
        val alpine = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, GraphicSession.I3)
        )
        val deb = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.I3)
        )

        assertEquals(listOf("i3wm", "xterm"), alpine.packages)
        assertEquals(listOf("i3-wm", "xterm"), deb.packages)
        assertEquals("i3", alpine.verificationCommand)
        assertEquals("i3", deb.verificationCommand)
    }

    @Test
    fun installPreservesUserConfigAndVerifyUsesCheckMode() {
        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.I3))
        val post = spec.postInstallCommands.joinToString("\n") { it.command }
        val verify = spec.verificationCommands.joinToString("\n") { it.command }

        assertTrue(post.contains("[ -f /root/.config/i3/config ] ||"))
        assertTrue(post.contains("[ -f /root/.i3/config ] ||"))
        assertTrue(post.contains("cp /etc/i3/config /root/.config/i3/config"))
        assertTrue(verify.contains("i3 -C -c"))
        assertFalse(verify.contains("exec i3"))
    }

    @Test
    fun startupUsesExistingGenericLauncher() {
        InitSystem.entries.forEach { initSystem ->
            val joined = GraphicSessionInstaller.startupStepsFor(
                ContainerPlatform.ALPINE,
                initSystem,
                GraphicSession.I3
            ).joinToString("\n") { it.command }
            assertTrue(joined.contains("exec i3"))
        }
    }
}
