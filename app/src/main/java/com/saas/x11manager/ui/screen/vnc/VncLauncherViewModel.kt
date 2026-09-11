package com.saas.x11manager.ui.screen.vnc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saas.x11manager.X11Application
import com.saas.x11manager.embeddedvnc.EmbeddedVncConfig
import com.saas.x11manager.embeddedvnc.EmbeddedVncListener
import com.saas.x11manager.embeddedvnc.EmbeddedVncSession
import com.saas.x11manager.embeddedvnc.EmbeddedVncState
import com.saas.x11manager.operations.LogOperation
import com.saas.x11manager.operations.OperationArea
import com.saas.x11manager.operations.OperationNotifications
import com.saas.x11manager.operations.OperationOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

data class VncLauncherProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "VNC connection",
    val host: String = "",
    val port: Int = 5900,
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = false,
    val securityType: Int = 0,
    val imageQuality: Int = 6,
    val rawEncodingOnly: Boolean = false,
    val localCursor: Boolean = true,
    val viewOnly: Boolean = false,
    val directTouch: Boolean = false,
    val clipboardSync: Boolean = true,
    val autoReconnect: Boolean = false,
    val repeaterId: Int? = null,
    val trustAllCertificates: Boolean = false,
    val trustedCertificateSha256: String = "",
    val wakeMac: String = ""
)

class VncLauncherViewModel : ViewModel() {
    private val context = X11Application.instance
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val operationStore get() = context.operationLogs

    val singleDisplayMode: Boolean = true

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<VncLauncherProfile>> = _profiles

    private val _selectedProfileId = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    val selectedProfileId: StateFlow<String?> = _selectedProfileId

    private val _connectionState = MutableStateFlow(EmbeddedVncState.OFF)
    val connectionState: StateFlow<EmbeddedVncState> = _connectionState

    private val _connectionDetail = MutableStateFlow<String?>(null)
    val connectionDetail: StateFlow<String?> = _connectionDetail

    private val _screenEnabled = MutableStateFlow(false)
    val screenEnabled: StateFlow<Boolean> = _screenEnabled

    private val _framebufferSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val framebufferSize: StateFlow<Pair<Int, Int>?> = _framebufferSize

    private val _remoteClipboard = MutableStateFlow("")
    val remoteClipboard: StateFlow<String> = _remoteClipboard

    var showLogs by mutableStateOf(false)
        private set

    private var logProfileId by mutableStateOf<String?>(null)
    private var manualDisconnect = true
    private var reconnectJob: Job? = null
    private var activeProfileId: String? = null
    private var lastFramebuffer: Pair<Int, Int>? = null

    val session = EmbeddedVncSession(object : EmbeddedVncListener {
        override fun onStateChanged(state: EmbeddedVncState, detail: String?) {
            _connectionState.value = state
            _connectionDetail.value = detail
            val profile = activeProfile()
            val operation = profile?.let { operationFor(it.id) }

            when (state) {
                EmbeddedVncState.CONNECTING -> Unit
                EmbeddedVncState.CONNECTED -> {
                    operation?.append(3, "[VNC] Connected to ${profile?.host}:${profile?.port}")
                    detail?.takeIf { it.isNotBlank() }?.let {
                        operation?.append(3, "[VNC] Remote desktop: $it")
                    }
                    operation?.takeIf { it.running }?.finish(
                        true,
                        "Connected to ${profile?.name ?: "VNC server"}"
                    )
                }
                EmbeddedVncState.ERROR -> {
                    val reason = detail?.takeIf { it.isNotBlank() } ?: "VNC connection failed"
                    operation?.append(5, "[VNC] $reason")
                    operation?.finish(false, reason)
                    if (!manualDisconnect) scheduleReconnect()
                }
                EmbeddedVncState.OFF -> {
                    _screenEnabled.value = false
                    _framebufferSize.value = null
                    lastFramebuffer = null
                    operation?.let {
                        if (it.running) {
                            it.finish(
                                manualDisconnect,
                                if (manualDisconnect) "Disconnected" else "Connection closed"
                            )
                        } else if (it.available) {
                            it.append(3, "[VNC] Disconnected")
                            it.changed(immediate = true)
                        }
                    }
                    if (!manualDisconnect) scheduleReconnect()
                }
            }
        }

        override fun onFramebufferSizeChanged(width: Int, height: Int) {
            val next = width to height
            _framebufferSize.value = next
            if (next != lastFramebuffer) {
                activeProfile()?.let { profile ->
                    operationFor(profile.id)?.append(3, "[VNC] Framebuffer $width × $height")
                }
                lastFramebuffer = next
            }
        }

        override fun onClipboardText(text: String) {
            _remoteClipboard.value = text
            val profile = activeProfile()
            if (profile?.clipboardSync == true && text.isNotEmpty()) {
                clipboard?.setPrimaryClip(ClipData.newPlainText("VNC clipboard", text))
            }
        }
    })

    init {
        if (_profiles.value.size > 1) {
            val preferred = _profiles.value.firstOrNull { it.id == _selectedProfileId.value }
                ?: _profiles.value.first()
            _profiles.value = listOf(preferred)
            persistProfiles()
            selectProfile(preferred.id)
        } else if (_profiles.value.none { it.id == _selectedProfileId.value }) {
            selectProfile(_profiles.value.firstOrNull()?.id)
        }
    }

    fun selectedProfile(): VncLauncherProfile? =
        _profiles.value.firstOrNull { it.id == _selectedProfileId.value }

    fun activeProfile(): VncLauncherProfile? =
        _profiles.value.firstOrNull { it.id == activeProfileId }

    fun isActive(profile: VncLauncherProfile): Boolean = activeProfileId == profile.id

    private fun ownerFor(profileId: String) = OperationOwner(OperationArea.VNC, profileId)
    private fun operationFor(profileId: String): LogOperation? = operationStore.find(ownerFor(profileId))

    val selectedLogOperation: LogOperation?
        get() {
            operationStore.loadedState
            val id = activeProfileId ?: _selectedProfileId.value ?: return null
            return operationStore.findAvailable(ownerFor(id))
        }

    val logOperation: LogOperation?
        get() {
            operationStore.loadedState
            val id = logProfileId ?: activeProfileId ?: _selectedProfileId.value ?: return null
            return operationStore.findAvailable(ownerFor(id))
        }

    fun selectProfile(id: String?) {
        _selectedProfileId.value = id
        prefs.edit().apply {
            if (id == null) remove(KEY_SELECTED) else putString(KEY_SELECTED, id)
        }.apply()
    }

    fun saveProfile(profile: VncLauncherProfile) {
        require(profile.host.isNotBlank()) { "Host is required" }
        require(profile.port in 1..65535) { "Port must be between 1 and 65535" }
        require(profile.securityType >= 0) { "Security type cannot be negative" }
        val current = _profiles.value.toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            current[index] = profile
        } else {
            check(current.isEmpty()) {
                "This X11-0nly build supports one VNC display. Edit or delete the existing connection."
            }
            current += profile
        }
        _profiles.value = current.take(1)
        persistProfiles()
        selectProfile(profile.id)
        if (activeProfileId == profile.id && _connectionState.value == EmbeddedVncState.CONNECTED) {
            session.setViewOnly(profile.viewOnly)
        }
    }

    fun deleteProfile(id: String) {
        if (activeProfileId == id) disconnect()
        operationFor(id)?.takeUnless { it.running }?.let { OperationNotifications.dismiss(context, it) }
        _profiles.value = _profiles.value.filterNot { it.id == id }
        persistProfiles()
        if (_selectedProfileId.value == id) selectProfile(_profiles.value.firstOrNull()?.id)
    }

    fun connect(profile: VncLauncherProfile) {
        if (_connectionState.value == EmbeddedVncState.CONNECTING && activeProfileId == profile.id) return
        reconnectJob?.cancel()
        reconnectJob = null
        if (_connectionState.value == EmbeddedVncState.CONNECTED || _connectionState.value == EmbeddedVncState.CONNECTING) {
            disconnect()
        }

        manualDisconnect = false
        activeProfileId = profile.id
        selectProfile(profile.id)
        _screenEnabled.value = true
        _framebufferSize.value = null
        lastFramebuffer = null

        val operation = operationStore.get(ownerFor(profile.id))
        if (operation.running) operation.finish(false, "Previous VNC connection attempt was superseded")
        operation.begin("VNC · ${profile.name}", "${profile.host}:${profile.port}")
        operation.append(3, "[VNC] Opening standalone viewer")
        operation.append(3, "[VNC] Endpoint ${profile.host}:${profile.port}")
        operation.append(3, "[VNC] Screen updates enabled")
        logProfileId = profile.id
        showLogs = true

        runCatching { session.connect(profile.toEmbeddedConfig(), turnScreenOn = true) }
            .onFailure { error ->
                operation.append(5, "[VNC] ${error.message ?: "Could not start VNC client"}")
                operation.finish(false, error.message ?: "Could not start VNC client")
                _connectionState.value = EmbeddedVncState.ERROR
                _connectionDetail.value = error.message
            }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        manualDisconnect = true
        activeProfile()?.let {
            operationFor(it.id)?.takeIf { op -> op.available }?.append(3, "[VNC] Disconnect requested")
        }
        _screenEnabled.value = false
        session.disconnect()
        activeProfileId = null
    }

    fun setScreenEnabled(enabled: Boolean) {
        if (_connectionState.value != EmbeddedVncState.CONNECTED) return
        _screenEnabled.value = enabled
        activeProfile()?.let {
            operationFor(it.id)?.append(3, if (enabled) "[VNC] Screen updates resumed" else "[VNC] Screen updates paused")
        }
        session.setScreenEnabled(enabled)
    }

    fun setViewOnly(enabled: Boolean) = session.setViewOnly(enabled)

    fun refreshFramebuffer() {
        activeProfile()?.let { operationFor(it.id)?.append(3, "[VNC] Framebuffer refresh requested") }
        viewModelScope.launch(Dispatchers.IO) { session.refreshFramebuffer() }
    }

    fun resetZoom() = session.setZoom(1f)

    fun resizeRemote(width: Int, height: Int) {
        activeProfile()?.let { operationFor(it.id)?.append(3, "[VNC] Remote resize requested: $width × $height") }
        viewModelScope.launch(Dispatchers.IO) { session.resizeRemoteDesktop(width, height) }
    }

    fun sendClipboardFromAndroid() {
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) {
            activeProfile()?.let { operationFor(it.id)?.append(3, "[VNC] Android clipboard sent") }
            viewModelScope.launch(Dispatchers.IO) { session.sendClipboard(text) }
        }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { session.sendText(text) }
    }

    fun tapKeySym(keySym: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            session.sendKeySym(keySym, true)
            session.sendKeySym(keySym, false)
        }
    }

    fun wake(profile: VncLauncherProfile, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val mac = profile.wakeMac.trim().replace("-", ":")
                    val parts = mac.split(':')
                    require(parts.size == 6) { "Wake-on-LAN MAC must contain 6 bytes" }
                    val macBytes = ByteArray(6) { index -> parts[index].toInt(16).toByte() }
                    val packetBytes = ByteArray(6 + 16 * macBytes.size)
                    for (i in 0 until 6) packetBytes[i] = 0xFF.toByte()
                    var offset = 6
                    repeat(16) {
                        macBytes.copyInto(packetBytes, offset)
                        offset += macBytes.size
                    }
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        socket.send(DatagramPacket(packetBytes, packetBytes.size, InetAddress.getByName("255.255.255.255"), 9))
                    }
                }
            }
            onResult(result)
        }
    }

    fun openLogs(profileId: String? = null) {
        viewModelScope.launch {
            operationStore.awaitLoaded()
            val id = profileId ?: activeProfileId ?: _selectedProfileId.value ?: return@launch
            logProfileId = id
            showLogs = operationStore.findAvailable(ownerFor(id)) != null
        }
    }

    fun dismissLogs() {
        if (logOperation?.running != true) showLogs = false
    }

    fun minimizeLogs() {
        logOperation?.let { if (operationStore.minimize(it)) showLogs = false }
    }

    fun clearLogs() {
        val operation = logOperation ?: return
        if (operation.running) return
        viewModelScope.launch {
            operationStore.awaitLoaded()
            OperationNotifications.dismiss(context, operation)
            operation.logs.clear()
            operation.changed(immediate = true)
            operationStore.awaitPersisted()
            showLogs = false
        }
    }

    private fun scheduleReconnect() {
        val profile = activeProfile() ?: return
        if (!profile.autoReconnect || manualDisconnect) return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(2000)
            if (!manualDisconnect && activeProfileId == profile.id) connect(profile)
        }
    }

    private fun VncLauncherProfile.toEmbeddedConfig() = EmbeddedVncConfig(
        host = host.trim(),
        port = port,
        username = username,
        password = password,
        securityType = securityType,
        imageQuality = imageQuality,
        rawEncodingOnly = rawEncodingOnly,
        localCursor = localCursor,
        viewOnly = viewOnly,
        repeaterId = repeaterId,
        trustAllCertificates = trustAllCertificates,
        trustedCertificateSha256 = trustedCertificateSha256.ifBlank { null }
    )

    private fun loadProfiles(): List<VncLauncherProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(VncLauncherProfile(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = o.optString("name", "VNC connection"),
                        host = o.optString("host"),
                        port = o.optInt("port", 5900).coerceIn(1, 65535),
                        username = o.optString("username"),
                        password = if (o.optBoolean("rememberPassword", false)) o.optString("password") else "",
                        rememberPassword = o.optBoolean("rememberPassword", false),
                        securityType = o.optInt("securityType", 0).coerceAtLeast(0),
                        imageQuality = o.optInt("imageQuality", 6).coerceIn(0, 9),
                        rawEncodingOnly = o.optBoolean("rawEncodingOnly", false),
                        localCursor = o.optBoolean("localCursor", true),
                        viewOnly = o.optBoolean("viewOnly", false),
                        directTouch = o.optBoolean("directTouch", false),
                        clipboardSync = o.optBoolean("clipboardSync", true),
                        autoReconnect = o.optBoolean("autoReconnect", false),
                        repeaterId = if (o.has("repeaterId") && !o.isNull("repeaterId")) o.optInt("repeaterId") else null,
                        trustAllCertificates = o.optBoolean("trustAllCertificates", false),
                        trustedCertificateSha256 = o.optString("trustedCertificateSha256"),
                        wakeMac = o.optString("wakeMac")
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistProfiles() {
        val array = JSONArray()
        _profiles.value.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("host", p.host)
                put("port", p.port)
                put("username", p.username)
                put("rememberPassword", p.rememberPassword)
                put("password", if (p.rememberPassword) p.password else "")
                put("securityType", p.securityType)
                put("imageQuality", p.imageQuality)
                put("rawEncodingOnly", p.rawEncodingOnly)
                put("localCursor", p.localCursor)
                put("viewOnly", p.viewOnly)
                put("directTouch", p.directTouch)
                put("clipboardSync", p.clipboardSync)
                put("autoReconnect", p.autoReconnect)
                put("repeaterId", p.repeaterId ?: JSONObject.NULL)
                put("trustAllCertificates", p.trustAllCertificates)
                put("trustedCertificateSha256", p.trustedCertificateSha256)
                put("wakeMac", p.wakeMac)
            })
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    override fun onCleared() {
        reconnectJob?.cancel()
        session.shutdown()
        super.onCleared()
    }

    companion object {
        private const val PREFS = "standalone-vnc-launcher"
        private const val KEY_PROFILES = "profiles-json"
        private const val KEY_SELECTED = "selected-profile"
    }
}
