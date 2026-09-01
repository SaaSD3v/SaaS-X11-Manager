package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VncAccessPolicyTest {

    @Test
    fun accessModesKeepIntegratedAndVncResponsibilitiesExplicit() {
        assertTrue(SessionAccessMode.INTEGRATED_X11.usesIntegratedX11)
        assertFalse(SessionAccessMode.INTEGRATED_X11.requiresVnc)

        assertFalse(SessionAccessMode.VNC.usesIntegratedX11)
        assertTrue(SessionAccessMode.VNC.requiresVnc)

        assertTrue(SessionAccessMode.BOTH.usesIntegratedX11)
        assertTrue(SessionAccessMode.BOTH.requiresVnc)
    }

    @Test
    fun defaultVncPortIs5901AndPortRangeIsValidated() {
        assertEquals(5901, VncSettings.DEFAULT_PORT)
        assertTrue(VncSettings.isValidPort(5901))
        assertTrue(VncSettings.isValidPort(1))
        assertTrue(VncSettings.isValidPort(65535))
        assertFalse(VncSettings.isValidPort(0))
        assertFalse(VncSettings.isValidPort(65536))
    }

    @Test
    fun vncPasswordPolicyMatchesLegacyVncAuthSignificantLength() {
        assertFalse(VncSettings.isValidPassword("12345"))
        assertTrue(VncSettings.isValidPassword("123456"))
        assertTrue(VncSettings.isValidPassword("12345678"))
        assertFalse(VncSettings.isValidPassword("123456789"))
        assertFalse(VncSettings.isValidPassword("12345\n"))
    }

    @Test
    fun portProbeChecksBothIpv4AndIpv6KernelTables() {
        val command = VncServerManager.portListeningCommand(5901)
        assertTrue(command.contains("/proc/net/tcp"))
        assertTrue(command.contains("/proc/net/tcp6"))
        assertTrue(command.contains("printf '%04X' 5901"))
        assertTrue(command.contains("exit 0"))
    }
}
