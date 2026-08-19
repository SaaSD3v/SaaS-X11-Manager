package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdditionalGraphicSessionRuntimeAvailabilityTest {

    @Test
    fun standaloneAptCandidateCheckIsRedundantDuringInstall() {
        val step = GraphicSessionInstallStep(
            title = "Checking qtile availability",
            command = AptPackageAvailability.candidateCommand("qtile")
        )

        assertTrue(
            AdditionalGraphicSessionRuntime.isRedundantStandalonePackageAvailabilityStep(step)
        )
    }

    @Test
    fun standaloneApkSearchIsRedundantDuringInstall() {
        val step = GraphicSessionInstallStep(
            title = "Checking openbox availability",
            command = "apk search -e openbox >/dev/null"
        )

        assertTrue(
            AdditionalGraphicSessionRuntime.isRedundantStandalonePackageAvailabilityStep(step)
        )
    }

    @Test
    fun transactionSafetyIsSkippedDuringRuntimeInstall() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.QTILE
            )
        )
        val step = requireNotNull(AptTransactionSafety.stepFor(plan))

        assertTrue(
            AdditionalGraphicSessionRuntime.isRedundantStandalonePackageAvailabilityStep(step)
        )
    }

    @Test
    fun verificationPackageCheckIsNotClassifiedAsRedundantAvailability() {
        val step = GraphicSessionInstallStep(
            title = "Checking Qtile packages",
            command = "dpkg -s qtile >/dev/null 2>&1 && dpkg -s xterm >/dev/null 2>&1"
        )

        assertFalse(
            AdditionalGraphicSessionRuntime.isRedundantStandalonePackageAvailabilityStep(step)
        )
    }
}
