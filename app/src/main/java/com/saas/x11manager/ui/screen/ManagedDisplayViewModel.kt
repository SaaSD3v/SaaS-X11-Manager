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
    val logOperation get() = operationStore.get(OperationOwner(OperationArea.MONITOR, "0"))
    val busy get() = logOperation.running
    var message by mutableStateOf<String?>(null)
        private set
    var showMonitorLogs by mutableStateOf(false)
        private set

    fun openLogs() { showMonitorLogs = true }
    fun dismissLogs() { if (!busy) showMonitorLogs = false }
    fun minimizeLogs() {
        if (operationStore.minimize(logOperation)) showMonitorLogs = false
    }
    fun clearLogs() {
        if (!busy) {
            logOperation.logs.clear()
            logOperation.changed(immediate = true)
        }
    }

    fun toggleServer(seedContainer: String?) {
        if (home.hasRunningOperations) {
            message = "Another operation is still running. Its logs remain available."
            return
        }
        // Set RUNNING before launching so another screen cannot start a competing action.
        val operation = logOperation
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
                operation.finish(succeeded, message ?: result)
                home.refreshRuntimeState()
            }
        }
    }
}
