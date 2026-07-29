package com.saas.x11manager.ui.screen

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saas.x11manager.util.*
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel : ViewModel() {
    private val _containers = MutableStateFlow<List<ContainerInfo>>(emptyList())
    val containers: StateFlow<List<ContainerInfo>> = _containers

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _rootStatus = MutableStateFlow<RootStatus>(RootStatus.Checking)
    val rootStatus: StateFlow<RootStatus> = _rootStatus

    private val _termuxStatus = MutableStateFlow<TermuxStatus>(TermuxStatus.Checking)
    val termuxStatus: StateFlow<TermuxStatus> = _termuxStatus

    private val _x11ApkStatus = MutableStateFlow<X11ApkStatus>(X11ApkStatus.Checking)
    val x11ApkStatus: StateFlow<X11ApkStatus> = _x11ApkStatus

    private val _dsStatus = MutableStateFlow(false)
    val dsStatus: StateFlow<Boolean> = _dsStatus

    private val _loaderStatus = MutableStateFlow<LoaderStatus>(LoaderStatus.Stopped)
    val loaderStatus: StateFlow<LoaderStatus> = _loaderStatus

    private val _loaderPid = MutableStateFlow<Int?>(null)
    val loaderPid: StateFlow<Int?> = _loaderPid

    var runningOperationContainer by mutableStateOf<String?>(null)
        private set

    var containerLogs by mutableStateOf<Map<String, SnapshotStateList<Pair<Int, String>>>>(emptyMap())
        private set

    var showLogViewerFor by mutableStateOf<String?>(null)

    var navigateToEdit by mutableStateOf<String?>(null)

    private val _kernelVersion = MutableStateFlow("")
    val kernelVersion: StateFlow<String> = _kernelVersion

    private val _arch = MutableStateFlow("")
    val arch: StateFlow<String> = _arch

    private val _androidVersion = MutableStateFlow("")
    val androidVersion: StateFlow<String> = _androidVersion

    private val _rootProvider = MutableStateFlow("")
    val rootProvider: StateFlow<String> = _rootProvider

    private var initialized = false

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            if (!initialized) _isLoading.value = true
            try {
                _rootStatus.value = RootChecker.checkRootAccess()
                _rootProvider.value = RootChecker.getRootProvider()
                _termuxStatus.value = TermuxChecker.checkTermux()
                _x11ApkStatus.value = TermuxChecker.checkX11Apk()
                _dsStatus.value = DroidspacesChecker.checkBackend()

                _loaderStatus.value = X11SessionManager.getLoaderStatus()
                _loaderPid.value = if (_loaderStatus.value == LoaderStatus.Running) {
                    X11SessionManager.getLoaderPid()
                } else null

                _containers.value = ContainerManager.listContainers()

                _kernelVersion.value = getSystemProp("ro.build.version.release")
                _arch.value = getSystemProp("ro.product.cpu.abi")
                _androidVersion.value = getSystemProp("ro.build.version.sdk")
                initialized = true
            } catch (e: Exception) {
                Log.e("HomeViewModel", "refresh() failed", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startX11(container: ContainerInfo) {
        viewModelScope.launch {
            runningOperationContainer = container.name
            val logs = logsFor(container.name)
            logs.clear()
            showLogViewerFor = container.name
            val logger = ViewModelLogger { level, message -> logs.add(level to message) }

            try {
                X11SessionManager.startX11Session(
                    containerName = container.name,
                    enablePulseAudioFix = container.enablePulseAudio,
                    logger = logger
                )
                _loaderStatus.value = X11SessionManager.getLoaderStatus()
                _loaderPid.value = if (_loaderStatus.value == LoaderStatus.Running) {
                    X11SessionManager.getLoaderPid()
                } else null
            } catch (e: Exception) {
                Log.e("HomeViewModel", "startX11 failed", e)
                logger.e("Error: ${e.message}")
            } finally {
                delay(500)
                runningOperationContainer = null
                refresh()
            }
        }
    }

    fun stopContainer(container: ContainerInfo) {
        viewModelScope.launch {
            runningOperationContainer = container.name
            val logs = logsFor(container.name)
            logs.clear()
            showLogViewerFor = container.name
            val logger = ViewModelLogger { level, message -> logs.add(level to message) }

            try {
                ContainerManager.stopContainer(container.name, logger)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "stopContainer failed", e)
                logger.e("Error: ${e.message}")
            } finally {
                delay(500)
                runningOperationContainer = null
                refresh()
            }
        }
    }

    fun stopAll() {
        viewModelScope.launch {
            val logger = ViewModelLogger { _, _ -> }
            try {
                X11SessionManager.stopAll(logger)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "stopAll failed", e)
            } finally {
                _loaderStatus.value = LoaderStatus.Stopped
                _loaderPid.value = null
                refresh()
            }
        }
    }

    fun showLogs(container: ContainerInfo) {
        showLogViewerFor = container.name
        val logs = logsFor(container.name)
        if (logs.isEmpty()) {
            val status = if (container.isRunning) "Running" else "Stopped"
            val pidLine = if (container.pid != null) "  PID: ${container.pid}" else ""
            logs.add(Log.INFO to "Container: ${container.name}")
            logs.add(Log.INFO to "  Status: $status$pidLine")
            logs.add(Log.INFO to "  Rootfs: ${container.rootfsPath}")
            if (container.hostname.isNotEmpty()) {
                logs.add(Log.INFO to "  Hostname: ${container.hostname}")
            }
            logs.add(Log.INFO to "")
            logs.add(Log.INFO to "Start X11 session to see live logs.")
        }
    }

    fun dismissLogViewer() {
        showLogViewerFor = null
    }

    fun clearLogsBuffer(name: String) {
        containerLogs[name]?.clear()
        containerLogs = containerLogs.toMutableMap()
    }

    fun navigateToEditContainer(name: String) {
        navigateToEdit = name
    }

    fun onEditNavigated() {
        navigateToEdit = null
    }

    private fun logsFor(name: String): SnapshotStateList<Pair<Int, String>> {
        containerLogs[name]?.let { return it }
        val newLogs = mutableStateListOf<Pair<Int, String>>()
        containerLogs = containerLogs.toMutableMap().apply { put(name, newLogs) }
        return newLogs
    }

    private suspend fun getSystemProp(key: String): String = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("getprop $key 2>/dev/null").exec()
            result.out.firstOrNull()?.trim() ?: ""
        } catch (_: Exception) { "" }
    }
}
