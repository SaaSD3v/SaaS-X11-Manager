package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalWaylandCatalogTest {
    private val debianArm64 = ContainerCapabilities(
        platform = ContainerPlatform.UBUNTU,
        distribution = ContainerDistribution.DEBIAN,
        availableInitSystems = setOf(InitSystem.SYSTEMD),
        distributionName = "Debian GNU/Linux",
        architecture = "aarch64"
    )

    private val alpineArm64 = ContainerCapabilities(
        platform = ContainerPlatform.ALPINE,
        distribution = ContainerDistribution.ALPINE,
        availableInitSystems = setOf(InitSystem.OPENRC),
        distributionName = "Alpine Linux",
        architecture = "aarch64"
    )

    @Test
    fun debianExperimentalWaylandHasAdditionalNestedCompositorsOnly() {
        val stable = GraphicSessionWizard.sessionsFor(
            debianArm64,
            GraphicSessionCatalogMode.STABLE,
            GraphicProtocol.WAYLAND
        )
        val experimental = GraphicSessionWizard.sessionsFor(
            debianArm64,
            GraphicSessionCatalogMode.EXPERIMENTAL,
            GraphicProtocol.WAYLAND
        )

        assertTrue(experimental.contains(GraphicSession.WAYFIRE))
        assertTrue(experimental.contains(GraphicSession.WLMAKER))
        assertTrue(experimental.contains(GraphicSession.PHOC))
        assertTrue(experimental.contains(GraphicSession.MUTTER_WAYLAND))
        assertFalse(experimental.contains(GraphicSession.RIVER))
        assertFalse(experimental.contains(GraphicSession.QTILE_WAYLAND))
        assertTrue(stable.intersect(experimental.toSet()).isEmpty())
    }

    @Test
    fun alpineExperimentalWaylandHasRepositorySpecificOptionsOnly() {
        val stable = GraphicSessionWizard.sessionsFor(
            alpineArm64,
            GraphicSessionCatalogMode.STABLE,
            GraphicProtocol.WAYLAND
        )
        val experimental = GraphicSessionWizard.sessionsFor(
            alpineArm64,
            GraphicSessionCatalogMode.EXPERIMENTAL,
            GraphicProtocol.WAYLAND
        )

        assertTrue(experimental.contains(GraphicSession.WAYFIRE))
        assertTrue(experimental.contains(GraphicSession.RIVER))
        assertTrue(experimental.contains(GraphicSession.QTILE_WAYLAND))
        assertFalse(experimental.contains(GraphicSession.WLMAKER))
        assertFalse(experimental.contains(GraphicSession.PHOC))
        assertFalse(experimental.contains(GraphicSession.MUTTER_WAYLAND))
        assertTrue(stable.intersect(experimental.toSet()).isEmpty())
    }

    @Test
    fun everyNewExperimentalEntryHasAPlanAndLauncher() {
        val cases = listOf(
            ContainerPlatform.UBUNTU to GraphicSession.WLMAKER,
            ContainerPlatform.UBUNTU to GraphicSession.PHOC,
            ContainerPlatform.UBUNTU to GraphicSession.MUTTER_WAYLAND,
            ContainerPlatform.ALPINE to GraphicSession.RIVER,
            ContainerPlatform.ALPINE to GraphicSession.QTILE_WAYLAND
        )

        cases.forEach { (platform, session) ->
            val plan = GraphicSessionPlanResolver.forSelection(platform, session)
            assertNotNull("missing install plan for $session on $platform", plan)
            assertEquals(session.startCommand, plan!!.verificationCommand)
            assertTrue(GraphicSessionWizard.isExperimental(platform, session))
            assertNotNull("missing Wayland launcher for $session", WaylandGraphicSessionSupport.specFor(session))
        }
    }

    @Test
    fun newWlrootsLaunchersForceX11AndSoftwareFallback() {
        listOf(
            GraphicSession.WLMAKER,
            GraphicSession.RIVER,
            GraphicSession.PHOC,
            GraphicSession.QTILE_WAYLAND
        ).forEach { session ->
            val script = WaylandGraphicSessionSupport.specFor(session)!!
                .postInstallCommands
                .joinToString("\n") { it.command }
            assertTrue("$session must force X11 backend", script.contains("WLR_BACKENDS=x11"))
            assertTrue("$session must have Pixman fallback", script.contains("WLR_RENDERER=pixman"))
        }
    }

    @Test
    fun mutterLauncherDetectsNestedCapabilityAtRuntime() {
        val script = WaylandGraphicSessionSupport.specFor(GraphicSession.MUTTER_WAYLAND)!!
            .postInstallCommands
            .joinToString("\n") { it.command }

        assertTrue(script.contains("mutter --help-all"))
        assertTrue(script.contains("--devkit"))
        assertTrue(script.contains("--nested"))
        assertTrue(script.contains("dbus-run-session"))
    }
}
