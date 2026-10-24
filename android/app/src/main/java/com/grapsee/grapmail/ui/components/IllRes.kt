package com.grapsee.grapmail.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.grapsee.grapmail.R

/**
 * Theme-aware illustration picker: the founder's designs ship paired light
 * and dark artwork, so dark mode uses the dark crops — never a tinted light
 * image glaring on a dark surface.
 */
@Composable
fun illRes(name: String): Int {
    val dark = isSystemInDarkTheme()
    return when (name) {
        "hero" -> if (dark) R.drawable.ill_hero_dark else R.drawable.ill_hero
        "verify" -> if (dark) R.drawable.ill_verify_dark else R.drawable.ill_verify
        "welcome" -> if (dark) R.drawable.ill_welcome_dark else R.drawable.ill_welcome
        "step1_name" -> if (dark) R.drawable.ill_step1_name_dark else R.drawable.ill_step1_name
        "created_a" -> if (dark) R.drawable.ill_created_a_dark else R.drawable.ill_created_a
        "created_b" -> if (dark) R.drawable.ill_created_b_dark else R.drawable.ill_created_b
        "puzzle" -> if (dark) R.drawable.puzzle_photo_dark else R.drawable.puzzle_photo
        else -> R.drawable.ill_welcome
    }
}
