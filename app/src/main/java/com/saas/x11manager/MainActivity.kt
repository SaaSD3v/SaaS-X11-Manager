package com.saas.x11manager

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saas.x11manager.ui.navigation.AppNavigation
import com.saas.x11manager.ui.screen.HomeViewModel
import com.saas.x11manager.ui.screen.PREF_FULLSCREEN
import com.saas.x11manager.ui.screen.publishLoriePreferenceChange
import com.saas.x11manager.ui.theme.ManagerAppearancePreferences
import com.saas.x11manager.ui.theme.ManagerAppearanceSettings
import com.saas.x11manager.ui.theme.ManagerThemeMode
import com.saas.x11manager.ui.theme.X11ManagerTheme
import com.termux.x11.EmbeddedDisplayHost

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    private lateinit var fallbackBackCallback: OnBackPressedCallback
    private var platformBackCallback: OnBackInvokedCallback? = null
    private var fullscreenExitDialogVisible by mutableStateOf(false)
    private var appearanceSettings by mutableStateOf(ManagerAppearanceSettings())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearanceSettings = ManagerAppearancePreferences.load(this)

        fallbackBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleManagedBack()
            }
        }
        onBackPressedDispatcher.addCallback(this, fallbackBackCallback)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { handleManagedBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                callback
            )
            platformBackCallback = callback
        }

        setContent {
            val systemDark = isSystemInDarkTheme()
            val useDarkTheme = when (appearanceSettings.themeMode) {
                ManagerThemeMode.SYSTEM -> systemDark
                ManagerThemeMode.LIGHT -> false
                ManagerThemeMode.DARK -> true
            }

            X11ManagerTheme(
                darkTheme = useDarkTheme,
                dynamicColor = appearanceSettings.dynamicColor,
                amoledMode = appearanceSettings.amoledMode,
                themePalette = appearanceSettings.palette
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppNavigation(
                            viewModel = viewModel,
                            appearanceSettings = appearanceSettings,
                            onAppearanceSettingsChange = ::updateAppearance,
                            onResetAppearance = ::resetAppearance
                        )
                    }

                    if (fullscreenExitDialogVisible) {
                        AlertDialog(
                            onDismissRequest = { fullscreenExitDialogVisible = false },
                            icon = {
                                Icon(
                                    Icons.Default.FullscreenExit,
                                    contentDescription = null
                                )
                            },
                            title = { Text("Exit fullscreen?") },
                            text = {
                                Text(
                                    "Return to the X11 monitor controls while keeping " +
                                        "the monitor and container running?"
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        fullscreenExitDialogVisible = false
                                        exitManagedX11Fullscreen()
                                    }
                                ) {
                                    Text("Exit fullscreen")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { fullscreenExitDialogVisible = false }
                                ) {
                                    Text("Stay fullscreen")
                                }
                            },
                            shape = RoundedCornerShape(26.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 6.dp
                        )
                    }
                }
            }
        }
    }

    private fun updateAppearance(settings: ManagerAppearanceSettings) {
        if (appearanceSettings == settings) return
        appearanceSettings = settings
        ManagerAppearancePreferences.save(this, settings)
    }

    private fun resetAppearance() {
        appearanceSettings = ManagerAppearancePreferences.reset(this)
    }

    /**
     * LorieView is focusable and can consume hardware key events before Compose
     * BackHandler sees them. Intercept Android Back at the Activity boundary so
     * a focused X11 viewport never forwards the system Back key to the guest.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && isManagedX11Fullscreen()) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                showFullscreenExitConfirmation()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleManagedBack() {
        if (isManagedX11Fullscreen()) {
            showFullscreenExitConfirmation()
            return
        }

        fallbackBackCallback.isEnabled = false
        try {
            onBackPressedDispatcher.onBackPressed()
        } finally {
            fallbackBackCallback.isEnabled = true
        }
    }

    private fun isManagedX11Fullscreen(): Boolean = runCatching {
        EmbeddedDisplayHost.getPrefs(this).get().getBoolean(PREF_FULLSCREEN, false)
    }.getOrDefault(false)

    private fun showFullscreenExitConfirmation() {
        if (fullscreenExitDialogVisible || isFinishing || isDestroyed) return
        fullscreenExitDialogVisible = true
    }

    private fun exitManagedX11Fullscreen() {
        val store = EmbeddedDisplayHost.getPrefs(this).get()
        store.edit().putBoolean(PREF_FULLSCREEN, false).apply()
        publishLoriePreferenceChange(this, PREF_FULLSCREEN)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshRuntimeState()
    }

    override fun onDestroy() {
        fullscreenExitDialogVisible = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            platformBackCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        }
        platformBackCallback = null
        super.onDestroy()
    }
}
