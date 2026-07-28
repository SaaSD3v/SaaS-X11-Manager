package com.saas.x11manager.ui.theme

import androidx.compose.ui.graphics.Color

val PRIMARY = Color(0xFF8AADF4)
val PRIMARY_LIGHT = Color(0xFFB7BDF8)
val SECONDARY_LIGHT = Color(0xFFA6DA95)
val PRIMARY_DARK = Color(0xFF7DC4E4)
val SECONDARY_DARK = Color(0xFFF5BDE6)
val AMOLED_BLACK = Color(0xFF000000)
val GREEN = Color(0xFF4CAF50)
val RED = Color(0xFFF44336)
val YELLOW = Color(0xFFFFEB3B)
val ORANGE = Color(0xFFFF9800)

enum class ThemePalette(
    val displayName: String,
    val primaryLight: Color,
    val secondaryLight: Color,
    val tertiaryLight: Color,
    val primaryDark: Color,
    val secondaryDark: Color,
    val tertiaryDark: Color
) {
    CATPPUCCIN(
        displayName = "Catppuccin",
        primaryLight = Color(0xFF8AADF4),
        secondaryLight = Color(0xFFB7BDF8),
        tertiaryLight = Color(0xFFA6DA95),
        primaryDark = Color(0xFF7DC4E4),
        secondaryDark = Color(0xFFF5BDE6),
        tertiaryDark = Color(0xFFA6DA95)
    ),
    OCEAN(
        displayName = "Ocean",
        primaryLight = Color(0xFF0277BD),
        secondaryLight = Color(0xFF00ACC1),
        tertiaryLight = Color(0xFF26A69A),
        primaryDark = Color(0xFF4FC3F7),
        secondaryDark = Color(0xFF4DD0E1),
        tertiaryDark = Color(0xFF80CBC4)
    ),
    FOREST(
        displayName = "Forest",
        primaryLight = Color(0xFF2E7D32),
        secondaryLight = Color(0xFF558B2F),
        tertiaryLight = Color(0xFF8D6E63),
        primaryDark = Color(0xFF81C784),
        secondaryDark = Color(0xFFA5D6A7),
        tertiaryDark = Color(0xFFBCAAA4)
    ),
    SUNSET(
        displayName = "Sunset",
        primaryLight = Color(0xFFD84315),
        secondaryLight = Color(0xFFF4511E),
        tertiaryLight = Color(0xFFFFB300),
        primaryDark = Color(0xFFFF8A65),
        secondaryDark = Color(0xFFFF8A80),
        tertiaryDark = Color(0xFFFFD54F)
    ),
    AMETHYST(
        displayName = "Amethyst",
        primaryLight = Color(0xFF6A1B9A),
        secondaryLight = Color(0xFF8E24AA),
        tertiaryLight = Color(0xFFAD1457),
        primaryDark = Color(0xFFCE93D8),
        secondaryDark = Color(0xFFBA68C8),
        tertiaryDark = Color(0xFFF48FB1)
    ),
    SAKURA(
        displayName = "Sakura",
        primaryLight = Color(0xFFD81B60),
        secondaryLight = Color(0xFFEC407A),
        tertiaryLight = Color(0xFF7E57C2),
        primaryDark = Color(0xFFF48FB1),
        secondaryDark = Color(0xFFF8BBD0),
        tertiaryDark = Color(0xFFB39DDB)
    );

    companion object {
        fun fromName(name: String): ThemePalette =
            entries.find { it.name == name } ?: CATPPUCCIN
    }
}
