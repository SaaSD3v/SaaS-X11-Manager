package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaylandGraphicSessionTest {

    private val alpineArm64 = ContainerCapabilities(
        platform = ContainerPlatform.ALPINE,
        distribution = ContainerDistribution.ALPINE,
        availableInitSystems = setOf(InitSystem.OPENRC),
        distributionName = "Alpine Linux",
        architecture = "aarch64"
    )

    private val debianArm64 = ContainerCapabilities(
        platform = ContainerPlatform.UBUNTU,
        distribution = ContainerDistribution.DEBIAN,
        availableInitSystems = setOf(InitSystem.SYSTEMD),
        distributionName = "Debian GNU/Linux",
        architecture = "aarch64"
    )

    @Test
    fun alpineStableAndExperimentalWaylandCatalogsAreSeparated() {
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

        assertTrue(stable.contains(GraphicSession.WESTON))
        assertTrue(stable.contains(GraphicSession.LABWC))
        assertTrue(stable.contains(GraphicSession.SWAY))
        assertTrue(stable.contains(GraphicSession.CAGE))
        assertFalse(stable.contains(GraphicSession.WAYFIRE))
        assertTrue(experimental.containsAll(stable))
        assertTrue(experimental.contains(GraphicSession.WAYFIRE))
    }

    @Test
    fun debianArm64StableCatalogIncludesNormalRepositoryWaylandSessions() {
        val stable = GraphicSessionWizard.sessionsFor(
            debianArm64,
            GraphicSessionCatalogMode.STABLE,
            GraphicProtocol.WAYLAND
        )

        assertTrue(stable.contains(GraphicSession.WESTON))
        assertTrue(stable.contains(GraphicSession.LABWC))
        assertTrue(stable.contains(GraphicSession.SWAY))
        assertTrue(stable.contains(GraphicSession.CAGE))
        assertTrue(stable.contains(GraphicSession.WAYFIRE))
    }

    @Test
    fun unvalidatedArchitectureDoesNotExposeWaylandCatalog() {
        val amd64 = debianArm64.copy(architecture = "x86_64")
        val sessions = GraphicSessionWizard.sessionsFor(
            amd64,
            GraphicSessionCatalogMode.EXPERIMENTAL,
            GraphicProtocol.WAYLAND
        )

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun waylandSessionLauncherKeepsDynamicX11TransportAndWaylandIdentity() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.WESTON, "/bin/sh")

        assertTrue(script.contains("export DISPLAY=:\$X11_DISPLAY_NUMBER"))
        assertTrue(script.contains("export XDG_SESSION_TYPE=wayland"))
        assertTrue(script.contains("export SAAS_WAYLAND_SOCKET=wayland-0"))
        assertTrue(script.contains("unset WAYLAND_DISPLAY"))
        assertTrue(script.contains("exec saas-weston-wayland-session"))
    }

    @Test
    fun compositorLaunchersForceNestedBackends() {
        val weston = WaylandGraphicSessionSupport.specFor(GraphicSession.WESTON)!!
            .postInstallCommands.joinToString("\n") { it.command }
        val sway = WaylandGraphicSessionSupport.specFor(GraphicSession.SWAY)!!
            .postInstallCommands.joinToString("\n") { it.command }

        assertTrue(weston.contains("--backend=x11"))
        assertTrue(weston.contains("--renderer=pixman"))
        assertTrue(sway.contains("WLR_BACKENDS=x11"))
        assertTrue(sway.contains("WLR_X11_OUTPUTS=1"))
        assertTrue(sway.contains("WLR_RENDERER=pixman"))
    }

    @Test
    fun runtimeControllerRequiresWaylandSocketForWaylandSessions() {
        val command = GraphicSessionRuntimeController.buildStartCommand(
            X11DisplaySlot(2),
            requireWaylandSocket = true
        )

        assertTrue(command.contains("require_wayland=1"))
        assertTrue(command.contains("/tmp/runtime-root/wayland-*"))
        assertTrue(command.contains("wayland-visible"))
        assertTrue(command.contains("wayland-not-visible"))
    }
}
