import SwiftUI

/// Human verification — GrapMail's own slide puzzle, replacing Telegram per
/// the founder's direction. Skippable by design; the account works either way.
struct HumanVerifyView: View {
    @ObservedObject var model: SignUpModel
    let onDone: () -> Void

    @State private var verified = false

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 56)
            Eyebrow("Security Check")
            Text.h2("Human Verification").foregroundColor(GM.textPrimary)
                .padding(.top, 10)
            Text("Slide the piece into place to prove you're human")
                .font(.system(size: 14))
                .foregroundColor(GM.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)

            VStack(alignment: .leading, spacing: 14) {
                Text("Quick security check")
                    .font(GMFont.captionMedium)
                    .foregroundColor(GM.textSecondary)
                PuzzleSlider(verified: $verified) {
                    model.markHumanVerified()
                }
                if !verified {
                    Text("Get a new challenge")
                        .font(GMFont.captionMedium)
                        .foregroundColor(GM.primarySolid)
                        .onTapGesture {
                            // New random target rolls on the next appearance of the puzzle.
                            verified = false
                        }
                }
            }
            .padding(20)
            .background(
                RoundedRectangle(cornerRadius: GMShape.card, style: .continuous)
                    .fill(GM.card)
            )
            .padding(.top, 36)

            Spacer()

            if verified {
                Text("Verified — you're all set!")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.success)
                GradientButton(title: "Continue", action: onDone)
                    .padding(.top, 20)
            } else {
                Text("Skip for now →")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(GM.textSecondary)
                    .onTapGesture { onDone() }
            }
            Spacer(minLength: 24)
        }
        .padding(.horizontal, 24)
        .background(GM.bg.ignoresSafeArea())
    }
}

// MARK: - Account Created (A) — "You're all set!" with what's-next

struct AccountCreatedView: View {
    let address: String
    let name: String
    let onContinueOnboarding: () -> Void

    @State private var shown = false

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 44)
            Eyebrow("Account Created")
            Text.h1("You're all set!").foregroundColor(GM.textPrimary)
                .padding(.top, 10)
            Text("Your GrapMail address is ready to use")
                .font(.system(size: 14))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 8)

            ZStack {
                Confetti()
                Image("ill_created_a")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .padding(.horizontal, 44)
                    .frame(height: 180)
            }
            .padding(.top, 26)
            .opacity(shown ? 1 : 0)
            .offset(y: shown ? 0 : 14)

            AddressCard(address: address, name: name)
                .padding(.top, 22)

            VStack(alignment: .leading, spacing: 0) {
                Text("WHAT'S NEXT?")
                    .font(GMFont.micro)
                    .kerning(1.2)
                    .foregroundColor(GM.textTertiary)
                WhatsNextRow(icon: "person.crop.circle", label: "Set your profile photo")
                WhatsNextRow(icon: "checkmark.seal", label: "Verify your email address")
                WhatsNextRow(icon: "tray.full", label: "Explore the mailbox")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 24)

            Spacer()

            HStack(spacing: 8) {
                Circle().fill(GM.primarySolid).frame(width: 7, height: 7)
                ForEach(0..<4, id: \.self) { _ in
                    Circle().fill(GM.stepInactive).frame(width: 7, height: 7)
                }
            }
            GradientButton(title: "Continue to Onboarding", action: onContinueOnboarding)
                .padding(.top, 20)
                .padding(.bottom, 28)
        }
        .padding(.horizontal, 24)
        .background(GM.bg.ignoresSafeArea())
        .onAppear { withAnimation(.easeOut(duration: 0.45)) { shown = true } }
    }
}

// MARK: - Account Created (B) — Go to Mailbox / Continue to Onboarding

struct AccountCreatedAltView: View {
    let address: String
    let name: String
    let onGoToMailbox: () -> Void
    let onContinueOnboarding: () -> Void

    @State private var shown = false

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 48)
            Eyebrow("Account Created")
            Text.h1("You're all set!").foregroundColor(GM.textPrimary)
                .padding(.top, 10)

            ZStack {
                Confetti()
                Image("ill_created_b")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .padding(.horizontal, 36)
                    .frame(height: 190)
            }
            .padding(.top, 24)
            .opacity(shown ? 1 : 0)
            .offset(y: shown ? 0 : 14)

            AddressCard(address: address, name: name)
                .padding(.top, 22)

            Spacer()

            GradientButton(title: "Go to Mailbox", action: onGoToMailbox)
            OutlineActionButton(title: "Continue to Onboarding", action: onContinueOnboarding)
                .padding(.top, 12)
                .padding(.bottom, 28)
        }
        .padding(.horizontal, 24)
        .background(GM.bg.ignoresSafeArea())
        .onAppear { withAnimation(.easeOut(duration: 0.45)) { shown = true } }
    }
}

private struct AddressCard: View {
    let address: String
    let name: String

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.system(size: 15, weight: .semibold)).foregroundColor(GM.textPrimary)
                Text(address).font(.system(size: 14)).foregroundColor(GM.textSecondary)
            }
            Spacer()
            Button {
                UIPasteboard.general.string = address
            } label: {
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 15))
                    .foregroundColor(GM.primarySolid)
            }
            .buttonStyle(.plain)
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: GMShape.button, style: .continuous)
                .fill(GM.card)
        )
    }
}

private struct WhatsNextRow: View {
    let icon: String
    let label: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 19))
                .foregroundColor(GM.primarySolid)
                .frame(width: 24)
            Text(label)
                .font(.system(size: 15))
                .foregroundColor(GM.textPrimary)
            Spacer()
            Image(systemName: "chevron.right")
                .font(.system(size: 13))
                .foregroundColor(GM.textTertiary)
        }
        .padding(.vertical, 12)
    }
}

/// Gentle confetti drift over the illustration — once, ~2.4s, then rests.
struct Confetti: View {
    struct Particle: Identifiable {
        let id: Int
        let x: CGFloat, y: CGFloat, colorIndex: Int, driftRight: Bool
    }

    private let particles: [Particle] = (0..<26).map { i in
        let r = SeededRandom(seed: UInt64(i * 31 + 7))
        return Particle(
            id: i,
            x: CGFloat(r.next()),
            y: CGFloat(r.next()),
            colorIndex: Int(r.next() * 5),
            driftRight: r.next() > 0.5
        )
    }

    private let colors: [Color] = [Color(0x10B981), Color(0xF5A623), Color(0x9E8CFF), GM.primaryTop, Color(0xEF8FB0)]

    @State private var progress: CGFloat = 0

    var body: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate - start
            Canvas { context, size in
                guard t > 0.25, t < 2.65 else { return }
                let p = CGFloat((t - 0.25) / 2.4)
                for particle in particles {
                    let x = size.width * (0.1 + particle.x * 0.8)
                        + (particle.driftRight ? 12 : -12) * p
                    let y = size.height * (0.05 + particle.y * 0.3) + size.height * 0.5 * p
                    let alpha = min(1 - p, 0.9)
                    context.fill(
                        Path(ellipseIn: CGRect(x: x, y: y, width: 5, height: 5)),
                        with: .color(colors[particle.colorIndex].opacity(alpha))
                    )
                }
            }
        }
        .onAppear { start = Date().timeIntervalSinceReferenceDate }
    }

    @State private var start: TimeInterval = 0
}

/// Deterministic random for stable confetti layouts.
struct SeededRandom {
    private var state: UInt64
    init(seed: UInt64) { state = seed &* 0x9E3779B97F4A7C15 &+ 0x2545F4914F6CDD1D }
    mutating func next() -> Double {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return Double(state >> 11) / Double(1 << 53)
    }
}
