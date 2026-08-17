package com.saas.x11manager.util

/**
 * User-selected container package/configuration profile.
 *
 * UBUNTU intentionally represents the apt-based path shared by Debian/Ubuntu
 * for the SaaS-X11-Manager UI. It is not a strict distro identity check.
 */
enum class ContainerPlatform(
    val label: String,
    val defaultInitSystem: InitSystem
) {
    UBUNTU("Ubuntu", InitSystem.SYSTEMD),
    ALPINE("Alpine", InitSystem.OPENRC)
}

/**
 * Graphical sessions supported by the configuration model.
 * Install/start support is enabled per session as its implementation lands.
 */
enum class GraphicSession(
    val label: String,
    val startCommand: String
) {
    XFCE("XFCE", "startxfce4"),
    LXQT("LXQt", "startlxqt"),
    OPENBOX("Openbox", "openbox-session")
}
