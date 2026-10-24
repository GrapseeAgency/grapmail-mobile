import SwiftUI

/// Human verification — founder design verbatim: shield + badge illustration,
/// ACCOUNT PROTECTION, "Just one more step!", the Solve-the-puzzle card,
/// OR divider, Get a new challenge, Skip for now + caption.
struct HumanVerifyView: View {
    @ObservedObject var model: SignUpModel
    let onDone: () -> Void

    @State private var verified = false
    @State private var challenge = 0

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                HStack {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(GM.textPrimary)
                        .onTapGesture { onDone() }
                    Spacer()
                }
                .padding(.leading, 12)
                Wordmark()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)

            ScrollView {
                VStack(spacing: 0) {
                    Image("ill_verify")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 30)

                    Text("ACCOUNT PROTECTION")
                        .font(.system(size: 12, weight: .bold))
                        .kerning(1.6)
                        .foregroundColor(GM.primarySolid)
                        .padding(.top, 6)
                    Text("Just one more step!")
                        .font(.system(size: 26, weight: .bold))
                        .foregroundColor(GM.textPrimary)
                        .padding(.top, 8)
                    Text("To keep GrapMail safe and spam-free, we need to make sure you're a real person. Complete the challenge below to continue.")
                        .font(.system(size: 14))
                        .foregroundColor(GM.textSecondary)
                        .multilineTextAlignment(.center)
                        .lineSpacing(3)
                        .padding(.top, 8)

                    // Solve the puzzle card
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(alignment: .top, spacing: 12) {
                            Circle()
                                .fill(GM.primarySolid)
                                .frame(width: 40, height: 40)
                                .overlay(Text("🧩").font(.system(size: 17)))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Solve the puzzle")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundColor(GM.textPrimary)
                                Text("Drag the puzzle piece to the correct position to complete the image.")
                                    .font(GMFont.caption)
                                    .foregroundColor(GM.textSecondary)
                                    .lineSpacing(2)
                            }
                        }
                        PuzzleSlider(verified: $verified) {
                            model.markHumanVerified()
                        }
                        .id(challenge)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(GM.card))
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(GM.border))
                    .padding(.top, 22)

                    // OR divider
                    HStack(spacing: 12) {
                        Rectangle().fill(GM.border).frame(height: 1)
                        Text("OR").font(.system(size: 12, weight: .medium)).foregroundColor(GM.textTertiary)
                        Rectangle().fill(GM.border).frame(height: 1)
                    }
                    .padding(.top, 20)

                    Button {
                        verified = false
                        challenge += 1
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "arrow.clockwise")
                                .font(.system(size: 14))
                                .foregroundColor(GM.primarySolid)
                            Text("Get a new challenge")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(GM.primarySolid)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .background(RoundedRectangle(cornerRadius: 14).strokeBorder(GM.primarySolid.opacity(0.5)))
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 16)

                    Text("Skip for now")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(GM.primarySolid)
                        .padding(.top, 18)
                        .onTapGesture { onDone() }
                    Text("You can always complete verification later\nfrom your account settings.")
                        .font(.system(size: 12))
                        .foregroundColor(GM.textTertiary)
                        .multilineTextAlignment(.center)
                        .lineSpacing(3)
                        .padding(.top, 6)
                        .padding(.bottom, 20)
                }
            }
            .padding(.horizontal, 24)
        }
        .background(GM.bg.ignoresSafeArea())
    }
}

// MARK: - Account Created (A) — founder design verbatim

struct AccountCreatedView: View {
    let address: String
    let name: String
    let onContinueOnboarding: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 36)
            Wordmark()

            Image("ill_created_a")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 40)
                .padding(.top, 14)

            Text("ACCOUNT CREATED")
                .font(.system(size: 12, weight: .bold))
                .kerning(1.6)
                .foregroundColor(GM.primarySolid)
                .padding(.top, 8)
            Text("You're all set!")
                .font(.system(size: 28, weight: .bold))
                .foregroundColor(GM.textPrimary)
                .padding(.top, 8)
            Text("Your GrapMail account has been successfully\ncreated and you're now signed in.")
                .font(.system(size: 14))
                .foregroundColor(GM.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.top, 8)

            // Profile card with Active chip
            HStack(spacing: 12) {
                Circle()
                    .fill(GM.primarySolid)
                    .frame(width: 44, height: 44)
                    .overlay(Text(String(name.prefix(1)).uppercased())
                        .font(.system(size: 18, weight: .bold)).foregroundColor(.white))
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).font(.system(size: 15, weight: .semibold)).foregroundColor(GM.textPrimary)
                    Text(address).font(.system(size: 13)).foregroundColor(GM.textSecondary)
                }
                Spacer()
                HStack(spacing: 4) {
                    Image(systemName: "checkmark").font(.system(size: 11, weight: .semibold)).foregroundColor(GM.success)
                    Text("Active").font(.system(size: 12, weight: .medium)).foregroundColor(GM.success)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Capsule().fill(GM.success.opacity(0.12)))
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(GM.card))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(GM.border))
            .padding(.top, 20)

            Text("WHAT'S NEXT?")
                .font(.system(size: 12, weight: .bold))
                .kerning(1.4)
                .foregroundColor(GM.textTertiary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 20)

            nextTile("envelope.fill", Color(0xE3ECFB), Color(0x1A6DF0), "Explore your mailbox", "Check your inbox, send emails, and more.")
            nextTile("person.crop.circle.fill", Color(0xEDE7FD), Color(0x7C5CE0), "Add your contacts", "Stay connected with friends and colleagues.")
            nextTile("gearshape.fill", Color(0xDFF5EA), Color(0x10B981), "Personalize your experience", "Set up your preferences and get the most out of GrapMail.")

            Spacer(minLength: 8)

            GradientButton(title: "Continue to Onboarding", trailing: AnyView(
                Image(systemName: "arrow.right")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
            ), action: onContinueOnboarding)
            Text("We'll take you through a quick setup to get you started.")
                .font(.system(size: 12))
                .foregroundColor(GM.textTertiary)
                .padding(.top, 10)

            HStack(spacing: 7) {
                Circle().fill(GM.primarySolid).frame(width: 7, height: 7)
                ForEach(0..<4, id: \.self) { _ in
                    Circle().fill(GM.stepInactive).frame(width: 7, height: 7)
                }
            }
            .padding(.top, 16)
            .padding(.bottom, 20)
        }
        .padding(.horizontal, 24)
        .background(GM.bg.ignoresSafeArea())
    }

    private func nextTile(_ icon: String, _ tile: Color, _ tint: Color, _ title: String, _ subtitle: String) -> some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(tile)
                .frame(width: 42, height: 42)
                .overlay(Image(systemName: icon).font(.system(size: 17)).foregroundColor(tint))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 14, weight: .semibold)).foregroundColor(GM.textPrimary)
                Text(subtitle).font(.system(size: 12)).foregroundColor(GM.textSecondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.system(size: 12))
                .foregroundColor(GM.textTertiary)
        }
        .padding(.vertical, 10)
    }
}

// MARK: - Account Created (B) — founder design verbatim

struct AccountCreatedAltView: View {
    let address: String
    let name: String
    let onGoToMailbox: () -> Void
    let onContinueOnboarding: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 32)
            HStack {
                Wordmark()
                Spacer()
            }

            Image("ill_created_b")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 44)
                .padding(.top, 12)

            Text("ACCOUNT CREATED")
                .font(.system(size: 12, weight: .bold))
                .kerning(1.6)
                .foregroundColor(GM.primarySolid)
                .padding(.top, 6)
            Text("You're all set!")
                .font(.system(size: 28, weight: .bold))
                .foregroundColor(GM.textPrimary)
                .padding(.top, 8)
            Text("Your GrapMail account has been successfully\ncreated and you're now signed in.")
                .font(.system(size: 14))
                .foregroundColor(GM.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.top, 8)

            // Profile card with copy icon
            HStack(spacing: 12) {
                Circle()
                    .fill(GM.primarySolid)
                    .frame(width: 44, height: 44)
                    .overlay(Text(String(name.prefix(1)).uppercased())
                        .font(.system(size: 18, weight: .bold)).foregroundColor(.white))
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).font(.system(size: 15, weight: .semibold)).foregroundColor(GM.textPrimary)
                    Text(address).font(.system(size: 13)).foregroundColor(GM.textSecondary)
                }
                Spacer()
                Button {
                    UIPasteboard.general.string = address
                } label: {
                    Image(systemName: "doc.on.doc")
                        .font(.system(size: 15))
                        .foregroundColor(GM.textTertiary)
                }
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(GM.card))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(GM.border))
            .padding(.top, 20)

            simpleRow("envelope", "Your secure inbox", "Send, receive and manage your emails.")
            simpleRow("lock", "Built for your privacy", "Your data stays yours.")
            simpleRow("bolt", "Ready for what's next", "Let's get you set up and personalized.")

            Spacer(minLength: 8)

            GradientButton(title: "Go to Mailbox", trailing: AnyView(
                Image(systemName: "arrow.right")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
            ), action: onGoToMailbox)
            OutlineActionButton(title: "Continue to Onboarding", trailing: AnyView(
                Image(systemName: "arrow.right")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(GM.primarySolid)
            ), action: onContinueOnboarding)
            .padding(.top, 12)
            .padding(.bottom, 28)
        }
        .padding(.horizontal, 24)
        .background(GM.bg.ignoresSafeArea())
    }

    private func simpleRow(_ icon: String, _ title: String, _ subtitle: String) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 17))
                .foregroundColor(GM.textSecondary)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 14, weight: .semibold)).foregroundColor(GM.textPrimary)
                Text(subtitle).font(.system(size: 12)).foregroundColor(GM.textSecondary)
            }
            Spacer()
        }
        .padding(.vertical, 9)
    }
}
