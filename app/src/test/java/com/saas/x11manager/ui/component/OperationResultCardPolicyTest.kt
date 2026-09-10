package com.saas.x11manager.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OperationResultCardPolicyTest {

    private fun source(relativePath: String): String {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate.readText()
            current = current.parentFile
        }
        error("Could not locate source file: $relativePath")
    }

    @Test
    fun inlineOperationCardsExistOnlyWhileTheOperationIsRunning() {
        val card = source("app/src/main/java/com/saas/x11manager/ui/component/OperationResultCard.kt")

        assertTrue(card.contains("if (!operation.running) return"))
        assertFalse(card.contains("if (!operation.available) return"))
        assertFalse(card.contains("operation.result"))
        assertTrue(card.contains("LinearProgressIndicator"))
        assertTrue(card.contains("Text(\"In progress\""))
        assertTrue(card.contains("Text(\"View logs\""))
    }

    @Test
    fun completedMonitorLogsRemainReachableWithoutACompletedInlineCard() {
        val screen = source("app/src/main/java/com/saas/x11manager/ui/screen/ManagedDisplayScreen.kt")

        // Saved logs remain exposed by the normal toolbar affordance.
        assertTrue(screen.contains("onShowLogs = displayViewModel::openLogs"))
        assertTrue(screen.contains("contentDescription = \"Monitor logs\""))

        // The same shared card can stay mounted in the screen because the
        // component itself now owns the transient-only contract.
        assertTrue(screen.contains("OperationResultCard(displayViewModel.logOperation, displayViewModel::openLogs)"))
    }
}
