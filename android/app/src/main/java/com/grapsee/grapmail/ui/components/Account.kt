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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.delay

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
        val tight = maxHeight < 660.dp
        Column(Modifier.fillMaxSize()) {
            AccountHeader(onBack)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                if (illustration != null) {
                    Spacer(Modifier.height(if (tight) 2.dp else 4.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(illRes(illustration)),
                            contentDescription = null,
                            modifier = Modifier.width((if (tight) illustrationWidth * 3 / 4 else illustrationWidth).dp),
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
                        color = palette.primarySolid,
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
                Column(Modifier.padding(horizontal = 24.dp)) { footer() }
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
                    contentDescription = "Back",
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
    val dark = isSystemInDarkTheme()
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
 */
@Composable
fun CodeCells(
    length: Int,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    groupGap: Boolean = false,
    alphanumeric: Boolean = false,
) {
    val palette = LocalPalette.current
    val focuses = remember(length) { List(length) { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    val keep: (Char) -> Boolean = { c -> if (alphanumeric) c.isLetterOrDigit() else c.isDigit() }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(length) { i ->
            if (groupGap && i == length / 2) {
                // "ABCDE-FGHIJ": a visible seam where the dash sits, so the
                // reader can count the halves without a stray character.
                Box(Modifier.width(4.dp).height(1.dp).align(Alignment.CenterVertically))
            }
            val char = value.getOrNull(i)?.toString() ?: ""
            var focused by remember { mutableStateOf(false) }
            val stroke by animateColorAsState(
                if (focused) palette.primarySolid else palette.border,
                tween(150), label = "code-stroke",
            )
            Box(
                Modifier
                    .weight(1f)
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
                        .onFocusChanged { focused = it.isFocused },
                    textStyle = TextStyle(
                        fontSize = if (length > 8) 17.sp else 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = palette.textPrimary,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (alphanumeric) KeyboardType.Ascii else KeyboardType.NumberPassword,
                        capitalization = if (alphanumeric) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                    ),
                    cursorBrush = SolidColor(palette.primarySolid),
                    singleLine = true,
                )
            }
        }
    }
}

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
        Text("Code expires in ", fontSize = 13.sp, color = palette.textSecondary)
        Text(
            if (remaining > 0) "$minutes:$seconds" else "expired",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (remaining > 0) palette.primarySolid else palette.error,
        )
    }
}

/** Hairline + OR + hairline. */
@Composable
fun OrDivider(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(palette.border))
        Text(" OR ", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.textTertiary)
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
        color = palette.primarySolid,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
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
                tint = palette.primarySolid,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = annotatedWithLink(text, linkText, onLink, palette.primarySolid),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.textSecondary,
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
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    } else {
                        Text(
                            "$n",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) palette.onPrimary else palette.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) palette.primarySolid else palette.textTertiary,
                )
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
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, palette.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint = palette.primarySolid,
            modifier = Modifier.size(15.dp),
        )
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.primarySolid)
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
            tint = if (met) palette.primarySolid else palette.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = if (met) palette.textSecondary else palette.textTertiary,
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
        Icon(icon, contentDescription = null, tint = tint ?: palette.primarySolid, modifier = Modifier.size(20.dp))
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
