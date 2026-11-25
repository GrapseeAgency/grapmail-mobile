import XCTest
@testable import GrapMail

/// KNOWN BUGS — every test here asserts the CORRECT behaviour and is EXPECTED
/// TO FAIL against the current production code (found by reading the source
/// while writing the suite; see TESTING.md "Known bugs"). Fix the code, not the
/// test. To unblock a release before a fix, skip the one test with
/// `throw XCTSkip("KNOWN BUG …")` at its top — never delete it.
final class KnownBugsTests: XCTestCase {

    /// NaturalSearch: the natural `from` matcher takes up to four words
    /// ("alice last week with") and then removes the whole match from the text,
    /// so the time phrase and the attachment phrase that follow are lost. This
    /// is the exact example in the function's own doc comment.
    func testKnownBug_naturalSearch_documentedExampleLosesDateAndAttachment() {
        let p = NaturalSearch.parse("invoice from alice last week with attachments")
        XCTAssertEqual(p.from, "alice")
        XCTAssertEqual(p.hasAttachment, true)
        XCTAssertEqual(p.afterDaysAgo, 14)
        XCTAssertEqual(p.beforeDaysAgo, 7)
    }

    /// NaturalSearch: "the" is a stopword, so "about the budget" stops before
    /// the first word and yields no subject at all.
    func testKnownBug_naturalSearch_aboutTheTopicGivesNoSubject() {
        XCTAssertEqual(NaturalSearch.parse("emails about the budget").subject, "budget")
    }

    /// ActionCardExtractor: `(\d+(?:[.,]\d{2})?)` reads "$1,250.00" as "$1,25".
    func testKnownBug_actionCards_thousandsSeparatorTruncatesTheAmount() {
        let c = ActionCardExtractor.extract(subject: "Invoice", body: "Amount due: $1,250.00")
        XCTAssertEqual(c.first { $0.kind == .payment }?.value, "$1,250.00")
    }

    /// ActionCardExtractor: the flight pattern runs case-insensitively, so any
    /// two letters before a number ("on 2026") is taken as a flight number.
    func testKnownBug_actionCards_ordinaryWordsBeforeANumberAreNotAFlight() {
        let c = ActionCardExtractor.extract(subject: "Flight reminder", body: "Your flight departs on 2026-12-01.")
        XCTAssertTrue(c.filter { $0.kind == .flight }.isEmpty, "\(c.map(\.value))")
    }

    /// MailCategorizer: the sender is lowercased but `knownSenders` is not, so a
    /// contact saved as "Alice@Example.com" is not recognised (Android lowercases both).
    func testKnownBug_categorizer_knownSendersAreCaseSensitive() {
        let d = MailCategorizer.classify(senderAddress: "alice@example.com", subject: "Lunch", knownSenders: ["Alice@Example.com"])
        XCTAssertEqual(d.category, .people)
        XCTAssertTrue(d.priority, "a known contact is priority")
    }
}
