package com.saas.x11manager.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GraphicSessionNoDefaultXfcePolicyTest {

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
    fun editContainerDoesNotPretendXfceIsInstalledOrSelected() {
        val viewModel = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/EditContainerViewModel.kt"
        )
        val containerManager = source(
            "app/src/main/java/com/saas/x11manager/util/ContainerManager.kt"
        )

        assertFalse(viewModel.contains("mutableStateOf(GraphicSession.XFCE)"))
        assertFalse(Regex("savedGraphicSession\\s*=\\s*GraphicSession\\.XFCE").containsMatchIn(viewModel))
        assertFalse(Regex("\\?:\\s*GraphicSession\\.XFCE").containsMatchIn(viewModel))
        assertFalse(viewModel.contains("installed || graphicSession == session"))
        assertFalse(viewModel.contains("installedSessions[session] == true || graphicSession == session"))
        assertFalse(Regex("getGraphicSession\\(name\\)\\s*\\?:\\s*GraphicSession\\.XFCE").containsMatchIn(containerManager))

        assertTrue(viewModel.contains("mutableStateOf(GraphicSession.NONE)"))
        assertTrue(viewModel.contains("snapshot.isGraphicSessionInstalled(session)"))
        assertTrue(containerManager.contains("getGraphicSession(name) ?: GraphicSession.NONE"))

        // XFCE remains a legitimate installable catalog option; only implicit defaults are forbidden.
        assertTrue(containerManager.contains("x11-xfce.service"))
    }
}
