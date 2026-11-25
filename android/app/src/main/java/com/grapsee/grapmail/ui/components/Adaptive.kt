package com.grapsee.grapmail.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared responsive-layout helpers, so every screen answers "how much room do
 * I have?" the same way on phones, foldables (inner and cover screens),
 * tablets, split screen, freeform / desktop windows (ChromeOS, DeX, Android
 * desktop windowing) and landscape.
 *
 * Widths follow the Material window size classes: compact < 600dp,
 * medium 600-839dp, expanded >= 840dp. They are read from the window, not
 * the physical screen, so split screen and resizable windows are handled too.
 */
enum class GMWidth { Compact, Medium, Expanded }

@Composable
@ReadOnlyComposable
fun gmWidthClass(): GMWidth {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w < 600 -> GMWidth.Compact
        w < 840 -> GMWidth.Medium
        else -> GMWidth.Expanded
    }
}

/** A short window: a phone in landscape, a split-screen half, a Flip-style cover screen. */
@Composable
@ReadOnlyComposable
fun gmIsShortWindow(): Boolean = LocalConfiguration.current.screenHeightDp < 480

/** A very narrow window: small phones at large display size, cover screens, narrow split screen. */
@Composable
@ReadOnlyComposable
fun gmIsNarrowWindow(): Boolean = LocalConfiguration.current.screenWidthDp < 360

/** Comfortable maximum widths for content on wide windows. Wider than any phone in portrait. */
object GMLayout {
    /** Reading: email bodies, long text. */
    val Readable: Dp = 720.dp
    /**
     * The mail reader's column: subject, message cards, reply bar. About
     * 70-75 characters of 16 sp body text per line inside a card, the measure
     * long-form reading is most comfortable at; centred beyond it.
     */
    val Reading: Dp = 680.dp
    /** Forms, sign-up steps, settings lists, dialogs and sheets. */
    val Form: Dp = 560.dp
    /** Compose. */
    val Compose: Dp = 820.dp
}

/**
 * Fills the width it is given, but keeps the content at most [max] wide and
 * centred. No effect on a phone in portrait.
 */
fun Modifier.gmMaxWidth(max: Dp = GMLayout.Readable): Modifier =
    this.fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = max)

/**
 * The reader's column: at most [GMLayout.Reading] wide, centred, and filling
 * that width itself (so a card inside it is as wide as the column, not as
 * wide as its content).
 */
fun Modifier.gmReadingColumn(): Modifier =
    this.gmMaxWidth(GMLayout.Reading).fillMaxWidth()
