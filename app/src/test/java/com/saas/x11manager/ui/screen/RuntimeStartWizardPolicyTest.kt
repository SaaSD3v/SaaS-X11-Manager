package com.saas.x11manager.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeStartWizardPolicyTest {

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
    fun homeStartChoosesLinuxUserBeforeTransportAndDoesNotPreselectTransportInCard() {
        val home = source("app/src/main/java/com/saas/x11manager/ui/screen/HomeScreen.kt")
        val userDialog = home.indexOf("GraphicSessionUserDialog(")
        val accessDialog = home.indexOf("GraphicAccessDialog(")

        assertTrue(userDialog >= 0)
        assertTrue(accessDialog > userDialog)
        assertTrue(home.contains("pendingUserContainer = null\n                pendingAccessContainer = container"))
        assertTrue(home.contains("startLabel = \"Start\""))
        assertFalse(home.contains("startLabel = \"Start X11\""))
        assertFalse(home.contains("startLabel = \"Start VNC\""))
    }

    @Test
    fun accessDialogExposesOnlyIndependentX11AndVncModesWithAdvancedVncAccess() {
        val dialog = source("app/src/main/java/com/saas/x11manager/ui/screen/GraphicAccessDialog.kt")

        assertTrue(dialog.contains("title = \"Integrated X11\""))
        assertTrue(dialog.contains("title = \"VNC\""))
        assertTrue(dialog.contains("Integrated X11 stays off"))
        assertTrue(dialog.contains("Text(\"Advanced settings\")"))
        assertTrue(dialog.contains("TigerVncSettingsDialog("))
        assertTrue(dialog.contains("ADB forward local port"))
        assertTrue(dialog.contains("127.0.0.1:"))
        assertFalse(dialog.contains("SessionAccessMode.BOTH"))
        assertFalse(dialog.contains("title = \"Both\""))
        assertFalse(dialog.contains("Run desktop as"))
    }

    @Test
    fun editContainerShowsDetectedFactsAsSummaryAndLeavesRuntimeAccessForHome() {
        val screen = source("app/src/main/java/com/saas/x11manager/ui/screen/EditContainerScreen.kt")
        val viewModel = source("app/src/main/java/com/saas/x11manager/ui/screen/EditContainerViewModel.kt")

        assertTrue(screen.contains("DetectedContainerSummaryDialog("))
        assertTrue(screen.contains("these values were detected automatically"))
        assertTrue(screen.contains("viewModel.wizardStage == ConfigurationWizardStage.DETECTED_SUMMARY"))
        assertFalse(screen.contains("GraphicAccessDialog("))
        assertTrue(screen.contains("color = MaterialTheme.colorScheme.background"))

        assertTrue(viewModel.contains("resolveAutomaticInitSystem"))
        assertTrue(viewModel.contains("ContainerSettingsManager.readSnapshot(containerName)"))
        assertFalse(viewModel.contains("readSnapshot(containerName, forceRefresh = true)"))
        assertTrue(viewModel.contains("Access method is chosen when you press Start on Home"))
    }

    @Test
    fun standaloneVncUsesSelectedLinuxUserAndOnlyReservesIntegratedMonitor() {
        val vnc = source("app/src/main/java/com/saas/x11manager/util/VncServerManager.kt")
        val access = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")
        val reservation = source("app/src/main/java/com/saas/x11manager/util/VncX11MonitorReservation.kt")

        assertTrue(vnc.contains("GraphicSessionInitFiles.vncSessionScript(session, \"/bin/sh\")"))
        assertTrue(access.contains("VncX11MonitorReservation.reserveBeforeVncStart"))
        assertTrue(access.indexOf("VncX11MonitorReservation.reserveBeforeVncStart") < access.indexOf("VncServerManager.startStandalone"))
        assertTrue(access.contains("desktopUser = userPreparation.selection.userName"))
        assertTrue(access.contains("adbLocalPort = vncAdbLocalPort"))
        assertTrue(reservation.contains("reserve only; Integrated X11 remains off"))
        assertTrue(reservation.contains("X11DisplayAllocator.firstFree(occupied)"))
        assertTrue(reservation.contains("ContainerConfigManager.ensureManualX11Config"))
    }

    @Test
    fun activeVncConnectionSummaryIsPinnedUntilRuntimeEnds() {
        val viewModel = source("app/src/main/java/com/saas/x11manager/ui/screen/HomeViewModel.kt")
        val guide = source("app/src/main/java/com/saas/x11manager/util/VncConnectionGuide.kt")

        assertTrue(guide.contains("ACTIVE_SUMMARY_BEGIN"))
        assertTrue(guide.contains("Connection information pinned until this VNC/container session ends"))
        assertTrue(viewModel.contains("VncConnectionGuide.retainPinnedSummary(logs)"))
        assertTrue(viewModel.contains("removePinnedVncSummary"))
    }
}
