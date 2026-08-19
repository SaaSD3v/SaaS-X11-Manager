package com.saas.x11manager.ui.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.saas.x11manager.util.ScreenConfig
import com.saas.x11manager.util.ScreenTouchMode
import com.termux.x11.CmdEntryPoint
import com.termux.x11.ICmdEntryInterface
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.Prefs
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.InputStub
import com.termux.x11.input.RenderData
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.math.abs

/**
 * Lorie surface hosted directly by the DroidSpaces Screen tab.
 *
 * Upstream Lorie normally lives inside com.termux.x11.MainActivity. For the
 * integrated UI we keep only the rendering surface and the Binder connection
 * to CmdEntryPoint here, so no second Activity has to be opened.
 */
class EmbeddedX11View(context: Context) : LorieView(context) {
    private val renderData = RenderData()
    private val inputSender = InputEventSender(this)
    private var touchMode = ScreenTouchMode.Trackpad
    private var service: ICmdEntryInterface? = null
    private var receiverRegistered = false
    private var connectedCallback: ((Boolean) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var moved = false

    private val reconnect = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || connected()) return
            tryConnect()
            if (!connected()) postDelayed(this, 350L)
        }
    }

    private val serverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CmdEntryPoint.ACTION_START) return
            val bundle: Bundle = intent.getBundleExtra(null) ?: return
            val binder: IBinder = bundle.getBinder(null) ?: return
            service = ICmdEntryInterface.Stub.asInterface(binder)
            try {
                binder.linkToDeath({
                    service = null
                    post {
                        notifyConnection(false)
                        scheduleReconnect()
                    }
                }, 0)
            } catch (_: Exception) {
                service = null
            }
            tryConnect()
        }
    }

    private val imeConnection = object : BaseInputConnection(this, false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!text.isNullOrEmpty()) sendTextEvent(text.toString().toByteArray(UTF_8))
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true

        override fun finishComposingText(): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength.coerceAtLeast(0)) { sendKey(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength.coerceAtLeast(0)) { sendKey(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean = inputSender.sendKeyEvent(event)

        override fun performEditorAction(actionCode: Int): Boolean {
            sendKey(KeyEvent.KEYCODE_ENTER)
            return true
        }
    }

    init {
        // LorieView reads these static preferences while measuring its surface.
        // It does not require the upstream Activity to exist for that part.
        MainActivity.prefs = Prefs(context.applicationContext)
        setCallback { width, height, transform ->
            renderData.screenWidth = width
            renderData.screenHeight = height
            renderData.setInputTransform(transform)
        }
        setOnClickListener { requestFocus() }
    }

    fun setConnectionListener(listener: ((Boolean) -> Unit)?) {
        connectedCallback = listener
        listener?.invoke(connected())
    }

    fun applyScreenConfig(config: ScreenConfig) {
        touchMode = config.touchMode
        MainActivity.prefs = Prefs(context.applicationContext)

        // Filtering and clipboard are applied before upstream reloadPreferences()
        // reaches its Activity-only input-device refresh. The embedded host owns
        // input itself, so that final Activity-only refresh is intentionally ignored.
        try {
            reloadPreferences(MainActivity.prefs)
        } catch (_: NullPointerException) {
            // Expected when the upstream com.termux.x11.MainActivity is not running.
        }

        requestLayout()
        triggerCallback()
    }

    fun toggleSoftKeyboard() {
        toggleKeyboardVisible()
    }

    fun sendKey(keyCode: Int) {
        sendKeyEvent(0, keyCode, true)
        sendKeyEvent(0, keyCode, false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerServerReceiver()
        scheduleReconnect()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(reconnect)
        unregisterServerReceiver()
        connectedCallback = null
        service = null
        super.onDetachedFromWindow()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_NORMAL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return imeConnection
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean = inputSender.sendKeyEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP ||
            event.action == KeyEvent.ACTION_MULTIPLE
        ) {
            inputSender.sendKeyEvent(event)
        } else {
            super.dispatchKeyEvent(event)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        markUserActivity()
        return when (touchMode) {
            ScreenTouchMode.DirectTouch -> handleDirectTouch(event)
            ScreenTouchMode.SimulatedTouch -> handleSimulatedTouch(event)
            ScreenTouchMode.Trackpad -> handleTrackpad(event)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            inputSender.sendMouseWheelEvent(
                -event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                -event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            )
            return true
        }

        if (event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE || event.actionMasked == MotionEvent.ACTION_MOVE)
        ) {
            val point = renderData.mapScreenPoint(event.x, event.y)
            inputSender.sendCursorMove(point.x, point.y, false)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleDirectTouch(event: MotionEvent): Boolean {
        inputSender.sendTouchEvent(event, renderData)
        return true
    }

    private fun handleSimulatedTouch(event: MotionEvent): Boolean {
        val point: PointF = renderData.mapScreenPoint(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inputSender.sendCursorMove(point.x, point.y, false)
                inputSender.sendMouseDown(InputStub.BUTTON_LEFT, false)
            }
            MotionEvent.ACTION_MOVE -> inputSender.sendCursorMove(point.x, point.y, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                inputSender.sendCursorMove(point.x, point.y, false)
                inputSender.sendMouseUp(InputStub.BUTTON_LEFT, false)
            }
        }
        return true
    }

    private fun handleTrackpad(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downAt = event.eventTime
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(event.x - downX) > 8f || abs(event.y - downY) > 8f) moved = true

                if (event.pointerCount >= 2) {
                    inputSender.sendMouseWheelEvent(0f, -dy)
                } else {
                    inputSender.sendCursorMove(dx, dy, true)
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && event.eventTime - downAt < 300L) {
                    inputSender.sendMouseClick(InputStub.BUTTON_LEFT, true)
                }
            }
            MotionEvent.ACTION_CANCEL -> moved = true
        }
        return true
    }

    private fun registerServerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(CmdEntryPoint.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(serverReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(serverReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterServerReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(serverReceiver)
        } catch (_: IllegalArgumentException) {
        }
        receiverRegistered = false
    }

    private fun scheduleReconnect() {
        removeCallbacks(reconnect)
        if (!connected()) post(reconnect)
    }

    private fun tryConnect() {
        if (connected()) {
            notifyConnection(true)
            return
        }

        val activeService = service
        if (activeService == null) {
            try {
                requestConnection()
            } catch (_: Exception) {
            }
            return
        }

        try {
            val fd = activeService.getXConnection()
            if (fd != null) {
                connect(fd.detachFd())
                try {
                    val logFd = activeService.getLogcatOutput()
                    if (logFd != null) startLogcat(logFd.detachFd())
                } catch (_: Exception) {
                }
                triggerCallback()
                notifyConnection(connected())
            }
        } catch (_: Exception) {
            service = null
            notifyConnection(false)
        }
    }

    private fun notifyConnection(value: Boolean) {
        post { connectedCallback?.invoke(value) }
    }
}
