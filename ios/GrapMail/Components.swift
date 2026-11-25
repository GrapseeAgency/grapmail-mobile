import SwiftUI

// The GrapMail mark — rounded square, brand gradient, white envelope glyph.
struct GrapMailMark: View {
    var size: CGFloat = 40

    var body: some View {
        // The founder's real mark — the actual logo file, never a drawn
        // imitation. scaledToFit: the whole logo is always visible, uncropped,
        // at every size and on every screen shape.
        Image("GrapMailLogo")
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
    }
}

private struct EnvelopeGlyph: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let r = rect.height * 0.14
        p.addRoundedRect(in: rect, cornerSize: CGSize(width: r, height: r), style: .continuous)
        return p
    }
}

private struct EnvelopeFlap: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.minX + rect.width * 0.02, y: rect.minY + rect.height * 0.04))
        p.addLine(to: CGPoint(x: rect.midX, y: rect.midY + rect.height * 0.02))
        p.addLine(to: CGPoint(x: rect.maxX - rect.width * 0.02, y: rect.minY + rect.height * 0.04))
        return p
    }
}

struct Wordmark: View {
    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        HStack(spacing: 10) {
            GrapMailMark(size: 40)
            // Two-tone logotype — "Grap" in text color, "Mail" in brand blue,
            // exactly as every founder frame shows it. A logotype, so it may be
            // capped: it grows a little with the text size but never crowds
            // the screen title out.
            (Text(verbatim: "Grap").foregroundColor(GM.textPrimary)
                + Text(verbatim: "Mail").foregroundColor(GM.primarySolid))
                .font(.gm(20, .bold, maxSize: 28, scaledFor: dts))
                .lineLimit(1)
        }
    }
}

/// One shared tablet/responsive contract: cap the form column and center it.
/// Screens used to repeat `.frame(maxWidth: 560).frame(maxWidth: .infinity)`
/// by hand — seven copies that could silently drift. 560 matches Android's
/// 520dp cap plus its 2×24dp gutters. Same width as `GMLayout.form`.
extension View {
    func centeredForm() -> some View {
        self.gmMaxWidth(GMLayout.form)
    }
}

/// The design's numbered-circle stepper: five circles with labels beneath
/// (Name, Details, Address, Password, Terms), completed and current filled.
struct StepProgress: View {
    let step: Int
    /// The number disc grows with the text size so its digit never clips.
    @ScaledMetric(relativeTo: .body) private var disc: CGFloat = 30

    private let labels = [
        String(localized: "Name", comment: "Sign-up stepper label: step 1"),
        String(localized: "Details", comment: "Sign-up stepper label: step 2"),
        String(localized: "Address", comment: "Sign-up stepper label: step 3"),
        String(localized: "Password", comment: "Sign-up stepper label: step 4"),
        String(localized: "Terms", comment: "Sign-up stepper label: step 5"),
    ]

    var body: some View {
        let side = min(disc, 44)
        HStack(alignment: .top, spacing: 0) {
            ForEach(0..<5, id: \.self) { i in
                let n = i + 1
                let active = n <= step
                // Equal columns. Before this the labels competed with the
                // connectors for width, so "Address" and "Password" were
                // proposed less than their intrinsic width and wrapped
                // mid-word ("Addres / s", "Passw / ord") on a 402pt screen —
                // Android had always drawn them on one line.
                VStack(spacing: 5) {
                    Circle()
                        .fill(active ? GM.primarySolid : GM.stepInactive)
                        .frame(width: side, height: side)
                        .overlay(
                            Text("\(n)")
                                .gmFont(13, .semibold, maxSize: 20)
                                .foregroundColor(active ? .white : GM.textSecondary)
                        )
                    Text(labels[i])
                        .gmFont(12, n == step ? .semibold : .regular)
                        .foregroundColor(n == step ? GM.textPrimary : GM.textTertiary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                .frame(maxWidth: .infinity)
                if n < 5 {
                    Rectangle()
                        .fill(n < step ? GM.primarySolid : GM.stepInactive)
                        .frame(width: 14, height: 2)
                        .padding(.top, side / 2 - 1)
                }
            }
        }
    }
}

/// Primary CTA: 54pt, radius 16, brand gradient, pressed scale, exact
/// disabled tint; optional trailing content (the design's arrows).
struct GradientButton: View {
    let title: String
    var enabled: Bool = true
    var busy: Bool = false
    var trailing: AnyView? = nil
    let action: () -> Void

    var body: some View {
        Button {
            guard enabled, !busy else { return }
            action()
        } label: {
            HStack(spacing: 10) {
                if busy {
                    ProgressView().tint(enabled ? .white : GM.textSecondary)
                } else {
                    Text(title)
                        .font(.body.weight(.semibold))
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .foregroundColor(enabled ? .white : GM.textSecondary)
                    if enabled { trailing }
                    else { trailing?.opacity(0.5) }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 54)
            .background {
                RoundedRectangle(cornerRadius: GMShape.button, style: .continuous)
                    .fill(enabled ? GM.primarySolid : GM.softFill)
            }
        }
        .buttonStyle(PressScaleStyle())
        .disabled(!enabled || busy)
    }
}

struct PressScaleStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        PressScaleLabel(label: configuration.label, isPressed: configuration.isPressed)
    }
}

/// The pressed scale, read through a View so it can see Reduce Motion
/// (round8-spec §1.10: no scale under Reduce Motion — the press then shows as
/// a slight dim instead, which is not motion).
private struct PressScaleLabel: View {
    let label: ButtonStyleConfiguration.Label
    let isPressed: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        label
            .scaleEffect(isPressed && !reduceMotion ? 0.97 : 1)
            .opacity(isPressed && reduceMotion ? 0.85 : 1)
            .animation(GMMotion.gated(.easeOut(duration: 0.12), reduced: reduceMotion), value: isPressed)
    }
}

struct OutlineActionButton: View {
    let title: String
    var trailing: AnyView? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .foregroundColor(GM.textPrimary)
                trailing
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 54)
            .background(
                RoundedRectangle(cornerRadius: GMShape.button, style: .continuous)
                    .strokeBorder(GM.border, lineWidth: 1.5)
            )
        }
    }
}

/// The design's field: "Label *" handled by callers; 54pt field, radius 14,
/// soft fill, focus ring, error stroke, optional leading icon.
struct AppTextField: View {
    var title: String? = nil
    @Binding var value: String
    var error: String? = nil
    var hint: String? = nil
    var isPassword: Bool = false
    var keyboardType: UIKeyboardType = .default
    var leadingIcon: String? = nil
    // Step-terminal fields pass true: return closes the keyboard instead of
    // jumping focus. Without it return only cycles focus between the step's
    // fields, the keyboard stays up, and the fixed wizard Next bar sits
    // hidden underneath (CI walk: 'missing: field yourname').
    var dismissOnReturn: Bool = false
    // MARK: Round 8 — B (password managers)
    /// What the field holds, so iCloud Keychain / 1Password offer to fill and
    /// save GrapMail credentials: `.username`, `.password`, `.newPassword`,
    /// `.oneTimeCode`. nil (the default) sets nothing, exactly as before.
    /// Applied to BOTH branches — the hidden SecureField and the revealed
    /// TextField — so the eye toggle never changes what the system thinks the
    /// field is.
    var contentType: UITextContentType? = nil
    /// Focus the field a moment after it appears (after a reveal animation):
    /// welcome-back's password field, spec §2.3 rung 3.
    var focusOnAppear: Bool = false

    @State private var visible = false // passwords start hidden, like the design's dots
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // The title was accepted and silently dropped — sign-in field
            // labels rendered nowhere. Show it like Android does.
            if let title {
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 10) {
            if let leadingIcon {
                Image(systemName: leadingIcon)
                    .gmFont(16, maxSize: 28)
                    .foregroundColor(GM.textTertiary)
            }
            Group {
                if isPassword && !visible {
                    SecureField(hint ?? "", text: $value)
                        .font(.body)
                        .textContentType(contentType)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } else {
                    TextField(hint ?? "", text: $value)
                        .font(.body)
                        .keyboardType(keyboardType)
                        .textContentType(contentType)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onSubmit { if dismissOnReturn { focused = false } }
                }
            }
            .focused($focused)
            // Round 8 — B: a new-password field tells the password manager the
            // server's rules, so a generated password is never one the server
            // then refuses. A no-op for every other field.
            .background {
                if contentType == .newPassword { GMPasswordRulesHook().frame(width: 1, height: 1) }
            }
            .accessibilityLabel(title ?? hint ?? "")
            .onAppear {
                guard focusOnAppear else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { focused = true }
            }
            if isPassword {
                Button { visible.toggle() } label: {
                    Image(systemName: visible ? "eye.slash" : "eye")
                        .gmFont(14, maxSize: 26)
                        .foregroundColor(GM.textSecondary)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(visible ? Text("Hide password") : Text("Show password"))
            }
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 54)
            .background(RoundedRectangle(cornerRadius: GMShape.input, style: .continuous).fill(GM.inputFill))
            .overlay(
                RoundedRectangle(cornerRadius: GMShape.input, style: .continuous)
                    .strokeBorder(
                        error != nil ? GM.error : (focused ? GM.focusRing : GM.border),
                        lineWidth: error != nil || focused ? 1.5 : 1
                    )
            )
            .animation(.easeOut(duration: 0.15), value: focused)
            if let error {
                Text(error)
                    .gmFont(.caption)
                    .foregroundColor(GM.error)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: Round 8 — B (password managers)

/// A visually hidden username field that carries the full address.
///
/// Password managers save and fill a credential as a username + password
/// pair. A screen whose only fields are a new password (the sign-up password
/// step, the reset screen) or a password for an account already on screen
/// (welcome-back) gives them no username: they either stay quiet or save the
/// password under a blank name. This field supplies it. It is 1 pt and nearly
/// transparent rather than hidden or zero-sized, because AutoFill ignores views
/// that are hidden outright; it takes no touches and is invisible to VoiceOver.
struct CredentialUsernameField: View {
    let username: String

    var body: some View {
        TextField("", text: .constant(username))
            .textContentType(.username)
            .keyboardType(.emailAddress)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .frame(width: 1, height: 1)
            .opacity(0.01)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// The server's password rules (`passwordProblem`, backend
/// lib/password-policy.ts): at least 8 characters, a letter, a number and a
/// symbol — no upper/lower-case rule. Written in Apple's password-rules
/// language so iCloud Keychain and 1Password generate a password the server
/// accepts.
enum GMPasswordRules {
    static let descriptor = "minlength: 8; required: lower, upper; required: digit; required: special; allowed: ascii-printable;"
    static var rules: UITextInputPasswordRules { UITextInputPasswordRules(descriptor: descriptor) }
}

/// SwiftUI has no `passwordRules` modifier, so this 1 pt, touch-transparent
/// helper sits behind a new-password field and sets the rules on the
/// UITextField SwiftUI draws it with. It only ever touches fields whose content
/// type is `.newPassword`; if it finds none, nothing happens and the field
/// works exactly as before (the rules are a hint, never a gate).
struct GMPasswordRulesHook: UIViewRepresentable {
    func makeUIView(context: Context) -> HookView { HookView() }

    func updateUIView(_ view: HookView, context: Context) {
        // After this layout pass: the eye toggle swaps the SecureField for a
        // TextField, and the new field exists only once the pass is done.
        DispatchQueue.main.async { view.apply() }
    }

    final class HookView: UIView {
        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            isAccessibilityElement = false
            backgroundColor = .clear
        }

        required init?(coder: NSCoder) { return nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            DispatchQueue.main.async { [weak self] in self?.apply() }
        }

        /// Look a few levels up for the container that also holds the field.
        func apply() {
            var ancestor = superview
            for _ in 0..<4 {
                guard let current = ancestor else { return }
                if Self.stamp(current) { return }
                ancestor = current.superview
            }
        }

        private static func stamp(_ root: UIView) -> Bool {
            var found = false
            var stack: [UIView] = [root]
            var visited = 0
            while let view = stack.popLast(), visited < 400 {
                visited += 1
                if let field = view as? UITextField, field.textContentType == .newPassword {
                    if field.passwordRules == nil { field.passwordRules = GMPasswordRules.rules }
                    found = true
                }
                stack.append(contentsOf: view.subviews)
            }
            return found
        }
    }
}

/// The red alert row under a field: ⚠ + message, exactly as designed.
struct FieldErrorRow: View {
    let text: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "exclamationmark.circle.fill")
                .gmFont(13, maxSize: 24)
                .foregroundColor(GM.error)
            Text(text)
                .gmFont(.caption)
                .foregroundColor(GM.error)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Wizard chrome — back + centered logo, CREATE ACCOUNT eyebrow, title,
/// subtitle, stepper, scrollable body, action, "Already have an account?".
struct WizardScaffold<Body: View, Bottom: View>: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    let step: Int
    let title: String
    let subtitle: Text
    var onBack: (() -> Void)? = nil
    var onSignIn: (() -> Void)? = nil
    @ViewBuilder let bodyContent: () -> Body
    @ViewBuilder let bottom: () -> Bottom

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
            ZStack {
                if let onBack {
                    HStack {
                        Button(action: onBack) {
                            Image(systemName: "chevron.left")
                                .gmFont(17, .semibold, maxSize: 28)
                                .foregroundColor(GM.textPrimary)
                                .flipsForRightToLeftLayoutDirection(true)
                                // A 44 pt target, and a spoken name: a bare
                                // chevron is read as an image, not as Back.
                                .frame(minWidth: 44, minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text("Back"))
                        Spacer()
                    }
                    .padding(.leading, 0)
                }
                Wordmark()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)

            VStack(alignment: .leading, spacing: 0) {
                (textSize.isAccessibilitySize || verticalSizeClass == .compact ? Text("Step \(step) of 5") : Text("CREATE ACCOUNT"))
                    .gmFont(12, .bold)
                    .kerning(1.6)
                    .foregroundColor(GM.textTertiary)
                    .padding(.top, 10)
                Text(title)
                    .gmFont(22, .bold)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)
                subtitle
                    .gmFont(14)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)
                if !textSize.isAccessibilitySize && verticalSizeClass != .compact {
                    StepProgress(step: step).padding(.top, 22)
                }
                VStack(alignment: .leading, spacing: 0) {
                    bodyContent()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 22)
                bottom()
                    .padding(.top, 14)
                // Side by side; stacked at the accessibility sizes, where the
                // question and the link no longer fit on one line.
                GMAdaptiveStack(spacing: 0, horizontalAlignment: .center) {
                    Text("Already have an account?  ")
                        .gmFont(.caption)
                        .foregroundColor(GM.textSecondary)
                        .multilineTextAlignment(.center)
                    Button(action: onSignIn ?? {}) {
                        Text("Sign In")
                            .gmFont(.captionMedium)
                            .foregroundColor(GM.primaryText)
                            .frame(minHeight: textSize.isAccessibilitySize ? 44 : nil)
                    }
                    .buttonStyle(.plain)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 10)
                .padding(.bottom, 16)
            }
            .padding(.horizontal, 24)
        }
            .centeredForm()
        }
        .background(GM.bg.ignoresSafeArea())
    }
}

/// Soft info banner.
struct InfoBanner: View {
    let text: String

    var body: some View {
        Text(text)
            .gmFont(.caption)
            .foregroundColor(GM.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: GMShape.small + 2, style: .continuous).fill(GM.softFill))
    }
}
