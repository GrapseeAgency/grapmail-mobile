@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.grapsee.grapmail.ui.screens.account

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.RememberedAccounts
import com.grapsee.grapmail.logic.AccountFlowViewModel
import com.grapsee.grapmail.ui.artwork.Artwork
import com.grapsee.grapmail.ui.components.GMMotion
import com.grapsee.grapmail.ui.components.GMWidth
import com.grapsee.grapmail.ui.components.GrapMailLogo
import com.grapsee.grapmail.ui.components.gmIsShortWindow
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.components.gmWidthClass
import com.grapsee.grapmail.ui.components.rememberMotionReduced
import com.grapsee.grapmail.ui.theme.Body
import com.grapsee.grapmail.ui.theme.BodyMedium
import com.grapsee.grapmail.ui.theme.Caption
import com.grapsee.grapmail.ui.theme.H2
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.ui.theme.Title
import kotlinx.coroutines.launch

/**
 * The account chooser (round8-spec §2.6): two or more remembered accounts
 * are signed out on this device, so the person picks one first.
 *
 * The chooser and the welcome-back stage for the picked account live in ONE
 * container — a [SharedTransitionLayout] around an [AnimatedContent] — so the
 * row's 48 avatar travels into the 88 hero as a shared element (§2.7,
 * `long` 300 ms, standard easing) without a navigation push; Back returns to
 * the list. Reduce motion: a plain crossfade, no shared element.
 *
 * Signed-in accounts never appear here (they stay in the account switcher).
 */
@Composable
fun AccountChooserScreen(
    flow: AccountFlowViewModel,
    onSignedIn: (address: String) -> Unit,
    onNeedSecondFactor: () -> Unit,
    onForgotPassword: () -> Unit,
    onUseAnotherAccount: () -> Unit,
    /** The standard sign-in form, replacing this screen (nothing remembered is left, or a blocked account). */
    onStandardSignIn: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = rememberMotionReduced()
    val accounts by rememberSignedOutAccounts()
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    BackHandler(enabled = picked != null) { picked = null }

    val list = accounts
    if (list == null) {
        Box(Modifier.fillMaxSize().background(palette.bg))
        return
    }
    if (list.isEmpty()) {
        if (!leaving) LaunchedEffect(Unit) { onStandardSignIn() }
        Box(Modifier.fillMaxSize().background(palette.bg))
        return
    }
    LaunchedEffect(list, picked) {
        // The picked account was removed or signed in meanwhile: back to the list.
        if (picked != null && list.none { it.address == picked }) picked = null
    }

    fun remove(record: RememberedAccounts.Record) {
        leaving = true
        scope.launch {
            RememberedAccounts.forget(context, record.address)
            val remaining = RememberedAccounts.signedOut(context).size
            if (remaining == 0) {
                Toast.makeText(context, context.getString(R.string.wb_removed), Toast.LENGTH_SHORT).show()
                onStandardSignIn()
            } else {
                leaving = false
                picked = null
                snackbar.showSnackbar(context.getString(R.string.wb_removed))
            }
        }
    }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = picked,
                transitionSpec = {
                    if (reduced) {
                        fadeIn(tween(GMMotion.ShortMs)) togetherWith fadeOut(tween(GMMotion.ShortMs))
                    } else {
                        fadeIn(tween(GMMotion.LongMs, easing = GMMotion.Standard)) togetherWith
                            fadeOut(tween(GMMotion.ShortMs))
                    }
                },
                label = "wb-chooser",
            ) { target ->
                val record = target?.let { address -> list.firstOrNull { it.address == address } }
                if (record == null) {
                    ChooserContent(
                        accounts = list,
                        avatarModifier = { row -> wbSharedAvatar(row.address, this@AnimatedContent, reduced) },
                        onPick = { picked = it.address },
                        onRemove = { remove(it) },
                        onUseAnotherAccount = {
                            RememberedAccounts.skipForThisLaunch()
                            onUseAnotherAccount()
                        },
                    )
                } else {
                    WelcomeBackContent(
                        record = record,
                        flow = flow,
                        avatarModifier = wbSharedAvatar(record.address, this@AnimatedContent, reduced),
                        animateEntrance = false,
                        onSignedIn = onSignedIn,
                        onNeedSecondFactor = onNeedSecondFactor,
                        onForgotPassword = onForgotPassword,
                        onUseAnotherAccount = onUseAnotherAccount,
                        onStandardSignIn = onStandardSignIn,
                        onRemove = { remove(record) },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

private val WbAvatarBounds = BoundsTransform { _, _ ->
    tween<Rect>(GMMotion.LongMs, easing = GMMotion.Standard)
}

/** The avatar's shared element between the chooser row (48) and the welcome-back hero (88). */
@Composable
private fun SharedTransitionScope.wbSharedAvatar(
    address: String,
    animatedScope: AnimatedVisibilityScope,
    reduced: Boolean,
): Modifier {
    if (reduced) return Modifier
    val state = rememberSharedContentState(key = "wb-avatar-$address")
    return Modifier.sharedElement(state, animatedScope, boundsTransform = WbAvatarBounds)
}

/** The list stage: title, the rows in a surface card, and "Use another account". */
@Composable
private fun ChooserContent(
    accounts: List<RememberedAccounts.Record>,
    avatarModifier: @Composable (RememberedAccounts.Record) -> Modifier,
    onPick: (RememberedAccounts.Record) -> Unit,
    onRemove: (RememberedAccounts.Record) -> Unit,
    onUseAnotherAccount: () -> Unit,
) {
    val palette = LocalPalette.current
    val header: @Composable (TextAlign) -> Unit = { align ->
        Text(
            stringResource(R.string.wb_chooser_title),
            style = H2,
            color = palette.textPrimary,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.wb_chooser_subtitle),
            style = Body,
            color = palette.textSecondary,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val card: @Composable () -> Unit = {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(palette.card)
                .border(1.dp, palette.border, shape),
        ) {
            accounts.forEachIndexed { index, record ->
                if (index > 0) {
                    Box(Modifier.padding(start = 78.dp).fillMaxWidth().height(1.dp).background(palette.border))
                }
                ChooserRow(
                    record = record,
                    avatarModifier = avatarModifier(record),
                    onPick = { onPick(record) },
                    onRemove = { onRemove(record) },
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(role = Role.Button, onClick = onUseAnotherAccount)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.AddCircleOutline,
                        contentDescription = null,
                        tint = palette.primarySolid,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.wb_use_another_account), style = BodyMedium, color = palette.primarySolid)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        val short = gmIsShortWindow()
        val twoPane = !short && gmWidthClass() != GMWidth.Compact && maxWidth >= 600.dp
        when {
            twoPane -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Artwork(
                        "welcome",
                        modifier = Modifier
                            .padding(32.dp)
                            .widthIn(max = 420.dp)
                            .fillMaxWidth()
                            .aspectRatio(2f),
                    )
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.heightIn(min = viewport).padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                            header(TextAlign.Start)
                            Spacer(Modifier.height(20.dp))
                            card()
                        }
                    }
                }
            }

            // Landscape phone: the list only — no illustration, no wordmark.
            short -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .gmMaxWidth(560.dp),
            ) {
                header(TextAlign.Start)
                Spacer(Modifier.height(12.dp))
                card()
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .gmMaxWidth(420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))
                GrapMailLogo(28.dp, wordmark = true)
                Spacer(Modifier.height(32.dp))
                header(TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                card()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * One remembered account, 72 high: avatar 48 · name · masked address ·
 * overflow. The row (all but the overflow's 44 target) continues as that
 * account; TalkBack hears "Continue as {name}, {spoken masked address}" and
 * gets "Remove from this device" through the overflow button.
 */
@Composable
private fun ChooserRow(
    record: RememberedAccounts.Record,
    avatarModifier: Modifier,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalPalette.current
    val masked = remember(record.address) { wbMaskAddress(record.address) }
    val spoken = masked.spoken()
    val name = record.displayName.trim().ifEmpty { masked.text }
    val rowLabel = stringResource(R.string.wb_chooser_row_description, name, spoken)
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by rememberSaveable(record.address) { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 72.dp)
                .clickable(role = Role.Button, onClick = onPick)
                .clearAndSetSemantics {
                    contentDescription = rowLabel
                    role = Role.Button
                    onClick { onPick(); true }
                }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RememberedAvatar(record, 48.dp, avatarModifier)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = Title,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                WbMiddleEllipsisText(
                    masked.text,
                    style = Caption,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Start,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.wb_chooser_more_options, name),
                    tint = palette.textSecondary,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = palette.card,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.wb_remove_from_device), style = Body, color = palette.textPrimary) },
                    leadingIcon = { Icon(Icons.Rounded.PersonRemove, contentDescription = null, tint = palette.textSecondary) },
                    onClick = {
                        menuOpen = false
                        confirmRemove = true
                    },
                )
            }
        }
        Spacer(Modifier.width(4.dp))
    }

    if (confirmRemove) {
        RemoveConfirmDialog(
            who = wbFirstName(record) ?: masked.text,
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}
