import SwiftUI

/// Everything the recovery and security screens pass between each other.
///
/// A single object rather than navigation values, because two of these are
/// secrets: the password a 2FA challenge was opened with, and the code that
/// proves it. Navigation paths are restorable state; a flow that dies with the
/// app is the right home for both.
final class RecoveryModel: ObservableObject {
    @Published var identifier = ""
    @Published var password = ""
    @Published var challengeId: String?
    @Published var expiresAt: Date?
    @Published var maskedPhone: String?
    /// The masked address a two-step sign-in code was emailed to.
    @Published var factorDestination: String?
    /// "email" when forgot-password emailed a reset link instead of sending a code.
    @Published var resetChannel: String?
    @Published var code = ""
    @Published var resetAddress: String?
    @Published var foundAddress: String?
    @Published var foundAddressFull: String?
    @Published var phone = ""
    @Published var phoneChallengeId: String?
    @Published var phoneExpiresAt: Date?
    @Published var verifiedPhone: String?
    @Published var prefillIdentifier: String?
    @Published var verificationEmail: String?

    func clearSignInChallenge() {
        password = ""
        challengeId = nil
        expiresAt = nil
        code = ""
        verificationEmail = nil
        factorDestination = nil
    }
}

// Round 8 colour note (docs/design/round8-spec.md §1.2): cobalt `GM.primarySolid`
// is a FILL. Text, links and icons drawn on a surface in this sector use
// `GM.primaryText`, which is the same cobalt in light and lifts to #8EA8FF in
// dark — cobalt text on the near-black canvas is under 4.5:1. Focused strokes
// use `GM.focusRing`.

/// The recovery/security screen shell — founder design verbatim.
///
/// Back arrow and centred wordmark, the screen's own artwork, the blue eyebrow,
/// the 28pt title, the body paragraph, whatever the screen adds, then the
/// footer. The body scrolls and the footer stays put, so the action is always
/// reachable and never pushed off-screen by copy.
///
/// Adaptive: the column is capped at `GMLayout.form` and centred on wide
/// windows (iPad, Stage Manager, Mac); in short windows (SE, landscape, large
/// text) the artwork shrinks and the footer scrolls with the copy; in a
/// landscape phone (compact height) the artwork is left out so the fields and
/// the action stay on screen.
struct AccountScreen<Content: View, Footer: View>: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    var onBack: (() -> Void)? = nil
    var illustration: String? = nil
    var illustrationWidth: CGFloat = 200
    var eyebrow: String? = nil
    var title: String? = nil
    var subtitle: String? = nil
    @ViewBuilder let content: () -> Content
    @ViewBuilder let footer: () -> Footer

    var body: some View {
        // The designs are drawn on an 852pt phone. On a short one the artwork
        // gives way first and the action scrolls with the copy, so a field is
        // never left half-clipped under a pinned button.
        GeometryReader { geo in
            let tight = geo.size.height < 660 || textSize.isAccessibilitySize
            let showArt = verticalSizeClass != .compact && geo.size.height >= 480
            let artWidth = min(tight ? illustrationWidth * 0.75 : illustrationWidth,
                               max(0, geo.size.width - 48))
            VStack(spacing: 0) {
                AccountHeader(onBack: onBack)
                // Screen states: offline notice above the unchanged content.
                GMOfflineBanner()
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if let illustration, showArt {
                            HStack {
                                Spacer()
                                // Code-drawn vector scene, not a cropped PNG:
                                // it scales with the width it is given and
                                // dark mode is the same code, not a second
                                // `_dark` image.
                                Art.view(illustration, width: artWidth)
                                Spacer()
                            }
                            .padding(.top, tight ? 2 : 4)
                            .padding(.bottom, tight ? 10 : 18)
                        } else {
                            Spacer().frame(height: tight ? 8 : 16)
                        }
                        if let eyebrow {
                            Text(eyebrow.uppercased())
                                .gmFont(11, .bold)
                                .kerning(1.6)
                                .foregroundColor(GM.primaryText)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(.bottom, 8)
                        }
                        if let title {
                            Text(title)
                                .gmFont(28, .bold, maxSize: 44)
                                .foregroundColor(GM.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(.bottom, tight ? 8 : 10)
                        }
                        if let subtitle {
                            Text(subtitle)
                                .gmFont(15)
                                .lineSpacing(5)
                                .foregroundColor(GM.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(.bottom, tight ? 16 : 22)
                        }
                        content()
                        Spacer().frame(height: 16)
                        if tight {
                            VStack(alignment: .leading, spacing: 0) { footer() }
                            Spacer().frame(height: 12)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 24)
                }
                .scrollDismissesKeyboard(.interactively)
                if !tight {
                    VStack(alignment: .leading, spacing: 0) { footer() }
                        .padding(.horizontal, 24)
                    Spacer().frame(height: 12)
                }
            }
            .gmMaxWidth(GMLayout.form)
            .background(GM.bg.ignoresSafeArea())
        }
    }
}

struct AccountHeader: View {
    var onBack: (() -> Void)? = nil
    @ScaledMetric(relativeTo: .body) private var barHeight: CGFloat = 56

    var body: some View {
        ZStack {
            if let onBack {
                HStack {
                    Button(action: onBack) {
                        Image(systemName: "chevron.left")
                            .gmFont(17, .semibold, maxSize: 28)
                            .foregroundColor(GM.textPrimary)
                            .flipsForRightToLeftLayoutDirection(true)
                            // A 44 pt target, not just the 17 pt glyph.
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    // A word for VoiceOver instead of the symbol's name.
                    .accessibilityLabel(Text("Back"))
                    // The CI security walk navigates back by name; an
                    // unlabeled image button is unqueryable in XCUITest.
                    .accessibilityIdentifier("back")
                    Spacer()
                }
                .padding(.leading, 2)
            }
            Wordmark()
        }
        // A bar, so it grows a little with the text size but is capped.
        .frame(minHeight: min(barHeight, 72))
        .padding(.horizontal, 8)
    }
}

/// Soft brand blobs behind the top-right corner of the sign-in card.
struct DecorativeBlobs: View {
    var body: some View {
        GeometryReader { geo in
            ZStack {
                Circle()
                    .fill(RadialGradient(colors: [GM.primarySolid.opacity(0.10), .clear],
                                         center: .center, startRadius: 0, endRadius: geo.size.width * 0.45))
                    .frame(width: geo.size.width * 0.9, height: geo.size.width * 0.9)
                    .offset(x: geo.size.width * 0.38, y: -geo.size.width * 0.10)
                Circle()
                    .fill(RadialGradient(colors: [GM.primaryTop.opacity(0.07), .clear],
                                         center: .center, startRadius: 0, endRadius: geo.size.width * 0.32))
                    .frame(width: geo.size.width * 0.64, height: geo.size.width * 0.64)
                    .offset(x: geo.size.width * 0.28, y: geo.size.width * 0.26)
            }
        }
        .allowsHitTesting(false)
    }
}

enum ChipTone { case success, warning }

struct StatusChip: View {
    let text: String
    let tone: ChipTone

    var body: some View {
        let bg = tone == .success ? GM.successContainer : GM.warningContainer
        let ink = tone == .success ? GM.success : GM.warning
        HStack(spacing: 8) {
            Image(systemName: tone == .success ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                .gmFont(14, .semibold)
            Text(text)
                .gmFont(13, .semibold)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundColor(ink)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        // A capsule on one line; stays a pill (not a lens) if large text wraps.
        .background(RoundedRectangle(cornerRadius: GMShape.pill, style: .continuous).fill(bg))
    }
}

/// The design's code cells. One invisible field sits over the row so a pasted
/// code lands in one piece; the cells are the drawing, the field is the input.
///
/// The cells share the row's width (they are sized by it, not by text), so the
/// character inside scales with the text size but is capped to stay in a cell.
struct CodeCells: View {
    let length: Int
    @Binding var value: String
    var alphanumeric = false
    var groupGap = false

    @FocusState private var focused: Bool

    var body: some View {
        ZStack {
            HStack(spacing: 8) {
                ForEach(0..<length, id: \.self) { i in
                    if groupGap && i == length / 2 {
                        Spacer().frame(width: 4)
                    }
                    cell(i)
                }
            }
            TextField("", text: $value)
                .keyboardType(alphanumeric ? .asciiCapable : .numberPad)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .focused($focused)
                .foregroundColor(.clear)
                .tint(.clear)
                .opacity(0.02)
                // The real input under the drawn cells: VoiceOver otherwise
                // meets an unnamed text field.
                .accessibilityLabel(Text("Verification code"))
                .onChange(of: value) { newValue in
                    let allowed = newValue.filter { alphanumeric ? $0.isLetter || $0.isNumber : $0.isNumber }
                    let capped = String((alphanumeric ? allowed.uppercased() : allowed).prefix(length))
                    if capped != value { value = capped }
                }
        }
        // On a wide window the cells would otherwise grow into huge squares.
        .frame(maxWidth: GMLayout.form)
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .onTapGesture { focused = true }
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { focused = true }
        }
    }

    private func cell(_ i: Int) -> some View {
        let chars = Array(value)
        let char = i < chars.count ? String(chars[i]) : ""
        let isFocused = focused && i == min(value.count, length - 1)
        return ZStack {
            RoundedRectangle(cornerRadius: GMShape.input, style: .continuous)
                .fill(GM.otpFill)
            RoundedRectangle(cornerRadius: GMShape.input, style: .continuous)
                .strokeBorder(isFocused ? GM.focusRing : GM.border, lineWidth: isFocused ? 1.5 : 1)
            Text(char)
                .gmFont(length > 8 ? 17 : 20, .semibold, maxSize: length > 8 ? 24 : 30)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .foregroundColor(GM.textPrimary)
        }
        .aspectRatio(0.94, contentMode: .fit)
    }
}

/// "Code expires in 04:52" — ticking without a timer object to leak.
struct CodeExpiry: View {
    let expiresAt: Date?
    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        if let expiresAt {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let remaining = max(0, Int(expiresAt.timeIntervalSince(context.date)))
                let countdown = remaining > 0
                    ? String(format: "%02d:%02d", remaining / 60, remaining % 60)
                    : String(localized: "expired", comment: "Verification code countdown once the code has run out")
                HStack(spacing: 7) {
                    Image(systemName: "clock")
                        .gmFont(13)
                        .foregroundColor(GM.textTertiary)
                    // One sentence with the countdown inside it, so a translation
                    // can put the time wherever its grammar needs it.
                    Text("Code expires in \(Text(countdown).font(.gm(13, .bold, scaledFor: dts)).foregroundColor(remaining > 0 ? GM.primaryText : GM.error))")
                        .gmFont(13)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

struct OrDivider: View {
    var body: some View {
        HStack(spacing: 8) {
            Rectangle().fill(GM.border).frame(height: 1)
            Text(" OR ")
                .gmFont(12, .medium)
                .foregroundColor(GM.textTertiary)
                .fixedSize()
            Rectangle().fill(GM.border).frame(height: 1)
        }
    }
}

/// The sector's centred brand link ("Resend code", "Back to sign in").
struct AccountLink: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .gmFont(13, .semibold)
                .foregroundColor(GM.primaryText)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
    }
}

/// The shield note that closes most screens, with an inline link when the
/// sentence carries one.
struct FooterNote: View {
    let text: String
    var linkText: String? = nil
    var onLink: (() -> Void)? = nil
    var divider: Bool = true
    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if divider {
                Rectangle().fill(GM.border).frame(height: 1)
                Spacer().frame(height: 16)
            }
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "checkmark.shield.fill")
                    .gmFont(15)
                    .foregroundColor(GM.primaryText)
                Text(attributed)
                    .gmFont(12)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .environment(\.openURL, OpenURLAction { url in
            if url.scheme == "grapmail" {
                onLink?()
                return .handled
            }
            return .systemAction
        })
    }

    private var attributed: AttributedString {
        var s = AttributedString(text)
        if let linkText, let range = s.range(of: linkText), onLink != nil {
            s[range].foregroundColor = GM.primaryText
            s[range].font = .gm(12, .semibold, scaledFor: dts)
            s[range].link = URL(string: "grapmail://footer")
        }
        return s
    }
}

/// The "What's happening?" card.
struct InfoPanel<Icon: View>: View {
    let title: String
    let detail: String
    let icon: () -> Icon
    @ScaledMetric(relativeTo: .body) private var disc: CGFloat = 36
    // The stored line is `detail`, not `body`: a `body` property collides with
    // SwiftUI's required `var body`. The initializer keeps the external `body:`
    // label (and the trailing-closure `icon:`) so every call site is unchanged.
    init(title: String, body: String, @ViewBuilder icon: @escaping () -> Icon) {
        self.title = title
        self.detail = body
        self.icon = icon
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            icon()
                .frame(width: min(disc, 56), height: min(disc, 56))
                .background(Circle().fill(GM.softFill))
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(detail)
                    .gmFont(13)
                    .lineSpacing(3)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).fill(GM.cardAlt))
        .overlay(
            RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).strokeBorder(GM.border)
        )
    }
}

/// The two-step ribbon on the phone screens.
///
/// At the accessibility text sizes the steps are listed one under the other —
/// two numbered labels and a connector no longer fit across a phone.
struct TwoStepRibbon: View {
    let current: Int
    let labels: [String]
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .body) private var dot: CGFloat = 24

    var body: some View {
        if dts.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(0..<labels.count, id: \.self) { index in
                    step(index)
                }
            }
        } else {
            HStack(spacing: 0) {
                ForEach(0..<labels.count, id: \.self) { index in
                    let n = index + 1
                    let done = n < current
                    step(index)
                    if n < labels.count {
                        Rectangle()
                            .fill(done ? GM.primarySolid : GM.border)
                            .frame(height: 1.5)
                            .padding(.horizontal, 10)
                    }
                }
            }
        }
    }

    private func step(_ index: Int) -> some View {
        let label = labels[index]
        let n = index + 1
        let done = n < current
        let active = n == current
        return HStack(spacing: 8) {
            ZStack {
                Circle()
                    .fill(done ? GM.primaryTop.opacity(0.45) : (active ? GM.primarySolid : GM.stepInactive))
                    .frame(width: min(dot, 40), height: min(dot, 40))
                if done {
                    Image(systemName: "checkmark")
                        .gmFont(11, .bold, maxSize: 18)
                        .foregroundColor(GM.onPrimary)
                } else {
                    Text("\(n)")
                        .gmFont(12, .semibold, maxSize: 20)
                        .foregroundColor(active ? GM.onPrimary : GM.textSecondary)
                }
            }
            Text(label)
                .gmFont(13, active ? .semibold : .regular)
                .foregroundColor(active ? GM.primaryText : GM.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// A copy affordance inside a card.
struct CopyButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "doc.on.doc")
                    .gmFont(12, .semibold)
                Text(title)
                    .gmFont(13, .semibold)
                    .lineLimit(2)
            }
            .foregroundColor(GM.primaryText)
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .overlay(
                RoundedRectangle(cornerRadius: GMShape.input - 2, style: .continuous)
                    .strokeBorder(GM.border)
            )
        }
        .buttonStyle(.plain)
    }
}

/// One line of the password checklist.
struct RuleLine: View {
    let label: String
    let met: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill")
                .gmFont(15)
                .foregroundColor(met ? GM.primaryText : GM.textTertiary)
            Text(label)
                .gmFont(13)
                .foregroundColor(met ? GM.textSecondary : GM.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 5)
    }
}

/// A bordered card — the shape every summary in this sector sits in.
struct InfoCard<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        content()
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).fill(GM.card))
            .overlay(
                RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).strokeBorder(GM.border)
            )
    }
}

/// Soft-fill icon tile used by the summary cards.
struct IconTile: View {
    let systemName: String
    @ScaledMetric(relativeTo: .body) private var tile: CGFloat = 40

    var body: some View {
        Image(systemName: systemName)
            .gmFont(17, .medium, maxSize: 30)
            .foregroundColor(GM.primaryText)
            .frame(width: min(tile, 60), height: min(tile, 60))
            .background(RoundedRectangle(cornerRadius: GMShape.thumb, style: .continuous).fill(GM.softFill))
    }
}

/// "+880 1XXXXXXXXX" — how a number is shown once it is known.
enum PhoneFormat {
    static let countries: [(flag: String, dial: String, name: String)] = [
        ("🇧🇩", "+880", String(localized: "Bangladesh", comment: "Country name in the phone number picker")),
        ("🇮🇳", "+91", String(localized: "India", comment: "Country name in the phone number picker")),
        ("🇵🇰", "+92", String(localized: "Pakistan", comment: "Country name in the phone number picker")),
        ("🇺🇸", "+1", String(localized: "United States", comment: "Country name in the phone number picker")),
        ("🇬🇧", "+44", String(localized: "United Kingdom", comment: "Country name in the phone number picker")),
        ("🇦🇪", "+971", String(localized: "United Arab Emirates", comment: "Country name in the phone number picker")),
        ("🇸🇦", "+966", String(localized: "Saudi Arabia", comment: "Country name in the phone number picker")),
        ("🇲🇾", "+60", String(localized: "Malaysia", comment: "Country name in the phone number picker")),
    ]

    /// E.164 from the dial code and what the person typed.
    static func e164(dial: String, national: String) -> String? {
        let digits = national.filter(\.isNumber)
        guard digits.count >= 6 else { return nil }
        // Local convention: 01XXXXXXXXX is +880 1XXXXXXXXX.
        let trimmed = dial != "+1" && digits.hasPrefix("0")
            ? String(digits.drop(while: { $0 == "0" }))
            : digits
        return dial + trimmed
    }

    static func pretty(_ e164: String) -> String {
        for c in countries where e164.hasPrefix(c.dial) {
            return c.dial + " " + String(e164.dropFirst(c.dial.count))
        }
        return e164
    }
}
