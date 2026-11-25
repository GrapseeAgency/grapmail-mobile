package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.components.GMLayout
import com.grapsee.grapmail.ui.components.GMWidth
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.components.WelcomeHero
import com.grapsee.grapmail.ui.components.gmIsNarrowWindow
import com.grapsee.grapmail.ui.components.gmIsShortWindow
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.components.gmWidthClass
import com.grapsee.grapmail.ui.components.rememberMotionReduced
import com.grapsee.grapmail.ui.theme.H1
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Welcome — the first screen anyone sees: the living-mailbox illustration on
 * top, then "Welcome to / GrapMail", the promise line, Create Account / Sign In
 * with trailing arrows and the legal line in brand blue.
 *
 * The headline and tagline exist ONCE, as real localized text (sp, so they
 * follow the font-size setting). The illustration ([WelcomeHero]) carries no
 * text of its own.
 *
 * Sizing, from the window this screen actually gets (after system bars and
 * display cutout):
 *  - The art keeps its authored 400 x 440 ratio and is always fitted, centred,
 *    never cropped: its height is the smallest of
 *      · ~43% of the window height (30% in short or very narrow windows),
 *      · the room left after the text block and buttons (so they stay on screen
 *        without scrolling),
 *      · the content column width x 440/400,
 *      · 400dp on phones / 480dp on tablets, foldables and desktop windows.
 *  - If that leaves less than ~140dp (112dp short/narrow) — a landscape phone,
 *    a Flip cover screen, a tiny split-screen pane, or very large fonts — the
 *    art is left out and only text and buttons show.
 *  - Wide windows centre one column at [GMLayout.Form] width.
 *  - Wide landscape windows ([gmUseSplitLayout]) put the art in a left pane
 *    (fitted to the window height, same 400 x 440 box, never cropped) and the
 *    text + buttons in a right pane; the headline is still drawn once.
 *  - The whole group is centred vertically, so there is no dead band between
 *    the art and the buttons; if it is still taller than the window (font
 *    scale 200%) it scrolls, and the primary button follows the text directly.
 */
@Composable
fun WelcomeScreen(
    onCreateAccount: () -> Unit,
    onSignIn: () -> Unit,
    onLegal: (GrapMailLegalDocument) -> Unit,
) {
    val palette = LocalPalette.current
    val legalLine = stringResource(R.string.acct_welcome_legal_line)
    val termsTitle = stringResource(R.string.acct_terms_of_service)
    val privacyTitle = stringResource(R.string.acct_privacy_policy)
    val short = gmIsShortWindow()
    val narrow = gmIsNarrowWindow()
    val wide = gmWidthClass() != GMWidth.Compact
    val reducedMotion = rememberMotionReduced()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // Measured height of everything below the art (text, buttons, legal line).
    // It never depends on the art, so this settles after one layout pass.
    var blockHeightPx by remember { mutableIntStateOf(0) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            // MainActivity already pads for system bars + cutout; this is a
            // no-op there (insets already consumed) and a guard anywhere else.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val viewportHeight: Dp =
            if (constraints.hasBoundedHeight) maxHeight else configuration.screenHeightDp.dp
        val viewportWidth: Dp =
            if (constraints.hasBoundedWidth) maxWidth else configuration.screenWidthDp.dp

        val side = if (narrow) 16.dp else 24.dp
        val verticalPad = if (short) 12.dp else 24.dp
        val heroGap = if (short) 12.dp else 20.dp

        val columnWidth = min(viewportWidth - side * 2, GMLayout.Form)
        val blockHeight: Dp = if (blockHeightPx > 0) {
            with(density) { blockHeightPx.toDp() }
        } else {
            // First frame only: a slightly generous estimate of the text block.
            150.dp + 230.dp * density.fontScale
        }
        val fraction = if (short || narrow) 0.30f else 0.43f
        val cap = if (wide) 480.dp else 400.dp
        val room = viewportHeight - verticalPad * 2 - heroGap - blockHeight
        val heroHeight = min(
            min(viewportHeight * fraction, room),
            min(columnWidth * (440f / 400f), cap),
        )
        val minHero = if (short || narrow) 112.dp else 140.dp
        val showHero = heroHeight >= minHero

        // Wide landscape windows (tablets and unfolded foldables held sideways,
        // Chromebook / DeX / desktop windows, phones in landscape): the art in
        // a left pane, fitted to the window height and never cropped, and the
        // text and buttons centred in a right pane. Everything else keeps the
        // single centred column below.
        val split = gmUseSplitLayout(viewportWidth, viewportHeight)
        val textBlock: @Composable () -> Unit = {
            Text(
                stringResource(R.string.acct_welcome_to),
                style = H1,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text("GrapMail", style = H1, color = palette.primarySolid, textAlign = TextAlign.Center)
            Spacer(Modifier.height(if (short) 6.dp else 10.dp))
            Text(
                stringResource(R.string.acct_a_faster_safer_and_more_private_way),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(if (short) 16.dp else 28.dp))

            GradientButton(stringResource(R.string.acct_create_account_button), onCreateAccount, trailing = {
                Spacer(Modifier.size(10.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            })
            // The founder asked for air between the primary and the secondary CTA:
            // 20dp, not the 12dp pair spacing used inside forms (14dp in short windows).
            Spacer(Modifier.height(if (short) 14.dp else 20.dp))
            OutlineButton(stringResource(R.string.acct_sign_in), onSignIn, trailing = {
                Spacer(Modifier.size(10.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp), tint = palette.textPrimary)
            })

            Spacer(Modifier.height(if (short) 12.dp else 18.dp))
            Text(
                buildAnnotatedString {
                    appendWithLegalLinks(
                        legalLine, palette.primarySolid, onLegal,
                        GrapMailLegalDocument.Terms to termsTitle,
                        GrapMailLegalDocument.Privacy to privacyTitle,
                    )
                },
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.textTertiary,
                textAlign = TextAlign.Center,
            )
        }

        if (split) {
            val paneWidth = (viewportWidth - 32.dp * 2 - 40.dp) / 2
            val splitHero = min(min(viewportHeight - verticalPad * 2, paneWidth * (440f / 400f)), cap)
            val showSplitHero = splitHero >= minHero
            Row(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showSplitHero) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        key(palette.primarySolid, palette.bg, reducedMotion) {
                            WelcomeHero(
                                accent = palette.primarySolid,
                                backdrop = palette.bg,
                                still = reducedMotion,
                                modifier = Modifier.size(width = splitHero * (400f / 440f), height = splitHero),
                            )
                        }
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        Modifier
                            .gmMaxWidth(GMLayout.Form)
                            .heightIn(min = viewportHeight)
                            .padding(vertical = verticalPad),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { textBlock() }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = side),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .gmMaxWidth(GMLayout.Form)
                        .heightIn(min = viewportHeight)
                        .padding(vertical = verticalPad),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (showHero) {
                        // Rebuilt when the theme or "Remove animations" changes.
                        key(palette.primarySolid, palette.bg, reducedMotion) {
                            WelcomeHero(
                                accent = palette.primarySolid,
                                backdrop = palette.bg,
                                still = reducedMotion,
                                // Exactly the authored 400 x 440 box: fitted, never cropped.
                                modifier = Modifier.size(width = heroHeight * (400f / 440f), height = heroHeight),
                            )
                        }
                        Spacer(Modifier.height(heroGap))
                    }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { blockHeightPx = it.height },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        textBlock()
                    }
                }
            }
        }
    }
}
