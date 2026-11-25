package com.grapsee.grapmail.ui.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Text that reaches the screen from code with no composable scope: ViewModels,
 * workers, notification builders, validators, the feature engines.
 *
 * Two ways in, chosen by how much a change would ripple:
 *
 *  - [UiText] for new state that can carry a resource id to the screen and be
 *    resolved there with [asString].
 *  - [CoreStrings] where an existing API already hands a plain `String` to
 *    screens owned elsewhere: the string is resolved against the application
 *    context at the point it is built, so no consumer's type changes.
 *
 * [CoreStrings] always carries the English wording next to the resource id.
 * Before [CoreStrings.install] runs — a plain JVM unit test, which has no
 * Context — that English is returned unchanged, so behaviour under test is
 * exactly the shipped English text. In the app the resource wins. The English
 * in `res/values/strings_core.xml` must stay byte-identical to the fallback.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun raw(text: String): UiText = Raw(text)
    }
}

fun UiText.asString(context: Context): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, count, *args.toTypedArray())
}

@Composable
fun UiText.asString(): String = asString(LocalContext.current)

object CoreStrings {
    @Volatile
    private var appContext: Context? = null

    /** Called once from GrapMailApp.onCreate. */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The localized string for [id], formatted with [args]; [english] when no
     * application context is installed (JVM unit tests) or the lookup fails.
     */
    fun get(@StringRes id: Int, vararg args: Any, english: () -> String): String {
        val ctx = appContext ?: return english()
        return try {
            if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
        } catch (_: Exception) {
            english()
        }
    }

    /** Quantity string for [count]; [english] under the same rules as [get]. */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any, english: () -> String): String {
        val ctx = appContext ?: return english()
        return try {
            ctx.resources.getQuantityString(id, count, *args)
        } catch (_: Exception) {
            english()
        }
    }
}
