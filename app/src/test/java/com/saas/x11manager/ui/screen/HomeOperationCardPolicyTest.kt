package com.saas.x11manager.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeOperationCardPolicyTest {

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
    fun homeOperationCardsExistOnlyWhileTheirOperationIsRunning() {
        val home = source("app/src/main/java/com/saas/x11manager/ui/screen/HomeScreen.kt")

        assertTrue(home.contains("val allOperation = viewModel.logOperation(\"__all__\")"))
        assertTrue(home.contains("if (allOperation.running)"))
        assertTrue(home.contains("val operation = viewModel.logOperation(container.name)"))
        assertTrue(home.contains("if (operation.running)"))

        assertFalse(home.contains("logOperation(\"__all__\").available"))
        assertFalse(home.contains("logOperation(container.name).available"))
    }

    @Test
    fun completedLogsRemainReachableFromTheContainerLogButton() {
        val home = source("app/src/main/java/com/saas/x11manager/ui/screen/HomeScreen.kt")
        val viewModel = source("app/src/main/java/com/saas/x11manager/ui/screen/HomeViewModel.kt")

        assertTrue(home.contains("onShowLogs = { viewModel.showLogs(container) }"))
        assertTrue(viewModel.contains("fun showLogs(container: ContainerInfo)"))
        assertTrue(viewModel.contains("val logs = logsFor(container.name)"))
        assertTrue(viewModel.contains("private fun logsFor(name: String)"))
        assertTrue(viewModel.contains("val newLogs = logOperation(name).logs"))
    }
}
