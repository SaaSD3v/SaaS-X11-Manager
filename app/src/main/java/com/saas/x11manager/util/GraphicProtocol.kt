package com.saas.x11manager.util

/**
 * Display protocol used by a graphical session.
 *
 * X11 remains the default for the existing catalog. Wayland sessions are
 * introduced through the same session/install-plan pipeline so package,
 * repository and init-system handling stay centralized.
 */
enum class GraphicProtocol(val label: String) {
    X11("X11"),
    WAYLAND("Wayland")
}
