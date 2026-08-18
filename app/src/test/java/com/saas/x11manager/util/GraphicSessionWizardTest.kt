package com.saas.x11manager.util

import org.junit.Assert.assertEquals
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
}
