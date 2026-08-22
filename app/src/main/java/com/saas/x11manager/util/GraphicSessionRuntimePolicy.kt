package com.saas.x11manager.util

/**
 * Ephemeral in-container lease markers for Manager-owned X11 resources.
 *
 * /run is intentionally used so a container boot never inherits permission to
 * start a graphical session from a previous boot. The runtime controller owns
 * marker creation/removal; init services merely honor the markers.
 */
internal object GraphicSessionRuntimePolicy {
    const val REQUEST_DIR = "/run/saas-x11"
    const val SOCKET_REQUEST_FILE = "$REQUEST_DIR/socket-requested"
    const val SESSION_REQUEST_FILE = "$REQUEST_DIR/session-requested"
}
