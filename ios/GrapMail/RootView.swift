import SwiftUI
import UIKit
// `NotificationCenter.publisher(for:)` — the session-expiry signal below — is a
// Combine API. SwiftUI does not re-export it, so the publisher is invisible
// without this.
import Combine

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
        case secRevoke(id: String, device: String)
        case secRevoked(device: String)
        case secRevokeAlert(id: String, device: String)
        case secRevokedAlert(device: String)
        case secActivity, secAlert, secSecure
        case created(address: String, name: String)
        case createdAlt(address: String, name: String)
        case home
        case mailThread(id: String, isThread: Bool)
        // Compose carries an optional reply target and mode: empty reply opens
        // blank, "reply|all|forward" opens prepared from the full server row.
        case mailCompose(reply: String, mode: String)
        case mailLabels, mailLabelCreate(id: String)
        case mailContacts, mailContactDetail(address: String)
        case mailFiles, mailAttachment(id: String)
        case mailSettings, mailScreener
        // ── account-record band ──────────────────────────────────────────────
        // The web client's `/mail/notes`, `/mail/tasks`, `/mail/settings`,
        // `/mail/account/{profile,privacy,addresses,connections,support}` —
        // one profile document, eight screens over it. They hang off
        // mailSettings the way the security screens hang off .security: every
        // one has a parent, so a deep link can never strand somebody on a
        // child with no way back.
        case mailNotes, mailTasks, mailPrefs, mailAddresses, mailConnections
        case accountProfile, accountPrivacy, mailSupport
        // The web's `/mail/subscriptions` — senders detected from the loaded
        // inbox, each with a browser handoff. One screen; it hangs off the
        // same Settings/drawer doors as the account band.
        case mailSubscriptions
        // ── encryption band ──────────────────────────────────────────────────
        // The web client's `/mail/account/encryption`, split into the eight
        // screens a phone needs. One model for the stack: the in-memory unlock
        // session is the whole point of three of them, and two copies of it
        // would be two answers to "is the key open right now".
        case e2eStatus, e2eSetup, e2eUnlock, e2eDetails, e2ePublish
        case e2eChangePassphrase, e2eRemove, e2eRecipients
    }

    /// One mailbox for the whole signed-in stack. The list, the thread, compose
    /// and the secondary screens all read and write this, so starring a message
    /// in the list is visible in the thread that opens next, and a body read in
    /// the thread is not fetched again by the Files screen.
    @StateObject private var mail = MailboxModel()

    /// The account record every account-band screen reads and writes. One
    /// instance for the stack: the screens are eight views of one document,
    /// and eight separate copies of it is eight chances to disagree about the
    /// same account. Mirror of Android's `profile: ProfileViewModel` in
    /// `GrapMailNav`.
    @StateObject private var account = ProfileModel()

    /// The encryption sector's state: the device identity, the in-memory
    /// unlock session, the directory's published answer. Mirror of Android's
    /// `e2e: E2eViewModel` in `GrapMailNav` — one instance, because compose,
    /// the thread and the eight screens must agree on whether the key is open.
    @StateObject private var e2e = E2eModel()

    // A stored, unexpired session opens the app signed-in — the Keychain
    // token has to be read at launch, not just written.
    /// A stored session opens signed-in. The test-only snapshot host is the one
    /// launch that must NOT do that: the walk signs up first, so by the time it
    /// relaunches per screen a live session exists, and pushing `.home` on top
    /// of the stub left every post-write screenshot a photograph of the home
    /// screen. The hook outranks the restore because no real launch carries it.
    @State private var path: [Route] =
        (SecuritySnapshot.requested() == nil && SessionStore.hasValidSession) ? [.home] : []
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
            Group {
                // Test-only hook: the UI test names one post-write confirmation
                // screen at launch and the app renders it instead of the flow.
                // Those screens only exist after a write the walk must not
                // perform, so they are otherwise unreachable. No real launch
                // ever carries the argument, and nothing here fetches or submits.
                if let snapshot = SecuritySnapshot.requested() {
                    Group {
                        if snapshot.hasPrefix("mail-") || snapshot.hasPrefix("account-")
                            || snapshot.hasPrefix("e2e-") {
                            MailSnapshotView(name: snapshot)
                        } else {
                            SecuritySnapshotView(name: snapshot)
                        }
                    }
                    // The dark frame the design review needs. XCUITest on this
                    // runner has no appearance override, so the app applies it
                    // from a launch argument no real launch carries — and only
                    // inside the test-only branch, where it cannot affect a live
                    // screen. The colours still resolve through GM's trait
                    // lookup; this only changes which trait the system reports.
                    .preferredColorScheme(
                        ProcessInfo.processInfo.arguments.contains("-gmDark") ? .dark : nil)
                } else {
                    WelcomeView(
                        onCreateAccount: { path.append(.stepName) },
                        onSignIn: { path.append(.signIn) }
                    )
                }
            }
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
                        onRevoke: { session in path.append(.secRevoke(id: session.id, device: session.device)) },
                        onRevokeAlert: { session in path.append(.secRevokeAlert(id: session.id, device: session.device)) },
                        onSignOutAll: { path.append(.secSignOutAll) }
                    )
                case let .secRevoke(id, device):
                    RevokeSessionView(
                        sessionId: id,
                        device: device,
                        onBack: { path.removeLast() },
                        onRevoked: { label in
                            path.removeLast()
                            path.append(.secRevoked(device: label))
                        }
                    )
                case let .secRevoked(device):
                    SessionRevokedView(device: device, onDone: { backToSecurity() })
                case let .secRevokeAlert(id, device):
                    ConfirmSessionRevocationView(
                        sessionId: id,
                        device: device,
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
                    MailboxView(
                        vm: mail,
                        onThread: { id, isThread in
                            path.append(.mailThread(id: id, isThread: isThread))
                        },
                        onCompose: { path.append(.mailCompose(reply: "", mode: "reply")) },
                        onLabels: { path.append(.mailLabels) },
                        onScreener: { path.append(.mailScreener) },
                        onSettings: { path.append(.mailSettings) },
                        onSecurity: { path.append(.security) },
                        onHelp: openSupport,
                        onContacts: { path.append(.mailContacts) },
                        onFiles: { path.append(.mailFiles) },
                        onSubscriptions: { path.append(.mailSubscriptions) },
                        onSignOut: {
                            SessionStore.clear()
                            path.removeAll()
                        }
                    )
                case let .mailThread(id, isThread):
                    ThreadView(
                        vm: mail, e2e: e2e, id: id, isThread: isThread,
                        onBack: { path.removeLast() },
                        onReply: { id, mode in path.append(.mailCompose(reply: id, mode: mode)) }
                    )
                case let .mailCompose(reply, mode):
                    ComposeView(vm: mail, account: account, e2e: e2e, replyId: reply, mode: mode,
                                onBack: { path.removeLast() })
                case .mailLabels:
                    LabelsView(vm: mail,
                               onBack: { path.removeLast() },
                               onCreate: { path.append(.mailLabelCreate(id: "")) },
                               onEdit: { path.append(.mailLabelCreate(id: $0.id)) })
                case let .mailLabelCreate(id):
                    // "" is a new label; anything else is the row that opened
                    // this screen. If the list has not loaded that label, the
                    // screen opens as a create rather than inventing a name or a
                    // colour to show for it.
                    LabelCreateView(vm: mail, onDone: { path.removeLast() },
                                    editing: id.isEmpty ? nil
                                        : mail.labelState.labels.first { $0.id == id })
                case .mailContacts:
                    ContactsView(vm: mail,
                                 onBack: { path.removeLast() },
                                 onOpen: { path.append(.mailContactDetail(address: $0)) })
                case let .mailContactDetail(address):
                    ContactDetailView(vm: mail, address: address,
                                      onBack: { path.removeLast() },
                                      onWrite: { path.append(.mailCompose(reply: "", mode: "reply")) })
                case .mailFiles:
                    FilesView(vm: mail,
                              onBack: { path.removeLast() },
                              onOpen: { path.append(.mailAttachment(id: $0)) })
                case let .mailAttachment(id):
                    AttachmentPreviewView(vm: mail, attachmentId: id,
                                          onBack: { path.removeLast() })
                case .mailSettings:
                    MailSettingsView(
                        vm: mail,
                        onBack: { path.removeLast() },
                        onSecurity: { path.append(.security) },
                        onScreener: { path.append(.mailScreener) },
                        onLabels: { path.append(.mailLabels) },
                        onNotes: { path.append(.mailNotes) },
                        onTasks: { path.append(.mailTasks) },
                        onPrefs: { path.append(.mailPrefs) },
                        onAddresses: { path.append(.mailAddresses) },
                        onConnections: { path.append(.mailConnections) },
                        onSubscriptions: { path.append(.mailSubscriptions) },
                        onProfile: { path.append(.accountProfile) },
                        onPrivacy: { path.append(.accountPrivacy) },
                        onEncryption: { path.append(.e2eStatus) },
                        onHelp: { path.append(.mailSupport) },
                        onSignOut: {
                            SessionStore.clear()
                            path.removeAll()
                        }
                    )
                case .mailNotes:
                    NotesView(vm: account, onBack: { path.removeLast() })
                case .mailTasks:
                    TasksView(vm: account, onBack: { path.removeLast() })
                case .mailPrefs:
                    MailPrefsView(vm: account, onBack: { path.removeLast() })
                case .mailAddresses:
                    AddressesView(vm: account, onBack: { path.removeLast() })
                case .mailConnections:
                    ConnectionsView(vm: account, onBack: { path.removeLast() })
                case .mailSubscriptions:
                    SubscriptionsView(vm: mail, onBack: { path.removeLast() })
                case .accountProfile:
                    ProfileEditView(vm: account, onBack: { path.removeLast() })
                case .accountPrivacy:
                    PrivacyView(
                        vm: account,
                        onBack: { path.removeLast() },
                        onProfile: { path.append(.accountProfile) },
                        onPhone: { path.append(.phoneVerify) },
                        onAddresses: { path.append(.mailAddresses) },
                        onPrefs: { path.append(.mailPrefs) },
                        onSupport: { path.append(.mailSupport) },
                    )
                case .mailSupport:
                    SupportView(
                        onBack: { path.removeLast() },
                        // The in-app doors the Android support screen routes
                        // to. Sign-in carries the forgot-password entry on iOS
                        // (there is no separate forgot route), and phone
                        // verification is the same proven-by-code flow the
                        // security sector runs.
                        onForgot: { path.append(.signIn) },
                        onPhone: { path.append(.phoneVerify) },
                        onAddresses: { path.append(.mailAddresses) },
                    )
                // ── encryption band ──────────────────────────────────────────
                case .e2eStatus:
                    E2eStatusScreen(vm: e2e, onBack: { path.removeLast() },
                                    onSetup: { path.append(.e2eSetup) },
                                    onUnlock: { path.append(.e2eUnlock) },
                                    onDetails: { path.append(.e2eDetails) },
                                    onPublish: { path.append(.e2ePublish) },
                                    onChangePassphrase: { path.append(.e2eChangePassphrase) },
                                    onRemove: { path.append(.e2eRemove) },
                                    onRecipients: { path.append(.e2eRecipients) })
                case .e2eSetup:
                    E2eSetupScreen(vm: e2e, onBack: { path.removeLast() })
                case .e2eUnlock:
                    E2eUnlockScreen(vm: e2e, onBack: { path.removeLast() })
                case .e2eDetails:
                    E2eDetailsScreen(vm: e2e, onBack: { path.removeLast() })
                case .e2ePublish:
                    E2ePublishScreen(vm: e2e, onBack: { path.removeLast() })
                case .e2eChangePassphrase:
                    E2ePassphraseScreen(vm: e2e, onBack: { path.removeLast() })
                case .e2eRemove:
                    E2eRemoveScreen(vm: e2e, onBack: { path.removeLast() })
                case .e2eRecipients:
                    E2eRecipientsScreen(vm: e2e, onBack: { path.removeLast() },
                                        onCompose: {
                                            // Same door Android's lookup screen
                                            // opens: back to compose, where the
                                            // encrypt row reads the same session.
                                            // Pop to the mailbox first -- the
                                            // band sits under Settings -- then
                                            // one fresh compose, so a stale
                                            // reply-target compose is never
                                            // reused for a new message.
                                            while let last = path.last, last != .home {
                                                path.removeLast()
                                            }
                                            path.append(.mailCompose(reply: "", mode: "reply"))
                                        })
                case .mailScreener:
                    ScreenerView(
                        vm: mail,
                        onBack: { path.removeLast() },
                        onOpenQueue: { mail.open(.screener); backToHome() }
                    )
                }
                }
                .toolbar(.hidden, for: .navigationBar)
                .navigationBarBackButtonHidden(true)
            }
            // A dead token is discovered by the server, not by us: the first
            // authenticated call that comes back 401 clears the Keychain and
            // posts this. Without the handler the app stays on a signed-in
            // screen whose every request fails, and the person has no way back
            // in short of reinstalling. Anonymous 401s (wrong password at
            // sign-in) never reach here — only a rejected bearer does.
            .onReceive(NotificationCenter.default.publisher(for: .gmSessionExpired)) { _ in
                guard path.last != .signIn else { return }
                path = [.signIn]
            }
        }
    }
}

