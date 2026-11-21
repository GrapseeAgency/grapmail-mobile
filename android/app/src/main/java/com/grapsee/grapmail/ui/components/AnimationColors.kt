package com.grapsee.grapmail.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import java.util.Locale

private val animationHex = Regex("""#([\da-fA-F]{8}|[\da-fA-F]{6})(?![\w-])""")
private val animationRgb = Regex("""(rgba?\(\s*)(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?=\s*[,\)])""")

/** Recolor blue artwork without changing its lightness, opacity or animation. */
internal fun themeAnimationHtml(html: String, accent: Color): String {
    val accentHsl = FloatArray(3)
    val colorHsl = FloatArray(3)
    ColorUtils.colorToHSL(accent.toArgb(), accentHsl)
    val baseHue = 215f
    val hueShift = accentHsl[0] - baseHue
    if (accentHsl[1] >= 0.2f && kotlin.math.abs(hueShift) < 12f) return html

    fun recolor(rgb: Int): Int? {
        ColorUtils.colorToHSL(rgb, colorHsl)
        if (colorHsl[0] !in 177f..257f || colorHsl[1] < 0.20f) return null
        colorHsl[0] = (colorHsl[0] + hueShift + 360f) % 360f
        colorHsl[1] *= if (accentHsl[1] < 0.20f) 0.35f else 1f
        return ColorUtils.HSLToColor(colorHsl) and 0xFFFFFF
    }

    val themedHex = animationHex.replace(html) { match ->
        val hex = match.groupValues[1]
        val color = recolor(hex.take(6).toInt(16)) ?: return@replace match.value
        "#" + String.format(Locale.ROOT, "%06x", color) + hex.drop(6)
    }
    return animationRgb.replace(themedHex) { match ->
        val (_, red, green, blue) = match.destructured
        val channels = listOf(red.toInt(), green.toInt(), blue.toInt())
        if (channels.any { it > 255 }) return@replace match.value
        val color = recolor((channels[0] shl 16) or (channels[1] shl 8) or channels[2])
            ?: return@replace match.value
        "${match.groupValues[1]}${(color shr 16) and 255},${(color shr 8) and 255},${color and 255}"
    }
}
