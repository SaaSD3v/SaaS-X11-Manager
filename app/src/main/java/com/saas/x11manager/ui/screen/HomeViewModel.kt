package com.saas.x11manager.ui.screen

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
                    val termuxDef = async(Dispatchers.IO) { TermuxChecker.checkTermux() }
                    val x11Def = async(Dispatchers.IO) { TermuxChecker.checkX11Apk() }
                    val dsDef = async(Dispatchers.IO) { DroidspacesChecker.checkBackend() }
                    val containersDef = async(Dispatchers.IO) { ContainerManager.listContainers() }
                    val loaderDef = async(Dispatchers.IO) {
                        val status = X11SessionManager.getLoaderStatus()
                        val pid = if (status == LoaderStatus.Running) X11SessionManager.getLoaderPid() else null
                        status to pid
                    }
                    val systemDef = async(Dispatchers.IO) {
                        coroutineScope {
                            val kernel = async { getKernelVersion() }
                            val archVal = async { getSystemProp("ro.product.cpu.abi") }
                            val sdk = async { getSystemProp("ro.build.version.sdk") }
                            Triple(kernel.await(), archVal.await(), sdk.await())
                        }
                    }

                    FullRefreshSnapshot(
                        root = rootDef.await(),
                        termux = termuxDef.await(),
                        x11Apk = x11Def.await(),
                        droidspaces = dsDef.await(),
                        containers = containersDef.await(),
                        loader = loaderDef.await(),
                        system = systemDef.await()
                    )
                }

                if (generation != refreshGeneration) return@launch

                _rootStatus.value = snapshot.root.first
                _rootProvider.value = snapshot.root.second
                _termuxStatus.value = snapshot.termux
                _x11ApkStatus.value = snapshot.x11Apk
                _dsStatus.value = snapshot.droidspaces

                val (kernel, archVal, sdk) = snapshot.system
                _kernelVersion.value = kernel
                _arch.value = archVal
                _androidVersion.value = sdk

                if (
                    runtimeGenerationAtStart == runtimeStateGeneration &&
                    runningOperationContainer == null
                ) {
                    _containers.value = snapshot.containers
                    _loaderStatus.value = snapshot.loader.first
                    _loaderPid.value = snapshot.loader.second
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
                    val loaderDef = async(Dispatchers.IO) {
                        val status = X11SessionManager.getLoaderStatus()
                        val pid = if (status == LoaderStatus.Running) X11SessionManager.getLoaderPid() else null
                        status to pid
                    }
                    RuntimeRefreshSnapshot(
                        containers = containersDef.await(),
                        loader = loaderDef.await()
                    )
                }

                if (
                    generation != runtimeStateGeneration ||
                    runningOperationContainer != null
                ) return@launch

                _containers.value = snapshot.containers
                _loaderStatus.value = snapshot.loader.first
                _loaderPid.value = snapshot.loader.second
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
            val logger = ViewModelLogger { level, message -> logs.add(level to message) }

            try {
                X11SessionManager.startX11Session(
                    containerName = container.name,
                    logger = logger
                )

                val (running, pid) = ContainerManager.checkContainerStatusPublic(container.name)
                if (running) {
                    updateContainerState(container.name, ContainerStatus.RUNNING, pid)
                }

                _loaderStatus.value = X11SessionManager.getLoaderStatus()
                _loaderPid.value = if (_loaderStatus.value == LoaderStatus.Running) {
                    X11SessionManager.getLoaderPid()
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
            val logger = ViewModelLogger { level, message -> logs.add(level to message) }

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
                _loaderStatus.value = LoaderStatus.Stopped
                _loaderPid.value = null
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

    private suspend fun getKernelVersion(): String = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("uname -r 2>/dev/null").exec()
            result.out.firstOrNull()?.trim() ?: ""
        } catch (_: Exception) { "" }
    }

    private suspend fun getSystemProp(key: String): String = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("getprop $key 2>/dev/null").exec()
            result.out.firstOrNull()?.trim() ?: ""
        } catch (_: Exception) { "" }
    }

    private data class FullRefreshSnapshot(
        val root: Pair<RootStatus, String>,
        val termux: TermuxStatus,
        val x11Apk: X11ApkStatus,
        val droidspaces: Boolean,
        val containers: List<ContainerInfo>,
        val loader: Pair<LoaderStatus, Int?>,
        val system: Triple<String, String, String>
    )

    private data class RuntimeRefreshSnapshot(
        val containers: List<ContainerInfo>,
        val loader: Pair<LoaderStatus, Int?>
    )
}
