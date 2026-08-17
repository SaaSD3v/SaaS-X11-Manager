package com.saas.x11manager.util

/**
 * Parser for /proc/net/unix. Kept pure so socket ownership resolution can be
 * tested without depending on a rooted Android runtime.
 */
internal object UnixSocketTableParser {

    fun findInode(lines: List<String>, socketPath: String): String? {
        if (socketPath.isBlank()) return null

        return lines.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(Regex("\\s+"), limit = 8) }
            .firstOrNull { columns ->
                columns.size == 8 &&
                    columns[7] == socketPath &&
                    columns[6].isNotEmpty() &&
                    columns[6].all(Char::isDigit)
            }
            ?.get(6)
    }
}
