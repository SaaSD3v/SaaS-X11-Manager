package com.saas.x11manager.util

import java.util.concurrent.ConcurrentHashMap

internal enum class AlpineInstallProfile {
    MINIMAL,
    FULL
}

/**
 * Holds the Alpine package profile only while one install/reinstall is running.
 * Minimal preserves the researched session plan exactly. Full adds a small,
 * distro-native desktop integration bundle without adding an X server, display
 * manager or audio stack.
 */
internal object AlpineInstallProfileOverride {

    internal val fullDesktopPackages = listOf(
        "dbus-x11",
        "xdg-utils",
        "font-dejavu",
        "hicolor-icon-theme",
        "adwaita-icon-theme"
    )

    private val values = ConcurrentHashMap<GraphicSession, AlpineInstallProfile>()

    fun set(session: GraphicSession, profile: AlpineInstallProfile) {
        values[session] = profile
    }

    fun clear(session: GraphicSession) {
        values.remove(session)
    }

    fun apply(plan: GraphicSessionInstallPlan): GraphicSessionInstallPlan {
        if (plan.platform != ContainerPlatform.ALPINE) return plan
        if (values[plan.session] != AlpineInstallProfile.FULL) return plan
        return plan.copy(packages = (plan.packages + fullDesktopPackages).distinct())
    }
}
