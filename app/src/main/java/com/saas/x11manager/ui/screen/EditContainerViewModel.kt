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

    var logs by mutableStateOf<List<Pair<Int, String>>>(emptyList())
    val installLogs = mutableStateListOf<Pair<Int, String>>()

    var isSaving by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
        private set
    var isInstallingSession by mutableStateOf(false)
        private set
    var showInstallTerminal by mutableStateOf(false)
        private set
    var installResult by mutableStateOf<String?>(null)
        private set

    private var loaded = false
    private var containerName = ""
    private var cacheDir: File? = null

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
            graphicSession = withContext(Dispatchers.IO) {
                ContainerSettingsManager.getGraphicSession(containerName)
            } ?: GraphicSession.XFCE
            logs = emptyList()
            installLogs.clear()
        }
    }

    fun selectInitSystem(system: InitSystem) {
        initSystem = system
    }

    fun installOpenbox() {
        if (isInstallingSession || isSaving) return

        val cd = cacheDir ?: run {
            installLogs.clear()
            installLogs.add(Log.ERROR to "[-] FAIL")
            installLogs.add(Log.ERROR to "[-] cacheDir not set")
            installResult = "Error: cacheDir not set"
            showInstallTerminal = true
            return
        }

        installLogs.clear()
        installResult = null
        showInstallTerminal = true
        isInstallingSession = true

        val logger = ViewModelLogger { level, message ->
            installLogs.add(level to message)
        }

        viewModelScope.launch {
            try {
                val installed = GraphicSessionInstaller.install(
                    containerName = containerName,
                    platform = ContainerPlatform.ALPINE,
                    session = GraphicSession.OPENBOX,
                    cacheDir = cd,
                    logger = logger
                )

                if (installed) {
                    graphicSession = GraphicSession.OPENBOX
                    installResult = "OK: Openbox installed"
                } else {
                    installResult = "Error: Openbox installation failed"
                }
            } catch (e: Exception) {
                installLogs.add(Log.ERROR to "[-] FAIL")
                installLogs.add(Log.ERROR to "[-] ${e.message ?: "Unexpected installation error"}")
                installResult = "Error: ${e.message ?: "Openbox installation failed"}"
            } finally {
                isInstallingSession = false
            }
        }
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

                val initOk = ContainerManager.updateInitSystem(
                    name = containerName,
                    target = initSystem,
                    cacheDir = cd
                )

                if (initOk) {
                    logs = logs + (Log.INFO to "[+] Config saved successfully")
                    saveError = "OK: Config saved"
                    val info = ContainerManager.getContainerInfo(containerName)
                    if (info != null) {
                        status = info.status
                        initSystem = info.initSystem
                    }
                } else {
                    logs = logs + (Log.ERROR to "[-] Failed to write config")
                    saveError = "Error: Failed to write config"
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
