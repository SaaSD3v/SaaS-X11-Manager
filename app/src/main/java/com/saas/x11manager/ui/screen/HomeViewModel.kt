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
import com.saas.x11manager.X11Application
import com.saas.x11manager.util.*
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HomeViewModel : ViewModel() {
    private val _containers = MutableStateFlow<List<ContainerInfo>>(emptyList())
    val containers: StateFlow<List<ContainerInfo>> = _containers

    private val _monitors = MutableStateFlow<List<X11MonitorInfo>>(emptyList())
    val monitors: StateFlow<List<X11MonitorInfo>> = _monitors

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
    private val runtimeSnapshotMutex = Mutex()

    init {
        refresh()
    }

    /**
     * Full refresh is intentionally serialized. libsu exposes one shared privileged
     * shell and blocking Shell.exec() calls are not cancelled when a coroutine Job
     * is cancelled. The old async/cancel/relaunch strategy could therefore leave
     * several real shell tasks queued behind one another while Compose believed the
     * previous refresh had been cancelled.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return

        val generation = ++refreshGeneration
        val runtimeGenerationAtStart = runtimeStateGeneration

        refreshJob = viewModelScope.launch {
            if (!initialized) _isLoading.value = true

            try {
                val rootStatus = withContext(Dispatchers.IO) {
                    RootChecker.checkRootAccess()
                }
                val droidspacesAvailable = if (rootStatus == RootStatus.Granted) {
                    withContext(Dispatchers.IO) { DroidspacesChecker.checkBackend() }
                } else {
                    false
                }
                val runtime = if (rootStatus == RootStatus.Granted && droidspacesAvailable) {
                    readRuntimeSnapshot()
                } else {
                    RuntimeRefreshSnapshot(emptyList(), emptyList())
                }

                if (generation != refreshGeneration) return@launch

                _rootStatus.value = rootStatus
                if (rootStatus != RootStatus.Granted) {
                    _rootProvider.value = ""
                }

                _dsStatus.value = droidspacesAvailable
                if (!droidspacesAvailable) {
                    _dsRequirements.value = null
                }

                if (
                    runtimeGenerationAtStart == runtimeStateGeneration &&
                    runningOperationContainer == null
                ) {
                    applyRuntimeSnapshot(runtime)
                }

                initialized = true
                _isLoading.value = false
                refreshDiagnostics(
                    generation = generation,
                    rootStatus = rootStatus,
                    droidspacesAvailable = droidspacesAvailable
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

    /**
     * Diagnostics are low-value for interactive runtime control, so they run as
     * one sequential background chain rather than competing async Shell tasks.
     */
    private fun refreshDiagnostics(
        generation: Long,
        rootStatus: RootStatus,
        droidspacesAvailable: Boolean
    ) {
        if (diagnosticsJob?.isActive == true) return

        diagnosticsJob = viewModelScope.launch {
            try {
                val rootProvider = if (rootStatus == RootStatus.Granted) {
                    withContext(Dispatchers.IO) { RootChecker.getRootProvider() }
                } else {
                    ""
                }
                val droidspacesRequirements = if (droidspacesAvailable) {
                    withContext(Dispatchers.IO) { DroidspacesChecker.checkRequirements() }
                } else {
                    null
                }
                val system = readDeviceSnapshot()

                if (generation != refreshGeneration) return@launch

                _rootProvider.value = rootProvider
                _dsRequirements.value = droidspacesRequirements
                _kernelVersion.value = system.kernel
                _arch.value = system.arch
                _androidVersion.value = system.androidVersion
                _androidSdk.value = system.androidSdk
                _deviceName.value = system.deviceName
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeViewModel", "refreshDiagnostics() failed", e)
            }
        }
    }

    /**
     * Coalesce runtime refresh requests instead of cancelling a blocking libsu
     * command and immediately queueing another one behind it.
     */
    fun refreshRuntimeState() {
        if (!initialized || runningOperationContainer != null) return
        if (runtimeRefreshJob?.isActive == true) return

        val generation = ++runtimeStateGeneration
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
        // A pre-operation refresh may still own the shared shell. Let that one
        // finish instead of cancelling it (Shell.exec itself is not cancellable),
        // then publish one authoritative post-operation snapshot.
        runtimeRefreshJob?.join()
        val generation = ++runtimeStateGeneration

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
        _monitors.value = snapshot.monitors
        val running = snapshot.monitors.firstOrNull { it.status == X11ServerStatus.Running }
        _x11ServerStatus.value = if (running != null) X11ServerStatus.Running else X11ServerStatus.Stopped
        _x11ServerPid.value = running?.pid
    }

    private suspend fun readRuntimeSnapshot(): RuntimeRefreshSnapshot = runtimeSnapshotMutex.withLock {
        withContext(Dispatchers.IO) {
            val containers = ContainerManager.listContainers()
            val monitors = X11SessionManager.getMonitors(containers)
            RuntimeRefreshSnapshot(containers = containers, monitors = monitors)
        }
    }

    private fun updateContainerState(name: String, status: ContainerStatus, pid: Int? = null) {
        _containers.value = _containers.value.map { container ->
            if (container.name == name) container.copy(status = status, pid = pid) else container
        }
    }

    private fun tryBeginOperation(containerName: String): Boolean {
        if (runningOperationContainer != null) return false

        // Invalidate any snapshot that started before the user operation. Do not
        // cancel its blocking shell work: cancellation was the source of queued
        // zombie refresh tasks on real devices.
        runtimeStateGeneration++
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
                // Never start a user operation behind a cancelled-but-still-running
                // refresh. With the new fast snapshot this wait is normally tiny.
                runtimeRefreshJob?.join()

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

    /** Backward-compatible direct X11 entry point for existing callers/tests. */
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
                runtimeRefreshJob?.join()

                // Do not touch or mention VNC for an X11-only container. The old
                // unconditional cleanup both emitted a false VNC success line and
                // paid the VNC stop delay even when VNC had never been selected.
                val accessMode = VncSettings.getAccessMode(X11Application.instance, container.name)
                if (accessMode.requiresVnc) {
                    VncServerManager.stopManagedVnc(container.name, logger)
                }

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
                runtimeRefreshJob?.join()

                val currentContainers = ContainerManager.listContainers()
                currentContainers.filter { it.isRunning }.forEach { container ->
                    val accessMode = VncSettings.getAccessMode(X11Application.instance, container.name)
                    if (accessMode.requiresVnc) {
                        VncServerManager.stopManagedVnc(container.name, logger)
                    }
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

    private data class RuntimeRefreshSnapshot(
        val containers: List<ContainerInfo>,
        val monitors: List<X11MonitorInfo>
    )

    private companion object {
        const val MAX_LOG_ENTRIES = 2_000
        const val LOG_ENTRIES_AFTER_TRIM = 1_500
    }
}
