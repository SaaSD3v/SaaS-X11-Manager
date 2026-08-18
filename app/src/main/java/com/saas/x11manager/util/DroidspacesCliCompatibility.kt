package com.saas.x11manager.util

/**
 * Builds DroidSpaces CLI commands from advertised capabilities instead of
 * assuming a backend version. Current DroidSpaces documents --conf/-C for
 * loading config files, while older deployments used --config.
 */
internal object DroidspacesCliCompatibility {

    fun startWithConfigCommand(binaryPath: String, configPath: String): String {
        val binary = shellQuote(binaryPath)
        val config = shellQuote(configPath)
        return "if $binary help 2>&1 | grep -Fq -e '--conf'; then " +
            "$binary --conf=$config start 2>&1; " +
            "else $binary --config=$config start 2>&1; fi"
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
