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
import com.saas.x11manager.util.ContainerCapabilities
import com.saas.x11manager.util.ContainerCapabilitiesDetector
import com.saas.x11manager.util.ContainerManager
import com.saas.x11manager.util.ContainerSettingsManager
import com.saas.x11manager.util.ContainerStatus
import com.saas.x11manager.util.GraphicProtocol
import com.saas.x11manager.util.GraphicSession
import com.saas.x11manager.util.GraphicSessionCatalogMode
import com.saas.x11manager.util.GraphicSessionInstaller
import com.saas.x11manager.util.GraphicSessionSupport
import com.saas.x11manager.util.GraphicSessionWizard
import com.saas.x11manager.util.InitSystem
import com.saas.x11manager.util.ViewModelLogger
import com.saas.x11manager.util.X11SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ConfigurationWizardStage {
    HIDDEN,
    RUNNING_WARNING,
    INIT_SELECTION,
    PROTOCOL_SELECTION,
    CATALOG_SELECTION,
    SESSION_SELECTION
}

class EditContainerViewModel : ViewModel() {

    var name by mutableStateOf("")
    var hostname by mutableStateOf("")
    var status by mutableStateOf(ContainerStatus.UNKNOWN)
    var initSystem by mutableStateOf(InitSystem.SYSTEMD)
        private set
    var graphicSession by mutableStateOf(GraphicSession.XFCE)
        private set
    var containerCapabilities by mutableStateOf<ContainerCapabilities?>(null)
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

    var wizardStage by mutableStateOf(ConfigurationWizardStage.HIDDEN)
        private set
    var pendingWizardInitSystem by mutableStateOf<InitSystem?>(null)
        private set
    var pendingWizardProtocol by mutableStateOf(GraphicProtocol.X11)
        private set
    var pendingWizardCatalogMode by mutableStateOf(GraphicSessionCatalogMode.STABLE)
        private set
    var isPreparingWizard by mutableStateOf(false)
        private set
    var wizardError by mutableStateOf<String?>(null)
        private set
    var wizardStarted by mutableStateOf(false)
        private set
    var canStartX11FromInstall by mutableStateOf(false)
        private set
    var quickStartCompleted by mutableStateOf(false)
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
        containerCapabilities = null

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
            wizardError = null
            pendingWizardProtocol = graphicSession.protocol
            pendingWizardCatalogMode = GraphicSessionCatalogMode.STABLE
            canStartX11FromInstall = false
            quickStartCompleted = false
        }
    }

    fun startConfigurationWizard() {
        if (wizardStarted || name.isEmpty() || isInstallingSession || isPreparingWizard) return
        wizardStarted = true
        wizardStage = ConfigurationWizardStage.HIDDEN
        wizardError = null
        pendingWizardProtocol = graphicSession.protocol
        pendingWizardCatalogMode = GraphicSessionCatalogMode.STABLE
        isPreparingWizard = true

        viewModelScope.launch {
            try {
                val detected = ContainerCapabilitiesDetector.detect(containerName)
                if (detected == null || detected.platform == null) {
                    wizardError = "Could not detect a supported package manager (apk or apt/dpkg) in this container."
                    wizardStage = ConfigurationWizardStage.HIDDEN
                    return@launch
                }
                if (detected.availableInitSystems.isEmpty()) {
                    wizardError = "No supported init backend was detected. Install or provide systemd or OpenRC before configuring a graphic session."
                    wizardStage = ConfigurationWizardStage.HIDDEN
                    return@launch
                }

                containerCapabilities = detected
                pendingWizardInitSystem = if (detected.supports(initSystem)) {
                    initSystem
                } else {
                    detected.availableInitSystems.first()
                }
                wizardStage = if (status == ContainerStatus.RUNNING) {
                    ConfigurationWizardStage.RUNNING_WARNING
                } else {
                    ConfigurationWizardStage.INIT_SELECTION
                }
            } catch (e: Exception) {
                Log.e("EditContainerViewModel", "capability detection failed", e)
                wizardError = e.message ?: "Could not detect container capabilities."
                wizardStage = ConfigurationWizardStage.HIDDEN
            } finally {
                isPreparingWizard = false
            }
        }
    }

    fun dismissConfigurationWizard() {
        if (isPreparingWizard || isInstallingSession) return
        wizardStage = ConfigurationWizardStage.HIDDEN
        wizardStarted = false
    }

    fun confirmRunningContainerRestart() {
        if (isPreparingWizard || isInstallingSession) return
        if (status != ContainerStatus.RUNNING) {
            wizardStage = ConfigurationWizardStage.INIT_SELECTION
            return
        }

        isPreparingWizard = true
        wizardError = null
        viewModelScope.launch {
            try {
                val stopped = ContainerManager.stopContainer(containerName)
                val (runtimeStatus, _) = ContainerManager.getContainerRuntimeStatePublic(containerName)
                if (stopped || runtimeStatus == ContainerStatus.STOPPED) {
                    status = ContainerStatus.STOPPED
                    wizardStage = ConfigurationWizardStage.INIT_SELECTION
                } else {
                    wizardStage = ConfigurationWizardStage.HIDDEN
                    wizardStarted = false
                    wizardError = "Could not stop the running container. Configuration was not started."
                }
            } catch (e: Exception) {
                wizardStage = ConfigurationWizardStage.HIDDEN
                wizardStarted = false
                wizardError = e.message ?: "Could not stop the running container."
            } finally {
                isPreparingWizard = false
            }
        }
    }

    fun availableWizardInitSystems(): List<InitSystem> =
        InitSystem.entries.filter { system -> containerCapabilities?.supports(system) == true }

    fun selectWizardInitSystem(system: InitSystem) {
        if (isPreparingWizard || isInstallingSession) return
        val capabilities = containerCapabilities
        if (capabilities == null || !capabilities.supports(system)) {
            wizardError = "${system.name.lowercase()} is not available in this container."
            return
        }
        pendingWizardInitSystem = system
        wizardStage = ConfigurationWizardStage.PROTOCOL_SELECTION
    }

    fun selectWizardProtocol(protocol: GraphicProtocol) {
        if (isPreparingWizard || isInstallingSession) return
        pendingWizardProtocol = protocol
        wizardStage = ConfigurationWizardStage.CATALOG_SELECTION
    }

    fun selectWizardCatalogMode(mode: GraphicSessionCatalogMode) {
        if (isPreparingWizard || isInstallingSession) return
        pendingWizardCatalogMode = mode
        wizardStage = ConfigurationWizardStage.SESSION_SELECTION
    }

    fun backToWizardInitSelection() {
        if (isPreparingWizard || isInstallingSession) return
        wizardStage = ConfigurationWizardStage.INIT_SELECTION
    }

    fun backToWizardProtocolSelection() {
        if (isPreparingWizard || isInstallingSession) return
        wizardStage = ConfigurationWizardStage.PROTOCOL_SELECTION
    }

    fun backToWizardCatalogSelection() {
        if (isPreparingWizard || isInstallingSession) return
        wizardStage = ConfigurationWizardStage.CATALOG_SELECTION
    }

    fun returnToWizardSessionSelection() {
        if (isPreparingWizard || isInstallingSession) return
        wizardStage = ConfigurationWizardStage.SESSION_SELECTION
    }

    fun wizardSessions(): List<GraphicSession> =
        containerCapabilities?.platform?.let { platform ->
            GraphicSessionWizard.sessionsFor(
                platform,
                pendingWizardCatalogMode,
                pendingWizardProtocol
            )
        }.orEmpty()

    fun isWizardSessionExperimental(session: GraphicSession): Boolean =
        containerCapabilities?.platform?.let { platform ->
            GraphicSessionWizard.isExperimental(platform, session)
        } == true

    fun configureWizardSession(session: GraphicSession) {
        if (isPreparingWizard || isInstallingSession || isSaving) return
        val selectedInit = pendingWizardInitSystem ?: initSystem
        val capabilities = containerCapabilities
        val platform = capabilities?.platform
        if (capabilities == null || platform == null) {
            wizardError = "Container package capabilities are unavailable. Reopen the configuration wizard and retry detection."
            return
        }
        if (!capabilities.supports(selectedInit)) {
            wizardError = "${selectedInit.name.lowercase()} is not available in this container."
            return
        }
        if (
            session !in GraphicSessionWizard.sessionsFor(
                platform,
                pendingWizardCatalogMode,
                pendingWizardProtocol
            )
        ) {
            wizardError = "${session.label} is not available for the detected ${platform.label} package platform and ${pendingWizardProtocol.label} protocol."
            return
        }
        wizardStage = ConfigurationWizardStage.HIDDEN
        installSessionWithInit(session, selectedInit)
    }

    fun selectInitSystem(system: InitSystem) {
        if (containerCapabilities?.let { !it.supports(system) } == true) return
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
        installSessionWithInit(session, initSystem)
    }

    private fun installSessionWithInit(session: GraphicSession, selectedInitSystem: InitSystem) {
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
        val logger = operationLogger()
        val detectedPlatform = containerCapabilities?.platform

        viewModelScope.launch {
            try {
                val installed = if (usesLegacyInstaller(session)) {
                    GraphicSessionInstaller.install(
                        containerName = containerName,
                        platform = detectedPlatform,
                        session = session,
                        initSystem = selectedInitSystem,
                        cacheDir = cd,
                        logger = logger
                    )
                } else {
                    AdditionalGraphicSessionInstaller.install(
                        containerName = containerName,
                        platform = detectedPlatform,
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
                    status = statusAfterStop
                    if (stopAccepted || statusAfterStop == ContainerStatus.STOPPED) {
                        logger.i("[+] Container stopped")
                    } else {
                        logger.w("[!] ${session.label} was installed, but the container could not be confirmed stopped")
                    }

                    logger.i("")
                    logger.i("[+] ${session.label} installation completed successfully")
                    logger.i("[+] Monitor: assigned dynamically on next Start X11 (lowest available)")
                    logger.i("[+] Use Start X11 below to launch this container now")
                    installResult = "OK: ${session.label} installed · monitor assigned on Start X11"
                    canStartX11FromInstall = true
                    quickStartCompleted = false
                } else {
                    installResult = "Error: ${session.label} installation failed"
                    canStartX11FromInstall = false
                }
            } catch (e: Exception) {
                logOperationException(e, "${session.label} installation failed")
                canStartX11FromInstall = false
            } finally {
                refreshRuntimeStatus()
                isInstallingSession = false
            }
        }
    }

    fun quickStartX11() {
        if (isInstallingSession || !canStartX11FromInstall) return

        installLogs.add(Log.INFO to "")
        installLogs.add(Log.INFO to "--- Starting X11 ---")
        sessionOperationTitle = "Starting X11: ${graphicSession.label}"
        isInstallingSession = true
        quickStartCompleted = false
        val logger = operationLogger()

        viewModelScope.launch {
            try {
                X11SessionManager.startX11Session(
                    containerName = containerName,
                    logger = logger
                )
                val (runtimeStatus, _) = ContainerManager.getContainerRuntimeStatePublic(containerName)
                status = runtimeStatus
                if (runtimeStatus == ContainerStatus.RUNNING) {
                    val displaySlot = X11SessionManager.getDisplayForContainer(containerName)
                    if (displaySlot != null) {
                        logger.i("[+] Active monitor: ${displaySlot.describe()}")
                        installResult =
                            "OK: X11 started with ${graphicSession.label} · " +
                                "Monitor ${displaySlot.monitorNumber} (${displaySlot.displayName})"
                    } else {
                        logger.w("[!] X11 started, but the active monitor could not be resolved")
                        installResult = "OK: X11 started with ${graphicSession.label}"
                    }
                    canStartX11FromInstall = false
                    quickStartCompleted = true
                } else {
                    installResult = "Warning: X11 start requested, but container state is ${runtimeStatus.name.lowercase()}"
                    canStartX11FromInstall = true
                }
            } catch (e: Exception) {
                logOperationException(e, "X11 start failed")
                canStartX11FromInstall = true
            } finally {
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
        val detectedPlatform = containerCapabilities?.platform

        viewModelScope.launch {
            try {
                val verified = if (usesLegacyInstaller(session)) {
                    GraphicSessionInstaller.verify(
                        containerName = containerName,
                        platform = detectedPlatform,
                        session = session,
                        initSystem = selectedInitSystem,
                        logger = logger
                    )
                } else {
                    AdditionalGraphicSessionInstaller.verify(
                        containerName = containerName,
                        platform = detectedPlatform,
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
        canStartX11FromInstall = false
        quickStartCompleted = false
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
        canStartX11FromInstall = false
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
