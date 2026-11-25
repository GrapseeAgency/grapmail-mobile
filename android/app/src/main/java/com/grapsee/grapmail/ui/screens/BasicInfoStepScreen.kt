package com.grapsee.grapmail.ui.screens

import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Man
import androidx.compose.material.icons.rounded.Woman
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.logic.GenderOption
import com.grapsee.grapmail.logic.Validators
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.theme.LocalPalette
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource


/**
 * Step 2 — "Basic Info" — founder design verbatim: the bordered Profile Photo
 * card (avatar + camera badge + "Choose Photo" outline button), Birthday as
 * three dropdowns (Month / Day / Year) with the 13+ info line, Gender as a
 * 2×2 grid of outlined icon chips, "Next →".
 */
@Composable
fun BasicInfoStepScreen(vm: SignUpViewModel, onBack: () -> Unit, onSignIn: () -> Unit, onNext: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val (iso, birthError) = Validators.birthday(
        state.bYear.toIntOrNull(), state.bMonth.toIntOrNull(), state.bDay.toIntOrNull(),
    )

    // The real photo picker (Android 13+ backed PickVisualMedia): the design
    // ships this control, so it must open a picker, not swallow the tap.
    var photoUri by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(state.photoUri)
    }
    var photoError by remember { mutableStateOf(false) }
    var photoLoading by remember { mutableStateOf(false) }
    val photoScope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) photoScope.launch {
            photoLoading = true
            val bytes = com.grapsee.grapmail.data.AvatarPhoto.encode(context, uri)
            photoLoading = false
            photoError = bytes == null
            if (bytes != null) {
                photoUri = uri.toString()
                vm.update { copy(photoUri = photoUri, photoBytes = bytes) }
            }
        }
    }
    var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(state.photoBytes) {
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            state.photoBytes?.let {
                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
            }
        }
    }

    val months = stringArrayResource(R.array.acct_months).toList()
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    WizardScaffold(
        step = 2,
        title = stringResource(R.string.acct_basic_info),
        subtitle = AnnotatedSubtitle(rest = stringResource(R.string.acct_tell_us_a_little_more_about_yourself)),
        onBack = onBack,
        onSignIn = onSignIn,
        bottomBar = {
            GradientButton(
                stringResource(R.string.acct_next),
                onClick = {
                    vm.update { copy(birthdayIso = iso) }
                    if (iso != null && birthError == null && !photoLoading) onNext()
                },
                enabled = iso != null && birthError == null && !photoLoading,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        },
    ) {
        if (photoError) Text(stringResource(R.string.acct_couldnt_open_that_photo_choose), color = palette.error)
        // ── Profile Photo card ──────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(palette.softFill),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap!!,
                            contentDescription = stringResource(R.string.acct_profile_photo),
                            modifier = Modifier.size(72.dp).clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Person,
                            contentDescription = null,
                            tint = Color(0xFF8FA8E8),
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
                Box(
                    Modifier
                        .size(26.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(palette.primarySolid),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.AddAPhoto,
                        contentDescription = null,
                        tint = palette.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Row {
                    Text(stringResource(R.string.acct_profile_photo_2), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Text(stringResource(R.string.acct_optional), fontSize = 13.sp, color = palette.textTertiary)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.acct_add_a_photo_to_make_your_account),
                    fontSize = 13.sp, lineHeight = 18.sp, color = palette.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, palette.border, RoundedCornerShape(10.dp))
                        .clickable {
                            picker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AddAPhoto, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (photoUri != null) stringResource(R.string.acct_change_photo) else stringResource(R.string.acct_choose_photo),
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.primarySolid,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // ── Birthday (Required) — three dropdowns ───────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.acct_birthday), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Text(stringResource(R.string.acct_required), fontSize = 13.sp, color = palette.textTertiary)
        }
        Spacer(Modifier.height(10.dp))
        // Three across, as designed; at large font sizes or on very narrow
        // windows Month takes its own row (so "September" is never clipped)
        // and Day / Year share the next one.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stack = fontScale > 1.3f || maxWidth < 260.dp
            val month: @Composable (Modifier) -> Unit = { m ->
                DropdownField(
                    value = state.bMonth.toIntOrNull()?.let { months.getOrNull(it - 1) } ?: "",
                    options = months,
                    placeholder = stringResource(R.string.acct_month),
                    modifier = m,
                ) { pick -> vm.update { copy(bMonth = (months.indexOf(pick) + 1).toString()) } }
            }
            val day: @Composable (Modifier) -> Unit = { m ->
                DropdownField(
                    value = state.bDay.ifEmpty { "" },
                    options = (1..31).map { it.toString() },
                    placeholder = stringResource(R.string.acct_day),
                    modifier = m,
                ) { pick -> vm.update { copy(bDay = pick) } }
            }
            val year: @Composable (Modifier) -> Unit = { m ->
                DropdownField(
                    value = state.bYear.ifEmpty { "" },
                    // Youngest eligible cohort first: the 13-to-20 group signs up
                    // most and their years sit at the top of the list.
                    options = ((java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 13) downTo 1936).map { it.toString() },
                    placeholder = stringResource(R.string.acct_year),
                    modifier = m,
                ) { pick -> vm.update { copy(bYear = pick) } }
            }
            if (!stack) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    month(Modifier.weight(1.4f))
                    day(Modifier.weight(1f))
                    year(Modifier.weight(1.1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    month(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        day(Modifier.weight(1f))
                        year(Modifier.weight(1.1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (birthError != null) {
            Text(birthError, fontSize = 13.sp, color = palette.error)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.acct_you_must_be_13_or_older_to_create_an), fontSize = 12.sp, color = palette.textTertiary)
            }
        }

        Spacer(Modifier.height(22.dp))

        // ── Gender (Optional) — 2×2 outlined icon chips ─────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Man, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.acct_gender), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Text(stringResource(R.string.acct_optional), fontSize = 13.sp, color = palette.textTertiary)
        }
        Spacer(Modifier.height(10.dp))
        // 2 x 2, as designed; one per row at large font sizes or on very narrow
        // windows, so "Prefer not to say" never squeezes into half a row.
        // The account stores the code (GenderOption); only the label is translated.
        val genders = listOf(
            Triple(stringResource(R.string.profile_gender_female), Icons.Rounded.Woman, GenderOption.FEMALE),
            Triple(stringResource(R.string.profile_gender_male), Icons.Rounded.Man, GenderOption.MALE),
            Triple(stringResource(R.string.profile_gender_prefer_not_to_say), Icons.Rounded.Person, GenderOption.PREFER_NOT_TO_SAY),
            Triple(stringResource(R.string.profile_gender_custom), Icons.Rounded.AccountCircle, GenderOption.CUSTOM),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = if (fontScale > 1.3f || maxWidth < 260.dp) 1 else 2
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                genders.chunked(perRow).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowItems.forEach { (label, icon, option) ->
                            Box(Modifier.weight(1f)) {
                                GenderCard(label, icon, GenderOption.normalize(state.gender) == option) {
                                    vm.update { copy(gender = if (GenderOption.normalize(gender) == option) null else option) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenderCard(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) palette.primarySolid.copy(alpha = 0.06f) else palette.card)
            .border(
                1.dp,
                if (selected) palette.primarySolid else palette.border,
                RoundedCornerShape(12.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) palette.primarySolid else palette.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) palette.primarySolid else palette.textPrimary,
        )
    }
}

/**
 * The design's dropdown, as an inline animated panel instead of a popup
 * window: same look when closed, and the option list renders in the main
 * window — reachable by screen readers, automation, and the same tap
 * geometry on every device.
 */
@Composable
private fun DropdownField(
    value: String,
    options: List<String>,
    placeholder: String,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    if (open) palette.primarySolid else palette.border,
                    RoundedCornerShape(12.dp),
                )
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value.ifEmpty { placeholder },
                fontSize = 13.sp,
                color = if (value.isEmpty()) palette.textTertiary else palette.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(if (open) "▴" else "▾", fontSize = 12.sp, color = palette.textTertiary)
        }
        androidx.compose.animation.AnimatedVisibility(visible = open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.card)
                    .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                    .heightIn(max = 210.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { opt ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(opt)
                                open = false
                            }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            opt,
                            fontSize = 13.sp,
                            color = if (opt == value) palette.primarySolid else palette.textPrimary,
                            fontWeight = if (opt == value) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
