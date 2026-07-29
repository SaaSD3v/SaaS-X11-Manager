package com.saas.x11manager.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saas.x11manager.util.ContainerLogger
import com.saas.x11manager.util.ContainerManager
import com.saas.x11manager.util.ContainerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditContainerViewModel : ViewModel() {

    var name by mutableStateOf("")
    var hostname by mutableStateOf("")
    var status by mutableStateOf(ContainerStatus.UNKNOWN)
    var enablePulseAudio by mutableStateOf(false)
    var logs by mutableStateOf<List<ContainerLogger.Entry>>(emptyList())
    var isSaving by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
        private set

    private var loaded = false
    private var containerName = ""
    private var cacheDir: File? = null
    private val PA_BIND = "/data/data/com.termux/files/usr/tmp/.pulse-socket:/tmp/.pulse-socket"

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
            enablePulseAudio = info.bindMounts.contains("/tmp/.pulse-socket")
        }
    }

    fun save() {
        if (isSaving) return
        isSaving = true
        saveError = null

        viewModelScope.launch {
            try {
                val cd = cacheDir ?: run {
                    saveError = "Error: cacheDir not set"
                    isSaving = false
                    return@launch
                }

                val ok = ContainerManager.updatePulseAudioBindMount(
                    name = containerName,
                    enable = enablePulseAudio,
                    cacheDir = cd
                )

                if (ok) {
                    saveError = "OK: Config saved"
                    // Refresh status
                    val info = ContainerManager.getContainerInfo(containerName)
                    if (info != null) {
                        status = info.status
                        enablePulseAudio = info.bindMounts.contains("/tmp/.pulse-socket")
                    }
                } else {
                    saveError = "Error: Failed to write config"
                }
            } catch (e: Exception) {
                saveError = "Error: ${e.message}"
            } finally {
                isSaving = false
            }
        }
    }
}
