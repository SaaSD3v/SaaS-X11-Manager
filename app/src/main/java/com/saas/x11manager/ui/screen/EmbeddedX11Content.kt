package com.saas.x11manager.ui.screen

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.saas.x11manager.util.LoaderStatus
import com.termux.x11.EmbeddedDisplayHost
import com.termux.x11.LoriePreferences
import com.termux.x11.LorieView
import com.termux.x11.extrakeys.ExtraKeyButton
import com.termux.x11.extrakeys.ExtraKeysConstants
import com.termux.x11.extrakeys.ExtraKeysInfo
import com.termux.x11.utils.TermuxX11ExtraKeys

internal const val ACTION_LORIE_PREFERENCES_CHANGED = "com.termux.x11.ACTION_PREFERENCES_CHANGED"

internal fun publishLoriePreferenceChange(context: Context, key: String) {
    context.sendBroadcast(Intent(ACTION_LORIE_PREFERENCES_CHANGED).apply {
        putExtra("key", key)
        putExtra("fromBroadcast", true)
        setPackage(context.packageName)
    })
}

internal fun openLorieSettings(context: Context) {
    context.startActivity(Intent(context, LoriePreferences::class.java).apply {
        action = Intent.ACTION_MAIN
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

@Composable
internal fun EmbeddedX11Surface(
    modifier: Modifier = Modifier,
    onConnectionChanged: (Boolean) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            LorieView(context).apply {
                setZOrderOnTop(false)
                setZOrderMediaOverlay(false)
                setCallback { screenWidth, screenHeight, inputTransform ->
                    EmbeddedDisplayHost.updateInputTransform(
                        this,
                        screenWidth,
                        screenHeight,
                        inputTransform
                    )
                    onConnectionChanged(connected())
                }
                setOnTouchListener { _, event ->
                    EmbeddedDisplayHost.handleTouch(this, event)
                }
                setOnKeyListener { _, _, event ->
                    connected() && EmbeddedDisplayHost.handleKey(this, event)
                }
                requestFocus()
                EmbeddedDisplayHost.tryConnect()
            }
        },
        update = { view ->
            val connected = view.connected()
            onConnectionChanged(connected)
            if (!connected) EmbeddedDisplayHost.tryConnect()
        }
    )
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
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
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
                if (keyCode != null) EmbeddedDisplayHost.tapKeyCode(keyCode)
                else EmbeddedDisplayHost.sendText(key)
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
        parts.filterNot { it in modifierKeyCodes || it == "FN" }.forEach(::sendSingle)
        temporaryModifiers.asReversed().forEach { setModifier(it, false) }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeModifiers.filterValues { it }.keys.toList().forEach { key ->
                modifierKeyCodes[key]?.let { EmbeddedDisplayHost.sendKeyCode(it, false) }
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
                    val active = button.key in modifierKeyCodes && activeModifiers[button.key] == true
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 38.dp)
                            .combinedClickable(
                                onClick = { execute(button) },
                                onLongClick = { button.popup?.let(::execute) }
                            ),
                        shape = RoundedCornerShape(4.dp),
                        color = if (active) MaterialTheme.colorScheme.primaryContainer
                        else Color(0xFF161616),
                        border = BorderStroke(
                            1.dp,
                            if (active) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.16f)
                        )
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = button.display,
                                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FullscreenDisplayScreen(
    viewModel: HomeViewModel,
    onExitFullscreen: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val serverStatus by viewModel.loaderStatus.collectAsState()
    val prefs = remember(context) { EmbeddedDisplayHost.getPrefs(context) }
    var surfaceConnected by remember { mutableStateOf(false) }
    var showAdditionalKbd by remember { mutableStateOf(prefs.showAdditionalKbd.get()) }
    var additionalKbdVisible by remember { mutableStateOf(prefs.additionalKbdVisible.get()) }
    var extraKeysConfig by remember { mutableStateOf(prefs.extra_keys_config.get()) }

    fun exitFullscreen() {
        prefs.fullscreen.put(false)
        publishLoriePreferenceChange(context, "fullscreen")
        onExitFullscreen()
    }

    DisposableEffect(prefs) {
        val store = prefs.get()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "showAdditionalKbd" -> showAdditionalKbd = prefs.showAdditionalKbd.get()
                "additionalKbdVisible" -> additionalKbdVisible = prefs.additionalKbdVisible.get()
                "extra_keys_config" -> extraKeysConfig = prefs.extra_keys_config.get()
                "fullscreen" -> if (!prefs.fullscreen.get()) onExitFullscreen()
            }
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    DisposableEffect(activity) {
        val window = activity?.window
        val oldCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.attributes?.layoutInDisplayCutoutMode
        } else null
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }

        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                window.attributes = attrs
            }
        }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && oldCutoutMode != null) {
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode = oldCutoutMode
                    window.attributes = attrs
                }
            }
        }
    }

    BackHandler(onBack = ::exitFullscreen)

    LaunchedEffect(serverStatus) {
        if (serverStatus != LoaderStatus.Running) exitFullscreen()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                EmbeddedX11Surface(
                    modifier = Modifier.fillMaxSize(),
                    onConnectionChanged = { surfaceConnected = it }
                )
                if (!surfaceConnected) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            if (showAdditionalKbd && additionalKbdVisible) {
                EmbeddedExtraKeysBar(
                    config = extraKeysConfig,
                    onOpenSettings = { openLorieSettings(context) },
                    onExitDisplay = ::exitFullscreen
                )
            }
        }

        Popup(
            alignment = Alignment.TopEnd,
            properties = PopupProperties(focusable = false)
        ) {
            Surface(
                modifier = Modifier.padding(8.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.72f),
                shadowElevation = 6.dp
            ) {
                Row {
                    IconButton(onClick = { openLorieSettings(context) }) {
                        Icon(Icons.Default.Settings, contentDescription = "X11 settings", tint = Color.White)
                    }
                    IconButton(onClick = ::exitFullscreen) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = "Exit fullscreen", tint = Color.White)
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
