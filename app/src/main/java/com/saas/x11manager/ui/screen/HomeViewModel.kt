package com.saas.x11manager.ui.screen

import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saas.x11manager.util.*
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    private val _dsStatus = MutableStateFlow(false)
    val dsStatus: StateFlow<Boolean> = _dsStatus

    private val _dsRequirements = MutableStateFlow<DroidspacesRequirementsResult?>(null)
    val dsRequirements: StateFlow<DroidspacesRequirementsResult?> = _dsRequirements

    private val _x11ServerStatus = MutableStateFlow<X11ServerStatus>(X11ServerStatus.Stopped)
    val x11ServerStatus: StateFlow<X11ServerStatus> = _x11ServerStatus

    private val _x11ServerPid = MutableStateFlow<Int?>(null)
    val x11ServerPid: StateFlow<Int?> = _x11ServerPid

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

    private val _androidSdk = MutableStateFlow("")
    val androidSdk: StateFlow<String> = _androidSdk

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    private val _rootProvider = MutableStateFlow("")
    val rootProvider: StateFlow<String> = _rootProvider

    private var initialized = false
    private var refreshJob: Job? = null
    private var runtimeRefreshJob: Job? = null
    private var refreshGeneration = 0L
    private var runtimeStateGeneration = 0L

    init {
        refresh()
    }

    fun refresh() {
        val generation = ++refreshGeneration
        runtimeRefreshJob?.cancel()
        val runtimeGenerationAtStart = ++runtimeStateGeneration
        refreshJob?.cancel()

        refreshJob = viewModelScope.launch {
            if (!initialized) _isLoading.value = true

            try {
                val snapshot = coroutineScope {
                    val rootDef = async(Dispatchers.IO) {
                        val status = RootChecker.checkRootAccess()
                        val provider = RootChecker.getRootProvider()
                        status to provider
                    }
                    val dsDef = async(Dispatchers.IO) {
                        val available = DroidspacesChecker.checkBackend()
                        val requirements = if (available) {
                            DroidspacesChecker.checkRequirements()
                        } else {
                            null
                        }
                        available to requirements
                    }
                    val containersDef = async(Dispatchers.IO) { ContainerManager.listContainers() }
                    val serverDef = async(Dispatchers.IO) {
                        val status = X11SessionManager.getServerStatus()
                        val pid = if (status == X11ServerStatus.Running) X11SessionManager.getServerPid() else null
                        status to pid
                    }
                    val systemDef = async(Dispatchers.IO) { readDeviceSnapshot() }

                    FullRefreshSnapshot(
                        root = rootDef.await(),
                        droidspaces = dsDef.await(),
                        containers = containersDef.await(),
                        server = serverDef.await(),
                        system = systemDef.await()
                    )
                }

                if (generation != refreshGeneration) return@launch

                _rootStatus.value = snapshot.root.first
                _rootProvider.value = snapshot.root.second
                _dsStatus.value = snapshot.droidspaces.first
                _dsRequirements.value = snapshot.droidspaces.second

                _kernelVersion.value = snapshot.system.kernel
                _arch.value = snapshot.system.arch
                _androidVersion.value = snapshot.system.androidVersion
                _androidSdk.value = snapshot.system.androidSdk
                _deviceName.value = snapshot.system.deviceName

                if (
                    runtimeGenerationAtStart == runtimeStateGeneration &&
                    runningOperationContainer == null
                ) {
                    _containers.value = snapshot.containers
                    _x11ServerStatus.value = snapshot.server.first
                    _x11ServerPid.value = snapshot.server.second
                }

                initialized = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeViewModel", "refresh() failed", e)
            } finally {
                if (generation == refreshGeneration) {
                    _isLoading.value = false
                }
            }
        }
    }

    fun refreshRuntimeState() {
        if (!initialized || runningOperationContainer != null) return

        val generation = ++runtimeStateGeneration
        runtimeRefreshJob?.cancel()
        runtimeRefreshJob = viewModelScope.launch {
            try {
                val snapshot = coroutineScope {
                    val containersDef = async(Dispatchers.IO) { ContainerManager.listContainers() }
                    val serverDef = async(Dispatchers.IO) {
                        val status = X11SessionManager.getServerStatus()
                        val pid = if (status == X11ServerStatus.Running) X11SessionManager.getServerPid() else null
                        status to pid
                    }
                    RuntimeRefreshSnapshot(
                        containers = containersDef.await(),
                        server = serverDef.await()
                    )
                }

                if (
                    generation != runtimeStateGeneration ||
                    runningOperationContainer != null
                ) return@launch

                _containers.value = snapshot.containers
                _x11ServerStatus.value = snapshot.server.first
                _x11ServerPid.value = snapshot.server.second
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeViewModel", "refreshRuntimeState() failed", e)
            }
        }
    }

    private suspend fun refreshContainers() {
        val generation = ++runtimeStateGeneration
        runtimeRefreshJob?.cancel()

        try {
            val containers = ContainerManager.listContainers()
            if (generation == runtimeStateGeneration) {
                _containers.value = containers
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HomeViewModel", "refreshContainers() failed", e)
        }
    }

    private fun updateContainerState(name: String, status: ContainerStatus, pid: Int? = null) {
        _containers.value = _containers.value.map { container ->
            if (container.name == name) container.copy(status = status, pid = pid) else container
        }
    }

    private fun tryBeginOperation(containerName: String): Boolean {
        if (runningOperationContainer != null) return false

        runtimeStateGeneration++
        runtimeRefreshJob?.cancel()
        runningOperationContainer = containerName
        return true
    }

    fun startX11(container: ContainerInfo) {
        if (!tryBeginOperation(container.name)) return

        viewModelScope.launch {
            val logs = logsFor(container.name)
            logs.clear()
            showLogViewerFor = container.name
            val logger = ViewModelLogger { level, message -> appendLog(logs, level, message) }

            try {
                X11SessionManager.startX11Session(
                    containerName = container.name,
                    logger = logger
                )

                val (running, pid) = ContainerManager.checkContainerStatusPublic(container.name)
                if (running) {
                    updateContainerState(container.name, ContainerStatus.RUNNING, pid)
                }

                _x11ServerStatus.value = X11SessionManager.getServerStatus()
                _x11ServerPid.value = if (_x11ServerStatus.value == X11ServerStatus.Running) {
                    X11SessionManager.getServerPid()
                } else null
            } catch (e: Exception) {
                Log.e("HomeViewModel", "startX11 failed", e)
                logger.e("Error: ${e.message}")
            } finally {
                runningOperationContainer = null
                refreshContainers()
            }
        }
    }

    fun stopContainer(container: ContainerInfo) {
        if (!tryBeginOperation(container.name)) return

        viewModelScope.launch {
            val logs = logsFor(container.name)
            logs.clear()
            showLogViewerFor = container.name
            val logger = ViewModelLogger { level, message -> appendLog(logs, level, message) }

            try {
                val stopped = ContainerManager.stopContainer(container.name, logger)
                if (stopped) {
                    updateContainerState(container.name, ContainerStatus.STOPPED, null)
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "stopContainer failed", e)
                logger.e("Error: ${e.message}")
            } finally {
                runningOperationContainer = null
                refreshContainers()
            }
        }
    }

    fun stopAll() {
        if (!tryBeginOperation("__all__")) return

        viewModelScope.launch {
            val logger = ViewModelLogger { _, _ -> }
            try {
                X11SessionManager.stopAll(logger)
                _containers.value = _containers.value.map {
                    it.copy(status = ContainerStatus.STOPPED, pid = null)
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "stopAll failed", e)
            } finally {
                runningOperationContainer = null
                _x11ServerStatus.value = X11ServerStatus.Stopped
                _x11ServerPid.value = null
                refreshContainers()
            }
        }
    }

    fun showLogs(container: ContainerInfo) {
        showLogViewerFor = container.name
        val logs = logsFor(container.name)
        if (logs.isEmpty()) {
            val status = if (container.isRunning) "Running" else "Stopped"
            val pidLine = if (container.pid != null) "  PID: ${container.pid}" else ""
            appendLog(logs, Log.INFO, "Container: ${container.name}")
            appendLog(logs, Log.INFO, "  Status: $status$pidLine")
            appendLog(logs, Log.INFO, "  Rootfs: ${container.rootfsPath}")
            if (container.hostname.isNotEmpty()) {
                appendLog(logs, Log.INFO, "  Hostname: ${container.hostname}")
            }
            appendLog(logs, Log.INFO, "")
            appendLog(logs, Log.INFO, "Start X11 session to see live logs.")
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

    private fun appendLog(
        logs: SnapshotStateList<Pair<Int, String>>,
        level: Int,
        message: String
    ) {
        logs.add(level to message)
        if (logs.size > MAX_LOG_ENTRIES) {
            val removeCount = logs.size - LOG_ENTRIES_AFTER_TRIM
            repeat(removeCount) {
                logs.removeAt(0)
            }
        }
    }

    private suspend fun getKernelVersion(): String = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("uname -r 2>/dev/null").exec()
            result.out.firstOrNull()?.trim() ?: ""
        } catch (_: Exception) { "" }
    }

    private suspend fun readDeviceSnapshot(): DeviceSnapshot {
        val kernel = getKernelVersion()
        val manufacturer = Build.MANUFACTURER.trim()
        val model = Build.MODEL.trim()
        val deviceName = listOf(manufacturer, model)
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .joinToString(" ")
        return DeviceSnapshot(
            deviceName = deviceName,
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            androidSdk = Build.VERSION.SDK_INT.toString(),
            arch = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            kernel = kernel
        )
    }

    private data class DeviceSnapshot(
        val deviceName: String,
        val androidVersion: String,
        val androidSdk: String,
        val arch: String,
        val kernel: String
    )

    private data class FullRefreshSnapshot(
        val root: Pair<RootStatus, String>,
        val droidspaces: Pair<Boolean, DroidspacesRequirementsResult?>,
        val containers: List<ContainerInfo>,
        val server: Pair<X11ServerStatus, Int?>,
        val system: DeviceSnapshot
    )

    private data class RuntimeRefreshSnapshot(
        val containers: List<ContainerInfo>,
        val server: Pair<X11ServerStatus, Int?>
    )

    private companion object {
        const val MAX_LOG_ENTRIES = 2_000
        const val LOG_ENTRIES_AFTER_TRIM = 1_500
    }
}
