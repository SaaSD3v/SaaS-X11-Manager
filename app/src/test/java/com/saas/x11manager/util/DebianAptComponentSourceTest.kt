package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DebianAptComponentSourceTest {

    @Test
    fun commandUsesSupplementalSourcesWithoutVersionPinning() {
        val command = DebianAptComponentSource.commandFor("non-free")

        assertTrue(command.contains("saas-x11-manager-$component".replace("$component", "\$component")))
        assertTrue(command.contains("/etc/apt/sources.list.d/*.sources"))
        assertTrue(command.contains("/etc/apt/sources.list.d/*.list"))
        assertTrue(command.contains("debian-archive-keyring\\.gpg"))
        assertTrue(command.contains("Components: %s"))
        assertFalse(command.contains("VERSION_ID"))
        assertFalse(command.contains("sed -i"))
    }

    @Test
    fun unsafeComponentNameIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DebianAptComponentSource.commandFor("non-free;touch /tmp/bad")
        }
    }
}
