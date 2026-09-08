package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VncSelectedUserPolicyTest {

    @Test
    fun standaloneVncLauncherUsesTheSharedGraphicalUserPolicy() {
        val script = GraphicSessionInitFiles.vncSessionScript(
            session = GraphicSession.ICEWM,
            shell = "/bin/sh"
        )

        assertTrue(script.contains("SESSION_USER_FILE=/etc/saas-x11-manager/session-user"))
        assertTrue(script.contains("export USER=\$SESSION_USER"))
        assertTrue(script.contains("export HOME=\$SESSION_HOME"))
        assertTrue(script.contains("exec su -p -s \"\$SESSION_SHELL\" \"\$SESSION_USER\""))
        assertTrue(script.contains("exec ${GraphicSession.ICEWM.startCommand}"))

        // The standalone VNC server chooses DISPLAY before invoking the launcher.
        // The user-aware script must preserve that display instead of scanning the
        // Manager's Integrated X11 bind mount.
        assertFalse(script.contains("X11_SOURCE=/usr/.X11-unix"))
        assertFalse(script.contains("export HOME=/root\nexport USER=root"))
    }
}
