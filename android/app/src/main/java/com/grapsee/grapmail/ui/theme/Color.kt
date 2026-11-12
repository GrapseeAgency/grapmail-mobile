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
    // Mailbox swipe affordances. Two directions, two meanings, so two colours —
    // and neither is the destructive red: a swipe can only archive or trash, both
    // of which undo. Permanent destruction stays behind a confirmation sheet, so
    // no single gesture can erase mail, and the palette has no colour for it on
    // purpose rather than by omission.
    val swipeArchive: Color = Color(0xFF1A6DF0),
    val swipeTrash: Color = Color(0xFFDC2626),
    /**
     * The starred marker. Both values, because the thread used to draw a single
     * hardcoded amber (`Color(0xFFF5A623)`) at the call site: in dark mode that is
     * a light-theme colour on a dark canvas, and no screenshot of the light theme
     * would ever show it. The light value is the one the app has always drawn; the
     * dark value is the web theme's lifted star (`--gm-star`, `grapmail.css:111`).
     */
    val star: Color = Color(0xFFF5A623),
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
    // Dark variants lifted for the same reason the light pair exists: a swipe
    // background sits behind a row that is sliding away, so it has to read as a
    // deliberate colour on both themes rather than as a dark rectangle.
    val swipeArchiveDark: Color = Color(0xFF3B82F6),
    val swipeTrashDark: Color = Color(0xFFF87171),
    /// The starred marker on a dark canvas — see `star` above.
    val starDark: Color = Color(0xFFFDD663),
)

val LocalGrapMailColors = staticCompositionLocalOf { GrapMailColors() }

/**
 * The sender avatar disc: the web reference's `AVATAR_COLOURS` ramp, in its
 * order, with the value each entry resolves to in each theme
 * (`src/app/(mail)/mail/page.tsx:305` against `grapmail.css:35,42,44,56-63,100,
 * 108,110`).
 *
 * Three of the eight web entries are not avatar hues at all — they are
 * `--gm-accent`, `--gm-error-strong` and `--gm-warn`, and those three DO change
 * with the theme. That is the fact this table exists to carry: the disc lifts
 * from `#1a73e8` to `#8ab4f8` in the dark canvas exactly as the web does, and the
 * five plain `--gm-avatar-*` slots stay put in both because the reference gives
 * them no dark override. Two values, not one, because a single light hue on a
 * dark canvas is the exact defect that left the starred marker sitting at
 * `#F5A623` in both apps until this sector's dark pin was written.
 *
 * It lives in the token table rather than beside the avatar composable because as
 * a hex literal in the mail components file it sat outside the gate that says the
 * mail screens pick no colour of their own — which let that gate's claim print
 * while the most-visited pixel of every row was unaccounted for. A token the file
 * does not name is a token the file cannot drift from.
 *
 * iOS holds the same sixteen values as `GM.avatarRamp` (`Theme.swift`), and
 * `scripts/mail-honesty.py` pins both against the reference's list. The slot a
 * sender lands on is the app's own accumulator (`Mail.kt`'s `rampIndex`), which
 * the two apps share exactly and the web does not: what is copied from the
 * reference is the palette and the determinism, not the hash.
 */
val GrapMailAvatarRampLight: List<Color> = listOf(
    Color(0xFF1A73E8), Color(0xFF0B8043), Color(0xFFA142F4), Color(0xFF455A64),
    Color(0xFFE37400), Color(0xFF00796B), Color(0xFF8E24AA), Color(0xFF3949AB),
)

val GrapMailAvatarRampDark: List<Color> = listOf(
    Color(0xFF8AB4F8), Color(0xFF0B8043), Color(0xFFA142F4), Color(0xFF546E7A),
    Color(0xFFFBBC04), Color(0xFF00796B), Color(0xFF8E24AA), Color(0xFF3949AB),
)
