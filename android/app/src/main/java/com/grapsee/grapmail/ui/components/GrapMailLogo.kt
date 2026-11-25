package com.grapsee.grapmail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.theme.Palette

/**
 * The GrapMail mark with, when [wordmark] is set, the "GrapMail" logotype
 * beside it.
 *
 * The mark is the founder's own logo file, so its cobalt field and envelope
 * come from the artwork, not from a gradient drawn here. Retuning it to the
 * signature gradient is the app-icon work in round8-spec §1.14, and that waits
 * for founder sign-off. The logotype follows the Round 8 tokens: "Mail" is
 * drawn in `primaryText`, which is cobalt #1F5EFF in light and #8EA8FF in
 * dark. The old filled-button blue read at only ≈3.9:1 on the near-black
 * canvas.
 */
@Composable
fun GrapMailLogo(size: Dp = 44.dp, wordmark: Boolean = false) {
    val palette = LocalPalette.current
    if (!wordmark) { Mark(size, palette); return }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Mark(size, palette)
        // Two-tone logotype — every founder frame shows "Grap" in the text
        // color and "Mail" in brand blue.
        Text(
            buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = palette.textPrimary)) { append("Grap") }
                withStyle(androidx.compose.ui.text.SpanStyle(color = palette.primaryText)) { append("Mail") }
            },
            fontSize = 20.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Mark(size: Dp, palette: Palette) {
    // The founder's real mark — the actual logo file, never a drawn imitation.
    // ContentScale.Fit: the whole logo is always visible, uncropped, at every
    // size and on every screen shape; its own transparency is the shape.
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(R.drawable.grapmail_logo),
        contentDescription = null,
        modifier = Modifier.size(size),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
}

/**
 * The eyebrow label used across the design ("STEP 1 OF 5", "ACCOUNT CREATED").
 * 11 sp text, so it takes `primaryText`, which passes AA on both canvases,
 * rather than the filled-button cobalt.
 */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val palette = LocalPalette.current
    Text(
        text.uppercase(),
        modifier = modifier,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        color = color ?: palette.primaryText,
    )
}

@Preview(showBackground = true)
@Composable
private fun LogoPreview() {
    val palette = LocalPalette.current
    Column(Modifier.background(palette.bg).padding(24.dp)) {
        GrapMailLogo(44.dp, wordmark = true)
        Spacer(Modifier.height(12.dp))
        Eyebrow("Step 1 of 5")
    }
}
