import XCTest
@testable import GrapMail

/// Mirrors android/.../logic/CodeFinderTest.kt case for case: CodeFinder.swift
/// and CodeFinder.kt run the same rules, so the same inputs must give the same
/// answers on both platforms.
final class CodeFinderTests: XCTestCase {
    private func find(_ subject: String?, _ body: String?, encrypted: Bool = false) -> String? {
        CodeFinder.find(subject: subject, body: body, encrypted: encrypted)
    }

    // ── positives ────────────────────────────────────────────────────────────

    func testPlainSixDigitCodeAfterKeyword() {
        XCTAssertEqual(find("Your verification code", "Your verification code is 482913. It expires in 10 minutes."), "482913")
    }

    func testFourDigitCodeNearSignIn() {
        XCTAssertEqual(find("Sign in to Acme", "Use 4821 to sign in."), "4821")
    }

    func testPrefixedCodeWithKeywordAfter() {
        XCTAssertEqual(find("", "G-482913 is your Google verification code."), "G-482913")
    }

    func testSplitCodeIsJoined() {
        XCTAssertEqual(find("Your code", "Code: 482 913"), "482913")
    }

    func testMixedAlphanumericCodeNextToKeyword() {
        XCTAssertEqual(find("Login code", "Your one-time passcode: K7P2QX"), "K7P2QX")
    }

    func testOtherLanguages() {
        XCTAssertEqual(find("Código de verificación", "Tu código es 739104"), "739104")
        XCTAssertEqual(find("", "Ihr Bestätigungscode lautet 551902"), "551902")
        XCTAssertEqual(find("", "您的验证码是 806215，5分钟内有效。"), "806215")
        XCTAssertEqual(find("", "Ваш код подтверждения: 3391"), "3391")
    }

    func testKeywordOnlyInSubjectAcceptsTheSingleCandidate() {
        let filler = "Hi there, thanks for using Acme. Here is what you asked us for today:"
        XCTAssertEqual(find("Your sign-in code", "\(filler)\n\n593017\n\nThe Acme team"), "593017")
    }

    func testCodeBeatsAnOrderNumberElsewhere() {
        XCTAssertEqual(find("Acme", "Order 5531 is ready. Your pickup code is 482913."), "482913")
    }

    func testListRowConvenienceReadsSubjectAndSnippet() {
        var row = MailMessage()
        row.id = "m1"
        row.subject = "Your login code"
        row.snippet = "Enter 662041 to continue"
        XCTAssertEqual(CodeFinder.find(row), "662041")
    }

    // ── negatives ────────────────────────────────────────────────────────────

    func testEncryptedMailNeverAnswers() {
        XCTAssertNil(find("Your verification code", "Your verification code is 482913", encrypted: true))
        var sealed = MailMessage()
        sealed.id = "m2"
        sealed.subject = "Your login code"
        sealed.snippet = "Enter 662041"
        sealed.isE2eEncrypted = true
        XCTAssertNil(CodeFinder.find(sealed))
    }

    func testOrderAndTrackingNumbers() {
        XCTAssertNil(find("Your order", "Order 48291337 has shipped"))
        XCTAssertNil(find("Shipping update", "Tracking number 58213347"))
        XCTAssertNil(find("Ticket #4821", "Your ticket #4821 was updated"))
    }

    func testPricesAndAmounts() {
        XCTAssertNil(find("Receipt", "Total: $1,299.00 paid"))
        XCTAssertNil(find("Your order code", "Pay 4999 INR"))
        XCTAssertNil(find("Verification", "Price: €4999"))
        XCTAssertNil(find("Invoice", "Amount due 4500 USD"))
    }

    func testYearsAndDates() {
        XCTAssertNil(find("Meeting", "See you on 2026-11-24 at 10:30"))
        XCTAssertNil(find("Login code", "© 2024 Acme Inc."))
        XCTAssertNil(find("Your code", "Your code expires in 2027"))
        XCTAssertNil(find("Sign-in code", "Sent 20261124"))
    }

    func testPhoneNumbers() {
        XCTAssertNil(find("Call us", "Call 0800 1234 5678 for help"))
        XCTAssertNil(find("Verification", "Questions? Call +8801712345678"))
    }

    func testPromoCodes() {
        XCTAssertNil(find("Spring sale", "Use promo code SAVE20 for 20% off"))
        XCTAssertNil(find("Gift", "Your coupon code 7788 is inside"))
    }

    func testNoKeywordNoCode() {
        XCTAssertNil(find("Lunch", "Room 4821 at noon"))
        XCTAssertNil(find("Hello", "We have 4821 members now"))
    }

    func testPlausibilityGuardsTheCopyAction() {
        XCTAssertTrue(CodeFinder.isPlausible("482913"))
        XCTAssertTrue(CodeFinder.isPlausible("G-482913"))
        XCTAssertFalse(CodeFinder.isPlausible("48"))
        XCTAssertFalse(CodeFinder.isPlausible("482913; rm"))
        XCTAssertFalse(CodeFinder.isPlausible(nil))
    }
}
