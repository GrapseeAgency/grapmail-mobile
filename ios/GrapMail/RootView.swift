import SwiftUI
import UIKit

/// The root navigation for the account sector: welcome → wizard (5 steps) →
/// human verification → account created, plus sign-in, two-step verification
/// and the whole recovery/security arc beside it.
struct RootView: View {
    @StateObject private var model = SignUpModel()
    @StateObject private var recovery = RecoveryModel()

    enum Route: Hashable {
        case welcome, signIn
        case twoFactor, backupCode
        case forgotPassword, recoveryCode, newPassword, resetDone, recoveryPending
        case findAddress, addressFound
        case phoneVerify, phoneVerified
        case stepName, stepInfo, stepAddress, stepPassword, stepTerms
        case humanVerify
        // The security sector — the 24-screen batch. Everything hangs off
        // .security, so a deep link can never strand somebody on a
        // confirmation with no parent.
        case security
        case secEnable, secConfirmPassword, secVerifyPhone, secEnabled
        case secDisable, secDisabled
        case secBackup, secBackupSaved
        case secPassword, secPasswordDone, secPasswordAfter, secPasswordUpdated
        case secSignOutAll, secSignOutAllDone
        case secSessions
        case secRevoke(id: String)
        case secRevoked(device: String)
        case secRevokeAlert(id: String)
        case secRevokedAlert(device: String)
        case secActivity, secAlert, secSecure
        case created(address: String, name: String)
        case createdAlt(address: String, name: String)
        case home
    }

    // A stored, unexpired session opens the app signed-in — the Keychain
    // token has to be read at launch, not just written.
    @State private var path: [Route] = SessionStore.hasValidSession ? [.home] : []
    /// The ten codes live in SecurityFlow.backupCodes, not a view @State, so
    /// SessionStore.clear() → SecurityFlow.reset() wipes them on sign-out. A
    /// plaintext recovery code left in the root view's memory after the account
    /// is signed out survives `path.removeAll()` and is readable by whoever
    /// signs in next.

    private func backToSignIn() {
        if path.contains(.signIn) {
            while let last = path.last, last != .signIn { path.removeLast() }
        } else {
            path.append(.signIn)
        }
    }

    private func backToHome() {
        while let last = path.last, last != .home { path.removeLast() }
    }

    /// Back to the hub, whichever door the person came through.
    private func backToSecurity() {
        if path.contains(.security) {
            while let last = path.last, last != .security { path.removeLast() }
        } else {
            path.append(.security)
        }
    }

    private func openSupport() {
        if let url = URL(string: "https://grapsee.com/contact") {
            UIApplication.shared.open(url)
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            WelcomeView(
                onCreateAccount: { path.append(.stepName) },
                onSignIn: { path.append(.signIn) }
            )
            .navigationDestination(for: Route.self) { route in
                // Every screen draws its own header with its own back control.
                // Without this the system bar added a second, unguarded back
                // button above it — and an unguarded way past screens that ask
                // for confirmation before leaving.
                Group {
                switch route {
                case .welcome:
                    WelcomeView(
                        onCreateAccount: { path.append(.stepName) },
                        onSignIn: { path.append(.signIn) }
                    )
                case .signIn:
                    SignInView(
                        model: recovery,
                        onSignedIn: { _ in
                            path.removeAll()
                            path.append(.home)
                        },
                        onBack: { path.removeLast() },
                        onSignUp: { path.append(.stepName) },
                        onForgotPassword: { path.append(.forgotPassword) },
                        onNeedSecondFactor: { path.append(.twoFactor) }
                    )
                case .twoFactor:
                    TwoFactorView(
                        model: recovery,
                        onSignedIn: { _ in
                            path.removeAll()
                            path.append(.home)
                        },
                        onBack: { path.removeLast() },
                        onBackToSignIn: { backToSignIn() },
                        onUseBackupCode: { path.append(.backupCode) }
                    )
                case .backupCode:
                    BackupCodeView(
                        model: recovery,
                        onSignedIn: { _ in
                            path.removeAll()
                            path.append(.home)
                        },
                        onBack: { path.removeLast() },
                        onTryAnotherMethod: { path.removeLast() },
                        onBackToSignIn: { backToSignIn() }
                    )
                case .forgotPassword:
                    ForgotPasswordView(
                        model: recovery,
                        onBack: { path.removeLast() },
                        onCodeSent: { path.append(.recoveryCode) },
                        onNeedsManualReview: { path.append(.recoveryPending) },
                        onFindAddress: { path.append(.findAddress) },
                        onSupport: { openSupport() }
                    )
                case .recoveryCode:
                    RecoveryCodeView(
                        model: recovery,
                        onBack: { path.removeLast() },
                        onContinue: { path.append(.newPassword) },
                        onBackToSignIn: { backToSignIn() }
                    )
                case .newPassword:
                    NewPasswordView(
                        model: recovery,
                        onBack: { path.removeLast() },
                        onBackToCode: { path.removeLast() },
                        onReset: { address in
                            recovery.resetAddress = address ?? recovery.identifier
                            // The recovery stack is spent once the password
                            // changes: back must not land on a code screen whose
                            // challenge the reset just consumed.
                            path.removeAll { $0 == .forgotPassword || $0 == .recoveryCode || $0 == .newPassword }
                            path.append(.resetDone)
                        }
                    )
                case .resetDone:
                    ResetDoneView {
                        recovery.prefillIdentifier = recovery.resetAddress ?? recovery.identifier
                        backToSignIn()
                    }
                case .recoveryPending:
                    RecoveryPendingView(
                        model: recovery,
                        onBackToSignIn: { backToSignIn() },
                        onSupport: { openSupport() }
                    )
                case .findAddress:
                    FindAddressView(
                        model: recovery,
                        onBack: { path.removeLast() },
                        onFound: { path.append(.addressFound) },
                        onSupport: { openSupport() }
                    )
                case .addressFound:
                    AddressFoundView(model: recovery) { address in
                        recovery.prefillIdentifier = address ?? recovery.foundAddressFull
                        backToSignIn()
                    }
                case .phoneVerify:
                    PhoneVerifyView(
                        model: recovery,
                        onBack: { path.removeLast() },
                        onVerified: { _ in
                            if path.last == .phoneVerify { path.removeLast() }
                            path.append(.phoneVerified)
                        }
                    )
                case .phoneVerified:
                    PhoneVerifiedView(
                        model: recovery,
                        onGoToSecurity: { path.append(.security) },
                        onBackHome: { backToHome() }
                    )
                case .security:
                    AccountSecurityView(
                        onBack: { path.removeLast() },
                        onTwoFactor: { path.append(.secEnable) },
                        onDisableTwoFactor: { path.append(.secDisable) },
                        onPhone: { path.append(.phoneVerify) },
                        onSessions: { path.append(.secSessions) },
                        onActivity: { path.append(.secActivity) },
                        onBackupCodes: { path.append(.secBackup) },
                        onPassword: { path.append(.secPassword) },
                        onSignOutAll: { path.append(.secSignOutAll) },
                        onSupport: { openSupport() }
                    )
                case .secEnable:
                    TwoFactorEnableView(
                        onBack: { path.removeLast() },
                        onContinue: { path.append(.secConfirmPassword) }
                    )
                case .secConfirmPassword:
                    ConfirmPasswordView(
                        onBack: { path.removeLast() },
                        onForgot: { path.append(.forgotPassword) },
                        onVerified: { path.append(.secVerifyPhone) }
                    )
                case .secVerifyPhone:
                    VerifyPhoneView(
                        onBack: { path.removeLast() },
                        onAddPhone: { path.append(.phoneVerify) },
                        onVerified: { path.append(.secEnabled) }
                    )
                case .secEnabled:
                    TwoFactorEnabledView(onContinue: { backToSecurity() }, onBackHome: { backToSecurity() })
                case .secDisable:
                    DisableTwoFactorView(
                        onBack: { path.removeLast() },
                        onDisabled: { path.append(.secDisabled) }
                    )
                case .secDisabled:
                    TwoFactorDisabledView(
                        onDone: { backToSecurity() },
                        onSecureNow: { path.append(.secEnable) }
                    )
                case .secBackup:
                    GenerateBackupCodesView(
                        onBack: { path.removeLast() },
                        onGenerated: { codes in
                            SecurityFlow.backupCodes = codes
                            path.append(.secBackupSaved)
                        }
                    )
                case .secBackupSaved:
                    BackupCodesSavedView(codes: SecurityFlow.backupCodes, onDone: { backToSecurity() })
                case .secPassword:
                    ChangePasswordView(
                        afterSuspicious: false,
                        onBack: { path.removeLast() },
                        onForgot: { path.append(.forgotPassword) },
                        onChanged: { path.append(.secPasswordDone) }
                    )
                case .secPasswordDone:
                    PasswordChangedView(updated: false, onDone: { backToSecurity() }, onSupport: { openSupport() })
                case .secPasswordAfter:
                    ChangePasswordView(
                        afterSuspicious: true,
                        onBack: { path.removeLast() },
                        onForgot: { path.append(.forgotPassword) },
                        onChanged: { path.append(.secPasswordUpdated) }
                    )
                case .secPasswordUpdated:
                    PasswordChangedView(updated: true, onDone: { backToSecurity() }, onSupport: { openSupport() })
                case .secSignOutAll:
                    SignOutAllDevicesView(
                        onBack: { path.removeLast() },
                        onDone: { path.append(.secSignOutAllDone) }
                    )
                case .secSignOutAllDone:
                    SignedOutAllDevicesView(onDone: { backToSecurity() })
                case .secSessions:
                    ManageSessionsView(
                        onBack: { path.removeLast() },
                        onRevoke: { session in path.append(.secRevoke(id: session.id)) },
                        onRevokeAlert: { session in path.append(.secRevokeAlert(id: session.id)) },
                        onSignOutAll: { path.append(.secSignOutAll) }
                    )
                case let .secRevoke(id):
                    RevokeSessionView(
                        sessionId: id,
                        onBack: { path.removeLast() },
                        onRevoked: { label in
                            path.removeLast()
                            path.append(.secRevoked(device: label))
                        }
                    )
                case let .secRevoked(device):
                    SessionRevokedView(device: device, onDone: { backToSecurity() })
                case let .secRevokeAlert(id):
                    ConfirmSessionRevocationView(
                        sessionId: id,
                        onBack: { path.removeLast() },
                        onRevoked: { label in
                            path.removeLast()
                            path.append(.secRevokedAlert(device: label))
                        }
                    )
                case let .secRevokedAlert(device):
                    SessionRevokedSuspiciousView(device: device, onDone: { backToSecurity() })
                case .secActivity:
                    SecurityActivityView(
                        onBack: { path.removeLast() },
                        onOpenAlert: { path.append(.secAlert) },
                        onOpenSessions: { path.append(.secSessions) }
                    )
                case .secAlert:
                    SuspiciousSignInAlertView(
                        onBack: { path.removeLast() },
                        onSecure: { path.append(.secSecure) },
                        onThisWasMe: { path.removeLast() }
                    )
                case .secSecure:
                    SecureMyAccountView(
                        onBack: { path.removeLast() },
                        onChangePassword: { path.append(.secPasswordAfter) },
                        onBackToActivity: {
                            if path.contains(.secActivity) {
                                while let last = path.last, last != .secActivity { path.removeLast() }
                            } else {
                                path.append(.secActivity)
                            }
                        }
                    )
                case .stepName:
                    NameStepView(model: model, onBack: { path.removeLast() }, onSignIn: { path.append(.signIn) }) {
                        model.update { $0.step = 2 }
                        path.append(.stepInfo)
                    }
                case .stepInfo:
                    BasicInfoView(model: model, onBack: { path.removeLast() }, onSignIn: { path.append(.signIn) }) {
                        model.update { $0.step = 3 }
                        path.append(.stepAddress)
                    }
                case .stepAddress:
                    AddressStepView(model: model, onBack: { path.removeLast() }, onSignIn: { path.append(.signIn) }) {
                        model.update { $0.step = 4 }
                        path.append(.stepPassword)
                    }
                case .stepPassword:
                    PasswordStepView(model: model, onBack: { path.removeLast() }, onSignIn: { path.append(.signIn) }) {
                        model.update { $0.step = 5 }
                        path.append(.stepTerms)
                    }
                case .stepTerms:
                    TermsStepView(model: model, onBack: { path.removeLast() }, onSignIn: { path.append(.signIn) }) { address, name in
                        path.removeAll(where: { $0 == .stepName || $0 == .stepInfo || $0 == .stepAddress || $0 == .stepPassword || $0 == .stepTerms })
                        // The account exists at this point, but the auto
                        // sign-in may not have: without a token the next screens
                        // answer "sign in again". Send the person to sign-in with
                        // the address filled in instead of into a dead flow.
                        if SessionStore.hasValidSession {
                            path.append(.humanVerify)
                        } else {
                            recovery.prefillIdentifier = address
                            path.append(.signIn)
                        }
                        _ = (address, name)
                    }
                case .humanVerify:
                    HumanVerifyView(model: model, onBack: { path.removeLast() }) {
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
                    SignedInHome(
                        onSignOut: {
                            SessionStore.clear()
                            path.removeAll()
                        },
                        onAccountSecurity: { path.append(.security) }
                    )
                }
                }
                .toolbar(.hidden, for: .navigationBar)
                .navigationBarBackButtonHidden(true)
            }
        }
    }
}

/// Temporary authenticated home — real session, real sign-out. The mailbox and
/// onboarding replace this in the next build phases.
struct SignedInHome: View {
    let onSignOut: () -> Void
    let onAccountSecurity: () -> Void

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
            // Account security is built: the phone screens live here until the
            // real settings hub exists, rather than being unreachable.
            OutlineActionButton(title: "Account security", action: onAccountSecurity)
                .padding(.top, 18)
            OutlineActionButton(title: "Sign out", action: onSignOut)
                .padding(.top, 12)
                .padding(.bottom, 32)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .centeredForm()
        .background(GM.bg.ignoresSafeArea())
    }
}
