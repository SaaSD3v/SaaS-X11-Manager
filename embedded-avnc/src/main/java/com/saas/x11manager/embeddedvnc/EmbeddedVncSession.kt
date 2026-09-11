package com.saas.x11manager.embeddedvnc

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.gaurav.avnc.ui.vnc.FrameState
import com.gaurav.avnc.vnc.UserCredential
import com.gaurav.avnc.vnc.VncClient
import com.gaurav.avnc.vnc.XKeySymAndroid
import com.gaurav.avnc.vnc.XKeySymUnicode
import com.gaurav.avnc.vnc.XTKeyCode
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Small host adapter around AVNC 3.3.1's VncClient/LibVNCClient engine.
 *
 * This class deliberately knows nothing about DroidSpaces, TigerVNC lifecycle,
 * Integrated X11 or the Manager's container model. To it, a VNC endpoint is just
 * host:port. This keeps the embedded viewer equivalent to a standalone desktop
 * VNC client while allowing the Manager to own the surrounding UI.
 */
data class EmbeddedVncConfig(
    val host: String,
    val port: Int = 5900,
    val username: String = "",
    val password: String = "",
    val securityType: Int = 0,
    val imageQuality: Int = 6,
    val rawEncodingOnly: Boolean = false,
    val localCursor: Boolean = true,
    val viewOnly: Boolean = false,
    val repeaterId: Int? = null,
    val trustAllCertificates: Boolean = false,
    val trustedCertificateSha256: String? = null
)

enum class EmbeddedVncState {
    OFF,
    CONNECTING,
    CONNECTED,
    ERROR
}

interface EmbeddedVncListener {
    fun onStateChanged(state: EmbeddedVncState, detail: String?) {}
    fun onFramebufferUpdated() {}
    fun onFramebufferSizeChanged(width: Int, height: Int) {}
    fun onPointerMoved(x: Int, y: Int) {}
    fun onClipboardText(text: String) {}
    fun onBell() {}
}

class EmbeddedVncSession(
    private val listener: EmbeddedVncListener
) {
    val frameState = FrameState(minZoomScale = 0.25f, maxZoomScale = 8f, usePerOrientationZoom = true)

    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    private val screenEnabled = AtomicBoolean(false)
    private val inputDisabled = AtomicBoolean(false)
    private val renderLock = Any()

    @Volatile
    internal var client: VncClient? = null
        private set

    @Volatile
    private var renderOwner: Any? = null

    @Volatile
    private var renderSink: (() -> Unit)? = null

    @Volatile
    var state: EmbeddedVncState = EmbeddedVncState.OFF
        private set

    val isScreenEnabled: Boolean get() = screenEnabled.get()
    val isConnected: Boolean get() = state == EmbeddedVncState.CONNECTED && client?.connected == true

    fun connect(config: EmbeddedVncConfig, turnScreenOn: Boolean = true) {
        require(config.host.isNotBlank()) { "VNC host cannot be empty" }
        require(config.port in 1..65535) { "VNC port must be between 1 and 65535" }
        require(config.imageQuality in 0..9) { "Image quality must be between 0 and 9" }

        val token = generation.incrementAndGet()
        screenEnabled.set(turnScreenOn)
        inputDisabled.set(config.viewOnly)
        publishState(EmbeddedVncState.CONNECTING, "Connecting to ${config.host}:${config.port}")

        Thread({ runConnection(token, config) }, "embedded-avnc-$token").apply {
            isDaemon = true
            start()
        }
    }

    private fun runConnection(token: Long, config: EmbeddedVncConfig) {
        var localClient: VncClient? = null
        var failed = false
        try {
            val observer = object : VncClient.Observer {
                override fun getVncPassword(): String = config.password

                override fun getVncCredentials(): UserCredential =
                    UserCredential(config.username, config.password)

                override fun verifyVncServerCertificate(certificate: X509Certificate): Boolean {
                    if (config.trustAllCertificates) return true
                    val expected = config.trustedCertificateSha256
                        ?.filterNot { it == ':' || it.isWhitespace() }
                        ?.lowercase(Locale.ROOT)
                        ?.takeIf { it.isNotBlank() }
                        ?: return false
                    return certificateSha256(certificate) == expected
                }

                override fun onCutTextReceived(text: String) {
                    dispatch { listener.onClipboardText(text) }
                }

                override fun onFramebufferUpdated() {
                    dispatch {
                        requestRender()
                        listener.onFramebufferUpdated()
                    }
                }

                override fun onFramebufferSizeChanged(width: Int, height: Int) {
                    frameState.setFramebufferSize(width.toFloat(), height.toFloat())
                    dispatch {
                        requestRender()
                        listener.onFramebufferSizeChanged(width, height)
                    }
                }

                override fun onPointerMoved(x: Int, y: Int) {
                    dispatch {
                        requestRender()
                        listener.onPointerMoved(x, y)
                    }
                }

                override fun onBell() {
                    dispatch { listener.onBell() }
                }
            }

            val created = VncClient(observer)
            localClient = created
            if (token != generation.get()) return
            client = created

            created.configure(
                securityType = config.securityType,
                useLocalCursor = config.localCursor,
                imageQuality = config.imageQuality,
                useRawEncoding = config.rawEncodingOnly
            )
            config.repeaterId?.takeIf { it >= 0 }?.let(created::setupRepeater)
            created.setInputDisabled(config.viewOnly)
            created.connect(config.host, config.port)

            if (token != generation.get()) return
            created.setFrameBufferUpdatesPaused(!screenEnabled.get())
            publishState(EmbeddedVncState.CONNECTED, created.getDesktopName().ifBlank { null })
            requestRender()

            while (token == generation.get() && created.connected) {
                created.processServerMessage()
            }
        } catch (t: Throwable) {
            failed = true
            if (token == generation.get()) {
                publishState(
                    EmbeddedVncState.ERROR,
                    t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                )
            }
        } finally {
            runCatching { localClient?.cleanup() }
            if (client === localClient) client = null
            if (token == generation.get() && !failed) {
                publishState(EmbeddedVncState.OFF, null)
            }
            requestRender()
        }
    }

    fun disconnect() {
        generation.incrementAndGet()
        screenEnabled.set(false)
        publishState(EmbeddedVncState.OFF, null)
        requestRender()
        // processServerMessage() waits at most one second. Its worker observes the
        // generation change, exits and owns cleanup, so the UI thread never blocks
        // on AVNC's read/write lock.
    }

    fun shutdown() = disconnect()

    fun setScreenEnabled(enabled: Boolean) {
        screenEnabled.set(enabled)
        val current = client ?: run {
            requestRender()
            return
        }
        Thread({
            runCatching { current.setFrameBufferUpdatesPaused(!enabled) }
                .onSuccess { dispatch(::requestRender) }
                .onFailure {
                    if (current === client) {
                        publishState(EmbeddedVncState.ERROR, it.message ?: "Could not change framebuffer state")
                    }
                }
        }, "embedded-avnc-screen").apply {
            isDaemon = true
            start()
        }
    }

    fun setViewOnly(enabled: Boolean) {
        inputDisabled.set(enabled)
        client?.setInputDisabled(enabled)
    }

    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        if (inputDisabled.get()) return
        val current = client ?: return
        current.moveClientPointer(x, y)
        current.sendPointerEvent(x, y, buttonMask)
        requestRender()
    }

    fun sendKey(event: KeyEvent, isDown: Boolean): Boolean {
        if (inputDisabled.get()) return false
        val current = client ?: return false
        var keySym = XKeySymAndroid.getKeySymForAndroidKeyCode(event.keyCode)
        if (keySym == 0 && event.unicodeChar > 0) {
            keySym = XKeySymUnicode.getKeySymForUnicodeChar(event.unicodeChar)
        }
        if (keySym == 0) return false
        val xtCode = XTKeyCode.fromAndroidScancode(event.scanCode)
        current.sendKeyEvent(keySym, xtCode, isDown)
        return true
    }

    fun sendKeySym(keySym: Int, down: Boolean) {
        if (inputDisabled.get()) return
        client?.sendKeyEvent(keySym, 0, down)
    }

    fun sendText(text: String) {
        if (inputDisabled.get()) return
        val current = client ?: return
        text.codePoints().forEach { codePoint ->
            val keySym = XKeySymUnicode.getKeySymForUnicodeChar(codePoint)
            current.sendKeyEvent(keySym, 0, true)
            current.sendKeyEvent(keySym, 0, false)
        }
    }

    fun sendClipboard(text: String) {
        if (inputDisabled.get()) return
        client?.sendCutText(text)
    }

    fun resizeRemoteDesktop(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        client?.setDesktopSize(width, height)
    }

    fun refreshFramebuffer() {
        client?.refreshFrameBuffer()
    }

    fun setZoom(zoom1: Float, zoom2: Float = zoom1) {
        frameState.setZoom(zoom1, zoom2)
        requestRender()
    }

    fun updateZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        frameState.updateZoom(scaleFactor, focusX, focusY)
        requestRender()
    }

    fun pan(deltaX: Float, deltaY: Float) {
        frameState.pan(deltaX, deltaY)
        requestRender()
    }

    internal fun attachRenderSink(owner: Any, sink: () -> Unit) {
        synchronized(renderLock) {
            renderOwner = owner
            renderSink = sink
        }
        requestRender()
    }

    internal fun detachRenderSink(owner: Any) {
        synchronized(renderLock) {
            if (renderOwner === owner) {
                renderOwner = null
                renderSink = null
            }
        }
    }

    private fun requestRender() {
        val callback = synchronized(renderLock) { renderSink }
        callback?.invoke()
    }

    private fun publishState(newState: EmbeddedVncState, detail: String?) {
        state = newState
        dispatch { listener.onStateChanged(newState, detail) }
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun certificateSha256(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}
