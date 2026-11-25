import Foundation
import UIKit

// ── haptics ─────────────────────────────────────────────────────────────────

/// The one place GrapMail touches the Taptic Engine. Every haptic in the app
/// goes through here; nothing else constructs a feedback generator.
///
/// Two layers:
/// - `Haptics` — the raw kinds (success / warning / error / light / medium /
///   selection), for the few UI moments that are not a model result (the
///   puzzle slider landing or missing).
/// - `GMHaptics` — the semantic events the Android app also plays
///   (`GMHaptics.kt`: filing, marking, success, failure). Models call these,
///   because a haptic belongs to the *result*, not to the tap: a filing the
///   server refused must not buzz as if it landed.
///
/// There is no in-app haptics setting on either platform. iOS gives no API to
/// read "System Haptics", but UIKit's generators already honour it (and stay
/// silent in the background, in Low Power situations the system decides, and
/// on devices without an engine), so nothing here forces a vibration.
///
/// Callable from anywhere: the generators live on the main actor, and a call
/// from off the main thread hops there instead of touching UIKit directly.
/// Generators are created on first use and re-`prepare()`d after each play so
/// a quick second event (a run of swipes) is not late.
enum Haptics {
    /// A write the server accepted (mail sent, saved to the Outbox).
    static func success() { play { $0.notify(.success) } }

    /// Something worth noticing that is not a failure.
    static func warning() { play { $0.notify(.warning) } }

    /// A write refused, a read that would not answer, a check that failed.
    static func error() { play { $0.notify(.error) } }

    /// A flag changed in place: star, read / unread, undo send.
    static func light() { play { $0.impact(.light) } }

    /// Mail left the screen: archive, trash, spam, snooze, and reverses.
    static func medium() { play { $0.impact(.medium) } }

    /// A discrete value changed (a toggle, a picker step).
    static func selection() { play { $0.select() } }

    private static func play(_ body: @escaping @MainActor (HapticEngine) -> Void) {
        if Thread.isMainThread {
            MainActor.assumeIsolated { body(HapticEngine.shared) }
        } else {
            Task { @MainActor in body(HapticEngine.shared) }
        }
    }
}

/// The semantic events, one-to-one with Android's `GMHaptics` so the two apps
/// feel the same for the same outcome. Android plays Filing as KEYBOARD_TAP,
/// Marking as VIRTUAL_KEY, Success as CONFIRM and Failure as REJECT; iOS maps
/// those to medium impact, light impact, success and error notifications.
enum GMHaptics {
    /// A message moved boxes: archive, trash, spam, restore, snooze, and their
    /// reverses; also unsubscribe / block landing. Medium: the mail leaves.
    static func filing() { Haptics.medium() }

    /// A flag changed on a row that stayed where it was: star, read, unread;
    /// and the undo-send tap. Light: nothing moved.
    static func marking() { Haptics.light() }

    /// The server accepted a send (or it was safely queued in the Outbox).
    static func success() { Haptics.success() }

    /// A write the server refused, or a read it would not answer.
    static func failure() { Haptics.error() }
}

/// Lazily created, main-actor-only generators.
@MainActor
private final class HapticEngine {
    static let shared = HapticEngine()

    private var lightImpact: UIImpactFeedbackGenerator?
    private var mediumImpact: UIImpactFeedbackGenerator?
    private var notification: UINotificationFeedbackGenerator?
    private var selection: UISelectionFeedbackGenerator?

    func impact(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        let generator: UIImpactFeedbackGenerator
        switch style {
        case .medium:
            if mediumImpact == nil { mediumImpact = UIImpactFeedbackGenerator(style: .medium) }
            generator = mediumImpact!
        default:
            if lightImpact == nil { lightImpact = UIImpactFeedbackGenerator(style: .light) }
            generator = lightImpact!
        }
        generator.impactOccurred()
        generator.prepare()
    }

    func notify(_ type: UINotificationFeedbackGenerator.FeedbackType) {
        if notification == nil { notification = UINotificationFeedbackGenerator() }
        let generator = notification!
        generator.notificationOccurred(type)
        generator.prepare()
    }

    func select() {
        if selection == nil { selection = UISelectionFeedbackGenerator() }
        let generator = selection!
        generator.selectionChanged()
        generator.prepare()
    }
}
