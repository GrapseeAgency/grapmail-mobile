package com.grapsee.grapmail.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.MailDisplayPrefs

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

/** The light token values, the defaults for every Round 8 field below. */
private val LightTokens = GrapMailColors()

/**
 * Live palette for the current theme. Every screen resolves through this so
 * light/dark match the founder's paired PNGs exactly.
 *
 * The fields after `unreadBadge` are new in Round 8 (spec §1.2–§1.6). They
 * have defaults (the light values), so any code that builds a Palette by
 * hand still compiles.
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
    // theme, brand ink in the light one — the one color whose job is contrast
    // against the field's own fill.
    val cursor: Color,
    val tabBadgeAll: Color = Color(0xFF1560C7),
    val tabBadgeStarred: Color = Color(0xFF975619),
    val tabTintSent: Color = Color(0xFFAC63FE),
    val tabBadgeSent: Color = Color(0xFF6928CC),
    /** = errorFill since Round 8 (was #BC2828). */
    val unreadBadge: Color = LightTokens.errorFill,
    // ── Round 8 identity ──
    val primaryPressed: Color = LightTokens.primaryPressed,
    /** Links, text buttons, selected icons, progress; #8EA8FF in dark. */
    val primaryText: Color = LightTokens.primaryText,
    val primaryContainer: Color = LightTokens.primaryContainer,
    val onPrimaryContainer: Color = LightTokens.onPrimaryContainer,
    val primaryContainerStrong: Color = LightTokens.primaryContainerStrong,
    val focusRing: Color = LightTokens.focusRing,
    val brandViolet: Color = LightTokens.brandViolet,
    val selectedRow: Color = LightTokens.selectedRow,
    val successFill: Color = LightTokens.successFill,
    val errorFill: Color = LightTokens.errorFill,
    val errorContainer: Color = LightTokens.errorContainer,
    /** Text on [errorContainer] (AA in both themes; see GrapMailColors.onErrorContainer). */
    val onErrorContainer: Color = LightTokens.onErrorContainer,
    val warning: Color = LightTokens.warning,
    val warningContainer: Color = LightTokens.warnChipBg,
    val ownMessageFill: Color = LightTokens.ownMessageFill,
    val scrim: Color = LightTokens.scrim,
    /** Plate behind a brand (BIMI) logo. */
    val bimiPlate: Color = LightTokens.bimiPlate,
    /** 1 dp inner hairline on avatar discs; transparent in light. */
    val avatarRing: Color = Color.Transparent,
    /** True when this palette is the dark one, so a component can follow the app theme rather than the system. */
    val isDark: Boolean = false,
)

/** The GrapMail palette for one theme, before any "Match wallpaper" override. */
fun grapMailPalette(base: GrapMailColors, dark: Boolean): Palette = if (dark) Palette(
    bg = base.bgDark, bgTinted = base.bgTintedDark, card = base.cardDark, softFill = base.softFillDark,
    infoFill = base.primaryContainerDark,
    border = base.borderDark, borderSoft = base.borderSoftDark,
    primaryTop = base.primaryTopDark, primaryBottom = base.primaryBottomDark, primarySolid = base.primarySolidDark,
    buttonDisabled = base.buttonDisabledDark, onPrimary = base.onPrimary,
    textPrimary = base.textPrimaryDark, textSecondary = base.textSecondaryDark, textTertiary = base.textTertiaryDark,
    success = base.successDark, error = base.errorDark, stepInactive = base.stepInactiveDark,
    inputFill = base.inputFillDark,
    successChipBg = base.successChipBgDark, successInk = base.successInkDark,
    warnChipBg = base.warnChipBgDark, warnInk = base.warnInkDark,
    cardAlt = base.cardAltDark, otpFill = base.otpFillDark,
    swipeArchive = base.swipeArchiveDark, swipeTrash = base.swipeTrashDark,
    star = base.starDark,
    // Caret stays light ink on the dark fields (§1.12 "keep for caret").
    cursor = base.textPrimaryDark,
    unreadBadge = base.errorFillDark,
    primaryPressed = base.primaryPressedDark,
    primaryText = base.primaryTextDark,
    primaryContainer = base.primaryContainerDark,
    onPrimaryContainer = base.onPrimaryContainerDark,
    primaryContainerStrong = base.primaryContainerStrongDark,
    focusRing = base.focusRingDark,
    brandViolet = base.brandVioletDark,
    selectedRow = base.selectedRowDark,
    successFill = base.successFillDark,
    errorFill = base.errorFillDark,
    errorContainer = base.errorContainerDark,
    onErrorContainer = base.onErrorContainerDark,
    warning = base.warningDark,
    warningContainer = base.warnChipBgDark,
    ownMessageFill = base.ownMessageFillDark,
    scrim = base.scrimDark,
    bimiPlate = base.bimiPlateDark,
    avatarRing = base.avatarRingDark,
    isDark = true,
) else Palette(
    bg = base.bg, bgTinted = base.bgTinted, card = base.card, softFill = base.softFill, infoFill = base.infoFill,
    border = base.border, borderSoft = base.borderSoft,
    primaryTop = base.primaryTop, primaryBottom = base.primaryBottom, primarySolid = base.primarySolid,
    buttonDisabled = base.buttonDisabled, onPrimary = base.onPrimary,
    textPrimary = base.textPrimary, textSecondary = base.textSecondary, textTertiary = base.textTertiary,
    success = base.success, error = base.error, stepInactive = base.stepInactive,
    inputFill = base.inputFill,
    successChipBg = base.successChipBg, successInk = base.successInk,
    warnChipBg = base.warnChipBg, warnInk = base.warnInk,
    cardAlt = base.cardAlt, otpFill = base.otpFill,
    swipeArchive = base.swipeArchive, swipeTrash = base.swipeTrash,
    star = base.star,
    cursor = base.primarySolid,
    unreadBadge = base.errorFill,
    primaryPressed = base.primaryPressed,
    primaryText = base.primaryText,
    primaryContainer = base.primaryContainer,
    onPrimaryContainer = base.onPrimaryContainer,
    primaryContainerStrong = base.primaryContainerStrong,
    focusRing = base.focusRing,
    brandViolet = base.brandViolet,
    selectedRow = base.selectedRow,
    successFill = base.successFill,
    errorFill = base.errorFill,
    errorContainer = base.errorContainer,
    onErrorContainer = base.onErrorContainer,
    warning = base.warning,
    warningContainer = base.warnChipBg,
    ownMessageFill = base.ownMessageFill,
    scrim = base.scrim,
    bimiPlate = base.bimiPlate,
    avatarRing = Color.Transparent,
    isDark = false,
)

val LocalPalette = staticCompositionLocalOf { grapMailPalette(LightTokens, dark = false) }

// ── corner radii (§1.8) ───────────────────────────────────────────────────

object GMRadius {
    val Small: Dp = 10.dp
    val Input: Dp = 14.dp
    val Button: Dp = 16.dp
    val Card: Dp = 20.dp
    val Thumb: Dp = 12.dp
    val Dialog: Dp = 24.dp
    /** Bottom sheets: top corners only, see [GMShapes.Sheet]. */
    val Sheet: Dp = 28.dp
    val Pill: Dp = 999.dp
}

object GMShapes {
    val Small = RoundedCornerShape(GMRadius.Small)
    val Input = RoundedCornerShape(GMRadius.Input)
    val Button = RoundedCornerShape(GMRadius.Button)
    val Card = RoundedCornerShape(GMRadius.Card)
    val Thumb = RoundedCornerShape(GMRadius.Thumb)
    val Dialog = RoundedCornerShape(GMRadius.Dialog)
    val Sheet = RoundedCornerShape(topStart = GMRadius.Sheet, topEnd = GMRadius.Sheet)
    val Pill = RoundedCornerShape(50)
}

/** Material's shape scale mapped onto the radii, as §1.8 specifies. */
private val GrapMailShapes = Shapes(
    extraSmall = RoundedCornerShape(GMRadius.Small),
    small = RoundedCornerShape(GMRadius.Input),
    medium = RoundedCornerShape(GMRadius.Card),
    large = RoundedCornerShape(GMRadius.Dialog),
    extraLarge = RoundedCornerShape(GMRadius.Sheet),
)

// ── elevation (§1.9) ──────────────────────────────────────────────────────

/** Elevation levels. Light uses soft, cool shadows; dark uses none and lifts with fills. */
enum class GMElevation { Flat, Card, Menu, Brand, Sheet }

private val ShadowInk = Color(0xFF0F172A)
private val BrandShadowInk = Color(0xFF1F5EFF)

/**
 * The spec's tinted shadow for [level] on [shape]. In dark it does nothing:
 * the caller lifts the surface with `card` / `cardAlt` (+ `border` at level 2)
 * instead, as §1.9 says. Use this, not `tonalElevation` (surfaceTint is
 * transparent on purpose). Tinted shadow colours need API 28+; below that,
 * Android draws its default grey shadow at the same elevation.
 */
fun Modifier.gmElevation(level: GMElevation, shape: Shape, dark: Boolean): Modifier {
    if (dark) return this
    return when (level) {
        GMElevation.Flat -> this
        GMElevation.Card -> this.shadow(1.dp, shape, clip = false,
            ambientColor = ShadowInk.copy(alpha = 0.04f), spotColor = ShadowInk.copy(alpha = 0.06f))
        GMElevation.Menu -> this.shadow(12.dp, shape, clip = false,
            ambientColor = ShadowInk.copy(alpha = 0.12f), spotColor = ShadowInk.copy(alpha = 0.12f))
        GMElevation.Brand -> this.shadow(8.dp, shape, clip = false,
            ambientColor = BrandShadowInk.copy(alpha = 0.28f), spotColor = BrandShadowInk.copy(alpha = 0.28f))
        GMElevation.Sheet -> this.shadow(12.dp, shape, clip = false,
            ambientColor = ShadowInk.copy(alpha = 0.16f), spotColor = ShadowInk.copy(alpha = 0.16f))
    }
}

// ── theme ─────────────────────────────────────────────────────────────────

/**
 * Settings → Appearance → "Match wallpaper" (§1.11), read as snapshot state so
 * the theme recomposes the moment the switch flips. Off unless the person
 * turned it on: the GrapMail palette is the default on every device.
 */
@Composable
internal fun rememberMatchWallpaper(): Boolean {
    val context = LocalContext.current
    remember(context) { MailDisplayPrefs.ensureAppearanceLoaded(context) }
    return MailDisplayPrefs.matchWallpaper
}

@Composable
fun GrapMailTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Round 8: Material You is opt-in ("Match wallpaper"), never the default.
    dynamicColor: Boolean = rememberMatchWallpaper(),
    content: @Composable () -> Unit
) {
    val dark = darkTheme
    val base = LocalGrapMailColors.current
    // "Match wallpaper", with a light hand: on Android 12+ only the accent
    // family — buttons, links, containers, the caret, focus, the archive swipe —
    // follows the wallpaper through the platform's own scheme. Avatar hues,
    // semantic colours, neutrals and the signature gradient stay GrapMail's.
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= 31) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else null
    val palette = grapMailPalette(base, dark).let { p ->
        if (scheme == null) p else p.copy(
            primaryTop = scheme.primary,
            primaryBottom = scheme.primary,
            primarySolid = scheme.primary,
            primaryPressed = lerp(scheme.primary, scheme.onPrimaryContainer, 0.15f),
            primaryText = scheme.primary,
            onPrimary = scheme.onPrimary,
            primaryContainer = scheme.primaryContainer,
            onPrimaryContainer = scheme.onPrimaryContainer,
            infoFill = scheme.primaryContainer,
            buttonDisabled = scheme.primaryContainer,
            focusRing = scheme.primary,
            cursor = scheme.primary,
            swipeArchive = scheme.primary,
        )
    }
    // Material components (switch track, TextButton, progress, checkbox,
    // radio) take colorScheme.primary. In dark that is primaryText #8EA8FF
    // with #0A1A52 on it (§1.2); the custom GrapMail buttons keep cobalt.
    val m3Primary = if (dark) palette.primaryText else palette.primarySolid
    val m3OnPrimary = when {
        scheme != null -> scheme.onPrimary
        dark -> base.m3OnPrimaryDark
        else -> palette.onPrimary
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = (scheme ?: (if (dark) darkColorScheme() else lightColorScheme())).copy(
                primary = m3Primary,
                onPrimary = m3OnPrimary,
                primaryContainer = palette.primaryContainer,
                onPrimaryContainer = palette.onPrimaryContainer,
                // Material's baseline leaves these purple/pink (the AM/PM
                // selector of the time picker, snackbar-style bars' actions).
                // They take the GrapMail accent family instead; with "Match
                // wallpaper" on, the wallpaper scheme's own values are kept.
                inversePrimary = scheme?.inversePrimary ?: (if (dark) base.primaryPressed else base.primaryTextDark),
                inverseSurface = scheme?.inverseSurface ?: (if (dark) base.textPrimaryDark else base.textPrimary),
                inverseOnSurface = scheme?.inverseOnSurface ?: (if (dark) base.cardDark else base.textPrimaryDark),
                tertiary = m3Primary,
                onTertiary = m3OnPrimary,
                tertiaryContainer = palette.primaryContainer,
                onTertiaryContainer = palette.onPrimaryContainer,
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
                errorContainer = palette.errorContainer,
                onErrorContainer = palette.onErrorContainer,
                scrim = palette.scrim,
            ),
            shapes = GrapMailShapes,
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
