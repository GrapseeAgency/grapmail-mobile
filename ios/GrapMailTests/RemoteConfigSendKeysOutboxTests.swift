import XCTest
@testable import GrapMail

/// RemoteConfig.compare decides `updateRequired` (only the update screen shows
/// below the server's minimum), so a wrong answer either strands users on a
/// dead build or locks current users out.
final class RemoteConfigTests: XCTestCase {
    @MainActor
    func testEqualVersionsCompareEqualWhateverTheirLength() {
        XCTAssertEqual(RemoteConfig.compare("1.2.0", "1.2"), 0)
        XCTAssertEqual(RemoteConfig.compare("1", "1.0.0"), 0)
        XCTAssertEqual(RemoteConfig.compare("0.6.4", "0.6.4"), 0)
    }

    @MainActor
    func testComponentsCompareNumericallyNotAsText() {
        XCTAssertEqual(RemoteConfig.compare("1.10", "1.9"), 1)
        XCTAssertEqual(RemoteConfig.compare("2", "10"), -1)
        XCTAssertEqual(RemoteConfig.compare("0.6.4", "0.7"), -1)
        XCTAssertEqual(RemoteConfig.compare("1.0.1", "1"), 1)
    }

    @MainActor
    func testTheServersDefaultMinimumNeverForcesAnUpdate() {
        // minVersion.ios defaults to "0" when the server sends nothing.
        XCTAssertGreaterThanOrEqual(RemoteConfig.compare(RemoteConfig.appVersion, "0"), 0)
        XCTAssertGreaterThanOrEqual(RemoteConfig.compare("0.0.1", "0"), 0)
    }

    @MainActor
    func testNonNumericComponentsCountAsZero() {
        XCTAssertEqual(RemoteConfig.compare("abc", "0"), 0)
        XCTAssertEqual(RemoteConfig.compare("1.2.x", "1.2.0"), 0)
        XCTAssertEqual(RemoteConfig.compare("1.2.x", "1.2.1"), -1)
    }

    @MainActor
    func testCompareIsAntisymmetric() {
        let versions = ["0", "0.1", "0.6.4", "0.7", "1", "1.0.1", "1.9", "1.10", "2", "10"]
        for a in versions {
            for b in versions {
                XCTAssertEqual(RemoteConfig.compare(a, b), -RemoteConfig.compare(b, a), "\(a) vs \(b)")
            }
        }
    }
}

/// Mirrors android/.../data/SendKeysTest.kt. A fresh instance per test, so the
/// process-wide `shared` is never touched.
final class SendKeysTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)
    private func body(_ tag: String) -> Data {
        Data(#"{"to":["bob@example.com"],"subject":"\#(tag) \#(UUID().uuidString)","bodyText":"hi"}"#.utf8)
    }

    func testSameMessageInsideTheWindowReusesItsKey() {
        let keys = SendKeys()
        let b = body("retry")
        let first = keys.key(for: b, now: t0)
        XCTAssertEqual(keys.key(for: b, now: t0.addingTimeInterval(1)), first)
        XCTAssertEqual(keys.key(for: b, now: t0.addingTimeInterval(600)), first, "inclusive at ten minutes")
    }

    func testKeyHasTheIosPrefixAndIsOpaque() {
        let key = SendKeys().key(for: body("format"), now: t0)
        XCTAssertNotNil(key.range(of: "^i-[0-9a-f]{32}$", options: .regularExpression), key)
    }

    func testDifferentMessagesGetDifferentKeys() {
        let keys = SendKeys()
        XCTAssertNotEqual(keys.key(for: body("one"), now: t0), keys.key(for: body("two"), now: t0))
    }

    func testSettlingForgetsTheKey() {
        let keys = SendKeys()
        let b = body("settle")
        let first = keys.key(for: b, now: t0)
        keys.settle(b)
        XCTAssertNotEqual(keys.key(for: b, now: t0.addingTimeInterval(1)), first)
    }

    func testKeyExpiresAfterTenMinutes() {
        let keys = SendKeys()
        let b = body("expire")
        let first = keys.key(for: b, now: t0)
        XCTAssertNotEqual(keys.key(for: b, now: t0.addingTimeInterval(601)), first)
    }
}

/// Mirrors android/.../logic/OutboxRetryPolicyTest.kt: the failure table and
/// the stored item whose Idempotency-Key every retry presents.
final class OutboxTests: XCTestCase {
    func testServerErrorsTimeoutsAndRateLimitsAreNotFinal() {
        for status in [500, 502, 503, 504, 599, 408, 429] {
            XCTAssertFalse(Outbox.isFinalRefusal(status: status), "HTTP \(status) must retry")
        }
    }

    func testClientErrorsAreFinal() {
        for status in [400, 401, 403, 404, 409, 413, 422] {
            XCTAssertTrue(Outbox.isFinalRefusal(status: status), "HTTP \(status) must give up")
        }
    }

    func testOnlyTheThreeDeliveryStatesCountAsAccepted() {
        for s in ["sent", "queued", "delivered"] { XCTAssertTrue(Outbox.accepted(s), s) }
        for s in ["failed", "bounced", "", "SENT"] { XCTAssertFalse(Outbox.accepted(s), s) }
        XCTAssertFalse(Outbox.accepted(nil))
    }

    func testItemsExpireAfterThreeDays() {
        XCTAssertEqual(Outbox.maxAge, 3 * 24 * 60 * 60)
        let created = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertFalse(Outbox.expired(createdAt: created, now: created))
        XCTAssertFalse(Outbox.expired(createdAt: created, now: created.addingTimeInterval(Outbox.maxAge)))
        XCTAssertTrue(Outbox.expired(createdAt: created, now: created.addingTimeInterval(Outbox.maxAge + 1)))
    }

    func testIdempotencyKeySurvivesAttemptsAndTheFileFormat() throws {
        var item = OutboxItem(id: "item-1", address: "ada@grapmail.com",
                              body: Data(#"{"to":["bob@example.com"],"subject":"Plan"}"#.utf8),
                              draftId: "draft-7", idempotencyKey: "o-0123456789abcdef0123456789abcdef",
                              createdAt: Date(timeIntervalSince1970: 1_800_000_000))
        for _ in 0..<5 { item.attempts += 1 }
        let data = try JSONEncoder().encode([item])
        let back = try JSONDecoder().decode([OutboxItem].self, from: data)
        XCTAssertEqual(back.count, 1)
        XCTAssertEqual(back[0].idempotencyKey, "o-0123456789abcdef0123456789abcdef")
        XCTAssertEqual(back[0].attempts, 5)
        XCTAssertEqual(back[0].body, item.body)
        XCTAssertEqual(back[0].address, "ada@grapmail.com")
        XCTAssertEqual(back[0].createdAt, item.createdAt)
    }
}
