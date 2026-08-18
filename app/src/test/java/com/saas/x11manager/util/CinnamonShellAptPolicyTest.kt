package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CinnamonShellAptPolicyTest {

    @Test
    fun cinnamonShellKeepsRecommendsButBlocksCinnamonCoreWhenAbsent() {
        assertEquals(
            listOf("cinnamon-core"),
            GraphicSessionAptPolicy.blockedRecommendedPackages(GraphicSession.CINNAMON_SHELL)
        )

        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.CINNAMON_SHELL
            )
        )
        val install = requireNotNull(
            AdditionalGraphicSessionInstaller.stepsFor(plan).firstOrNull {
                it.title == "Installing Cinnamon Shell packages"
            }
        )

        assertTrue(install.command.contains("--install-recommends"))
        assertTrue(install.command.contains("dpkg -s \"\$pkg\""))
        assertTrue(install.command.contains("cinnamon-core"))
        assertTrue(install.command.contains("\$pkg-"))
        assertTrue(!install.command.contains("--no-install-recommends"))
    }
}
