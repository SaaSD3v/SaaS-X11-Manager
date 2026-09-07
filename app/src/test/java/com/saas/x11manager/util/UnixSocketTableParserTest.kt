package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnixSocketTableParserTest {

    @Test
    fun selectsExactDisplaySocketInodeWhenOtherDisplaysExist() {
        val x0 = X11DisplaySlot(0).socketFile
        val x5 = X11DisplaySlot(5).socketFile
        val lines = listOf(
            "Num RefCount Protocol Flags Type St Inode Path",
            "000000001: 00000002 00000000 00000000 0001 01 11111 $x5",
            "000000002: 00000002 00000000 00000000 0001 01 22222 $x0"
        )

        assertEquals("22222", UnixSocketTableParser.findInode(lines, x0))
    }

    @Test
    fun doesNotAcceptPathPrefixOrSuffixMatches() {
        val slot = X11DisplaySlot(0)
        val x0 = slot.socketFile
        val lines = listOf(
            "000000001: 00000002 00000000 00000000 0001 01 11111 ${x0}0",
            "000000002: 00000002 00000000 00000000 0001 01 22222 ${slot.socketDir}"
        )

        assertNull(UnixSocketTableParser.findInode(lines, x0))
    }

    @Test
    fun ignoresMalformedInodeAndUsesValidEntry() {
        val x0 = X11DisplaySlot(0).socketFile
        val lines = listOf(
            "000000001: 00000002 00000000 00000000 0001 01 not-an-inode $x0",
            "000000002: 00000002 00000000 00000000 0001 01 33333 $x0"
        )

        assertEquals("33333", UnixSocketTableParser.findInode(lines, x0))
    }

    @Test
    fun missingSocketReturnsNull() {
        val x0 = X11DisplaySlot(0).socketFile
        val x5 = X11DisplaySlot(5).socketFile
        val lines = listOf(
            "000000001: 00000002 00000000 00000000 0001 01 11111 $x5"
        )

        assertNull(UnixSocketTableParser.findInode(lines, x0))
    }
}
