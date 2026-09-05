package com.saas.x11manager.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Applies the shared Debian/Ubuntu graphical base dependencies and holds the
 * APT recommendation choice only while the UI is running one install.
 * Alpine plans are never changed here.
 */
internal object AptInstallRecommendationOverride {

    private val values = ConcurrentHashMap<GraphicSession, Boolean>()

    private val baseX11Packages = listOf(
        "dbus",
        "dbus-x11",
        "x11-xserver-utils"
    )

    fun set(session: GraphicSession, installRecommendedPackages: Boolean) {
        values[session] = installRecommendedPackages
    }

    fun clear(session: GraphicSession) {
        values.remove(session)
    }

    fun apply(plan: GraphicSessionInstallPlan): GraphicSessionInstallPlan {
        if (plan.platform != ContainerPlatform.UBUNTU) return plan

        val installRecommendedPackages =
            values[plan.session] ?: plan.installRecommendedPackages
        val packages = (baseX11Packages + plan.packages).distinct()

        return plan.copy(
            packages = packages,
            installRecommendedPackages = installRecommendedPackages
        )
    }
}
