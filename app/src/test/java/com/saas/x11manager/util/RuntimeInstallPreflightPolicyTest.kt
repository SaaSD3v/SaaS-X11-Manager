package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeInstallPreflightPolicyTest {

    @Test
    fun genericRuntimeSkipsRedundantPackagePreflightSteps() {
        listOf(
            GraphicSessionInstallStep("Validating Alpine package manager", "command -v apk >/dev/null"),
            GraphicSessionInstallStep("Validating Debian package manager", "command -v apt-get >/dev/null"),
            GraphicSessionInstallStep("Checking required apt repository", "expensive repository probe"),
            GraphicSessionInstallStep("Checking APT transaction safety", "apt-get -s install qtile"),
            GraphicSessionInstallStep("Checking apk transaction safety", "apk --simulate add qtile")
        ).forEach { step ->
            assertTrue(AdditionalGraphicSessionRuntime.isRedundantStandalonePackageAvailabilityStep(step))
        }
    }

    @Test
    fun realPackageOperationsAndVerificationChecksStillRun() {
        listOf(
            GraphicSessionInstallStep("Refreshing package index", "DEBIAN_FRONTEND=noninteractive apt-get update"),
            GraphicSessionInstallStep("Installing Qtile packages", "apt-get install -y --no-install-recommends qtile xterm"),
            GraphicSessionInstallStep("Installing Qtile packages", "apk add qtile xterm"),
            GraphicSessionInstallStep("Checking Qtile session command", "command -v saas-qtile-session"),
            GraphicSessionInstallStep("Checking Debian package manager", "command -v apt-get >/dev/null && command -v dpkg >/dev/null")
        ).forEach { step ->
            assertFalse(AdditionalGraphicSessionRuntime.isRedundantStandalonePackageAvailabilityStep(step))
        }
    }
}
