import XCTest
@testable import GrapMail

/// iOS's trust layer (MailTrust.swift): link/QR verdicts, tracker counting and
/// action cards. iOS scores additively (see UrlSafety.analyze), so its risk
/// levels differ from Android's for the same URL; these tests hold iOS's rules.
final class MailTrustTests: XCTestCase {

    // ── link / QR verdicts ───────────────────────────────────────────────────

    func testOrdinaryHttpsLinkIsLowRisk() {
        let v = UrlSafety.analyze("https://www.apple.com/iphone")
        XCTAssertEqual(v.risk, .low)
        XCTAssertTrue(v.reasons.isEmpty)
        XCTAssertEqual(v.displayHost, "www.apple.com")
        XCTAssertEqual(v.original, "https://www.apple.com/iphone")
    }

    func testPlainHttpIsMedium() {
        let v = UrlSafety.analyze("http://example.com/")
        XCTAssertEqual(v.risk, .medium)
        XCTAssertEqual(v.reasons.count, 1)
    }

    func testRawIpIsNeverLowAndOverHttpIsHigh() {
        XCTAssertNotEqual(UrlSafety.analyze("https://192.168.1.10/login").risk, .low)
        XCTAssertEqual(UrlSafety.analyze("http://192.168.1.10/login").risk, .high)
    }

    func testDecorationBeforeAtIsFlaggedAndTheRealHostShown() {
        let v = UrlSafety.analyze("https://paypal.com@evil.example/login")
        XCTAssertNotEqual(v.risk, .low)
        XCTAssertEqual(v.displayHost, "evil.example")
        XCTAssertTrue(v.reasons.contains { $0.contains("evil.example") })
    }

    func testPunycodeAndShortenersAreFlagged() {
        XCTAssertEqual(UrlSafety.analyze("https://xn--pple-43d.com/signin").risk, .medium)
        XCTAssertEqual(UrlSafety.analyze("https://bit.ly/3abcDEF").risk, .medium)
    }

    func testStackedSignalsAddUpToHigh() {
        let v = UrlSafety.analyze("http://secure.login.account.verify.example.xyz/")
        XCTAssertEqual(v.risk, .high)
        XCTAssertGreaterThanOrEqual(v.reasons.count, 3)
    }

    func testUnreadableAddressIsFlaggedNotTrusted() {
        let v = UrlSafety.analyze("not a url")
        XCTAssertEqual(v.risk, .medium)
        XCTAssertEqual(v.reasons.count, 1)
    }

    /// A raw Unicode lookalike (Cyrillic "а" in "аpple.com") must not read as
    /// safe. Expected to pass on iOS 17+ (the URL parser IDNA-encodes the host
    /// to xn--…, which is flagged) and on older systems (no host → flagged);
    /// Android has the bug — see Android KnownBugsTest.
    func testUnicodeHomographHostIsNotLowRisk() {
        XCTAssertNotEqual(UrlSafety.analyze("https://\u{0430}pple.com/login").risk, .low)
    }

    // ── tracker shield ───────────────────────────────────────────────────────

    private let logo = #"<img src="https://cdn.example.com/logo.png" width="120" alt="Logo">"#

    func testOrdinaryImageIsRemoteButNotATracker() {
        let s = TrackerShield.scan("<p>Hi</p>" + logo)
        XCTAssertEqual(s.remoteImageCount, 1)
        XCTAssertEqual(s.trackerCount, 0)
        XCTAssertTrue(s.hosts.isEmpty)
    }

    func testInvisibleAndKnownTrackerImagesAreCounted() {
        let html = #"<img src="https://mail.example.com/o.gif" width="1" height="1">"#
            + #"<img src="https://u1.ct.sendgrid.net/wf/open?upn=x" width="600">"#
            + #"<img src="https://cdn.example.com/x.png" style="display:none">"#
            + logo
        let s = TrackerShield.scan(html)
        XCTAssertEqual(s.remoteImageCount, 4)
        XCTAssertEqual(s.trackerCount, 3)
        XCTAssertEqual(s.hosts, ["mail.example.com", "u1.ct.sendgrid.net", "cdn.example.com"])
    }

    func testEmbeddedImagesAreLocal() {
        let s = TrackerShield.scan(#"<img src="cid:logo@x"><img src='data:image/png;base64,AAAA'>"#)
        XCTAssertEqual(s.remoteImageCount, 0)
        XCTAssertEqual(s.trackerCount, 0)
    }

    func testHostListIsDistinctAndCappedAtSix() {
        let html = (1...8).map { #"<img src="https://h\#($0).example.com/p.gif" width="1">"# }.joined()
            + #"<img src="https://h1.example.com/q.gif" height="1">"#
        let s = TrackerShield.scan(html)
        XCTAssertEqual(s.trackerCount, 9)
        XCTAssertEqual(s.hosts.count, 6)
        XCTAssertEqual(Set(s.hosts).count, 6)
    }

    // ── action cards ─────────────────────────────────────────────────────────

    private func cards(_ subject: String, _ body: String) -> [MailActionCard] {
        ActionCardExtractor.extract(subject: subject, body: body)
    }

    func testLabelledCodeBecomesACodeCard() {
        let c = cards("Sign-in", "Your code: 482913")
        XCTAssertEqual(c.first { $0.kind == .code }?.value, "482913")
    }

    func testUpsNumberNeedsShippingWords() {
        XCTAssertEqual(cards("Your order", "Tracking 1Z999AA10123456784").first { $0.kind == .package }?.value, "1Z999AA10123456784")
        XCTAssertNil(cards("Hello", "Ref 1Z999AA10123456784").first { $0.kind == .package })
    }

    func testDeliveryDayWithoutATrackingNumber() {
        XCTAssertEqual(cards("Good news", "Your order has shipped and arrives Thursday").first { $0.kind == .package }?.value, "Thursday")
    }

    func testFlightNumberNearFlightWords() {
        XCTAssertEqual(cards("Boarding pass", "Flight BA117 departs 09:40").first { $0.kind == .flight }?.value, "BA117")
    }

    func testConfirmationCode() {
        XCTAssertEqual(cards("Booking confirmed", "Your confirmation number is ABC123.").first { $0.kind == .reservation }?.value, "ABC123")
    }

    func testMeetingLinkCarriesItsUrl() {
        let m = cards("Join us", "Link: https://zoom.us/j/123456789 see you").first { $0.kind == .meeting }
        XCTAssertEqual(m?.value, "zoom.us")
        XCTAssertEqual(m?.url, "https://zoom.us/j/123456789")
    }

    func testAmountNearPaymentWords() {
        XCTAssertEqual(cards("Invoice", "Amount due: $45.99").first { $0.kind == .payment }?.value, "$45.99")
        XCTAssertNil(cards("Hi", "I paid $45.99 for lunch").first { $0.kind == .payment }, "no invoice/payment/due/amount/balance word")
    }

    func testStripIsDedupedAndCappedAtFour() {
        let c = cards("Booking confirmation", "Your code: 482913. Tracking 1Z999AA10123456784 shipped. Flight BA117 boarding. Ref ABC123. https://zoom.us/j/1 Amount due $10.00")
        XCTAssertEqual(c.count, 4)
        XCTAssertEqual(Set(c.map { "\($0.kind.rawValue)|\($0.value)" }).count, 4)
    }

    func testPlainMailHasNoCards() {
        XCTAssertTrue(cards("Lunch?", "Are we still on for noon?").isEmpty)
    }
}
