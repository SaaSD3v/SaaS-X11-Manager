package com.saas.x11manager.util

/** Keeps audio scripts within the DroidSpaces daemon's per-argument wire limit. */
internal object PulseAudioContainerCommand {
    // At most 3 KiB of UTF-8 per word, including non-ASCII shell content.
    private const val CHUNK_CHARS = 1024

    private val bootstrap = """
        saas_audio_script=''
        for saas_audio_part do
            saas_audio_script="${'$'}saas_audio_script${'$'}saas_audio_part"
        done
        exec /bin/sh -lc "${'$'}saas_audio_script"
    """.trimIndent()

    fun build(
        containerName: String,
        payload: String,
        binaryPath: String = Constants.DS_BINARY_PATH
    ): String {
        val words = mutableListOf(binaryPath, "--name=$containerName", "run",
            "/bin/sh", "-c", bootstrap, "saas-audio")
        var start = 0
        while (start < payload.length) {
            var end = (start + CHUNK_CHARS).coerceAtMost(payload.length)
            // Do not split a supplementary Unicode character across argv words.
            if (end < payload.length && Character.isHighSurrogate(payload[end - 1]) &&
                Character.isLowSurrogate(payload[end])) end--
            words += payload.substring(start, end)
            start = end
        }
        require(words.size - 1 <= 64) { "Audio command exceeds DroidSpaces argument capacity" }
        return words.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
    }
}
