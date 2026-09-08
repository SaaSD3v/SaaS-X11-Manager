package com.saas.x11manager.ui.screen

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.saas.x11manager.X11Application
import com.saas.x11manager.operations.*
import com.saas.x11manager.util.*
import com.termux.x11.EmbeddedDisplayHost
import kotlinx.coroutines.launch

internal const val PREF_KNOWN_MONITOR_SLOTS = "saas_known_monitor_slots"

/** Operation and log state outlives the renderer screen and holds no Activity or UI callbacks. */
class ManagedDisplayViewModel : ViewModel() {
    private val app get() = X11Application.instance
    private val operationStore get() = app.operationLogs
    private val home get() = ViewModelProvider(app)[HomeViewModel::class.java]
    private val store = EmbeddedDisplayHost.getPrefs(X11Application.instance).get()
    var manualDisplayNumbers by mutableStateOf(readKnownMonitorSlots(store))
        private set
    var selectedDisplayNumber by mutableStateOf<Int?>(null)
    var busyDisplayNumber by mutableStateOf<Int?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var showMonitorLogs by mutableStateOf(false)
        private set
    private var logDisplayNumber by mutableStateOf<Int?>(null)
    val logOperation: LogOperation?
        get() = logDisplayNumber?.let { operationStore.get(OperationOwner(OperationArea.MONITOR, it.toString())) }
            ?: operationStore.records.values.filter { it.owner.area == OperationArea.MONITOR && it.available }
                .maxByOrNull { it.updatedAt }
    val selectedLogOperation: LogOperation?
        get() = selectedDisplayNumber?.let { operationStore.records[OperationOwner(OperationArea.MONITOR, it.toString()).key] }
            ?.takeIf { it.available } ?: logOperation

    fun persistManualDisplayNumbers(numbers: Set<Int>) {
        val sanitized = numbers.filter { it >= 0 }.toSortedSet()
        manualDisplayNumbers = sanitized
        store.edit().putStringSet(PREF_KNOWN_MONITOR_SLOTS, sanitized.map(Int::toString).toSet()).apply()
    }

    private fun beginOperation(monitor: X11MonitorInfo, title: String): LogOperation {
        logDisplayNumber = monitor.slot.number
        val operation = operationStore.get(OperationOwner(OperationArea.MONITOR, monitor.slot.number.toString()))
        operation.begin(title)
        busyDisplayNumber = monitor.slot.number
        message = null
        showMonitorLogs = true
        return operation
    }

    fun openLogs(number: Int? = null) {
        logDisplayNumber = number ?: selectedLogOperation?.owner?.target?.toIntOrNull()
        showMonitorLogs = logOperation != null
    }

    fun dismissLogs() { if (logOperation?.running != true) showMonitorLogs = false }
    fun minimizeLogs() {
        logOperation?.let { if (operationStore.minimize(it)) showMonitorLogs = false }
    }
    fun clearLogs() {
        logOperation?.takeUnless { it.running }?.let {
            it.logs.clear()
            it.changed(immediate = true)
        }
    }

    fun deleteMonitor(monitor: X11MonitorInfo) {
        if (!canBeginOperation()) return

        selectedDisplayNumber = monitor.slot.number
        val operation = beginOperation(monitor, "Deleting ${monitor.slot.describe()}")
        val logger = ViewModelLogger(operation::append)
        viewModelScope.launch {
            try {
                logger.i("--- Deleting X11 monitor ---")
                logger.i("[*] Monitor: ${monitor.monitorNumber}")
                logger.i("[*] Display: ${monitor.displayName}")
                logger.i("[*] Runtime: ${monitor.slot.runtimeDir}")
                logger.i("[*] Socket: ${monitor.slot.socketFile}")
                logger.i("")

                if (monitor.containerName != null) {
                    message = "${monitor.slot.describe()} is reserved by ${monitor.containerName}"
                    logger.w("[!] Monitor is reserved by running container '${monitor.containerName}'")
                    logger.w("[!] Stop the container or stop its monitor before deleting the slot")
                    return@launch
                }
                if (monitor.status == X11ServerStatus.Running) {
                    message = "${monitor.slot.describe()} is still running"
                    logger.w("[!] Stop the monitor before deleting it")
                    return@launch
                }

                // A delete is an authoritative release, not just a UI preference
                // change. Reuse the verified server-stop cleanup so stale sockets,
                // lock files and the complete unowned runtime directory disappear.
                val released = X11SessionManager.stopIntegratedServer(monitor.slot, logger)
                if (!released) {
                    message = "${monitor.slot.describe()} could not be fully deleted"
                    logger.e("[-] Monitor delete failed because runtime cleanup was not confirmed")
                    return@launch
                }

                persistManualDisplayNumbers(manualDisplayNumbers - monitor.slot.number)
                if (selectedDisplayNumber == monitor.slot.number) {
                    selectedDisplayNumber = home.monitors.value
                        .asSequence()
                        .map { it.slot.number }
                        .filter { it != monitor.slot.number }
                        .minOrNull()
                }
                logger.i("[+] ${monitor.slot.describe()} runtime fully released")
                logger.i("[+] Monitor removed from the monitor list")
                home.refreshRuntimeState()
            } catch (error: Exception) {
                message = error.message ?: "Monitor operation failed"
                logger.e("[-] $message")
            } finally {
                logger.flush()
                operation.finish(message == null, message ?: "${monitor.slot.describe()}: operation completed")
                busyDisplayNumber = null
            }
        }
    }

    fun toggleMonitor(monitor: X11MonitorInfo, seedContainer: String?) {
        if (!canBeginOperation()) return
        selectedDisplayNumber = monitor.slot.number
        val title = if (monitor.status == X11ServerStatus.Running) {
            "Stopping ${monitor.slot.describe()}"
        } else {
            "Starting ${monitor.slot.describe()}"
        }
        val operation = beginOperation(monitor, title)
        val logger = ViewModelLogger(operation::append)
        viewModelScope.launch {
            try {
                if (monitor.status == X11ServerStatus.Running) {
                    logger.i("--- Stopping X11 monitor ---")
                    logger.i("[*] Monitor: ${monitor.monitorNumber}")
                    logger.i("[*] Display: ${monitor.displayName}")
                    monitor.pid?.let { logger.i("[*] PID: $it") }
                    monitor.containerName?.let { logger.i("[*] Container remains running: $it") }
                    logger.i("")

                    if (monitor.containerName != null) {
                        val sessionStopped = X11SessionManager.stopContainerGraphicSession(
                            monitor.containerName,
                            logger
                        )
                        if (!sessionStopped) {
                            logger.w("[!] Continuing with X11 server stop; container is still running")
                        }
                    }

                    // A monitor action owns only the Manager X11 server. Stopping
                    // the card must never stop the associated DroidSpaces container.
                    val stopped = X11SessionManager.stopIntegratedServer(monitor.slot, logger)

                    if (!stopped) {
                        message = "${monitor.slot.describe()} could not be stopped"
                        logger.e("[-] ${monitor.slot.describe()} stop failed")
                    } else {
                        if (monitor.containerName != null) {
                            // The running container itself keeps this slot visible.
                            // No manual/persistent monitor placeholder is needed.
                            persistManualDisplayNumbers(manualDisplayNumbers - monitor.slot.number)
                            logger.i("[+] Container '${monitor.containerName}' was left running")
                            logger.i("[+] Empty monitor bind anchor retained for the running container")
                        } else {
                            // Raw/unowned Stop is a complete release: no process,
                            // socket, runtime directory or UI placeholder survives.
                            persistManualDisplayNumbers(manualDisplayNumbers - monitor.slot.number)
                            logger.i("[+] ${monitor.slot.describe()} fully released")
                            logger.i("[+] Monitor removed from the monitor list")
                        }
                    }
                } else {
                    logger.i("--- Starting X11 monitor ---")
                    logger.i("[*] Monitor: ${monitor.monitorNumber}")
                    logger.i("[*] Display: ${monitor.displayName}")
                    logger.i("[*] Process: ${monitor.slot.processName}")
                    logger.i("[*] Runtime: ${monitor.slot.runtimeDir}")
                    logger.i("[*] Socket: ${monitor.slot.socketFile}")
                    logger.i("")

                    val seed = monitor.containerName ?: seedContainer
                    val started = X11SessionManager.startIntegratedServer(
                        displaySlot = monitor.slot,
                        containerName = seed,
                        logger = logger
                    )
                    if (started.isSuccess) {
                        val graphicSessionReady = monitor.containerName?.let { containerName ->
                            X11SessionManager.ensureContainerGraphicSession(
                                containerName = containerName,
                                displaySlot = monitor.slot,
                                logger = logger
                            )
                        } ?: true

                        if (monitor.containerName == null) {
                            persistManualDisplayNumbers(manualDisplayNumbers + monitor.slot.number)
                        } else {
                            persistManualDisplayNumbers(manualDisplayNumbers - monitor.slot.number)
                        }
                        logger.i("")
                        logger.i("[+] ${monitor.slot.describe()} is ready")
                        logger.i("[+] PID: ${started.getOrNull()}")
                        if (!graphicSessionReady) {
                            message =
                                "${monitor.slot.describe()} is running, but its graphic session did not start"
                        }
                    } else {
                        message = started.exceptionOrNull()?.message
                            ?: "${monitor.slot.describe()} could not start"
                        logger.e("[-] ${message ?: "X11 start failed"}")
                    }
                }
            } catch (error: Exception) {
                message = error.message ?: "Monitor operation failed"
                logger.e("[-] $message")
            } finally {
                logger.flush()
                operation.finish(message == null, message ?: "${monitor.slot.describe()}: operation completed")
                // Publish one shared runtime snapshot. The Screen no longer starts
                // a second X11 discovery pass of its own.
                home.refreshRuntimeState()
                busyDisplayNumber = null
            }
        }
    }

    private fun canBeginOperation(): Boolean {
        if (busyDisplayNumber != null) return false
        if (operationStore.records.values.any { it.running }) {
            message = "Another container operation is still running. Its logs remain available."
            return false
        }
        return true
    }
}
