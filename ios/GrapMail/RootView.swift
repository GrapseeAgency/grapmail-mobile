import SwiftUI

/// The root navigation for the account sector: welcome → wizard (5 steps) →
/// human verification → account created. Sign-in runs beside it.
struct RootView: View {
    @StateObject private var model = SignUpModel()

    enum Route: Hashable {
        case welcome, signIn
        case stepName, stepInfo, stepAddress, stepPassword, stepTerms
        case humanVerify
        case created(address: String, name: String)
        case createdAlt(address: String, name: String)
        case home
    }

    @State private var path: [Route] = []

    var body: some View {
        NavigationStack(path: $path) {
            WelcomeView(
                onCreateAccount: { path.append(.stepName) },
                onSignIn: { path.append(.signIn) }
            )
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .welcome:
                    WelcomeView(
                        onCreateAccount: { path.append(.stepName) },
                        onSignIn: { path.append(.signIn) }
                    )
                case .signIn:
                    SignInView(
                        onSignedIn: { _ in
                            path.removeAll()
                            path.append(.home)
                        },
                        onSignUp: { path.append(.stepName) }
                    )
                case .stepName:
                    NameStepView(model: model, onBack: { path.removeLast() }) {
                        model.update { $0.step = 2 }
                        path.append(.stepInfo)
                    }
                case .stepInfo:
                    BasicInfoView(model: model, onBack: { path.removeLast() }) {
                        model.update { $0.step = 3 }
                        path.append(.stepAddress)
                    }
                case .stepAddress:
                    AddressStepView(model: model, onBack: { path.removeLast() }) {
                        model.update { $0.step = 4 }
                        path.append(.stepPassword)
                    }
                case .stepPassword:
                    PasswordStepView(model: model, onBack: { path.removeLast() }) {
                        model.update { $0.step = 5 }
                        path.append(.stepTerms)
                    }
                case .stepTerms:
                    TermsStepView(model: model, onBack: { path.removeLast() }) { address, name in
                        path.removeAll(where: { $0 == .stepName || $0 == .stepInfo || $0 == .stepAddress || $0 == .stepPassword || $0 == .stepTerms })
                        path.append(.humanVerify)
                        _ = (address, name)
                    }
                case .humanVerify:
                    HumanVerifyView(model: model) {
                        let s = model.state
                        path.append(.created(
                            address: s.createdAddress ?? "",
                            name: s.createdName ?? s.firstName
                        ))
                    }
                case let .created(address, name):
                    AccountCreatedView(address: address, name: name) {
                        path.append(.createdAlt(address: address, name: name))
                    }
                case let .createdAlt(address, name):
                    AccountCreatedAltView(
                        address: address,
                        name: name,
                        onGoToMailbox: {
                            path.removeAll()
                            path.append(.home)
                        },
                        onContinueOnboarding: {
                            path.removeAll()
                            path.append(.home)
                        }
                    )
                case .home:
                    SignedInHome {
                        SessionStore.clear()
                        path.removeAll()
                    }
                }
            }
        }
    }
}

/// Temporary authenticated home — real session, real sign-out. The mailbox
/// and onboarding replace this in the next build phases.
struct SignedInHome: View {
    let onSignOut: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 56)
            Wordmark()
            Text("Signed in")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(GM.textPrimary)
                .padding(.top, 28)
            Text(SessionStore.address ?? "—")
                .font(.system(size: 15))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 8)
            Spacer()
            Text("The mailbox arrives in the next build phase.")
                .font(GMFont.caption)
                .foregroundColor(GM.textTertiary)
            OutlineActionButton(title: "Sign out", action: onSignOut)
                .padding(.top, 18)
                .padding(.bottom, 32)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(GM.bg.ignoresSafeArea())
    }
}
