package com.saas.x11manager.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the APT recommendation choice only while the UI is running one
 * install/reinstall. The shared graphical base dependencies are injected into
 * that install transaction, not into the canonical package catalog exposed to
 * tests, capability checks and repository policy.
 * Alpine plans are never changed here.
 */
internal object AptInstallRecommendationOverride {

    private val values = ConcurrentHashMap<GraphicSession, Boolean>()

    internal val baseX11Packages = listOf(
        "dbus",
        "dbus-x11",
        "x11-xserver-utils",
        "xauth"
    )

    fun set(session: GraphicSession, installRecommendedPackages: Boolean) {
        values[session] = installRecommendedPackages
    }

    fun clear(session: GraphicSession) {
        values.remove(session)
    }

    fun apply(plan: GraphicSessionInstallPlan): GraphicSessionInstallPlan {
        if (plan.platform != ContainerPlatform.UBUNTU) return plan

        val installRecommendedPackages = values[plan.session] ?: return plan
        val packages = (baseX11Packages + plan.packages).distinct()

        return plan.copy(
            packages = packages,
            installRecommendedPackages = installRecommendedPackages
        )
    }
}
