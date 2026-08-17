package com.saas.x11manager.util

data class ContainerProfileSelection(
    val distribution: ContainerDistribution,
    val platform: ContainerPlatform,
    val initSystem: InitSystem,
    val graphicSession: GraphicSession
)

/**
 * Resolves edit-screen defaults without ever overriding persisted choices.
 * Distribution detection is only a suggestion source when no saved platform
 * or init choice exists yet.
 */
internal object ContainerProfileDefaults {

    fun resolve(
        distribution: ContainerDistribution,
        savedPlatform: ContainerPlatform?,
        savedInitSystem: InitSystem?,
        savedGraphicSession: GraphicSession?
    ): ContainerProfileSelection {
        val platform = savedPlatform
            ?: distribution.suggestedPlatform
            ?: ContainerPlatform.UBUNTU

        val initSystem = savedInitSystem ?: platform.defaultInitSystem
        val graphicSession = savedGraphicSession ?: GraphicSession.XFCE

        return ContainerProfileSelection(
            distribution = distribution,
            platform = platform,
            initSystem = initSystem,
            graphicSession = graphicSession
        )
    }
}
