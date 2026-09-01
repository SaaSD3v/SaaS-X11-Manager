package com.saas.x11manager.ui.screen

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.x11.EmbeddedDisplayHost
import com.termux.x11.EmbeddedStylusInputController
import com.termux.x11.EmbeddedTouchInputController
import com.termux.x11.LorieView
import com.termux.x11.extrakeys.ExtraKeyButton
import com.termux.x11.extrakeys.ExtraKeysConstants
import com.termux.x11.extrakeys.ExtraKeysInfo
import com.termux.x11.utils.TermuxX11ExtraKeys

internal const val ACTION_LORIE_PREFERENCES_CHANGED =
    "com.termux.x11.ACTION_PREFERENCES_CHANGED"

internal fun publishLoriePreferenceChange(context: Context, key: String) {
    context.sendBroadcast(Intent(ACTION_LORIE_PREFERENCES_CHANGED).apply {
        putExtra("key", key)
        putExtra("fromBroadcast", true)
        setPackage(context.packageName)
    })
}

@Composable
internal fun EmbeddedX11Surface(
    displayName: String = ":0",
    modifier: Modifier = Modifier,
    onConnectionChanged: (Boolean) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            LorieView(context).apply {
                val stylusInput = EmbeddedStylusInputController(this)
                val touchInput = EmbeddedTouchInputController(this)

                setZOrderOnTop(false)
                setZOrderMediaOverlay(false)
                isFocusable = true
                isFocusableInTouchMode = true
                setCallback { screenWidth, screenHeight, inputTransform ->
                    EmbeddedDisplayHost.updateInputTransform(
                        this,
                        screenWidth,
                        screenHeight,
                        inputTransform
                    )
                    stylusInput.updateInputTransform(
                        screenWidth,
                        screenHeight,
                        inputTransform
                    )
                    touchInput.updateInputTransform(
                        screenWidth,
                        screenHeight,
                        inputTransform
                    )
                    onConnectionChanged(connected())
                }

                fun routeMotion(event: android.view.MotionEvent): Boolean = when {
                    stylusInput.handles(event) -> stylusInput.handle(event)
                    touchInput.handles(event) -> touchInput.handle(event)
                    else -> EmbeddedDisplayHost.handleMotion(this, event)
                }

                setOnTouchListener { _, event -> routeMotion(event) }
                setOnHoverListener { _, event -> routeMotion(event) }
                setOnGenericMotionListener { _, event -> routeMotion(event) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setOnCapturedPointerListener { _, event -> routeMotion(event) }
                }
                setOnKeyListener { _, _, event ->
                    connected() && EmbeddedDisplayHost.handleKey(this, event)
                }
                requestFocus()
                EmbeddedDisplayHost.selectDisplay(displayName)
                EmbeddedDisplayHost.tryConnect()
                scheduleEmbeddedSurfaceResync(this)
            }
        },
        update = { view ->
            val selectedChanged = EmbeddedDisplayHost.getSelectedDisplay() != displayName
            if (selectedChanged) {
                onConnectionChanged(false)
                EmbeddedDisplayHost.selectDisplay(displayName)
            }

            val connected = view.connected()
            onConnectionChanged(connected)
            if (!connected) EmbeddedDisplayHost.tryConnect()
        }
    )
}

/**
 * A newly-created SurfaceView can reconnect to the long-lived X11 server before
 * Android has finished publishing its replacement Surface. In that narrow
 * window the embedded renderer is technically connected, but it can initially
 * show only the X root/cursor until a later client damage event arrives.
 *
 * Re-assert the viewport once, after both the X11 connection and Android Surface
 * are ready. The retry is bounded and never restarts the X server, container or
 * graphical session.
 */
private fun scheduleEmbeddedSurfaceResync(view: LorieView, attempt: Int = 0) {
    if (attempt >= 6) return

    val delayMs = when (attempt) {
        0 -> 60L
        1 -> 120L
        2 -> 240L
        else -> 400L
    }

    view.postDelayed({
        if (EmbeddedDisplayHost.getActiveView() !== view || !view.isAttachedToWindow) {
            return@postDelayed
        }

        if (!view.connected()) {
            EmbeddedDisplayHost.tryConnect()
            scheduleEmbeddedSurfaceResync(view, attempt + 1)
            return@postDelayed
        }

        if (!view.holder.surface.isValid) {
            scheduleEmbeddedSurfaceResync(view, attempt + 1)
            return@postDelayed
        }

        view.triggerCallback()
        view.postInvalidateOnAnimation()
    }, delayMs)
}

private val modifierKeyCodes = mapOf(
    "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
    "ALT" to KeyEvent.KEYCODE_ALT_LEFT,
    "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT,
    "META" to KeyEvent.KEYCODE_META_LEFT
)

private fun parseExtraKeys(config: String?): List<List<ExtraKeyButton>> {
    val requested = config?.takeIf { it.isNotBlank() }
        ?: TermuxX11ExtraKeys.DEFAULT_IVALUE_EXTRA_KEYS

    fun parse(value: String): List<List<ExtraKeyButton>> =
        ExtraKeysInfo(
            value,
            "default",
            ExtraKeysConstants.CONTROL_CHARS_ALIASES
        ).matrix.map { it.toList() }

    return runCatching { parse(requested) }
        .getOrElse {
            runCatching { parse(TermuxX11ExtraKeys.DEFAULT_IVALUE_EXTRA_KEYS) }
                .getOrDefault(emptyList())
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EmbeddedExtraKeysBar(
    config: String?,
    onOpenSettings: () -> Unit,
    onExitDisplay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val rows = remember(config) { parseExtraKeys(config) }
    val activeModifiers = remember { mutableStateMapOf<String, Boolean>() }

    fun setModifier(key: String, down: Boolean) {
        val keyCode = modifierKeyCodes[key] ?: return
        if (EmbeddedDisplayHost.sendKeyCode(keyCode, down)) {
            activeModifiers[key] = down
        }
    }

    fun sendSingle(key: String) {
        when (key) {
            "KEYBOARD" -> EmbeddedDisplayHost.toggleSoftKeyboard()
            "PREFERENCES", "DRAWER" -> onOpenSettings()
            "EXIT" -> onExitDisplay()
            "PASTE" -> {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = clipboard
                    ?.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                if (!text.isNullOrEmpty()) EmbeddedDisplayHost.sendText(text)
            }
            "ZOOM_IN" -> EmbeddedDisplayHost.adjustRendererZoom(25)
            "ZOOM_OUT" -> EmbeddedDisplayHost.adjustRendererZoom(-25)
            "ZOOM_RESET" -> EmbeddedDisplayHost.resetRendererZoom()
            "FN" -> Unit
            in modifierKeyCodes -> {
                val next = activeModifiers[key] != true
                setModifier(key, next)
            }
            else -> {
                val keyCode = ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS[key]
                if (keyCode != null) {
                    EmbeddedDisplayHost.tapKeyCode(keyCode)
                } else {
                    EmbeddedDisplayHost.sendText(key)
                }
            }
        }
    }

    fun execute(button: ExtraKeyButton) {
        if (!button.macro) {
            sendSingle(button.key)
            return
        }

        val parts = button.key.split(' ').filter { it.isNotBlank() }
        val macroModifiers = parts.filter { it in modifierKeyCodes }
        val temporaryModifiers = macroModifiers.filter { activeModifiers[it] != true }

        temporaryModifiers.forEach { setModifier(it, true) }
        parts
            .filterNot { it in modifierKeyCodes || it == "FN" }
            .forEach(::sendSingle)
        temporaryModifiers.asReversed().forEach { setModifier(it, false) }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeModifiers
                .filterValues { it }
                .keys
                .toList()
                .forEach { key ->
                    modifierKeyCodes[key]?.let {
                        EmbeddedDisplayHost.sendKeyCode(it, false)
                    }
                }
        }
    }

    if (rows.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                row.forEach { button ->
                    val active =
                        button.key in modifierKeyCodes && activeModifiers[button.key] == true

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 38.dp)
                            .combinedClickable(
                                onClick = { execute(button) },
                                onLongClick = { button.popup?.let(::execute) }
                            ),
                        shape = RoundedCornerShape(4.dp),
                        color = if (active) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color(0xFF161616)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(alpha = 0.16f)
                            }
                        )
                    ) {
                        Box(
                            modifier = Modifier.padding(
                                horizontal = 2.dp,
                                vertical = 8.dp
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = button.display,
                                color = if (active) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    Color.White
                                },
                                fontSize = 11.sp,
                                fontWeight = if (active) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Medium
                                },
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
