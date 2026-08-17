package com.saas.x11manager.ui.screen

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saas.x11manager.util.AdditionalGraphicSessionInstaller
import com.saas.x11manager.util.ContainerManager
import com.saas.x11manager.util.ContainerSettingsManager
import com.saas.x11manager.util.ContainerStatus
import com.saas.x11manager.util.GraphicSession
import com.saas.x11manager.util.GraphicSessionInstaller
import com.saas.x11manager.util.GraphicSessionSupport
import com.saas.x11manager.util.InitSystem
import com.saas.x11manager.util.ViewModelLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditContainerViewModel : ViewModel() {

    var name by mutableStateOf("")
    var hostname by mutableStateOf("")
    var status by mutableStateOf(ContainerStatus.UNKNOWN)
    var initSystem by mutableStateOf(InitSystem.SYSTEMD)
        private set
    var graphicSession by mutableStateOf(GraphicSession.XFCE)
        private set

    private val installedSessions = mutableStateMapOf<GraphicSession, Boolean>()

    val openboxInstalled: Boolean
        get() = isSessionInstalled(GraphicSession.OPENBOX)
    val icewmInstalled: Boolean
        get() = isSessionInstalled(GraphicSession.ICEWM)
    val jwmInstalled: Boolean
        get() = isSessionInstalled(GraphicSession.JWM)

    var logs by mutableStateOf<List<Pair<Int, String>>>(emptyList())
    val installLogs = mutableStateListOf<Pair<Int, String>>()

    var isSaving by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
        private set
    var isInstallingSession by mutableStateOf(false)
        private set
    var showInstallTerminal by mutableStateOf(false)
        private set
    var sessionOperationTitle by mutableStateOf("Graphic Session")
        private set
    var installResult by mutableStateOf<String?>(null)
        private set
    var installResultSession by mutableStateOf<GraphicSession?>(null)
        private set

    private var loaded = false
    private var containerName = ""
    private var cacheDir: File? = null
    private var savedGraphicSession = GraphicSession.XFCE

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
            initSystem = info.initSystem

            val sessionState = withContext(Dispatchers.IO) {
                val saved = ContainerSettingsManager.getGraphicSession(containerName)
                val installed = GraphicSessionSupport.installableSessions.associateWith { session ->
                    ContainerSettingsManager.isGraphicSessionInstalled(containerName, session)
                }
                saved to installed
            }

            graphicSession = sessionState.first ?: GraphicSession.XFCE
            savedGraphicSession = graphicSession
            installedSessions.clear()
            sessionState.second.forEach { (session, installed) ->
                installedSessions[session] = installed || graphicSession == session
            }

            logs = emptyList()
            installLogs.clear()
            installResult = null
            installResultSession = null
        }
    }

    fun selectInitSystem(system: InitSystem) {
        initSystem = system
    }

    fun isSessionInstalled(session: GraphicSession): Boolean =
        installedSessions[session] == true || graphicSession == session

    fun toggleSessionSelection(session: GraphicSession) {
        if (!isSessionInstalled(session) || isInstallingSession || isSaving) return
        graphicSession = if (graphicSession == session) GraphicSession.NONE else session
        installResultSession = session
        installResult = if (graphicSession == session) {
            "${session.label} selected — tap Save to apply"
        } else {
            "${session.label} deselected — tap Save to apply"
        }
    }

    fun installSession(session: GraphicSession) {
        if (isInstallingSession || isSaving) return
        if (session !in GraphicSessionSupport.installableSessions) return

        val cd = cacheDir ?: run {
            showOperationSetupError(
                "Installing ${session.label}",
                "cacheDir not set",
                session
            )
            return
        }

        beginSessionOperation("Installing ${session.label}", session)
        val selectedInitSystem = initSystem
        val logger = operationLogger()

        viewModelScope.launch {
            try {
                val installed = if (usesLegacyInstaller(session)) {
                    GraphicSessionInstaller.install(
                        containerName = containerName,
                        platform = null,
                        session = session,
                        initSystem = selectedInitSystem,
                        cacheDir = cd,
                        logger = logger
                    )
                } else {
                    AdditionalGraphicSessionInstaller.install(
                        containerName = containerName,
                        platform = null,
                        session = session,
                        initSystem = selectedInitSystem,
                        cacheDir = cd,
                        logger = logger
                    )
                }

                if (installed) {
                    initSystem = selectedInitSystem
                    graphicSession = session
                    savedGraphicSession = session
                    installedSessions[session] = true

                    val markerSaved = withContext(Dispatchers.IO) {
                        ContainerSettingsManager.setGraphicSessionInstalled(
                            containerName = containerName,
                            graphicSession = session,
                            installed = true,
                            cacheDir = cd
                        )
                    }
                    if (!markerSaved) {
                        logger.w("[!] ${session.label} is installed, but its installed marker could not be saved")
                    }

                    logger.i("")
                    val (statusBeforeFinalStop, _) =
                        ContainerManager.getContainerRuntimeStatePublic(containerName)
                    val stopAccepted = if (statusBeforeFinalStop == ContainerStatus.STOPPED) {
                        logger.i("[+] Container already stopped after installation")
                        true
                    } else {
                        logger.i("[*] Stopping container after installation...")
                        ContainerManager.stopContainer(containerName, logger)
                    }
                    val (statusAfterStop, _) = ContainerManager.getContainerRuntimeStatePublic(containerName)
                    if (stopAccepted || statusAfterStop == ContainerStatus.STOPPED) {
                        logger.i("[+] Container stopped")
                        logger.i("")
                        logger.i("[+] ${session.label} installation completed successfully")
                        logger.i("[+] Click Start X11 to launch the session")
                        installResult = "OK: ${session.label} installed. Click Start X11."
                    } else {
                        logger.w("[!] ${session.label} was installed, but the container could not be confirmed stopped")
                        logger.w("[!] Stop the container before clicking Start X11")
                        installResult = "Warning: ${session.label} installed; stop container before Start X11"
                    }
                } else {
                    installResult = "Error: ${session.label} installation failed"
                }
            } catch (e: Exception) {
                logOperationException(e, "${session.label} installation failed")
            } finally {
                refreshRuntimeStatus()
                isInstallingSession = false
            }
        }
    }

    fun verifySession(session: GraphicSession) {
        if (isInstallingSession || isSaving) return
        if (session !in GraphicSessionSupport.installableSessions) return

        beginSessionOperation("Verifying ${session.label}", session)
        val selectedInitSystem = initSystem
        val logger = operationLogger()

        viewModelScope.launch {
            try {
                val verified = if (usesLegacyInstaller(session)) {
                    GraphicSessionInstaller.verify(
                        containerName = containerName,
                        platform = null,
                        session = session,
                        initSystem = selectedInitSystem,
                        logger = logger
                    )
                } else {
                    AdditionalGraphicSessionInstaller.verify(
                        containerName = containerName,
                        platform = null,
                        session = session,
                        initSystem = selectedInitSystem,
                        logger = logger
                    )
                }

                if (verified) {
                    installedSessions[session] = true
                    cacheDir?.let { cd ->
                        withContext(Dispatchers.IO) {
                            ContainerSettingsManager.setGraphicSessionInstalled(
                                containerName,
                                session,
                                true,
                                cd
                            )
                        }
                    }
                    installResult = "OK: ${session.label} verified"
                } else {
                    installResult = "Error: ${session.label} verification failed"
                }
            } catch (e: Exception) {
                logOperationException(e, "${session.label} verification failed")
            } finally {
                refreshRuntimeStatus()
                isInstallingSession = false
            }
        }
    }

    private fun usesLegacyInstaller(session: GraphicSession): Boolean =
        session == GraphicSession.OPENBOX ||
            session == GraphicSession.ICEWM ||
            session == GraphicSession.JWM

    // Compatibility wrappers for the cards that existed before the generic flow.
    fun toggleOpenboxSelection() = toggleSessionSelection(GraphicSession.OPENBOX)
    fun toggleIcewmSelection() = toggleSessionSelection(GraphicSession.ICEWM)
    fun toggleJwmSelection() = toggleSessionSelection(GraphicSession.JWM)
    fun installOpenbox() = installSession(GraphicSession.OPENBOX)
    fun installIcewm() = installSession(GraphicSession.ICEWM)
    fun installJwm() = installSession(GraphicSession.JWM)
    fun verifyOpenbox() = verifySession(GraphicSession.OPENBOX)
    fun verifyIcewm() = verifySession(GraphicSession.ICEWM)
    fun verifyJwm() = verifySession(GraphicSession.JWM)

    private fun beginSessionOperation(title: String, session: GraphicSession) {
        installLogs.clear()
        installResult = null
        installResultSession = session
        sessionOperationTitle = title
        showInstallTerminal = true
        isInstallingSession = true
    }

    private fun operationLogger(): ViewModelLogger = ViewModelLogger { level, message ->
        installLogs.add(level to message)
    }

    private fun showOperationSetupError(
        title: String,
        message: String,
        session: GraphicSession
    ) {
        installLogs.clear()
        installLogs.add(Log.ERROR to "[-] FAIL")
        installLogs.add(Log.ERROR to "[-] $message")
        installResult = "Error: $message"
        installResultSession = session
        sessionOperationTitle = title
        showInstallTerminal = true
    }

    private fun logOperationException(e: Exception, fallback: String) {
        val message = e.message ?: fallback
        installLogs.add(Log.ERROR to "[-] FAIL")
        installLogs.add(Log.ERROR to "[-] $message")
        installResult = "Error: $message"
    }

    private suspend fun refreshRuntimeStatus() {
        val (runtimeStatus, _) = ContainerManager.getContainerRuntimeStatePublic(containerName)
        status = runtimeStatus
    }

    fun dismissInstallTerminal() {
        if (!isInstallingSession) showInstallTerminal = false
    }

    fun clearInstallLogs() {
        if (!isInstallingSession) installLogs.clear()
    }

    fun save() {
        if (isSaving || isInstallingSession) return
        isSaving = true
        saveError = null
        logs = emptyList()

        viewModelScope.launch {
            try {
                val cd = cacheDir ?: run {
                    saveError = "Error: cacheDir not set"
                    isSaving = false
                    return@launch
                }

                logs = logs + (Log.INFO to "[*] Saving configuration...")
                val previousSession = savedGraphicSession
                val sessionSaved = withContext(Dispatchers.IO) {
                    ContainerSettingsManager.setGraphicSession(
                        containerName = containerName,
                        graphicSession = graphicSession,
                        cacheDir = cd
                    )
                }

                if (!sessionSaved) {
                    logs = logs + (Log.ERROR to "[-] Failed to save Graphic Session")
                    saveError = "Error: Failed to save Graphic Session"
                    return@launch
                }

                val initOk = ContainerManager.updateInitSystem(
                    name = containerName,
                    target = initSystem,
                    cacheDir = cd
                )

                if (initOk) {
                    savedGraphicSession = graphicSession
                    logs = logs + (Log.INFO to "[+] Init System and Graphic Session saved")
                    saveError = "OK: Config saved"
                    val info = ContainerManager.getContainerInfo(containerName)
                    if (info != null) {
                        status = info.status
                        initSystem = info.initSystem
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        ContainerSettingsManager.setGraphicSession(
                            containerName = containerName,
                            graphicSession = previousSession,
                            cacheDir = cd
                        )
                    }
                    graphicSession = previousSession
                    logs = logs + (Log.ERROR to "[-] Failed to apply Init System / Graphic Session")
                    saveError = "Error: Failed to apply configuration"
                }
            } catch (e: Exception) {
                logs = logs + (Log.ERROR to "[-] ${e.message}")
                saveError = "Error: ${e.message}"
            } finally {
                isSaving = false
            }
        }
    }
}
