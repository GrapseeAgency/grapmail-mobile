import AuthenticationServices
import SwiftUI
import UIKit

// MARK: Round 8 — B (welcome-back)
//
// The personal welcome-back sign-in (round8-spec §2.2–§2.7) and the account
// chooser for two or more remembered accounts (§2.6).
//
// Entry: RootView routes every signed-out door to sign-in (cold start, the
// welcome screen's "Sign in", sign-out, an expired session, a link or shortcut
// while signed out) through `signInRoute()`, which picks
// `.welcomeBack(address:)` for one remembered account, `.accountChooser` for
// two or more, and the classic `.signIn` otherwise. Every success runs
// RootView.enterMailbox(), after SessionStore.save() has posted
// `.gmSessionChanged` (trusted-device enrollment, push re-registration) —
// exactly the pipeline the password and passkey forms run today.
//
// Security: a remembered record only pre-fills the address and shows the name
// and photo. It never signs anyone in: every path below still needs a passkey,
// a password, and the normal two-step screen when the account has one.
//
// Strings: every new English string lives in en.lproj/WelcomeBack.strings
// (`table: "WelcomeBack"`); strings that already exist in Localizable are
// reused from it so their translations keep working.

// MARK: - Gate

/// Whether the welcome-back surface may show, plus the per-launch state §2.1 names.
@MainActor
enum WelcomeBackGate {
    /// "Use another account" was tapped in this launch (§2.1 rule 3).
    private(set) static var usedAnotherAccount = false
    /// Set when a passkey signed in a different account than the one chosen
    /// (§2.3): "Signed in as {address}". The mailbox may show and clear it.
    static var signedInAsNotice: String?

    /// RemoteConfig `welcome_back`: on in debug, off in release until QA (§7).
    /// UI-test launches keep the classic sign-in the screenshot walk expects.
    static var isEnabled: Bool {
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-gmNoWelcomeBack") || args.contains("-gmSnapshot") || args.contains("-gmNoTips") {
            return false
        }
        #if DEBUG
        let fallback = true
        #else
        let fallback = false
        #endif
        return RemoteConfig.shared.flag("welcome_back", default: fallback)
    }

    /// The remembered accounts welcome-back may offer right now: none while a
    /// session exists, none after "Use another account" in this launch.
    static func candidates() -> [RememberedAccount] {
        guard isEnabled, !usedAnotherAccount, SessionStore.token == nil else { return [] }
        return RememberedAccounts.signedOutAccounts()
    }

    static func chooseAnotherAccount() { usedAnotherAccount = true }
}

// MARK: - Screen (chooser ↔ welcome-back in one container)

/// Chooser and welcome-back live in ONE container that swaps its content, so
/// the avatar can move between them with matchedGeometryEffect on iOS 16
/// (§2.7) — a NavigationStack push cannot share an element.
@MainActor
struct WelcomeBackScreen: View {
    enum Exit {
        /// "Use another account", or nothing left to offer.
        case useAnotherAccount
        /// The last remembered account was removed from this device.
        case removedAll
        /// The account is blocked or gone; its record is already forgotten.
        case blocked(address: String, message: String)
    }

    @ObservedObject var model: RecoveryModel
    /// Opens straight onto this account when it is remembered.
    var preferredAddress: String? = nil
    var onBack: (() -> Void)? = nil
    let onSignedIn: (String) -> Void
    let onNeedSecondFactor: () -> Void
    let onNeedEmailVerification: () -> Void
    let onForgotPassword: () -> Void
    let onExit: (Exit) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var avatarSpace
    @State private var accounts: [RememberedAccount] = []
    @State private var photos: [String: Data] = [:]
    @State private var selectedID: String?
    @State private var showChooser = false
    @State private var cameFromChooser = false
    @State private var pendingRemoval: RememberedAccount?
    @State private var snackbar: String?
    @State private var loaded = false

    private var selected: RememberedAccount? {
        if let selectedID { return accounts.first { $0.id == selectedID } }
        if !showChooser, accounts.count == 1 { return accounts.first }
        return nil
    }

    /// Shared-element move between chooser and welcome-back; a crossfade
    /// under Reduce Motion.
    private var swapAnimation: Animation {
        reduceMotion ? .easeInOut(duration: GMMotion.short) : GMMotion.standard(GMMotion.long)
    }

    var body: some View {
        ZStack {
            GM.bg.ignoresSafeArea()
            if let account = selected {
                WelcomeBackAccountView(
                    account: account,
                    photo: photos[account.id],
                    model: model,
                    namespace: avatarSpace,
                    sharedAvatar: !reduceMotion,
                    animateEntrance: !cameFromChooser,
                    onBack: accounts.count >= 2 ? { choose(nil) } : onBack,
                    onSignedIn: onSignedIn,
                    onNeedSecondFactor: onNeedSecondFactor,
                    onNeedEmailVerification: onNeedEmailVerification,
                    onForgotPassword: onForgotPassword,
                    onUseAnother: useAnother,
                    onRemove: { pendingRemoval = account },
                    onBlocked: { message in
                        RememberedAccounts.forget(account.address)
                        onExit(.blocked(address: account.address, message: message))
                    }
                )
                .id(account.id)
                .transition(.opacity)
            } else if !accounts.isEmpty {
                AccountChooserView(
                    accounts: accounts,
                    photos: photos,
                    namespace: avatarSpace,
                    sharedAvatar: !reduceMotion,
                    onBack: onBack,
                    onPick: { choose($0.id) },
                    onRemove: { pendingRemoval = $0 },
                    onUseAnother: useAnother
                )
                .transition(.opacity)
            }
        }
        .overlay(alignment: .bottom) { snackbarView }
        .alert(
            removalTitle,
            isPresented: Binding(get: { pendingRemoval != nil }, set: { if !$0 { pendingRemoval = nil } }),
            presenting: pendingRemoval
        ) { account in
            Button(String(localized: "Remove"), role: .destructive) { remove(account) }
            Button(String(localized: "Cancel"), role: .cancel) {}
        } message: { _ in
            Text("Your account and mail aren't affected. You'll need your address and password to sign in here again.",
                 tableName: "WelcomeBack")
        }
        .onAppear { load() }
    }

    private var removalTitle: String {
        guard let account = pendingRemoval else { return "" }
        let label = WelcomeBackNames.firstName(account) ?? account.address
        return String(localized: "Remove \(label) from this device?", table: "WelcomeBack")
    }

    @ViewBuilder private var snackbarView: some View {
        if let snackbar {
            Text(verbatim: snackbar)
                .gmFont(.captionMedium)
                .foregroundColor(GM.bg)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(Capsule().fill(GM.textPrimary))
                .padding(.horizontal, 24)
                .padding(.bottom, 24)
                .transition(.opacity)
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        accounts = RememberedAccounts.signedOutAccounts()
        photos = cachedPhotos(for: accounts)
        if let preferred = preferredAddress?.nilIfBlank() {
            let id = RememberedAccounts.canonical(preferred)
            if accounts.contains(where: { $0.id == id }) { selectedID = id }
        }
        if accounts.isEmpty { onExit(.useAnotherAccount) }
    }

    private func cachedPhotos(for list: [RememberedAccount]) -> [String: Data] {
        var out: [String: Data] = [:]
        for account in list {
            if let data = RememberedAccounts.cachedAvatar(for: account.address) { out[account.id] = data }
        }
        return out
    }

    private func choose(_ id: String?) {
        withAnimation(swapAnimation) {
            cameFromChooser = id != nil
            selectedID = id
            showChooser = id == nil
        }
    }

    private func useAnother() {
        WelcomeBackGate.chooseAnotherAccount()
        onExit(.useAnotherAccount)
    }

    /// §2.5: delete the record and the cached photo, announce it, then the
    /// chooser if anyone is left, otherwise the standard sign-in.
    private func remove(_ account: RememberedAccount) {
        RememberedAccounts.forget(account.address)
        let removed = String(localized: "Removed from this device", table: "WelcomeBack")
        UIAccessibility.post(notification: .announcement, argument: removed)
        let remaining = RememberedAccounts.signedOutAccounts()
        guard !remaining.isEmpty else {
            onExit(.removedAll)
            return
        }
        withAnimation(swapAnimation) {
            accounts = remaining
            photos = cachedPhotos(for: remaining)
            selectedID = nil
            showChooser = true
            cameFromChooser = false
        }
        showSnackbar(removed)
    }

    private func showSnackbar(_ text: String) {
        withAnimation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion)) { snackbar = text }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            guard snackbar == text else { return }
            withAnimation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion)) { snackbar = nil }
        }
    }
}

// MARK: - Welcome-back (one account)

@MainActor
private struct WelcomeBackAccountView: View {
    let account: RememberedAccount
    let photo: Data?
    @ObservedObject var model: RecoveryModel
    let namespace: Namespace.ID
    let sharedAvatar: Bool
    let animateEntrance: Bool
    let onBack: (() -> Void)?
    let onSignedIn: (String) -> Void
    let onNeedSecondFactor: () -> Void
    let onNeedEmailVerification: () -> Void
    let onForgotPassword: () -> Void
    let onUseAnother: () -> Void
    let onRemove: () -> Void
    let onBlocked: (String) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @ObservedObject private var network = NetworkMonitor.shared

    @State private var password = ""
    /// Rung 3 (§2.3): the password field, shown only once rungs 1–2 gave nothing.
    @State private var revealed = false
    @State private var busy = false
    @State private var fieldError: String?
    @State private var message: String?
    @State private var shakes: CGFloat = 0
    @State private var appeared = false

    private var offline: Bool { network.online == false }

    private var displayName: String {
        account.displayName.nilIfBlank() ?? AddressMask.localPart(account.address)
    }

    private var continueTitle: String {
        if busy { return String(localized: "Signing in…", table: "WelcomeBack") }
        if let first = WelcomeBackNames.firstName(account) {
            return String(localized: "Continue as \(first)", table: "WelcomeBack")
        }
        return String(localized: "Continue")
    }

    private var reasonText: String? {
        switch account.lastSignOutReason {
        case .expired?: return String(localized: "For your security, please sign in again.", table: "WelcomeBack")
        case .revoked?: return String(localized: "You were signed out from another device.", table: "WelcomeBack")
        default: return nil
        }
    }

    var body: some View {
        GeometryReader { geo in
            let short = verticalSizeClass == .compact || geo.size.height < 480
            let wide = !short && horizontalSizeClass == .regular && geo.size.width >= 700
            Group {
                if short {
                    landscape(geo)
                } else if wide {
                    tablet(geo)
                } else {
                    phone(geo)
                }
            }
        }
        .onAppear { appeared = true }
    }

    // MARK: Layouts

    /// Phone portrait: one centred column, 420 wide at most, gutter 24.
    private func phone(_ geo: GeometryProxy) -> some View {
        ScrollView {
            VStack(spacing: 0) {
                AccountHeader(onBack: onBack)
                Spacer().frame(height: 40)
                hero(88)
                    .entrance(visible: appeared || !animateEntrance, scaled: true, delay: 0, reduced: reduceMotion)
                Spacer().frame(height: 16)
                nameBlock
                    .entrance(visible: appeared || !animateEntrance, scaled: false, delay: 0.04, reduced: reduceMotion)
                Spacer().frame(height: 32)
                actionColumn
                    .entrance(visible: appeared || !animateEntrance, scaled: false, delay: 0.08, reduced: reduceMotion)
                Spacer(minLength: 24)
                removeButton
                    .padding(.bottom, 16)
            }
            .padding(.horizontal, 24)
            .frame(minHeight: geo.size.height)
            .gmMaxWidth(420)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    /// iPad / regular width: illustration on the left half, the same column in
    /// a card on the right, vertically centred.
    private func tablet(_ geo: GeometryProxy) -> some View {
        let half = geo.size.width * 0.5
        return HStack(spacing: 0) {
            Art.view("welcome", width: max(0, min(420, half - 48)))
                .frame(width: half)
            ScrollView {
                VStack(spacing: 0) {
                    AccountHeader(onBack: onBack)
                    Spacer().frame(height: 24)
                    hero(88)
                        .entrance(visible: appeared || !animateEntrance, scaled: true, delay: 0, reduced: reduceMotion)
                    Spacer().frame(height: 16)
                    nameBlock
                        .entrance(visible: appeared || !animateEntrance, scaled: false, delay: 0.04, reduced: reduceMotion)
                    Spacer().frame(height: 32)
                    actionColumn
                        .entrance(visible: appeared || !animateEntrance, scaled: false, delay: 0.08, reduced: reduceMotion)
                    Spacer().frame(height: 24)
                    removeButton
                }
                .padding(32)
                .frame(width: max(0, min(440, half - 32)))
                .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
                .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
                .frame(maxWidth: .infinity, minHeight: geo.size.height)
            }
            .scrollDismissesKeyboard(.interactively)
            .frame(width: half)
        }
    }

    /// Landscape phone / short window: identity on the left 40 %, actions on
    /// the right, no wordmark.
    private func landscape(_ geo: GeometryProxy) -> some View {
        ScrollView {
            HStack(alignment: .top, spacing: 24) {
                VStack(spacing: 12) {
                    if let onBack {
                        HStack {
                            Button(action: onBack) {
                                Image(systemName: "chevron.left")
                                    .gmFont(17, .semibold, maxSize: 28)
                                    .foregroundColor(GM.textPrimary)
                                    .flipsForRightToLeftLayoutDirection(true)
                                    .frame(minWidth: 44, minHeight: 44)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("Back", tableName: "WelcomeBack"))
                            .accessibilityIdentifier("back")
                            Spacer()
                        }
                    }
                    hero(72)
                    nameBlock
                }
                .frame(width: max(0, (geo.size.width - 72) * 0.4))
                VStack(spacing: 12) {
                    actionColumn
                    removeButton
                }
                .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .frame(minHeight: geo.size.height)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    // MARK: Pieces

    /// Avatar inside a 3 pt gap and a 3 pt brand-gradient ring. The ring turns
    /// while signing in; under Reduce Motion it stays still.
    private func hero(_ size: CGFloat) -> some View {
        ZStack {
            WelcomeBackRing(spinning: busy && !reduceMotion)
                .frame(width: size + 12, height: size + 12)
            FlexibleAvatar(account: account, photo: photo)
                .modifier(SharedAvatar(id: account.id, namespace: namespace, enabled: sharedAvatar))
                .frame(width: size, height: size)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: displayName))
        .accessibilityAddTraits(.isImage)
    }

    private var nameBlock: some View {
        VStack(spacing: 4) {
            Text(verbatim: displayName)
                .gmFont(.h2)
                .foregroundColor(GM.textPrimary)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            Text(verbatim: AddressMask.mask(account.address))
                .gmFont(.body)
                .foregroundColor(GM.textSecondary)
                .lineLimit(1)
                .truncationMode(.middle)
                .accessibilityLabel(AddressMask.spokenLabel(account.address))
            if let reasonText {
                Text(verbatim: reasonText)
                    .gmFont(.caption)
                    .foregroundColor(GM.textSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var actionColumn: some View {
        VStack(spacing: 12) {
            offlineBanner
            if let message {
                Text(verbatim: message)
                    .gmFont(.caption)
                    .foregroundColor(GM.error)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            passwordBlock
            GradientButton(
                title: continueTitle,
                enabled: !offline && (!revealed || !password.isEmpty)
            ) { primaryTapped() }
            .allowsHitTesting(!busy)
            .accessibilityIdentifier("welcome-back-continue")
            Button(action: onUseAnother) {
                Text("Use another account", tableName: "WelcomeBack")
                    .gmFont(.bodyMedium)
                    .foregroundColor(GM.primaryText)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(busy)
            .accessibilityIdentifier("welcome-back-use-another")
        }
        .animation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion), value: revealed)
    }

    @ViewBuilder private var offlineBanner: some View {
        if offline {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: "wifi.slash")
                    .gmFont(13, .semibold, maxSize: 24)
                    .accessibilityHidden(true)
                Text("You're offline. Connect to sign in.", tableName: "WelcomeBack")
                    .gmFont(.caption)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .foregroundColor(GM.warnInk)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(RoundedRectangle(cornerRadius: GMShape.small, style: .continuous).fill(GM.warnChipBg))
            .accessibilityElement(children: .combine)
        }
    }

    /// Rung 3. The hidden username field carries the full address so iCloud
    /// Keychain / 1Password can fill and later save the right credential.
    @ViewBuilder private var passwordBlock: some View {
        if revealed {
            VStack(alignment: .trailing, spacing: 4) {
                CredentialUsernameField(username: account.address)
                AppTextField(
                    title: String(localized: "Password", comment: "Text field label (sign in screen)"),
                    value: $password,
                    error: fieldError,
                    hint: String(localized: "Enter your password", comment: "Text field placeholder (sign in screen)"),
                    isPassword: true,
                    leadingIcon: "lock",
                    contentType: .password,
                    focusOnAppear: true
                )
                .modifier(GMShakeEffect(shakes: shakes))
                // Return signs in, as the Continue button does.
                .submitLabel(.go)
                .onSubmit { primaryTapped() }
                .onChange(of: password) { _ in if fieldError != nil { fieldError = nil } }
                Button {
                    model.identifier = account.address
                    onForgotPassword()
                } label: {
                    Text("Forgot password?")
                        .gmFont(.captionMedium)
                        .foregroundColor(GM.primaryText)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
                .disabled(busy)
            }
            .transition(.opacity)
        }
    }

    private var removeButton: some View {
        Button(action: onRemove) {
            HStack(spacing: 6) {
                Image(systemName: "person.crop.circle.badge.minus")
                    .gmFont(15, .medium, maxSize: 26)
                    .accessibilityHidden(true)
                Text("Remove from this device", tableName: "WelcomeBack")
                    .gmFont(.captionMedium)
                    .multilineTextAlignment(.center)
            }
            .foregroundColor(GM.textSecondary)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .accessibilityIdentifier("welcome-back-remove")
    }

    // MARK: The sign-in ladder (§2.3)

    private func primaryTapped() {
        guard !busy, !offline else { return }
        if revealed {
            guard !password.isEmpty else { return }
            let secret = password
            busy = true
            fieldError = nil
            message = nil
            Task {
                await signIn(password: secret)
                busy = false
            }
        } else {
            runLadder()
        }
    }

    /// Rung 1 (passkey, when the hint says one exists) and rung 2 (a saved
    /// password for this address) go to the platform as ONE request, so the
    /// person sees one sheet. A cancel, an empty answer or a failure all fall
    /// to rung 3 silently.
    private func runLadder() {
        busy = true
        fieldError = nil
        message = nil
        Task {
            defer { busy = false }
            var challengeId: String?
            var passkeyOptions: PasskeyAuthenticationOptions?
            if account.hasPasskey,
               let response = try? await ApiClient.passkeyAuthOptions(), response.success,
               let id = response.challengeId.nilIfBlank(),
               let challenge = response.challengeBase64Url,
               var options = response.options {
                // The live route nests the challenge in `options`; an older
                // shape sends it at the top level only. Sign whichever came.
                if options.challenge.nilIfBlank() == nil { options.challenge = challenge }
                challengeId = id
                passkeyOptions = options
            }
            do {
                // One platform sheet for both rungs: a passkey (when the hint
                // says one exists) and a saved password for this address.
                let credential = try await PasskeyManager.welcomeBackCredential(passkeyOptions: passkeyOptions)
                switch credential {
                case let .passkey(assertion):
                    guard let challengeId else { reveal(); return }
                    await verifyPasskey(assertion, challengeId: challengeId)
                case let .password(user, secret):
                    // A saved password for a different account is not this one's.
                    guard RememberedAccounts.canonical(user) == account.id else { reveal(); return }
                    await signIn(password: secret)
                }
            } catch {
                reveal()
            }
        }
    }

    /// The same `/grapsee/auth/login` call and the same session write as
    /// SignInView.submit(); two-step accounts continue into the existing screen.
    private func signIn(password secret: String) async {
        do {
            let resp = try await ApiClient.signIn(identifier: account.address, password: secret)
            if resp.secondFactorRequired, resp.challengeId != nil {
                model.factorDestination = resp.maskedEmail
                model.identifier = account.address
                model.password = secret
                model.challengeId = resp.challengeId
                // The challenge's own expiry, as SignInView reads it.
                model.expiresAt = resp.expiresAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
                model.prefillIdentifier = nil
                onNeedSecondFactor()
                return
            }
            guard resp.success,
                  let token = resp.sessionToken ?? resp.accessToken ?? resp.token, !token.isEmpty else {
                reveal()
                message = String(localized: "Sign-in did not return a complete session. Please try again.", comment: "Sign-in error")
                return
            }
            let address = resp.account?.address.nilIfBlank() ?? account.address
            SessionStore.save(
                token: token,
                refreshToken: resp.refreshToken,
                address: address,
                name: resp.account?.name ?? "",
                expiresAtRaw: resp.expiresAtMillis
            )
            finish(address)
        } catch ApiError.emailVerificationRequired(let email) {
            model.clearSignInChallenge()
            model.verificationEmail = email
            onNeedEmailVerification()
        } catch let apiError as ApiError {
            if case let .http(status, code, serverMessage) = apiError {
                if status == 401 || code == "WRONG_PASSWORD" || code == "INVALID_CREDENTIALS" {
                    wrongPassword()
                } else if status == 403, code == nil {
                    // The login route's suspended answer carries no code
                    // (`auth.ts`): the account cannot sign in here any more.
                    onBlocked(serverMessage.nilIfBlank() ?? apiError.userMessage)
                } else {
                    reveal()
                    message = apiError.userMessage
                }
            } else {
                reveal()
                message = offline
                    ? String(localized: "You're offline. Connect to sign in.", table: "WelcomeBack")
                    : apiError.userMessage
            }
        } catch {
            reveal()
            message = apiMessage(error, fallback: String(localized: "Could not complete the account request. Try again.", comment: "Error message shown when the server gives no reason (sign in screen)"))
        }
    }

    /// The same verify call and session write as SignInView.passkeySignIn().
    /// Discoverable-only options mean the person may pick another account's
    /// passkey: that sign-in is accepted and announced (§2.3).
    private func verifyPasskey(_ assertion: PasskeyManager.Assertion, challengeId: String) async {
        do {
            let verified = try await ApiClient.passkeyAuthVerify(
                challengeId: challengeId,
                response: PasskeyAuthenticationResponseJson(
                    id: assertion.credentialId,
                    clientDataJSON: assertion.clientDataJSON,
                    authenticatorData: assertion.authenticatorData,
                    signature: assertion.signature,
                    userHandle: assertion.userHandle))
            guard verified.success, let token = verified.accessToken?.nilIfBlank() else {
                reveal()
                message = verified.error?.nilIfBlank()
                    ?? String(localized: "The passkey was not accepted. Use your password instead.", comment: "Passkey sign-in error")
                return
            }
            let address = verified.user?.email.nilIfBlank() ?? account.address
            SessionStore.save(
                token: token,
                refreshToken: verified.refreshToken,
                address: address,
                name: verified.user?.name ?? "",
                expiresAtRaw: nil)
            RememberedAccounts.setHasPasskey(true, for: address)
            if RememberedAccounts.canonical(address) != account.id {
                let notice = String(localized: "Signed in as \(address)", table: "WelcomeBack")
                WelcomeBackGate.signedInAsNotice = notice
                UIAccessibility.post(notification: .announcement, argument: notice)
            }
            finish(address)
        } catch {
            reveal()
            message = apiMessage(error, fallback: String(localized: "Could not sign in with the passkey. Try again.", comment: "Passkey sign-in error when the server gives no reason"))
        }
    }

    /// Success. The password field keeps its value on purpose: the view leaves
    /// the hierarchy next (RootView.enterMailbox), which is the moment the
    /// system offers to save the credential, and a cleared field gives it
    /// nothing to save. The view's state dies with it.
    private func finish(_ address: String) {
        GMHaptics.success()
        model.clearSignInChallenge()
        onSignedIn(address)
    }

    private func reveal() {
        guard !revealed else { return }
        withAnimation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion)) { revealed = true }
    }

    /// §2.3 / §2.7: keep the field, clear the value, shake (haptic only under
    /// Reduce Motion).
    private func wrongPassword() {
        reveal()
        password = ""
        fieldError = String(localized: "That password isn't right.", table: "WelcomeBack")
        GMHaptics.failure()
        if !reduceMotion {
            withAnimation(.linear(duration: 0.28)) { shakes += 1 }
        }
    }
}

// MARK: - Account chooser (§2.6)

@MainActor
private struct AccountChooserView: View {
    let accounts: [RememberedAccount]
    let photos: [String: Data]
    let namespace: Namespace.ID
    let sharedAvatar: Bool
    let onBack: (() -> Void)?
    let onPick: (RememberedAccount) -> Void
    let onRemove: (RememberedAccount) -> Void
    let onUseAnother: () -> Void

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    /// Rows grow with the text size; the avatar does not (§1.6.3).
    @ScaledMetric(relativeTo: .body) private var rowHeight: CGFloat = 72

    var body: some View {
        GeometryReader { geo in
            let short = verticalSizeClass == .compact || geo.size.height < 480
            let wide = !short && horizontalSizeClass == .regular && geo.size.width >= 700
            if wide {
                let half = geo.size.width * 0.5
                HStack(spacing: 0) {
                    Art.view("welcome", width: max(0, min(420, half - 48)))
                        .frame(width: half)
                    ScrollView {
                        content
                            .frame(width: max(0, min(440, half - 32)))
                            .frame(maxWidth: .infinity, minHeight: geo.size.height)
                    }
                    .frame(width: half)
                }
            } else {
                ScrollView {
                    content
                        .padding(.horizontal, 24)
                        .gmMaxWidth(GMLayout.form)
                }
            }
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 0) {
            AccountHeader(onBack: onBack)
            Text("Choose an account", tableName: "WelcomeBack")
                .gmFont(.h2)
                .foregroundColor(GM.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 16)
                .accessibilityAddTraits(.isHeader)
            Text("Signed out on this device", tableName: "WelcomeBack")
                .gmFont(.body)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 4)
            VStack(spacing: 0) {
                ForEach(accounts) { account in
                    row(account)
                    Rectangle()
                        .fill(GM.border)
                        .frame(height: 1)
                        .padding(.leading, 76)
                        .accessibilityHidden(true)
                }
                useAnotherRow
            }
            .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
            .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
            .padding(.top, 20)
            .padding(.bottom, 24)
        }
    }

    private func row(_ account: RememberedAccount) -> some View {
        let name = account.displayName.nilIfBlank() ?? AddressMask.localPart(account.address)
        let masked = AddressMask.mask(account.address)
        return HStack(spacing: 0) {
            Button { onPick(account) } label: {
                HStack(spacing: 12) {
                    FlexibleAvatar(account: account, photo: photos[account.id])
                        .modifier(SharedAvatar(id: account.id, namespace: namespace, enabled: sharedAvatar))
                        .frame(width: 48, height: 48)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: name)
                            .gmFont(.title)
                            .foregroundColor(GM.textPrimary)
                            .lineLimit(1)
                        Text(verbatim: masked)
                            .gmFont(.caption)
                            .foregroundColor(GM.textSecondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer(minLength: 8)
                }
                .padding(.leading, 16)
                .frame(minHeight: min(rowHeight, 132))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Continue as \(name), \(masked)", tableName: "WelcomeBack"))
            Menu {
                Button(role: .destructive) { onRemove(account) } label: {
                    Label(String(localized: "Remove from this device", table: "WelcomeBack"),
                          systemImage: "person.crop.circle.badge.minus")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .rotationEffect(.degrees(90))
                    .gmFont(16, .semibold, maxSize: 28)
                    .foregroundColor(GM.textSecondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .padding(.trailing, 8)
            .accessibilityLabel(Text("More options for \(name)", tableName: "WelcomeBack"))
        }
    }

    private var useAnotherRow: some View {
        Button(action: onUseAnother) {
            HStack(spacing: 12) {
                Image(systemName: "plus.circle")
                    .gmFont(22, maxSize: 34)
                    .foregroundColor(GM.primaryText)
                    .frame(width: 48, height: 48)
                    .accessibilityHidden(true)
                Text("Use another account", tableName: "WelcomeBack")
                    .gmFont(.bodyMedium)
                    .foregroundColor(GM.primaryText)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 60)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("welcome-back-use-another")
    }
}

// MARK: - Shared pieces

/// The existing account avatar (AccountAvatar, MailChrome.swift) sized by its
/// frame, so a matched-geometry move from 48 to 88 actually scales it.
private struct FlexibleAvatar: View {
    let account: RememberedAccount
    let photo: Data?

    var body: some View {
        GeometryReader { geo in
            let side = max(1, min(geo.size.width, geo.size.height))
            AccountAvatar(photo: photo, name: account.displayName, address: account.address, size: side)
                .frame(width: geo.size.width, height: geo.size.height)
        }
    }
}

private struct SharedAvatar: ViewModifier {
    let id: String
    let namespace: Namespace.ID
    let enabled: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if enabled {
            content.matchedGeometryEffect(id: "welcome-back-avatar-\(id)", in: namespace)
        } else {
            content
        }
    }
}

/// The 3 pt signature-gradient ring (§2.2, Theme.swift's shared angular
/// sweep, so the turning ring reads as motion).
private struct WelcomeBackRing: View {
    let spinning: Bool

    var body: some View {
        if spinning {
            // 1.2 s per turn, linear, only while signing in.
            TimelineView(.animation) { context in
                ring.rotationEffect(.degrees(
                    context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.2) / 1.2 * 360))
            }
        } else {
            ring
        }
    }

    private var ring: some View {
        Circle()
            .strokeBorder(Color.signatureAngularGradient, lineWidth: 3)
            .accessibilityHidden(true)
    }
}

private extension View {
    /// §2.7 entrance: scale 0.92 → 1 and fade for the avatar, fade + rise 8
    /// for the rest, staggered; a 150 ms crossfade under Reduce Motion.
    func entrance(visible: Bool, scaled: Bool, delay: Double, reduced: Bool) -> some View {
        self
            .opacity(visible ? 1 : 0)
            .scaleEffect(scaled && !visible && !reduced ? 0.92 : 1)
            .offset(y: !scaled && !visible && !reduced ? 8 : 0)
            .animation(reduced ? .easeInOut(duration: GMMotion.short)
                               : GMMotion.emphasizedDecelerate(GMMotion.long).delay(delay),
                       value: visible)
    }
}

/// §2.2 first-name rule.
private enum WelcomeBackNames {
    static func firstName(_ account: RememberedAccount) -> String? {
        let given = account.firstName.trimmingCharacters(in: .whitespacesAndNewlines)
        let candidate = given.isEmpty
            ? account.displayName.split(whereSeparator: \.isWhitespace).first.map(String.init)
            : given
        guard let candidate, !candidate.isEmpty, candidate.count <= 16 else { return nil }
        return candidate
    }
}

/// §2.2 `maskAddress`, kept private to this screen (the shared helper is
/// Workstream A's Avatar.swift; this one only has to agree with the spec).
private enum AddressMask {
    private static let bullets = "\u{2022}\u{2022}\u{2022}"

    /// Split at the LAST `@`.
    static func split(_ address: String) -> (local: String, domain: String)? {
        guard let at = address.lastIndex(of: "@") else { return nil }
        return (String(address[..<at]), String(address[address.index(after: at)...]))
    }

    static func localPart(_ address: String) -> String {
        split(address)?.local ?? address
    }

    /// n ≤ 2: first + •••; 3–4: first + ••• + last; ≥ 5: first two + ••• +
    /// last. Graphemes, not bytes. Domain in full.
    /// `olivier@grapmail.com` → `ol•••r@grapmail.com`.
    static func mask(_ address: String) -> String {
        guard let (local, domain) = split(address) else { return address }
        let g = Array(local)
        let n = g.count
        let masked: String
        switch n {
        case 0: masked = bullets
        case 1...2: masked = String(g[0]) + bullets
        case 3...4: masked = String(g[0]) + bullets + String(g[n - 1])
        default: masked = String(g[0]) + String(g[1]) + bullets + String(g[n - 1])
        }
        return masked + "@" + domain
    }

    /// "Address beginning {spelled}, ending {last} at {domain}" — the full
    /// address is never spoken on a screen whose point is privacy.
    static func spokenLabel(_ address: String) -> Text {
        guard let (local, domain) = split(address) else { return Text(verbatim: address) }
        let g = Array(local)
        let n = g.count
        let beginning = n >= 5 ? String(g[0...1]) : (n > 0 ? String(g[0]) : "")
        let beginText = Text(verbatim: beginning).speechSpellsOutCharacters()
        if n >= 3 {
            let endText = Text(verbatim: String(g[n - 1])).speechSpellsOutCharacters()
            return Text("Address beginning \(beginText), ending \(endText) at \(domain)", tableName: "WelcomeBack")
        }
        return Text("Address beginning \(beginText) at \(domain)", tableName: "WelcomeBack")
    }
}

// MARK: - Sign-out confirmation (§2.4)

/// The sign-out sheet with the "Also remove from this device" row, default
/// off. Off keeps the remembered record (name, photo, address — never a
/// password), so the next sign-in here can say "Continue as …". On forgets it
/// and its cached photo. Either way the session itself always ends.
struct SignOutConfirmSheet: View {
    let address: String
    let onConfirm: (_ removeFromDevice: Bool) -> Void
    let onCancel: () -> Void

    @State private var removeFromDevice = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Sign out of \(address)?", tableName: "WelcomeBack")
                    .gmFont(.h2)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                Toggle(isOn: $removeFromDevice) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Also remove from this device", tableName: "WelcomeBack")
                            .gmFont(.bodyMedium)
                            .foregroundColor(GM.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                        Text("You'll need to type your address next time.", tableName: "WelcomeBack")
                            .gmFont(.caption)
                            .foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                // §1.2: switches take `primaryText` (#8EA8FF on dark).
                .tint(GM.primaryText)
                .frame(minHeight: 44)
                .accessibilityIdentifier("sign-out-remove-from-device")
                Button { onConfirm(removeFromDevice) } label: {
                    Text("Sign out")
                        .gmFont(.bodyMedium)
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        // `errorFill` (#D92D2D in both themes): white on the
                        // dark `error` ink (#F87171) is under 3:1.
                        .background(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).fill(GM.errorFill))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("sign-out-confirm")
                Button(action: onCancel) {
                    Text("Cancel")
                        .gmFont(.bodyMedium)
                        .foregroundColor(GM.primaryText)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("sign-out-cancel")
            }
            .padding(24)
            .gmMaxWidth(GMLayout.form)
        }
        .background(GM.bg.ignoresSafeArea())
        .presentationDetents([.medium, .large])
    }
}

// MARK: - Platform credential (§2.3 rungs 1–2)

extension PasskeyManager {
    /// What the platform sheet handed back for welcome-back.
    enum WelcomeBackCredential {
        /// Rung 1: a passkey assertion, still to be verified by the server.
        case passkey(Assertion)
        /// Rung 2: a password saved in iCloud Keychain / a password manager.
        /// The caller checks `user` against the chosen address.
        case password(user: String, password: String)
    }

    /// Rungs 1 and 2 as ONE request, so the person sees one sheet: the passkey
    /// assertion when `passkeyOptions` is given (discoverable-only, no allow
    /// list), plus `ASAuthorizationPasswordProvider` for a saved password.
    /// `.preferImmediatelyAvailableCredentials` means nothing is shown when the
    /// device holds neither; that, a cancel, or any failure throws, and the
    /// caller falls to rung 3 (the password field) without showing an error.
    /// Nothing here signs anyone in: the caller still sends the passkey or
    /// password to the server's normal sign-in route.
    @MainActor
    static func welcomeBackCredential(passkeyOptions: PasskeyAuthenticationOptions?) async throws -> WelcomeBackCredential {
        var requests: [ASAuthorizationRequest] = []
        if let options = passkeyOptions, let challenge = options.challenge.welcomeBackBase64UrlData() {
            let rpId = options.rpId?.nilIfBlank() ?? relyingPartyIdentifier
            let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: rpId)
            requests.append(provider.createCredentialAssertionRequest(challenge: challenge))
        }
        requests.append(ASAuthorizationPasswordProvider().createRequest())
        let session = WelcomeBackCredentialSession()
        let authorization = try await session.perform(requests)
        if let credential = authorization.credential as? ASAuthorizationPlatformPublicKeyCredentialAssertion {
            let handle = credential.userID.isEmpty ? nil : credential.userID.welcomeBackBase64Url()
            return .passkey(Assertion(
                credentialId: credential.credentialID.welcomeBackBase64Url(),
                clientDataJSON: credential.rawClientDataJSON.welcomeBackBase64Url(),
                authenticatorData: credential.rawAuthenticatorData.welcomeBackBase64Url(),
                signature: credential.signature.welcomeBackBase64Url(),
                userHandle: handle
            ))
        }
        if let credential = authorization.credential as? ASPasswordCredential {
            return .password(user: credential.user, password: credential.password)
        }
        throw PasskeyError.failed(String(localized: "Passkey sign-in failed. Try again.", comment: "Passkey error"))
    }
}

/// One ASAuthorizationController for welcome-back, from performRequests to the
/// single delegate answer — the same shape as PasskeyManager's own session,
/// kept separate because that one is private to PasskeyManager.swift.
private final class WelcomeBackCredentialSession: NSObject, ASAuthorizationControllerDelegate,
                                                  ASAuthorizationControllerPresentationContextProviding {
    private var continuation: CheckedContinuation<ASAuthorization, Error>?
    private var controller: ASAuthorizationController?

    @MainActor
    func perform(_ requests: [ASAuthorizationRequest]) async throws -> ASAuthorization {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            let controller = ASAuthorizationController(authorizationRequests: requests)
            controller.delegate = self
            controller.presentationContextProvider = self
            self.controller = controller
            controller.performRequests(options: .preferImmediatelyAvailableCredentials)
        }
    }

    func authorizationController(controller: ASAuthorizationController,
                                 didCompleteWithAuthorization authorization: ASAuthorization) {
        continuation?.resume(returning: authorization)
        continuation = nil
        self.controller = nil
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        let mapped: PasskeyManager.PasskeyError
        if let authError = error as? ASAuthorizationError, authError.code == .canceled {
            mapped = .cancelled
        } else {
            mapped = .failed(String(localized: "Passkey sign-in failed. Try again.", comment: "Passkey error"))
        }
        continuation?.resume(throwing: mapped)
        continuation = nil
        self.controller = nil
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }
}

// base64url (RFC 4648, unpadded) — the WebAuthn wire encoding. Named apart from
// PasskeyManager.swift's file-private helpers so the two can never collide.
private extension Data {
    func welcomeBackBase64Url() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

private extension String {
    func welcomeBackBase64UrlData() -> Data? {
        var normalized = replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder > 0 {
            normalized.append(String(repeating: "=", count: 4 - remainder))
        }
        return Data(base64Encoded: normalized)
    }
}
