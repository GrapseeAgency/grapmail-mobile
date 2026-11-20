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
/// ── THREADING ─────────────────────────────────────────────────────────────
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
        let ok = await authenticate(reason: on ? "Turn on app lock" : "Turn off app lock")
        if ok {
            await MainActor.run {
                UserDefaults.standard.set(on, forKey: Prefs.enabled)
                if !on { self.locked = false }
            }
        }
        return ok
    }

    func unlock() async {
        let ok = await authenticate(reason: "Unlock GrapMail")
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
        context.localizedCancelTitle = "Cancel"
        var error: NSError?
        let policy = LAPolicy.deviceOwnerAuthentication
        guard context.canEvaluatePolicy(policy, error: &error) else {
            return (false, error?.localizedDescription ?? "Set a device passcode to use app lock.")
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
            VStack(spacing: 12) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundColor(Color.primary)
                Text("GrapMail is locked")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(Color.primary)
                Text("Unlock to see your mailbox.")
                    .font(.system(size: 14))
                    .foregroundColor(Color.secondary)
                Button {
                    Task { await lock.unlock() }
                } label: {
                    Text("Unlock")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Color.accentColor))
                }
                .padding(.horizontal, 44)
                .padding(.top, 6)
                if let error = lock.lastError {
                    Text(error)
                        .font(.system(size: 12))
                        .foregroundColor(Color.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 36)
                }
            }
        }
        .task { await lock.unlock() }
    }
}

/// The settings row for app lock — the one place the lock is turned on or off.
/// Its own view so the settings screen stays a list of rows: this one owns the
/// two things it needs, the live lock state and the honest note when the
/// device cannot do it (no passcode, no biometrics).
struct AppLockRow: View {
    @ObservedObject private var lock = AppLock.shared
    @State private var note: String?

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "faceid")
                .font(.system(size: 17))
                .foregroundColor(Color.secondary)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text("App lock")
                    .font(.system(size: 15))
                    .foregroundColor(Color.primary)
                Text(value)
                    .font(.system(size: 12))
                    .foregroundColor(Color.secondary)
                    .lineLimit(1)
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
            ? "On — the mailbox needs Face ID when locked"
            : "Off — tap to require Face ID or passcode"
    }

    private func toggle() {
        Task {
            let ok = await lock.setEnabled(!lock.enabled)
            note = ok ? nil : (lock.lastError ?? "Could not change app lock.")
        }
    }
}
