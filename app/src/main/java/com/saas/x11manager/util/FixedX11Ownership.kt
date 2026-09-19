package com.saas.x11manager.util

/** Bind sources identify ownership; an unrelated X11 socket does not own X0. */
internal object FixedX11Ownership {
    fun ownsServer(container: ContainerInfo): Boolean =
        container.isRunning && ContainerConfigManager.usesManagedX11(container.bindMounts)

    fun owners(containers: List<ContainerInfo>): List<String> =
        containers.asSequence()
            .filter(::ownsServer)
            .map { it.name }
            .distinct()
            .sorted()
            .toList()

    fun otherOwners(containers: List<ContainerInfo>, containerName: String): List<String> =
        containers.asSequence()
            .filter { it.name != containerName && ownsServer(it) }
            .map { it.name }
            .distinct()
            .sorted()
            .toList()

    fun otherOwner(containers: List<ContainerInfo>, containerName: String): String? =
        otherOwners(containers, containerName).singleOrNull()

    fun hasConflict(containers: List<ContainerInfo>): Boolean =
        owners(containers).size > 1

    fun canReleaseAfterStop(container: ContainerInfo?, remaining: List<ContainerInfo>): Boolean =
        container != null && ownsServer(container) && owners(remaining).isEmpty()
}
