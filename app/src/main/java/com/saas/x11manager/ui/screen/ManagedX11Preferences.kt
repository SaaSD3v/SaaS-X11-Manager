package com.saas.x11manager.ui.screen

import android.content.Context
import android.content.SharedPreferences

internal const val PREF_SHOW_ADDITIONAL_KEYS = "showAdditionalKbd"
internal const val PREF_ADDITIONAL_KEYS_VISIBLE = "additionalKbdVisible"
internal const val PREF_SHOW_IME_WITH_EXTERNAL_KEYBOARD = "showIMEWhileExternalConnected"
internal const val PREF_FULLSCREEN = "fullscreen"
internal const val PREF_MANAGER_DEFAULTS_INITIALIZED = "saasManagedX11DefaultsInitialized"

/**
 * Manager-owned first-run defaults for the embedded X11 experience.
 *
 * Upstream Lorie defaults the additional key bar and external-keyboard IME to
 * enabled. The Manager intentionally starts both opt-in so Screen stays clean
 * until the user enables those controls from Configuration. Once initialized,
 * this function never overwrites user choices.
 */
internal fun ensureManagedX11Defaults(
    context: Context,
    store: SharedPreferences
) {
    if (store.getBoolean(PREF_MANAGER_DEFAULTS_INITIALIZED, false)) return

    store.edit()
        .putBoolean(PREF_SHOW_ADDITIONAL_KEYS, false)
        .putBoolean(PREF_ADDITIONAL_KEYS_VISIBLE, false)
        .putBoolean(PREF_SHOW_IME_WITH_EXTERNAL_KEYBOARD, false)
        .putBoolean(PREF_FULLSCREEN, false)
        .putBoolean(PREF_MANAGER_DEFAULTS_INITIALIZED, true)
        .apply()

    publishLoriePreferenceChange(context, PREF_SHOW_ADDITIONAL_KEYS)
    publishLoriePreferenceChange(context, PREF_ADDITIONAL_KEYS_VISIBLE)
    publishLoriePreferenceChange(context, PREF_SHOW_IME_WITH_EXTERNAL_KEYBOARD)
    publishLoriePreferenceChange(context, PREF_FULLSCREEN)
}
