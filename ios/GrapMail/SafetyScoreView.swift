import SwiftUI

// MARK: - Round 8 — account safety score (docs/design/round8-spec.md §4)
//
// The server computes the score (§4.1); nothing here re-derives it. The ring,
// the level word, the weighted checklist (§4.2), the header card (§4.3 A) and
// the once-per-account celebration at 100 (§4.4).

/// The copy and ordering rules for the score and its checklist.
enum SafetyCopy {
    static func levelWord(_ level: SecScoreLevel) -> String {
        switch level {
        case .strong: return secL("Strong")
        case .good: return secL("Good")
        default: return secL("Needs attention")
        }
    }

    /// §4.1 ring / level-word colour.
    static func levelColor(_ level: SecScoreLevel) -> Color {
        switch level {
        case .strong: return SecPalette.success
        case .good: return SecPalette.primaryText
        default: return SecPalette.warning
        }
    }

    static func title(_ item: SecChecklistItem) -> String {
        let done = item.isDone
        switch item.id {
        case .twoStep: return done ? secL("2-step verification is on") : secL("Turn on 2-step verification")
        case .passkey: return done ? secL("Passkey added") : secL("Add a passkey")
        case .recoveryCodes: return done ? secL("Recovery codes saved") : secL("Save recovery codes")
        case .recoveryEmail: return done ? secL("Recovery email verified") : secL("Add a recovery email")
        case .secondDevice:
            let count = max(item.meta?.count ?? 2, 2)
            return done ? secL("\(count) trusted devices") : secL("Sign in on a second device")
        case .passwordHealth:
            return done ? secL("Password not found in known breaches") : secL("Change your password")
        case .recoveryPhone: return done ? secL("Phone verified") : secL("Verify a phone number")
        case .alertsReviewed:
            let count = max(item.meta?.count ?? 1, 1)
            return done ? secL("No alerts to review") : secL("Review \(count) security alerts")
        default: return secL("Security step")
        }
    }

    /// The one-line "why it matters" (todo rows only).
    static func why(_ item: SecChecklistItem) -> String? {
        guard !item.isDone else { return nil }
        switch item.id {
        case .twoStep: return secL("Someone with your password still can't get in without a code.")
        case .passkey: return secL("Sign in with your face, fingerprint or screen lock — there's nothing to phish.")
        case .recoveryCodes: return secL("Get back in even if you lose every device.")
        case .recoveryEmail: return secL("We can reach you there if you're ever locked out.")
        case .secondDevice: return secL("A second phone or tablet can vouch for you if you lose this one.")
        case .passwordHealth: return secL("This password appears in a public data breach.")
        case .recoveryPhone: return secL("A second way to get codes if email isn't available.")
        case .alertsReviewed: return secL("Confirm recent sign-ins were really you.")
        default: return nil
        }
    }

    /// §4.2: todo first by points (desc), then done in table order. Unknown
    /// ids and `not_applicable` rows are hidden.
    static func ordered(_ list: [SecChecklistItem]) -> [SecChecklistItem] {
        let visible = list.filter { $0.id.isKnown && $0.state != .notApplicable && $0.state.isKnown }
        let todo = visible.filter(\.isTodo).sorted { lhs, rhs in
            if lhs.points != rhs.points { return lhs.points > rhs.points }
            return tableIndex(lhs.id) < tableIndex(rhs.id)
        }
        let done = visible.filter(\.isDone).sorted { tableIndex($0.id) < tableIndex($1.id) }
        return todo + done
    }

    static func topTodo(_ list: [SecChecklistItem]) -> SecChecklistItem? {
        ordered(list).first(where: \.isTodo)
    }

    private static func tableIndex(_ id: SecChecklistID) -> Int {
        SecChecklistID.known.firstIndex(of: id) ?? Int.max
    }

    /// The fewest todo steps (largest first) that lift the server's own
    /// earned/possible over the Strong line (85 after round-half-up). Guidance
    /// only — the score itself always comes from the server.
    static func stepsToStrong(_ overview: SecurityOverviewResponse) -> Int? {
        let possible = overview.score.possiblePoints
        guard possible > 0 else { return nil }
        var earned = overview.score.earnedPoints
        var steps = 0
        for item in ordered(overview.checklist) where item.isTodo {
            if Double(earned) * 100 / Double(possible) >= 84.5 { break }
            earned += item.points
            steps += 1
        }
        return Double(earned) * 100 / Double(possible) >= 84.5 ? steps : nil
    }

    /// §4.3 A next-step line.
    static func nextStep(_ overview: SecurityOverviewResponse, celebrating: Bool) -> String {
        let score = overview.score
        if celebrating || score.value >= 100 {
            return celebrating ? secL("Your account is fully protected") : secL("You've done everything")
        }
        if score.level != .strong, let steps = stepsToStrong(overview), steps > 0 {
            return secL("\(steps) steps to Strong")
        }
        if let top = topTodo(overview.checklist) {
            return secL("Start with: \(title(top))")
        }
        return secL("You've done everything")
    }

    static func ringLabelValue(_ score: SecScore) -> String {
        secL("\(score.value) out of 100, \(levelWord(score.level))")
    }
}

// MARK: - Ring

/// The safety ring: track `stepInactive`, progress in the level colour, the
/// signature gradient at 100. Animates from `from` to `value` over 450 ms
/// (EmphasizedDecelerate); Reduce Motion shows the final value.
struct SafetyRing: View {
    let value: Int
    let level: SecScoreLevel
    var size: CGFloat = 96
    var stroke: CGFloat = 10
    var showsCenter = true
    /// The previous cached value, when there is one to animate from.
    var from: Int? = nil
    /// §4.4 — play the 100 celebration (once; the caller owns the flag).
    var celebrate = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dts
    @State private var shown: Double = 0
    @State private var checkVisible = false
    @State private var confettiOn = false
    @State private var burst: CGFloat = 0

    /// Spec durations (§1.10 `extraLong`, `celebration`). Literal here because
    /// Motion.swift's new tokens land with Workstream A.
    private static let fill: Double = 0.45
    private static let celebration: Double = 1.2

    private var fraction: Double { Double(min(100, max(0, value))) / 100 }

    var body: some View {
        ZStack {
            Circle()
                .stroke(GM.stepInactive, lineWidth: stroke)
            Circle()
                .trim(from: 0, to: shown)
                .stroke(progressStyle, style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                .rotationEffect(.degrees(-90))
            if showsCenter { center }
            if confettiOn { confetti }
        }
        .frame(width: size, height: size)
        .onAppear(perform: start)
        .onChange(of: value) { _ in
            withAnimation(GMMotion.gated(GMMotion.emphasizedDecelerate(Self.fill), reduced: reduceMotion)) {
                shown = fraction
            }
        }
    }

    private var progressStyle: AnyShapeStyle {
        value >= 100 ? AnyShapeStyle(SecPalette.signature) : AnyShapeStyle(SafetyCopy.levelColor(level))
    }

    @ViewBuilder private var center: some View {
        ZStack {
            if checkVisible {
                // Scale 0.6 → 1 on the settle spring; a plain crossfade under
                // Reduce Motion (§4.4).
                Image(systemName: "checkmark")
                    .font(.system(size: size * 0.32, weight: .bold))
                    .foregroundColor(SecPalette.success)
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.6).combined(with: .opacity))
            } else {
                VStack(spacing: 0) {
                    // The number does not scale past the disc it sits in.
                    Text(verbatim: "\(value)")
                        .font(.gm(size >= 80 ? 28 : 13, .bold, maxSize: size * 0.34, scaledFor: dts))
                        .foregroundColor(GM.textPrimary)
                        .monospacedDigit()
                    if size >= 80 {
                        Text(secL("of 100"))
                            .font(.gm(13, maxSize: size * 0.15, scaledFor: dts))
                            .foregroundColor(GM.textSecondary)
                    }
                }
                .transition(.opacity)
            }
        }
    }

    private var confetti: some View {
        ZStack {
            ForEach(0..<14, id: \.self) { index in
                let angle = (Double(index) / 14) * 2 * .pi
                let reach = size * (0.62 + 0.12 * Double(index % 3))
                Circle()
                    .fill(SecPalette.confetti[index % SecPalette.confetti.count])
                    .frame(width: index % 2 == 0 ? 7 : 5, height: index % 2 == 0 ? 7 : 5)
                    .offset(x: cos(angle) * reach * burst,
                            y: sin(angle) * reach * burst - size * 0.35 * burst)
                    .opacity(Double(1 - burst))
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func start() {
        guard let from, !reduceMotion, from != value else {
            shown = fraction
            if celebrate { celebrateNow(delay: 0) }
            return
        }
        shown = Double(min(100, max(0, from))) / 100
        withAnimation(GMMotion.emphasizedDecelerate(Self.fill)) { shown = fraction }
        if celebrate { celebrateNow(delay: Self.fill) }
    }

    /// §4.4: ring full → number crossfades to a check (settle spring; 150 ms
    /// crossfade under Reduce Motion) → 14 particles drift out over 1.2 s
    /// (none under Reduce Motion) → success haptic → announcement.
    private func celebrateNow(delay: Double) {
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            withAnimation(reduceMotion ? .easeInOut(duration: GMMotion.short) : GMMotion.settle) {
                checkVisible = true
            }
            if !reduceMotion {
                burst = 0
                confettiOn = true
                withAnimation(.easeOut(duration: Self.celebration)) { burst = 1 }
                DispatchQueue.main.asyncAfter(deadline: .now() + Self.celebration + 0.05) { confettiOn = false }
            }
            GMHaptics.success()
            GMAnnounce.say(secL("Account safety 100 out of 100. Fully protected."))
        }
    }
}

// MARK: - Header card (§4.3 A)

/// The top of the Security Center and of Account Security: ring + level +
/// next step, then the checklist collapsed to the top three todo items.
struct SafetyScoreHeader: View {
    let overview: SecurityOverviewResponse
    var celebrate = false
    var celebrateFrom: Int? = nil
    /// Anchor the first-visit `safetyScore` tip on the ring (iOS 17+).
    var tipAnchor = false
    /// A trailing "Open Devices & security" link (Account Security only).
    var onOpenCenter: (() -> Void)? = nil
    let onItem: (SecChecklistItem) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var expanded = false

    private var items: [SecChecklistItem] { SafetyCopy.ordered(overview.checklist) }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            GMAdaptiveStack(spacing: 16) {
                SafetyRing(value: overview.score.value, level: overview.score.level,
                           from: celebrate ? celebrateFrom : nil, celebrate: celebrate)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text(secL("Account safety")))
                    .accessibilityValue(Text(SafetyCopy.ringLabelValue(overview.score)))
                    .featureTip(.safetyScore, enabled: tipAnchor)
                VStack(alignment: .leading, spacing: 4) {
                    Text(secL("Account safety"))
                        .gmFont(.title)
                        .foregroundColor(GM.textPrimary)
                        .accessibilityAddTraits(.isHeader)
                    Text(celebrate ? SafetyCopy.levelWord(.strong) : SafetyCopy.levelWord(overview.score.level))
                        .gmFont(.captionMedium)
                        .foregroundColor(SafetyCopy.levelColor(celebrate ? .strong : overview.score.level))
                    Text(SafetyCopy.nextStep(overview, celebrating: celebrate))
                        .gmFont(.caption)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let onOpenCenter {
                        Button(action: onOpenCenter) {
                            Text(secL("Open Devices & security"))
                                .gmFont(.captionMedium)
                                .foregroundColor(SecPalette.primaryText)
                                .frame(minHeight: 44, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("sec-open-center")
                    }
                }
                Spacer(minLength: 0)
            }
            if celebrate {
                HStack {
                    Spacer()
                    Art.view("ill_sec_hub_secure", width: 200)
                    Spacer()
                }
                .transition(.opacity)
            }
            checklist
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
        .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sec-safety-header")
    }

    @ViewBuilder private var checklist: some View {
        let todo = items.filter(\.isTodo)
        let shown = expanded ? items : Array(todo.prefix(3))
        if !shown.isEmpty {
            VStack(spacing: 0) {
                ForEach(shown) { item in
                    SafetyChecklistRow(item: item) {
                        // Using a step is using the feature the tip describes.
                        FeatureTips.used(.safetyScore)
                        onItem(item)
                    }
                    if item.id != shown.last?.id {
                        Rectangle().fill(GM.border).frame(height: 1).padding(.leading, 36)
                    }
                }
            }
        }
        if items.count > shown.count || expanded {
            Button {
                withAnimation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion)) { expanded.toggle() }
            } label: {
                Text(expanded ? secL("Show fewer steps") : secL("See all \(items.count) steps"))
                    .gmFont(.bodyMedium)
                    .foregroundColor(SecPalette.primaryText)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("sec-see-all-steps")
        }
    }
}

/// One checklist row (§4.2): 64 min height, status icon, title, why-line,
/// "+points" pill for todo. The whole row taps through, done rows included.
struct SafetyChecklistRow: View {
    let item: SecChecklistItem
    let action: () -> Void
    @ScaledMetric(relativeTo: .body) private var icon: CGFloat = 24
    @ScaledMetric(relativeTo: .body) private var minHeight: CGFloat = 64

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: 12) {
                Group {
                    if item.isDone {
                        Image(systemName: "checkmark.circle.fill")
                            .resizable()
                            .foregroundColor(SecPalette.successFill)
                    } else {
                        Circle().strokeBorder(GM.border, lineWidth: 2)
                    }
                }
                .frame(width: icon, height: icon)
                .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(SafetyCopy.title(item))
                        .gmFont(.bodyMedium)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let why = SafetyCopy.why(item) {
                        Text(why)
                            .gmFont(.caption)
                            .foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 8)
                if item.isTodo {
                    Text(secL("+\(item.points)"))
                        .gmFont(.captionMedium)
                        .foregroundColor(SecPalette.onPrimaryContainer)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Capsule().fill(SecPalette.primaryContainer))
                }
            }
            .frame(minHeight: minHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(accessibilityText))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("sec-step-\(item.id.rawValue)")
    }

    private var accessibilityText: String {
        let title = SafetyCopy.title(item)
        if item.isDone { return secL("\(title). Done.") }
        let worth = secL("Worth \(item.points) points")
        return [title, SafetyCopy.why(item), worth].compactMap { $0 }.joined(separator: ". ") + "."
    }
}

// MARK: - Skeleton (§3.7 loading, no cache)

/// A `softFill` block with a 1200 ms linear shimmer; static under Reduce Motion.
struct SecSkeletonShape: View {
    var width: CGFloat? = nil
    var height: CGFloat
    var radius: CGFloat = 8
    var circle = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var phase: CGFloat = -1

    var body: some View {
        Group {
            if circle {
                Circle().strokeBorder(GM.softFill, lineWidth: 10)
            } else {
                RoundedRectangle(cornerRadius: radius, style: .continuous).fill(GM.softFill)
            }
        }
        .frame(width: width, height: height)
        .frame(maxWidth: width == nil ? .infinity : nil, alignment: .leading)
        .overlay {
            if !reduceMotion {
                GeometryReader { geo in
                    LinearGradient(colors: [.clear, GM.card.opacity(0.55), .clear],
                                   startPoint: .leading, endPoint: .trailing)
                        .frame(width: geo.size.width * 0.6)
                        .offset(x: phase * geo.size.width)
                }
                .clipped()
                .allowsHitTesting(false)
            }
        }
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.linear(duration: 1.2).repeatForever(autoreverses: false)) { phase = 1.4 }
        }
    }
}

/// Score header skeleton: 96 ring outline + 2 text bars.
struct SafetyScoreSkeleton: View {
    var body: some View {
        HStack(spacing: 16) {
            SecSkeletonShape(width: 96, height: 96, circle: true)
            VStack(alignment: .leading, spacing: 10) {
                SecSkeletonShape(width: 140, height: 14)
                SecSkeletonShape(width: 200, height: 12)
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
        .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
    }
}
