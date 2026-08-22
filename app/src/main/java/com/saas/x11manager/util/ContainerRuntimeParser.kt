package com.saas.x11manager.util

internal data class ContainerRuntimeState(
    val status: ContainerStatus,
    val pid: Int? = null
)

/**
 * Pure parsers for DroidSpaces runtime status output.
 *
 * A parser returns null when the supplied output is not confidently recognized.
 * Callers can then try the next capability instead of incorrectly treating an
 * unknown output format as "all containers stopped".
 */
internal object ContainerRuntimeParser {

    fun parseMachineReadableShow(
        lines: List<String>,
        containerNames: List<String>
    ): Map<String, ContainerRuntimeState>? {
        val names = normalizedNames(containerNames)
        if (names.isEmpty()) return emptyMap()

        val normalized = normalizedLines(lines)
        val summarySeen = normalized.any {
            it.startsWith("TOTAL_CONTAINERS=") || it.startsWith("RUN_CONTAINERS=")
        }
        val explicitlyEmpty = isNoContainersMessage(normalized)
        val running = mutableMapOf<String, Int>()
        var validContainerEntrySeen = false

        normalized.forEach { line ->
            if (!line.startsWith("CONT_")) return@forEach

            val separator = line.lastIndexOf('=')
            if (separator <= 5 || separator == line.lastIndex) return@forEach

            val name = line.substring(5, separator)
            val pid = line.substring(separator + 1).toIntOrNull()
            if (pid == null || pid <= 0) return@forEach

            validContainerEntrySeen = true
            if (name in names) running[name] = pid
        }

        if (!summarySeen && !explicitlyEmpty && !validContainerEntrySeen) return null

        return stoppedBaseline(names).apply {
            running.forEach { (name, pid) ->
                this[name] = ContainerRuntimeState(ContainerStatus.RUNNING, pid)
            }
        }
    }

    fun parsePlainShow(
        lines: List<String>,
        containerNames: List<String>
    ): Map<String, ContainerRuntimeState>? {
        val names = normalizedNames(containerNames)
        if (names.isEmpty()) return emptyMap()

        val normalized = normalizedLines(lines)
        val running = mutableMapOf<String, Int>()
        var validRowSeen = false

        normalized.forEach { line ->
            val delimiter = when {
                line.contains('│') -> '│'
                line.contains('|') -> '|'
                else -> return@forEach
            }

            val columns = line.split(delimiter)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (columns.size < 2) return@forEach

            val pid = columns.last().toIntOrNull() ?: return@forEach
            if (pid <= 0) return@forEach

            validRowSeen = true
            val name = columns[columns.lastIndex - 1]
            if (name in names) running[name] = pid
        }

        val explicitlyEmpty = isNoContainersMessage(normalized)
        if (!validRowSeen && !explicitlyEmpty) return null

        return stoppedBaseline(names).apply {
            running.forEach { (name, pid) ->
                this[name] = ContainerRuntimeState(ContainerStatus.RUNNING, pid)
            }
        }
    }

    fun parsePid(lines: List<String>): ContainerRuntimeState {
        val normalized = normalizedLines(lines)
        val pid = normalized.firstNotNullOfOrNull { it.toIntOrNull()?.takeIf { value -> value > 0 } }

        return when {
            pid != null -> ContainerRuntimeState(ContainerStatus.RUNNING, pid)
            normalized.any { it.equals("NONE", ignoreCase = true) } ->
                ContainerRuntimeState(ContainerStatus.STOPPED)
            else -> ContainerRuntimeState(ContainerStatus.UNKNOWN)
        }
    }

    /**
     * Parse one shell invocation containing multiple `droidspaces ... pid`
     * queries. Every query is prefixed by a marker carrying the requested
     * container name. Missing/unsupported output remains UNKNOWN exactly as it
     * does in [parsePid]; the batch transport must never manufacture STOPPED.
     */
    fun parsePidBatch(
        lines: List<String>,
        containerNames: List<String>,
        marker: String
    ): Map<String, ContainerRuntimeState> {
        val names = normalizedNames(containerNames)
        if (names.isEmpty()) return emptyMap()

        val sections = names.associateWithTo(mutableMapOf()) { mutableListOf<String>() }
        var currentName: String? = null

        lines.forEach { raw ->
            if (raw.startsWith(marker)) {
                val candidate = raw.removePrefix(marker)
                currentName = candidate.takeIf { it in sections }
            } else {
                currentName?.let { sections.getValue(it).add(raw) }
            }
        }

        return names.associateWith { name ->
            parsePid(sections[name].orEmpty())
        }
    }

    private fun normalizedNames(containerNames: List<String>): List<String> =
        containerNames.filter { it.isNotBlank() }.distinct()

    private fun normalizedLines(lines: List<String>): List<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() }

    private fun stoppedBaseline(
        containerNames: List<String>
    ): MutableMap<String, ContainerRuntimeState> =
        containerNames.associateWithTo(mutableMapOf()) {
            ContainerRuntimeState(ContainerStatus.STOPPED)
        }

    private fun isNoContainersMessage(lines: List<String>): Boolean =
        lines.any {
            it.contains("no containers", ignoreCase = true) &&
                it.contains("running", ignoreCase = true)
        }
}
