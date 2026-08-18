package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AptPackageAvailabilityTest {

    @Test
    fun packageAvailabilityRequiresAnInstallableCandidate() {
        val command = AptPackageAvailability.candidateCommand("w9wm")

        assertTrue(command.contains("LC_ALL=C apt-cache policy 'w9wm'"))
        assertTrue(command.contains("\$1 == \"Candidate:\""))
        assertTrue(command.contains("\$2 != \"(none)\""))
        assertFalse(command.contains("apt-cache show"))
        assertFalse(command.contains("VERSION_ID="))
    }

    @Test
    fun repositoryProbeFunctionUsesSameCandidateRule() {
        val command = AptPackageAvailability.shellFunctionDefinition()

        assertTrue(command.startsWith("apt_package_available()"))
        assertTrue(command.contains("apt-cache policy \"\$1\""))
        assertTrue(command.contains("\$2 != \"(none)\""))
        assertFalse(command.contains("apt-cache show"))
    }

    @Test
    fun packageNameIsShellQuoted() {
        val command = AptPackageAvailability.candidateCommand("user's-package")
        assertTrue(command.contains("'user'\\''s-package'"))
    }
}
