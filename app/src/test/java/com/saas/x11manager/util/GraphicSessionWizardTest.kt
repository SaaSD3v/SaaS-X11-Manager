package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionWizardTest {
    @Test
    fun openRcLegacyMappingUsesAlpineCatalog() {
        assertEquals(ContainerPlatform.ALPINE, GraphicSessionWizard.platformFor(InitSystem.OPENRC))
        val sessions = GraphicSessionWizard.sessionsFor(InitSystem.OPENRC)
        assertTrue(sessions.isNotEmpty())
        sessions.forEach { session ->
            assertTrue(
                GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session) != null
            )
        }
    }

    @Test
    fun systemdLegacyMappingUsesDebCatalog() {
        assertEquals(ContainerPlatform.UBUNTU, GraphicSessionWizard.platformFor(InitSystem.SYSTEMD))
        val sessions = GraphicSessionWizard.sessionsFor(InitSystem.SYSTEMD)
        assertTrue(sessions.isNotEmpty())
        sessions.forEach { session ->
            assertTrue(
                GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, session) != null
            )
        }
    }

    @Test
    fun detectedAlpineCatalogIsIndependentFromInitChoice() {
        val sessions = GraphicSessionWizard.sessionsFor(ContainerPlatform.ALPINE)
        assertTrue(sessions.isNotEmpty())
        sessions.forEach { session ->
            assertTrue(GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session) != null)
        }
    }

    @Test
    fun detectedDebCatalogIsIndependentFromInitChoice() {
        val sessions = GraphicSessionWizard.sessionsFor(ContainerPlatform.UBUNTU)
        assertTrue(sessions.isNotEmpty())
        sessions.forEach { session ->
            assertTrue(GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, session) != null)
        }
    }

    @Test
    fun experimentalAlpineCatalogIsAdditive() {
        val stable = GraphicSessionWizard.sessionsFor(
            ContainerPlatform.ALPINE,
            GraphicSessionCatalogMode.STABLE
        )
        val experimental = GraphicSessionWizard.sessionsFor(
            ContainerPlatform.ALPINE,
            GraphicSessionCatalogMode.EXPERIMENTAL
        )

        assertTrue(stable.isNotEmpty())
        assertTrue(experimental.containsAll(stable))
    }

    @Test
    fun experimentalDebCatalogIsAdditive() {
        val stable = GraphicSessionWizard.sessionsFor(
            ContainerPlatform.UBUNTU,
            GraphicSessionCatalogMode.STABLE
        )
        val experimental = GraphicSessionWizard.sessionsFor(
            ContainerPlatform.UBUNTU,
            GraphicSessionCatalogMode.EXPERIMENTAL
        )

        assertTrue(stable.isNotEmpty())
        assertTrue(experimental.containsAll(stable))
    }

    @Test
    fun stableCatalogNeverContainsExperimentalSessions() {
        ContainerPlatform.entries.forEach { platform ->
            GraphicSessionWizard.sessionsFor(
                platform,
                GraphicSessionCatalogMode.STABLE
            ).forEach { session ->
                assertFalse(GraphicSessionWizard.isExperimental(platform, session))
            }
        }
    }

    @Test
    fun qtileIsExperimentalOnlyOnAlpine() {
        val alpineStable = GraphicSessionWizard.sessionsFor(
            ContainerPlatform.ALPINE,
            GraphicSessionCatalogMode.STABLE
        )
        val alpineExperimental = GraphicSessionWizard.sessionsFor(
            ContainerPlatform.ALPINE,
            GraphicSessionCatalogMode.EXPERIMENTAL
        )
        val debStable = GraphicSessionWizard.sessionsFor(
            ContainerPlatform.UBUNTU,
            GraphicSessionCatalogMode.STABLE
        )

        assertFalse(alpineStable.contains(GraphicSession.QTILE))
        assertTrue(alpineExperimental.contains(GraphicSession.QTILE))
        assertTrue(GraphicSessionWizard.isExperimental(ContainerPlatform.ALPINE, GraphicSession.QTILE))
        assertTrue(debStable.contains(GraphicSession.QTILE))
        assertFalse(GraphicSessionWizard.isExperimental(ContainerPlatform.UBUNTU, GraphicSession.QTILE))
    }
}
