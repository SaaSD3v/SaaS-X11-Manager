package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell

/**
 * Persistence boundary for the current single-display lease.
 *
 * Container runtime state and display ownership are intentionally different:
 * a container may remain RUNNING after its managed X11 session is stopped.
 */
internal object DisplayLeaseRegistry {
    private const val OWNER_FILE = "${Constants.INTEGRATED_X11_RUNTIME_DIR}/owner"

    fun releaseSingleDisplayOwner(): Boolean = try {
        Shell.cmd("rm -f ${shellQuote(OWNER_FILE)} 2>/dev/null").exec().isSuccess
    } catch (_: Exception) {
        false
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
