package com.saas.x11manager.util

import java.util.concurrent.ConcurrentHashMap

internal enum class AlpineInstallProfile {
    MINIMAL,
    FULL
}

/**
 * Holds the Alpine package profile only while one install/reinstall is running.
 * Shared graphical base dependencies are injected into that install transaction
 * instead of changing the canonical distro/session package catalog.
 */
internal object AlpineInstallProfileOverride {

    internal val baseX11Packages = listOf(
        "dbus",
        "dbus-x11",
        "xauth",
        "xrandr",
        "xset"
    )

    internal val fullDesktopPackages = listOf(
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
        val profile = values[plan.session] ?: return plan

        val packages = buildList {
            addAll(baseX11Packages)
            addAll(plan.packages)
            if (profile == AlpineInstallProfile.FULL) {
                addAll(fullDesktopPackages)
            }
        }.distinct()

        return plan.copy(packages = packages)
    }
}
