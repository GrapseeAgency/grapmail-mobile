import SwiftUI

// The GrapMail mark — rounded square, brand gradient, white envelope glyph
// with the flap. Drawn vector so it is crisp at every density.
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

struct Eyebrow: View {
    let text: String
    var color: Color?

    init(_ text: String, color: Color? = nil) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text.uppercased())
            .font(GMFont.micro)
            .kerning(1.4)
            .foregroundColor(color ?? GM.primarySolid)
    }
}

/// Primary CTA: 54pt, radius 16, brand gradient, pressed scale,
/// exact disabled tint from the terms screen.
struct GradientButton: View {
    let title: String
    var enabled: Bool = true
    var busy: Bool = false
    let action: () -> Void

    @State private var pressed = false

    var body: some View {
        Button {
            guard enabled, !busy else { return }
            action()
        } label: {
            ZStack {
                RoundedRectangle(cornerRadius: GMShape.button, style: .continuous)
                    .fill(
                        enabled
                            ? AnyShapeStyle(Color.brandGradient)
                            : AnyShapeStyle(GM.buttonDisabled)
                    )
                if busy {
                    ProgressView().tint(.white)
                } else {
                    Text(title)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.white)
                }
            }
            .frame(height: 54)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(PressScaleStyle(onChange: { pressed = $0 }))
        .scaleEffect(pressed ? 0.97 : 1)
        .animation(.easeOut(duration: 0.12), value: pressed)
        .disabled(!enabled || busy)
        .opacity(enabled || busy ? 1 : 1)
    }
}

struct PressScaleStyle: ButtonStyle {
    var onChange: (Bool) -> Void

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .onChange(of: configuration.isPressed) { newValue in
                onChange(newValue)
            }
    }
}

struct OutlineActionButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(GM.textPrimary)
                .frame(height: 54)
                .frame(maxWidth: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: GMShape.button, style: .continuous)
                        .strokeBorder(GM.border, lineWidth: 1.5)
                )
        }
    }
}

/// The design's filled text field: 54pt content height, radius 14, soft fill,
/// label above, error state with red stroke + note, focus ring in brand blue.
struct AppTextField: View {
    let label: String
    @Binding var value: String
    var error: String? = nil
    var hint: String? = nil
    var isPassword: Bool = false
    var keyboardType: UIKeyboardType = .default
    var trailing: String? = nil

    @State private var visible = true
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(GMFont.captionMedium)
                .foregroundColor(GM.textSecondary)
            HStack {
                if isPassword && !visible {
                    SecureField("", text: $value)
                        .focused($focused)
                        .submitLabel(.done)
                } else {
                    TextField(hint ?? "", text: $value)
                        .focused($focused)
                        .keyboardType(keyboardType)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
                if isPassword {
                    Button {
                        visible.toggle()
                    } label: {
                        Image(systemName: visible ? "eye.slash" : "eye")
                            .font(.system(size: 15))
                            .foregroundColor(GM.textTertiary)
                    }
                } else if let trailing {
                    Text(trailing)
                        .font(GMFont.caption)
                        .foregroundColor(GM.textTertiary)
                }
            }
            .padding(.horizontal, 14)
            .frame(height: 54)
            .background(
                RoundedRectangle(cornerRadius: GMShape.input, style: .continuous)
                    .fill(GM.inputFill)
            )
            .overlay(
                RoundedRectangle(cornerRadius: GMShape.input, style: .continuous)
                    .strokeBorder(
                        error != nil ? GM.error : (focused ? GM.primarySolid : GM.border),
                        lineWidth: error != nil || focused ? 1.5 : 1
                    )
            )
            .animation(.easeOut(duration: 0.15), value: focused)
            .animation(.easeOut(duration: 0.15), value: error == nil)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(label)
            .accessibilityLabel(label)

            if let error {
                Text(error).font(GMFont.caption).foregroundColor(GM.error)
            } else if let hint, value.isEmpty {
                Text(hint).font(GMFont.caption).foregroundColor(GM.textTertiary)
            }
        }
    }
}

/// The 5-segment step indicator with animated fill.
struct StepProgress: View {
    let step: Int
    let total: Int
    var onBack: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                if let onBack {
                    Button {
                        onBack()
                    } label: {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(GM.textSecondary)
                            .frame(width: 36, height: 36)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                Eyebrow("Step \(step) of \(total)", color: GM.textTertiary)
            }
            HStack(spacing: 6) {
                ForEach(0..<total, id: \.self) { i in
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(GM.stepInactive)
                            Capsule()
                                .fill(Color.brandGradient)
                                .frame(width: i < step ? geo.size.width : 0)
                                .animation(.spring(response: 0.4, dampingFraction: 0.85), value: step)
                        }
                    }
                    .frame(height: 4)
                }
            }
        }
    }
}

/// Password requirement row with animated check state.
struct RequirementRow: View {
    let label: String
    let met: Bool

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: met ? "checkmark.circle.fill" : "checkmark")
                .font(.system(size: 15))
                .foregroundColor(met ? GM.success : GM.textTertiary)
            Text(label)
                .font(GMFont.caption)
                .foregroundColor(met ? GM.success : GM.textSecondary)
        }
    }
}

/// Soft info banner.
struct InfoBanner: View {
    let text: String

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Text(text)
                .font(GMFont.caption)
                .foregroundColor(GM.textSecondary)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: GMShape.small + 2, style: .continuous)
                .fill(GM.softFill)
        )
    }
}

/// Gender chip (pill) from step 2.
struct GenderChip: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(GMFont.captionMedium)
                .foregroundColor(selected ? .white : GM.textSecondary)
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .background(
                    Capsule().fill(selected ? GM.primarySolid : GM.softFill)
                )
        }
        .buttonStyle(.plain)
    }
}

/// Wizard scaffold: step chrome + title + body + bottom action.
struct WizardScaffold<Body: View, Bottom: View>: View {
    let step: Int
    let title: String
    let subtitle: String
    var onBack: (() -> Void)? = nil
    @ViewBuilder let body: () -> Body
    @ViewBuilder let bottom: () -> Bottom

    var body: some View {
        VStack(spacing: 0) {
            StepProgress(step: step, total: 5, onBack: onBack)
                .padding(.top, 16)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text.h2(title).foregroundColor(GM.textPrimary)
                        .padding(.top, 20)
                    Text(subtitle)
                        .font(.system(size: 14))
                        .foregroundColor(GM.textSecondary)
                        .padding(.top, 6)
                    body()
                        .padding(.top, 26)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            bottom()
                .padding(.top, 16)
                .padding(.bottom, 20)
        }
        .padding(.horizontal, 24)
        .background(GM.bg.ignoresSafeArea())
        .animation(.easeInOut(duration: 0.32), value: step)
    }
}
