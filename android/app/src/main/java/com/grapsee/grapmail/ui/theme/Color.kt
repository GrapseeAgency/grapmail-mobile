package com.grapsee.grapmail.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Measured from the founder's design PNGs — see design/DESIGN_TOKENS.md.
data class GrapMailColors(
    // Light theme
    val bg: Color = Color(0xFFFBFCFE),
    val bgTinted: Color = Color(0xFFF7F9FC),
    val card: Color = Color(0xFFFFFFFF),
    val softFill: Color = Color(0xFFEFF5FD),
    val infoFill: Color = Color(0xFFE3ECFB),
    val border: Color = Color(0xFFE1E7F2),
    val borderSoft: Color = Color(0xFFD9E6F9),
    val primaryTop: Color = Color(0xFF206BFD),
    val primaryBottom: Color = Color(0xFF1560F5),
    val primarySolid: Color = Color(0xFF1A6DF0),
    val buttonDisabled: Color = Color(0xFFC4DBFD),
    val textPrimary: Color = Color(0xFF0F172A),
    val textSecondary: Color = Color(0xFF5B6B84),
    val textTertiary: Color = Color(0xFF94A3B8),
    val success: Color = Color(0xFF10B981),
    val error: Color = Color(0xFFEF4444),
    val stepInactive: Color = Color(0xFFDCE6F5),
    val onPrimary: Color = Color(0xFFFFFFFF),
    // Recovery/security sector (sampled from raw-designs/account/)
    val successChipBg: Color = Color(0xFFDCF8F4),
    val successInk: Color = Color(0xFF0B8F64),
    val warnChipBg: Color = Color(0xFFFDF0DE),
    val warnInk: Color = Color(0xFFC2620A),
    val cardAlt: Color = Color(0xFFF5F9FE),
    val otpFill: Color = Color(0xFFFFFFFF),
    // Dark theme
    val bgDark: Color = Color(0xFF070F18),
    val cardDark: Color = Color(0xFF0C182A),
    val inputFillDark: Color = Color(0xFF0D1521),
    val borderDark: Color = Color(0xFF1B2A44),
    val softFillDark: Color = Color(0xFF12233C),
    val buttonDisabledDark: Color = Color(0xFF1E3A66),
    val textPrimaryDark: Color = Color(0xFFF1F5F9),
    val textSecondaryDark: Color = Color(0xFF8FA3C0),
    val textTertiaryDark: Color = Color(0xFF5E7290),
    val successDark: Color = Color(0xFF34D399),
    val errorDark: Color = Color(0xFFF87171),
    val stepInactiveDark: Color = Color(0xFF1B2A44),
    val successChipBgDark: Color = Color(0xFF0A2A23),
    val successInkDark: Color = Color(0xFF34D399),
    val warnChipBgDark: Color = Color(0xFF2B1F0B),
    val warnInkDark: Color = Color(0xFFF5A623),
    val cardAltDark: Color = Color(0xFF0B1626),
    val otpFillDark: Color = Color(0xFF0A1220),
)

val LocalGrapMailColors = staticCompositionLocalOf { GrapMailColors() }
