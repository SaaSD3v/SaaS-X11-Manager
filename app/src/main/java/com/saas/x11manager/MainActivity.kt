package com.saas.x11manager

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.saas.x11manager.ui.navigation.AppNavigation
import com.saas.x11manager.ui.screen.HomeViewModel
import com.saas.x11manager.ui.screen.PREF_FULLSCREEN
import com.saas.x11manager.ui.screen.publishLoriePreferenceChange
import com.saas.x11manager.ui.theme.X11ManagerTheme
import com.termux.x11.EmbeddedDisplayHost

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    private lateinit var fallbackBackCallback: OnBackPressedCallback
    private var platformBackCallback: OnBackInvokedCallback? = null
    private var fullscreenExitDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        enableEdgeToEdge()
        setContent {
            X11ManagerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(viewModel = viewModel)
                }
            }
        }
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

        // Delegate normal navigation to the Compose callbacks/fallback while
        // keeping this Activity-level safety net out of its own dispatch path.
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
        if (fullscreenExitDialog?.isShowing == true || isFinishing || isDestroyed) return

        fullscreenExitDialog = AlertDialog.Builder(this)
            .setTitle("Exit fullscreen?")
            .setMessage("Return to the X11 monitor controls while keeping the monitor and container running?")
            .setPositiveButton("Exit fullscreen") { _, _ ->
                exitManagedX11Fullscreen()
            }
            .setNegativeButton("Stay fullscreen", null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { fullscreenExitDialog = null }
                dialog.show()
            }
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
        fullscreenExitDialog?.dismiss()
        fullscreenExitDialog = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            platformBackCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        }
        platformBackCallback = null
        super.onDestroy()
    }
}
