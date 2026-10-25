import SwiftUI

// The GrapMail mark — rounded square, brand gradient, white envelope glyph.
struct GrapMailMark: View {
    var size: CGFloat = 44

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
                .fill(Color.brandGradient)
            EnvelopeGlyph()
                .fill(Color.white)
                .frame(width: size * 0.56, height: size * 0.4)
            EnvelopeFlap()
                .stroke(GM.primaryBottom, lineWidth: size * 0.045)
                .frame(width: size * 0.56, height: size * 0.4)
        }
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
    var body: some View {
        HStack(spacing: 10) {
            GrapMailMark(size: 40)
            Text("GrapMail")
                .font(.system(size: 20, weight: .bold))
                .foregroundColor(GM.textPrimary)
        }
    }
}

/// The design's numbered-circle stepper: five circles with labels beneath
/// (Name, Details, Address, Password, Terms), completed and current filled.
struct StepProgress: View {
    let step: Int

    private let labels = ["Name", "Details", "Address", "Password", "Terms"]

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(0..<5, id: \.self) { i in
                let n = i + 1
                let active = n <= step
                VStack(spacing: 5) {
                    Circle()
                        .fill(active ? GM.primarySolid : GM.stepInactive)
                        .frame(width: 30, height: 30)
                        .overlay(
                            Text("\(n)")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundColor(active ? .white : GM.textSecondary)
                        )
                    Text(labels[i])
                        .font(.system(size: 12, weight: n == step ? .semibold : .regular))
                        .foregroundColor(n == step ? GM.textPrimary : GM.textTertiary)
                }
                if n < 5 {
                    Rectangle()
                        .fill(n < step ? GM.primarySolid : GM.stepInactive)
                        .frame(height: 2)
                        .padding(.horizontal, 5)
                        .padding(.top, 14)
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
            ZStack {
                RoundedRectangle(cornerRadius: GMShape.button, style: .continuous)
                    .fill(enabled ? AnyShapeStyle(Color.brandGradient) : AnyShapeStyle(GM.buttonDisabled))
                HStack(spacing: 10) {
                    if busy {
                        ProgressView().tint(.white)
                    } else {
                        Text(title)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.white)
                        trailing
                    }
                }
            }
            .frame(height: 54)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(PressScaleStyle())
        .disabled(!enabled || busy)
    }
}

struct PressScaleStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
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
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                trailing
            }
            .frame(height: 54)
            .frame(maxWidth: .infinity)
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

    @State private var visible = true
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 10) {
            if let leadingIcon {
                Image(systemName: leadingIcon)
                    .font(.system(size: 16))
                    .foregroundColor(GM.textTertiary)
            }
            Group {
                if isPassword && !visible {
                    SecureField(hint ?? "", text: $value)
                } else {
                    TextField(hint ?? "", text: $value)
                        .keyboardType(keyboardType)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
            }
            if isPassword {
                Button { visible.toggle() } label: {
                    Image(systemName: visible ? "eye.slash" : "eye")
                        .font(.system(size: 14))
                        .foregroundColor(GM.textTertiary)
                }
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 54)
        .background(RoundedRectangle(cornerRadius: GMShape.input, style: .continuous).fill(GM.inputFill))
        .overlay(
            RoundedRectangle(cornerRadius: GMShape.input, style: .continuous)
                .strokeBorder(
                    error != nil ? GM.error : (focused ? GM.primarySolid : GM.border),
                    lineWidth: error != nil || focused ? 1.5 : 1
                )
        )
        .animation(.easeOut(duration: 0.15), value: focused)
    }
}

/// The red alert row under a field: ⚠ + message, exactly as designed.
struct FieldErrorRow: View {
    let text: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 13))
                .foregroundColor(GM.error)
            Text(text)
                .font(GMFont.caption)
                .foregroundColor(GM.error)
        }
    }
}

/// Wizard chrome — back + centered logo, CREATE ACCOUNT eyebrow, title,
/// subtitle, stepper, scrollable body, action, "Already have an account?".
struct WizardScaffold<Body: View, Bottom: View>: View {
    let step: Int
    let title: String
    let subtitle: Text
    var onBack: (() -> Void)? = nil
    @ViewBuilder let bodyContent: () -> Body
    @ViewBuilder let bottom: () -> Bottom

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                if let onBack {
                    HStack {
                        Button(action: onBack) {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundColor(GM.textPrimary)
                        }
                        .buttonStyle(.plain)
                        Spacer()
                    }
                    .padding(.leading, 12)
                }
                Wordmark()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)

            VStack(alignment: .leading, spacing: 0) {
                Text("CREATE ACCOUNT")
                    .font(.system(size: 12, weight: .bold))
                    .kerning(1.6)
                    .foregroundColor(GM.textTertiary)
                    .padding(.top, 10)
                Text(title)
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(GM.textPrimary)
                    .padding(.top, 8)
                subtitle
                    .font(.system(size: 14))
                    .foregroundColor(GM.textSecondary)
                    .padding(.top, 8)
                StepProgress(step: step)
                    .padding(.top, 22)
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        bodyContent()
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.top, 22)
                bottom()
                    .padding(.top, 14)
                HStack(spacing: 0) {
                    Text("Already have an account?  ")
                        .font(GMFont.caption)
                        .foregroundColor(GM.textSecondary)
                    Text("Sign In")
                        .font(GMFont.captionMedium)
                        .foregroundColor(GM.primarySolid)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 10)
                .padding(.bottom, 16)
            }
            .padding(.horizontal, 24)
        }
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .background(GM.bg.ignoresSafeArea())
    }
}

/// Soft info banner.
struct InfoBanner: View {
    let text: String

    var body: some View {
        Text(text)
            .font(GMFont.caption)
            .foregroundColor(GM.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: GMShape.small + 2, style: .continuous).fill(GM.softFill))
    }
}
