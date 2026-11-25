package com.grapsee.grapmail.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * GrapMail's own token table — Round 8 visual identity (docs/design/round8-spec.md §1).
 *
 * The neutrals are still the founder-measured values (design/DESIGN_TOKENS.md).
 * The accent family moved from the old "very good Gmail" blue (#1A6DF0) to
 * GrapMail **cobalt** (#1F5EFF light / #2F66FF dark), taken from the app-icon
 * field. The semantic inks were darkened until text passes WCAG AA (§1.5).
 *
 * Every name that existed before Round 8 is still here, so nothing that reads
 * it stops compiling. Where the spec renamed a role, the old name now holds the
 * new value (`infoFill` = primaryContainer, `successInk` = success,
 * `swipeTrash` = errorFill). `warnInk` is the warning ink for text on the warn
 * container (see its note).
 */
data class GrapMailColors(
    // ── Light theme ────────────────────────────────────────────────────────
    val bg: Color = Color(0xFFFBFCFE),
    val bgTinted: Color = Color(0xFFF7F9FC),
    val card: Color = Color(0xFFFFFFFF),
    val inputFill: Color = Color(0xFFFFFFFF),
    val softFill: Color = Color(0xFFEFF5FD),
    /** = primaryContainer (§1.12). Was #E3ECFB. */
    val infoFill: Color = Color(0xFFE6EDFF),
    val border: Color = Color(0xFFE1E7F2),
    val borderSoft: Color = Color(0xFFD9E6F9),
    /** Button gradient top (spec `primaryGradientTop`). Was #206BFD. */
    val primaryTop: Color = Color(0xFF2465FF),
    /** Button gradient bottom (spec `primaryGradientBottom`). Was #1560F5. */
    val primaryBottom: Color = Color(0xFF1A4FF0),
    /** Custom filled buttons (spec `primary`). Was #1A6DF0. */
    val primarySolid: Color = Color(0xFF1F5EFF),
    val primaryPressed: Color = Color(0xFF1446E0),
    /** Links, text buttons, selected icons. Same as primary in light. */
    val primaryText: Color = Color(0xFF1F5EFF),
    val primaryContainer: Color = Color(0xFFE6EDFF),
    val onPrimaryContainer: Color = Color(0xFF0B2A8F),
    val primaryContainerStrong: Color = Color(0xFFD3DEFF),
    val focusRing: Color = Color(0xFF1F5EFF),
    /** Was #C4DBFD. */
    val buttonDisabled: Color = Color(0xFFC7D5FF),
    val brandViolet: Color = Color(0xFF6A4DF4),
    val selectedRow: Color = Color(0xFFEEF2FF),
    val ownMessageFill: Color = Color(0xFFF3F6FF),
    val textPrimary: Color = Color(0xFF0F172A),
    val textSecondary: Color = Color(0xFF5B6B84),
    /** Was #94A3B8 (2.6:1, failed AA). */
    val textTertiary: Color = Color(0xFF657489),
    /** Success text/ink. Was #10B981 (failed AA as text). */
    val success: Color = Color(0xFF08734F),
    /** Check discs, done icons (non-text). */
    val successFill: Color = Color(0xFF0E9F6E),
    /** Error text and field stroke. Was #EF4444 (3.8:1). */
    val error: Color = Color(0xFFD92D2D),
    /** Destructive filled button, unread badge, trash swipe; white text on it. */
    val errorFill: Color = Color(0xFFD92D2D),
    val errorContainer: Color = Color(0xFFFDECEC),
    /**
     * Text drawn ON [errorContainer]. Not in the spec's table: `error` #D92D2D on
     * #FDECEC is ≈4.2:1, under AA for body text, so banner copy uses this
     * darker red (≈5.8:1). Dark theme keeps `error`, which passes there.
     */
    val onErrorContainer: Color = Color(0xFFB42318),
    /** Warning text on surfaces (§1.5). Was `warnInk` #C2620A (4.2:1). ≈5.0:1 on white. */
    val warning: Color = Color(0xFFB45309),
    val stepInactive: Color = Color(0xFFDCE6F5),
    val onPrimary: Color = Color(0xFFFFFFFF),
    // Recovery/security sector (sampled from raw-designs/account/)
    /** = successContainer. Was #DCF8F4. */
    val successChipBg: Color = Color(0xFFDCF8F0),
    /** Alias of [success] since Round 8. Was #0B8F64. */
    val successInk: Color = Color(0xFF08734F),
    /** = warningContainer. */
    val warnChipBg: Color = Color(0xFFFDF0DE),
    /**
     * The warning ink the app draws on [warnChipBg] banners, notes and chips.
     * The spec maps this name to `warning` #B45309, but that is only ≈4.47:1 on
     * the #FDF0DE container, just under AA for 12–13 sp text. This value is
     * slightly darker (≈5.2:1 there, ≈5.9:1 on white), so every place that already
     * reads `warnInk` passes. Dark keeps #F5A623 (≈7.9:1 on #2B1F0B).
     */
    val warnInk: Color = Color(0xFFA44B08),
    val cardAlt: Color = Color(0xFFF5F9FE),
    val otpFill: Color = Color(0xFFFFFFFF),
    // Mailbox swipe affordances. Two directions, two meanings, so two colours.
    // A swipe can only archive or trash, and both can be undone. Permanent
    // deletion stays behind a confirmation sheet. Trash now uses errorFill,
    // as §1.12 says.
    val swipeArchive: Color = Color(0xFF1F5EFF),
    val swipeTrash: Color = Color(0xFFD92D2D),
    /**
     * The starred marker. Its state is carried by filled vs outline shape, so
     * colour is not the only signal.
     */
    val star: Color = Color(0xFFF5A623),
    /** Brand-logo (BIMI) plate behind a sender's logo. */
    val bimiPlate: Color = Color(0xFFFFFFFF),
    /** Behind sheets and dialogs: #0F172A at 40 %. */
    val scrim: Color = Color(0x660F172A),

    // ── Dark theme ─────────────────────────────────────────────────────────
    // Neutral near-black canvas with clearly lighter cards (repainted
    // 2026-11-14 to the founder's mailbox reference, same values as iOS).
    val bgDark: Color = Color(0xFF0A0A0C),
    val bgTintedDark: Color = Color(0xFF0D0D0F),
    val cardDark: Color = Color(0xFF141416),
    val inputFillDark: Color = Color(0xFF1A1A1E),
    val borderDark: Color = Color(0xFF2C2C31),
    /** Section dividers. Dark value moved from #2C2C31 in Round 8. */
    val borderSoftDark: Color = Color(0xFF232327),
    val softFillDark: Color = Color(0xFF232327),
    val primarySolidDark: Color = Color(0xFF2F66FF),
    val primaryTopDark: Color = Color(0xFF2F66FF),
    val primaryBottomDark: Color = Color(0xFF2456E8),
    val primaryPressedDark: Color = Color(0xFF2557EA),
    val primaryTextDark: Color = Color(0xFF8EA8FF),
    val primaryContainerDark: Color = Color(0xFF1A2550),
    val onPrimaryContainerDark: Color = Color(0xFFDCE4FF),
    val primaryContainerStrongDark: Color = Color(0xFF26326A),
    val focusRingDark: Color = Color(0xFF8EA8FF),
    /** Material components' `onPrimary` in dark: they take primaryText #8EA8FF (§1.2). */
    val m3OnPrimaryDark: Color = Color(0xFF0A1A52),
    /** Was #1E3A66. */
    val buttonDisabledDark: Color = Color(0xFF1D2B5C),
    val brandVioletDark: Color = Color(0xFFB4A6FF),
    val selectedRowDark: Color = Color(0xFF1E2440),
    val ownMessageFillDark: Color = Color(0xFF161A26),
    val textPrimaryDark: Color = Color(0xFFEAEAEA),
    val textSecondaryDark: Color = Color(0xFF9E9EA5),
    /** Was #67676D (3.5:1, failed AA). */
    val textTertiaryDark: Color = Color(0xFF808088),
    val successDark: Color = Color(0xFF34D399),
    val successFillDark: Color = Color(0xFF34D399),
    val errorDark: Color = Color(0xFFF87171),
    val errorFillDark: Color = Color(0xFFD92D2D),
    val errorContainerDark: Color = Color(0xFF3B1A1A),
    val onErrorContainerDark: Color = Color(0xFFF87171),
    val warningDark: Color = Color(0xFFF5A623),
    val stepInactiveDark: Color = Color(0xFF2C2C31),
    val successChipBgDark: Color = Color(0xFF0A2A23),
    val successInkDark: Color = Color(0xFF34D399),
    val warnChipBgDark: Color = Color(0xFF2B1F0B),
    val warnInkDark: Color = Color(0xFFF5A623),
    val cardAltDark: Color = Color(0xFF1C1C20),
    val otpFillDark: Color = Color(0xFF141418),
    /** Was #3B82F6. */
    val swipeArchiveDark: Color = Color(0xFF2F66FF),
    /** errorFill in both themes, with white on it (4.8:1). Was #F87171. */
    val swipeTrashDark: Color = Color(0xFFD92D2D),
    /** The starred marker on a dark canvas — see `star` above. */
    val starDark: Color = Color(0xFFFDD663),
    val bimiPlateDark: Color = Color(0xFFF4F5F7),
    /** Behind sheets and dialogs: black at 60 %. */
    val scrimDark: Color = Color(0x99000000),
    /** 1 dp inner hairline on avatar discs in dark only: white at 10 % (§1.6.4). */
    val avatarRingDark: Color = Color(0x1AFFFFFF),
)

val LocalGrapMailColors = staticCompositionLocalOf { GrapMailColors() }

/**
 * Brand brushes (§1.3).
 *
 * [signature] is the sky → cobalt → violet gradient for a few brand moments:
 * compose FAB, safety ring at "strong", welcome-back avatar ring, celebration.
 * Never put it behind body text. A white glyph on it is ≥3.3:1 at the lightest
 * stop, which is fine for icons of 24 dp and up. The default start/end points run
 * from the top-left to the bottom-right corner of whatever it fills, which is
 * the spec's 135°.
 *
 * None of these follow "Match wallpaper": the signature gradient is identity.
 */
object GMBrush {
    val SignatureStart = Color(0xFF3D8BFF)
    val SignatureMid = Color(0xFF1F5EFF)
    val SignatureEnd = Color(0xFF5B47E0)

    val signature: Brush = Brush.linearGradient(
        0f to SignatureStart,
        0.55f to SignatureMid,
        1f to SignatureEnd,
    )

    /** The celebration confetti colours (§4.4), in spec order. */
    val confetti: List<Color> = listOf(
        SignatureStart, SignatureMid, SignatureEnd, Color(0xFF0E9F6E), Color(0xFFF5A623),
    )
}

/**
 * The sender avatar ramp, kept under its pre-Round-8 names so every caller
 * still compiles. The values are now GrapMail's own 10 hues (§1.6.1), held in
 * ui/theme/AvatarColors.kt next to the hash that picks a slot. This file adds
 * no hues of its own. `scripts/mail-honesty.py` pins the table there.
 *
 * Pick a slot with [avatarIndex], not with a local hash. The light and dark
 * lists have the same length, so a slot is valid in either.
 */
val GrapMailAvatarRampLight: List<Color> get() = AvatarPaletteLight

val GrapMailAvatarRampDark: List<Color> get() = AvatarPaletteDark
