package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DroidspacesCheckerRequirementsTest {

    @Test
    fun parsesCurrentSuccessfulRequirementsSummary() {
        val result = DroidspacesChecker.parseRequirementsOutput(
            listOf(
                "Summary:",
                "  [\u001B[32m✓\u001B[0m] All required features found!"
            ),
            commandSucceeded = true
        )

        assertEquals(DroidspacesRequirementState.READY, result.state)
        assertNull(result.missingRequiredCount)
        assertEquals("All required DroidSpaces features found", result.summary)
    }

    @Test
    fun parsesCurrentMissingRequirementsSummary() {
        val result = DroidspacesChecker.parseRequirementsOutput(
            listOf(
                "Summary:",
                "  [\u001B[31m✗\u001B[0m] 3 required feature(s) missing - Droidspaces will not work"
            ),
            commandSucceeded = true
        )

        assertEquals(DroidspacesRequirementState.MISSING_REQUIRED, result.state)
        assertEquals(3, result.missingRequiredCount)
        assertEquals("3 required DroidSpaces feature(s) missing", result.summary)
    }

    @Test
    fun acceptsSingularMissingRequirementWording() {
        val result = DroidspacesChecker.parseRequirementsOutput(
            listOf("1 required feature missing - Droidspaces will not work"),
            commandSucceeded = false
        )

        assertEquals(DroidspacesRequirementState.MISSING_REQUIRED, result.state)
        assertEquals(1, result.missingRequiredCount)
    }

    @Test
    fun keepsUnknownSuccessfulOutputInconclusive() {
        val result = DroidspacesChecker.parseRequirementsOutput(
            listOf("Future DroidSpaces diagnostic format"),
            commandSucceeded = true
        )

        assertEquals(DroidspacesRequirementState.INCONCLUSIVE, result.state)
        assertEquals("DroidSpaces requirements output was not recognized", result.summary)
    }

    @Test
    fun keepsFailedCommandInconclusiveInsteadOfAssumingMissingRequirements() {
        val result = DroidspacesChecker.parseRequirementsOutput(
            emptyList(),
            commandSucceeded = false
        )

        assertEquals(DroidspacesRequirementState.INCONCLUSIVE, result.state)
        assertEquals("DroidSpaces requirements check could not be completed", result.summary)
    }
}
