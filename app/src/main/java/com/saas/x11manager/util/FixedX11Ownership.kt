package com.saas.x11manager.util

/** Bind sources identify ownership; an unrelated X11 socket does not own X0. */
internal object FixedX11Ownership {
    fun ownsServer(container: ContainerInfo): Boolean =
        container.isRunning && ContainerConfigManager.usesManagedX11(container.bindMounts)

    fun otherOwner(containers: List<ContainerInfo>, containerName: String): String? =
        containers.firstOrNull { it.name != containerName && ownsServer(it) }?.name

    fun canReleaseAfterStop(container: ContainerInfo?, remaining: List<ContainerInfo>): Boolean =
        container != null && ownsServer(container) && remaining.none(::ownsServer)
}
