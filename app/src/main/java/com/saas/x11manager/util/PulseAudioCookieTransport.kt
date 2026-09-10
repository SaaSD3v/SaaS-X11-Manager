package com.saas.x11manager.util

/** ASCII-only transport of the exact 256-byte authentication cookie. */
internal object PulseAudioCookieTransport {
    private val octalCookie = Regex("""(?:\\0[0-3][0-7]{2}){256}""")

    fun encodeCommand(cookiePath: String): String = """
        cookie_file=${quote(cookiePath)}
        [ -r "${'$'}cookie_file" ] || exit 1
        [ "${'$'}(wc -c < "${'$'}cookie_file" | tr -d '[:space:]')" = 256 ] || exit 2
        LC_ALL=C od -An -v -tu1 "${'$'}cookie_file" |
            LC_ALL=C awk '{ for (i=1; i<=NF; i++) printf "\\0%03o", ${'$'}i }'
    """.trimIndent()

    // Kotlin raw strings do not consume backslashes. AWK must emit one literal
    // backslash per byte (\0ooo), which the container decodes once with printf %b.
    // Reject truncation, banners and double escaping before changing the client.
    fun fromOutput(lines: List<String>): String? = lines.joinToString("").trim()
        .takeIf { octalCookie.matches(it) }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
