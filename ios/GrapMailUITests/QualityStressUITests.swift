import XCTest

/// Quality job (`.github/workflows/quality.yml`, `ios-stress-a11y`), NOT part of
/// the screenshot walk. Two tests, both run with
///   xcodebuild test ... -only-testing:GrapMailUITests/QualityStressUITests
///
/// 1. `testAccessibilityAuditMainScreens` — `performAccessibilityAudit()` (Xcode 15+,
///    iOS 17+) on the launch screen and the offline `-gmSnapshot` screens the
///    walk already uses (seeded sample data, no account, no server). WARN-ONLY:
///    every issue is printed as `A11Y-ISSUE: ...` and attached, and the handler
///    returns true (= "ignore"), so the audit never fails the test by itself.
/// 2. `testSeededRandomGestures` — ~3 minutes (QUALITY_STRESS_SECONDS, default 180)
///    of random taps/swipes from a fixed seed (QUALITY_STRESS_SEED, default
///    20261123). FAILS if the app stops running (crash) at any step; the seed and
///    step are in the failure message so the run reproduces locally.
///
/// Network chaos: the app has no launch-argument offline simulation, so this
/// test does not toggle the network (the simulator cannot be taken offline from
/// inside XCUITest). The Android monkey job covers network drops.
final class QualityStressUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = true
    }

    // MARK: - Accessibility audit

    func testAccessibilityAuditMainScreens() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("performAccessibilityAudit needs iOS 17 / Xcode 15")
        }
        let screens: [(String, [String])] = [
            ("launch", []),
            ("mail-home", ["-gmSnapshot", "mail-home"]),
            ("mail-thread", ["-gmSnapshot", "mail-thread"]),
            ("mail-labels", ["-gmSnapshot", "mail-labels"]),
        ]
        var total = 0
        for (name, args) in screens {
            let app = XCUIApplication()
            app.launchArguments = args
            app.launch()
            _ = app.wait(for: .runningForeground, timeout: 10)
            sleep(3)
            var lines: [String] = []
            do {
                try app.performAccessibilityAudit(for: .all) { issue in
                    let el = issue.element.map { "\($0.elementType.rawValue):\($0.label)" } ?? "-"
                    lines.append("A11Y-ISSUE: screen=\(name) type=\(issue.auditType.rawValue) desc=\(issue.compactDescription) element=\(el)")
                    return true // warn-only: record, do not fail
                }
            } catch {
                lines.append("A11Y-ISSUE: screen=\(name) audit-error=\(error)")
            }
            lines.forEach { print($0) }
            total += lines.count
            let att = XCTAttachment(string: lines.isEmpty ? "no issues" : lines.joined(separator: "\n"))
            att.name = "a11y-\(name)"
            att.lifetime = .keepAlways
            add(att)
            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = "a11y-\(name)"
            shot.lifetime = .keepAlways
            add(shot)
            app.terminate()
        }
        print("A11Y-TOTAL: \(total)")
    }

    // MARK: - Seeded random gestures

    /// SplitMix64: tiny, deterministic across runs and machines.
    private struct SeededRNG: RandomNumberGenerator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E3779B97F4A7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
            z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            return z ^ (z >> 31)
        }
    }

    func testSeededRandomGestures() {
        let env = ProcessInfo.processInfo.environment
        let seed = UInt64(env["QUALITY_STRESS_SEED"] ?? "") ?? 20261123
        let seconds = TimeInterval(env["QUALITY_STRESS_SECONDS"] ?? "") ?? 180
        var rng = SeededRNG(state: seed)
        print("STRESS-SEED: \(seed) duration=\(Int(seconds))s")

        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15), "app did not start")
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let deadline = Date().addingTimeInterval(seconds)
        var step = 0
        var relaunches = 0

        while Date() < deadline {
            step += 1
            let x = Double.random(in: 0.05...0.95, using: &rng)
            let y = Double.random(in: 0.08...0.95, using: &rng)
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y))
            switch Int.random(in: 0..<10, using: &rng) {
            case 0...5:
                start.tap()
            case 6, 7:
                let dy = Double.random(in: -0.4...0.4, using: &rng)
                start.press(forDuration: 0.05,
                            thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: min(max(y + dy, 0.05), 0.95))))
            case 8:
                let dx = Double.random(in: -0.5...0.5, using: &rng)
                start.press(forDuration: 0.05,
                            thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: min(max(x + dx, 0.02), 0.98), dy: y)))
            default:
                // Edge-swipe back, the most common iOS navigation gesture.
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5))
                    .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5)))
            }

            // System alerts (notifications permission etc.) would swallow every tap.
            let alert = springboard.alerts.firstMatch
            if alert.exists {
                let allow = alert.buttons.element(boundBy: alert.buttons.count > 1 ? 1 : 0)
                if allow.exists { allow.tap() }
            }

            switch app.state {
            case .runningForeground:
                break
            case .notRunning, .unknown:
                XCTFail("CRASH: app not running after step \(step) (seed \(seed)). Reproduce with QUALITY_STRESS_SEED=\(seed).")
                let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
                shot.name = "crash-step-\(step)"; shot.lifetime = .keepAlways; add(shot)
                return
            default:
                // Backgrounded (a link opened Safari, a share sheet left the app): bring it back.
                relaunches += 1
                app.activate()
                _ = app.wait(for: .runningForeground, timeout: 10)
            }
        }
        print("STRESS-DONE: steps=\(step) reactivations=\(relaunches) seed=\(seed)")
    }
}
