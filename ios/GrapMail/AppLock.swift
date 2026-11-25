import LocalAuthentication
import SwiftUI

/// The app lock: Face ID (or Touch ID, or the device passcode) in front of
/// everything the signed-in app shows.
///
/// ── WHEN THE LOCK APPLIES ──────────────────────────────────────────────────
/// Only to a device that has a session: a signed-out phone showing the welcome
/// wizard holds nothing worth hiding, and a lock over it would be a prompt
/// with no secret behind it. `checkOnActive` therefore refuses to lock when
/// there is no token.
///
/// ── THE GRACE WINDOW ───────────────────────────────────────────────────────
/// A lock that fires on every app-switch is a lock people turn off. The app
/// locks on a cold launch and after `graceSeconds` in the background — long
/// enough to glance at a notification, short enough that a phone left on a
/// café table is covered. The window is a constant, not a setting.
///
/// ── THREADING ──────────────────────────────────────────────────────────────
/// Deliberately a plain ObservableObject, not main-actor-isolated: every
/// published mutation goes through the main queue, so no call site — scene
/// hooks, button tasks, settings rows — has to carry actor annotations to
/// compile. Scene hooks call in from the main thread; the async paths hop.
final class AppLock: ObservableObject {
    static let shared = AppLock()

    enum Prefs {
        static let enabled = "grapmail.appLock.enabled"
        static let graceSeconds: TimeInterval = 15
    }

    @Published private(set) var locked = false
    @Published private(set) var authenticating = false
    @Published private(set) var lastError: String?

    private var authenticatedThisLaunch = false
    private var backgroundedAt: Date?

    private init() {}

    var enabled: Bool { UserDefaults.standard.bool(forKey: Prefs.enabled) }

    /// Called when the app leaves the foreground (scene hooks, main thread).
    func noteBackground() {
        backgroundedAt = Date()
    }

    /// Called when the app becomes active (and once at launch). Decides
    /// whether the lock screen should be in front.
    func checkOnActive() {
        guard enabled, SessionStore.token != nil else {
            locked = false
            return
        }
        if !authenticatedThisLaunch {
            locked = true
            return
        }
        if let at = backgroundedAt, Date().timeIntervalSince(at) > Prefs.graceSeconds {
            locked = true
        }
    }

    /// The settings toggle. Both directions require the owner once — a
    /// borrowed unlocked phone must not be able to switch the lock off, and a
    /// lock turned on must be proven to work before it is trusted with the
    /// mailbox.
    @discardableResult
    func setEnabled(_ on: Bool) async -> Bool {
        guard on != enabled else { return true }
        let ok = await authenticate(reason: on
            ? String(localized: "Turn on app lock", comment: "Face ID / passcode prompt reason when enabling app lock")
            : String(localized: "Turn off app lock", comment: "Face ID / passcode prompt reason when disabling app lock"))
        if ok {
            await MainActor.run {
                UserDefaults.standard.set(on, forKey: Prefs.enabled)
                if !on { self.locked = false }
            }
        }
        return ok
    }

    func unlock() async {
        let ok = await authenticate(reason: String(localized: "Unlock GrapMail", comment: "Face ID / passcode prompt reason when unlocking the app"))
        if ok {
            await MainActor.run {
                self.authenticatedThisLaunch = true
                self.backgroundedAt = nil
                self.locked = false
            }
        }
    }

    @discardableResult
    func authenticate(reason: String) async -> Bool {
        let busy = await MainActor.run { self.authenticating }
        guard !busy else { return false }
        await MainActor.run { self.authenticating = true }
        let outcome = await evaluate(reason: reason)
        await MainActor.run {
            self.authenticating = false
            self.lastError = outcome.error
        }
        return outcome.ok
    }

    /// The one LAContext round-trip. Kept apart from `authenticate` so every
    /// state write above happens in one place, on the main queue.
    private func evaluate(reason: String) async -> (ok: Bool, error: String?) {
        let context = LAContext()
        context.localizedCancelTitle = String(localized: "Cancel", comment: "Cancel button on the Face ID / passcode prompt")
        var error: NSError?
        let policy = LAPolicy.deviceOwnerAuthentication
        guard context.canEvaluatePolicy(policy, error: &error) else {
            return (false, error?.localizedDescription ?? String(localized: "Set a device passcode to use app lock.", comment: "App lock error when the device has no passcode"))
        }
        do {
            let ok = try await context.evaluatePolicy(policy, localizedReason: reason)
            return (ok, nil)
        } catch {
            return (false, error.localizedDescription)
        }
    }
}

/// The full-screen cover while the app is locked. It auto-prompts so the
/// person never has to find a button first; the button is there for a
/// cancelled or failed prompt.
struct AppLockOverlay: View {
    @ObservedObject var lock: AppLock

    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial).ignoresSafeArea()
            // Centred when it fits; scrolls in a short window (landscape
            // phone, small Stage Manager window) or at the largest text sizes.
            ViewThatFits(in: .vertical) {
                content
                ScrollView { content.padding(.vertical, 24) }
            }
        }
        .task { await lock.unlock() }
    }

    private var content: some View {
        VStack(spacing: 12) {
            Image(systemName: "lock.fill")
                .gmFont(34, .semibold, maxSize: 60)
                .foregroundColor(Color.primary)
            Text("GrapMail is locked")
                .gmFont(17, .semibold)
                .foregroundColor(Color.primary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Text("Unlock to see your mailbox.")
                .gmFont(14)
                .foregroundColor(Color.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                Task { await lock.unlock() }
            } label: {
                Text("Unlock")
                    .gmFont(16, .semibold)
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.accentColor))
            }
            // Phone-width button; on iPad / Mac it stops at a comfortable width.
            .frame(maxWidth: 360)
            .padding(.horizontal, 44)
            .padding(.top, 6)
            if let error = lock.lastError {
                Text(error)
                    .gmFont(12)
                    .foregroundColor(Color.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: GMLayout.form)
                    .padding(.horizontal, 36)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

/// The settings row for app lock — the one place the lock is turned on or off.
/// Its own view so the settings screen stays a list of rows: this one owns the
/// two things it needs, the live lock state and the honest note when the
/// device cannot do it (no passcode, no biometrics).
struct AppLockRow: View {
    @ObservedObject private var lock = AppLock.shared
    @State private var note: String?
    @ScaledMetric(relativeTo: .body) private var iconWidth: CGFloat = 22

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "faceid")
                .gmFont(17)
                .foregroundColor(Color.secondary)
                .frame(width: iconWidth)
            VStack(alignment: .leading, spacing: 2) {
                Text("App lock")
                    .gmFont(15)
                    .foregroundColor(Color.primary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(value)
                    .gmFont(12)
                    .foregroundColor(Color.secondary)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            if lock.authenticating {
                ProgressView().controlSize(.small)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture { toggle() }
        .accessibilityIdentifier("mail-app-lock-row")
    }

    private var value: String {
        if let note { return note }
        return lock.enabled
            ? String(localized: "On — the mailbox needs Face ID when locked", comment: "App lock settings row status when on")
            : String(localized: "Off — tap to require Face ID or passcode", comment: "App lock settings row status when off")
    }

    private func toggle() {
        Task {
            let ok = await lock.setEnabled(!lock.enabled)
            note = ok ? nil : (lock.lastError ?? String(localized: "Could not change app lock.", comment: "App lock settings row error"))
        }
    }
}
