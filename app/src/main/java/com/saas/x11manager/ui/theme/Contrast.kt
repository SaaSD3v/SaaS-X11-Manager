package com.saas.x11manager.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** Contrast after Android's sRGB quantization and any foreground transparency. */
internal fun Color.contrastAgainst(background: Color): Float {
    val foregroundLuminance = Color(compositeOver(background).toArgb()).luminance()
    val backgroundLuminance = Color(background.toArgb()).luminance()
    return (maxOf(foregroundLuminance, backgroundLuminance) + 0.05f) /
        (minOf(foregroundLuminance, backgroundLuminance) + 0.05f)
}

/** Keep the accent when readable; otherwise move only as far toward black/white as needed. */
internal fun Color.readableOn(background: Color, minimum: Float = 4.6f): Color {
    if (contrastAgainst(background) >= minimum) return this
    val target = if (Color.Black.contrastAgainst(background) > Color.White.contrastAgainst(background)) {
        Color.Black
    } else Color.White
    val start = compositeOver(background)
    var low = 0f
    var high = 1f
    repeat(16) {
        val middle = (low + high) / 2f
        if (lerp(start, target, middle).contrastAgainst(background) >= minimum) high = middle
        else low = middle
    }
    return lerp(start, target, high)
}
