import XCTest
@testable import GrapMail

/// Mirrors android/.../logic/NotificationPolicyTest.kt: the device copy of the
/// server's decideNotification. Only the pure functions are exercised.
final class NotificationPolicyTests: XCTestCase {
    private let iso = ISO8601DateFormatter()
    private func at(_ s: String) -> Date { iso.date(from: s)! }
    // 23:30 and 09:00 in Dhaka (UTC+6, no DST).
    private var lateEveningDhaka: Date { at("2026-11-23T17:30:00Z") }
    private var morningDhaka: Date { at("2026-11-23T03:00:00Z") }

    private func settings(_ change: (inout NotificationSettingsInfo) -> Void = { _ in }) -> NotificationSettingsInfo {
        var s = NotificationSettingsInfo()
        change(&s)
        return s
    }
    private func quiet(_ start: String = "22:00", _ end: String = "07:00", zone: String = "Asia/Dhaka", allowVip: Bool = true) -> QuietHoursInfo {
        var q = QuietHoursInfo()
        q.enabled = true; q.start = start; q.end = end; q.timeZone = zone; q.allowVip = allowVip
        return q
    }
    private func decide(_ s: NotificationSettingsInfo, _ from: String?, _ thread: String? = nil, now: Date? = nil) -> NotificationPolicy.Decision {
        NotificationPolicy.decide(s, fromAddress: from, threadKey: thread, now: now ?? morningDhaka)
    }

    func testDefaultsAlertForEverythingWithAPreview() {
        XCTAssertEqual(decide(settings(), "a@example.com", "t1"), .init(notify: true, generic: false))
    }

    func testPreviewOffMakesTheAlertGeneric() {
        let d = decide(settings { $0.showPreview = false }, "a@example.com")
        XCTAssertTrue(d.notify)
        XCTAssertTrue(d.generic)
    }

    func testSecurityMailAlwaysAlerts() {
        let s = settings {
            $0.mode = "none"; $0.mutedThreads = ["t1"]; $0.quietHours = quiet(allowVip: false); $0.showPreview = false
        }
        let d = decide(s, "  Security@Grapsee.com ", "t1", now: lateEveningDhaka)
        XCTAssertTrue(d.notify)
        XCTAssertTrue(d.generic)
    }

    func testMutedThreadNeverAlertsEvenFromAVip() {
        let s = settings { $0.mutedThreads = ["t1"]; $0.watchedThreads = ["t1"]; $0.vipSenders = ["boss@example.com"] }
        XCTAssertFalse(decide(s, "boss@example.com", "t1").notify)
        XCTAssertTrue(decide(s, "boss@example.com", "t2").notify)
    }

    func testModeNoneAlertsOnlyForWatchedThreads() {
        let s = settings { $0.mode = "none"; $0.watchedThreads = ["w"]; $0.vipSenders = ["boss@example.com"] }
        XCTAssertFalse(decide(s, "a@example.com", "x").notify)
        XCTAssertFalse(decide(s, "boss@example.com", "x").notify)
        XCTAssertTrue(decide(s, "a@example.com", "w").notify)
        XCTAssertFalse(decide(settings { $0.mode = "none"; $0.watchedThreads = [""] }, "a@example.com", "").notify)
    }

    func testPriorityModeAlertsForVipAddressesDomainsAndWatchedThreads() {
        let s = settings { $0.mode = "priority"; $0.vipSenders = ["boss@example.com", "partner.org"]; $0.watchedThreads = ["w"] }
        XCTAssertTrue(decide(s, "Boss@Example.com", "x").notify)
        XCTAssertTrue(decide(s, "anyone@partner.org", "x").notify)
        XCTAssertTrue(decide(s, "ops@mail.partner.org", "x").notify)
        XCTAssertFalse(decide(s, "x@notpartner.org", "x").notify)
        XCTAssertFalse(decide(s, "stranger@example.com", "x").notify)
        XCTAssertTrue(decide(s, "stranger@example.com", "w").notify)
        XCTAssertFalse(decide(s, nil, "x").notify)
    }

    func testQuietHoursCrossMidnightInTheSavedZone() {
        let s = settings { $0.quietHours = quiet() }
        XCTAssertTrue(NotificationPolicy.inQuietHours(s, now: lateEveningDhaka))
        XCTAssertFalse(NotificationPolicy.inQuietHours(s, now: morningDhaka))
        XCTAssertTrue(NotificationPolicy.inQuietHours(s, now: at("2026-11-23T18:59:00Z")))   // 00:59
        XCTAssertFalse(NotificationPolicy.inQuietHours(s, now: at("2026-11-24T01:00:00Z")))  // 07:00, end exclusive
        XCTAssertTrue(NotificationPolicy.inQuietHours(s, now: at("2026-11-23T16:00:00Z")))   // 22:00, start inclusive
    }

    func testSameDayWindowAndUtcFallback() {
        let s = settings { $0.quietHours = quiet("09:00", "17:00", zone: "UTC") }
        XCTAssertFalse(NotificationPolicy.inQuietHours(s, now: at("2026-11-23T08:59:00Z")))
        XCTAssertTrue(NotificationPolicy.inQuietHours(s, now: at("2026-11-23T09:00:00Z")))
        XCTAssertFalse(NotificationPolicy.inQuietHours(s, now: at("2026-11-23T17:00:00Z")))
        let late = at("2026-11-23T23:30:00Z")
        XCTAssertTrue(NotificationPolicy.inQuietHours(settings { $0.quietHours = quiet(zone: "") }, now: late))
        XCTAssertTrue(NotificationPolicy.inQuietHours(settings { $0.quietHours = quiet(zone: "Mars/Olympus_Mons") }, now: late))
    }

    func testDisabledOrMalformedWindowIsNeverQuiet() {
        var off = quiet(); off.enabled = false
        XCTAssertFalse(NotificationPolicy.inQuietHours(settings { $0.quietHours = off }, now: lateEveningDhaka))
        for (start, end) in [("24:00", "07:00"), ("22:00", "7:00"), ("ab:cd", "07:00"), ("22:60", "07:00"), ("", "")] {
            XCTAssertFalse(NotificationPolicy.inQuietHours(settings { $0.quietHours = quiet(start, end) }, now: lateEveningDhaka), "\(start)-\(end)")
        }
    }

    func testQuietHoursLetVipThroughOnlyWhenAllowed() {
        let s = settings { $0.quietHours = quiet(); $0.vipSenders = ["boss@example.com"]; $0.watchedThreads = ["w"] }
        XCTAssertFalse(decide(s, "a@example.com", "x", now: lateEveningDhaka).notify)
        XCTAssertTrue(decide(s, "boss@example.com", "x", now: lateEveningDhaka).notify)
        XCTAssertTrue(decide(s, "a@example.com", "w", now: lateEveningDhaka).notify)
        var noVip = s; noVip.quietHours.allowVip = false
        XCTAssertFalse(decide(noVip, "boss@example.com", "x", now: lateEveningDhaka).notify)
    }

    func testSettingsDecodeLenientlyAndRoundTrip() throws {
        let json = #"{"mode":5,"vipSenders":["a@b.c"],"quietHours":{"enabled":"yes","start":"23:00"},"extra":true}"#
        let s = try JSONDecoder().decode(NotificationSettingsInfo.self, from: Data(json.utf8))
        XCTAssertEqual(s.mode, "all", "a wrong type falls back to the default")
        XCTAssertEqual(s.vipSenders, ["a@b.c"])
        XCTAssertFalse(s.quietHours.enabled)
        XCTAssertEqual(s.quietHours.start, "23:00")
        XCTAssertEqual(s.quietHours.end, "07:00")
        XCTAssertTrue(s.showPreview)
        let again = try JSONDecoder().decode(NotificationSettingsInfo.self, from: JSONEncoder().encode(s))
        XCTAssertEqual(again, s)
        XCTAssertEqual(Set(s.dictionary.keys), ["mode", "vipSenders", "mutedThreads", "watchedThreads", "quietHours", "showPreview"])
    }
}
