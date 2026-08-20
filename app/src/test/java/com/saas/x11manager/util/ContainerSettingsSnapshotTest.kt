package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerSettingsSnapshotTest {

    @Test
    fun parsesProfileAndInstalledSessionsFromOneSnapshot() {
        val snapshot = ContainerSettingsManager.parseSnapshot(
            listOf(
                "# SaaS-X11-Manager container settings",
                "platform=ubuntu",
                "init_system=openrc",
                "graphic_session=qtile",
                "installed_openbox=1",
                "installed_qtile=TRUE",
                "installed_jwm=no",
                "future_key=preserved-by-writer"
            )
        )

        assertEquals(ContainerPlatform.UBUNTU, snapshot.platform)
        assertEquals(InitSystem.OPENRC, snapshot.initSystem)
        assertEquals(GraphicSession.QTILE, snapshot.graphicSession)
        assertTrue(snapshot.isGraphicSessionInstalled(GraphicSession.OPENBOX))
        assertTrue(snapshot.isGraphicSessionInstalled(GraphicSession.QTILE))
        assertFalse(snapshot.isGraphicSessionInstalled(GraphicSession.JWM))
        assertFalse(snapshot.isGraphicSessionInstalled(GraphicSession.NONE))
    }

    @Test
    fun snapshotKeepsPreviousFirstValueSemanticsForDuplicateKeys() {
        val snapshot = ContainerSettingsManager.parseSnapshot(
            listOf(
                "init_system=systemd",
                "init_system=openrc",
                "platform=alpine",
                "platform=ubuntu",
                "graphic_session=openbox",
                "graphic_session=jwm"
            )
        )

        assertEquals(InitSystem.SYSTEMD, snapshot.initSystem)
        assertEquals(ContainerPlatform.ALPINE, snapshot.platform)
        assertEquals(GraphicSession.OPENBOX, snapshot.graphicSession)
    }

    @Test
    fun installedMarkerCompatibilityRemainsCaseInsensitive() {
        val snapshot = ContainerSettingsManager.parseSnapshot(
            listOf(
                "installed_openbox=YES",
                "installed_jwm=true",
                "installed_icewm=1"
            )
        )

        assertTrue(snapshot.isGraphicSessionInstalled(GraphicSession.OPENBOX))
        assertTrue(snapshot.isGraphicSessionInstalled(GraphicSession.JWM))
        assertTrue(snapshot.isGraphicSessionInstalled(GraphicSession.ICEWM))
    }

    @Test
    fun malformedOrUnknownValuesRemainUnconfigured() {
        val snapshot = ContainerSettingsManager.parseSnapshot(
            listOf(
                "init_system=unknown-init",
                "platform=unknown-platform",
                "graphic_session=not-a-session",
                "installed_openbox=0",
                "installed_jwm=garbage",
                "broken-line"
            )
        )

        assertNull(snapshot.initSystem)
        assertNull(snapshot.platform)
        assertNull(snapshot.graphicSession)
        assertTrue(snapshot.installedSessions.isEmpty())
    }
}
