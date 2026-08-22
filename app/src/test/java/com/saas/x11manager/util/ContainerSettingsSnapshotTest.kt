package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerSettingsSnapshotTest {

    @Test
    fun parsesProfileAndInstalledMarkersFromOneSnapshot() {
        val snapshot = ContainerSettingsManager.parseSnapshot(
            listOf(
                "# Manager metadata",
                "platform=alpine",
                "init_system=openrc",
                "graphic_session=openbox",
                "installed_openbox=1",
                "installed_icewm=yes",
                "installed_jwm=false",
                "future_key=preserved-by-writer"
            )
        )

        assertEquals(ContainerPlatform.ALPINE, snapshot.platform)
        assertEquals(InitSystem.OPENRC, snapshot.initSystem)
        assertEquals(GraphicSession.OPENBOX, snapshot.graphicSession)
        assertTrue(snapshot.isGraphicSessionInstalled(GraphicSession.OPENBOX))
        assertTrue(snapshot.isGraphicSessionInstalled(GraphicSession.ICEWM))
        assertFalse(snapshot.isGraphicSessionInstalled(GraphicSession.JWM))
        assertFalse(snapshot.isGraphicSessionInstalled(GraphicSession.NONE))
    }

    @Test
    fun parserPreservesLegacyFirstOccurrenceSemantics() {
        val snapshot = ContainerSettingsManager.parseSnapshot(
            listOf(
                "platform=ubuntu",
                "platform=alpine",
                "init_system=systemd",
                "init_system=openrc",
                "graphic_session=jwm",
                "graphic_session=openbox"
            )
        )

        assertEquals(ContainerPlatform.UBUNTU, snapshot.platform)
        assertEquals(InitSystem.SYSTEMD, snapshot.initSystem)
        assertEquals(GraphicSession.JWM, snapshot.graphicSession)
    }

    @Test
    fun malformedAndUnknownValuesRemainNonFatal() {
        val snapshot = ContainerSettingsManager.parseSnapshot(
            listOf(
                "broken",
                "=missing-key",
                "platform=unknown",
                "init_system=other",
                "graphic_session=does-not-exist",
                "installed_openbox=maybe"
            )
        )

        assertNull(snapshot.platform)
        assertNull(snapshot.initSystem)
        assertNull(snapshot.graphicSession)
        assertFalse(snapshot.isGraphicSessionInstalled(GraphicSession.OPENBOX))
    }
}
