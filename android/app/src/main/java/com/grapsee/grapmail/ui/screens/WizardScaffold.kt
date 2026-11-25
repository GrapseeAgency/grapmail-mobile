package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.grapsee.grapmail.ui.components.GMLayout
import com.grapsee.grapmail.ui.components.GrapMailLogo
import com.grapsee.grapmail.ui.components.gmIsNarrowWindow
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.components.StepProgress
import com.grapsee.grapmail.ui.theme.H2
import com.grapsee.grapmail.ui.theme.LocalPalette
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

/**
 * Wizard chrome — founder design verbatim: back arrow + centered logo header,
 * "CREATE ACCOUNT" eyebrow, step title + subtitle, the numbered stepper,
 * scrollable body, then the footer with the primary action and
 * "Already have an account? Sign In".
 */
@Composable
fun WizardScaffold(
    step: Int,
    title: String,
    subtitle: AnnotatedSubtitle,
    onBack: (() -> Unit)?,
    onSignIn: () -> Unit,
    bottomBar: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val config = LocalConfiguration.current
    val compact = config.screenWidthDp < 380 || config.screenHeightDp < 680 || config.fontScale > 1.15f
    // 16dp margins on small phones / cover screens / narrow split panes, 24dp elsewhere.
    val side = if (gmIsNarrowWindow()) 16.dp else 24.dp
    // The window (after the system bars, cutout and keyboard) scrolls as one
    // column: header, copy, the step's fields, then the primary action and the
    // sign-in line. imePadding keeps the end of that column — the action —
    // reachable above the keyboard on every height, landscape included.
    Box(Modifier.fillMaxSize().background(palette.bg).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Tablets, foldables, landscape and desktop windows: one centred
            // form column (GMLayout.Form). Phones in portrait are narrower
            // than the cap, so they look exactly as before.
            Column(Modifier.gmMaxWidth(GMLayout.Form)) {
            // Header: back + centered logo
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.acct_back), tint = palette.textPrimary)
                    }
                }
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) { GrapMailLogo(36.dp, wordmark = true) }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = side),
            ) {
                if (compact) {
                    Text(stringResource(R.string.acct_wizard_step_of, step), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.textSecondary)
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.acct_create_account), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = palette.textTertiary)
                }
                Spacer(Modifier.height(6.dp))
                Text(title, style = H2, color = palette.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text(subtitle.build(), fontSize = 14.sp, lineHeight = 21.sp, color = palette.textSecondary)
                if (!compact) {
                    Spacer(Modifier.height(22.dp))
                    StepProgress(step = step)
                }
                Spacer(Modifier.height(if (compact) 14.dp else 22.dp))
                body()
                Spacer(Modifier.height(14.dp))
                bottomBar()
                Spacer(Modifier.height(10.dp))
                AlreadyHaveAccount(onSignIn = onSignIn)
                Spacer(Modifier.height(20.dp))
            }
            }
        }
    }
}

/**
 * True when a window is wide enough and landscape enough to put a screen's
 * artwork beside its copy instead of above it: landscape tablets, unfolded
 * foldables held sideways, Chromebook / DeX / desktop freeform windows and
 * phones in landscape. Never true for a phone in portrait.
 */
internal fun gmUseSplitLayout(width: Dp, height: Dp): Boolean =
    width >= 600.dp && width >= height * 1.25f

/**
 * The shared shell for the sign-up screens that carry artwork (the account
 * created screens): one centred, scrolling [GMLayout.Form] column on phones,
 * tablets in portrait and other tall windows; on wide landscape windows
 * ([gmUseSplitLayout]) the [art] block sits in a vertically centred left pane
 * and the [form] block scrolls in the right pane, so nothing is cropped and
 * the action is never below a fold the person cannot see.
 *
 * [art] receives `split = true` in the two-pane layout so it can grow its
 * illustration into the room it has.
 */
@Composable
internal fun ArtFormLayout(
    modifier: Modifier = Modifier,
    art: @Composable ColumnScope.(split: Boolean) -> Unit,
    form: @Composable ColumnScope.() -> Unit,
) {
    val side = if (gmIsNarrowWindow()) 16.dp else 24.dp
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        if (gmUseSplitLayout(maxWidth, maxHeight)) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = viewportHeight)
                            .padding(vertical = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { art(true) }
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
                            .padding(vertical = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { form() }
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
                    Modifier.gmMaxWidth(GMLayout.Form),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    art(false)
                    form()
                }
            }
        }
    }
}

/** "Already have an account? Sign In" — the wizard footer, exactly as designed. */
@Composable
fun AlreadyHaveAccount(onSignIn: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSignIn)
            .padding(vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.acct_already_have_an_account), fontSize = 13.sp, color = palette.textSecondary)
        Text(stringResource(R.string.acct_sign_in), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.primarySolid)
    }
}

/** Subtitle with an optional bold lead sentence, like "Let's get started." */
class AnnotatedSubtitle(val boldLead: String? = null, val rest: String) {
    fun build() = buildAnnotatedString {
        boldLead?.let {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(it) }
            append(" ")
        }
        append(rest)
    }
}
