package com.saas.x11manager.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saas.x11manager.util.*
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    private val _kernelVersion = MutableStateFlow("")
    val kernelVersion: StateFlow<String> = _kernelVersion

    private val _arch = MutableStateFlow("")
    val arch: StateFlow<String> = _arch

    private val _androidVersion = MutableStateFlow("")
    val androidVersion: StateFlow<String> = _androidVersion

    private val _rootProvider = MutableStateFlow("")
    val rootProvider: StateFlow<String> = _rootProvider

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true

            _rootStatus.value = RootChecker.checkRootAccess()
            _rootProvider.value = RootChecker.getRootProvider()
            _termuxStatus.value = TermuxChecker.checkTermux()
            _x11ApkStatus.value = TermuxChecker.checkX11Apk()
            _dsStatus.value = DroidspacesChecker.checkBackend()

            // Update loader status and PID
            _loaderStatus.value = X11SessionManager.getLoaderStatus()
            _loaderPid.value = if (_loaderStatus.value == LoaderStatus.Running) {
                X11SessionManager.getLoaderPid()
            } else {
                null
            }

            _containers.value = ContainerManager.listContainers()

            _kernelVersion.value = getSystemProp("ro.build.version.release")
            _arch.value = getSystemProp("ro.product.cpu.abi")
            _androidVersion.value = getSystemProp("ro.build.version.sdk")

            _isLoading.value = false
        }
    }

    fun startX11(container: ContainerInfo) {
        viewModelScope.launch {
            runningOperationContainer = container.name
            val logs = logsFor(container.name)
            logs.clear()
            showLogViewerFor = container.name
            val logger = ViewModelLogger { level, message -> logs.add(level to message) }

            X11SessionManager.startX11Session(container.name, logger)

            // Update loader status after starting
            _loaderStatus.value = X11SessionManager.getLoaderStatus()
            _loaderPid.value = if (_loaderStatus.value == LoaderStatus.Running) {
                X11SessionManager.getLoaderPid()
            } else {
                null
            }

            delay(500)
            runningOperationContainer = null
            refresh()
        }
    }

    fun stopContainer(container: ContainerInfo) {
        viewModelScope.launch {
            runningOperationContainer = container.name
            val logs = logsFor(container.name)
            logs.clear()
            showLogViewerFor = container.name
            val logger = ViewModelLogger { level, message -> logs.add(level to message) }

            ContainerManager.stopContainer(container.name, logger)

            delay(500)
            runningOperationContainer = null
            refresh()
        }
    }

    fun stopAll() {
        viewModelScope.launch {
            val logger = ViewModelLogger { _, _ -> }
            X11SessionManager.stopAll(logger)

            // Update loader status after stopping
            _loaderStatus.value = LoaderStatus.Stopped
            _loaderPid.value = null

            refresh()
        }
    }

    fun showLogs(container: ContainerInfo) {
        showLogViewerFor = container.name
    }

    fun dismissLogViewer() {
        showLogViewerFor = null
        refresh()
    }

    fun clearLogsBuffer(name: String) {
        containerLogs[name]?.clear()
        containerLogs = containerLogs.toMutableMap()
    }

    private fun logsFor(name: String): SnapshotStateList<Pair<Int, String>> {
        containerLogs[name]?.let { return it }
        val newLogs = mutableStateListOf<Pair<Int, String>>()
        containerLogs = containerLogs.toMutableMap().apply { put(name, newLogs) }
        return newLogs
    }

    private fun getSystemProp(key: String): String {
        return try {
            val result = Shell.cmd("getprop $key 2>/dev/null").exec()
            result.out.firstOrNull()?.trim() ?: ""
        } catch (_: Exception) { "" }
    }
}
