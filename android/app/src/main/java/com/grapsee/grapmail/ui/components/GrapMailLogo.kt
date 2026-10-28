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
import com.grapsee.grapmail.ui.theme.Palette

/**
 * The GrapMail mark, drawn as vector so it is crisp at every density:
 * a rounded square with the blue gradient, white envelope glyph, and the
 * paper-plane accent — matching the founder's icon. With [wordmark] the
 * "GrapMail" logotype sits beside it.
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
                withStyle(androidx.compose.ui.text.SpanStyle(color = palette.primarySolid)) { append("Mail") }
            },
            fontSize = 20.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Mark(size: Dp, palette: Palette) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val corner = CornerRadius(s * 0.28f, s * 0.28f)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(palette.primaryTop, palette.primaryBottom)),
            cornerRadius = corner,
        )
        // Envelope body
        val bodyLeft = s * 0.22f; val bodyTop = s * 0.30f
        val bodyRight = s * 0.78f; val bodyBottom = s * 0.70f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(bodyLeft, bodyTop),
            size = androidx.compose.ui.geometry.Size(bodyRight - bodyLeft, bodyBottom - bodyTop),
            cornerRadius = CornerRadius(s * 0.06f, s * 0.06f),
        )
        // Flap
        val flap = Path().apply {
            moveTo(bodyLeft, bodyTop + s * 0.02f)
            lineTo((bodyLeft + bodyRight) / 2f, (bodyTop + bodyBottom) / 2f + s * 0.02f)
            lineTo(bodyRight, bodyTop + s * 0.02f)
        }
        drawPath(
            flap,
            color = palette.primaryBottom,
            style = Stroke(width = s * 0.045f, pathEffect = PathEffect.cornerPathEffect(s * 0.02f)),
        )
    }
}

/** The eyebrow label used across the design ("STEP 1 OF 5", "ACCOUNT CREATED"). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val palette = LocalPalette.current
    Text(
        text.uppercase(),
        modifier = modifier,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        color = color ?: palette.primarySolid,
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
