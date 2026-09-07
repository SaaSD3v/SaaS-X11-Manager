package com.saas.x11manager.util

/**
 * Runtime access policy exposed by the current UI.
 *
 * BOTH remains in [SessionAccessMode] only so installations that persisted the
 * old enum name can be migrated without crashing. It is not a selectable mode
 * and is normalized to Integrated X11 before any runtime action is executed.
 */
object RuntimeAccessPolicy {
    val selectableModes: List<SessionAccessMode> = listOf(
        SessionAccessMode.INTEGRATED_X11,
        SessionAccessMode.VNC
    )

    fun normalize(mode: SessionAccessMode): SessionAccessMode = when (mode) {
        SessionAccessMode.BOTH -> SessionAccessMode.INTEGRATED_X11
        SessionAccessMode.INTEGRATED_X11 -> SessionAccessMode.INTEGRATED_X11
        SessionAccessMode.VNC -> SessionAccessMode.VNC
    }
}
