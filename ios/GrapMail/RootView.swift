import SwiftUI
import UIKit
// `NotificationCenter.publisher(for:)` — the session-expiry signal below — is a
// Combine API. SwiftUI does not re-export it, so the publisher is invisible
// without this.
import Combine

@MainActor
private final class SignedInModels: ObservableObject {
    private(set) var mail = MailboxModel()
    @Published private(set) var account = ProfileModel()
    private var accountChanges: AnyCancellable?

    init() { observeAccount() }

    private func observeAccount() {
        accountChanges = account.objectWillChange.sink { [weak self] in
            self?.objectWillChange.send()
        }
    }

    func reset() {
        mail = MailboxModel()
        account = ProfileModel()
        observeAccount()
    }
}

/// The root navigation for the account sector: welcome → wizard (5 steps) →
/// human verification → account created, plus sign-in, two-step verification
/// and the whole recovery/security arc beside it.
@MainActor
struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = SignUpModel()
    @StateObject private var recovery = RecoveryModel()
    /// The home-screen shortcut's parked request. Observed, not owned: it is
    /// filled by the app delegate before any view exists, so the singleton is
    /// its home and this view is one of its readers.
    @ObservedObject private var shortcuts = ShortcutRouter.shared

    enum Route: Hashable {
        case welcome, signIn
        case twoFactor, backupCode, emailVerification
        case forgotPassword, recoveryCode, newPassword, resetDone, recoveryPending
        // The Forgot Password screen's signature path: this device proves
        // custody with its enrolled key instead of a texted code.
        case trustedRecovery
        case findAddress, addressFound
        case phoneVerify, phoneVerified
        // Recovery email: from Account Security, and the optional step right
        // after sign-up (carries the new account so the wizard can finish).
        case recoveryEmail
        case recoveryEmailSignup(address: String, name: String)
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
        // The device-security band: trusted-device list, and the recovery
        // codes the all-devices-lost path spends. Both hang off the hub like
        // the sessions and activity screens do. Passkeys sit on the same band:
        // the platform credentials the sign-in and the hub's list share.
        case secTrustedDevices, secRecoveryCodes, secPasskeys
        case created(address: String, name: String)
        case createdAlt(address: String, name: String)
        case onboarding(address: String, name: String)
        case home
        // `zoomSource` is the list row's message id when the list's tap pushed
        // the reader: the iOS 18 zoom transition grows the reader out of that
        // row. nil -- links, notifications, stepping -- is the plain push.
        case mailThread(id: String, isThread: Bool, focus: String? = nil, more: Bool = false,
                        zoomSource: String? = nil)
        // Compose carries an optional reply target and mode: empty reply opens
        // blank, "reply|all|forward" opens prepared from the full server row.
        case mailCompose(reply: String, mode: String, recipient: String = "")
        case mailLabels, mailLabelCreate(id: String)
        case mailContacts, mailContactDetail(address: String)
        case mailFiles, mailAttachment(id: String)
        case mailSettings, mailScreener, mailScheduled
        // ── account-record band ──────────────────────────────────────────────
        // The web client's `/mail/notes`, `/mail/tasks`, `/mail/settings`,
        // `/mail/account/{profile,privacy,addresses,connections,support}` —
        // one profile document, eight screens over it. They hang off
        // mailSettings the way the security screens hang off .security: every
        // one has a parent, so a deep link can never strand somebody on a
        // child with no way back.
        case mailNotes, mailTasks, mailPrefs, mailAddresses, mailConnections
        // Connected mailboxes: Outlook (OAuth) and IMAP accounts synced in.
        case mailProviders
        // Per-scope notification preferences (mail / security / account),
        // edited on the account-wide preference row the push backend reads.
        case mailNotifPrefs
        // The per-account mute: one signed-in account's alerts silenced on
        // this device. Hangs off mailSettings like the per-scope row beside it.
        case perAccountNotifications
        case accountProfile, accountPrivacy, mailSupport
        // The web's `/mail/account/connections/<product>`: one linked app in
        // full. The product rides as a route payload, mirroring the encoded
        // path segment on the web and Android.
        case accountConnectionDetail(product: String)
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
        // MARK: Round 8 — security center
        // The Device & Security Center (SecurityCenterView.swift) and the
        // "This wasn't me" arc (NotMeFlow.swift): secured screen, change
        // password in required mode (back asks first), recovery review. All
        // dispatched by `securityCenterDestination`.
        case secCenter
        case secNotMeDone(device: String)
        case secRecoveryReview
        case secPasswordRequired
        // MARK: Round 8 — welcome back
        // The personal welcome-back sign-in for one remembered, signed-out
        // account, and the chooser for two or more (WelcomeBackView.swift).
        // Both open the same screen; `signInRoute()` picks between them and
        // the classic `.signIn`. Dispatched by `welcomeBackDestination`.
        case welcomeBack(address: String)
        case accountChooser
    }

    /// One mailbox for the whole signed-in stack. The list, the thread, compose
    /// and the secondary screens all read and write this, so starring a message
    /// in the list is visible in the thread that opens next, and a body read in
    /// the thread is not fetched again by the Files screen.
    // Destinations observe the mailbox directly. Owning it here without
    // forwarding its publications keeps draft edits out of navigation updates.
    @StateObject private var signedInModels = SignedInModels()
    private var mail: MailboxModel { signedInModels.mail }
    private var account: ProfileModel { signedInModels.account }

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
    @State private var path: [Route] = []
    /// The app-lock cover (AppLock.swift). Owned at the stack so every
    /// destination behind it is one gate.
    @ObservedObject private var appLock = AppLock.shared
    /// A tapped link (DeepLinks.swift) -- grapmail://thread|message|scheduled|compose,
    /// https://grapsee.com/mail/thread/<id>, or mailto: -- parked until three
    /// things are true at once: the launch restore has decided the stack, a
    /// session exists, and the app lock is not in front. Signed out it waits
    /// through sign-in (`enterMailbox` opens it); locked it waits for the
    /// unlock (`.onChange(of: appLock.locked)`). Cleared the moment it opens.
    /// It replaces the widget's old `pendingScheduled` flag: grapmail://scheduled
    /// is one of the links now, parked and opened by the same rule.
    @State private var pendingLink: DeepLink?
    @State private var restoredSession = false
    @State private var renewingSession = false
    /// The ten codes live in SecurityFlow.backupCodes, not a view @State, so
    /// SessionStore.clear() → SecurityFlow.reset() wipes them on sign-out. A
    /// plaintext recovery code left in the root view's memory after the account
    /// is signed out survives `path.removeAll()` and is readable by whoever
    /// signs in next.

    // MARK: Round 8 — welcome back (state)
    /// The sign-out sheet's account, while the sheet is up.
    @State private var signOutPrompt: SignOutPrompt?
    /// "Signed in as {address}" after a passkey picked another account (§2.3).
    @State private var welcomeBackToast: String?
    /// The server's reason when a welcome-back account turned out to be
    /// blocked, shown once on the classic sign-in form it falls back to.
    @State private var signInNotice: String?

    private func backToSignIn() {
        recovery.clearSignInChallenge()
        if path.contains(.signIn) {
            while let last = path.last, last != .signIn { path.removeLast() }
        } else if recovery.prefillIdentifier == nil,
                  let index = path.lastIndex(where: { isWelcomeBackRoute($0) }) {
            // Came from welcome-back (two-step "Back to sign in"): return to
            // it rather than to a blank form. A prefilled address (reset done,
            // address found) still lands on the classic form below.
            path.removeSubrange(path.index(after: index)...)
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
            // The launch content and the destination switch live in
            // `rootContent` and `destination` below. The body of this view had
            // grown past what the type-checker will resolve in one expression
            // (CI named it "unable to type-check this expression in reasonable
            // time"), and the repo has learned twice that the fix is the same:
            // break the builder into functions. The Group the switch sat in
            // erased the branch types already, so extracting changes nothing
            // a person can see.
            rootContent
                .navigationDestination(for: Route.self) { route in
                    destination(route)
                }
            // The bar rule lives at the stack, not at the destinations: the
            // screens draw their own headers, and `.toolbar(.hidden)` applied
            // only inside `destination` left the ROOT — the welcome screen and
            // every snapshot-host frame — under a live bar the routed screens
            // had dropped. One empty strip at the top of a 90-frame design
            // review is the difference between "the app has no system chrome"
            // and a photograph set that contradicts itself screen by screen.
            .toolbar(.hidden, for: .navigationBar)
            .navigationBarBackButtonHidden(true)
            // -- App lock: one cover over the whole signed-in stack (AppLock.swift).
            // Scene changes arm it; a cold launch checks once the first frame exists.
            .overlay {
                if appLock.locked { AppLockOverlay(lock: appLock) }
            }
            .onChange(of: scenePhase) { phase in
                switch phase {
                case .background: appLock.noteBackground()
                case .active: appLock.checkOnActive()
                default: break
                }
            }
            .onAppear { appLock.checkOnActive() }
            // -- Deep links. The custom scheme (grapmail://thread/<id>, the
            // widget's and Live Activity's grapmail://scheduled, ...) arrives
            // here; so would mailto: if this app is ever the default mail app.
            .onOpenURL { url in handleIncomingURL(url) }
            // -- Universal Links: https://grapsee.com/mail/thread/<id>. iOS
            // delivers these as a browsing activity, never through onOpenURL,
            // and only while the associated-domains entitlement is signed in
            // AND grapsee.com serves the association file
            // (well-known/README.md). Same parser, same parking rule.
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in handleUserActivity(activity) }
            .task(id: scenePhase == .active && path.contains(.home) ? SessionStore.generation : nil) {
                if scenePhase == .active, path.contains(.home) { await account.watchAvatar() }
            }
            // The gateway's access token lives one hour; the refresh token is
            // good for thirty days. A stored pair opens the locally-restored
            // mailbox immediately; renewal must not sit between app launch and
            // the first rendered frame. The mailbox can paint its actor cache
            // while the single-flight renewal runs, and a genuinely dead token
            // still reaches the `.gmSessionExpired` handler below on the first
            // refused authenticated request.
            .task {
                guard !restoredSession, SecuritySnapshot.requested() == nil else { return }
                // Round 8 — welcome back: the install-marker wipe and the
                // 90-day prune run before anything reads the remembered list.
                RememberedAccounts.bootstrap()
                let generation = SessionStore.generation
                let session = await SessionStore.launchSession()
                guard !Task.isCancelled, generation == SessionStore.generation else { return }
                restoredSession = true
                if path.isEmpty {
                    if session.stored { path = [.home] }
                    else if !SessionStore.accounts().isEmpty { showRemainingAccount(or: []) }
                    else {
                        // Signed out with a remembered account: "Continue as …".
                        let landing = signInRoute()
                        if landing != .signIn { path = [landing] }
                    }
                }
                if session.stored && !session.unexpired {
                    renewingSession = true
                    let renewal = await ApiClient.renewPairStatus()
                    renewingSession = false
                    guard !Task.isCancelled, generation == SessionStore.generation else { return }
                    if shortcuts.pendingCompose, renewal == .renewed { openComposeShortcut() }
                    if shortcuts.pendingCompose, renewal == .rejected, !(path.last.map { isSignInSurface($0) } ?? false) {
                        path = [signInRoute()]
                    }
                    // A transient unavailable result keeps Home and the shortcut
                    // pending. The normal authenticated retry/expiry path decides
                    // later; a network outage is not evidence of bad credentials.
                } else if shortcuts.pendingCompose {
                    openComposeShortcut()
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .gmSessionChanged)) { _ in
                guard shortcuts.pendingCompose, restoredSession, SessionStore.hasValidSession else { return }
                openComposeShortcut()
            }
            // A dead token is discovered by the server, not by us: the first
            // authenticated call that comes back 401 clears the Keychain and
            // posts this. Without the handler the app stays on a signed-in
            // screen whose every request fails, and the person has no way back
            // in short of reinstalling. Anonymous 401s (wrong password at
            // sign-in) never reach here — only a rejected bearer does.
            .onReceive(NotificationCenter.default.publisher(for: .gmSessionExpired)) { _ in
                restoredSession = true
                showRemainingAccount(or: [signInRoute()])
            }
            // A security push's "Secure account" action, or a plain tap on the
            // alert banner (SecurityPush.handle). The hub refetches on entry,
            // so the alert that brought the person here is answered by the
            // account's live state, not a copy.
            .onReceive(NotificationCenter.default.publisher(for: .gmOpenAccountSecurity)) { _ in
                openSecurityHub()
            }
            // Round 8 — security center: the mailbox's §3.6 banner and §4.3
            // nudge card (SafetyNudgeCard.swift) ask for the Center or the
            // required-mode password screen through this one signal.
            .onReceive(NotificationCenter.default.publisher(for: .gmOpenSecurityCenter)) { note in
                openSecurityCenter(note.object as? String)
            }
            // A long-press on the icon that says "Compose mail" has to land in
            // the composer. The delegate parks the request until a stack exists
            // that can act on it, and it is consumed here once -- a pending
            // request replayed on the next launch would open a composer nobody
            // asked for. Signed out, the honest route to writing a mail is
            // sign-in, not a composer whose every send would fail.
            .onChange(of: shortcuts.pendingMail) { _ in openNotificationMail() }
            .onChange(of: restoredSession) { _ in
                openNotificationMail()
                openPendingLink()
                routeSignedOutLink()
            }
            .onChange(of: renewingSession) { _ in
                openNotificationMail()
                openPendingLink()
            }
            // The unlock is the moment a parked notification tap or link may
            // finally open; nothing behind the cover moves before it.
            .onChange(of: appLock.locked) { _ in
                openNotificationMail()
                openPendingLink()
            }
            .onChange(of: shortcuts.pendingCompose) { pending in
                guard pending, restoredSession, !renewingSession else { return }
                openComposeShortcut()
            }
            // The background poll is scheduled whenever this app goes away:
            // that is the moment the next read has to be asked for, and the
            // system owns the interval from here.
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
                MailPoll.scheduleNext()
            }
            // Round 8 — welcome back: the sign-out sheet ("Also remove from
            // this device") and the "Signed in as …" toast. One modifier so
            // this body stays within what the type-checker resolves.
            .modifier(WelcomeBackHost(prompt: $signOutPrompt, toast: welcomeBackToast) { remove in
                performSignOut(removeFromDevice: remove)
            })
        }
    }

    /// Every URL the scene hands over: the custom scheme (the widget and the
    /// send-later Live Activity tap grapmail://scheduled through here, a
    /// shared thread link is grapmail://thread/<id>) and mailto:. Anything
    /// DeepLink.parse does not recognise exactly -- an id that is not the
    /// server's uuid included -- does nothing at all.
    private func handleIncomingURL(_ url: URL) {
        guard let link = DeepLink.parse(url) else { return }
        receive(link)
    }

    /// A Universal Link's browsing activity, parsed by the same rules.
    private func handleUserActivity(_ activity: NSUserActivity) {
        guard let link = DeepLink.parse(activity) else { return }
        receive(link)
    }

    /// Park, then try. A link handed over while the scene is still coming
    /// forward can beat the `.active` phase change that re-arms the lock, so
    /// the lock is asked first -- otherwise the thread would be pushed in the
    /// instant before the cover goes up and load (and mark itself read)
    /// behind it. Already active means the scene hook has decided.
    private func receive(_ link: DeepLink) {
        pendingLink = link
        if scenePhase != .active { appLock.checkOnActive() }
        openPendingLink()
        routeSignedOutLink()
    }

    /// Opens the parked link once nothing stands in front of it. Every exit
    /// that is not "open it now" leaves it parked for the next trigger:
    /// the restore finishing, a renewal finishing, the unlock, a sign-in.
    private func openPendingLink() {
        // A stored token, not `hasValidSession`: an hour-old access token on
        // a warm app is still a session -- the reader's first request renews
        // it through the single-flight path, as it does for a notification
        // tap -- and nothing would ever retry a link parked on "expired".
        guard let link = pendingLink, restoredSession, !renewingSession, !appLock.locked,
              SessionStore.token != nil else { return }
        pendingLink = nil
        if path.last != .home { path = [.home] }
        switch link {
        case let .thread(id):
            // Two-pane (regular width): the link selects the thread in the
            // reader column instead of pushing a reader over the list.
            if mailboxSizeClass == .regular { inlineMail = MailPaneSelection(id: id, isThread: true) }
            else { path.append(.mailThread(id: id, isThread: true)) }
        case let .message(id):
            // The message route: the thread route answers a message id 404.
            if mailboxSizeClass == .regular { inlineMail = MailPaneSelection(id: id, isThread: false) }
            else { path.append(.mailThread(id: id, isThread: false)) }
        case .scheduled:
            path.append(.mailScheduled)
        case let .compose(recipient):
            // The composer's existing prefilled-recipient slot -- the same one
            // the contact screen's "Write" fills. Nothing else is carried.
            path.append(.mailCompose(reply: "", mode: "reply", recipient: recipient))
        case .inbox:
            // The widget's unread count: the mailbox home (the line above has
            // already popped everything pushed over it), on the Inbox, with
            // nothing open in the reader column and nothing picked.
            inlineMail = nil
            mail.clearSelection()
            mail.open(.inbox)
        }
    }

    /// Signed out with a link waiting: the honest route is sign-in, the same
    /// answer the compose shortcut gives. The link stays parked and
    /// `enterMailbox` opens it once the person has proved the account.
    private func routeSignedOutLink() {
        guard pendingLink != nil, restoredSession, !renewingSession,
              SessionStore.token == nil, SecuritySnapshot.requested() == nil,
              !path.contains(where: { isSignInSurface($0) }) else { return }
        path = [signInRoute()]
    }

    private func openNotificationMail() {
        // The same door as a deep link: a tap that names a thread opens it on
        // the same route, and never behind the app lock. Coming forward from
        // a notification can beat the `.active` hook, so the lock is asked
        // first; a locked app keeps the tap parked until the unlock's
        // onChange calls back in here.
        if shortcuts.pendingMail != nil, scenePhase != .active { appLock.checkOnActive() }
        guard restoredSession, !renewingSession, !appLock.locked, let target = shortcuts.pendingMail else { return }
        shortcuts.pendingMail = nil
        if target.account.caseInsensitiveCompare(SessionStore.address ?? "") != .orderedSame {
            guard SessionStore.switchTo(target.account) else { return }
            enterMailbox()
        }
        guard SessionStore.token != nil else { return }
        if mailboxSizeClass == .regular {
            inlineMail = MailPaneSelection(id: target.threadId ?? target.messageId, isThread: target.threadId != nil,
                                           focus: target.messageId, more: target.more)
            path = [.home]
        } else {
            path = [.home, .mailThread(id: target.threadId ?? target.messageId, isThread: target.threadId != nil, focus: target.messageId, more: target.more)]
        }
        if target.reply { path.append(.mailCompose(reply: target.messageId, mode: "reply")) }
    }

    private func openComposeShortcut() {
        shortcuts.consume()
        guard SessionStore.hasValidSession else {
            path = [signInRoute()]
            return
        }
        if path.last != .home { path = [.home] }
        path.append(.mailCompose(reply: "", mode: "reply"))
    }

    /// The security push's landing: the account-security hub. Signed out, the
    /// honest route is sign-in — the hub's every call would 401. The launch
    /// restore may still be deciding the stack, so like the other openers this
    /// waits for `restoredSession` rather than racing it.
    private func openSecurityHub() {
        guard restoredSession, !renewingSession else { return }
        guard SessionStore.hasValidSession else {
            path = [signInRoute()]
            return
        }
        if path.contains(.security) {
            backToSecurity()
        } else {
            if path.last != .home { path = [.home] }
            path.append(.security)
        }
    }

    /// The launch screen: the test-only snapshot host when the walk named
    /// one, the signed-in-or-welcome root otherwise.
    @ViewBuilder private var rootContent: some View {
        Group {
            // Test-only hook: the UI test names one post-write confirmation
            // screen at launch and the app renders it instead of the flow.
            // Those screens only exist after a write the walk must not
            // perform, so they are otherwise unreachable. No real launch
            // ever carries the argument, and nothing here fetches or submits.
            if let snapshot = SecuritySnapshot.requested() {
                Group {
                    // The onboarding pages are reachable in the real flow only
                    // after a signup write, so the walk names one here:
                    // `onboarding-1` .. `onboarding-5`, one scene each.
                    if snapshot.hasPrefix("onboarding-") {
                        OnboardingView(
                            onFinish: {},
                            initialScene: (Int(snapshot.dropFirst("onboarding-".count)) ?? 1) - 1
                        )
                    } else if snapshot.hasPrefix("mail-") || snapshot.hasPrefix("account-")
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
            } else if !restoredSession {
                GM.bg.ignoresSafeArea()
            } else {
                WelcomeView(
                    onCreateAccount: { path.append(.stepName) },
                    onSignIn: { path.append(signInRoute()) }
                )
            }
        }
    }

    /// Routed screens, dispatched by sector. The whole map lived in one
    /// switch; the type-checker gave up on it twice (CI: "unable to
    /// type-check this expression in reasonable time" at the body, then at
    /// the single `destination`), so each sector is its own ViewBuilder
    /// function now. Every case below is verbatim what it was — same
    /// closures, same path edits.
    @ViewBuilder private func destination(_ route: Route) -> some View {
        Group {
            switch route {
            case .welcome, .signIn, .twoFactor, .backupCode, .emailVerification, .forgotPassword,
                 .recoveryCode, .newPassword, .resetDone, .recoveryPending, .trustedRecovery,
                 .findAddress, .addressFound, .phoneVerify, .phoneVerified:
                authDestination(route)
            case .security, .secEnable, .secConfirmPassword, .secVerifyPhone, .secEnabled,
                 .secDisable, .secDisabled, .secBackup, .secBackupSaved, .secPassword,
                 .secPasswordDone, .secPasswordAfter, .secPasswordUpdated, .secSignOutAll,
                 .secSignOutAllDone, .secSessions, .secRevoke, .secRevoked, .secRevokeAlert,
                 .secRevokedAlert, .secActivity, .secAlert, .secSecure,
                 .secTrustedDevices, .secRecoveryCodes, .secPasskeys, .recoveryEmail:
                securityDestination(route)
            // Round 8 — security center.
            case .secCenter, .secNotMeDone, .secRecoveryReview, .secPasswordRequired:
                securityCenterDestination(route)
            // Round 8 — welcome back.
            case .welcomeBack, .accountChooser:
                welcomeBackDestination(route)
            // `.onboarding` belongs here too: it was missing from this list, so
            // the walkthrough pushed after sign-up fell through to the
            // signed-in switch and drew a blank screen with no way forward.
            case .stepName, .stepInfo, .stepAddress, .stepPassword, .stepTerms,
                 .humanVerify, .created, .createdAlt, .recoveryEmailSignup,
                 .onboarding:
                wizardDestination(route)
            default:
                // Everything signed-in: mailbox, account band, encryption
                // band, subscriptions. A new mail-band route needs no new
                // dispatch line, and `mailDestination`'s switch keeps its
                // own default, so the compiler stays happy either side.
                mailDestination(route)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
    }

    /// Sign-in, recovery and the phone arc.
    @ViewBuilder private func authDestination(_ route: Route) -> some View {
        switch route {

                case .welcome:
                    WelcomeView(
                        onCreateAccount: { path.append(.stepName) },
                        onSignIn: { path.append(signInRoute()) }
                    )
                case .signIn:
                    SignInView(
                        model: recovery,
                        onSignedIn: { _ in enterMailbox() },
                        onBack: { path.removeLast() },
                        onSignUp: { path.append(.stepName) },
                        onForgotPassword: { path.append(.forgotPassword) },
                        onNeedSecondFactor: { path.append(.twoFactor) },
                        onNeedEmailVerification: { path.append(.emailVerification) },
                        notice: signInNotice
                    )
                    // The notice is shown once: SignInView copies it on appear.
                    .onAppear { DispatchQueue.main.async { signInNotice = nil } }
                case .emailVerification:
                    EmailVerificationView(model: recovery,
                                          onSignedIn: { _ in enterMailbox() },
                                          onBack: { backToSignIn() })
                case .twoFactor:
                    TwoFactorView(
                        model: recovery,
                        onSignedIn: { _ in enterMailbox() },
                        onBack: { path.removeLast() },
                        onBackToSignIn: { backToSignIn() },
                        onUseBackupCode: { path.append(.backupCode) }
                    )
                case .backupCode:
                    BackupCodeView(
                        model: recovery,
                        onSignedIn: { _ in enterMailbox() },
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
                        onSupport: { openSupport() },
                        onTrustedDevice: { path.append(.trustedRecovery) }
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
                case .trustedRecovery:
                    TrustedDeviceRecoveryView(
                        model: recovery,
                        onBack: { path.removeLast() },
                        onResetDone: {
                            // The reset is spent and every session is closed,
                            // so the trusted route is spent too: back must not
                            // land on a grant screen whose proof is used up.
                            path.removeAll { $0 == .trustedRecovery }
                            path.append(.resetDone)
                        }
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
            default:
                // Unreachable while the dispatcher's lists stay exact, and
                // safer than an EmptyView: a stray route lands in the
                // signed-in switch, whose own default draws nothing quietly.
                mailDestination(route)
        }
    }

    /// The security sector's twenty-three screens.
    @ViewBuilder private func securityDestination(_ route: Route) -> some View {
        switch route {

                case .security:
                    // Round 8: with `security_center` on, the Trusted Devices,
                    // Active Sessions and Recent Activity rows open the Device &
                    // Security Center (§3) — the old screens stay reachable by
                    // their own routes — and the hub gains the safety header.
                    AccountSecurityView(
                        onBack: { path.removeLast() },
                        onTwoFactor: { path.append(.secEnable) },
                        onDisableTwoFactor: { path.append(.secDisable) },
                        onPhone: { path.append(.phoneVerify) },
                        onSessions: { path.append(SecFeature.enabled ? .secCenter : .secSessions) },
                        onActivity: { path.append(SecFeature.enabled ? .secCenter : .secActivity) },
                        onBackupCodes: { path.append(.secBackup) },
                        onPassword: { path.append(.secPassword) },
                        onSignOutAll: { path.append(.secSignOutAll) },
                        onSupport: { openSupport() },
                        onRecoveryCodes: { path.append(.secRecoveryCodes) },
                        onTrustedDevices: { path.append(SecFeature.enabled ? .secCenter : .secTrustedDevices) },
                        onPasskeys: { path.append(.secPasskeys) },
                        onRecoveryEmail: { path.append(.recoveryEmail) },
                        onSecurityCenter: SecFeature.enabled ? { path.append(.secCenter) } : nil,
                        onSafetyItem: { id, done in openSafetyItem(id, done: done) }
                    )
                case .recoveryEmail:
                    RecoveryEmailView(
                        onBack: { path.removeLast() },
                        onDone: { path.removeLast() }
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
                    // Step 05 of turning two-step on. Codes go to the verified
                    // recovery email (GrapMail sends no SMS); the route name is
                    // kept so existing links and the walk still land here.
                    TwoFactorEmailView(
                        onBack: { path.removeLast() },
                        onAddRecoveryEmail: { path.append(.recoveryEmail) },
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
                case .secTrustedDevices:
                    TrustedDevicesView(onBack: { path.removeLast() })
                case .secPasskeys:
                    PasskeysView(onBack: { path.removeLast() })
                case .secRecoveryCodes:
                    // Done returns to the hub, whichever door opened this
                    // screen — the codes screen is a child of it, like the
                    // 2FA confirmation screens above.
                    RecoveryCodesView(onBack: { backToSecurity() })
            default:
                mailDestination(route)
        }
    }

    // MARK: Round 8 — security center

    /// The Center, the "Account secured" screen, change password in required
    /// mode and the recovery review (§3.4). Every exit lands back on the
    /// Center, which refetches on return.
    @ViewBuilder private func securityCenterDestination(_ route: Route) -> some View {
        switch route {
        case .secCenter:
            SecurityCenterView(
                onBack: { path.removeLast() },
                onAction: { id, done in openSafetyItem(id, done: done) },
                onSecured: { label in path.append(.secNotMeDone(device: label)) },
                onChangePassword: { path.append(.secPasswordRequired) },
                onSignOut: signOutOfMailbox
            )
        case let .secNotMeDone(device):
            AccountSecuredView(
                device: device,
                onChangePassword: { path.append(.secPasswordRequired) },
                onDone: { backToSecurityCenter() }
            )
        case .secPasswordRequired:
            RequiredChangePasswordView(
                onChanged: {
                    // The secured screen and this one are spent: back from the
                    // review must not land on a password form that just worked.
                    path.removeAll { route in
                        if case .secNotMeDone = route { return true }
                        return route == .secPasswordRequired
                    }
                    path.append(.secRecoveryReview)
                },
                onLeave: { path.removeLast() },
                onForgot: { path.append(.forgotPassword) }
            )
        case .secRecoveryReview:
            RecoveryReviewView(
                onDone: { backToSecurityCenter() },
                onOpen: { id in openSafetyItem(id, done: false) }
            )
        default:
            mailDestination(route)
        }
    }

    /// A safety checklist row's (or recovery-review row's) destination — the
    /// existing security screens (§4.2 "Action → existing screen").
    private func openSafetyItem(_ id: SecChecklistID, done: Bool) {
        switch id {
        case .twoStep:
            if done { backToSecurity() } else { path.append(.secEnable) }
        case .passkey:
            path.append(.secPasskeys)
        case .recoveryCodes:
            path.append(.secRecoveryCodes)
        case .recoveryEmail:
            path.append(.recoveryEmail)
        case .passwordHealth:
            path.append(.secPassword)
        case .recoveryPhone:
            path.append(.phoneVerify)
        case .secondDevice, .alertsReviewed:
            // Answered inside the Center (devices section / first "Review"
            // row); from the hub header the Center is the door.
            if path.last != .secCenter { path.append(.secCenter) }
        default:
            break
        }
    }

    /// Back to the Center, whichever door the person came through.
    private func backToSecurityCenter() {
        if path.contains(.secCenter) {
            while let last = path.last, last != .secCenter { path.removeLast() }
        } else {
            path.removeAll { route in
                switch route {
                case .secNotMeDone, .secPasswordRequired, .secRecoveryReview: return true
                default: return false
                }
            }
            path.append(.secCenter)
        }
    }

    /// The mailbox slot's doors (`.gmOpenSecurityCenter`). Signed out, there
    /// is nothing to open; the same restore rule as the other openers.
    private func openSecurityCenter(_ target: String?) {
        guard restoredSession, !renewingSession, SessionStore.hasValidSession else { return }
        if target == "password" {
            if path.last != .secPasswordRequired { path.append(.secPasswordRequired) }
        } else if path.contains(.secCenter) {
            backToSecurityCenter()
        } else {
            path.append(.secCenter)
        }
    }

    /// Sign-up's five steps, the human check and the two finish screens.
    @ViewBuilder private func wizardDestination(_ route: Route) -> some View {
        switch route {

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
                        // Human verification moved BEFORE the Terms/create step
                        // (2026-11-18): the register call consumes the server-issued
                        // verificationId, so the challenge must be solved first.
                        path.append(.humanVerify)
                    }
                case .stepTerms:
                    TermsStepView(model: model, onBack: { path.removeLast() }, onSignIn: { path.append(.signIn) }) { address, name in
                        path.removeAll(where: { $0 == .stepName || $0 == .stepInfo || $0 == .stepAddress || $0 == .stepPassword || $0 == .stepTerms || $0 == .humanVerify })
                        // The account now exists and the register call consumed the
                        // verificationId (verification ran before this step,
                        // 2026-11-18 reorder): finish the wizard. If the auto
                        // sign-in did not land, send the person to sign-in with
                        // the address filled in instead of into a dead flow.
                        if SessionStore.hasValidSession {
                            // Optional: add a recovery email now (Skip for now
                            // continues). Account Security keeps reminding.
                            path.append(.recoveryEmailSignup(address: address, name: name))
                        } else {
                            recovery.prefillIdentifier = address
                            path.append(.signIn)
                        }
                    }
                case let .recoveryEmailSignup(address, name):
                    RecoveryEmailView(
                        onDone: {
                            guard !OnboardingGate.isDone else { return enterMailbox() }
                            path.append(.onboarding(address: address, name: name))
                        },
                        onSkip: {
                            guard !OnboardingGate.isDone else { return enterMailbox() }
                            path.append(.onboarding(address: address, name: name))
                        }
                    )
                case .humanVerify:
                    HumanVerifyView(model: model, onBack: { path.removeLast() }) {
                        // Server-confirmed verdict only (Continue gates on it). Go
                        // to the Terms/create step — the register call there spends
                        // the verificationId; the wizard finishes from that step
                        // (2026-11-18 reorder).
                        path.append(.stepTerms)
                    }
                case let .created(address, name):
                    AccountCreatedView(address: address, name: name) {
                        guard !OnboardingGate.isDone else { return enterMailbox() }
                        path.append(.onboarding(address: address, name: name))
                    }
                case let .createdAlt(address, name):
                    AccountCreatedAltView(
                        address: address,
                        name: name,
                        onGoToMailbox: enterMailbox,
                        onContinueOnboarding: {
                            guard !OnboardingGate.isDone else { return enterMailbox() }
                            path.append(.onboarding(address: address, name: name))
                        }
                    )
                case .onboarding:
                    OnboardingView(
                        onFinish: {
                            // Finished or skipped: this device has now seen the
                            // walkthrough, and no later account repeats it.
                            OnboardingGate.markDone()
                            enterMailbox()
                        }
                    )
            default:
                mailDestination(route)
        }
    }

    private func enterMailbox() {
        e2e.clearForSignOut()
        signedInModels.reset()
        inlineMail = nil
        path = [.home]
        // Round 8 — welcome back: a passkey that signed in another account
        // than the one chosen is accepted and announced (§2.3).
        if let notice = WelcomeBackGate.signedInAsNotice {
            WelcomeBackGate.signedInAsNotice = nil
            showWelcomeBackToast(notice)
        }
        // A link that arrived signed out was parked, not dropped: the person
        // who just proved the account is theirs gets the thread they tapped
        // (still behind the app lock's rule -- openPendingLink checks it).
        openPendingLink()
    }

    private func showRemainingAccount(or fallback: [Route]) {
        if let next = SessionStore.accounts().first, SessionStore.switchTo(next.address) {
            enterMailbox()
        } else {
            e2e.clearForSignOut()
            signedInModels.reset()
            inlineMail = nil
            path = fallback
        }
    }

    /// Every sign-out door (drawer, account switcher, settings, the Security
    /// Center). With welcome-back on, the confirmation sheet asks whether to
    /// also remove the account from this device (§2.4); otherwise it signs out
    /// at once, as before.
    private func signOutOfMailbox() {
        guard WelcomeBackGate.isEnabled, let address = SessionStore.address, address.contains("@") else {
            performSignOut(removeFromDevice: false)
            return
        }
        signOutPrompt = SignOutPrompt(address: address)
    }

    /// End the active session. The remembered record (never a password or
    /// token) is kept unless `removeFromDevice`; RememberedAccounts reads the
    /// choice inside SessionStore.clear().
    private func performSignOut(removeFromDevice: Bool) {
        if let address = SessionStore.address {
            RememberedAccounts.prepareSignOut(address: address, removeFromDevice: removeFromDevice)
        }
        SessionStore.clear()
        // Another signed-in account takes over when there is one; otherwise a
        // remembered account opens on "Continue as …", and the welcome screen
        // stays underneath it.
        let landing = signInRoute()
        showRemainingAccount(or: landing == .signIn ? [] : [landing])
    }

    // MARK: Round 8 — welcome back

    /// Where a signed-out "sign in" goes: welcome-back for one remembered,
    /// signed-out account, the chooser for two or more, the classic form when
    /// there is none, the flag is off, or "Use another account" was tapped in
    /// this launch (§2.1). A remembered account never signs anyone in; it only
    /// pre-fills the address and shows the name and photo.
    private func signInRoute() -> Route {
        let remembered = WelcomeBackGate.candidates()
        if remembered.count >= 2 { return .accountChooser }
        if let only = remembered.first { return .welcomeBack(address: only.address) }
        return .signIn
    }

    private func isWelcomeBackRoute(_ route: Route) -> Bool {
        switch route {
        case .welcomeBack, .accountChooser: return true
        default: return false
        }
    }

    /// Any sign-in surface: the classic form, welcome-back or the chooser.
    private func isSignInSurface(_ route: Route) -> Bool {
        route == .signIn || isWelcomeBackRoute(route)
    }

    @ViewBuilder private func welcomeBackDestination(_ route: Route) -> some View {
        switch route {
        case let .welcomeBack(address):
            welcomeBackScreen(preferred: address)
        case .accountChooser:
            welcomeBackScreen(preferred: nil)
        default:
            mailDestination(route)
        }
    }

    /// The same sign-in pipeline as SignInView: success → enterMailbox(), a
    /// two-step account → the existing two-step screen, forgot password → the
    /// existing reset flow with the address prefilled.
    private func welcomeBackScreen(preferred: String?) -> some View {
        WelcomeBackScreen(
            model: recovery,
            preferredAddress: preferred,
            onBack: { if !path.isEmpty { path.removeLast() } },
            onSignedIn: { _ in enterMailbox() },
            onNeedSecondFactor: { path.append(.twoFactor) },
            onNeedEmailVerification: { path.append(.emailVerification) },
            onForgotPassword: { path.append(.forgotPassword) },
            onExit: { exit in leaveWelcomeBack(exit) }
        )
    }

    /// "Use another account", the last record removed, or a blocked account:
    /// the classic sign-in form takes the welcome-back screen's place.
    private func leaveWelcomeBack(_ exit: WelcomeBackScreen.Exit) {
        path.removeAll { isWelcomeBackRoute($0) }
        switch exit {
        case .useAnotherAccount, .removedAll:
            recovery.prefillIdentifier = nil
        case let .blocked(address, message):
            recovery.prefillIdentifier = address
            signInNotice = message
        }
        path.append(.signIn)
    }

    private func showWelcomeBackToast(_ text: String) {
        welcomeBackToast = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            if welcomeBackToast == text { welcomeBackToast = nil }
        }
    }

    @Environment(\.horizontalSizeClass) private var mailboxSizeClass
    /// The text size the two-pane breakpoint and list width follow
    /// (`MailPaneLayout`, MailTwoPane.swift): larger text needs a wider list
    /// and a wider window before list and reader share it.
    @Environment(\.dynamicTypeSize) private var mailboxTextSize
    /// The reader column's open mail on a regular width (MailTwoPane.swift).
    /// State here, not in the `.home` destination, so rotation, Split View
    /// resizing and a collapse to one column all keep the same mail open.
    @State private var inlineMail: MailPaneSelection?
    /// The one namespace the iOS 18 zoom push shares between a list row
    /// (`matchedTransitionSource`) and the reader it opens
    /// (`navigationTransition(.zoom)`), both in MailRowPreview.swift.
    @Namespace private var mailZoom
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The reader column's change of mail: a short fade-and-slide from the
    /// trailing edge on the two-pane layout; nothing under Reduce Motion or
    /// when the reader is the only column.
    private func readerColumnTransition(wide: Bool) -> AnyTransition {
        guard wide, !reduceMotion else { return AnyTransition.identity }
        return AnyTransition.opacity.combined(with: .move(edge: .trailing))
    }

    /// The reader column on a regular width, or the reader alone below the
    /// two-pane breakpoint. A ZStack so that, while a new selection slides in,
    /// the outgoing reader overlaps it instead of taking an HStack slot beside
    /// it. On two-pane a selection change fades and slides in from the trailing
    /// edge in ~0.22 s; under Reduce Motion, or with the reader as the only
    /// column, it swaps with no animation, as before.
    @ViewBuilder private func readerColumn(wide: Bool) -> some View {
        ZStack {
            if let selection = inlineMail {
                ThreadView(
                    vm: mail, e2e: e2e, id: selection.id, isThread: selection.isThread,
                    onBack: { inlineMail = nil },
                    onReply: { id, mode in path.append(.mailCompose(reply: id, mode: mode)) },
                    onStepPrev: { inlineMail = MailPaneSelection(id: $0.id, isThread: $0.isThread) },
                    onStepNext: { inlineMail = MailPaneSelection(id: $0.id, isThread: $0.isThread) },
                    onContacts: { path.append(.mailContacts) },
                    initialMessageId: selection.focus, openMessageActions: selection.more,
                    inColumn: wide
                )
                .id(selection.id)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(readerColumnTransition(wide: wide))
            } else {
                // Only reached on two-pane: the caller draws this column with
                // nothing selected only when `wide`.
                MailPaneEmptyState()
                    .transition(readerColumnTransition(wide: wide))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .animation(wide && !reduceMotion ? .easeOut(duration: 0.22) : nil, value: inlineMail?.id)
    }

    /// The signed-in world: mailbox, account record, encryption,
    /// subscriptions. `default:` above routes here.
    @ViewBuilder private func mailDestination(_ route: Route) -> some View {
        switch route {

                case .home:
                    GeometryReader { geometry in
                        // Size class + the width this window really has + the
                        // text size (MailPaneLayout): 760 pt and min(420, 42%)
                        // at the design sizes, exactly as before.
                        let wide = MailPaneLayout.isTwoPane(sizeClass: mailboxSizeClass,
                                                            width: geometry.size.width,
                                                            dynamicTypeSize: mailboxTextSize)
                        HStack(spacing: 0) {
                            if wide || inlineMail == nil {
                                MailboxView(
                                    vm: mail,
                                    onThread: { id, isThread, rowID in
                                        if wide { inlineMail = MailPaneSelection(id: id, isThread: isThread) }
                                        else { path.append(.mailThread(id: id, isThread: isThread, zoomSource: rowID)) }
                                    },
                                    // A draft is the person's own unfinished mail; the
                                    // only useful tap is the one that lets them finish it.
                                    // Declared before onCompose because Swift requires
                                    // call order to match the struct's property order.
                                    onDraft: { id in path.append(.mailCompose(reply: id, mode: "draft")) },
                                    onCompose: { path.append(.mailCompose(reply: "", mode: "reply")) },
                                    onLabels: { path.append(.mailLabels) },
                                    onScreener: { path.append(.mailScreener) },
                                    onSubscriptions: { path.append(.mailSubscriptions) },
                                    onProfile: { path.append(.accountProfile) },
                                    onSettings: { path.append(.mailSettings) },
                                    onSecurity: { path.append(.security) },
                                    onHelp: openSupport,
                                    onContacts: { path.append(.mailContacts) },
                                    onFiles: { path.append(.mailFiles) },
                                    onSignOut: signOutOfMailbox,
                                    onAddAccount: { path.append(.signIn) },
                                    onSwitchAccount: { address in
                                        if SessionStore.switchTo(address) { enterMailbox() }
                                    },
                                    accountPhoto: account.state.avatar,
                                    openRowID: wide ? inlineMail?.id : nil,
                                    onReply: { id in path.append(.mailCompose(reply: id, mode: "reply")) },
                                    // Two-pane selects rather than pushes: no zoom source.
                                    zoomNamespace: wide ? nil : mailZoom
                                )
                                .frame(width: wide ? MailPaneLayout.listWidth(total: geometry.size.width,
                                                                              dynamicTypeSize: mailboxTextSize) : nil)
                            }
                            if wide { Divider() }
                            // The reader column (MailTwoPane.swift). Its own
                            // function: this destination is already near what
                            // the type-checker resolves in one expression.
                            if wide || inlineMail != nil {
                                readerColumn(wide: wide)
                            }
                        }
                        // iPad hardware-keyboard commands, two-pane only.
                        .background {
                            if wide {
                                MailPaneShortcuts(
                                    vm: mail, selection: $inlineMail,
                                    onCompose: { path.append(.mailCompose(reply: "", mode: "reply")) }
                                )
                            }
                        }
                    }
                    .task(id: SessionStore.generation) {
                        if let address = model.state.createdAddress,
                           address.caseInsensitiveCompare(SessionStore.address ?? "") == .orderedSame,
                           let photo = model.state.photo {
                            let saved = model.state.photoSaved
                            model.update {
                                $0.photo = nil
                                $0.photoSaved = false
                            }
                            if saved {
                                account.showSignupAvatar(photo)
                                account.load()
                            } else {
                                account.uploadAvatar(bytes: photo, filename: "profile.png", mime: "image/png")
                            }
                        } else { account.load() }
                    }
                    // Round 8 — security center: the mailbox slot's overview
                    // (§4.3 B.2: at most one background read a day per account).
                    .task(id: SessionStore.generation) {
                        guard SecFeature.enabled else { return }
                        await SecurityOverviewStore.shared.refreshIfDue()
                    }
                    .task(id: account.state.busy ? nil : account.state.profile?.avatarUrl) {
                        if !account.state.busy { account.loadAvatar() }
                    }
                    .onChange(of: scenePhase) { phase in
                        if phase == .active { account.load(force: true) }
                    }
                    .id(SessionStore.generation)
                case let .mailThread(id, isThread, focus, more, zoomSource):
                    ThreadView(
                        vm: mail, e2e: e2e, id: id, isThread: isThread,
                        onBack: { path.removeLast() },
                        onReply: { id, mode in path.append(.mailCompose(reply: id, mode: mode)) },
                        // Stepping replaces the reader rather than pushing
                        // another copy of it: five nexts must not bury the
                        // list under five identical screens the back gesture
                        // has to unwind one at a time. The target's own flag
                        // decides the route, exactly as the list's tap does —
                        // a neighbour that is a single message would otherwise
                        // be opened on the thread route and answer 404.
                        onStepPrev: { step in
                            path.removeLast()
                            path.append(.mailThread(id: step.id, isThread: step.isThread))
                        },
                        onStepNext: { step in
                            path.removeLast()
                            path.append(.mailThread(id: step.id, isThread: step.isThread))
                        },
                        onContacts: { path.append(.mailContacts) },
                        initialMessageId: focus, openMessageActions: more
                    )
                    // iOS 18+: zooms out of the tapped row and back into it on
                    // swipe-back. No source (links, notifications) or iOS 16-17:
                    // the standard push, unchanged.
                    .mailZoomDestination(sourceID: zoomSource, in: mailZoom)
                case let .mailCompose(reply, mode, recipient):
                    ComposeView(vm: mail, account: account, e2e: e2e, replyId: reply, mode: mode,
                                onBack: { path.removeLast() }, initialRecipient: recipient)
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
                                      onWrite: { path.append(.mailCompose(reply: "", mode: "reply", recipient: address)) })
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
                        onScheduled: { path.append(.mailScheduled) },
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
                        onSignOut: signOutOfMailbox,
                        onNotificationPrefs: { path.append(.mailNotifPrefs) },
                        onPerAccountNotifications: { path.append(.perAccountNotifications) },
                        onProviders: { path.append(.mailProviders) }
                    )
                case .mailNotifPrefs:
                    NotificationPrefsView(onBack: { path.removeLast() })
                case .perAccountNotifications:
                    PerAccountNotificationsView(onBack: { path.removeLast() })
                case .mailNotes:
                    NotesView(vm: account, onBack: { path.removeLast() })
                case .mailTasks:
                    TasksView(vm: account, onBack: { path.removeLast() })
                case .mailPrefs:
                    MailPrefsView(vm: account, onBack: { path.removeLast() })
                case .mailAddresses:
                    AddressesView(vm: account, onBack: { path.removeLast() })
                case .mailProviders:
                    ConnectedAccountsView(onBack: { path.removeLast() })
                case .mailConnections:
                    ConnectionsView(vm: account, onBack: { path.removeLast() },
                                    onOpen: { path.append(.accountConnectionDetail(product: $0)) })
                case let .accountConnectionDetail(product):
                    ConnectionDetailView(vm: account, product: product,
                                         onBack: { path.removeLast() },
                                         onSupport: { path.append(.mailSupport) })
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
                        onDeleted: {
                            e2e.clearForSignOut()
                            recovery.clearSignInChallenge()
                            NotificationCenter.default.post(name: .gmAccountDeleted, object: nil)
                        }
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
                // Handed off whole to `e2eDestination`: the body's switch had
                // grown past what the type-checker will resolve in one pass
                // (CI: "unable to type-check this expression in reasonable
                // time"), and the switch's type erasure already lives on the
                // Group, so extracting a sub-switch changes nothing about the
                // views themselves.
                case .e2eStatus, .e2eSetup, .e2eUnlock, .e2eDetails,
                     .e2ePublish, .e2eChangePassphrase, .e2eRemove, .e2eRecipients:
                    e2eDestination(route)
                case .mailScheduled:
                    ScheduledView(
                        apiToken: SessionStore.token ?? "",
                        onBack: { path.removeLast() }
                    )
                case .mailScreener:
                    ScreenerView(
                        vm: mail,
                        onBack: { path.removeLast() },
                        onOpenQueue: { mail.open(.screener); backToHome() }
                    )
        @unknown default:
            // The pre-split switch was exhaustive, so adding a Route without a
            // destination was a COMPILE error; the sector switches need a
            // `default:` to keep the type-checker in time, and that trade
            // quietly turns the same mistake back into a blank screen. This
            // is the loud half of the trade: debug builds stop with the
            // route named, and `mail-honesty.py` §33 diffs every `Route` case
            // against the dispatch lines, so CI catches what a release build
            // would otherwise hide. The assertion lives INSIDE onAppear
            // because a ViewBuilder body takes expressions, and a bare
            // statement here is the exact error CI has now named twice
            // ("'buildExpression' is unavailable") — this repo's own compiler
            // lesson, re-applied by the person who wrote it and still
            // tripped over it.
            EmptyView().onAppear {
                assertionFailure("Route \(route) is dispatched by no sector — add it")
            }
        }
    }


    /// The encryption band's eight destinations, in their own switch. The
    /// body's own switch passed the type-checker's patience the way the
    /// snapshot host's frame did at twenty-six branches — a ViewBuilder
    /// function keeps every case legal, and the sub-switch is one expression
    /// the parent resolves instead of eight branch types of its own.
    @ViewBuilder private func e2eDestination(_ route: Route) -> some View {
        switch route {
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
                                    // Pop to the mailbox first -- the band
                                    // sits under Settings -- then one fresh
                                    // compose, so a stale reply-target
                                    // compose is never reused.
                                    while let last = path.last, last != .home {
                                        path.removeLast()
                                    }
                                    path.append(.mailCompose(reply: "", mode: "reply"))
                                })
        default:
            // Unreachable: the parent routes here only for the eight cases
            // above, and the switch inside a ViewBuilder must stay total.
            EmptyView()
        }
    }
}

// MARK: Round 8 — welcome back

/// The account the sign-out sheet is asking about.
private struct SignOutPrompt: Identifiable {
    let address: String
    var id: String { address }
}

/// RootView's welcome-back chrome, kept out of its body: the sign-out sheet
/// (§2.4, "Also remove from this device", default off) and the short
/// "Signed in as …" toast (§2.3).
private struct WelcomeBackHost: ViewModifier {
    @Binding var prompt: SignOutPrompt?
    let toast: String?
    let onSignOut: (_ removeFromDevice: Bool) -> Void

    func body(content: Content) -> some View {
        content
            .sheet(item: $prompt) { item in
                SignOutConfirmSheet(
                    address: item.address,
                    onConfirm: { remove in
                        prompt = nil
                        onSignOut(remove)
                    },
                    onCancel: { prompt = nil }
                )
            }
            .overlay(alignment: .bottom) {
                if let toast {
                    Text(verbatim: toast)
                        .gmFont(.captionMedium)
                        .foregroundColor(GM.bg)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .background(Capsule().fill(GM.textPrimary))
                        .padding(.horizontal, 24)
                        .padding(.bottom, 24)
                        .allowsHitTesting(false)
                        .transition(.opacity)
                }
            }
    }
}

