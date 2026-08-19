package com.saas.x11manager.ui.screen

import android.app.Activity
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.termux.x11.LorieView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun ManagedX11WindowEffects(
    activity: Activity?,
    store: SharedPreferences,
    connected: Boolean,
    fullscreen: Boolean
) {
    var forceOrientation by remember(store) {
        mutableStateOf(store.getString("forceOrientation", "auto") ?: "auto")
    }
    var useDisplayCutoutArea by remember(store) {
        mutableStateOf(store.getBoolean("hideCutout", false))
    }
    var idleTimeoutMode by remember(store) {
        mutableStateOf(store.getString("screenIdleTimeout", "system") ?: "system")
    }

    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "forceOrientation" -> {
                    forceOrientation = store.getString("forceOrientation", "auto") ?: "auto"
                }
                "hideCutout" -> {
                    useDisplayCutoutArea = store.getBoolean("hideCutout", false)
                }
                "screenIdleTimeout" -> {
                    idleTimeoutMode = store.getString("screenIdleTimeout", "system") ?: "system"
                }
            }
        }
        store.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val originalRequestedOrientation = remember(activity) { activity?.requestedOrientation }
    val originalKeepScreenOn = remember(activity) {
        ((activity?.window?.attributes?.flags ?: 0)
            and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
    }

    DisposableEffect(activity) {
        onDispose {
            if (activity != null) {
                originalRequestedOrientation?.let { activity.requestedOrientation = it }
                setKeepScreenOn(activity, originalKeepScreenOn)
            }
        }
    }

    LaunchedEffect(activity, forceOrientation) {
        if (activity == null) return@LaunchedEffect
        val requested = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode
        ) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ManagedX11WindowPolicy.requestedOrientation(forceOrientation)
        }
        if (activity.requestedOrientation != requested) {
            activity.requestedOrientation = requested
        }
    }

    DisposableEffect(fullscreen, activity, useDisplayCutoutArea) {
        val window = activity?.window
        if (!fullscreen || window == null) {
            onDispose { }
        } else {
            val oldCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode
            } else {
                null
            }
            val controller = WindowCompat.getInsetsController(window, window.decorView)

            WindowCompat.setDecorFitsSystemWindows(window, false)
            ManagedX11WindowPolicy
                .cutoutMode(useDisplayCutoutArea, Build.VERSION.SDK_INT)
                ?.let { mode ->
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode = mode
                    window.attributes = attrs
                }

            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())

            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && oldCutoutMode != null) {
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode = oldCutoutMode
                    window.attributes = attrs
                }
            }
        }
    }

    LaunchedEffect(activity, connected, idleTimeoutMode) {
        if (activity == null) return@LaunchedEffect

        fun restoreKeepScreenOn() {
            setKeepScreenOn(activity, originalKeepScreenOn)
        }

        if (!connected) {
            restoreKeepScreenOn()
            return@LaunchedEffect
        }

        val systemTimeoutMillis = Settings.System.getInt(
            activity.contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            0
        ).toLong()
        val configuredKeepWindow = ManagedX11WindowPolicy.keepScreenOnWindowMillis(
            idleTimeoutMode,
            systemTimeoutMillis
        )

        if (configuredKeepWindow == null) {
            restoreKeepScreenOn()
            return@LaunchedEffect
        }
        if (configuredKeepWindow == Long.MAX_VALUE) {
            setKeepScreenOn(activity, true)
            return@LaunchedEffect
        }
        val keepWindowMillis: Long = configuredKeepWindow

        LorieView.markUserActivity()
        while (isActive) {
            val nowMillis = System.nanoTime() / 1_000_000L
            val lastInputMillis = LorieView.getLastInputTimestamp()
            val elapsedMillis = (nowMillis - lastInputMillis).coerceAtLeast(0L)
            val remainingMillis = keepWindowMillis - elapsedMillis

            if (remainingMillis > 0L) {
                setKeepScreenOn(activity, true)
                delay(remainingMillis.coerceIn(250L, 5_000L))
            } else {
                restoreKeepScreenOn()
                delay(1_000L)
            }
        }
    }
}

private fun setKeepScreenOn(activity: Activity, enabled: Boolean) {
    if (enabled) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
