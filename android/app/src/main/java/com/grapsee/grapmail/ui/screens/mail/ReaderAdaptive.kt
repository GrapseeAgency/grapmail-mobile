package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Reader / compose layout pieces that answer to the window, not the device
 * (see ui/components/Adaptive.kt for the window classes they sit beside).
 */

/**
 * How many attachment cells fit across [available]: one per ~240 dp at the
 * default font size, wider cells as the font grows (a 200 % font gets cells
 * twice as wide, so a name and its size still fit on one line), at most
 * three. A phone in portrait is one column; the reader's capped reading
 * width on a tablet is two; the wide compose form is three.
 */
internal fun attachmentGridColumns(available: Dp, fontScale: Float, gap: Dp = 8.dp): Int {
    val cell = 240.dp * fontScale.coerceIn(1f, 2f)
    val fit = ((available + gap) / (cell + gap)).toInt()
    return fit.coerceIn(1, 3)
}

/**
 * A plain grid that keeps its children in ONE flat composition, so a keyed
 * child (the compose chips' enter / exit animations) keeps its state when the
 * column count changes or a neighbour leaves; only its position moves. Every
 * cell is the same width; a row is as tall as its tallest cell. A row whose
 * cells are all zero-height (a chip that has finished shrinking away) adds no
 * gap, so a removal does not leave a hole.
 */
@Composable
internal fun AttachmentGrid(
    columns: Int,
    modifier: Modifier = Modifier,
    horizontalGap: Dp = 8.dp,
    verticalGap: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val bounded = constraints.hasBoundedWidth
        val cols = if (bounded) columns.coerceAtLeast(1) else 1
        val hGap = horizontalGap.roundToPx()
        val vGap = verticalGap.roundToPx()
        val cellConstraints = if (bounded) {
            val cell = ((constraints.maxWidth - hGap * (cols - 1)) / cols).coerceAtLeast(0)
            Constraints(minWidth = cell, maxWidth = cell)
        } else {
            constraints.copy(minWidth = 0, minHeight = 0)
        }
        val placeables = measurables.map { it.measure(cellConstraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
        val rows = placeables.chunked(cols)
        val rowHeights = rows.map { row -> row.maxOf { it.height } }
        var total = 0
        rowHeights.forEachIndexed { index, h ->
            total += h
            if (h > 0 && index < rowHeights.lastIndex) total += vGap
        }
        val width = if (bounded) constraints.maxWidth else (placeables.maxOfOrNull { it.width } ?: 0)
        val height = total.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var y = 0
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, p ->
                    val cellWidth = if (bounded) cellConstraints.maxWidth else p.width
                    p.placeRelative(c * (cellWidth + hGap), y)
                }
                y += rowHeights[r]
                if (rowHeights[r] > 0 && r < rows.lastIndex) y += vGap
            }
        }
    }
}

/**
 * The reader's small "Original colours" / "Dark colours" pill, shown in a
 * dark reader on an HTML message. It names what a tap switches TO; TalkBack
 * hears the current state and the action ("Showing dark colours, button,
 * double-tap to show this sender's original colours"). The visual pill is
 * compact; the touch target is the Material minimum (48 dp).
 */
@Composable
internal fun ReaderColoursPill(
    showingOriginal: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val label = if (showingOriginal) stringResource(R.string.reader_colours_dark)
        else stringResource(R.string.reader_colours_original)
    val action = if (showingOriginal) stringResource(R.string.reader_colours_action_dark)
        else stringResource(R.string.reader_colours_action_original)
    val state = if (showingOriginal) stringResource(R.string.reader_colours_state_original)
        else stringResource(R.string.reader_colours_state_dark)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = action, onClick = onToggle)
            .semantics { stateDescription = state }
            .testTag("mail-colours-pill")
            .heightIn(min = 28.dp)
            .background(palette.softFill, CircleShape)
            .border(1.dp, palette.border, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (showingOriginal) Icons.Rounded.DarkMode else Icons.Rounded.Palette,
            contentDescription = null,
            tint = palette.primarySolid,
            modifier = Modifier.size(14.dp),
        )
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.primarySolid)
    }
}
