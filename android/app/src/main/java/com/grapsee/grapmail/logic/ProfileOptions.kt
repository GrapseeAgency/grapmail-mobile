package com.grapsee.grapmail.logic

import androidx.annotation.StringRes
import com.grapsee.grapmail.R
import java.util.Locale

/**
 * Gender as the account stores it: a stable code, never display text.
 *
 * Earlier app versions sent (and the server stored) the English label
 * ("Female", "Rather not say", ...), which no screen could translate. The
 * screens now send one of [ALL] and show a localized label for it
 * (res/values/strings_profile.xml). The server maps the legacy English words
 * to the same codes on input, and [normalize] does the same on the way in, so
 * a profile that still holds "Female" selects and shows the right option.
 *
 * Must stay in step with services/shared/src/lib/profile-options.ts on the
 * backend and ios/GrapMail/ProfileOptions.swift.
 */
object GenderOption {
    const val FEMALE = "female"
    const val MALE = "male"
    const val PREFER_NOT_TO_SAY = "prefer_not_to_say"
    const val CUSTOM = "custom"

    /** Every code, in the order the screens show them. */
    val ALL: List<String> = listOf(FEMALE, MALE, PREFER_NOT_TO_SAY, CUSTOM)

    /** Lower-cased, `_`-joined spellings (codes and legacy English labels) to the code. */
    private val ALIASES: Map<String, String> = mapOf(
        "female" to FEMALE,
        "male" to MALE,
        "prefer_not_to_say" to PREFER_NOT_TO_SAY,
        "rather_not_say" to PREFER_NOT_TO_SAY,
        "custom" to CUSTOM,
    )

    private val SEPARATORS = Regex("[\\s-]+")

    /**
     * The code for [raw], which may be a code or a legacy English label.
     * Blank becomes null. A value that is neither is returned trimmed and
     * unchanged, so nothing the server holds is lost or rewritten.
     */
    fun normalize(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val key = value.lowercase(Locale.ROOT).replace(SEPARATORS, "_")
        return ALIASES[key] ?: value
    }

    /** The label resource for [code] (code or legacy label), or null when it is not a known option. */
    @StringRes
    fun labelRes(code: String?): Int? = when (normalize(code)) {
        FEMALE -> R.string.profile_gender_female
        MALE -> R.string.profile_gender_male
        PREFER_NOT_TO_SAY -> R.string.profile_gender_prefer_not_to_say
        CUSTOM -> R.string.profile_gender_custom
        else -> null
    }
}
