package com.grapsee.grapmail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val swipeArchive: Color, val swipeTrash: Color,
    val star: Color,
    // The text caret. It is set on every field explicitly because a caret
    // inherits no visibility from anything else on the screen: the audit found
    // the default one black on the dark compose bar. Light ink in the dark
    // theme, dark ink in the light one — the one color whose job is contrast
    // against the field's own fill.
    val cursor: Color,
    val tabBadgeAll: Color = Color(0xFF1560C7),
    val tabBadgeStarred: Color = Color(0xFF975619),
    val tabTintSent: Color = Color(0xFFAC63FE),
    val tabBadgeSent: Color = Color(0xFF6928CC),
    val unreadBadge: Color = Color(0xFFBC2828),
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
        swipeArchive = Color(0xFF1A6DF0), swipeTrash = Color(0xFFDC2626),
        star = Color(0xFFF5A623),
        cursor = Color(0xFF1A6DF0),
    )
}

@Composable
fun GrapMailTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val dark = darkTheme
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
        swipeArchive = base.swipeArchiveDark, swipeTrash = base.swipeTrashDark,
        star = base.starDark,
        cursor = base.textPrimaryDark,
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
        swipeArchive = base.swipeArchive, swipeTrash = base.swipeTrash,
        star = base.star,
        cursor = base.primarySolid,
    )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
                primary = palette.primarySolid,
                onPrimary = palette.onPrimary,
                primaryContainer = palette.infoFill,
                onPrimaryContainer = palette.textPrimary,
                secondary = palette.textSecondary,
                secondaryContainer = palette.softFill,
                onSecondaryContainer = palette.textPrimary,
                background = palette.bg,
                onBackground = palette.textPrimary,
                surface = palette.card,
                surfaceTint = Color.Transparent,
                onSurface = palette.textPrimary,
                surfaceVariant = palette.softFill,
                onSurfaceVariant = palette.textSecondary,
                surfaceContainerLowest = palette.bg,
                surfaceContainerLow = palette.cardAlt,
                surfaceContainer = palette.card,
                surfaceContainerHigh = palette.cardAlt,
                surfaceContainerHighest = palette.softFill,
                outline = palette.border,
                outlineVariant = palette.borderSoft,
                error = palette.error,
            ),
            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(13.dp)),
            typography = GrapMailTypography,
            content = content,
        )
    }
}

/**
 * The one colour painted behind the system bars.
 *
 * The activity root paints the theme background behind the status and gesture
 * bars, and a screen cannot paint there itself — it is already inset. A screen
 * that is deliberately dark on both themes (the onboarding's cinematic scenes)
 * therefore states its surface here, and the root adopts it for as long as that
 * screen is on stage. Everything else leaves it null and keeps the theme.
 */
object ImmersiveSurface {
    val color = mutableStateOf<Color?>(null)
}

@Composable
fun ScreenBackground(color: Color, content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(color) {
        val previous = ImmersiveSurface.color.value
        ImmersiveSurface.color.value = color
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightIcons = color.luminance() > 0.5f
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = lightIcons
        controller?.isAppearanceLightNavigationBars = lightIcons
        onDispose {
            ImmersiveSurface.color.value = previous
            if (oldStatus != null) controller?.isAppearanceLightStatusBars = oldStatus
            if (oldNav != null) controller?.isAppearanceLightNavigationBars = oldNav
        }
    }
    content()
}
