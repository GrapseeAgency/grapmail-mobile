package com.grapsee.grapmail.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import java.text.BreakIterator
import java.util.Locale

/*
 * GrapMail's avatar system — docs/design/round8-spec.md §1.6.
 *
 * Everything in this file is shared by contract with iOS (`Avatar.swift`) and
 * web (`avatarColour.ts`):
 *  - the 10-hue palette, IN THIS ORDER (index N is the same colour everywhere);
 *  - the key normalisation and the FNV-1a 32-bit hash that pick the index;
 *  - the initials rule and the masked-address rule.
 *
 * The hash and text helpers are plain Kotlin (no Android APIs) so the JVM unit
 * test can assert the published vectors (AvatarColorsTest.kt, and
 * docs/design/avatar-hash-vectors.json).
 */

/** Light-theme disc fills, §1.6.1. White initials on each are ≥ 5.0:1. */
val AvatarPaletteLight: List<Color> = listOf(
    Color(0xFF2456E8), // 0 Cobalt
    Color(0xFF5B47E0), // 1 Indigo
    Color(0xFF8E3FD1), // 2 Grape
    Color(0xFFB5307A), // 3 Berry
    Color(0xFFC93A3A), // 4 Coral
    Color(0xFFB5520F), // 5 Ember
    Color(0xFF3B7D23), // 6 Fern
    Color(0xFF0E7C7B), // 7 Lagoon
    Color(0xFF0B6FA8), // 8 Ocean
    Color(0xFF52607A), // 9 Slate
)

/** Dark-theme disc fills: each light value × 0.9 per channel, §1.6.1. */
val AvatarPaletteDark: List<Color> = listOf(
    Color(0xFF204DD1), // 0 Cobalt
    Color(0xFF5240CA), // 1 Indigo
    Color(0xFF8039BC), // 2 Grape
    Color(0xFFA32B6E), // 3 Berry
    Color(0xFFB53434), // 4 Coral
    Color(0xFFA34A0E), // 5 Ember
    Color(0xFF357120), // 6 Fern
    Color(0xFF0D706F), // 7 Lagoon
    Color(0xFF0A6497), // 8 Ocean
    Color(0xFF4A566E), // 9 Slate
)

/** The initials and the person glyph on every disc: white in both themes, never the dynamic onPrimary. */
val AvatarInk: Color = Color(0xFFFFFFFF)

/** Number of palette slots. Part of the contract: the index is `hash mod 10`. */
const val AVATAR_SLOTS: Int = 10

// ── key + hash (§1.6.2) ────────────────────────────────────────────────────

private fun isAsciiSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'

/**
 * `normalize(s)` from §1.6.2:
 *  1. if `s` holds a name-addr `<…>`, keep what is inside the angle brackets;
 *  2. trim ASCII whitespace;
 *  3. lowercase ASCII A–Z only, never with the locale (a Turkish-locale device
 *     must not move `I` to a different slot); other characters pass through;
 *  4. keep `+tags` and dots.
 */
fun normalizeAvatarKey(s: String): String {
    var t = s
    val open = t.indexOf('<')
    if (open >= 0) {
        val close = t.indexOf('>', open + 1)
        if (close > open) t = t.substring(open + 1, close)
    }
    t = t.trim(::isAsciiSpace)
    val out = StringBuilder(t.length)
    for (c in t) out.append(if (c in 'A'..'Z') c + 32 else c)
    return out.toString()
}

/** `key = normalize(address) or normalize(displayName) or "?"`. */
fun avatarKey(address: String?, displayName: String? = null): String {
    val fromAddress = normalizeAvatarKey(address.orEmpty())
    if (fromAddress.isNotEmpty()) return fromAddress
    val fromName = normalizeAvatarKey(displayName.orEmpty())
    return fromName.ifEmpty { "?" }
}

/** FNV-1a 32-bit over the UTF-8 bytes of [key], as a signed Int holding the unsigned bits. */
fun fnv1a32(key: String): Int {
    var h = 0x811C9DC5.toInt()
    for (b in key.toByteArray(Charsets.UTF_8)) {
        h = h xor (b.toInt() and 0xFF)
        h *= 0x01000193
    }
    return h
}

/** The palette slot for an already-normalised key: the hash as UNSIGNED 32-bit, mod 10. */
fun avatarIndexForKey(key: String): Int =
    ((fnv1a32(key).toLong() and 0xFFFFFFFFL) % AVATAR_SLOTS).toInt()

/** The palette slot for a sender: address first, then display name, then "?". */
fun avatarIndex(address: String?, displayName: String? = null): Int =
    avatarIndexForKey(avatarKey(address, displayName))

/** The disc colour for a sender in the given theme. */
fun avatarColor(address: String?, displayName: String?, dark: Boolean): Color =
    (if (dark) AvatarPaletteDark else AvatarPaletteLight)[avatarIndex(address, displayName)]

/**
 * The disc fill (§1.6.4): a vertical gradient from the hue mixed 8 % with white
 * at the top to the hue itself at the bottom. Contrast is measured against the
 * bottom stop, which is the darker one.
 */
fun avatarFill(hue: Color): Brush {
    val top = Color(
        red = hue.red + (1f - hue.red) * 0.08f,
        green = hue.green + (1f - hue.green) * 0.08f,
        blue = hue.blue + (1f - hue.blue) * 0.08f,
        alpha = hue.alpha,
    )
    return Brush.verticalGradient(listOf(top, hue))
}

// ── initials (§1.6.3) ──────────────────────────────────────────────────────

private val WORD_SEPARATORS = Regex("[\\s._\\-]+")

private fun graphemes(s: String): List<String> {
    if (s.isEmpty()) return emptyList()
    val it = BreakIterator.getCharacterInstance()
    it.setText(s)
    val out = ArrayList<String>()
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        out.add(s.substring(start, end))
        start = end
        end = it.next()
    }
    return out
}

/** The first grapheme of [word] that starts with a letter or digit, skipping quotes, emoji and the like. */
private fun leadingLetter(word: String): String? =
    graphemes(word).firstOrNull { g -> Character.isLetterOrDigit(g.codePointAt(0)) }

/** The part of an address before its LAST `@`, after taking a name-addr apart. */
private fun localPartOf(address: String?): String {
    val raw = address.orEmpty()
    val open = raw.indexOf('<')
    val inner = if (open >= 0 && raw.indexOf('>', open + 1) > open) {
        raw.substring(open + 1, raw.indexOf('>', open + 1))
    } else raw
    return inner.trim(::isAsciiSpace).substringBeforeLast('@')
}

/**
 * The letters on the disc, as graphemes, uppercased in the device locale.
 *
 * The display name is used when there is one, otherwise the address's local
 * part. It is split on whitespace, `.`, `_` and `-`, and the first word that
 * has a letter or digit gives the first grapheme. With [twoLetters] (discs of
 * 56 dp and up) and at least two words, the last word's first grapheme is
 * added. An empty list means: draw the person glyph.
 */
fun avatarInitials(displayName: String?, address: String?, twoLetters: Boolean = false): List<String> {
    val source = displayName.orEmpty().trim(::isAsciiSpace).ifEmpty { localPartOf(address) }
    val words = source.split(WORD_SEPARATORS).filter { it.isNotEmpty() }
    val locale = Locale.getDefault()
    var firstAt = -1
    var first: String? = null
    for ((i, w) in words.withIndex()) {
        val g = leadingLetter(w)
        if (g != null) { first = g; firstAt = i; break }
    }
    if (first == null) return emptyList()
    val out = mutableListOf(first.uppercase(locale))
    if (twoLetters && words.size >= 2 && firstAt < words.lastIndex) {
        leadingLetter(words.last())?.let { out.add(it.uppercase(locale)) }
    }
    return out
}

// ── masked address (§2.2) — Workstream B imports this ─────────────────────

/** The three visible parts of a masked address; [domain] is empty when there was no `@`. */
data class MaskedAddress(val start: String, val end: String, val domain: String) {
    /** e.g. `ol•••r@grapmail.com`. */
    val text: String get() = start + AVATAR_MASK + end + (if (domain.isEmpty()) "" else "@$domain")
}

/** Three U+2022 bullets, no spaces. */
const val AVATAR_MASK: String = "\u2022\u2022\u2022"

/**
 * Splits at the LAST `@`. With n graphemes in the local part:
 * n ≤ 2 → first + •••; 3–4 → first + ••• + last; ≥ 5 → first two + ••• + last.
 * The domain is always shown in full.
 */
fun maskAddressParts(address: String): MaskedAddress {
    val trimmed = address.trim(::isAsciiSpace)
    val at = trimmed.lastIndexOf('@')
    val local = if (at >= 0) trimmed.substring(0, at) else trimmed
    val domain = if (at >= 0) trimmed.substring(at + 1) else ""
    val g = graphemes(local)
    val n = g.size
    return when {
        n == 0 -> MaskedAddress("", "", domain)
        n <= 2 -> MaskedAddress(g[0], "", domain)
        n <= 4 -> MaskedAddress(g[0], g[n - 1], domain)
        else -> MaskedAddress(g[0] + g[1], g[n - 1], domain)
    }
}

/** `olivier@grapmail.com` → `ol•••r@grapmail.com`. */
fun maskAddress(address: String): String = maskAddressParts(address).text
