package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionUserManagerTest {

    @Test
    fun parserListsRootAndNormalLoginUsersOnly() {
        val users = GraphicSessionUserManager.parsePasswd(
            listOf(
                "root:x:0:0:root:/root:/bin/sh",
                "daemon:x:2:2:daemon:/sbin:/sbin/nologin",
                "messagebus:x:101:101:dbus:/nonexistent:/sbin/nologin",
                "higor:x:1000:1000:Higor:/home/higor:/bin/bash",
                "worker:x:1001:1001:Worker:/home/worker:/bin/ash",
                "nobody:x:65534:65534:nobody:/:/sbin/nologin"
            )
        )

        assertEquals(listOf("higor", "worker", "root"), users.map { it.name })
        assertEquals("/home/higor", users.first { it.name == "higor" }.home)
        assertTrue(users.first { it.name == "root" }.isRoot)
    }

    @Test
    fun userNameValidationAcceptsBasicLinuxNamesOnly() {
        assertTrue(GraphicSessionUserManager.isValidUserName("higor"))
        assertTrue(GraphicSessionUserManager.isValidUserName("user_2"))
        assertTrue(GraphicSessionUserManager.isValidUserName("dev-user"))
        assertFalse(GraphicSessionUserManager.isValidUserName(""))
        assertFalse(GraphicSessionUserManager.isValidUserName("2user"))
        assertFalse(GraphicSessionUserManager.isValidUserName("User"))
        assertFalse(GraphicSessionUserManager.isValidUserName("user name"))
        assertFalse(GraphicSessionUserManager.isValidUserName("user;id"))
    }

    @Test
    fun rootSelectionNeverRequestsAccountCreation() {
        assertEquals("root", GraphicSessionUserSelection.ROOT.userName)
        assertFalse(GraphicSessionUserSelection.ROOT.createIfMissing)
    }
}
