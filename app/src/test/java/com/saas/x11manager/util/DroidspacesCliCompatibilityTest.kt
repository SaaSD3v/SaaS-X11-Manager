package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DroidspacesCliCompatibilityTest {

    @Test
    fun startCommandPrefersDocumentedConfFlagWithLegacyFallback() {
        val command = DroidspacesCliCompatibility.startWithConfigCommand(
            binaryPath = "/data/local/Droidspaces/bin/droidspaces",
            configPath = "/data/local/Droidspaces/Containers/demo/container.config"
        )

        val current = "--conf='/data/local/Droidspaces/Containers/demo/container.config' start"
        val legacy = "--config='/data/local/Droidspaces/Containers/demo/container.config' start"

        assertTrue(command.contains("help 2>&1"))
        assertTrue(command.contains("grep -Fq -e '--conf'"))
        assertTrue(command.contains(current))
        assertTrue(command.contains(legacy))
        assertTrue(command.indexOf(current) < command.indexOf(legacy))
        assertFalse(command.contains("VERSION_ID="))
    }

    @Test
    fun startCommandShellQuotesConfigPath() {
        val command = DroidspacesCliCompatibility.startWithConfigCommand(
            binaryPath = "/data/local/Droidspaces/bin/droidspaces",
            configPath = "/tmp/user's container.config"
        )

        assertTrue(command.contains("--conf='/tmp/user'\\''s container.config' start"))
        assertTrue(command.contains("--config='/tmp/user'\\''s container.config' start"))
    }
}
