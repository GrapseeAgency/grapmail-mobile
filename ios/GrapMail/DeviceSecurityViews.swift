import SwiftUI
import UIKit

/// The device-security screens: the trusted-device list, the recovery codes
/// the all-devices-lost path spends, the per-scope notification preferences,
/// and the trusted-device recovery flow the Forgot Password screen opens.
///
/// They hang off the account-security hub and the settings screen the same way
/// the rest of the security sector hangs off it, so the shells, chips and
/// buttons here are the sector's own components — not redrawn ones.

// ── Trusted devices ──────────────────────────────────────────────────────────

/// The trusted-device list. A row here is a device the server would accept a
/// recovery signature from; revoking one is the same one-way act revoking a
/// session is, so it takes the same explicit confirmation.
@MainActor
struct TrustedDevicesView: View {
    let onBack: () -> Void

    @State private var devices: [TrustedDeviceDto] = []
    @State private var loading = true
    @State private var error: String?
    /// The device behind an open confirmation. Held as the row itself so the
    /// alert's title names the right device even if the list re-renders.
    @State private var pendingRevoke: TrustedDeviceDto?
    @State private var revoking = false
    @State private var revokeError: String?

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_sessions",
            illustrationWidth: 170,
            title: String(localized: "Trusted Devices", comment: "Screen title (trusted devices screen)"),
            subtitle: String(localized: "Devices that can sign in to your account with their own key if you ever lose your password.", comment: "Screen description under the title (trusted devices screen)")
        ) {
            if loading {
                Text("Loading devices…").gmFont(13).foregroundColor(GM.textSecondary)
            } else if let error {
                Text(error).gmFont(13).foregroundColor(GM.error)
                    .fixedSize(horizontal: false, vertical: true)
                AccountLink(title: String(localized: "Try again", comment: "Link-style button (trusted devices screen)"), action: { Task { await load() } })
            } else if devices.isEmpty {
                Text("No trusted devices are recorded yet. This device is enrolled the first time you sign in.")
                    .gmFont(13)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                VStack(spacing: 10) {
                    ForEach(devices) { device in
                        DeviceCardView(
                            device: device,
                            isThisDevice: device.id == TrustedDeviceManager.shared.enrolledDeviceId(for: SessionStore.address ?? ""),
                            onRevoke: device.trustState == "revoked" ? nil : { pendingRevoke = device }
                        )
                    }
                }
                // The honesty rule for this phone's own key: say what it is
                // actually protected by. "No biometric lock" is the recorded
                // state on a device without a passcode — the key signs, and
                // the label must not imply a prompt that cannot exist.
                Text(DeviceKeyManager.keyGuardLabel)
                    .gmFont(11)
                    .foregroundColor(GM.textTertiary)
                    .padding(.top, 2)
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: "info.circle").gmFont(14).foregroundColor(GM.primaryText).accessibilityHidden(true)
                    Text("If a device on this list is not yours, revoke it immediately — a trusted device can reset your password without a code.")
                        .gmFont(12)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.softFill))
                .padding(.top, 12)
                if let revokeError {
                    Text(revokeError).gmFont(12).foregroundColor(GM.error).padding(.top, 6)
                }
            }
        } footer: {
            FooterNote(text: String(localized: "Revoking a device stops its recovery key from working and removes its push registration.", comment: "Footer note (trusted devices screen)"))
        }
        .task { await load() }
        .alert(
            pendingRevoke.map { $0.displayName.isEmpty ? Text("Revoke this device?") : Text("Revoke \($0.displayName)?") } ?? Text("Revoke this device?"),
            isPresented: Binding(get: { pendingRevoke != nil }, set: { if !$0 { pendingRevoke = nil } })
        ) {
            Button("Cancel", role: .cancel) { pendingRevoke = nil }
            Button("Revoke Device", role: .destructive) { if let device = pendingRevoke { revoke(device) } }
        } message: {
            Text("This device will no longer be able to reset your password with its key. This cannot be undone from here.")
        }
    }

    private func load() async {
        guard let token = SessionStore.token else { loading = false; return }
        loading = true
        error = nil
        do {
            let resp = try await ApiClient.listDevices(token: token)
            devices = resp.devices
        } catch {
            self.error = apiMessage(error, fallback: String(localized: "Could not load the device list. Try again.", comment: "Error message shown when the server gives no reason (trusted devices screen)"))
        }
        loading = false
    }

    private func revoke(_ device: TrustedDeviceDto) {
        guard let token = SessionStore.token else { return }
        guard !revoking else { return }
        revoking = true
        revokeError = nil
        Task {
            defer { revoking = false }
            do {
                _ = try await ApiClient.revokeDevice(token: token, id: device.id)
                // If that was THIS install's own row, drop the enrollment fact
                // too: the next sign-in must re-enroll, not skip on a ledger
                // entry that now points at a revoked server row.
                if device.id == TrustedDeviceManager.shared.enrolledDeviceId(for: SessionStore.address ?? "") {
                    TrustedDeviceManager.shared.forget(account: SessionStore.address ?? "")
                }
                await load()
            } catch {
                revokeError = apiMessage(error, fallback: String(localized: "The device could not be revoked. Try again.", comment: "Error message shown when the server gives no reason (trusted devices screen)"))
                pendingRevoke = nil
            }
        }
    }
}

/// One trusted device, as the sessions screen prints one — name, platform,
/// trust chip, the absolute time the server last heard from it.
private struct DeviceCardView: View {
    @Environment(\.dynamicTypeSize) private var dts
    let device: TrustedDeviceDto
    var isThisDevice: Bool
    var onRevoke: (() -> Void)?

    private var revoked: Bool { device.trustState == "revoked" || device.revokedAt != nil }

    private var platformName: String {
        switch device.platform.lowercased() {
        case "ios": return "iPhone"
        case "android": return "Android"
        default: return device.platform.isEmpty ? String(localized: "Device", comment: "Trusted device card: platform when it is not known") : device.platform
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            GMAdaptiveStack(spacing: 8) {
                Text(device.displayName.isEmpty ? String(localized: "Unknown device", comment: "Session card title when the device name is not known") : device.displayName)
                    .gmFont(15, .semibold)
                    .foregroundColor(GM.textPrimary)
                    .lineLimit(dts.isAccessibilitySize ? 4 : 2)
                    .fixedSize(horizontal: false, vertical: true)
                if !dts.isAccessibilitySize { Spacer(minLength: 0) }
                if isThisDevice {
                    Text("This device")
                        .gmFont(11, .bold)
                        .foregroundColor(GM.successInk)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Capsule().fill(GM.successChipBg))
                }
            }
            // One line when it fits; at the largest sizes the platform drops
            // under the trust state instead of being cut off.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) {
                    trustState
                    Text("·")
                        .gmFont(12)
                        .foregroundColor(GM.textTertiary)
                    platformLabel
                    Spacer(minLength: 0)
                }
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) { trustState }
                    platformLabel.fixedSize(horizontal: false, vertical: true)
                }
            }
            Text(device.lastSeenAt.map { String(localized: "Last seen \(securityAbsolute($0))", comment: "Trusted device card; the argument is a date and time") } ?? securityAbsolute(device.createdAt))
                .gmFont(11)
                .foregroundColor(GM.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            if let onRevoke {
                Button(action: onRevoke) {
                    Text("Revoke this device")
                        .gmFont(13, .semibold)
                        .foregroundColor(GM.error)
                        .frame(minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.top, 2)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).fill(GM.card))
        .overlay(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).strokeBorder(GM.border))
    }

    @ViewBuilder private var trustState: some View {
        Image(systemName: revoked ? "xmark.shield" : "checkmark.shield")
            .gmFont(11)
            .foregroundColor(revoked ? GM.error : GM.success)
        (revoked ? Text("Revoked") : Text("Trusted"))
            .gmFont(12)
            .foregroundColor(revoked ? GM.error : GM.textSecondary)
    }

    private var platformLabel: some View {
        Text(platformName)
            .gmFont(12)
            .foregroundColor(GM.textSecondary)
    }
}

// ── Recovery codes ───────────────────────────────────────────────────────────

/// The account-recovery codes — the all-devices-lost path. Generation is the
/// moment the old set dies, and the codes are readable exactly once, so this
/// is one screen in two states: the status view that offers to generate, and
/// the one-time display that does not let go until the person confirms.
struct RecoveryCodesView: View {
    @Environment(\.dynamicTypeSize) private var dts
    let onBack: () -> Void

    private enum Phase { case status, codes }
    @State private var phase = Phase.status
    @State private var usable: Bool?
    @State private var codes: [String] = []
    @State private var loading = true
    @State private var error: String?
    @State private var generating = false
    @State private var copied = false
    @State private var copiedCode: String?
    // Codes are shown once. Leaving without saving them is a real loss, so it
    // takes an explicit confirmation rather than a tap on the nearest button —
    // the same gate the 2FA backup-codes screen closes.
    @State private var confirmed = false
    @State private var showLeave = false

    /// Two codes a row; one at the accessibility sizes, where two monospaced
    /// codes no longer fit side by side.
    private var perRow: Int { dts.isAccessibilitySize ? 1 : 2 }
    private var grid: [[String]] { stride(from: 0, to: codes.count, by: perRow).map { Array(codes[$0..<min($0 + perRow, codes.count)]) } }

    var body: some View {
        AccountScreen(
            onBack: phase == .codes && !confirmed ? { showLeave = true } : onBack,
            illustration: phase == .codes ? "ill_sec_backup_saved" : "ill_sec_backup_generate",
            illustrationWidth: phase == .codes ? 150 : 180,
            title: phase == .codes ? String(localized: "Your Recovery Codes", comment: "Screen title (recovery codes screen)") : String(localized: "Account Recovery Codes", comment: "Screen title (recovery codes screen)"),
            subtitle: phase == .codes
                ? String(localized: "Store these codes somewhere safe. They won't be shown again.", comment: "Screen description under the title (recovery codes screen)")
                : String(localized: "One-time codes that sign you back in if you lose every trusted device and your password.", comment: "Screen description under the title (recovery codes screen)")
        ) {
            if phase == .codes {
                codesContent
            } else {
                statusContent
            }
        } footer: {
            VStack(spacing: 6) {
                if phase == .codes {
                    GradientButton(title: String(localized: "Done", comment: "Button title (recovery codes screen)"), enabled: confirmed, action: { onBack() })
                    FooterNote(text: String(localized: "Each code can be used once. Generating new codes retires every code on this list.", comment: "Footer note (recovery codes screen)"))
                } else {
                    GradientButton(
                        title: (usable ?? false) ? String(localized: "Generate New Codes", comment: "Button title (recovery codes screen)") : String(localized: "Generate Codes", comment: "Button title (recovery codes screen)"),
                        busy: generating,
                        action: generate
                    )
                    FooterNote(text: (usable ?? false)
                               ? String(localized: "Generating a new set retires every code you have saved.", comment: "Footer note (recovery codes screen)")
                               : String(localized: "Generate a set and keep it with the documents you would reach for in an emergency.", comment: "Footer note (recovery codes screen)"))
                }
            }
        }
        .alert("These codes will not be shown again", isPresented: $showLeave) {
            Button("Leave anyway", role: .destructive) { onBack() }
            Button("Stay", role: .cancel) { }
        } message: {
            Text("Leave now and the list is gone. You can generate a new set later, but the old codes stop working.")
        }
        .interactiveDismissDisabled(phase == .codes && !confirmed)
        .task { await loadStatus() }
    }

    @ViewBuilder private var statusContent: some View {
        if loading {
            Text("Checking for saved codes…").gmFont(13).foregroundColor(GM.textSecondary)
        } else if let error {
            Text(error).gmFont(13).foregroundColor(GM.error)
                    .fixedSize(horizontal: false, vertical: true)
            AccountLink(title: String(localized: "Try again", comment: "Link-style button (recovery codes screen)"), action: { Task { await loadStatus() } })
        } else {
            InfoCard {
                HStack(spacing: 8) {
                    Image(systemName: (usable ?? false) ? "checkmark.circle" : "info.circle")
                        .gmFont(14)
                        .foregroundColor((usable ?? false) ? GM.success : GM.primaryText)
                    ((usable ?? false)
                         ? Text("You have unused recovery codes saved.")
                         : Text("No unused recovery codes on this account."))
                        .gmFont(13, .semibold)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
                Text("A code is spent the moment it signs you in. Anyone holding an unused code can pass recovery, so the list belongs somewhere only you can reach.")
                    .gmFont(12)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)
            }
        }
    }

    @ViewBuilder private var codesContent: some View {
        InfoCard {
            GMAdaptiveStack(spacing: dts.isAccessibilitySize ? 8 : nil) {
                Text("Your Recovery Codes")
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if !dts.isAccessibilitySize { Spacer() }
                Button(action: {
                    UIPasteboard.general.string = codes.joined(separator: "\n")
                    copied = true
                }) {
                    HStack(spacing: 5) {
                        Image(systemName: copied ? "checkmark.circle" : "doc.on.doc")
                            .gmFont(12, .semibold)
                        (copied ? Text("Copied") : Text("Copy all"))
                            .gmFont(12, .semibold)
                    }
                    .foregroundColor(GM.primaryText)
                }
                .buttonStyle(.plain)
            }
            .padding(.bottom, 12)
            VStack(spacing: 10) {
                ForEach(Array(grid.enumerated()), id: \.offset) { _, pair in
                    HStack(spacing: 12) {
                        ForEach(pair, id: \.self) { code in
                            // Per-code copy: grabbing one code to use now should
                            // not require copying all ten.
                            Button { UIPasteboard.general.string = code; copiedCode = code } label: {
                                HStack(spacing: 6) {
                                    Text(code)
                                        .gmFont(13, .semibold, design: .monospaced)
                                        .foregroundColor(GM.textPrimary)
                                        .lineLimit(1)
                                        .minimumScaleFactor(0.7)
                                    Image(systemName: copiedCode == code ? "checkmark.circle" : "doc.on.doc")
                                        .gmFont(11)
                                        .foregroundColor(GM.textTertiary)
                                    Spacer(minLength: 0)
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 9)
                                .padding(.horizontal, 8)
                                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(GM.softFill))
                            }
                            .buttonStyle(.plain)
                        }
                        if pair.count == 1 && perRow == 2 { Spacer().frame(maxWidth: .infinity) }
                    }
                }
            }
        }
        Button {
            confirmed.toggle()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: confirmed ? "checkmark.square.fill" : "square")
                    .gmFont(18)
                    .foregroundColor(confirmed ? GM.primaryText : GM.textTertiary)
                Text("I have saved these codes somewhere safe.")
                    .gmFont(13)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
        }
        .buttonStyle(.plain)
        .padding(.top, 14)
        .accessibilityAddTraits(confirmed ? [.isButton, .isSelected] : .isButton)
    }

    private func loadStatus() async {
        guard let token = SessionStore.token else { loading = false; return }
        loading = true
        error = nil
        do {
            let resp = try await ApiClient.recoveryCodesStatus(token: token)
            usable = resp.usable
        } catch {
            self.error = apiMessage(error, fallback: String(localized: "Could not check the codes. Try again.", comment: "Error message shown when the server gives no reason (recovery codes screen)"))
        }
        loading = false
    }

    private func generate() {
        guard let token = SessionStore.token else { error = String(localized: "Sign in again.", comment: "Message (recovery codes screen)"); return }
        generating = true
        error = nil
        Task {
            do {
                let resp = try await ApiClient.generateRecoveryCodes(token: token)
                codes = resp.codes
                usable = !codes.isEmpty
                confirmed = false
                copied = false
                copiedCode = nil
                phase = .codes
            } catch {
                self.error = apiMessage(error, fallback: String(localized: "Could not generate codes. Try again.", comment: "Error message shown when the server gives no reason (recovery codes screen)"))
            }
            generating = false
        }
    }
}

// ── Notification preferences ─────────────────────────────────────────────────

/// Per-scope notification preferences on the account row
/// (`deviceKey: "account"`): new-mail alerts, security alerts and account
/// alerts, with quiet hours for the mail scope only — the server's own rule is
/// that a security alert never consults quiet hours, so the screen draws no
/// picker next to it rather than one that would do nothing.
struct NotificationPrefsView: View {
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .body) private var tile: CGFloat = 34
    let onBack: () -> Void

    /// The three scopes, in the order the screen prints them.
    private enum Scope: String, CaseIterable {
        case mail, security, account

        var title: String {
            switch self {
            case .mail: return String(localized: "New mail alerts", comment: "Notification preference title")
            case .security: return String(localized: "Security alerts", comment: "Notification preference title")
            case .account: return String(localized: "Account alerts", comment: "Notification preference title")
            }
        }
        var detail: String {
            switch self {
            case .mail: return String(localized: "When a new message reaches the inbox", comment: "Notification preference description")
            case .security: return String(localized: "Sign-ins, blocks and recovery on the account", comment: "Notification preference description")
            case .account: return String(localized: "Profile and account changes", comment: "Notification preference description")
            }
        }
        var icon: String {
            switch self {
            case .mail: return "envelope"
            case .security: return "checkmark.shield"
            case .account: return "person"
            }
        }
    }

    @State private var enabled: [Scope: Bool] = [:]
    @State private var quietStart: Date = Calendar.current.date(from: DateComponents(hour: 22, minute: 0)) ?? Date()
    @State private var quietEnd: Date = Calendar.current.date(from: DateComponents(hour: 7, minute: 0)) ?? Date()
    @State private var hasQuietHours = false
    @State private var loaded = false
    @State private var loading = true
    @State private var failed = false
    @State private var saving: Scope?
    @State private var error: String?
    @State private var reload = 0
    @State private var didAppear = false

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_alert",
            illustrationWidth: 170,
            title: String(localized: "Notification Preferences", comment: "Screen title (notification preferences screen)"),
            subtitle: String(localized: "Choose which alerts reach this account and when the mail ones go quiet.", comment: "Screen description under the title (notification preferences screen)")
        ) {
            if loading {
                Text("Loading preferences…").gmFont(13).foregroundColor(GM.textSecondary)
            } else if failed {
                Text("We couldn't load your notification preferences.")
                    .gmFont(13)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                AccountLink(title: String(localized: "Try again", comment: "Link-style button (notification preferences screen)"), action: { reload += 1 })
            } else {
                InfoCard {
                    Text("Alerts")
                        .gmFont(14, .semibold)
                        .foregroundColor(GM.textPrimary)
                    ForEach(Scope.allCases, id: \.self) { scope in
                        scopeRow(scope)
                        if scope != Scope.allCases.last { SecurityDivider() }
                    }
                }
                if failed == false, loaded {
                    quietCard
                }
                if let error {
                    Text(error).gmFont(12).foregroundColor(GM.error).padding(.top, 6)
                }
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: "info.circle").gmFont(14).foregroundColor(GM.primaryText).accessibilityHidden(true)
                    Text("Security alerts ignore quiet hours by design — a sign-in the account did not make is never held until morning.")
                        .gmFont(12)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.softFill))
                .padding(.top, 12)
            }
        } footer: {
            FooterNote(text: String(localized: "Preferences apply to this account on every signed-in device.", comment: "Footer note (notification preferences screen)"))
        }
        .task(id: reload) { await load() }
        .onAppear {
            if didAppear { reload += 1 } else { didAppear = true }
        }
    }

    @ViewBuilder private func scopeRow(_ scope: Scope) -> some View {
        // At the accessibility sizes the switch moves under the text.
        GMAdaptiveStack(spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: scope.icon)
                    .gmFont(15, .medium, maxSize: 26)
                    .foregroundColor(GM.primaryText)
                    .accessibilityHidden(true)
                    .frame(width: tile, height: tile)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(GM.softFill))
                VStack(alignment: .leading, spacing: 2) {
                    Text(scope.title)
                        .gmFont(14, .semibold)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(scope.detail)
                        .gmFont(12)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            if saving == scope {
                ProgressView()
            } else {
                Toggle(scope.title, isOn: Binding(
                    get: { enabled[scope] ?? false },
                    set: { patch(scope, enabled: $0) }
                ))
                .labelsHidden()
                .tint(GM.primaryText)
                .accessibilityIdentifier("notif-pref-\(scope.rawValue)")
            }
        }
        .padding(.vertical, 8)
    }

    @ViewBuilder private var quietCard: some View {
        InfoCard {
            Toggle("Quiet hours", isOn: Binding(
                get: { hasQuietHours },
                set: { hasQuietHours = $0; saveMailQuiet() }
            ))
            .tint(GM.primaryText)
            if hasQuietHours {
                GMAdaptiveStack(spacing: 12, verticalAlignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("From")
                            .gmFont(12)
                            .foregroundColor(GM.textSecondary)
                        DatePicker("", selection: $quietStart, displayedComponents: .hourAndMinute)
                            .labelsHidden()
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Until")
                            .gmFont(12)
                            .foregroundColor(GM.textSecondary)
                        DatePicker("", selection: $quietEnd, displayedComponents: .hourAndMinute)
                            .labelsHidden()
                    }
                    if !dts.isAccessibilitySize { Spacer(minLength: 0) }
                }
                .onChange(of: quietStart) { _ in saveMailQuiet() }
                .onChange(of: quietEnd) { _ in saveMailQuiet() }
                Text("Mail alerts hold until the window ends. Security alerts do not.")
                    .gmFont(12)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
            }
        }
        .padding(.top, 12)
    }

    // ── reads and writes ─────────────────────────────────────────────────────

    private func load() async {
        guard let token = SessionStore.token else { loading = false; failed = true; return }
        loading = true
        failed = false
        do {
            let resp = try await ApiClient.getNotificationPrefs(token: token)
            // This screen edits the account-wide rows only (deviceKey
            // "account", eventType "" = the whole scope). A scope with NO row
            // is not "off": the server's documented model is "rows are
            // overrides, the built-in default is enabled", so the toggle reads
            // on until a row says otherwise — showing Off would invite the
            // person to re-enable something that already fires.
            var rows: [Scope: NotificationPrefDto] = [:]
            for row in resp.prefs where row.deviceKey == "account" && row.eventType.isEmpty {
                if let scope = Scope(rawValue: row.scope) { rows[scope] = row }
            }
            for scope in Scope.allCases {
                if let row = rows[scope] {
                    enabled[scope] = row.enabled
                    if scope == .mail {
                        if let start = row.quietStartMin, let end = row.quietEndMin {
                            quietStart = Self.date(minutes: start)
                            quietEnd = Self.date(minutes: end)
                            hasQuietHours = true
                        } else {
                            hasQuietHours = false
                        }
                    }
                } else {
                    enabled[scope] = true
                }
            }
            loaded = true
        } catch {
            failed = true
            self.error = apiMessage(error, fallback: String(localized: "Could not load the preferences. Try again.", comment: "Error message shown when the server gives no reason (notification preferences screen)"))
        }
        loading = false
    }

    private func patch(_ scope: Scope, enabled value: Bool) {
        guard let token = SessionStore.token else { return }
        saving = scope
        error = nil
        let patch = NotificationPrefPatch(
            deviceKey: "account",
            scope: scope.rawValue,
            eventType: "",
            enabled: value,
            // The route rewrites quiet hours on every mail write, so the mail
            // toggle carries the hours it already holds — omitting them would
            // silently clear what the person set.
            quiet: scope == .mail && hasQuietHours
                ? .init(startMin: Self.minutes(quietStart), endMin: Self.minutes(quietEnd),
                        tzOffsetMin: Self.tzOffsetMin)
                : nil
        )
        Task {
            do {
                let resp = try await ApiClient.patchNotificationPrefs(token: token, patch: patch)
                if resp.success {
                    enabled[scope] = value
                } else {
                    self.error = String(localized: "The preference was not saved. Try again.", comment: "Message (notification preferences screen)")
                }
            } catch {
                self.error = apiMessage(error, fallback: String(localized: "The preference was not saved. Try again.", comment: "Error message shown when the server gives no reason (notification preferences screen)"))
            }
            saving = nil
        }
    }

    private func saveMailQuiet() {
        guard let token = SessionStore.token else { return }
        guard let on = enabled[.mail] else { return }
        saving = .mail
        error = nil
        let patch = NotificationPrefPatch(
            deviceKey: "account",
            scope: Scope.mail.rawValue,
            eventType: "",
            enabled: on,
            quiet: hasQuietHours
                ? .init(startMin: Self.minutes(quietStart), endMin: Self.minutes(quietEnd), tzOffsetMin: Self.tzOffsetMin)
                : nil,
            clearQuiet: !hasQuietHours
        )
        Task {
            do {
                _ = try await ApiClient.patchNotificationPrefs(token: token, patch: patch)
            } catch {
                self.error = apiMessage(error, fallback: String(localized: "Quiet hours were not saved. Try again.", comment: "Error message shown when the server gives no reason (notification preferences screen)"))
            }
            saving = nil
        }
    }

    /// Minutes since local midnight — the wire form the quiet window is stored
    /// in — and back. The device's own offset rides along so the server can
    /// reason about "morning" the way the person means it.
    private static func minutes(_ date: Date) -> Int {
        let components = Calendar.current.dateComponents([.hour, .minute], from: date)
        return (components.hour ?? 0) * 60 + (components.minute ?? 0)
    }

    private static func date(minutes: Int) -> Date {
        Calendar.current.date(from: DateComponents(hour: minutes / 60, minute: minutes % 60)) ?? Date()
    }

    private static var tzOffsetMin: Int { TimeZone.current.secondsFromGMT(for: Date()) / 60 }
}

// ── Trusted-device recovery ──────────────────────────────────────────────────

/// "Use a trusted device instead": the Forgot Password arc's signature path.
/// The device proves custody by signing a fresh nonce with its enrolled key
/// (DeviceKeyManager), the server answers with a one-time grant, and the grant
/// buys exactly one password reset. No code is texted, nothing is emailed —
/// the key IS the second factor here.
struct TrustedDeviceRecoveryView: View {
    @ObservedObject var model: RecoveryModel
    let onBack: () -> Void
    let onResetDone: () -> Void

    private enum Stage { case proving, form }
    @State private var stage = Stage.proving
    @State private var grant: String?
    @State private var identifier = ""
    @State private var nonceExpiresAt: Date?
    @State private var error: String?
    @State private var proving = false
    @State private var busy = false

    // The password rules are the reset flow's rules, ASCII classes and all —
    // the same `passwordProblem` the grant path checks server-side.
    @State private var password = ""
    @State private var confirm = ""

    private let commonPasswords: Set<String> = [
        "password", "password1", "password123", "12345678", "123456789", "qwerty123",
        "letmein1", "iloveyou", "welcome1", "admin123", "grapmail", "grapmail1",
    ]
    private var hasLength: Bool { password.count >= 8 }
    private var hasLetter: Bool { password.contains { ($0 >= "A" && $0 <= "Z") || ($0 >= "a" && $0 <= "z") } }
    private var hasDigit: Bool { password.contains { $0 >= "0" && $0 <= "9" } }
    private var hasSpecial: Bool { password.contains { !(($0 >= "A" && $0 <= "Z") || ($0 >= "a" && $0 <= "z") || ($0 >= "0" && $0 <= "9")) } }
    private var isFresh: Bool { password.count >= 8 && !commonPasswords.contains(password.lowercased()) }
    private var matches: Bool { !confirm.isEmpty && confirm == password }
    private var ready: Bool { hasLength && hasLetter && hasDigit && hasSpecial && isFresh && matches }

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: stage == .proving ? "ill_sec_hub" : "ill_account_new_password",
            illustrationWidth: stage == .proving ? 150 : 150,
            eyebrow: String(localized: "Forgot password", comment: "Small label above the screen title (trusted device recovery screen)"),
            title: stage == .proving ? String(localized: "Verify with this device", comment: "Screen title (trusted device recovery screen)") : String(localized: "Set a new password", comment: "Screen title (trusted device recovery screen)"),
            subtitle: stage == .proving
                ? String(localized: "This device holds a key your account trusts. We'll use it to verify you — no code needed.", comment: "Screen description under the title (trusted device recovery screen)")
                : String(localized: "Your device is verified. Choose a new password for your account.", comment: "Screen description under the title (trusted device recovery screen)")
        ) {
            switch stage {
            case .proving: provingContent
            case .form: formContent
            }
        } footer: {
            VStack(spacing: 4) {
                if stage == .form {
                    GradientButton(
                        title: String(localized: "Reset Password", comment: "Button title (trusted device recovery screen)"),
                        enabled: ready,
                        busy: busy,
                        trailing: AnyView(
                            Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                                .gmFont(15, .semibold, maxSize: 24)
                                .foregroundColor(.white)
                        )
                    ) { submit() }
                }
                if let error {
                    Text(error).gmFont(.caption).foregroundColor(GM.error).padding(.top, 10)
                }
                if stage == .proving || stage == .form {
                    AccountLink(title: String(localized: "Use the code instead", comment: "Link-style button (trusted device recovery screen)"), action: onBack)
                }
                FooterNote(text: String(localized: "The device key proves it's you. The new password still follows every rule the reset flow checks.", comment: "Footer note (trusted device recovery screen)"),
                           divider: false)
                    .padding(.top, 12)
                Spacer().frame(height: 4)
            }
        }
        .task { await prove() }
    }

    @ViewBuilder private var provingContent: some View {
        InfoCard {
            HStack(spacing: 12) {
                IconTile(systemName: "checkmark.shield")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Signing with this device's key")
                        .gmFont(13, .semibold)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    (proving ? Text("One moment…") : Text("Ready to verify"))
                        .gmFont(12)
                        .foregroundColor(GM.textSecondary)
                }
                Spacer(minLength: 0)
                if proving { ProgressView() }
            }
        }
        if identifier.isEmpty {
            AppTextField(
                value: $identifier,
                hint: String(localized: "GrapMail address", comment: "Text field placeholder (trusted device recovery screen)"),
                keyboardType: .emailAddress,
                leadingIcon: "envelope",
                contentType: .username
            )
            .padding(.top, 12)
            GradientButton(title: String(localized: "Verify This Device", comment: "Button title (trusted device recovery screen)"), enabled: !identifier.isEmpty, busy: proving) {
                Task { await prove() }
            }
            .padding(.top, 12)
        }
        if let expires = nonceExpiresAt {
            CodeExpiry(expiresAt: expires)
                .padding(.top, 10)
        }
    }

    @ViewBuilder private var formContent: some View {
        // Round 8 — B: the account being reset, so the password manager
        // updates its saved password.
        if let username = identifier.nilIfBlank() {
            CredentialUsernameField(username: username)
        }
        AppTextField(value: $password, hint: String(localized: "New password", comment: "Text field placeholder (trusted device recovery screen)"), isPassword: true, leadingIcon: "lock", contentType: .newPassword)
        HStack(spacing: 6) {
            ForEach(0..<4, id: \.self) { i in
                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .fill(i < [hasLength, hasLetter, hasDigit, hasSpecial].filter { $0 }.count
                          ? GM.primarySolid : GM.stepInactive)
                    .frame(height: 5)
            }
        }
        .padding(.top, 12)
        AppTextField(value: $confirm, hint: String(localized: "Confirm new password", comment: "Text field placeholder (trusted device recovery screen)"), isPassword: true, leadingIcon: "lock", contentType: .newPassword)
            .padding(.top, 18)
        VStack(alignment: .leading, spacing: 0) {
            RuleLine(label: String(localized: "At least 8 characters", comment: "Password requirement (trusted device recovery screen)"), met: hasLength)
            RuleLine(label: String(localized: "Include a letter", comment: "Password requirement (trusted device recovery screen)"), met: hasLetter)
            RuleLine(label: String(localized: "Include a number", comment: "Password requirement (trusted device recovery screen)"), met: hasDigit)
            RuleLine(label: String(localized: "Include a special character", comment: "Password requirement (trusted device recovery screen)"), met: hasSpecial)
            RuleLine(label: String(localized: "Not commonly used", comment: "Password requirement (trusted device recovery screen)"), met: isFresh)
        }
        .padding(.top, 16)
        if !confirm.isEmpty && !matches {
            Text("Both passwords must match.")
                .gmFont(.caption)
                .foregroundColor(GM.error)
                .padding(.top, 8)
        }
    }

    /// Challenge → sign → verify. Every refusal is said in full: "Recovery
    /// proof was not accepted" is all the server sends, and inventing a more
    /// specific reason would tell an attacker which check failed.
    private func prove() async {
        guard !proving else { return }
        let target = identifier.trimmingCharacters(in: .whitespaces).isEmpty
            ? model.identifier
            : identifier.trimmingCharacters(in: .whitespaces)
        guard !target.isEmpty else {
            identifier = ""
            error = nil
            return
        }
        // One address for the whole arc: the field, the grant, and the reset
        // must name the same account, so the resolved target is committed here
        // rather than re-derived at submit time.
        identifier = target
        proving = true
        error = nil
        do {
            let challenge = try await ApiClient.deviceChallenge(identifier: target)
            guard challenge.success, let challengeId = challenge.challengeId.nilIfBlank(),
                  let nonce = challenge.nonce.nilIfBlank() else {
                error = String(localized: "Recovery is not available for this account right now.", comment: "Message (trusted device recovery screen)")
                proving = false
                return
            }
            if let expiresAt = challenge.expiresAt.flatMap(parseIsoDate) { nonceExpiresAt = expiresAt }
            // The server hashed the nonce it sent; the signature must cover
            // exactly those raw bytes — no pre-hash, per the verify route.
            // Signing goes through the biometric gate: a cancelled prompt is
            // the gate's own sentence, not a broken key.
            guard let nonceBytes = Data(base64Encoded: nonce) else {
                error = String(localized: "Recovery is not available for this account right now.", comment: "Message (trusted device recovery screen)")
                proving = false
                return
            }
            let proof = await DeviceKeyManager.sign(nonceBytes)
            guard let signature = proof.signature?.base64EncodedString() else {
                error = proof.guardMessage ?? String(localized: "This device cannot prove custody of its key. Reset with a code instead.", comment: "Message (trusted device recovery screen)")
                proving = false
                return
            }
            let verified = try await ApiClient.deviceVerify(
                identifier: target, challengeId: challengeId, nonce: nonce,
                installationId: DeviceId.value, signature: signature)
            if verified.success, let freshGrant = verified.recoveryGrant.nilIfBlank() {
                grant = freshGrant
                model.identifier = target
                stage = .form
            } else {
                error = String(localized: "Recovery proof was not accepted. This device may not be trusted for that account.", comment: "Message (trusted device recovery screen)")
            }
        } catch {
            self.error = apiMessage(error, fallback: String(localized: "Could not verify this device. Try again.", comment: "Error message shown when the server gives no reason (trusted device recovery screen)"))
        }
        proving = false
    }

    private func submit() {
        guard let freshGrant = grant, !identifier.isEmpty else {
            error = String(localized: "That recovery grant is not valid. Start again.", comment: "Message (trusted device recovery screen)")
            stage = .proving
            return
        }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let resp = try await ApiClient.resetWithGrant(identifier: identifier, grant: freshGrant, password: password)
                guard resp.success else {
                    error = resp.message ?? String(localized: "That recovery grant is not valid.", comment: "Message (trusted device recovery screen)")
                    return
                }
                onResetDone()
            } catch {
                switch apiCode(error) {
                case "WEAK_PASSWORD": self.error = apiMessage(error, fallback: String(localized: "Use at least 8 characters with a letter, a number and a symbol.", comment: "Error message shown when the server gives no reason (trusted device recovery screen)"))
                default: self.error = apiMessage(error, fallback: String(localized: "We could not reset the password. Try again.", comment: "Error message shown when the server gives no reason (trusted device recovery screen)"))
                }
            }
        }
    }

    private func parseIsoDate(_ iso: String) -> Date? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = withFraction.date(from: iso) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: iso)
    }
}
