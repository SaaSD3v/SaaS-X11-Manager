package com.saas.x11manager.embeddedvnc

import android.content.Context
import android.graphics.PointF
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.gaurav.avnc.vnc.PointerButton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reusable Android view hosting AVNC's framebuffer engine inside the Manager.
 * It is intentionally independent from AVNC's Activity and from DroidSpaces.
 */
class EmbeddedVncFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {
    private var session: EmbeddedVncSession? = null
    private var rendererInstalled = false

    var directTouch: Boolean = false
    var inputEnabled: Boolean = true

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var scrollAccumulator = 0f
    private val moveThreshold = 12f * resources.displayMetrics.density
    private val scrollStep = 28f * resources.displayMetrics.density

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = session ?: return false
                current.updateZoom(detector.scaleFactor, detector.focusX, detector.focusY)
                requestRender()
                return true
            }
        }
    )

    init {
        preserveEGLContextOnPause = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun attachSession(value: EmbeddedVncSession) {
        if (session === value && rendererInstalled) return
        check(!rendererInstalled) { "EmbeddedVncFrameView can host only one session" }
        session = value
        setEGLContextClientVersion(2)
        setRenderer(EmbeddedVncRenderer(value))
        renderMode = RENDERMODE_WHEN_DIRTY
        rendererInstalled = true
        value.attachRenderSink(this) { requestRender() }
        requestFocus()
    }

    fun detachSession() {
        session?.detachRenderSink(this)
        session = null
    }

    override fun onDetachedFromWindow() {
        detachSession()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            session?.frameState?.setViewportSize(w.toFloat(), h.toFloat())
            session?.frameState?.setWindowSize(w.toFloat(), h.toFloat())
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val current = session ?: return false
        if (!current.isConnected || !current.isScreenEnabled) return true
        requestFocus()
        scaleDetector.onTouchEvent(event)

        if (!inputEnabled) return true

        if (event.pointerCount >= 2) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress) {
                val y = (event.getY(0) + event.getY(1)) / 2f
                if (lastY != 0f) {
                    scrollAccumulator += y - lastY
                    while (abs(scrollAccumulator) >= scrollStep) {
                        val button = if (scrollAccumulator > 0) PointerButton.WheelDown else PointerButton.WheelUp
                        val point = framebufferPoint(event.x, event.y) ?: PointF(0f, 0f)
                        current.sendPointer(point.x.roundToInt(), point.y.roundToInt(), button.bitMask)
                        current.sendPointer(point.x.roundToInt(), point.y.roundToInt(), PointerButton.None.bitMask)
                        scrollAccumulator += if (scrollAccumulator > 0) -scrollStep else scrollStep
                    }
                }
                lastY = y
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.actionMasked == MotionEvent.ACTION_UP) {
                lastY = 0f
                scrollAccumulator = 0f
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                if (directTouch) {
                    framebufferPoint(event.x, event.y)?.let { p ->
                        current.sendPointer(p.x.roundToInt(), p.y.roundToInt(), PointerButton.Left.bitMask)
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > moveThreshold || abs(event.y - downY) > moveThreshold) moved = true
                if (directTouch) {
                    framebufferPoint(event.x, event.y)?.let { p ->
                        current.sendPointer(p.x.roundToInt(), p.y.roundToInt(), PointerButton.Left.bitMask)
                    }
                } else {
                    val client = current.client
                    if (client != null) {
                        val snapshot = current.frameState.getSnapshot()
                        val scale = snapshot.scale.takeIf { it > 0f } ?: 1f
                        val nx = (client.pointerX + (event.x - lastX) / scale).roundToInt()
                            .coerceIn(0, (snapshot.fbWidth - 1f).roundToInt().coerceAtLeast(0))
                        val ny = (client.pointerY + (event.y - lastY) / scale).roundToInt()
                            .coerceIn(0, (snapshot.fbHeight - 1f).roundToInt().coerceAtLeast(0))
                        current.sendPointer(nx, ny, PointerButton.None.bitMask)
                    }
                }
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (directTouch) {
                    framebufferPoint(event.x, event.y)?.let { p ->
                        current.sendPointer(p.x.roundToInt(), p.y.roundToInt(), PointerButton.None.bitMask)
                    }
                } else if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                    val client = current.client
                    if (client != null) {
                        current.sendPointer(client.pointerX, client.pointerY, PointerButton.Left.bitMask)
                        current.sendPointer(client.pointerX, client.pointerY, PointerButton.None.bitMask)
                    }
                }
                lastX = 0f
                lastY = 0f
            }
        }
        requestRender()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val current = session ?: return super.onGenericMotionEvent(event)
        if (!inputEnabled || !current.isConnected) return true
        if (event.source and InputDevice.SOURCE_MOUSE != InputDevice.SOURCE_MOUSE) {
            return super.onGenericMotionEvent(event)
        }

        val point = framebufferPoint(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                point?.let { p ->
                    current.sendPointer(p.x.roundToInt(), p.y.roundToInt(), mouseButtons(event.buttonState))
                }
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val p = point ?: return true
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val button = when {
                    vertical > 0f -> PointerButton.WheelUp
                    vertical < 0f -> PointerButton.WheelDown
                    horizontal > 0f -> PointerButton.WheelLeft
                    horizontal < 0f -> PointerButton.WheelRight
                    else -> PointerButton.None
                }
                if (button != PointerButton.None) {
                    current.sendPointer(p.x.roundToInt(), p.y.roundToInt(), button.bitMask)
                    current.sendPointer(p.x.roundToInt(), p.y.roundToInt(), PointerButton.None.bitMask)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return session?.sendKey(event, true) == true || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return session?.sendKey(event, false) == true || super.onKeyUp(keyCode, event)
    }

    private fun framebufferPoint(x: Float, y: Float): PointF? =
        session?.frameState?.toFb(PointF(x, y))

    private fun mouseButtons(state: Int): Int {
        var mask = 0
        if (state and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or PointerButton.Left.bitMask
        if (state and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or PointerButton.Middle.bitMask
        if (state and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or PointerButton.Right.bitMask
        return mask
    }
}
