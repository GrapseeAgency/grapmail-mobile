package com.grapsee.grapmail.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.delay

// Round 8 (round8-spec §1.2): text and small glyphs in the brand colour —
// eyebrows, links, the code timer, copy buttons, the footer shield — draw in
// `primaryText`, which passes AA on both canvases (#1F5EFF light, #8EA8FF
// dark). Filled shapes keep `primarySolid` with white on them. The focused code
// cell draws its stroke in `focusRing`.

/**
 * The recovery/security sector's screen shell — founder design verbatim.
 *
 * Back arrow + centred wordmark, the screen's own artwork, a blue eyebrow, the
 * 28pt title, the body paragraph, whatever the screen adds, then the footer
 * (primary action, secondary links, OR divider, the shield note).
 *
 * The body scrolls and the footer stays put, which is what keeps the action
 * reachable on a small phone and above the keyboard without the copy ever
 * pushing it off-screen.
 */
@Composable
fun AccountScreen(
    onBack: (() -> Unit)?,
    illustration: String? = null,
    illustrationWidth: Int = 200,
    eyebrow: String? = null,
    title: String? = null,
    body: String? = null,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val palette = LocalPalette.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .imePadding(),
    ) {
        // The designs are drawn on an 852pt phone. On a short one (a 640dp
        // device, an SE) the artwork gives way first and the action scrolls
        // with the copy — never a clipped field with the button pinned over it.
        val tight = maxHeight < 660.dp || LocalDensity.current.fontScale > 1.3f
        // Under 360dp (a small phone at large display size, a cover screen, a
        // narrow split-screen pane) the side margins give 8dp each back to the
        // copy and the code cells, as iOS does. Wider windows: the design's 24.
        val side = if (maxWidth < 360.dp) 16.dp else 24.dp
        Column(Modifier.fillMaxSize()) {
            AccountHeader(onBack)
            // The scroll area spans the window (it scrolls from anywhere), the
            // copy and footer are capped and centred: a tablet, an unfolded
            // foldable or a desktop window keeps the phone's reading width.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .gmMaxWidth(GMLayout.Form)
                    .padding(horizontal = side),
            ) {
                if (illustration != null) {
                    Spacer(Modifier.height(if (tight) 2.dp else 4.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // Code-drawn vector scene, not a cropped PNG: the
                        // artwork scales with the width it is given instead
                        // of letterboxing a fixed 460x233 raster.
                        com.grapsee.grapmail.ui.artwork.Artwork(
                            illustration,
                            modifier = Modifier
                                .width((if (tight) illustrationWidth * 3 / 4 else illustrationWidth).dp)
                                .aspectRatio(2f),
                        )
                    }
                    Spacer(Modifier.height(if (tight) 10.dp else 18.dp))
                } else {
                    Spacer(Modifier.height(if (tight) 8.dp else 16.dp))
                }
                if (eyebrow != null) {
                    Text(
                        eyebrow.uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                        color = palette.primaryText,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (title != null) {
                    Text(
                        title,
                        fontSize = 28.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                        color = palette.textPrimary,
                    )
                    Spacer(Modifier.height(if (tight) 8.dp else 10.dp))
                }
                if (body != null) {
                    Text(body, fontSize = 15.sp, lineHeight = 23.sp, color = palette.textSecondary)
                    Spacer(Modifier.height(if (tight) 16.dp else 22.dp))
                }
                content()
                Spacer(Modifier.height(16.dp))
                if (tight) {
                    footer()
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (!tight) {
                Column(Modifier.gmMaxWidth(GMLayout.Form).padding(horizontal = side)) { footer() }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Back chevron at the left edge, wordmark centred — on every screen in this sector. */
@Composable
fun AccountHeader(onBack: (() -> Unit)?) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.mail_common_back),
                    tint = palette.textPrimary,
                )
            }
        }
        Box(Modifier.align(Alignment.Center)) { GrapMailLogo(40.dp, wordmark = true) }
    }
}

/** Soft brand blobs behind the top-right corner of the sign-in card. */
@Composable
fun DecorativeBlobs() {
    val palette = LocalPalette.current
    // The app's own theme, not only the system's, so the blobs follow the palette.
    val dark = palette.isDark
    val tint = if (dark) palette.primarySolid.copy(alpha = 0.20f) else palette.primarySolid.copy(alpha = 0.07f)
    val second = if (dark) palette.primaryTop.copy(alpha = 0.12f) else palette.primaryTop.copy(alpha = 0.05f)
    Canvas(Modifier.fillMaxWidth().height(260.dp)) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(tint, Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.20f),
                radius = size.width * 0.55f,
            ),
            radius = size.width * 0.55f,
            center = Offset(size.width * 0.92f, size.height * 0.20f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(second, Color.Transparent),
                center = Offset(size.width * 0.78f, size.height * 0.62f),
                radius = size.width * 0.42f,
            ),
            radius = size.width * 0.42f,
            center = Offset(size.width * 0.78f, size.height * 0.62f),
        )
    }
}

/** Status chip: green for done, amber for "still working". */
enum class ChipTone { Success, Warning }

@Composable
fun StatusChip(text: String, tone: ChipTone) {
    val palette = LocalPalette.current
    val bg = if (tone == ChipTone.Success) palette.successChipBg else palette.warnChipBg
    val ink = if (tone == ChipTone.Success) palette.successInk else palette.warnInk
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (tone == ChipTone.Success) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(16.dp),
        )
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink)
    }
}

/**
 * The design's six (or ten) code cells: soft-filled squares, radius 14, the
 * focused cell outlined in brand blue, digits centred. Typing advances, pasting
 * fills the row, backspace walks back — the behaviour a code field is expected
 * to have, not just the look.
 *
 * Fitting: the cells are sized from the width they are given. If one row would
 * squeeze the glyphs below ~80% of the size the font setting asks for (a 320dp
 * phone at 200% font, a cover screen, a narrow split-screen pane), the code
 * wraps to two rows of half the cells each — "ABCDE" over "FGHIJ" for a backup
 * code, so the halves still read as the halves. Gaps tighten on narrow widths
 * and the cells stop growing at [CodeCellMax] on wide windows. On a phone at
 * the default font size nothing changes: one row, 8dp gaps, the same sizes.
 *
 * The two-row fallback came from screens/account/AdaptiveCodeCells.kt
 * (WrappingCodeCells), written for the 10-character backup code that clipped
 * on a 320dp phone at 200% font; every code entry now shares it.
 */
@Composable
fun CodeCells(
    length: Int,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    groupGap: Boolean = false,
    alphanumeric: Boolean = false,
    /** [GMAutofill.OneTimeCode] for texted / emailed codes, so the autofill
     *  service can offer the code it read; null (the default) for codes no
     *  service could know, such as backup codes. */
    autofill: GMAutofill? = null,
) {
    val palette = LocalPalette.current
    val focuses = remember(length) { List(length) { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    val keep: (Char) -> Boolean = { c -> if (alphanumeric) c.isLetterOrDigit() else c.isDigit() }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val baseSp = if (length > 8) 17f else 20f
        // What the font setting asks for, in dp (1sp == fontScale dp).
        val wantedDp = baseSp * fontScale
        val gap = if (maxWidth < 300.dp) 5.dp else 8.dp
        val seam = 4.dp + gap // the dash gap, including the extra spacedBy slot it adds
        fun cellWidth(perRow: Int, withSeam: Boolean): Dp {
            val gaps = gap * (perRow - 1) + if (withSeam) seam else 0.dp
            return ((maxWidth - gaps) / perRow).coerceAtMost(CodeCellMax)
        }
        // Largest glyph a cell can hold: ~0.7em wide plus side room, and the
        // line box (~1.3em) inside the 0.94 aspect-ratio square.
        fun fitDp(cell: Dp): Float =
            minOf(wantedDp, (cell.value - 6f) / 0.7f, cell.value / 0.94f / 1.3f).coerceAtLeast(8f)
        val oneRowCell = cellWidth(length, groupGap)
        val twoRows = length >= 4 && fitDp(oneRowCell) < wantedDp * 0.8f
        val perRow = if (twoRows) (length + 1) / 2 else length
        val cell = if (twoRows) cellWidth(perRow, false) else oneRowCell
        val glyphSp = (fitDp(cell) / fontScale).sp

        @Composable
        fun Cell(i: Int) {
            val char = value.getOrNull(i)?.toString() ?: ""
            var focused by remember { mutableStateOf(false) }
            val stroke by animateColorAsState(
                if (focused) palette.focusRing else palette.border,
                tween(150), label = "code-stroke",
            )
            Box(
                Modifier
                    .width(cell)
                    .aspectRatio(0.94f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.otpFill)
                    .border(if (focused) 1.5.dp else 1.dp, stroke, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = char,
                    onValueChange = { input ->
                        val accepted = input.filter(keep).let { if (alphanumeric) it.uppercase() else it }
                        val chars = value.padEnd(length).toCharArray().toMutableList()
                        if (accepted.isEmpty()) {
                            if (i < chars.size) chars[i] = ' '
                            onValueChange(chars.joinToString("").trimEnd())
                            if (i > 0) focuses[i - 1].requestFocus()
                        } else {
                            for ((offset, d) in accepted.take(length - i).withIndex()) {
                                chars[i + offset] = d
                            }
                            onValueChange(chars.joinToString("").trimEnd())
                            val next = (i + accepted.length).coerceAtMost(length - 1)
                            if (next != i) focuses[next].requestFocus() else focusManager.clearFocus()
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focuses[i])
                        // Only the first cell is the autofill node: a service
                        // fills ONE field with the whole code, and the cells
                        // then show it split exactly as if it had been typed
                        // or pasted. Marking every cell would invite a service
                        // to write the full code (or one digit) into each.
                        .gmAutofill(if (i == 0) autofill else null) { filled ->
                            val accepted = filled.filter(keep)
                                .let { if (alphanumeric) it.uppercase() else it }
                                .take(length)
                            if (accepted.isNotEmpty()) {
                                onValueChange(accepted)
                                if (accepted.length == length) focusManager.clearFocus()
                                else focuses[accepted.length].requestFocus()
                            }
                        }
                        .onFocusChanged { focused = it.isFocused },
                    textStyle = TextStyle(
                        fontSize = glyphSp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = palette.textPrimary,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (alphanumeric) KeyboardType.Ascii else KeyboardType.NumberPassword,
                        capitalization = if (alphanumeric) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                    ),
                    cursorBrush = SolidColor(palette.cursor),
                    singleLine = true,
                )
            }
        }

        if (!twoRows) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            ) {
                repeat(length) { i ->
                    if (groupGap && i == length / 2) {
                        // "ABCDE-FGHIJ": a visible seam where the dash sits, so the
                        // reader can count the halves without a stray character.
                        Box(Modifier.width(4.dp).height(1.dp).align(Alignment.CenterVertically))
                    }
                    Cell(i)
                }
            }
        } else {
            // Two rows: the row break is the seam, so no dash gap is drawn.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (start in 0 until length step perRow) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                    ) {
                        for (i in start until minOf(start + perRow, length)) Cell(i)
                    }
                }
            }
        }
    }
}

/** Cells stop growing here, so six digits on a tablet stay a code, not a row of tiles. */
private val CodeCellMax = 64.dp

/** "Code expires in 04:52" — the timer under a code row. */
@Composable
fun CodeExpiry(expiresAt: Long, onExpired: () -> Unit = {}) {
    val palette = LocalPalette.current
    var remaining by remember(expiresAt) { mutableLongStateOf(expiresAt - System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (remaining > 0) {
            delay(1000)
            remaining = expiresAt - System.currentTimeMillis()
        }
        onExpired()
    }
    if (expiresAt <= 0) return
    val minutes = (remaining.coerceAtLeast(0) / 60000).toString().padStart(2, '0')
    val seconds = ((remaining.coerceAtLeast(0) / 1000) % 60).toString().padStart(2, '0')
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.mail_code_expires_in), fontSize = 13.sp, color = palette.textSecondary,
            modifier = Modifier.weight(1f, fill = false))
        Text(
            if (remaining > 0) "$minutes:$seconds" else stringResource(R.string.mail_code_expired),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (remaining > 0) palette.primaryText else palette.error,
        )
    }
}

/** Hairline + OR + hairline. */
@Composable
fun OrDivider(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(palette.border))
        Text(stringResource(R.string.mail_divider_or), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.textTertiary)
        Box(Modifier.weight(1f).height(1.dp).background(palette.border))
    }
}

/** A centred brand link, e.g. "Resend code", "Back to Sign in". */
@Composable
fun AccountLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = palette.primaryText,
        textAlign = TextAlign.Center,
        modifier = modifier
            // 48dp touch target (Play / WCAG) without changing how the link looks.
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * The shield note that closes most screens: one hairline above, the shield
 * glyph, and a sentence that may carry a link ("support team").
 */
@Composable
fun FooterNote(
    text: String,
    modifier: Modifier = Modifier,
    linkText: String? = null,
    onLink: (() -> Unit)? = null,
    divider: Boolean = true,
) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth()) {
        if (divider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
            Spacer(Modifier.height(16.dp))
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.Rounded.VerifiedUser,
                contentDescription = null,
                tint = palette.primaryText,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = annotatedWithLink(text, linkText, onLink, palette.primaryText),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.textSecondary,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Builds the sentence with its "support team" style link inline, mid-paragraph. */
@Composable
private fun annotatedWithLink(
    text: String,
    link: String?,
    onLink: (() -> Unit)?,
    color: Color,
) = androidx.compose.ui.text.buildAnnotatedString {
    if (link == null || onLink == null || !text.contains(link)) {
        append(text)
        return@buildAnnotatedString
    }
    append(text.substringBefore(link))
    withLink(androidx.compose.ui.text.LinkAnnotation.Clickable("footer-link") { onLink() }) {
        withStyle(androidx.compose.ui.text.SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) {
            append(link)
        }
    }
    append(text.substringAfter(link))
}

/** The "What's happening?" card: hairline border, icon, title, explanation. */
@Composable
fun InfoPanel(title: String, body: String, icon: (@Composable () -> Unit)? = null) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.cardAlt)
            .border(1.dp, palette.border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(palette.softFill),
                contentAlignment = Alignment.Center,
            ) { icon() }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = palette.textSecondary)
        }
    }
}

/**
 * The two-step ribbon on the phone screens: numbered circles joined by a line,
 * a completed step becoming a check.
 */
@Composable
fun TwoStepRibbon(current: Int, labels: List<String>) {
    val palette = LocalPalette.current
    // At large text or in a very narrow window only the current step keeps
    // its label beside the circle, so the ribbon still fits one line.
    val compactLabels = gmPrefersStackedRows() || gmIsNarrowWindow()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            val n = index + 1
            val done = n < current
            val active = n == current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                done -> palette.primaryTop.copy(alpha = 0.45f)
                                active -> palette.primarySolid
                                else -> palette.stepInactive
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.onPrimary, modifier = Modifier.size(14.dp))
                    } else {
                        Text(
                            "$n",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) palette.onPrimary else palette.textSecondary,
                        )
                    }
                }
                if (!compactLabels || active) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) palette.primaryText else palette.textTertiary,
                    )
                }
            }
            if (n < labels.size) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .height(1.5.dp)
                        .background(if (done) palette.primarySolid else palette.border),
                )
            }
        }
    }
}

/** A copy affordance inside a card: clipboard glyph + label, tap copies. */
@Composable
fun CopyButton(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, palette.border, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint = palette.primaryText,
            modifier = Modifier.size(15.dp),
        )
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.primaryText)
    }
}

/** One line of the password checklist (met = filled blue check). */
@Composable
fun RuleLine(label: String, met: Boolean) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = if (met) palette.primaryText else palette.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = if (met) palette.textSecondary else palette.textTertiary,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Soft-fill icon tile used by the summary cards. */
@Composable
fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color? = null) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.softFill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: palette.primaryText, modifier = Modifier.size(20.dp))
    }
}

/** A bordered card, the shape every summary in this sector sits in. */
@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, palette.border, RoundedCornerShape(16.dp))
            .background(palette.card)
            .padding(16.dp),
        content = content,
    )
}
