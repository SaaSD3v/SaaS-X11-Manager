package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnixSocketTableParserTest {

    @Test
    fun selectsExactX0InodeWhenOtherDisplaysExist() {
        val x0 = Constants.X11_SOCK_FILE
        val x5 = "${Constants.X11_SOCK_DIR}/X5"
        val lines = listOf(
            "Num RefCount Protocol Flags Type St Inode Path",
            "000000001: 00000002 00000000 00000000 0001 01 11111 $x5",
            "000000002: 00000002 00000000 00000000 0001 01 22222 $x0"
        )

        assertEquals("22222", UnixSocketTableParser.findInode(lines, x0))
    }

    @Test
    fun doesNotAcceptPathPrefixOrSuffixMatches() {
        val x0 = Constants.X11_SOCK_FILE
        val lines = listOf(
            "000000001: 00000002 00000000 00000000 0001 01 11111 ${x0}0",
            "000000002: 00000002 00000000 00000000 0001 01 22222 ${Constants.X11_SOCK_DIR}"
        )

        assertNull(UnixSocketTableParser.findInode(lines, x0))
    }

    @Test
    fun ignoresMalformedInodeAndUsesValidEntry() {
        val x0 = Constants.X11_SOCK_FILE
        val lines = listOf(
            "000000001: 00000002 00000000 00000000 0001 01 not-an-inode $x0",
            "000000002: 00000002 00000000 00000000 0001 01 33333 $x0"
        )

        assertEquals("33333", UnixSocketTableParser.findInode(lines, x0))
    }

    @Test
    fun missingSocketReturnsNull() {
        val lines = listOf(
            "000000001: 00000002 00000000 00000000 0001 01 11111 ${Constants.X11_SOCK_DIR}/X5"
        )

        assertNull(UnixSocketTableParser.findInode(lines, Constants.X11_SOCK_FILE))
    }
}
