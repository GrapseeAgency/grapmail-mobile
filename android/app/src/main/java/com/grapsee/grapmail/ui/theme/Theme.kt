package com.grapsee.grapmail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Type scale from DESIGN_TOKENS.md. Platform sans (Roboto) is the
// Android-correct equivalent of the design's Inter-like faces.
val H1 = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
val H2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
val Title = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
val Body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
val BodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
val Caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
val CaptionMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
val Micro = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

private val GrapMailTypography = Typography()

/**
 * Live palette for the current theme. Every screen resolves through this so
 * light/dark match the founder's paired PNGs exactly.
 */
data class Palette(
    val bg: Color, val bgTinted: Color, val card: Color, val softFill: Color, val infoFill: Color,
    val border: Color, val borderSoft: Color,
    val primaryTop: Color, val primaryBottom: Color, val primarySolid: Color,
    val buttonDisabled: Color, val onPrimary: Color,
    val textPrimary: Color, val textSecondary: Color, val textTertiary: Color,
    val success: Color, val error: Color, val stepInactive: Color, val inputFill: Color,
    val successChipBg: Color, val successInk: Color,
    val warnChipBg: Color, val warnInk: Color,
    val cardAlt: Color, val otpFill: Color,
)

val LocalPalette = staticCompositionLocalOf {
    Palette(
        bg = Color(0xFFFBFCFE), bgTinted = Color(0xFFF7F9FC), card = Color.White, softFill = Color(0xFFEFF5FD),
        infoFill = Color(0xFFE3ECFB), border = Color(0xFFE1E7F2), borderSoft = Color(0xFFD9E6F9),
        primaryTop = Color(0xFF206BFD), primaryBottom = Color(0xFF1560F5), primarySolid = Color(0xFF1A6DF0),
        buttonDisabled = Color(0xFFC4DBFD), onPrimary = Color.White,
        textPrimary = Color(0xFF0F172A), textSecondary = Color(0xFF5B6B84), textTertiary = Color(0xFF94A3B8),
        success = Color(0xFF10B981), error = Color(0xFFEF4444), stepInactive = Color(0xFFDCE6F5),
        inputFill = Color.White,
        successChipBg = Color(0xFFDCF8F4), successInk = Color(0xFF0B8F64),
        warnChipBg = Color(0xFFFDF0DE), warnInk = Color(0xFFC2620A),
        cardAlt = Color(0xFFF5F9FE), otpFill = Color.White,
    )
}

@Composable
fun GrapMailTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val base = LocalGrapMailColors.current
    val palette = if (dark) Palette(
        bg = base.bgDark, bgTinted = base.bgDark, card = base.cardDark, softFill = base.softFillDark, infoFill = base.softFillDark,
        border = base.borderDark, borderSoft = base.borderDark,
        primaryTop = base.primaryTop, primaryBottom = base.primaryBottom, primarySolid = base.primarySolid,
        buttonDisabled = base.buttonDisabledDark, onPrimary = base.onPrimary,
        textPrimary = base.textPrimaryDark, textSecondary = base.textSecondaryDark, textTertiary = base.textTertiaryDark,
        success = base.successDark, error = base.errorDark, stepInactive = base.stepInactiveDark,
        inputFill = base.inputFillDark,
        successChipBg = base.successChipBgDark, successInk = base.successInkDark,
        warnChipBg = base.warnChipBgDark, warnInk = base.warnInkDark,
        cardAlt = base.cardAltDark, otpFill = base.otpFillDark,
    ) else Palette(
        bg = base.bg, bgTinted = base.bgTinted, card = base.card, softFill = base.softFill, infoFill = base.infoFill,
        border = base.border, borderSoft = base.borderSoft,
        primaryTop = base.primaryTop, primaryBottom = base.primaryBottom, primarySolid = base.primarySolid,
        buttonDisabled = base.buttonDisabled, onPrimary = base.onPrimary,
        textPrimary = base.textPrimary, textSecondary = base.textSecondary, textTertiary = base.textTertiary,
        success = base.success, error = base.error, stepInactive = base.stepInactive,
        inputFill = base.card,
        successChipBg = base.successChipBg, successInk = base.successInk,
        warnChipBg = base.warnChipBg, warnInk = base.warnInk,
        cardAlt = base.cardAlt, otpFill = base.otpFill,
    )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                primary = base.primarySolid,
                background = palette.bg,
                surface = palette.card,
            ),
            typography = GrapMailTypography,
            content = content,
        )
    }
}
