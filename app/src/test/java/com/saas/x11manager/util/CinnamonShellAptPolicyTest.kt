package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinnamonShellAptPolicyTest {

    @Test
    fun cinnamonShellInstallsAptRecommendationsWithoutSuppression() {
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

        assertTrue(plan.installRecommendedPackages)
        assertTrue(install.command.contains("--install-recommends"))
        assertTrue(install.command.contains("cinnamon dbus-x11 xterm"))
        assertFalse(install.command.contains("blocked_recommends"))
        assertFalse(install.command.contains("dpkg -s \"\$pkg\""))
        assertFalse(install.command.contains("\$pkg-"))
        assertFalse(install.command.contains("--no-install-recommends"))
    }
}
