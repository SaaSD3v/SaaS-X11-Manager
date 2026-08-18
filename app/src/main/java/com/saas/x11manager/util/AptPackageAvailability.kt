package com.saas.x11manager.util

/**
 * APT package metadata can exist without an installable candidate. Use the
 * package policy selected by APT itself so repository/preflight decisions match
 * what apt-get would actually be able to install.
 */
internal object AptPackageAvailability {

    private const val CANDIDATE_AWK =
        "\$1 == \"Candidate:\" && \$2 != \"(none)\" { found=1 } END { exit found ? 0 : 1 }"

    fun candidateCommand(packageName: String): String =
        "LC_ALL=C apt-cache policy ${shellQuote(packageName)} | awk '$CANDIDATE_AWK'"

    fun shellFunctionDefinition(functionName: String = "apt_package_available"): String =
        "$functionName() { LC_ALL=C apt-cache policy \"\$1\" | awk '$CANDIDATE_AWK'; };"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
