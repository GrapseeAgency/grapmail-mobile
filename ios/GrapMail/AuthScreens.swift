import SwiftUI

/// Welcome — founder design verbatim: logo + "Your world. Your inbox.",
/// "Welcome to / GrapMail", the 3D-envelope hero, arrowed CTAs, legal line.
struct WelcomeView: View {
    let onCreateAccount: () -> Void
    let onSignIn: () -> Void

    var body: some View {
        // ScrollView-backed so nothing clips on the smallest phones (SE) and
        // in landscape; the min-height keeps the full-screen distribution
        // on regular devices.
        GeometryReader { viewport in
            ScrollView {
                VStack(spacing: 0) {
                    Spacer(minLength: 40)
                    Wordmark()
                    Text("Your world. Your inbox.")
                        .font(.system(size: 13))
                        .foregroundColor(GM.textSecondary)
                        .padding(.top, 6)

                    Spacer(minLength: 10)

                    VStack(spacing: 0) {
                        Text("Welcome to")
                            .font(.system(size: 30, weight: .bold))
                            .foregroundColor(GM.textPrimary)
                        Text("GrapMail")
                            .font(.system(size: 30, weight: .bold))
                            .foregroundColor(GM.primarySolid)
                    }
                    Text("A faster, safer and more private way\nto stay connected.")
                        .font(.system(size: 14))
                        .foregroundColor(GM.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 12)

                    // The clean envelope artwork — ill_hero carried the
                    // headline and subcopy baked in, duplicating the text.
                    Image("ill_welcome")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(maxWidth: 300)
                        .padding(.top, 18)

                    Spacer(minLength: 10)

                    GradientButton(title: "Create Account", trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    ), action: onCreateAccount)
                    OutlineActionButton(title: "Sign In", trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(GM.textPrimary)
                    ), action: onSignIn)
                    .padding(.top, 12)

                    (Text("By continuing, you agree to our ")
                        .foregroundColor(GM.textTertiary)
                     + Text("Terms of Service").foregroundColor(GM.primarySolid)
                     + Text("\nand ").foregroundColor(GM.textTertiary)
                     + Text("Privacy Policy").foregroundColor(GM.primarySolid)
                     + Text(".").foregroundColor(GM.textTertiary))
                        .font(.system(size: 12))
                        .multilineTextAlignment(.center)
                        .padding(.top, 20)
                        .padding(.bottom, 24)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity)
                .frame(minHeight: viewport.size.height)
            }
        }
        .centeredForm()
        .background(GM.bg.ignoresSafeArea())
    }
}
