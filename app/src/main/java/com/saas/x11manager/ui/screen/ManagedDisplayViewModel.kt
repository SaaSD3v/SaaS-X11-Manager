package com.saas.x11manager.ui.screen

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.saas.x11manager.X11Application
import com.saas.x11manager.operations.*
import com.saas.x11manager.util.*
import kotlinx.coroutines.launch

/** The fixed X0 operation survives navigation; the renderer holds only viewport state. */
class ManagedDisplayViewModel : ViewModel() {
    private val app get() = X11Application.instance
    private val operationStore get() = app.operationLogs
    private val home get() = ViewModelProvider(app)[HomeViewModel::class.java]
    private val monitorOwner = OperationOwner(OperationArea.MONITOR, "0")
    private val monitorOperation get() = operationStore.get(monitorOwner)

    private var ownerForLogs by mutableStateOf<String?>(null)
    val busy get() = monitorOperation.running || relevantLogOperation()?.running == true
    var message by mutableStateOf<String?>(null)
        private set
    var showMonitorLogs by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            operationStore.awaitLoaded()
            ownerForLogs = runCatching { X11SessionManager.getOwnerContainerName() }.getOrNull()
        }
    }

    /**
     * X0 may have been manipulated directly from Monitor or indirectly by a Home
     * Start. Select only MONITOR:0 and the current owner's HOME lifecycle; never a
     * random container/monitor log. When both exist, the newest relevant one wins.
     */
    private fun relevantLogOperation(): LogOperation? {
        operationStore.loadedState // Compose observation for asynchronously restored archives.
        val owner = MonitorLogSelectionPolicy.select(
            displayTarget = "0",
            containerName = ownerForLogs,
            candidates = operationStore.records.values.map {
                MonitorLogCandidate(it.owner, it.available, it.updatedAt)
            }
        )
        return owner?.let(operationStore::findAvailable)
    }

    // Kept non-null because the existing X0 Screen passes this object to the
    // transient running card. The fallback stays invisible/unopenable while empty.
    val logOperation: LogOperation
        get() = relevantLogOperation() ?: monitorOperation

    fun openLogs() {
        viewModelScope.launch {
            operationStore.awaitLoaded()
            ownerForLogs = runCatching { X11SessionManager.getOwnerContainerName() }.getOrNull()
            val operation = relevantLogOperation()
            if (operation != null) {
                message = null
                showMonitorLogs = true
            } else {
                showMonitorLogs = false
                message = "No lifecycle logs recorded for Monitor 1 yet"
            }
        }
    }

    fun dismissLogs() { if (!busy) showMonitorLogs = false }
    fun minimizeLogs() {
        val operation = relevantLogOperation() ?: return
        if (operationStore.minimize(operation)) showMonitorLogs = false
    }
    fun clearLogs() {
        viewModelScope.launch {
            operationStore.awaitLoaded()
            val operation = relevantLogOperation() ?: return@launch
            if (operation.running) return@launch
            OperationNotifications.dismiss(app, operation)
            operation.logs.clear()
            operation.changed(immediate = true)
            operationStore.awaitPersisted()
            showMonitorLogs = false
        }
    }

    fun toggleServer(seedContainer: String?) {
        if (home.hasRunningOperations) {
            message = "Another operation is still running. Its logs remain available."
            return
        }
        // Set RUNNING before launching so another screen cannot start a competing action.
        val operation = monitorOperation
        operation.begin("Updating X11 display")
        message = null
        showMonitorLogs = true
        val logger = ViewModelLogger(operation::append)
        viewModelScope.launch {
            var succeeded = false
            var result = "X11 operation was not confirmed — view logs"
            try {
                // Resolve the live state after the click, rather than acting on an old UI snapshot.
                val running = X11SessionManager.getServerStatus() == X11ServerStatus.Running
                val owner = X11SessionManager.getOwnerContainerName()
                ownerForLogs = owner
                logger.i(if (running) "--- Stopping X11 monitor ---" else "--- Starting X11 monitor ---")
                logger.i("[*] Monitor: 1")
                logger.i("[*] Display: ${Constants.X11_DISPLAY}")
                owner?.let { logger.i("[*] Container: $it") }
                if (running) {
                    if (owner != null && !X11SessionManager.stopContainerGraphicSession(owner, logger)) {
                        logger.w("[!] Continuing with X11 server stop; container is still running")
                    }
                    succeeded = X11SessionManager.stopIntegratedServer(logger)
                    if (succeeded) {
                        result = "X11 display stopped"
                        owner?.let { logger.i("[+] Container '$it' was left running") }
                    }
                } else {
                    val started = X11SessionManager.startIntegratedServer(owner ?: seedContainer, logger)
                    if (started.isFailure) {
                        message = started.exceptionOrNull()?.message ?: "X11 display could not start"
                    } else {
                        // Restarting the fixed server must also restore its owner's managed desktop.
                        // A raw server without an owner remains available for manual X11 clients.
                        succeeded = owner?.let {
                            X11SessionManager.ensureContainerGraphicSession(it, logger)
                        } ?: true
                        result = "X11 display ready"
                        if (!succeeded) message = "X11 is running, but its desktop could not be confirmed"
                    }
                }
                if (!succeeded && message == null) message = result
                message?.let { logger.e("[-] $it") }
            } catch (error: Exception) {
                message = error.message ?: "X11 operation failed"
                logger.e("[-] $message")
            } finally {
                logger.flush()
                operation.finishDurably(succeeded, message ?: result)
                home.refreshRuntimeState()
                ownerForLogs = runCatching { X11SessionManager.getOwnerContainerName() }.getOrNull()
            }
        }
    }
}
