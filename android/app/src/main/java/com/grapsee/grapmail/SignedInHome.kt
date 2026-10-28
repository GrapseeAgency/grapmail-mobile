package com.grapsee.grapmail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.components.GrapMailLogo
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Temporary authenticated home — real session, real sign-out. The mailbox and
 * onboarding replace this in the next build phases; it exists so the auth
 * flows have an honest destination instead of a fake mailbox.
 */
@Composable
fun signedInHome(onSignOut: () -> Unit, onAccountSecurity: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    // SessionStore.address is suspend (disk IO): load it off the composition
    // thread instead of runBlocking inside it.
    var address by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        address = SessionStore.address(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        GrapMailLogo(44.dp, wordmark = true)
        Spacer(Modifier.height(28.dp))
        Text("Signed in", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(address ?: "—", fontSize = 15.sp, color = palette.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(
            "The mailbox arrives in the next build phase.",
            fontSize = 13.sp, color = palette.textTertiary,
        )
        Spacer(Modifier.height(18.dp))
        // Account security is built: the phone screens in this build live here
        // until the real settings hub exists, rather than being unreachable.
        OutlineButton("Account security", onAccountSecurity)
        Spacer(Modifier.height(12.dp))
        OutlineButton("Sign out", onSignOut)
        Spacer(Modifier.height(32.dp))
    }
}
