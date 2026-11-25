@file:OptIn(ExperimentalComposeUiApi::class)

package com.grapsee.grapmail.ui.components

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/**
 * What a credential field holds, told to the platform's autofill framework so
 * Google Password Manager, 1Password, Bitwarden and the other autofill services
 * can offer (and generate) the right value for it.
 *
 * Call sites only name the kind; [AppTextField] and [CodeCells] carry the
 * mechanism. A field that is not a GrapMail credential (an IMAP password for
 * another provider, an E2E passphrase, a recovery address) passes nothing and
 * stays invisible to autofill, so a password manager never offers the GrapMail
 * password where it does not belong.
 */
enum class GMAutofill {
    /** The GrapMail address / sign-in identifier: username + email hints. */
    Username,

    /** The account's existing password (sign-in, re-confirm, current password). */
    Password,

    /** A password being chosen (sign-up, reset, change): lets managers suggest a strong one. */
    NewPassword,

    /** A one-time code sent by text or email. */
    OneTimeCode,
}

private fun GMAutofill.autofillTypes(): List<AutofillType> = when (this) {
    GMAutofill.Username -> listOf(AutofillType.Username, AutofillType.EmailAddress)
    GMAutofill.Password -> listOf(AutofillType.Password)
    GMAutofill.NewPassword -> listOf(AutofillType.NewPassword)
    GMAutofill.OneTimeCode -> listOf(AutofillType.SmsOtpCode)
}

/**
 * Registers the field it modifies as an autofill node of [kind].
 *
 * This project is on Compose UI 1.7 (compose-bom 2024.09.03), which predates
 * the `semantics { contentType = ContentType.… }` API of Compose 1.8, so this
 * is the established AutofillNode / LocalAutofillTree pattern:
 *  - the node is added to the composition's autofill tree while the field is
 *    composed, and removed again when it leaves (otherwise a sign-in password
 *    node from a screen that is gone would keep being reported to the
 *    autofill service on every later screen);
 *  - its bounds follow the field, so the service anchors its suggestion there;
 *  - focusing the field asks the service for suggestions, leaving it cancels.
 *
 * [onFill] receives the value the service supplies; pass the field's own
 * onValueChange so a filled value goes through exactly the path typing does.
 * Nothing visual is added. On API levels without an autofill framework
 * (LocalAutofill is null) the node is inert.
 */
fun Modifier.gmAutofill(kind: GMAutofill?, onFill: (String) -> Unit): Modifier {
    if (kind == null) return this
    return this.composed {
        val autofill = LocalAutofill.current
        val tree = LocalAutofillTree.current
        val latestOnFill by rememberUpdatedState(onFill)
        val node = remember(kind) {
            AutofillNode(autofillTypes = kind.autofillTypes(), onFill = { value -> latestOnFill(value) })
        }
        DisposableEffect(tree, node) {
            tree += node
            onDispose {
                autofill?.cancelAutofillForNode(node)
                // AutofillTree has no remove operator; its children map is the
                // registry, so the node is taken out of it directly.
                @Suppress("USELESS_CAST", "UNCHECKED_CAST")
                val registry = tree.children as? MutableMap<Int, AutofillNode>
                registry?.remove(node.id)
            }
        }
        Modifier
            .onGloballyPositioned { coordinates -> node.boundingBox = coordinates.boundsInWindow() }
            .onFocusChanged { state ->
                val service = autofill ?: return@onFocusChanged
                if (state.isFocused) {
                    // requestAutofillForNode throws without bounds; the first
                    // layout pass sets them before a person can focus the field.
                    if (node.boundingBox != null) service.requestAutofillForNode(node)
                } else {
                    service.cancelAutofillForNode(node)
                }
            }
    }
}
