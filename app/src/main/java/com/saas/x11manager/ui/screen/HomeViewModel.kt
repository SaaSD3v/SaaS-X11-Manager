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
    private var diagnosticsJob: Job? = null
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
        diagnosticsJob?.cancel()

        refreshJob = viewModelScope.launch {
            if (!initialized) _isLoading.value = true

            try {
                val operational = coroutineScope {
                    val rootDef = async(Dispatchers.IO) {
                        RootChecker.checkRootAccess()
                    }
                    val dsDef = async(Dispatchers.IO) {
                        DroidspacesChecker.checkBackend()
                    }
                    val runtimeDef = async(Dispatchers.IO) {
                        readRuntimeSnapshot()
                    }

                    OperationalRefreshSnapshot(
                        rootStatus = rootDef.await(),
                        droidspacesAvailable = dsDef.await(),
                        runtime = runtimeDef.await()
                    )
                }

                if (generation != refreshGeneration) return@launch

                _rootStatus.value = operational.rootStatus
                if (operational.rootStatus != RootStatus.Granted) {
                    _rootProvider.value = ""
                }

                _dsStatus.value = operational.droidspacesAvailable
                if (!operational.droidspacesAvailable) {
                    _dsRequirements.value = null
                }

                if (
                    runtimeGenerationAtStart == runtimeStateGeneration &&
                    runningOperationContainer == null
                ) {
                    applyRuntimeSnapshot(operational.runtime)
                }

                initialized = true
                _isLoading.value = false
                refreshDiagnostics(
                    generation = generation,
                    rootStatus = operational.rootStatus,
                    droidspacesAvailable = operational.droidspacesAvailable
                )
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

    private fun refreshDiagnostics(
        generation: Long,
        rootStatus: RootStatus,
        droidspacesAvailable: Boolean
    ) {
        diagnosticsJob?.cancel()
        diagnosticsJob = viewModelScope.launch {
            try {
                val diagnostics = coroutineScope {
                    val providerDef = async(Dispatchers.IO) {
                        if (rootStatus == RootStatus.Granted) {
                            RootChecker.getRootProvider()
                        } else {
                            ""
                        }
                    }
                    val requirementsDef = async(Dispatchers.IO) {
                        if (droidspacesAvailable) {
                            DroidspacesChecker.checkRequirements()
                        } else {
                            null
                        }
                    }
                    val systemDef = async(Dispatchers.IO) {
                        readDeviceSnapshot()
                    }

                    DiagnosticSnapshot(
                        rootProvider = providerDef.await(),
                        droidspacesRequirements = requirementsDef.await(),
                        system = systemDef.await()
                    )
                }

                if (generation != refreshGeneration) return@launch

                _rootProvider.value = diagnostics.rootProvider
                _dsRequirements.value = diagnostics.droidspacesRequirements
                _kernelVersion.value = diagnostics.system.kernel
                _arch.value = diagnostics.system.arch
                _androidVersion.value = diagnostics.system.androidVersion
                _androidSdk.value = diagnostics.system.androidSdk
                _deviceName.value = diagnostics.system.deviceName
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeViewModel", "refreshDiagnostics() failed", e)
            }
        }
    }

    fun refreshRuntimeState() {
        if (!initialized || runningOperationContainer != null) return

        val generation = ++runtimeStateGeneration
        runtimeRefreshJob?.cancel()
        runtimeRefreshJob = viewModelScope.launch {
            try {
                val snapshot = readRuntimeSnapshot()

                if (
                    generation != runtimeStateGeneration ||
                    runningOperationContainer != null
                ) return@launch

                applyRuntimeSnapshot(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeViewModel", "refreshRuntimeState() failed", e)
            }
        }
    }

    private suspend fun refreshRuntimeAfterOperation() {
        val generation = ++runtimeStateGeneration
        runtimeRefreshJob?.cancel()

        try {
            val snapshot = readRuntimeSnapshot()
            if (generation == runtimeStateGeneration) {
                applyRuntimeSnapshot(snapshot)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HomeViewModel", "refreshRuntimeAfterOperation() failed", e)
        }
    }

    private fun applyRuntimeSnapshot(snapshot: RuntimeRefreshSnapshot) {
        _containers.value = snapshot.containers
        _x11ServerStatus.value = snapshot.x11Status
        _x11ServerPid.value = snapshot.x11Pid
    }

    private suspend fun readRuntimeSnapshot(): RuntimeRefreshSnapshot = withContext(Dispatchers.IO) {
        val containers = ContainerManager.listContainers()
        RuntimeRefreshSnapshot(
            containers = containers,
            x11Status = X11SessionManager.getServerStatus(),
            x11Pid = X11SessionManager.getServerPid()
        )
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

    fun startSession(
        container: ContainerInfo,
        accessMode: SessionAccessMode,
        vncPort: Int
    ) {
        if (!tryBeginOperation(container.name)) return

        viewModelScope.launch {
            val logs = logsFor(container.name)
            logs.clear()
            showLogViewerFor = container.name
            val logger = ViewModelLogger { level, message -> appendLog(logs, level, message) }

            try {
                val profile = withContext(Dispatchers.IO) {
                    ContainerSettingsManager.readSnapshot(
                        containerName = container.name,
                        forceRefresh = true
                    )
                }
                val session = profile.graphicSession
                if (session == null || session == GraphicSession.NONE) {
                    logger.e("[-] No configured graphic session found for ${container.name}")
                    logger.e("[-] Open Edit container and configure a graphic session first")
                    return@launch
                }

                logger.i("[CTX] Saved access method: ${accessMode.label}")
                if (accessMode.requiresVnc) {
                    logger.i("[CTX] Saved VNC port: $vncPort")
                }

                val started = SessionAccessManager.start(
                    containerName = container.name,
                    platform = profile.platform,
                    session = session,
                    accessMode = accessMode,
                    vncPort = vncPort,
                    vncPassword = null,
                    logger = logger
                )

                val (running, pid) = ContainerManager.checkContainerStatusPublic(container.name)
                if (running) {
                    updateContainerState(container.name, ContainerStatus.RUNNING, pid)
                }
                if (!started) {
                    logger.e("[-] ${accessMode.label} start was not fully confirmed")
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "startSession failed", e)
                logger.e("Error: ${e.message}")
            } finally {
                runningOperationContainer = null
                refreshRuntimeAfterOperation()
            }
        }
    }

    fun startX11(container: ContainerInfo) =
        startSession(
            container = container,
            accessMode = SessionAccessMode.INTEGRATED_X11,
            vncPort = VncSettings.DEFAULT_PORT
        )

    fun stopContainer(container: ContainerInfo) {
        if (!tryBeginOperation(container.name)) return

        viewModelScope.launch {
            val logs = logsFor(container.name)
            logs.clear()
            showLogViewerFor = container.name
            val logger = ViewModelLogger { level, message -> appendLog(logs, level, message) }

            try {
                VncServerManager.stopManagedVnc(container.name, logger)
                val stopped = X11SessionManager.stopX11Session(container.name, logger)
                if (stopped) {
                    updateContainerState(container.name, ContainerStatus.STOPPED, null)
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "stopContainer failed", e)
                logger.e("Error: ${e.message}")
            } finally {
                runningOperationContainer = null
                refreshRuntimeAfterOperation()
            }
        }
    }

    fun stopAll() {
        if (!tryBeginOperation("__all__")) return

        viewModelScope.launch {
            val logger = ViewModelLogger { _, _ -> }
            try {
                val currentContainers = ContainerManager.listContainers()
                currentContainers.filter { it.isRunning }.forEach { container ->
                    VncServerManager.stopManagedVnc(container.name, logger)
                }
                X11SessionManager.stopAll(logger)
                _containers.value = _containers.value.map {
                    it.copy(status = ContainerStatus.STOPPED, pid = null)
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "stopAll failed", e)
            } finally {
                runningOperationContainer = null
                refreshRuntimeAfterOperation()
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
            appendLog(logs, Log.INFO, "Start the configured graphic session to see live logs.")
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
            val retained = logs.takeLast(LOG_ENTRIES_AFTER_TRIM)
            logs.clear()
            logs.addAll(retained)
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

    private data class OperationalRefreshSnapshot(
        val rootStatus: RootStatus,
        val droidspacesAvailable: Boolean,
        val runtime: RuntimeRefreshSnapshot
    )

    private data class DiagnosticSnapshot(
        val rootProvider: String,
        val droidspacesRequirements: DroidspacesRequirementsResult?,
        val system: DeviceSnapshot
    )

    private data class RuntimeRefreshSnapshot(
        val containers: List<ContainerInfo>,
        val x11Status: X11ServerStatus,
        val x11Pid: Int?
    )

    private companion object {
        const val MAX_LOG_ENTRIES = 2_000
        const val LOG_ENTRIES_AFTER_TRIM = 1_500
    }
}
