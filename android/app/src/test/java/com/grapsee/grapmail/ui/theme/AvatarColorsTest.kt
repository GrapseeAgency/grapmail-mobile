package com.grapsee.grapmail.ui.theme

import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The avatar contract from docs/design/round8-spec.md §1.6. iOS and web assert the
 * same vectors, so a sender wears the same colour on all three platforms.
 */
class AvatarColorsTest {

    private fun unsigned(h: Int): Long = h.toLong() and 0xFFFFFFFFL

    // ── §1.6.2 required vectors (published FNV-1a 32 values) ───────────────

    @Test fun fnvEmptyString() {
        assertEquals(0x811C9DC5L, unsigned(fnv1a32("")))
        assertEquals(1, avatarIndexForKey(""))
    }

    @Test fun fnvA() {
        assertEquals(0xE40C292CL, unsigned(fnv1a32("a")))
        assertEquals(0, avatarIndexForKey("a"))
    }

    @Test fun fnvB() {
        assertEquals(0xE70C2DE5L, unsigned(fnv1a32("b")))
        assertEquals(7, avatarIndexForKey("b"))
    }

    @Test fun fnvC() {
        assertEquals(0xE60C2C52L, unsigned(fnv1a32("c")))
        assertEquals(8, avatarIndexForKey("c"))
    }

    @Test fun fnvFoobar() {
        assertEquals(0xBF9CF968L, unsigned(fnv1a32("foobar")))
        assertEquals(0, avatarIndexForKey("foobar"))
    }

    // ── palette shape ─────────────────────────────────────────────────────

    @Test fun paletteHasTenSlotsPerTheme() {
        assertEquals(AVATAR_SLOTS, AvatarPaletteLight.size)
        assertEquals(AVATAR_SLOTS, AvatarPaletteDark.size)
        assertEquals(AvatarPaletteLight, GrapMailAvatarRampLight)
        assertEquals(AvatarPaletteDark, GrapMailAvatarRampDark)
    }

    // ── normalisation ─────────────────────────────────────────────────────

    @Test fun nameAddrCaseAndSpaceAllNormaliseToTheSameKey() {
        val plain = "olivier@grapmail.com"
        assertEquals(plain, normalizeAvatarKey("  Olivier@GrapMail.COM \t"))
        assertEquals(plain, normalizeAvatarKey("Olivier Pricious <Olivier@grapmail.com>"))
        assertEquals(avatarIndex(plain), avatarIndex("Olivier Pricious <OLIVIER@grapmail.com>"))
    }

    @Test fun tagsAndDotsAreKept() {
        assertEquals("a.b+news@x.com", normalizeAvatarKey("A.B+News@X.com"))
        assertTrue(normalizeAvatarKey("a.b+news@x.com") != normalizeAvatarKey("ab@x.com"))
    }

    @Test fun lowercasingIsAsciiOnlyAndIgnoresTheLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // Locale lowercasing would turn "I" into a dotless "ı" here.
            assertEquals("ivan@x.com", normalizeAvatarKey("IVAN@X.COM"))
            // Non-ASCII passes through untouched, "İ" included.
            assertEquals("\u0130pek@x.com", normalizeAvatarKey("\u0130PEK@X.COM"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun keyFallsBackToNameThenQuestionMark() {
        assertEquals("team grapmail", avatarKey(null, "Team GrapMail"))
        assertEquals("?", avatarKey("  ", "  "))
        assertEquals(avatarIndexForKey("?"), avatarIndex(null, null))
    }

    // ── initials (§1.6.3) ─────────────────────────────────────────────────

    @Test fun initialsFromNameOrLocalPart() {
        assertEquals(listOf("O"), avatarInitials("Olivier Pricious", "olivier@grapmail.com"))
        assertEquals(listOf("O", "P"), avatarInitials("Olivier Pricious", "olivier@grapmail.com", twoLetters = true))
        assertEquals(listOf("J", "D"), avatarInitials("", "jane.doe@x.com", twoLetters = true))
        assertEquals(listOf("J"), avatarInitials(null, "jane_doe@x.com"))
    }

    @Test fun initialsSkipQuotesAndEmoji() {
        assertEquals(listOf("A"), avatarInitials("\"Acme\" Support", null))
        assertEquals(listOf("A"), avatarInitials("\uD83D\uDE00 Acme", null))
    }

    @Test fun noLetterMeansPersonGlyph() {
        assertTrue(avatarInitials("", "").isEmpty())
        assertTrue(avatarInitials("\uD83D\uDE00", null).isEmpty())
    }

    // ── masked address (§2.2) ─────────────────────────────────────────────

    @Test fun maskAddressRule() {
        assertEquals("ol\u2022\u2022\u2022r@grapmail.com", maskAddress("olivier@grapmail.com"))
        assertEquals("a\u2022\u2022\u2022@x.com", maskAddress("a@x.com"))
        assertEquals("a\u2022\u2022\u2022@x.com", maskAddress("ab@x.com"))
        assertEquals("a\u2022\u2022\u2022c@x.com", maskAddress("abc@x.com"))
        assertEquals("a\u2022\u2022\u2022d@x.com", maskAddress("abcd@x.com"))
        assertEquals("ab\u2022\u2022\u2022e@x.com", maskAddress("abcde@x.com"))
        // Split at the LAST @.
        assertEquals("a\u2022\u2022\u2022b@c.com", maskAddress("a@b@c.com"))
    }

    // ── shared vector file (written by the web builder's test) ─────────────

    /**
     * docs/design/avatar-hash-vectors.json, generated by the web unit test. Skipped
     * (not failed) until that file exists. Accepted shapes: a top-level array, or
     * an object with a `vectors` array, whose entries carry the raw input as
     * `input` / `address` / `raw` / `key`, the `index`, and optionally a `hash`
     * (number or hex string).
     */
    @Test fun sharedVectorFileAgrees() {
        val file = listOf("../../docs/design/avatar-hash-vectors.json", "../docs/design/avatar-hash-vectors.json",
            "docs/design/avatar-hash-vectors.json").map(::File).firstOrNull { it.isFile }
        assumeTrue("avatar-hash-vectors.json not generated yet", file != null)
        val root = Json.parseToJsonElement(file!!.readText(Charsets.UTF_8))
        val list = (root as? JsonArray) ?: ((root as? JsonObject)?.get("vectors") as? JsonArray)
        assumeTrue("unrecognised vector file shape", list != null)
        var checked = 0
        for (element in list!!) {
            val o = element.jsonObject
            val input = (o["input"] ?: o["address"] ?: o["raw"] ?: o["key"])?.jsonPrimitive?.content ?: continue
            val index = o["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            assertEquals("index for \"$input\"", index, avatarIndex(input))
            o["hash"]?.jsonPrimitive?.let { h ->
                val expected = if (h.isString) h.content.removePrefix("0x").removePrefix("0X").toLong(16) else h.content.toLong()
                assertEquals("hash for \"$input\"", expected, unsigned(fnv1a32(avatarKey(input))))
            }
            checked++
        }
        assertTrue("no vectors were read", checked > 0)
    }
}
