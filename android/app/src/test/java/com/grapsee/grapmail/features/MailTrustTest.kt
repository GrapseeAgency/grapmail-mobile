package com.grapsee.grapmail.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trust layer (features/MailTrust.kt): link verdicts (also the verdict a
 * scanned QR code gets), the tracker shield, and action cards. Reasons are
 * localized, so they are counted and keyed on stable fragments (the brand name,
 * the host) rather than compared whole.
 */
class MailTrustTest {

    // ── F23 URL / QR verdicts ───────────────────────────────────────────────

    @Test
    fun officialDomainIsLowRiskAndShownByItsRegistrableName() {
        val v = UrlSafety.analyze("https://www.apple.com/iphone")
        assertEquals(UrlRisk.LOW, v.risk)
        assertTrue(v.reasons.isEmpty())
        assertEquals("apple.com", v.displayHost)
        assertEquals("https://www.apple.com/iphone", v.original)
    }

    @Test
    fun schemeAndHostCaseDoNotMatterAndWhitespaceIsTrimmed() {
        val v = UrlSafety.analyze("  HTTPS://WWW.GOOGLE.COM/search?q=x \n")
        assertEquals(UrlRisk.LOW, v.risk)
        assertEquals("google.com", v.displayHost)
    }

    @Test
    fun plainHttpIsOneReasonAndMediumRisk() {
        val v = UrlSafety.analyze("http://example.com/")
        assertEquals(UrlRisk.MEDIUM, v.risk)
        assertEquals(1, v.reasons.size)
    }

    @Test
    fun rawIpAddressIsHighRiskAndShownAsTheIp() {
        val v = UrlSafety.analyze("https://192.168.1.10/login")
        assertEquals(UrlRisk.HIGH, v.risk)
        assertEquals("192.168.1.10", v.displayHost)
    }

    @Test
    fun fakeLoginStringBeforeAtIsHighRiskAndTheRealHostIsShown() {
        val v = UrlSafety.analyze("https://paypal.com@evil.example/login")
        assertEquals(UrlRisk.HIGH, v.risk)
        assertEquals("evil.example", v.displayHost)
    }

    @Test
    fun punycodeHostIsFlagged() {
        val v = UrlSafety.analyze("https://xn--pple-43d.com/signin")
        assertEquals(UrlRisk.MEDIUM, v.risk)
        assertEquals(1, v.reasons.size)
        assertTrue(UrlSafety.analyze("https://login.xn--80ak6aa92e.com/").reasons.isNotEmpty())
    }

    @Test
    fun shortenerHidesTheDestination() {
        assertEquals(UrlRisk.MEDIUM, UrlSafety.analyze("https://bit.ly/3abcDEF").risk)
        assertEquals(UrlRisk.MEDIUM, UrlSafety.analyze("https://www.tinyurl.com/x").risk)
    }

    @Test
    fun brandLookalikeOnAScamTldIsHighRiskAndNamesTheRealDomain() {
        val v = UrlSafety.analyze("https://paypal-secure-login.xyz/verify")
        assertEquals(UrlRisk.HIGH, v.risk)
        assertTrue(v.reasons.any { it.contains("paypal") && it.contains("paypal.com") })
    }

    @Test
    fun brandInASubdomainOfSomeoneElsesDomainIsCaught() {
        val v = UrlSafety.analyze("https://login.paypal.com.account-verify.top/")
        assertEquals(UrlRisk.HIGH, v.risk)
        assertEquals("account-verify.top", v.displayHost)
        assertTrue("deep chain + risky TLD + imitation", v.reasons.size >= 3)
    }

    @Test
    fun countryCodeSecondLevelDomainsKeepThreeLabels() {
        val v = UrlSafety.analyze("https://evil-paypal.co.uk/")
        assertEquals("evil-paypal.co.uk", v.displayHost)
        assertEquals(UrlRisk.MEDIUM, v.risk)
        assertEquals(UrlRisk.LOW, UrlSafety.analyze("https://www.bbc.co.uk/news").risk)
    }

    @Test
    fun shortBrandNamesMatchOnlyWholeTokens() {
        assertEquals("pineapple is not apple", UrlRisk.LOW, UrlSafety.analyze("https://pineapple.com/").risk)
        assertEquals(UrlRisk.MEDIUM, UrlSafety.analyze("https://apple-support.com/").risk)
    }

    @Test
    fun unreadableLinkIsTreatedAsDangerous() {
        for (raw in listOf("not a url", "", "mailto:someone@example.com", "javascript:alert(1)")) {
            val v = UrlSafety.analyze(raw)
            assertEquals(raw, UrlRisk.HIGH, v.risk)
            assertEquals("", v.displayHost)
        }
    }

    @Test
    fun reasonsAreNeverRepeated() {
        val v = UrlSafety.analyze("http://a.b.c.paypal.d.example.zip/")
        assertEquals(v.reasons.size, v.reasons.toSet().size)
        assertEquals(UrlRisk.HIGH, v.risk)
    }

    // ── F16 tracker shield ──────────────────────────────────────────────────

    private val logo = """<img src="https://cdn.example.com/logo.png" width="120" alt="Logo">"""

    @Test
    fun ordinaryImageIsCountedButLeftAloneWhenRemoteImagesAreAllowed() {
        val html = "<p>Hi</p>$logo"
        val r = TrackerShield.scan(html, allowRemoteImages = true)
        assertEquals(1, r.remoteImageCount)
        assertEquals(0, r.trackerCount)
        assertTrue(r.blockedHosts.isEmpty())
        assertEquals(html, r.cleanedHtml)
    }

    @Test
    fun remoteImagesAreRemovedWhenNotAllowed() {
        val r = TrackerShield.scan("<p>Hi</p>$logo", allowRemoteImages = false)
        assertEquals(listOf("cdn.example.com"), r.blockedHosts)
        assertEquals("<p>Hi</p>", r.cleanedHtml)
        assertEquals(0, r.trackerCount)
    }

    @Test
    fun onePixelImageIsATrackerAndRemovedEvenWhenImagesAreAllowed() {
        val pixel = """<img src="https://mail.example.com/o.gif" width="1" height="1">"""
        val r = TrackerShield.scan("<p>Hi</p>$pixel$logo", allowRemoteImages = true)
        assertEquals(2, r.remoteImageCount)
        assertEquals(1, r.trackerCount)
        assertEquals(listOf("mail.example.com"), r.blockedHosts)
        assertEquals("<p>Hi</p>$logo", r.cleanedHtml)
    }

    @Test
    fun cssSizedPixelAndKnownTrackerHostsAreCaught() {
        val css = """<img style="width:1px;height:1px" src="https://cdn.example.com/p.gif">"""
        val host = """<img src="https://u123.list-manage.com/track/open.php?u=1" width="600">"""
        val r = TrackerShield.scan(css + host, allowRemoteImages = true)
        assertEquals(2, r.trackerCount)
        assertEquals("", r.cleanedHtml)
    }

    @Test
    fun tenPixelsIsNotOnePixel() {
        val r = TrackerShield.scan("""<img src="https://cdn.example.com/a.png" width="10" height="12">""", allowRemoteImages = true)
        assertEquals(0, r.trackerCount)
    }

    @Test
    fun embeddedAndRelativeImagesAreNotRemote() {
        val html = """<img src="cid:logo@x"><img src='data:image/png;base64,AAAA'><img src="img/a.png">"""
        val r = TrackerShield.scan(html, allowRemoteImages = false)
        assertEquals(0, r.remoteImageCount)
        assertEquals(html, r.cleanedHtml)
    }

    @Test
    fun blockedHostsAreDistinct() {
        val p = """<img src="https://pixel.example.net/a.gif">"""
        val r = TrackerShield.scan(p + p.replace("a.gif", "b.gif"), allowRemoteImages = true)
        assertEquals(2, r.trackerCount)
        assertEquals(listOf("pixel.example.net"), r.blockedHosts)
    }

    // ── F27 action cards ────────────────────────────────────────────────────

    @Test
    fun upsTrackingNumberWithShippingWordsBecomesATrackingCard() {
        val cards = ActionCardExtractor.extract("Your package has shipped", "Tracking number: 1Z999AA10123456784")
        assertEquals(1, cards.size)
        assertEquals(CardKind.TRACKING, cards[0].kind)
        assertEquals("1Z999AA10123456784", cards[0].value)
        assertEquals("https://www.ups.com/track?tracknum=1Z999AA10123456784", cards[0].url)
    }

    @Test
    fun carriersAreRecognisedAndOnlyOneTrackingCardIsMade() {
        val fedex = ActionCardExtractor.extract("Delivery update", "Your package 123456789012 is on its way")
        assertEquals(listOf("123456789012"), fedex.map { it.value })
        assertTrue(fedex[0].url!!.startsWith("https://www.fedex.com/"))

        val dhl = ActionCardExtractor.extract("DHL shipment", "Shipment 1234567890 is with the courier")
        assertEquals(1, dhl.size)
        assertTrue(dhl[0].url!!.startsWith("https://www.dhl.com/"))

        val usps = ActionCardExtractor.extract("USPS tracking", "Tracking 9400111899223197428490")
        assertEquals(1, usps.size)
        assertTrue(usps[0].url!!.startsWith("https://tools.usps.com/"))
    }

    @Test
    fun aNumberWithoutShippingContextIsNotATrackingNumber() {
        assertTrue(ActionCardExtractor.extract("Hello", "My account number is 123456789012").isEmpty())
    }

    @Test
    fun knownAirlineFlightBecomesAFlightCard() {
        val cards = ActionCardExtractor.extract("Your itinerary", "Flight BA 117 departs from gate 22 at 09:40")
        val flight = cards.single { it.kind == CardKind.FLIGHT }
        assertEquals("BA 117", flight.value)
        assertEquals("https://www.google.com/search?q=flight+BA+117+status", flight.url)
    }

    @Test
    fun unknownAirlineCodeIsIgnored() {
        assertTrue(ActionCardExtractor.extract("Flight", "Flight XY 123 boarding now").none { it.kind == CardKind.FLIGHT })
    }

    @Test
    fun isoDateNearEventWordsBecomesACalendarCard() {
        val cards = ActionCardExtractor.extract("Interview scheduled", "Your interview is scheduled for 2026-12-01 at 10:00")
        val event = cards.single { it.kind == CardKind.EVENT }
        assertEquals("2026-12-01", event.value)
        assertTrue(event.url!!.contains("dates=20261201/20261201"))
    }

    @Test
    fun sameCardIsNeverListedTwice() {
        val cards = ActionCardExtractor.extract("Tracking 1Z999AA10123456784", "Package 1Z999AA10123456784 shipped")
        assertEquals(1, cards.size)
    }

    @Test
    fun plainMailHasNoCards() {
        assertFalse(ActionCardExtractor.extract("Lunch?", "Are we still on for noon?").isNotEmpty())
    }
}
