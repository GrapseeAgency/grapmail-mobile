package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.logic.Validators
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.theme.LocalPalette
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)
private val GENDERS = listOf("Female", "Male", "Rather not say", "Custom")

/**
 * Step 2 — "Basic Info": optional photo, birthday (13+ enforced exactly like
 * the backend), optional gender chips. Founder design verbatim.
 */
@Composable
fun BasicInfoStepScreen(vm: SignUpViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsState()

    val (iso, birthError) = Validators.birthday(
        state.bYear.toIntOrNull(), state.bMonth.toIntOrNull(), state.bDay.toIntOrNull(),
    )

    WizardScaffold(
        step = 2,
        title = "Basic Info",
        subtitle = "Tell us a bit about you.",
        onBack = onBack,
        bottomBar = {
            GradientButton(
                "Continue",
                enabled = iso != null && iso.isNotEmpty(),
                onClick = {
                    vm.update { copy(birthdayIso = iso) }
                    if (birthError == null && iso != null) onNext()
                },
            )
        },
    ) {
        // Profile photo — optional by design
        Row(
            Modifier.fillMaxWidth().padding(bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(palette.softFill)
                    .border(1.dp, palette.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(26.dp))
            }
            Column {
                Text("Profile Photo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text("Optional — you can add it later", fontSize = 13.sp, color = palette.textTertiary)
            }
        }

        AppTextField(
            label = "Birthday",
            value = listOf(state.bMonth, state.bDay, state.bYear).filter { it.isNotEmpty() }.joinToString(" / "),
            onValueChange = { raw ->
                // Accept "MM/DD/YYYY", "MM DD YYYY", or "MMDDYYYY" into three parts
                val digits = raw.filter(Char::isDigit)
                val m = digits.take(2)
                val d = digits.drop(2).take(2)
                val y = digits.drop(4).take(4)
                vm.update { copy(bMonth = m, bDay = d, bYear = y) }
            },
            hint = "MM / DD / YYYY",
            keyboard = KeyboardType.Number,
            error = birthError,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "You must be at least 13 years old to use GrapMail.",
            fontSize = 12.sp, color = palette.textTertiary,
        )

        Spacer(Modifier.height(26.dp))
        Text("Gender (optional)", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.textSecondary)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GENDERS.take(2).forEach { g ->
                GenderChip(g, state.gender == g) { vm.update { copy(gender = if (gender == g) null else g) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GENDERS.drop(2).forEach { g ->
                GenderChip(g, state.gender == g) { vm.update { copy(gender = if (gender == g) null else g) } }
            }
        }
    }
}

@Composable
private fun GenderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .then(
                if (selected) Modifier.background(palette.primarySolid)
                else Modifier.background(palette.softFill)
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) palette.onPrimary else palette.textSecondary,
        )
    }
}
