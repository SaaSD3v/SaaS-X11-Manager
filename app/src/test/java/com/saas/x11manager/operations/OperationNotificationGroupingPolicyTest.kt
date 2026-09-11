package com.saas.x11manager.operations

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OperationNotificationGroupingPolicyTest {
    private fun projectFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.exists()) return candidate
            current = current.parentFile
        }
        return File(System.getProperty("user.dir"), relativePath)
    }

    @Test
    fun `operation notifications use one explicit Android group`() {
        val source = projectFile(
            "app/src/main/java/com/saas/x11manager/operations/OperationNotifications.kt"
        ).readText()

        assertTrue(source.contains("GROUP_KEY"))
        assertTrue(source.contains(".setGroup(GROUP_KEY)"))
        assertTrue(source.contains(".setGroupSummary(true)"))
        assertTrue(source.contains("SUMMARY_ID"))
        assertTrue(source.contains("refreshSummary(context)"))
    }
}
