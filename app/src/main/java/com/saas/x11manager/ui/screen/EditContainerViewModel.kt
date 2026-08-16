package com.saas.x11manager.ui.screen

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saas.x11manager.util.ContainerManager
import com.saas.x11manager.util.ContainerStatus
import com.saas.x11manager.util.InitSystem
import kotlinx.coroutines.launch
import java.io.File

class EditContainerViewModel : ViewModel() {

    var name by mutableStateOf("")
    var hostname by mutableStateOf("")
    var status by mutableStateOf(ContainerStatus.UNKNOWN)
    var initSystem by mutableStateOf(InitSystem.SYSTEMD)
        private set
    var logs by mutableStateOf<List<Pair<Int, String>>>(emptyList())
    var isSaving by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
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
            logs = emptyList()
        }
    }

    fun selectInitSystem(system: InitSystem) {
        initSystem = system
    }

    fun save() {
        if (isSaving) return
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
