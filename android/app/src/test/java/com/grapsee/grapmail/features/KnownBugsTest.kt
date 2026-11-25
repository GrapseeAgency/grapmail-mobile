package com.grapsee.grapmail.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KNOWN BUGS — every test in this class asserts the CORRECT behaviour and is
 * EXPECTED TO FAIL against the current production code (found by reading the
 * source while writing the suite; see TESTING.md "Known bugs"). They are kept
 * apart so the rest of the suite stays meaningful; fix the code, not the test.
 * To unblock a release before a fix, annotate the single test with
 * `@org.junit.Ignore("KNOWN BUG …")` — never delete it.
 */
class KnownBugsTest {

    /**
     * NaturalSearch: the "about …" topic matcher swallows up to 40 characters,
     * including later filter words, and removes them from the query before the
     * attachment / unread matchers run. cleanTopic trims the subject, but the
     * filter itself is lost.
     */
    @Test
    fun knownBug_naturalSearch_aboutTopicSwallowsTheAttachmentFilter() {
        val p = NaturalSearch.parse("emails about the budget with attachments")
        assertEquals("budget", p.subject)
        assertEquals(true, p.hasAttachment)
    }

    /** NaturalSearch: `from <word>` uses [\w.+-] which excludes '@', so "from bob@example.com" gives from="bob". */
    @Test
    fun knownBug_naturalSearch_fromAnEmailAddressKeepsTheDomain() {
        assertEquals("bob@example.com", NaturalSearch.parse("mail from bob@example.com").from)
    }

    /**
     * NaturalSearch: "past N weeks/months" and "older than N days/weeks" set a
     * date window but are never removed from the query, so they ALSO become the
     * free-text term and the search requires the literal words "past 2 weeks".
     */
    @Test
    fun knownBug_naturalSearch_relativeDatePhraseLeaksIntoFreeText() {
        val p = NaturalSearch.parse("invoices past 2 weeks")
        assertEquals(14, p.afterDaysAgo)
        assertEquals("invoices", p.text)
        assertEquals("invoices", NaturalSearch.parse("invoices older than 3 days").text)
    }

    /** ActionCardExtractor: airline codes with a digit (B6 JetBlue, F9 Frontier) are listed but the pattern is [A-Z]{2}. */
    @Test
    fun knownBug_actionCards_airlineCodesContainingADigit() {
        val cards = ActionCardExtractor.extract("Your flight", "Flight B6 123 departs at 9am")
        assertTrue(cards.any { it.kind == CardKind.FLIGHT && it.value == "B6 123" })
    }

    /** TrackerShield: the hint "facebook.com/tr" is compared against the HOST only, so it can never match. */
    @Test
    fun knownBug_trackerShield_facebookPixelPathIsNeverMatched() {
        val r = TrackerShield.scan("""<img src="https://www.facebook.com/tr?id=1&ev=PageView&noscript=1">""", allowRemoteImages = true)
        assertEquals(1, r.trackerCount)
    }

    /**
     * UrlSafety (QR/link verdict): a raw Unicode homograph host ("аpple.com" with
     * a Cyrillic "а") is not punycode-encoded before the checks; java.net.URI
     * leaves host null, the fallback keeps the Unicode, and the verdict is "Looks safe".
     */
    @Test
    fun knownBug_urlSafety_unicodeHomographHostLooksSafe() {
        assertNotEquals(UrlRisk.LOW, UrlSafety.analyze("https://\u0430pple.com/login").risk)
    }

    /** ComposeAssist FRIENDLY: "regards" is replaced before "kind regards", so the sign-off becomes "Kind Best". */
    @Test
    fun knownBug_composeAssist_friendlyKindRegardsBecomesKindBest() {
        val out = ComposeAssist.transform("Dear Sam,\nThanks.\nKind regards", ComposeTone.FRIENDLY)
        assertFalse(out, out.contains("Kind Best"))
        assertTrue(out, out.endsWith("Best"))
    }

    /** ComposeAssist.draft PROFESSIONAL: the template already ends "Best", then "Best regards" is appended too. */
    @Test
    fun knownBug_composeAssist_professionalDraftHasTwoSignOffs() {
        val body = ComposeAssist.draft("sorry for the delay on the report", ComposeTone.PROFESSIONAL).body
        assertFalse(body, body.contains("Best\n\nBest regards"))
    }
}
