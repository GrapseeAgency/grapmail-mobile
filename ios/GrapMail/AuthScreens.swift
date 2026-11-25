import SwiftUI

/// Welcome — founder design: "Welcome to / GrapMail", the tagline, the
/// living-mailbox hero, arrowed CTAs and the legal line.
///
/// The headline and tagline are real, localized, Dynamic Type text; the hero
/// is pure illustration (no headline inside it) so nothing is drawn twice.
///
/// Sizing: the hero is drawn aspect-fit (400 x 440, never cropped) inside a
/// box whose height is a share of the window — about 42 % on a regular phone,
/// 45 % on iPad / tall windows, 34 % in short windows (SE, small Split View /
/// Stage Manager windows) — and never wider than the content column. In very
/// short windows (landscape phone, tiny Stage Manager window) or when large
/// accessibility text would leave it no room, the hero is hidden so the
/// headline and both buttons stay visible without scrolling. Everything sits in
/// a `GMLayout.form` column centred on wide windows and in a ScrollView, so
/// accessibility text sizes can still scroll.
struct WelcomeView: View {
    let onCreateAccount: () -> Void
    let onSignIn: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    /// Hero height for a viewport, or nil when the hero should be hidden.
    private func heroHeight(for size: CGSize) -> CGFloat? {
        let h = size.height
        if verticalSizeClass == .compact || h < 480 { return nil }
        if dynamicTypeSize.isAccessibilitySize && h < 760 { return nil }

        let fraction: CGFloat
        if dynamicTypeSize.isAccessibilitySize {
            fraction = 0.26
        } else if h < 700 {
            fraction = 0.34
        } else if h >= 900 {
            fraction = 0.45
        } else {
            fraction = 0.42
        }
        // The column's usable width (24 pt margins each side) in the hero's
        // 400:440 box, so the art is never wider than the column.
        let columnWidth = max(0, min(size.width, GMLayout.form) - 48)
        let byWidth = columnWidth * 440.0 / 400.0
        let height = min(h * fraction, byWidth, 620)
        return height >= 120 ? height : nil
    }

    var body: some View {
        GeometryReader { viewport in
            let hero = heroHeight(for: viewport.size)
            let short = viewport.size.height < 700 || dynamicTypeSize.isAccessibilitySize
            ScrollView {
                VStack(spacing: 0) {
                    Spacer(minLength: short ? 16 : 32)

                    VStack(spacing: 0) {
                        Text("Welcome to")
                            .gmFont(30, .bold, maxSize: 44)
                            .foregroundColor(GM.textPrimary)
                        Text(verbatim: "GrapMail")
                            .gmFont(30, .bold, maxSize: 44)
                            .foregroundColor(GM.primarySolid)
                    }
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)

                    Text("A faster, safer and more private way to stay connected.")
                        .gmFont(14)
                        .foregroundColor(GM.textSecondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 12)

                    // The living-mailbox animation, authored at 400 x 440. It is
                    // drawn aspect-fit and centred in a box sized from the window
                    // (see heroHeight), never cropped. It is a picture: no touch,
                    // no navigation, no data.
                    if let hero {
                        WelcomeHeroView()
                            .aspectRatio(400.0 / 440.0, contentMode: .fit)
                            .frame(maxWidth: .infinity)
                            .frame(height: hero)
                            .padding(.top, short ? 12 : 20)
                            .accessibilityHidden(true)
                    }

                    Spacer(minLength: 24)

                    GradientButton(title: String(localized: "Create Account", comment: "Welcome screen primary button"), trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .gmFont(15, .semibold, maxSize: 26)
                            .foregroundColor(.white)
                            .flipsForRightToLeftLayoutDirection(true)
                    ), action: onCreateAccount)
                    OutlineActionButton(title: String(localized: "Sign In", comment: "Welcome screen secondary button"), trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .gmFont(15, .semibold, maxSize: 26)
                            .foregroundColor(GM.textPrimary)
                            .flipsForRightToLeftLayoutDirection(true)
                    ), action: onSignIn)
                    .padding(.top, short ? 14 : 20)

                    // One sentence with the two document names inside it, so a
                    // translation can reorder them; the names keep their colour.
                    Text("By continuing, you agree to our \(Text("Terms of Service").foregroundColor(GM.primarySolid))\nand \(Text("Privacy Policy").foregroundColor(GM.primarySolid)).")
                        .foregroundColor(GM.textTertiary)
                        .gmFont(12)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, short ? 14 : 20)
                        .padding(.bottom, short ? 16 : 24)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity)
                .frame(minHeight: viewport.size.height)
            }
        }
        .gmMaxWidth(GMLayout.form)
        .background(GM.bg.ignoresSafeArea())
    }
}
