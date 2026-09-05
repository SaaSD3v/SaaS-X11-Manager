package com.saas.x11manager.util

import java.util.concurrent.ConcurrentHashMap

internal enum class AlpineInstallProfile {
    MINIMAL,
    FULL
}

/**
 * Applies the shared Alpine graphical base dependencies and holds the optional
 * desktop integration profile only while one install/reinstall is running.
 * Every Alpine graphical plan gets DBus and lightweight X11 client utilities;
 * FULL adds a small distro-native desktop integration bundle without adding an
 * X server, display manager or audio stack.
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

        val packages = buildList {
            addAll(baseX11Packages)
            addAll(plan.packages)
            if (values[plan.session] == AlpineInstallProfile.FULL) {
                addAll(fullDesktopPackages)
            }
        }.distinct()

        return plan.copy(packages = packages)
    }
}
