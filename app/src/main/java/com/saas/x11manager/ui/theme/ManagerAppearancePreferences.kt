package com.saas.x11manager.ui.theme

import android.content.Context

enum class ManagerThemeMode(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");

    companion object {
        fun fromName(name: String?): ManagerThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

data class ManagerAppearanceSettings(
    val themeMode: ManagerThemeMode = ManagerThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val amoledMode: Boolean = false,
    val palette: ThemePalette = ThemePalette.CATPPUCCIN
)

/** Manager-only UI preferences. These are intentionally separate from Lorie/X11 prefs. */
object ManagerAppearancePreferences {
    private const val STORE = "manager_appearance"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_AMOLED_MODE = "amoled_mode"
    private const val KEY_PALETTE = "palette"

    fun load(context: Context): ManagerAppearanceSettings {
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        return ManagerAppearanceSettings(
            themeMode = ManagerThemeMode.fromName(store.getString(KEY_THEME_MODE, null)),
            dynamicColor = store.getBoolean(KEY_DYNAMIC_COLOR, true),
            amoledMode = store.getBoolean(KEY_AMOLED_MODE, false),
            palette = ThemePalette.fromName(store.getString(KEY_PALETTE, ThemePalette.CATPPUCCIN.name) ?: ThemePalette.CATPPUCCIN.name)
        )
    }

    fun save(context: Context, settings: ManagerAppearanceSettings) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, settings.themeMode.name)
            .putBoolean(KEY_DYNAMIC_COLOR, settings.dynamicColor)
            .putBoolean(KEY_AMOLED_MODE, settings.amoledMode)
            .putString(KEY_PALETTE, settings.palette.name)
            .apply()
    }

    fun reset(context: Context): ManagerAppearanceSettings {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().clear().apply()
        return ManagerAppearanceSettings()
    }
}
