package com.saas.x11manager.util

enum class ContainerDistribution(
    val suggestedPlatform: ContainerPlatform?
) {
    ALPINE(ContainerPlatform.ALPINE),
    UBUNTU(ContainerPlatform.UBUNTU),
    DEBIAN(ContainerPlatform.UBUNTU),
    UNKNOWN(null)
}

/**
 * Pure /etc/os-release parser used only to suggest an initial UI profile and
 * to choose repository mechanics later. Persisted user choices always win.
 */
internal object ContainerDistributionParser {

    fun parse(lines: List<String>): ContainerDistribution {
        val values = buildMap<String, String> {
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@forEach

                val key = line.substringBefore('=').trim()
                if (key.isEmpty()) return@forEach

                val value = unquote(line.substringAfter('=').trim())
                put(key, value)
            }
        }

        classify(values["ID"], values["ID_LIKE"])
    }

    private fun classify(id: String?, idLike: String?): ContainerDistribution {
        when (id?.lowercase()) {
            "alpine" -> return ContainerDistribution.ALPINE
            "ubuntu" -> return ContainerDistribution.UBUNTU
            "debian" -> return ContainerDistribution.DEBIAN
        }

        val related = idLike
            ?.lowercase()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return when {
            "alpine" in related -> ContainerDistribution.ALPINE
            "ubuntu" in related -> ContainerDistribution.UBUNTU
            "debian" in related -> ContainerDistribution.DEBIAN
            else -> ContainerDistribution.UNKNOWN
        }
    }

    private fun unquote(value: String): String {
        if (value.length < 2) return value
        val first = value.first()
        val last = value.last()
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.lastIndex)
        }
        return value
    }
}
