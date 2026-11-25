package com.grapsee.grapmail.ui.components

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.grapsee.grapmail.logic.AppLock
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * The app lock's door.
 *
 * Drawn above whatever screen is up when the preference is on, and emitting
 * nothing at all when it is off. The lock re-arms the moment the app leaves
 * the stage and the platform's prompt is asked on the way back in — the
 * prompt is the same one the Settings row advertises, so the two cannot drift.
 * While the door is closed the navigation under it neither shows nor hears:
 * the overlay absorbs taps, and back is answered by the door alone.
 */
@Composable
fun AppLockGate(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Every change of mind is also published to AppLock.markGateClosed, so a
    // parked deep link or notification tap (MainActivity) waits for the same
    // door this draws -- it never opens a thread under the overlay.
    var locked by remember { mutableStateOf(AppLock.isEnabled(context).also { AppLock.markGateClosed(it) }) }
    var prompted by remember { mutableStateOf(false) }

    fun ask() {
        if (!locked || prompted) return
        val activity = context.findFragmentActivity() ?: return
        prompted = true
        AppLock.prompt(activity) { verified ->
            prompted = false
            if (verified) {
                locked = false
                AppLock.markGateClosed(false)
            }
        }
    }

    // Leaving the stage re-arms the door; arriving back asks. ON_RESUME also
    // catches the cold start whose first frame lands after the activity has
    // already resumed — the LaunchedEffect is the other half of that pair.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (AppLock.isEnabled(context)) {
            locked = true
            AppLock.markGateClosed(true)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ask() }
    LaunchedEffect(Unit) {
        if (locked && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            ask()
        }
    }

    // Back closes doors, it does not open them: while the gate is closed the
    // navigation stack under it must not move.
    BackHandler(enabled = locked) { }

    val palette = LocalPalette.current
    if (locked) {
        Box(
            modifier
                .fillMaxSize()
                .background(palette.bg)
                // Taps land here and stop — the screens under the lock must
                // not hear anything.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { },
            contentAlignment = Alignment.Center,
        ) {
            // Scrolls and is capped, so the Unlock button stays reachable in a
            // landscape phone at large text and does not drift on a desktop window.
            Column(
                Modifier.widthIn(max = GMLayout.Form).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.Lock, null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.mail_lock_title),
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.mail_lock_subtitle),
                    fontSize = 13.sp, color = palette.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.primarySolid)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            role = Role.Button,
                        ) { ask() }
                        // 14dp + a 20dp line = the 48dp minimum target.
                        .padding(horizontal = 26.dp, vertical = 14.dp),
                ) {
                    Text(
                        stringResource(R.string.mail_lock_unlock),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.onPrimary,
                    )
                }
            }
        }
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var probe: Context? = this
    while (probe != null) {
        if (probe is FragmentActivity) return probe
        probe = (probe as? ContextWrapper)?.baseContext
    }
    return null
}
