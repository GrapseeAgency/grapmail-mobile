package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.components.GMMotion
import com.grapsee.grapmail.ui.components.SkeletonBone
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlin.math.ceil

/**
 * The reader's body pieces: the reading type scale, the space held for a body
 * while it loads, and the "··· Show quoted text" fold (QuotedText.kt decides
 * what folds).
 *
 * Typography: 16 sp on a 25 sp line (≈ 1.55, spec §5.1) for message text, in sp so it
 * follows the system font size (the HTML body follows the same size through
 * `textZoom`, MailHtmlBody.kt). The column the card sits in is capped at
 * [com.grapsee.grapmail.ui.components.GMLayout.Reading], so a line stays at a
 * comfortable measure on tablets and wide windows.
 *
 * Colours come from [LocalPalette] only, so a brand update flows through.
 */
internal object ReaderType {
    /** Message text. */
    val body = TextStyle(fontSize = 16.sp, lineHeight = 25.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp)
    /** Quoted history, once unfolded: the same measure, one step quieter. */
    val quoted = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal)
    /** The subject at the top of the conversation (H2, spec §5.1; iOS 22/28 bold). */
    val subject = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
}

/** Expand / fold motion for reader sections; nothing at all under reduced motion. */
internal fun readerEnter(reduced: Boolean): EnterTransition =
    if (reduced) EnterTransition.None
    else expandVertically(tween(GMMotion.MediumMs, easing = GMMotion.EmphasizedDecelerate), expandFrom = Alignment.Top) +
        fadeIn(tween(GMMotion.MediumMs, easing = GMMotion.Standard))

internal fun readerExit(reduced: Boolean): ExitTransition =
    if (reduced) ExitTransition.None
    else shrinkVertically(tween(GMMotion.MediumMs, easing = GMMotion.Standard), shrinkTowards = Alignment.Top) +
        fadeOut(tween(GMMotion.ShortMs, easing = GMMotion.EmphasizedAccelerate))

/**
 * Measured HTML body heights, remembered for this process. A card that
 * scrolls out of the lazy list and back, or is folded and opened again,
 * starts at the height it had rather than at the placeholder, so the list
 * does not jump. Keyed by the caller's part key, the document, the window
 * width and the text zoom (MailHtmlBody.kt), so a rotation or a font change
 * measures afresh.
 */
internal object ReaderBodyHeights {
    private val cache = android.util.LruCache<String, Float>(128)
    fun recall(key: String): Float? = cache.get(key)
    fun store(key: String, heightDp: Float) {
        cache.put(key, heightDp)
    }
}

/**
 * The height to hold for a body before it is drawn: the plain-text part's
 * lines at the reading size across [availableWidth], clamped. An HTML design
 * usually renders taller than its text part; the body then grows once, in
 * one animation, instead of jumping from nothing.
 */
internal fun estimatedBodyHeight(text: String?, availableWidth: Dp, fontScale: Float): Dp {
    val body = text?.trim().orEmpty()
    if (body.isEmpty()) return 220.dp
    val scale = fontScale.coerceIn(0.5f, 2.5f)
    val perLine = (availableWidth.value / (8.2f * scale)).coerceAtLeast(16f)
    var lines = 0f
    for (line in body.lineSequence()) {
        lines += maxOf(1f, ceil(line.length / perLine))
        if (lines > 40f) break
    }
    return (lines * 24f * scale + 8f).coerceIn(96f, 640f).dp
}

/**
 * Placeholder bars inside a fixed height: what the body area shows while the
 * formatted body is fetched or measured. Static (no shimmer loop), and one
 * TalkBack sentence instead of a run of unlabeled boxes.
 */
@Composable
internal fun ReaderBodySkeleton(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val label = stringResource(R.string.mail_common_loading)
    Box(modifier.background(palette.card).clearAndSetSemantics { contentDescription = label }) {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val widths = listOf(1f, 0.94f, 0.97f, 0.62f, 0f, 1f, 0.9f, 0.96f, 0.48f, 0f, 0.92f, 0.86f)
            widths.forEach { w ->
                if (w == 0f) Spacer(Modifier.height(4.dp))
                else SkeletonBone(Modifier.fillMaxWidth(w), height = 12.dp)
            }
        }
    }
}

/**
 * The "··· Show quoted text" chip. TalkBack hears the label and whether the
 * quote is open; the dots are decoration. The touch target is the Material
 * minimum even though the chip is drawn compact.
 */
@Composable
internal fun QuotedTextChip(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val label = if (expanded) stringResource(R.string.reader_quoted_hide) else stringResource(R.string.reader_quoted_show)
    val state = if (expanded) stringResource(R.string.reader_state_expanded) else stringResource(R.string.reader_state_collapsed)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .testTag("mail-quoted-toggle")
            .heightIn(min = 32.dp)
            .background(palette.softFill, CircleShape)
            .border(1.dp, palette.border, CircleShape)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!expanded) {
            Text("\u00B7\u00B7\u00B7", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.textSecondary,
                modifier = Modifier.clearAndSetSemantics {})
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.textSecondary)
    }
}

/**
 * A plain-text body at the reading size, its trailing quoted history folded
 * behind [QuotedTextChip]. Selectable is left to the system share / print
 * paths the card already has; this only draws.
 */
@Composable
internal fun PlainMailBody(
    text: String,
    forwarded: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val split = remember(text, forwarded) { QuotedText.splitPlain(text, forwarded) }
    var showQuoted by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(split.visible, style = ReaderType.body, color = palette.textPrimary,
                modifier = Modifier.fillMaxWidth().testTag("mail-body"))
        }
        val quoted = split.quoted ?: return@Column
        Spacer(Modifier.height(6.dp))
        QuotedTextChip(expanded = showQuoted, onToggle = { showQuoted = !showQuoted })
        AnimatedVisibility(visible = showQuoted, enter = readerEnter(reducedMotion), exit = readerExit(reducedMotion)) {
            SelectionContainer {
                Text(quoted, style = ReaderType.quoted, color = palette.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("mail-quoted-text"))
            }
        }
    }
}
