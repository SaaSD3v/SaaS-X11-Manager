package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DroidspacesRequirementState {
    READY,
    MISSING_REQUIRED,
    INCONCLUSIVE
}

data class DroidspacesRequirementsResult(
    val state: DroidspacesRequirementState,
    val missingRequiredCount: Int? = null,
    val summary: String
)

object DroidspacesChecker {
    private val ansiEscape = Regex("\\u001B\\[[;\\d]*m")
    private val missingRequiredPattern = Regex(
        """\b(\d+)\s+required\s+feature(?:\(s\)|s)?\s+missing\b""",
        RegexOption.IGNORE_CASE
    )
    private val allRequiredFoundPattern = Regex(
        """\bAll\s+required\s+features\s+found!?""",
        RegexOption.IGNORE_CASE
    )

    suspend fun checkBackend(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.cmd("test -x '${Constants.DS_BINARY_PATH}' && echo ok").exec()
                .let { it.isSuccess && it.out.any { o -> o.contains("ok") } }
        } catch (_: Exception) { false }
    }

    suspend fun checkRequirements(): DroidspacesRequirementsResult = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("${Constants.DS_BINARY_PATH} check 2>&1").exec()
            parseRequirementsOutput(result.out + result.err, result.isSuccess)
        } catch (_: Exception) {
            inconclusive(commandSucceeded = false)
        }
    }

    internal fun parseRequirementsOutput(
        outputLines: List<String>,
        commandSucceeded: Boolean
    ): DroidspacesRequirementsResult {
        val output = ansiEscape.replace(outputLines.joinToString("\n"), "")
        val missing = missingRequiredPattern.find(output)
        if (missing != null || output.contains("Droidspaces will not work", ignoreCase = true)) {
            val count = missing?.groupValues?.getOrNull(1)?.toIntOrNull()
            return DroidspacesRequirementsResult(
                state = DroidspacesRequirementState.MISSING_REQUIRED,
                missingRequiredCount = count,
                summary = if (count != null) {
                    "$count required DroidSpaces feature(s) missing"
                } else {
                    "Required DroidSpaces features are missing"
                }
            )
        }

        if (allRequiredFoundPattern.containsMatchIn(output)) {
            return DroidspacesRequirementsResult(
                state = DroidspacesRequirementState.READY,
                summary = "All required DroidSpaces features found"
            )
        }

        return inconclusive(commandSucceeded)
    }

    private fun inconclusive(commandSucceeded: Boolean): DroidspacesRequirementsResult =
        DroidspacesRequirementsResult(
            state = DroidspacesRequirementState.INCONCLUSIVE,
            summary = if (commandSucceeded) {
                "DroidSpaces requirements output was not recognized"
            } else {
                "DroidSpaces requirements check could not be completed"
            }
        )

    fun getBinaryPath(): String = Constants.DS_BINARY_PATH
}
