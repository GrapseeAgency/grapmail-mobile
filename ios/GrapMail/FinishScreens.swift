import SwiftUI
import WebKit

/// Human verification — REAL challenge, no bypass (2026-11-17 overhaul).
///
/// This replaces the old puzzle-slider card and the "Skip for now" exit. The
/// user solves a genuine reCAPTCHA challenge hosted on the web checkpoint page
/// (kit file web/04_verify-human-page.tsx → https://<your-web-app>/verify/human)
/// inside a WKWebView. A server-confirmed verdict is the only path forward:
/// "Continue" stays disabled and there is no skip — same semantics as the web app.
///
/// Integration (see PATCHES-ios.md):
///  - Delete the old `HumanVerifyView` struct in FinishScreens.swift (everything
///    from `struct HumanVerifyView` to just before `struct AccountCreatedView`)
///    and either keep this file or paste its contents in its place.
///  - Add to SignUpModel: `humanVerified` (server-confirmed), `humanVerifying`,
///    `humanVerifyError` and `submitHumanVerification(token:)`.
///  - Keep the RootView.swift call site unchanged — the initializer signature
///    matches the old view.
///
/// Bridge contract (must match the checkpoint page):
///   window.webkit.messageHandlers.grapmailVerify.postMessage(token)

struct HumanVerifyView: View {
    @ObservedObject var model: SignUpModel
    var onBack: (() -> Void)? = nil
    let onDone: () -> Void

    /// Bumped to force a fresh challenge (reloads the checkpoint page).
    @State private var reloadKey = 0
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @ScaledMetric(relativeTo: .body) private var lockDisc: CGFloat = 40

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                HStack {
                    if let onBack {
                        Button(action: onBack) {
                            Image(systemName: "chevron.left").flipsForRightToLeftLayoutDirection(true)
                                .gmFont(17, .semibold, maxSize: 28)
                                .foregroundColor(GM.textPrimary)
                        }
                        .buttonStyle(.plain)
                    }
                    Spacer()
                }
                .padding(.leading, 12)
                Wordmark()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)

            ScrollView {
                VStack(spacing: 0) {
                    // The art gives way in a landscape phone so the challenge
                    // starts on screen.
                    if verticalSizeClass != .compact {
                        Art.fill("verify")
                            .padding(.horizontal, 30)
                    }

                    Text("ACCOUNT PROTECTION")
                        .gmFont(12, .bold)
                        .kerning(1.6)
                        .foregroundColor(GM.primarySolid)
                        .multilineTextAlignment(.center)
                        .padding(.top, 6)
                    Text("Just one more step!")
                        .gmFont(26, .bold, maxSize: 44)
                        .foregroundColor(GM.textPrimary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)
                    Text("To keep GrapMail safe and spam-free, we need to make sure you're a real person. Complete the challenge below to continue.")
                        .gmFont(14)
                        .foregroundColor(GM.textSecondary)
                        .multilineTextAlignment(.center)
                        .lineSpacing(3)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)

                    securityCheckCard
                        .padding(.top, 22)

                    if !model.state.humanVerified {
                        Button {
                            reloadKey += 1
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "arrow.clockwise")
                                    .gmFont(14)
                                    .foregroundColor(GM.primarySolid)
                                Text("Get a new challenge")
                                    .gmFont(14, .medium)
                                    .foregroundColor(GM.primarySolid)
                                    .multilineTextAlignment(.center)
                            }
                            .padding(.vertical, 8)
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 50)
                            .background(RoundedRectangle(cornerRadius: 14).strokeBorder(GM.primarySolid.opacity(0.5)))
                        }
                        .buttonStyle(.plain)
                        .disabled(model.state.humanVerifying)
                        .padding(.top, 16)
                    }

                    // The ONLY way forward — enabled after the server-confirmed
                    // verdict. (The old "Skip for now" is deleted.)
                    Button(action: onDone) {
                        Text("Continue")
                            .gmFont(15, .semibold)
                            .foregroundColor(.white)
                            .multilineTextAlignment(.center)
                            .padding(.vertical, 8)
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 50)
                            .background(RoundedRectangle(cornerRadius: 14).fill(GM.primarySolid))
                    }
                    .buttonStyle(.plain)
                    .disabled(!model.state.humanVerified)
                    .opacity(model.state.humanVerified ? 1 : 0.45)
                    .padding(.top, 18)

                    // Branding disclosure (mobile apps: no badge required, text optional).
                    // TODO(branding): linkify "Privacy Policy" / "Terms of Service" per the guidelines.
                    Text("This app is protected by reCAPTCHA and the Google Privacy Policy and Terms of Service apply.")
                        .gmFont(11)
                        .foregroundColor(GM.textTertiary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 10)
                        .padding(.horizontal, 12)
                        .padding(.bottom, 20)
                }
                .gmMaxWidth(GMLayout.form)
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }

    private var securityCheckCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                Circle()
                    .fill(GM.primarySolid)
                    .frame(width: min(lockDisc, 60), height: min(lockDisc, 60))
                    .overlay(Text("🔒").gmFont(17, maxSize: 26))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Security check")
                        .gmFont(15, .semibold)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("Complete the challenge to verify you're a real person.")
                        .gmFont(.caption)
                        .foregroundColor(GM.textSecondary)
                        .lineSpacing(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            if model.state.humanVerified {
                // Server-confirmed verdict only. The WebView is no longer needed.
                HStack(spacing: 10) {
                    Image(systemName: "checkmark.seal.fill")
                        .gmFont(17)
                        .foregroundColor(GM.primarySolid)
                    Text("Verified — thank you!")
                        .gmFont(15, .semibold)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 22)
            } else {
                // Height is chosen so the reCAPTCHA image-challenge overlay fits.
                // The page lays out to the card's width, so it works from a
                // 320 pt Split View column to a full iPad form sheet.
                VerifyWebView(url: VerifyCheckpoint.url, reloadKey: reloadKey) { token in
                    model.submitHumanVerification(token: token)
                }
                .frame(height: 420)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(GM.border))

                if model.state.humanVerifying {
                    HStack(spacing: 8) {
                        ProgressView().scaleEffect(0.8)
                        Text("Checking…")
                            .gmFont(13)
                            .foregroundColor(GM.textSecondary)
                    }
                }
                if let message = model.state.humanVerifyError {
                    Text(message)
                        .gmFont(12)
                        .foregroundColor(.red) // TODO(theme): swap for a palette error color if one exists
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
        .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
    }
}

/// Target of the checkpoint page (must be HTTPS — required by reCAPTCHA).
enum VerifyCheckpoint {
    // TODO(deploy): your deployed web app origin. Same page the Android app loads.
    static let url = URL(string: "https://grapsee.com/verify/human?embed=ios")!
}

/// WKWebView host for the checkpoint page + the `grapmailVerify` JS bridge.
private struct VerifyWebView: UIViewRepresentable {
    let url: URL
    var reloadKey: Int
    let onToken: (String) -> Void

    final class Coordinator: NSObject, WKScriptMessageHandler {
        let onToken: (String) -> Void
        var handledReloadKey = -1

        init(onToken: @escaping (String) -> Void) {
            self.onToken = onToken
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == "grapmailVerify" else { return }
            if let token = message.body as? String, !token.isEmpty {
                onToken(token)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(onToken: onToken) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.userContentController.add(context.coordinator, name: "grapmailVerify")
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.bounces = false
        context.coordinator.handledReloadKey = reloadKey
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        if context.coordinator.handledReloadKey != reloadKey {
            context.coordinator.handledReloadKey = reloadKey
            webView.load(URLRequest(url: url))
        }
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "grapmailVerify")
        webView.stopLoading()
    }
}

/// The profile card both "account created" screens show: avatar, name and
/// address, and a trailing accessory (Active chip or copy button). At the
/// accessibility text sizes the accessory moves under the text so the address
/// keeps the full width.
private struct CreatedProfileCard<Accessory: View>: View {
    let name: String
    let address: String
    @ViewBuilder let accessory: () -> Accessory
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .body) private var avatar: CGFloat = 44

    var body: some View {
        Group {
            if dts.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 10) {
                    avatarDisc
                    nameBlock
                    accessory()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(spacing: 12) {
                    avatarDisc
                    nameBlock
                    Spacer()
                    accessory()
                }
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
        .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
    }

    private var avatarDisc: some View {
        Circle()
            .fill(GM.primarySolid)
            .frame(width: min(avatar, 64), height: min(avatar, 64))
            .overlay(Text(String(name.prefix(1)).uppercased())
                .gmFont(18, .bold, maxSize: 28).foregroundColor(.white))
    }

    private var nameBlock: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(name).gmFont(15, .semibold).foregroundColor(GM.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            Text(address).gmFont(13).foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - Account Created (A) — founder design verbatim

struct AccountCreatedView: View {
    let address: String
    let name: String
    let onContinueOnboarding: () -> Void
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @ScaledMetric(relativeTo: .body) private var tileSize: CGFloat = 42

    var body: some View {
        // Scrollable: on the smallest phones the card + three tiles + CTA
        // exceed the viewport and the button used to clip off-screen.
        GeometryReader { viewport in
            ScrollView {
                VStack(spacing: 0) {
            Spacer(minLength: verticalSizeClass == .compact ? 16 : 36)
            Wordmark()

            if verticalSizeClass != .compact {
                Art.fill("created_a")
                    .padding(.horizontal, 40)
                    .padding(.top, 14)
            }

            Text("ACCOUNT CREATED")
                .gmFont(12, .bold)
                .kerning(1.6)
                .foregroundColor(GM.primarySolid)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            Text("You're all set!")
                .gmFont(28, .bold, maxSize: 44)
                .foregroundColor(GM.textPrimary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
            Text("Your GrapMail account has been successfully\ncreated and you're now signed in.")
                .gmFont(14)
                .foregroundColor(GM.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            // Profile card with Active chip
            CreatedProfileCard(name: name, address: address) {
                HStack(spacing: 4) {
                    Image(systemName: "checkmark").gmFont(11, .semibold).foregroundColor(GM.success)
                    Text("Active").gmFont(12, .medium).foregroundColor(GM.success)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Capsule().fill(GM.success.opacity(0.12)))
            }
            .padding(.top, 20)

            Text("WHAT'S NEXT?")
                .gmFont(12, .bold)
                .kerning(1.4)
                .foregroundColor(GM.textTertiary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 20)

            nextTile("envelope.fill", Color(0xE3ECFB), Color(0x1A6DF0), "Explore your mailbox", "Check your inbox, send emails, and more.")
            nextTile("person.crop.circle.fill", Color(0xEDE7FD), Color(0x7C5CE0), "Add your contacts", "Stay connected with friends and colleagues.")
            nextTile("gearshape.fill", Color(0xDFF5EA), Color(0x10B981), "Personalize your experience", "Set up your preferences and get the most out of GrapMail.")

            Spacer(minLength: 8)

            GradientButton(title: String(localized: "Continue to Onboarding", comment: "Button title (account created screen)"), trailing: AnyView(
                Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                    .gmFont(15, .semibold, maxSize: 26)
                    .foregroundColor(.white)
            ), action: onContinueOnboarding)
            Text("We'll take you through a quick setup to get you started.")
                .gmFont(12)
                .foregroundColor(GM.textTertiary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 10)

            HStack(spacing: 7) {
                Circle().fill(GM.primarySolid).frame(width: 7, height: 7)
                ForEach(0..<4, id: \.self) { _ in
                    Circle().fill(GM.stepInactive).frame(width: 7, height: 7)
                }
            }
            .accessibilityHidden(true)
            .padding(.top, 16)
            .padding(.bottom, 20)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity)
                .frame(minHeight: viewport.size.height)
            }
        }
        .gmMaxWidth(GMLayout.form)
        .background(GM.bg.ignoresSafeArea())
    }

    private func nextTile(_ icon: String, _ tile: Color, _ tint: Color, _ title: LocalizedStringKey, _ subtitle: LocalizedStringKey) -> some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(tile)
                .frame(width: min(tileSize, 60), height: min(tileSize, 60))
                .overlay(Image(systemName: icon).gmFont(17, maxSize: 30).foregroundColor(tint))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).gmFont(14, .semibold).foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(subtitle).gmFont(12).foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            // No chevron: this tile has no destination. The screen's one action
            // is "Continue to Onboarding", and a chevron promises a tap that
            // does nothing. Removing the affordance beats faking one.
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
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @ScaledMetric(relativeTo: .body) private var rowIcon: CGFloat = 22

    var body: some View {
        GeometryReader { viewport in
            ScrollView {
                VStack(spacing: 0) {
            Spacer(minLength: verticalSizeClass == .compact ? 16 : 32)
            HStack {
                Wordmark()
                Spacer()
            }

            if verticalSizeClass != .compact {
                Art.fill("created_b")
                    .padding(.horizontal, 44)
                    .padding(.top, 12)
            }

            Text("ACCOUNT CREATED")
                .gmFont(12, .bold)
                .kerning(1.6)
                .foregroundColor(GM.primarySolid)
                .multilineTextAlignment(.center)
                .padding(.top, 6)
            Text("You're all set!")
                .gmFont(28, .bold, maxSize: 44)
                .foregroundColor(GM.textPrimary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
            Text("Your GrapMail account has been successfully\ncreated and you're now signed in.")
                .gmFont(14)
                .foregroundColor(GM.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            // Profile card with copy icon
            CreatedProfileCard(name: name, address: address) {
                Button {
                    UIPasteboard.general.string = address
                } label: {
                    Image(systemName: "doc.on.doc")
                        .gmFont(15, maxSize: 26)
                        .foregroundColor(GM.textTertiary)
                }
            }
            .padding(.top, 20)

            simpleRow("envelope", "Your secure inbox", "Send, receive and manage your emails.")
            simpleRow("lock", "Built for your privacy", "Your data stays yours.")
            simpleRow("bolt", "Ready for what's next", "Let's get you set up and personalized.")

            Spacer(minLength: 8)

            GradientButton(title: String(localized: "Go to Mailbox", comment: "Button title (account created screen)"), trailing: AnyView(
                Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                    .gmFont(15, .semibold, maxSize: 26)
                    .foregroundColor(.white)
            ), action: onGoToMailbox)
            OutlineActionButton(title: String(localized: "Continue to Onboarding", comment: "Button title (account created screen)"), trailing: AnyView(
                Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                    .gmFont(15, .semibold, maxSize: 26)
                    .foregroundColor(GM.primarySolid)
            ), action: onContinueOnboarding)
            .padding(.top, 12)
            .padding(.bottom, 28)
            }
            .padding(.horizontal, 24)
            .frame(maxWidth: .infinity)
            .frame(minHeight: viewport.size.height)
        }
        }
        .gmMaxWidth(GMLayout.form)
        .background(GM.bg.ignoresSafeArea())
    }

    private func simpleRow(_ icon: String, _ title: LocalizedStringKey, _ subtitle: LocalizedStringKey) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .gmFont(17)
                .foregroundColor(GM.textSecondary)
                .frame(width: rowIcon)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).gmFont(14, .semibold).foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(subtitle).gmFont(12).foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
        .padding(.vertical, 9)
    }
}
