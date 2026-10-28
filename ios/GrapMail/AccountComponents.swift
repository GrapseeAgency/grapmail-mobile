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
    @Published var code = ""
    @Published var resetAddress: String?
    @Published var foundAddress: String?
    @Published var foundAddressFull: String?
    @Published var phone = ""
    @Published var phoneChallengeId: String?
    @Published var phoneExpiresAt: Date?
    @Published var verifiedPhone: String?
    @Published var prefillIdentifier: String?
}

/// The recovery/security screen shell — founder design verbatim.
///
/// Back arrow and centred wordmark, the screen's own artwork, the blue eyebrow,
/// the 28pt title, the body paragraph, whatever the screen adds, then the
/// footer. The body scrolls and the footer stays put, so the action is always
/// reachable and never pushed off-screen by copy.
struct AccountScreen<Content: View, Footer: View>: View {
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
            let tight = geo.size.height < 660
            VStack(spacing: 0) {
                AccountHeader(onBack: onBack)
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if let illustration {
                            HStack {
                                Spacer()
                                Image(illustration)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: tight ? illustrationWidth * 0.75 : illustrationWidth)
                                Spacer()
                            }
                            .padding(.top, tight ? 2 : 4)
                            .padding(.bottom, tight ? 10 : 18)
                        } else {
                            Spacer().frame(height: tight ? 8 : 16)
                        }
                        if let eyebrow {
                            Text(eyebrow.uppercased())
                                .font(.system(size: 11, weight: .bold))
                                .kerning(1.6)
                                .foregroundColor(GM.primarySolid)
                                .padding(.bottom, 8)
                        }
                        if let title {
                            Text(title)
                                .font(.system(size: 28, weight: .bold))
                                .foregroundColor(GM.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(.bottom, tight ? 8 : 10)
                        }
                        if let subtitle {
                            Text(subtitle)
                                .font(.system(size: 15))
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
            .centeredForm()
            .background(GM.bg.ignoresSafeArea())
        }
    }
}

struct AccountHeader: View {
    var onBack: (() -> Void)? = nil

    var body: some View {
        ZStack {
            if let onBack {
                HStack {
                    Button(action: onBack) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundColor(GM.textPrimary)
                    }
                    .buttonStyle(.plain)
                    // The CI security walk navigates back by name; an
                    // unlabeled image button is unqueryable in XCUITest.
                    .accessibilityIdentifier("back")
                    Spacer()
                }
                .padding(.leading, 12)
            }
            Wordmark()
        }
        .frame(height: 56)
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
        let bg = tone == .success ? GM.successChipBg : GM.warnChipBg
        let ink = tone == .success ? GM.successInk : GM.warnInk
        HStack(spacing: 8) {
            Image(systemName: tone == .success ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                .font(.system(size: 14, weight: .semibold))
            Text(text).font(.system(size: 13, weight: .semibold))
        }
        .foregroundColor(ink)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Capsule().fill(bg))
    }
}

/// The design's code cells. One invisible field sits over the row so a pasted
/// code lands in one piece; the cells are the drawing, the field is the input.
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
                .onChange(of: value) { newValue in
                    let allowed = newValue.filter { alphanumeric ? $0.isLetter || $0.isNumber : $0.isNumber }
                    let capped = String((alphanumeric ? allowed.uppercased() : allowed).prefix(length))
                    if capped != value { value = capped }
                }
        }
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
                .strokeBorder(isFocused ? GM.primarySolid : GM.border, lineWidth: isFocused ? 1.5 : 1)
            Text(char)
                .font(.system(size: length > 8 ? 17 : 20, weight: .semibold))
                .foregroundColor(GM.textPrimary)
        }
        .aspectRatio(0.94, contentMode: .fit)
    }
}

/// "Code expires in 04:52" — ticking without a timer object to leak.
struct CodeExpiry: View {
    let expiresAt: Date?

    var body: some View {
        if let expiresAt {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let remaining = max(0, Int(expiresAt.timeIntervalSince(context.date)))
                HStack(spacing: 7) {
                    Image(systemName: "clock")
                        .font(.system(size: 13))
                        .foregroundColor(GM.textTertiary)
                    Text("Code expires in ")
                        .font(.system(size: 13))
                        .foregroundColor(GM.textSecondary)
                    Text(remaining > 0 ? String(format: "%02d:%02d", remaining / 60, remaining % 60) : "expired")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(remaining > 0 ? GM.primarySolid : GM.error)
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
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(GM.textTertiary)
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
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(GM.primarySolid)
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

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if divider {
                Rectangle().fill(GM.border).frame(height: 1)
                Spacer().frame(height: 16)
            }
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "checkmark.shield.fill")
                    .font(.system(size: 15))
                    .foregroundColor(GM.primarySolid)
                Text(attributed)
                    .font(.system(size: 12))
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
            s[range].foregroundColor = GM.primarySolid
            s[range].font = .system(size: 12, weight: .semibold)
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
                .frame(width: 36, height: 36)
                .background(Circle().fill(GM.softFill))
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                Text(detail)
                    .font(.system(size: 13))
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
struct TwoStepRibbon: View {
    let current: Int
    let labels: [String]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<labels.count, id: \.self) { index in
                let label = labels[index]
                let n = index + 1
                let done = n < current
                let active = n == current
                HStack(spacing: 8) {
                    ZStack {
                        Circle()
                            .fill(done ? GM.primaryTop.opacity(0.45) : (active ? GM.primarySolid : GM.stepInactive))
                            .frame(width: 24, height: 24)
                        if done {
                            Image(systemName: "checkmark")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.white)
                        } else {
                            Text("\(n)")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(active ? .white : GM.textSecondary)
                        }
                    }
                    Text(label)
                        .font(.system(size: 13, weight: active ? .semibold : .regular))
                        .foregroundColor(active ? GM.primarySolid : GM.textTertiary)
                }
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

/// A copy affordance inside a card.
struct CopyButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 12, weight: .semibold))
                Text(title).font(.system(size: 13, weight: .semibold))
            }
            .foregroundColor(GM.primarySolid)
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
                .font(.system(size: 15))
                .foregroundColor(met ? GM.primarySolid : GM.textTertiary)
            Text(label)
                .font(.system(size: 13))
                .foregroundColor(met ? GM.textSecondary : GM.textTertiary)
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

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 17, weight: .medium))
            .foregroundColor(GM.primarySolid)
            .frame(width: 40, height: 40)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(GM.softFill))
    }
}

/// "+880 1XXXXXXXXX" — how a number is shown once it is known.
enum PhoneFormat {
    static let countries: [(flag: String, dial: String, name: String)] = [
        ("🇧🇩", "+880", "Bangladesh"),
        ("🇮🇳", "+91", "India"),
        ("🇵🇰", "+92", "Pakistan"),
        ("🇺🇸", "+1", "United States"),
        ("🇬🇧", "+44", "United Kingdom"),
        ("🇦🇪", "+971", "United Arab Emirates"),
        ("🇸🇦", "+966", "Saudi Arabia"),
        ("🇲🇾", "+60", "Malaysia"),
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
