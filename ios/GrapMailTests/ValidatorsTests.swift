import XCTest
@testable import GrapMail

/// Mirrors android/.../logic/ValidatorsTest.kt. The rules are the server's.
final class ValidatorsTests: XCTestCase {
    private let thisYear = Calendar(identifier: .gregorian).component(.year, from: Date())

    func testNamesNeedTwoCharactersAfterTrimming() {
        XCTAssertNotNil(Validators.firstName(""))
        XCTAssertNotNil(Validators.firstName("  A  "))
        XCTAssertNil(Validators.firstName("  Al  "))
        XCTAssertNotNil(Validators.lastName("B"))
        XCTAssertNil(Validators.lastName("Bo"))
    }

    func testIncompleteBirthdayIsNeitherAcceptedNorAnError() {
        for (y, m, d) in [(nil, 1, 1), (2000, nil, 1), (2000, 1, nil)] as [(Int?, Int?, Int?)] {
            let r = Validators.birthday(year: y, month: m, day: d)
            XCTAssertNil(r.iso)
            XCTAssertNil(r.error)
        }
    }

    func testRealAdultBirthdayComesBackAsIsoDate() {
        XCTAssertEqual(Validators.birthday(year: 1990, month: 7, day: 4).iso, "1990-07-04")
        XCTAssertEqual(Validators.birthday(year: 2000, month: 2, day: 29).iso, "2000-02-29")
        XCTAssertNil(Validators.birthday(year: 1990, month: 7, day: 4).error)
    }

    func testImpossibleDatesAreRejectedNotRolledOver() {
        // Calendar.date(from:) rolls Feb 29 2001 to Mar 1; the validator must refuse it.
        for (y, m, d) in [(2001, 2, 29), (1999, 2, 30), (1999, 4, 31), (1999, 13, 1), (1999, 0, 10), (1999, 1, 32), (1899, 12, 31)] {
            let r = Validators.birthday(year: y, month: m, day: d)
            XCTAssertNil(r.iso, "\(y)-\(m)-\(d)")
            XCTAssertNotNil(r.error, "\(y)-\(m)-\(d)")
        }
    }

    func testFutureAndUnderThirteenAreRejected() {
        XCTAssertNil(Validators.birthday(year: thisYear + 1, month: 1, day: 1).iso)
        let young = Validators.birthday(year: thisYear - 5, month: 1, day: 1)
        XCTAssertNil(young.iso)
        XCTAssertTrue(young.error?.contains("13") ?? false)
    }

    func testAgeCountsWholeYears() {
        let cal = Calendar.current
        let thirteen = cal.date(byAdding: .year, value: -13, to: Date())!
        XCTAssertEqual(Validators.age(from: thirteen), 13)
        XCTAssertEqual(Validators.age(from: cal.date(byAdding: .day, value: 1, to: thirteen)!), 12)
    }

    func testHandleCharsAndValidity() {
        XCTAssertEqual(Validators.handleChars("John.O'Neil_99!"), "john.oneil_99")
        XCTAssertEqual(Validators.handleChars("Ω€ 🙂"), "")
        XCTAssertFalse(Validators.handleValid("ab"))
        XCTAssertTrue(Validators.handleValid("abc"))
        XCTAssertTrue(Validators.handleValid(String(repeating: "a", count: 30)))
        XCTAssertFalse(Validators.handleValid(String(repeating: "a", count: 31)))
        XCTAssertFalse(Validators.handleValid("Abc"))
        XCTAssertFalse(Validators.handleValid("a b c"))
        XCTAssertTrue(Validators.handleValid("a-b.c_d9"))
    }

    func testHandleCandidatesMatchAndroidForTheSameName() {
        XCTAssertEqual(Validators.handleCandidates(first: "Ada", last: "Lovelace"), [
            "ada.lovelace", "adalovelace", "ada.l", "alovelace", "ada", "lovelace",
            "ada.lovelace1", "ada.lovelace2", "ada.lovelace3", "ada.lovelace4",
        ])
    }

    func testEveryHandleCandidateIsValidAndDistinct() {
        for (f, l) in [("Zoë", "O'Brien"), ("Jo", "Li"), ("Mary-Jane", "Watson"), ("Ana", "")] {
            let out = Validators.handleCandidates(first: f, last: l)
            XCTAssertLessThanOrEqual(out.count, 10)
            XCTAssertEqual(Set(out).count, out.count)
            out.forEach { XCTAssertTrue(Validators.handleValid($0), "\($0) from \(f) \(l)") }
        }
        XCTAssertEqual(Validators.handleCandidates(first: "Al", last: ""), [])
    }

    func testPasswordRulesAreTheServersFour() {
        XCTAssertEqual(Validators.passwordStrength(""), 0)
        XCTAssertEqual(Validators.passwordStrength("a"), 0.25)
        XCTAssertEqual(Validators.passwordStrength("abcdefgh1"), 0.75)
        XCTAssertEqual(Validators.passwordStrength("abc12345!"), 1)
        XCTAssertEqual(Validators.passwordStrength("abcdefg1-"), 1, "no mixed-case rule")
        XCTAssertFalse(Validators.passwordMet(.digit, "Abcdefg!"))
        XCTAssertFalse(Validators.passwordMet(.special, "Abcdefg1"))
        XCTAssertFalse(Validators.passwordMet(.length, "Ab1!"))
    }

    func testLetterAndDigitClassesAreAsciiLikeTheServer() {
        // Greek letters are not "a letter" for the server, but count as a symbol.
        XCTAssertFalse(Validators.passwordMet(.letter, "ΩΩΩΩΩ123"))
        XCTAssertTrue(Validators.passwordMet(.special, "ΩΩΩΩΩ123"))
        XCTAssertFalse(Validators.passwordMet(.digit, "abcdefg!\u{0661}"))
    }
}
