package com.saas.x11manager.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.saas.x11manager.util.LoaderStatus
import com.termux.x11.EmbeddedDisplayHost

@Composable
fun ManagedFullscreenDisplayScreen(
    viewModel: HomeViewModel,
    onExitFullscreen: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findHostActivity() }
    val serverStatus by viewModel.loaderStatus.collectAsState()
    val prefs = remember(context) { EmbeddedDisplayHost.getPrefs(context) }
    val store = remember(prefs) { prefs.get() }

    var surfaceConnected by remember { mutableStateOf(false) }
    var additionalKeysEnabled by remember { mutableStateOf(store.getBoolean("showAdditionalKbd", true)) }
    var additionalKeysVisible by remember { mutableStateOf(store.getBoolean("additionalKbdVisible", false)) }
    var extraKeysConfig by remember { mutableStateOf(store.getString("extra_keys_config", null)) }

    fun exitFullscreen() {
        store.edit().putBoolean("fullscreen", false).apply()
        publishLoriePreferenceChange(context, "fullscreen")
        onExitFullscreen()
    }

    fun toggleAdditionalKeys() {
        additionalKeysVisible = !additionalKeysVisible
        store.edit().putBoolean("additionalKbdVisible", additionalKeysVisible).apply()
        publishLoriePreferenceChange(context, "additionalKbdVisible")
    }

    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "showAdditionalKbd" -> {
                    additionalKeysEnabled = store.getBoolean("showAdditionalKbd", true)
                    if (!additionalKeysEnabled) additionalKeysVisible = false
                }
                "additionalKbdVisible" -> additionalKeysVisible = store.getBoolean("additionalKbdVisible", false)
                "extra_keys_config" -> extraKeysConfig = store.getString("extra_keys_config", null)
                "fullscreen" -> if (!store.getBoolean("fullscreen", false)) onExitFullscreen()
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
            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && oldCutoutMode != null) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode = oldCutoutMode
                window.attributes = attrs
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
        Column(Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                EmbeddedX11Surface(
                    modifier = Modifier.fillMaxSize(),
                    onConnectionChanged = { surfaceConnected = it }
                )
                if (!surfaceConnected) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            if (additionalKeysEnabled && additionalKeysVisible) {
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
                    if (additionalKeysEnabled) {
                        IconButton(onClick = ::toggleAdditionalKeys) {
                            Icon(
                                Icons.Default.Keyboard,
                                contentDescription = "Additional key bar",
                                tint = if (additionalKeysVisible) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                    }
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

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
