package com.saas.x11manager.ui.screen

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saas.x11manager.util.ContainerManager
import com.saas.x11manager.util.ContainerPlatform
import com.saas.x11manager.util.ContainerSettingsManager
import com.saas.x11manager.util.ContainerStatus
import com.saas.x11manager.util.GraphicSession
import com.saas.x11manager.util.GraphicSessionInstaller
import com.saas.x11manager.util.InitSystem
import com.saas.x11manager.util.ViewModelLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditContainerViewModel : ViewModel() {

    var name by mutableStateOf("")
    var hostname by mutableStateOf("")
    var status by mutableStateOf(ContainerStatus.UNKNOWN)
    var initSystem by mutableStateOf(InitSystem.SYSTEMD)
        private set
    var graphicSession by mutableStateOf(GraphicSession.XFCE)
        private set
    var openboxInstalled by mutableStateOf(false)
        private set

    var logs by mutableStateOf<List<Pair<Int, String>>>(emptyList())
    val installLogs = mutableStateListOf<Pair<Int, String>>()

    var isSaving by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
        private set
    var isInstallingSession by mutableStateOf(false)
        private set
    var showInstallTerminal by mutableStateOf(false)
        private set
    var sessionOperationTitle by mutableStateOf("Openbox")
        private set
    var installResult by mutableStateOf<String?>(null)
        private set

    private var loaded = false
    private var containerName = ""
    private var cacheDir: File? = null
    private var savedGraphicSession = GraphicSession.XFCE

    fun load(containerName: String, cacheDir: File) {
        if (loaded && this.containerName == containerName) return
        this.containerName = containerName
        this.cacheDir = cacheDir
        loaded = true

        viewModelScope.launch {
            val info = ContainerManager.getContainerInfo(containerName) ?: return@launch
            name = info.name
            hostname = info.hostname
            status = info.status
            initSystem = info.initSystem

            val sessionState = withContext(Dispatchers.IO) {
                val saved = ContainerSettingsManager.getGraphicSession(containerName)
                val installed = ContainerSettingsManager.isGraphicSessionInstalled(
                    containerName,
                    GraphicSession.OPENBOX
                )
                Pair(saved, installed)
            }

            graphicSession = sessionState.first ?: GraphicSession.XFCE
            savedGraphicSession = graphicSession
            openboxInstalled = sessionState.second || graphicSession == GraphicSession.OPENBOX
            logs = emptyList()
            installLogs.clear()
        }
    }

    fun selectInitSystem(system: InitSystem) {
        initSystem = system
    }

    fun toggleOpenboxSelection() {
        if (!openboxInstalled || isInstallingSession || isSaving) return
        graphicSession = if (graphicSession == GraphicSession.OPENBOX) {
            GraphicSession.NONE
        } else {
            GraphicSession.OPENBOX
        }
        installResult = if (graphicSession == GraphicSession.OPENBOX) {
            "Openbox selected — tap Save to apply"
        } else {
            "Openbox deselected — tap Save to apply"
        }
    }

    fun installOpenbox() {
        if (isInstallingSession || isSaving) return

        val cd = cacheDir ?: run {
            showOperationSetupError("Installing Openbox", "cacheDir not set")
            return
        }

        beginSessionOperation("Installing Openbox")
        val selectedInitSystem = initSystem
        val logger = operationLogger()

        viewModelScope.launch {
            try {
                val installed = GraphicSessionInstaller.install(
                    containerName = containerName,
                    platform = ContainerPlatform.ALPINE,
                    session = GraphicSession.OPENBOX,
                    initSystem = selectedInitSystem,
                    cacheDir = cd,
                    logger = logger
                )

                if (installed) {
                    initSystem = selectedInitSystem
                    graphicSession = GraphicSession.OPENBOX
                    savedGraphicSession = GraphicSession.OPENBOX
                    openboxInstalled = true

                    val markerSaved = withContext(Dispatchers.IO) {
                        ContainerSettingsManager.setGraphicSessionInstalled(
                            containerName = containerName,
                            graphicSession = GraphicSession.OPENBOX,
                            installed = true,
                            cacheDir = cd
                        )
                    }
                    if (!markerSaved) {
                        logger.w("[!] Openbox is installed, but its installed marker could not be saved")
                    }

                    logger.i("")
                    logger.i("[*] Stopping container after installation...")
                    val stopped = ContainerManager.stopContainer(containerName, logger)
                    val (statusAfterStop, _) = ContainerManager.getContainerRuntimeStatePublic(containerName)
                    if (stopped || statusAfterStop == ContainerStatus.STOPPED) {
                        logger.i("[+] Container stopped")
                        logger.i("")
                        logger.i("[+] Openbox installation completed successfully")
                        logger.i("[+] Click Start X11 to launch the session")
                        installResult = "OK: Openbox installed — click Start X11"
                    } else {
                        logger.w("[!] Openbox was installed, but the container could not be confirmed stopped")
                        logger.w("[!] Stop the container before clicking Start X11")
                        installResult = "Warning: Openbox installed; stop container before Start X11"
                    }
                } else {
                    installResult = "Error: Openbox installation failed"
                }
            } catch (e: Exception) {
                logOperationException(e, "Openbox installation failed")
            } finally {
                refreshRuntimeStatus()
                isInstallingSession = false
            }
        }
    }

    fun verifyOpenbox() {
        if (isInstallingSession || isSaving) return

        beginSessionOperation("Verifying Openbox")
        val selectedInitSystem = initSystem
        val logger = operationLogger()

        viewModelScope.launch {
            try {
                val verified = GraphicSessionInstaller.verify(
                    containerName = containerName,
                    platform = ContainerPlatform.ALPINE,
                    session = GraphicSession.OPENBOX,
                    initSystem = selectedInitSystem,
                    logger = logger
                )

                if (verified) {
                    openboxInstalled = true
                    cacheDir?.let { cd ->
                        withContext(Dispatchers.IO) {
                            ContainerSettingsManager.setGraphicSessionInstalled(
                                containerName,
                                GraphicSession.OPENBOX,
                                true,
                                cd
                            )
                        }
                    }
                    installResult = "OK: Openbox verified"
                } else {
                    installResult = "Error: Openbox verification failed"
                }
            } catch (e: Exception) {
                logOperationException(e, "Openbox verification failed")
            } finally {
                refreshRuntimeStatus()
                isInstallingSession = false
            }
        }
    }

    private fun beginSessionOperation(title: String) {
        installLogs.clear()
        installResult = null
        sessionOperationTitle = title
        showInstallTerminal = true
        isInstallingSession = true
    }

    private fun operationLogger(): ViewModelLogger = ViewModelLogger { level, message ->
        installLogs.add(level to message)
    }

    private fun showOperationSetupError(title: String, message: String) {
        installLogs.clear()
        installLogs.add(Log.ERROR to "[-] FAIL")
        installLogs.add(Log.ERROR to "[-] $message")
        installResult = "Error: $message"
        sessionOperationTitle = title
        showInstallTerminal = true
    }

    private fun logOperationException(e: Exception, fallback: String) {
        val message = e.message ?: fallback
        installLogs.add(Log.ERROR to "[-] FAIL")
        installLogs.add(Log.ERROR to "[-] $message")
        installResult = "Error: $message"
    }

    private suspend fun refreshRuntimeStatus() {
        val (runtimeStatus, _) = ContainerManager.getContainerRuntimeStatePublic(containerName)
        status = runtimeStatus
    }

    fun dismissInstallTerminal() {
        if (!isInstallingSession) showInstallTerminal = false
    }

    fun clearInstallLogs() {
        if (!isInstallingSession) installLogs.clear()
    }

    fun save() {
        if (isSaving || isInstallingSession) return
        isSaving = true
        saveError = null
        logs = emptyList()

        viewModelScope.launch {
            try {
                val cd = cacheDir ?: run {
                    saveError = "Error: cacheDir not set"
                    isSaving = false
                    return@launch
                }

                logs = logs + (Log.INFO to "[*] Saving configuration...")
                val previousSession = savedGraphicSession
                val sessionSaved = withContext(Dispatchers.IO) {
                    ContainerSettingsManager.setGraphicSession(
                        containerName = containerName,
                        graphicSession = graphicSession,
                        cacheDir = cd
                    )
                }

                if (!sessionSaved) {
                    logs = logs + (Log.ERROR to "[-] Failed to save Graphic Session")
                    saveError = "Error: Failed to save Graphic Session"
                    return@launch
                }

                val initOk = ContainerManager.updateInitSystem(
                    name = containerName,
                    target = initSystem,
                    cacheDir = cd
                )

                if (initOk) {
                    savedGraphicSession = graphicSession
                    logs = logs + (Log.INFO to "[+] Init System and Graphic Session saved")
                    saveError = "OK: Config saved"
                    val info = ContainerManager.getContainerInfo(containerName)
                    if (info != null) {
                        status = info.status
                        initSystem = info.initSystem
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        ContainerSettingsManager.setGraphicSession(
                            containerName = containerName,
                            graphicSession = previousSession,
                            cacheDir = cd
                        )
                    }
                    graphicSession = previousSession
                    logs = logs + (Log.ERROR to "[-] Failed to apply Init System / Graphic Session")
                    saveError = "Error: Failed to apply configuration"
                }
            } catch (e: Exception) {
                logs = logs + (Log.ERROR to "[-] ${e.message}")
                saveError = "Error: ${e.message}"
            } finally {
                isSaving = false
            }
        }
    }
}
