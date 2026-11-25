import SwiftUI

// ── micro-motion ─────────────────────────────────────────────────────────────

/// The app's micro-motion vocabulary: the few durations and curves the small
/// icon animations use. The iOS twin of Android's `GMMotion`
/// (ui/components/Motion.kt), with the same numbers, so a star or a swipe
/// feels the same on both apps (docs/design/round8-spec.md §1.10).
///
/// Rules every user of this file keeps: 150–300 ms for micro-motion
/// (`extraLong` is for a sheet opening or a score ring filling, `celebration`
/// only for the one-off confetti drift), standard or emphasized easing,
/// springs damped enough not to wobble, and motion only in response to a state
/// change — never a loop, never on scroll, never on first appearance. Every
/// caller reads `@Environment(\.accessibilityReduceMotion)` and, when it is on,
/// shows the end state straight away (or a 150 ms crossfade, `reducedFade`);
/// nothing is hidden behind an animation, so skipping one never loses state.
/// With Reduce Motion: no translate/scale/rotate/particles, progress rings draw
/// at their final value, no confetti. Haptics are not motion and still play.
enum GMMotion {
    static let short: Double = 0.15
    static let medium: Double = 0.22
    static let long: Double = 0.30
    /// 450 ms: a sheet opening, the safety-score ring filling.
    static let extraLong: Double = 0.45
    /// 1200 ms in total: the celebration's confetti drift. Never under Reduce
    /// Motion — gate it with `gated(_:reduced:)` and draw no particles at all.
    static let celebration: Double = 1.2

    /// Material 3 standard easing.
    static func standard(_ duration: Double = medium) -> Animation {
        .timingCurve(0.2, 0, 0, 1, duration: duration)
    }

    /// Emphasized-decelerate: things arriving.
    static func emphasizedDecelerate(_ duration: Double = medium) -> Animation {
        .timingCurve(0.05, 0.7, 0.1, 1, duration: duration)
    }

    /// Emphasized-accelerate: things leaving.
    static func emphasizedAccelerate(_ duration: Double = medium) -> Animation {
        .timingCurve(0.3, 0, 0.8, 0.15, duration: duration)
    }

    /// A settle spring with at most one small overshoot (Android's
    /// dampingRatio 0.6, StiffnessMedium).
    static let settle: Animation = .spring(response: 0.16, dampingFraction: 0.6)

    /// The Reduce Motion stand-in for a movement: a 150 ms opacity crossfade.
    static let reducedFade: Animation = .easeInOut(duration: short)

    /// `animation` unless motion is reduced, in which case no animation at all.
    static func gated(_ animation: Animation, reduced: Bool) -> Animation? {
        reduced ? nil : animation
    }

    /// `animation` unless motion is reduced, in which case the 150 ms
    /// crossfade (`reducedFade`) — for changes that should still soften, such
    /// as an image swapping in, where the view itself does not move.
    static func gatedOrFade(_ animation: Animation, reduced: Bool) -> Animation {
        reduced ? reducedFade : animation
    }

    /// Two steps, one after the other: `first` under `firstAnimation`, then,
    /// once it has had `after` seconds, `second` under `secondAnimation`.
    /// iOS 16 has no keyframe animator; this is the pop (out, then back).
    /// Called from view code only, on the main thread.
    static func twoStep(_ firstAnimation: Animation, after: Double, _ first: () -> Void,
                        then secondAnimation: Animation, _ second: @escaping () -> Void) {
        withAnimation(firstAnimation) { first() }
        DispatchQueue.main.asyncAfter(deadline: .now() + after) {
            withAnimation(secondAnimation) { second() }
        }
    }
}

/// One horizontal shake, 280 ms: the send controls refusing. Drive it by
/// adding 1 to `shakes` inside `withAnimation(.linear(duration: 0.28))`; at
/// rest (any whole number) it draws nothing. Android's keyframes:
/// −6 pt at 40 ms, +6 at 100, −4 at 160, +3 at 220, 0 at 280.
struct GMShakeEffect: GeometryEffect {
    var shakes: CGFloat

    var animatableData: CGFloat {
        get { shakes }
        set { shakes = newValue }
    }

    func effectValue(size: CGSize) -> ProjectionTransform {
        let t = shakes - shakes.rounded(.down)
        guard t > 0 else { return ProjectionTransform(.identity) }
        let frames: [(CGFloat, CGFloat)] = [(0, 0), (40, -6), (100, 6), (160, -4), (220, 3), (280, 0)]
        let ms = t * 280
        var x: CGFloat = 0
        for i in 1..<frames.count where ms <= frames[i].0 {
            let (t0, v0) = frames[i - 1]
            let (t1, v1) = frames[i]
            x = v0 + (v1 - v0) * (ms - t0) / (t1 - t0)
            break
        }
        return ProjectionTransform(CGAffineTransform(translationX: x, y: 0))
    }
}
