import XCTest
@testable import GrapMail

/// Mirrors android/.../logic/ProviderPresetsTest.kt.
final class ProviderPresetsTests: XCTestCase {
    func testPresetTableHasAgreedProvidersAndNoGmail() {
        XCTAssertEqual(ProviderPreset.all.map(\.id), ["yahoo", "icloud", "aol", "fastmail", "zoho", "custom"])
        XCTAssertFalse(ProviderPreset.all.contains { $0.imapHost.contains("gmail") })
    }

    func testNamedPresetsAreValid() {
        for p in ProviderPreset.all where !p.isCustom {
            XCTAssertTrue(ImapFormValidator.validHost(p.imapHost), p.id)
            XCTAssertTrue(ImapFormValidator.validHost(p.smtpHost), p.id)
            XCTAssertEqual(p.imapPort, 993, p.id)
            XCTAssertEqual(p.smtpPort == 465, p.smtpTLS, p.id)
            XCTAssertTrue(p.appPasswordURL?.hasPrefix("https://") ?? false, p.id)
        }
        XCTAssertTrue(ProviderPreset.byId("gmail").isCustom)
    }

    func testEmailGuess() {
        XCTAssertEqual(ProviderPreset.forEmail("a@yahoo.co.uk")?.id, "yahoo")
        XCTAssertEqual(ProviderPreset.forEmail("a@me.com")?.id, "icloud")
        XCTAssertEqual(ProviderPreset.forEmail("a@AOL.com")?.id, "aol")
        XCTAssertNil(ProviderPreset.forEmail("a@gmail.com"))
    }

    private func valid() -> ImapForm { var f = ImapForm(); f.email = "me@yahoo.com"; f.password = "pw"; return f }

    func testValidation() {
        XCTAssertTrue(ImapFormValidator.validate(valid()).isEmpty)
        var f = valid(); f.email = "me@"; XCTAssertTrue(ImapFormValidator.validate(f).contains(.email))
        f = valid(); f.password = ""; XCTAssertTrue(ImapFormValidator.validate(f).contains(.password))
        f = valid(); f.imapHost = "bad host"; XCTAssertTrue(ImapFormValidator.validate(f).contains(.imapHost))
        f = valid(); f.imapPort = "70000"; XCTAssertTrue(ImapFormValidator.validate(f).contains(.imapPort))
        f = valid(); f.smtpHost = ""; XCTAssertTrue(ImapFormValidator.validate(f).isEmpty)
    }

    func testStatusMapping() {
        XCTAssertEqual(ProviderUIStatus.of("connected", lastSyncAt: nil, lastError: nil), .syncing)
        XCTAssertEqual(ProviderUIStatus.of("connected", lastSyncAt: "2026-11-23T10:00:00Z", lastError: nil), .ok)
        XCTAssertEqual(ProviderUIStatus.of("expired", lastSyncAt: nil, lastError: "x"), .needsReauth)
        XCTAssertEqual(ProviderUIStatus.of("error", lastSyncAt: nil, lastError: nil), .error)
    }

    func testOAuthCallbackParsing() {
        XCTAssertEqual(ProviderOAuth.parse(URL(string: "grapmail://providers/callback?code=abc&state=s1")!), .code("abc", state: "s1"))
        XCTAssertNil(ProviderOAuth.parse(URL(string: "grapmail://thread/x?code=abc&state=s1")!))
        XCTAssertEqual(ProviderOAuth.parse(URL(string: "grapmail://providers/callback?error=access_denied")!), .denied("access_denied"))
        let p = ProviderOAuth.pending(from: "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?state=u%3A1&redirect_uri=https%3A%2F%2Fx%2Fcb", providerEmail: "A@B.com")
        XCTAssertEqual(p?.state, "u:1"); XCTAssertEqual(p?.redirectUri, "https://x/cb"); XCTAssertEqual(p?.providerEmail, "a@b.com")
    }
}
