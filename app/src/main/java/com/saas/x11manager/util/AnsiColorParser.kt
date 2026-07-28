package com.saas.x11manager.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

object AnsiColorParser {
    private val ansiColors = mapOf(
        30 to Color(0xFF000000), 31 to Color(0xFFCD3131), 32 to Color(0xFF0DBC79),
        33 to Color(0xFFE5E510), 34 to Color(0xFF2472C8), 35 to Color(0xFFBC3FBC),
        36 to Color(0xFF11A8CD), 37 to Color(0xFFE5E5E5),
        90 to Color(0xFF767676), 91 to Color(0xFFFF6B6B), 92 to Color(0xFF51CF66),
        93 to Color(0xFFFFD93D), 94 to Color(0xFF74C0FC), 95 to Color(0xFFFF8CC8),
        96 to Color(0xFF66D9EF), 97 to Color(0xFFFFFFFF)
    )

    private val ansiBgColors = mapOf(
        40 to Color(0xFF000000), 41 to Color(0xFFCD3131), 42 to Color(0xFF0DBC79),
        43 to Color(0xFFE5E510), 44 to Color(0xFF2472C8), 45 to Color(0xFFBC3FBC),
        46 to Color(0xFF11A8CD), 47 to Color(0xFFE5E5E5),
        100 to Color(0xFF767676), 101 to Color(0xFFFF6B6B), 102 to Color(0xFF51CF66),
        103 to Color(0xFFFFD93D), 104 to Color(0xFF74C0FC), 105 to Color(0xFFFF8CC8),
        106 to Color(0xFF66D9EF), 107 to Color(0xFFFFFFFF)
    )

    fun parseAnsi(text: String, defaultColor: Color): AnnotatedString {
        if (!text.contains("\u001B[")) return AnnotatedString(text)

        return buildAnnotatedString {
            var currentColor: Color? = null
            var currentBgColor: Color? = null
            var isBold = false
            var isDim = false
            var isItalic = false
            var isUnderline = false
            var currentIndex = 0
            val ansiPattern = Regex("""\u001B\[([0-9;]*)m""")

            for (match in ansiPattern.findAll(text)) {
                if (match.range.first > currentIndex) {
                    val segment = text.substring(currentIndex, match.range.first)
                    if (segment.isNotEmpty()) {
                        val start = length
                        append(segment)
                        addStyle(createSpanStyle(currentColor ?: defaultColor, currentBgColor, isBold, isDim, isItalic, isUnderline), start, length)
                    }
                }
                val codes = match.groupValues[1]
                if (codes.isEmpty()) {
                    currentColor = null; currentBgColor = null; isBold = false; isDim = false; isItalic = false; isUnderline = false
                } else {
                    for (code in codes.split(Regex("[;:]")).mapNotNull { it.toIntOrNull() }) {
                        when (code) {
                            0 -> { currentColor = null; currentBgColor = null; isBold = false; isDim = false; isItalic = false; isUnderline = false }
                            1 -> isBold = true
                            2 -> isDim = true
                            3 -> isItalic = true
                            4 -> isUnderline = true
                            22 -> { isBold = false; isDim = false }
                            23 -> isItalic = false
                            24 -> isUnderline = false
                            in 30..37 -> currentColor = ansiColors[code]
                            in 90..97 -> currentColor = ansiColors[code]
                            39 -> currentColor = null
                            in 40..47 -> currentBgColor = ansiBgColors[code]
                            in 100..107 -> currentBgColor = ansiBgColors[code]
                            49 -> currentBgColor = null
                        }
                    }
                    if (isBold && currentColor != null) {
                        currentColor = when (currentColor) {
                            ansiColors[30] -> ansiColors[90]; ansiColors[31] -> ansiColors[91]
                            ansiColors[32] -> ansiColors[92]; ansiColors[33] -> ansiColors[93]
                            ansiColors[34] -> ansiColors[94]; ansiColors[35] -> ansiColors[95]
                            ansiColors[36] -> ansiColors[96]; ansiColors[37] -> ansiColors[97]
                            else -> currentColor
                        }
                    }
                }
                currentIndex = match.range.last + 1
            }
            if (currentIndex < text.length) {
                val segment = text.substring(currentIndex)
                if (segment.isNotEmpty()) {
                    val start = length
                    append(segment)
                    addStyle(createSpanStyle(currentColor ?: defaultColor, currentBgColor, isBold, isDim, isItalic, isUnderline), start, length)
                }
            }
        }
    }

    private fun createSpanStyle(color: Color, bgColor: Color?, bold: Boolean, dim: Boolean, italic: Boolean, underline: Boolean) = SpanStyle(
        color = if (dim) color.copy(alpha = 0.6f) else color,
        background = bgColor ?: androidx.compose.ui.graphics.Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
        textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null
    )

    fun stripAnsi(text: String): String = text.replace(Regex("""\u001B\[([0-9;]*)m"""), "")
}
