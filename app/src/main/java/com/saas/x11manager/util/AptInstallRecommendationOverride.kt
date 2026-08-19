package com.saas.x11manager.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the APT recommendation choice only while the UI is running one install.
 * The catalog remains minimal by default, and Alpine plans are never changed.
 */
internal object AptInstallRecommendationOverride {

    private val values = ConcurrentHashMap<GraphicSession, Boolean>()

    fun set(session: GraphicSession, installRecommendedPackages: Boolean) {
        values[session] = installRecommendedPackages
    }

    fun clear(session: GraphicSession) {
        values.remove(session)
    }

    fun apply(plan: GraphicSessionInstallPlan): GraphicSessionInstallPlan {
        if (plan.platform != ContainerPlatform.UBUNTU) return plan
        val installRecommendedPackages = values[plan.session] ?: return plan
        return plan.copy(installRecommendedPackages = installRecommendedPackages)
    }
}
