package com.saas.x11manager.operations

/**
 * Pure policy used by monitor screens to choose the lifecycle log that actually
 * belongs to the selected display. A monitor may have been started directly from
 * the monitor screen (MONITOR owner) or indirectly by starting its container from
 * Home (HOME owner). Logs from any other monitor/container are never eligible.
 */
internal data class MonitorLogCandidate(
    val owner: OperationOwner,
    val available: Boolean,
    val updatedAt: Long
)

internal object MonitorLogSelectionPolicy {
    fun select(
        displayTarget: String,
        containerName: String?,
        candidates: Iterable<MonitorLogCandidate>
    ): OperationOwner? {
        val monitorOwner = OperationOwner(OperationArea.MONITOR, displayTarget)
        val homeOwner = containerName
            ?.takeIf { it.isNotBlank() }
            ?.let { OperationOwner(OperationArea.HOME, it) }

        return candidates.asSequence()
            .filter { it.available }
            .filter { candidate ->
                candidate.owner == monitorOwner || candidate.owner == homeOwner
            }
            .maxWithOrNull(
                compareBy<MonitorLogCandidate> { it.updatedAt }
                    .thenBy { if (it.owner.area == OperationArea.MONITOR) 1 else 0 }
            )
            ?.owner
    }
}
